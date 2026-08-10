package it.smg.hu.carlinkit;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

import it.smg.libs.carlinkit.AudioMessage;
import it.smg.libs.common.Log;

/**
 * Plays back the PCM that comes from the Carlinkit dongle through {@link AudioTrack}.
 *
 * The dongle delivers raw (uncompressed) PCM, so there is no decoding — writing to the
 * AudioTrack is enough. During validation with Android Auto the format was
 * <b>48000 Hz, 2 channels, 16 bit</b> ({@code decodeType=4}).
 *
 * <p><b>The protocol {@code volume} field is not a gain.</b> In the real capture it came in
 * as 0.00 while the PCM had normal amplitude (-15 to -23 dBFS). Applying it as a scale factor
 * would mute the audio. Volume is controlled by the head unit (amplifier).
 *
 * <p>The format can change at runtime — navigation uses 16 kHz mono, phone calls 8 kHz mono —
 * which is why the AudioTrack is recreated whenever the {@code decodeType} changes.
 *
 * {@code AudioTrack} has existed since API 3, so it is compatible with Android 4.0.3.
 */
public final class CarlinkitAudioPlayer {

    private static final String TAG = "CarlinkitAudio";

    /**
     * One AudioTrack per decodeType, instead of one that gets recreated on every change.
     *
     * Measured on 10/Aug: during a call the dongle alternates decodeType 3 (8000 Hz, the voice)
     * and 4 (48000 Hz, the media) message by message, dozens of times per second. Recreating
     * the track each time meant a stop/release/new/play cycle at that rate, which is expensive
     * on this SoC and drops the first samples after every switch. Keeping both alive costs two
     * small buffers.
     */
    private final java.util.HashMap<Integer, AudioTrack> tracks =
            new java.util.HashMap<Integer, AudioTrack>();
    private AudioTrack track;
    private int currentDecodeType = -1;
    private volatile boolean running;
    /** In the background the dongle stays connected, but we must not play audio. */
    private volatile boolean muted;
    private long bytesWritten;

    public synchronized void start() {
        running = true;
    }

    /**
     * Mutes without destroying the AudioTrack. Used when the Activity goes off screen: the
     * USB connection is kept to avoid re-enumeration, but the audio has to stop so it does
     * not compete with the FM radio.
     */
    public synchronized void setMuted(boolean value) {
        muted = value;
        for (AudioTrack t : tracks.values()) {
            try {
                if (value) {
                    t.pause();
                    t.flush();
                } else {
                    t.play();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    public synchronized void stop() {
        running = false;
        releaseTrack();
        Log.i(TAG, "stopped (" + bytesWritten + " bytes played)");
    }

    private void releaseTrack() {
        for (AudioTrack t : tracks.values()) {
            try {
                if (t.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    t.stop();
                }
                t.release();
            } catch (Throwable e) {
                Log.e(TAG, "error releasing an AudioTrack", e);
            }
        }
        tracks.clear();
        track = null;
        currentDecodeType = -1;
    }

    /**
     * Ensures there is an AudioTrack compatible with the given format.
     *
     * @return true if there is a track ready to be written to
     */
    private boolean ensureTrack(int decodeType) {
        if (track != null && currentDecodeType == decodeType) {
            return true;
        }
        // Reuse the track for this format if it already exists. This is the whole point of the
        // map: switching format becomes a pointer swap instead of a stop/release/new/play cycle.
        AudioTrack existing = tracks.get(Integer.valueOf(decodeType));
        if (existing != null) {
            track = existing;
            currentDecodeType = decodeType;
            if (!muted) {
                try {
                    track.play();
                } catch (Throwable ignored) {
                }
            }
            return true;
        }
        AudioMessage.Format f = AudioMessage.formatOf(decodeType);
        if (f == null) {
            Log.e(TAG, "unknown decodeType: " + decodeType);
            return false;
        }

        int channelConfig = f.channels == 2
                ? AudioFormat.CHANNEL_OUT_STEREO
                : AudioFormat.CHANNEL_OUT_MONO;
        int encoding = AudioFormat.ENCODING_PCM_16BIT;

        int minBuf = AudioTrack.getMinBufferSize(f.sampleRate, channelConfig, encoding);
        if (minBuf <= 0) {
            Log.e(TAG, "invalid getMinBufferSize for " + f);
            return false;
        }
        // A buffer larger than the minimum reduces underruns on the head unit, which has a modest CPU
        int bufSize = minBuf * 4;

        try {
            AudioTrack fresh = new AudioTrack(AudioManager.STREAM_MUSIC, f.sampleRate,
                    channelConfig, encoding, bufSize, AudioTrack.MODE_STREAM);
            if (fresh.getState() != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack did not initialize for " + f);
                try {
                    fresh.release();
                } catch (Throwable ignored) {
                }
                return false;
            }
            if (!muted) {
                fresh.play();
            }
            tracks.put(Integer.valueOf(decodeType), fresh);
            track = fresh;
            currentDecodeType = decodeType;
            // In the file log too: the format the dongle SENDS is the best available clue to
            // the format it EXPECTS back from the microphone. On 10/Aug this line reported
            // 8000Hz during a call while the microphone was sending 16000Hz upstream, which is
            // why the far end heard nothing. The head unit exposes no logcat to check it any
            // other way. Logged once per format, not per switch.
            CarlinkitFileLog.log(TAG, "audio out: decodeType=" + decodeType + " -> " + f);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "failed to create the AudioTrack for " + f, t);
            return false;
        }
    }

    /** Handles an audio message from the dongle. */
    public synchronized void onAudio(AudioMessage msg, byte[] payload) {
        if (!running || msg == null) {
            return;
        }
        switch (msg.kind) {
            case AudioMessage.KIND_COMMAND:
                handleCommand(msg);
                break;
            case AudioMessage.KIND_PCM:
                if (muted) {
                    return;   // dropped in the background: no audio on top of the FM radio
                }
                if (ensureTrack(msg.decodeType)) {
                    try {
                        int n = track.write(payload, msg.pcmOffset, msg.pcmLength);
                        if (n > 0) {
                            bytesWritten += n;
                        } else if (n < 0) {
                            Log.e(TAG, "AudioTrack.write returned " + n);
                        }
                    } catch (Throwable t) {
                        Log.e(TAG, "error writing PCM", t);
                    }
                }
                break;
            default:
                // volumeDuration: informational
                break;
        }
    }

    private void handleCommand(AudioMessage msg) {
        String name = AudioMessage.Command.name(msg.command);
        Log.i(TAG, "AudioCommand " + name + " (decodeType=" + msg.decodeType
                + " audioType=" + msg.audioType + ")");
        switch (msg.command) {
            case AudioMessage.Command.OUTPUT_STOP:
            case AudioMessage.Command.MEDIA_STOP:
            case AudioMessage.Command.PHONECALL_STOP:
            case AudioMessage.Command.NAVI_STOP:
            case AudioMessage.Command.SIRI_STOP:
                // Flush the buffer so no leftover audio is left behind when the source changes
                if (track != null) {
                    try {
                        track.flush();
                    } catch (Throwable ignored) {
                    }
                }
                break;
            default:
                break;
        }
    }

    public long bytesWritten() {
        return bytesWritten;
    }
}
