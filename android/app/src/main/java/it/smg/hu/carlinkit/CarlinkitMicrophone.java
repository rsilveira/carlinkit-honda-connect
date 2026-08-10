package it.smg.hu.carlinkit;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import it.smg.libs.carlinkit.AudioMessage;
import it.smg.libs.carlinkit.CarlinkitDriver;

/**
 * Captures the microphone and sends it to the dongle, for the voice assistant and calls.
 *
 * <p>Format required by the protocol: <b>16000 Hz, mono, PCM 16 bit</b>, sent as an
 * {@code AUDIO_DATA} message with the header {@code [decodeType=5, volume=0.0f, audioType=3]}.
 * The value 5 corresponds to 16 kHz mono in the decodeType table.
 *
 * <p>Without this capture the assistant opens and hangs listening, because the phone waits
 * for the input audio and nothing arrives.
 *
 * <p>On the Honda head unit the microphone must be enabled through the EcNc service
 * ({@code HondaConnectManager.startMicSession()}), which turns on echo cancellation and
 * noise suppression. Without it the capture may come in empty or with echo from the unit's
 * own speaker. The one that makes that call is {@link CarlinkitSession}.
 */
public final class CarlinkitMicrophone {

    private static final String TAG = "CarlinkitMic";

    /**
     * Capture format, chosen by the dongle rather than fixed by us.
     *
     * It used to be hardcoded to decodeType 5 (16000 Hz mono). On 10/Aug that produced a call
     * where the microphone captured real speech (peak 2986, zero silent chunks, 1.3 MB sent
     * with no send failures) and the far end still heard nothing, which points at the format
     * rather than the capture. The protocol carries the answer in the InputConfig audio
     * command, whose decodeType announces the format the phone wants; the app used to parse
     * that command and discard it.
     */
    private int decodeType = 5;
    private int sampleRate = 16000;
    /** How many samples to send per message: 20 ms of audio at the current rate. */
    private int chunkSamples = 16000 / 50;

    /**
     * Audio sources to try, in order.
     *
     * VOICE_RECOGNITION comes first because it applies the least processing, which is what we
     * want when the head unit's EcNc already handles echo and noise. VOICE_COMMUNICATION is
     * second because it is the telephony source, and a call is precisely when the unit is most
     * likely to route the microphone somewhere else. MIC is the plain fallback.
     */
    private static final int[] SOURCES = new int[] {
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC,
    };

    /**
     * Chunks of digital silence tolerated before switching source. 50 chunks are 1 second.
     *
     * A source that opens successfully and delivers nothing is the failure observed in the car
     * on 10/Aug: the call had audible far-end audio and the local voice never arrived, while
     * the log showed the microphone being opened without complaint. Retrying at initialisation
     * would not have helped, because initialisation succeeded.
     */
    private static final int SILENT_CHUNKS_BEFORE_SWITCH = 50;

    private static String sourceLabel(int source) {
        switch (source) {
            case MediaRecorder.AudioSource.VOICE_RECOGNITION:
                return "VOICE_RECOGNITION";
            case MediaRecorder.AudioSource.VOICE_COMMUNICATION:
                return "VOICE_COMMUNICATION";
            case MediaRecorder.AudioSource.MIC:
                return "MIC";
            default:
                return "source " + source;
        }
    }

    private final CarlinkitDriver driver;
    /**
     * Volatile because the capture thread reads it outside the monitor, in the hot path, while
     * start() and the cleanup replace it under the monitor.
     */
    private volatile AudioRecord record;
    private Thread thread;
    /**
     * The thread that owns the device right now. Kept separate from `thread` (which stop()
     * nulls) so that a thread finishing late can tell whether it is still the owner before
     * clearing state or releasing the device.
     */
    private volatile Thread captureThread;
    private volatile boolean running;
    private long bytesSent;
    /** Which AudioSource the device accepted; the fallback path matters for diagnosis. */
    private String sourceName = "?";
    private long chunks;
    /** Largest absolute sample seen in the session: 0 means the mic was never routed here. */
    private int peakAll;
    private long silentChunks;
    /** Consecutive silent chunks, reset by any sound: drives the source switch. */
    private long consecutiveSilent;
    /** Index into SOURCES of the source currently open. */
    private int sourceIndex;
    private int bufSize;

    public CarlinkitMicrophone(CarlinkitDriver driver) {
        this.driver = driver;
    }

