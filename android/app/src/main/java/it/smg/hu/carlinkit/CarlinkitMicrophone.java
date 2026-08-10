package it.smg.hu.carlinkit;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

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

    /** decodeType 5 = 16000 Hz, 1 channel. */
    private static final int SAMPLE_RATE = 16000;
    /** How many samples to send per message: 20 ms of audio. */
    private static final int CHUNK_SAMPLES = SAMPLE_RATE / 50;

    private final CarlinkitDriver driver;
    private AudioRecord record;
    private Thread thread;
    private volatile boolean running;
    private long bytesSent;
    /** Which AudioSource the device accepted; the fallback path matters for diagnosis. */
    private String sourceName = "?";
    private long chunks;
    /** Largest absolute sample seen in the session: 0 means the mic was never routed here. */
    private int peakAll;
    private long silentChunks;

    public CarlinkitMicrophone(CarlinkitDriver driver) {
        this.driver = driver;
    }

    public synchronized boolean isRunning() {
        return running;
    }

    /** @return true if the capture was started */
    public synchronized boolean start() {
        if (running) {
            return true;
        }
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) {
            CarlinkitFileLog.log(TAG, "mic: invalid getMinBufferSize: " + minBuf);
            return false;
        }
        // Generous buffer: the head unit has a modest CPU and an overrun loses part of the phrase
        int bufSize = Math.max(minBuf * 4, CHUNK_SAMPLES * 2 * 8);

        try {
            // VOICE_RECOGNITION applies less processing than the default, which is what we
            // want here because the head unit EcNc already handles echo and noise.
            record = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufSize);
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                CarlinkitFileLog.log(TAG, "mic: VOICE_RECOGNITION did not initialize, trying MIC");
                releaseRecord();
                record = new AudioRecord(MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, bufSize);
                if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                    CarlinkitFileLog.log(TAG, "mic: AudioRecord did NOT initialize with either source");
                    releaseRecord();
                    return false;
                }
                sourceName = "MIC";
            } else {
                sourceName = "VOICE_RECOGNITION";
            }
            record.startRecording();
            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                CarlinkitFileLog.log(TAG, "mic: startRecording did NOT put the record in RECORDING state"
                        + " (state=" + record.getRecordingState() + ")");
            }
        } catch (Throwable t) {
            CarlinkitFileLog.log(TAG, "mic: failed to start the capture", t);
            releaseRecord();
            return false;
        }

        running = true;
        bytesSent = 0;
        chunks = 0;
        peakAll = 0;
        silentChunks = 0;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                captureLoop();
            }
        }, "CarlinkitMic");
        thread.setPriority(Thread.MAX_PRIORITY - 1);
        thread.start();
        CarlinkitFileLog.log(TAG, "mic: capture started, source=" + sourceName
                + " " + SAMPLE_RATE + "Hz mono buffer=" + bufSize + "B");
        return true;
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
        byte[] buf = new byte[CHUNK_SAMPLES * 2];
        long sendFailures = 0;
        while (running) {
            int n;
            try {
                n = record.read(buf, 0, buf.length);
            } catch (Throwable t) {
                CarlinkitFileLog.log(TAG, "mic: error reading the microphone", t);
                break;
            }
            if (n <= 0) {
                if (n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_BAD_VALUE) {
                    CarlinkitFileLog.log(TAG, "mic: AudioRecord.read returned " + n);
                    break;
                }
                continue;
            }
            chunks++;
            int peak = peakOf(buf, n);
            if (peak > peakAll) {
                peakAll = peak;
            }
            if (peak == 0) {
                silentChunks++;
            }
            // Chunks are 20ms, so 50 of them make a second. Log the first three (immediate
            // answer on whether anything arrives at all) and then once per second.
            if (chunks <= 3 || chunks % 50 == 0) {
                logAmplitude(buf, n, peak);
            }
            if (!driver.sendMicAudio(buf, n)) {
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
        // Verdict in a single line, which is what matters when reading the log afterwards
        String veredito;
        if (chunks == 0) {
            veredito = "NOTHING was read from the device";
        } else if (peakAll == 0) {
            veredito = "ALL chunks were digital silence: the head unit did not route the mic here";
        } else if (peakAll < 150) {
            veredito = "only noise floor (peak " + peakAll + "), no usable voice";
        } else {
            veredito = "real audio captured (peak " + peakAll + ")";
        }
        CarlinkitFileLog.log(TAG, "mic: capture stopped. " + veredito
                + " | chunks=" + chunks + " silent=" + silentChunks
                + " bytesSent=" + bytesSent + " sendFailures=" + sendFailures);
    }

    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) {
            try {
                t.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
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
