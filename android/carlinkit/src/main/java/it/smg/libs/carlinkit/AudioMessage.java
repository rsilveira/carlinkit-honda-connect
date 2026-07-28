package it.smg.libs.carlinkit;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Parses the payload of AUDIO_DATA (0x07) messages.
 *
 * Layout validated against the real dongle:
 * <pre>
 *   [0..3]   decodeType  uint32   index into the format table
 *   [4..7]   volume      float32  INFORMATIONAL — do NOT use as gain
 *   [8..11]  audioType   uint32
 *   [12..]   content, according to the remaining size:
 *              1 byte   -> AudioCommand (int8)
 *              4 bytes  -> volumeDuration (float32)
 *              > 4      -> PCM Int16 LE
 * </pre>
 *
 * <b>Beware of the volume field</b>: in the real capture it came through as 0.00 while the PCM
 * had normal amplitude (-15 to -23 dBFS). Applying it as a scale factor would mute the audio.
 */
public final class AudioMessage {

    /** Audio format corresponding to a decodeType. */
    public static final class Format {
        public final int sampleRate;
        public final int channels;
        public final int bitsPerSample;

        Format(int sampleRate, int channels, int bitsPerSample) {
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.bitsPerSample = bitsPerSample;
        }

        @Override
        public String toString() {
            return sampleRate + "Hz " + channels + "ch " + bitsPerSample + "bit";
        }
    }

    /** Audio commands (1-byte payload). */
    public static final class Command {
        public static final int OUTPUT_START = 1;
        public static final int OUTPUT_STOP = 2;
        public static final int INPUT_CONFIG = 3;
        public static final int PHONECALL_START = 4;
        public static final int PHONECALL_STOP = 5;
        public static final int NAVI_START = 6;
        public static final int NAVI_STOP = 7;
        public static final int SIRI_START = 8;
        public static final int SIRI_STOP = 9;
        public static final int MEDIA_START = 10;
        public static final int MEDIA_STOP = 11;
        public static final int ALERT_START = 12;
        public static final int ALERT_STOP = 13;

        private Command() {
        }

        public static String name(int cmd) {
            switch (cmd) {
                case OUTPUT_START: return "OutputStart";
                case OUTPUT_STOP: return "OutputStop";
                case INPUT_CONFIG: return "InputConfig";
                case PHONECALL_START: return "PhonecallStart";
                case PHONECALL_STOP: return "PhonecallStop";
                case NAVI_START: return "NaviStart";
                case NAVI_STOP: return "NaviStop";
                case SIRI_START: return "SiriStart";
                case SIRI_STOP: return "SiriStop";
                case MEDIA_START: return "MediaStart";
                case MEDIA_STOP: return "MediaStop";
                case ALERT_START: return "AlertStart";
                case ALERT_STOP: return "AlertStop";
                default: return "Unknown(" + cmd + ")";
            }
        }
    }

    public static final int HEADER_SIZE = 12;

    /** Content kind of the message. */
    public static final int KIND_COMMAND = 0;
    public static final int KIND_VOLUME_DURATION = 1;
    public static final int KIND_PCM = 2;

    public final int decodeType;
    public final float volume;
    public final int audioType;
    public final int kind;

    /** Valid when kind == KIND_COMMAND. */
    public final int command;
    /** Valid when kind == KIND_VOLUME_DURATION. */
    public final float volumeDuration;
    /** Offset and size of the PCM data within the original payload (kind == KIND_PCM). */
    public final int pcmOffset;
    public final int pcmLength;

    private AudioMessage(int decodeType, float volume, int audioType, int kind,
                         int command, float volumeDuration, int pcmOffset, int pcmLength) {
        this.decodeType = decodeType;
        this.volume = volume;
        this.audioType = audioType;
        this.kind = kind;
        this.command = command;
        this.volumeDuration = volumeDuration;
        this.pcmOffset = pcmOffset;
        this.pcmLength = pcmLength;
    }

    /**
     * @return the format matching the decodeType, or null if unknown
     */
    public static Format formatOf(int decodeType) {
        switch (decodeType) {
            case 1:
            case 2: return new Format(44100, 2, 16);
            case 3: return new Format(8000, 1, 16);
            case 4: return new Format(48000, 2, 16);   // observed with Android Auto
            case 5: return new Format(16000, 1, 16);
            case 6: return new Format(24000, 1, 16);
            case 7: return new Format(16000, 2, 16);
            default: return null;
        }
    }

    public Format format() {
        return formatOf(decodeType);
    }

    /**
     * Parses the payload of an AUDIO_DATA message.
     *
     * @return the parsed message, or null if the payload is smaller than the header
     */
    public static AudioMessage parse(byte[] payload, int length) {
        if (payload == null || length < HEADER_SIZE) {
            return null;
        }
        ByteBuffer bb = ByteBuffer.wrap(payload, 0, length);
        bb.order(ByteOrder.LITTLE_ENDIAN);
        int decodeType = bb.getInt();
        float volume = bb.getFloat();
        int audioType = bb.getInt();

        int remaining = length - HEADER_SIZE;
        if (remaining == 1) {
            return new AudioMessage(decodeType, volume, audioType, KIND_COMMAND,
                    payload[HEADER_SIZE], 0f, 0, 0);
        }
        if (remaining == 4) {
            return new AudioMessage(decodeType, volume, audioType, KIND_VOLUME_DURATION,
                    0, bb.getFloat(), 0, 0);
        }
        return new AudioMessage(decodeType, volume, audioType, KIND_PCM,
                0, 0f, HEADER_SIZE, remaining);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Audio[d=").append(decodeType)
                .append(" a=").append(audioType);
        Format f = format();
        if (f != null) {
            sb.append(' ').append(f);
        }
        switch (kind) {
            case KIND_COMMAND:
                sb.append(" cmd=").append(Command.name(command));
                break;
            case KIND_VOLUME_DURATION:
                sb.append(" volDur=").append(volumeDuration);
                break;
            default:
                sb.append(" pcm=").append(pcmLength).append("B");
                break;
        }
        return sb.append(']').toString();
    }
}