    /**
     * Sets the capture format from a protocol decodeType. Ignored while capturing, since the
     * AudioRecord would have to be recreated; the next start() picks it up.
     *
     * @return true if the format is known and was accepted
     */
    public synchronized boolean setDecodeType(int type) {
        AudioMessage.Format f = AudioMessage.formatOf(type);
        if (f == null || f.channels != 1) {
            // Only mono formats make sense for a microphone, and an unknown type would break
            // the AudioRecord constructor.
            CarlinkitFileLog.log(TAG, "mic: ignoring unusable capture decodeType " + type);
            return false;
        }
        if (running) {
            CarlinkitFileLog.log(TAG, "mic: decodeType " + type
                    + " will apply on the next capture (one is running)");
            this.decodeType = type;
            return true;
        }
        this.decodeType = type;
        this.sampleRate = f.sampleRate;
        this.chunkSamples = f.sampleRate / 50;
        CarlinkitFileLog.log(TAG, "mic: capture format set to decodeType " + type
                + " (" + f.sampleRate + "Hz mono)");
        return true;
    }

    public synchronized boolean isRunning() {
        return running;
    }

    /** @return true if the capture was started */
    public boolean start() {
        // The wait for a previous thread happens OUTSIDE the monitor, on purpose. That thread
        // needs this same monitor to run its finally block, so joining while holding it would
        // guarantee the timeout: the join would only ever succeed when it was not needed, and
        // start() would refuse every time a capture followed a recent stop (SiriStart right
        // after PhonecallStop lands exactly there).
        Thread previous;
        synchronized (this) {
            if (running) {
                return true;
            }
            previous = captureThread;
        }
        if (previous != null && previous.isAlive()) {
            try {
                previous.join(400);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            if (previous.isAlive()) {
                // Creating a second thread here is what turns a late cleanup into a crash,
                // because openSource() releases the AudioRecord the old thread still reads.
                CarlinkitFileLog.log(TAG, "mic: refusing to start, the previous capture thread"
                        + " is still running (it owns the device)");
                return false;
            }
        }
        return startLocked();
    }

    private synchronized boolean startLocked() {
        if (running) {
            return true;   // another thread got here first
        }
        sourceIndex = 0;
        if (!openSource(SOURCES[sourceIndex])) {
            // Initialisation itself failed; walk the rest of the list before giving up
            while (++sourceIndex < SOURCES.length) {
                if (openSource(SOURCES[sourceIndex])) {
                    break;
                }
            }
            if (sourceIndex >= SOURCES.length) {
                CarlinkitFileLog.log(TAG, "mic: no AudioSource initialized, capture unavailable");
                return false;
            }
        }

        running = true;
        bytesSent = 0;
        chunks = 0;
        peakAll = 0;
        silentChunks = 0;
        consecutiveSilent = 0;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                captureLoop();
            }
        }, "CarlinkitMic");
        captureThread = thread;
        thread.setPriority(Thread.MAX_PRIORITY - 1);
        try {
            thread.start();
        } catch (Throwable t) {
            // Without this the device would stay open in RECORDING with nobody to release it,
            // holding the head unit's EcNc session for the rest of the session.
            CarlinkitFileLog.log(TAG, "mic: could not start the capture thread", t);
            running = false;
            thread = null;
            captureThread = null;
            stopAndRelease();
            return false;
        }
        CarlinkitFileLog.log(TAG, "mic: capture started, source=" + sourceName
                + " " + sampleRate + "Hz mono (decodeType " + decodeType + ") buffer=" + bufSize + "B");
        return true;
    }

    /**
     * Creates and starts the AudioRecord on the given source.
     *
     * @return true if the device initialized and moved to RECORDING
     */
    private boolean openSource(int source) {
        int minBuf = AudioRecord.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) {
            CarlinkitFileLog.log(TAG, "mic: invalid getMinBufferSize: " + minBuf);
            return false;
        }
        // Generous buffer: the head unit has a modest CPU and an overrun loses part of the phrase
        bufSize = Math.max(minBuf * 4, chunkSamples * 2 * 8);
        try {
            releaseRecord();
            record = new AudioRecord(source, sampleRate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufSize);
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                CarlinkitFileLog.log(TAG, "mic: " + sourceLabel(source) + " did not initialize");
                releaseRecord();
                return false;
            }
            record.startRecording();
            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                CarlinkitFileLog.log(TAG, "mic: " + sourceLabel(source)
                        + " initialized but is not RECORDING (state="
                        + record.getRecordingState() + ")");
                releaseRecord();
                return false;
            }
            sourceName = sourceLabel(source);
            return true;
        } catch (Throwable t) {
            CarlinkitFileLog.log(TAG, "mic: failed to open " + sourceLabel(source), t);
            releaseRecord();
            return false;
        }
    }

    /**
     * Switches to the next source after persistent digital silence.
     *
     * Called from the capture thread, so it touches only `record`, which no other thread uses
     * while running is true.
     *
     * @return true if another source was opened
     */
    private boolean switchToNextSource() {
        if (!running) {
            return false;   // stop() came in while we were deciding to switch
        }
        CarlinkitFileLog.log(TAG, "mic: " + sourceLabel(SOURCES[sourceIndex])
                + " delivered " + consecutiveSilent + " silent chunks, switching source");
        while (++sourceIndex < SOURCES.length) {
            if (openSource(SOURCES[sourceIndex])) {
                CarlinkitFileLog.log(TAG, "mic: now capturing on "
                        + sourceLabel(SOURCES[sourceIndex]));
                consecutiveSilent = 0;
                return true;
            }
        }
        CarlinkitFileLog.log(TAG, "mic: every source delivered silence,"
                + " the head unit is not routing the microphone to this app");
        return false;
    }

    /**
     * Reports what the microphone actually captured, not merely that it was opened.
     *
     * Why the amplitude matters: on 10/Aug a call went through with the far end audible but
     * the local voice never arriving. The log recorded "EcNc session started" and nothing
     * more, because every message in this class went to logcat, which the head unit does not
     * expose. Opening the device successfully says nothing about whether samples carry sound:
     * if the head unit routes the microphone elsewhere, AudioRecord still reads happily and
     * returns silence.
     *
     * peak is the largest absolute sample in the chunk, on the Int16 scale (max 32767):
     *
     *   peak 0            the device delivers digital zero, the microphone is not routed here
     *   peak below ~150   only noise floor, nothing usable reaches the far end
     *   peak 2000+        real speech
     */
    private void logAmplitude(byte[] buf, int n, int peak) {
        double rms = 0;
        int samples = n / 2;
        for (int i = 0; i + 1 < n; i += 2) {
            int s = (short) ((buf[i + 1] << 8) | (buf[i] & 0xff));
            rms += (double) s * s;
        }
        rms = samples > 0 ? Math.sqrt(rms / samples) : 0;
        CarlinkitFileLog.log(TAG, "mic: chunk " + chunks + " peak=" + peak
                + " rms=" + (long) rms + (peak == 0 ? "  <-- DIGITAL SILENCE" : "")
                + " bytesSent=" + bytesSent);
    }

    private static int peakOf(byte[] buf, int n) {
        int peak = 0;
        for (int i = 0; i + 1 < n; i += 2) {
            int s = (short) ((buf[i + 1] << 8) | (buf[i] & 0xff));
            int a = s < 0 ? -s : s;
            if (a > peak) {
                peak = a;
            }
        }
        return peak;
    }

    private void captureLoop() {
        byte[] buf = new byte[chunkSamples * 2];
        long sendFailures = 0;
        try {
            while (running) {
                int n;
                try {
                    n = record.read(buf, 0, buf.length);
                } catch (Throwable t) {
                    CarlinkitFileLog.log(TAG, "mic: error reading the microphone", t);
                    break;
                }
                if (n <= 0) {
                    // Any negative return is fatal, not only the two documented constants:
                    // ERROR_DEAD_OBJECT (-6) also lands here, and treating it as "retry" spins
                    // this thread at near-max priority for the rest of the session.
                    if (n < 0) {
                        CarlinkitFileLog.log(TAG, "mic: AudioRecord.read returned " + n
                                + ", stopping the capture");
                        break;
                    }
                    continue;   // n == 0 is a legitimate empty read
                }
                chunks++;
                int peak = peakOf(buf, n);
                if (peak > peakAll) {
                    peakAll = peak;
                }
                if (peak == 0) {
                    silentChunks++;
                    consecutiveSilent++;
                    // Only switch while NOTHING has ever been heard on this source. Requiring
                    // peakAll == 0 is what separates "the head unit is not routing the mic here"
                    // from "nobody is talking right now": with EcNc noise suppression, real
                    // silence also arrives as digital zero, so without this guard a one-second
                    // pause in the conversation would drop a source that works.
                    if (peakAll == 0
                            && consecutiveSilent >= SILENT_CHUNKS_BEFORE_SWITCH
                            && sourceIndex + 1 < SOURCES.length) {
                        if (!switchToNextSource()) {
                            break;   // nothing left to try, the log already explains why
                        }
                        continue;
                    }
                } else {
                    consecutiveSilent = 0;
                }
                // Chunks are 20ms, so 50 of them make a second. Log the first three (immediate
                // answer on whether anything arrives at all) and then once per second.
                if (chunks <= 3 || chunks % 50 == 0) {
                    logAmplitude(buf, n, peak);
                }
                if (!driver.sendMicAudio(buf, n, decodeType)) {
                    // A send failure usually means the dongle is restarting; it is not worth
                    // stopping the capture for that, the driver reopens the device.
                    sendFailures++;
                    if (sendFailures == 1 || sendFailures % 100 == 0) {
                        CarlinkitFileLog.log(TAG, "mic: sendMicAudio failed " + sendFailures + "x");
                    }
                    continue;
                }
                bytesSent += n;
            }
        } finally {
            // Snapshot BEFORE releasing ownership: once captureThread is cleared, a new
            // capture may start and reset these counters, and the verdict line would mix two
            // sessions. On the head unit this log is the only diagnostic instrument there is.
            final long fChunks = chunks;
            final int fPeak = peakAll;
            final long fSilent = silentChunks;
            final long fBytes = bytesSent;
            final String fSource = sourceName;
            // Whatever ended the loop, the capture is over. Without clearing `running` here
            // the three break paths would leave it true: isRunning() would keep saying the
            // capture is alive, startMic() would decline to restart it, and the microphone
            // would stay dead for the rest of the session, reproducing the very symptom this
            // class exists to diagnose. Releasing the device matters too, since a record left
            // in RECORDING holds the head unit's EcNc session open.
            synchronized (CarlinkitMicrophone.this) {
                // Only the current owner cleans up. A thread that outlived its stop() must not
                // clear `running` nor release the device of a capture that started meanwhile.
                if (Thread.currentThread() == captureThread) {
                    running = false;
                    captureThread = null;
                    stopAndRelease();
                }
            }
            veredito(fChunks, fPeak, fSilent, fBytes, fSource, sendFailures);
        }
    }

    /** Verdict in a single line, which is what matters when reading the log afterwards. */
    private void veredito(long chunks, int peak, long silent, long bytes,
                          String source, long sendFailures) {
        String v;
        if (chunks == 0) {
            v = "NOTHING was read from the device";
        } else if (peak == 0) {
            v = "ALL chunks were digital silence: the head unit did not route the mic here";
        } else if (peak < 150) {
            v = "only noise floor (peak " + peak + "), no usable voice";
        } else {
            v = "real audio captured (peak " + peak + ")";
        }
        CarlinkitFileLog.log(TAG, "mic: capture stopped. " + v
                + " | source=" + source + " chunks=" + chunks + " silent=" + silent
                + " bytesSent=" + bytes + " sendFailures=" + sendFailures);
    }

    /**
     * Stops the capture.
     *
     * ⚠️ The AudioRecord is NOT released here, and that is deliberate. The capture thread is
     * the only owner of the device: it reads from it and, on a source switch, closes and
     * reopens it. Releasing it from this thread would risk a use-after-free in native code if
     * the join timed out while the capture was inside read() or openSource(), and on API 15
     * that means a SIGSEGV, not an exception.
     *
     * So this only signals the thread to finish and waits. The thread's own finally block does
     * the release. The wait is generous because it merely has to cover one read (20 ms) or one
     * source switch; if it ever expires, the thread still cleans up after itself, and the only
     * loss is that stop() returns before the device is free.
     */
    public void stop() {
        synchronized (this) {
            if (!running) {
                return;
            }
            running = false;
            thread = null;
        }
        // Deliberately does NOT wait. stopMic() is called from the driver's read thread (an
        // AudioCommand arrives and the session reacts inline), and blocking there stops the
        // app from draining the USB IN endpoint: video and audio stall and the dongle's buffer
        // backs up. Waiting is also unnecessary, because the capture thread clears its own
        // state and releases the device in its finally block, and start() refuses to run while
        // the previous thread is still alive.
    }

    /** Stops and releases the device. Callers must hold the monitor of this instance. */
    private void stopAndRelease() {
        try {
            if (record != null && record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop();
            }
        } catch (Throwable t2) {
            CarlinkitFileLog.log(TAG, "mic: error stopping the capture", t2);
        }
        releaseRecord();
    }

    private void releaseRecord() {
        if (record != null) {
            try {
                record.release();
            } catch (Throwable ignored) {
            }
            record = null;
        }
    }

    public long bytesSent() {
        return bytesSent;
    }
}
