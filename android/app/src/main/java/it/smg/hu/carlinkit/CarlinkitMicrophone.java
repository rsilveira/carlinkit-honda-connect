package it.smg.hu.carlinkit;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import it.smg.libs.carlinkit.CarlinkitDriver;
import it.smg.libs.common.Log;

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
            Log.e(TAG, "invalid getMinBufferSize: " + minBuf);
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
                Log.e(TAG, "AudioRecord did not initialize with VOICE_RECOGNITION, trying MIC");
                releaseRecord();
                record = new AudioRecord(MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, bufSize);
                if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord did not initialize");
                    releaseRecord();
                    return false;
                }
            }
            record.startRecording();
        } catch (Throwable t) {
            Log.e(TAG, "failed to start the capture", t);
            releaseRecord();
            return false;
        }

        running = true;
        bytesSent = 0;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                captureLoop();
            }
        }, "CarlinkitMic");
        thread.setPriority(Thread.MAX_PRIORITY - 1);
        thread.start();
        Log.i(TAG, "capture started (" + SAMPLE_RATE + "Hz mono, buffer " + bufSize + "B)");
        return true;
    }

    private void captureLoop() {
        byte[] buf = new byte[CHUNK_SAMPLES * 2];
        while (running) {
            int n;
            try {
                n = record.read(buf, 0, buf.length);
            } catch (Throwable t) {
                Log.e(TAG, "error reading the microphone", t);
                break;
            }
            if (n <= 0) {
                if (n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_BAD_VALUE) {
                    Log.e(TAG, "AudioRecord.read returned " + n);
                    break;
                }
                continue;
            }
            if (!driver.sendMicAudio(buf, n)) {
                // A send failure usually means the dongle is restarting; it is not worth
                // stopping the capture for that, the driver reopens the device.
                continue;
            }
            bytesSent += n;
        }
        Log.i(TAG, "capture stopped (" + bytesSent + " bytes sent)");
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
            Log.e(TAG, "error stopping the capture", t2);
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
