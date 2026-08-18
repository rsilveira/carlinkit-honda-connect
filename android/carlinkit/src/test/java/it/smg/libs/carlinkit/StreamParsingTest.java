package it.smg.libs.carlinkit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Tests using REAL data captured from the dongle (1314:1521, fw 2022.11.19.1218CHY)
 * during an Android Auto session — 4169 video frames and 1095 audio messages.
 */
public class StreamParsingTest {

    /** First 32 bytes of a real VIDEO_DATA message (metadata + start of the SPS). */
    private static final byte[] REAL_VIDEO_HEADER = {
            0x20, 0x03, 0x00, 0x00,        // width  = 800
            (byte) 0xe0, 0x01, 0x00, 0x00, // height = 480
            0x03, 0x00, 0x00, 0x00,        // flags  = 3
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x01,        // 4-byte start code
            0x67, 0x42, (byte) 0x80, 0x1f, // NAL 0x67 -> type 7 (SPS), Baseline, level 3.1
            (byte) 0xda, 0x03, 0x20, (byte) 0xf6
    };

    @Test
    public void parsesRealVideoMetadata() {
        VideoMessage m = VideoMessage.parse(REAL_VIDEO_HEADER, REAL_VIDEO_HEADER.length);
        assertNotNull(m);
        assertEquals(800, m.width);
        assertEquals(480, m.height);
        assertEquals(3, m.flags);
        assertEquals(20, m.dataOffset);          // metadata size confirmed: 20 bytes
        assertEquals(REAL_VIDEO_HEADER.length - 20, m.dataLength);
    }

    @Test
    public void detectsSpsInRealFrame() {
        VideoMessage m = VideoMessage.parse(REAL_VIDEO_HEADER, REAL_VIDEO_HEADER.length);
        int nal = VideoMessage.firstNalType(REAL_VIDEO_HEADER, m.dataOffset, m.dataLength);
        assertEquals(VideoMessage.NAL_SPS, nal);
    }

    @Test
    public void rejectsPayloadSmallerThanMetadata() {
        assertNull(VideoMessage.parse(new byte[20], 20));
        assertNull(VideoMessage.parse(null, 0));
    }

    /**
     * The cache must retain the SPS and allow reinjection — the dongle sends SPS/PPS
     * only ONCE in the whole stream.
     */
    @Test
    public void cachesParameterSetsForReinjection() {
        VideoMessage.ParameterSetCache cache = new VideoMessage.ParameterSetCache();
        assertFalse(cache.hasParameterSets());
        assertNull(cache.parameterSets());

        VideoMessage sps = VideoMessage.parse(REAL_VIDEO_HEADER, REAL_VIDEO_HEADER.length);
        assertTrue("a frame carrying SPS must be cached", cache.offer(sps, REAL_VIDEO_HEADER));
        assertTrue(cache.hasParameterSets());
        assertEquals(800, cache.width());
        assertEquals(480, cache.height());

        byte[] cached = cache.parameterSets();
        assertNotNull(cached);
        // Must start at the start code, not at the metadata
        assertArrayEquals(new byte[]{0x00, 0x00, 0x00, 0x01},
                new byte[]{cached[0], cached[1], cached[2], cached[3]});

        cache.clear();
        assertFalse(cache.hasParameterSets());
    }

    /** A P-slice (NAL 1) must not overwrite the parameter set cache. */
    @Test
    public void pSliceDoesNotOverwriteCache() {
        VideoMessage.ParameterSetCache cache = new VideoMessage.ParameterSetCache();
        VideoMessage sps = VideoMessage.parse(REAL_VIDEO_HEADER, REAL_VIDEO_HEADER.length);
        cache.offer(sps, REAL_VIDEO_HEADER);
        int lenBefore = cache.parameterSets().length;

        byte[] pFrame = REAL_VIDEO_HEADER.clone();
        pFrame[24] = 0x41;   // NAL type 1 (P-slice)
        VideoMessage pm = VideoMessage.parse(pFrame, pFrame.length);
        assertFalse("a P-slice must not be cached", cache.offer(pm, pFrame));
        assertEquals(lenBefore, cache.parameterSets().length);
    }

    // ------------------------------------------------------------------ audio

    private static byte[] audioPayload(int decodeType, float volume, int audioType, byte[] body) {
        ByteBuffer bb = ByteBuffer.allocate(12 + body.length);
        bb.order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(decodeType);
        bb.putFloat(volume);
        bb.putInt(audioType);
        bb.put(body);
        return bb.array();
    }

    /** Format observed in the real capture: decodeType 4, audioType 1, volume 0.00. */
    @Test
    public void parsesRealAudioFormat() {
        byte[] pcm = new byte[512];
        byte[] p = audioPayload(4, 0.0f, 1, pcm);
        AudioMessage m = AudioMessage.parse(p, p.length);
        assertNotNull(m);
        assertEquals(4, m.decodeType);
        assertEquals(1, m.audioType);
        assertEquals(AudioMessage.KIND_PCM, m.kind);
        assertEquals(12, m.pcmOffset);
        assertEquals(512, m.pcmLength);

        AudioMessage.Format f = m.format();
        assertNotNull(f);
        assertEquals(48000, f.sampleRate);
        assertEquals(2, f.channels);
        assertEquals(16, f.bitsPerSample);
    }

    /** Volume 0.00 was observed alongside PCM with normal amplitude: it is not a gain. */
    @Test
    public void volumeIsInformationalOnly() {
        byte[] p = audioPayload(4, 0.0f, 1, new byte[256]);
        AudioMessage m = AudioMessage.parse(p, p.length);
        assertEquals(0.0f, m.volume, 0.001f);
        // The parser exposes the PCM regardless of the reported volume
        assertEquals(256, m.pcmLength);
    }

    @Test
    public void parsesAudioCommand() {
        byte[] p = audioPayload(4, 0.0f, 1, new byte[]{AudioMessage.Command.MEDIA_START});
        AudioMessage m = AudioMessage.parse(p, p.length);
        assertEquals(AudioMessage.KIND_COMMAND, m.kind);
        assertEquals(AudioMessage.Command.MEDIA_START, m.command);
        assertEquals("MediaStart", AudioMessage.Command.name(m.command));
    }

    @Test
    public void parsesVolumeDuration() {
        ByteBuffer b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        b.putFloat(1.5f);
        byte[] p = audioPayload(4, 0.5f, 1, b.array());
        AudioMessage m = AudioMessage.parse(p, p.length);
        assertEquals(AudioMessage.KIND_VOLUME_DURATION, m.kind);
        assertEquals(1.5f, m.volumeDuration, 0.001f);
    }

    @Test
    public void mapsAllAudioFormats() {
        assertEquals(44100, AudioMessage.formatOf(1).sampleRate);
        assertEquals(44100, AudioMessage.formatOf(2).sampleRate);
        assertEquals(8000, AudioMessage.formatOf(3).sampleRate);
        assertEquals(48000, AudioMessage.formatOf(4).sampleRate);
        assertEquals(16000, AudioMessage.formatOf(5).sampleRate);
        assertEquals(24000, AudioMessage.formatOf(6).sampleRate);
        assertEquals(2, AudioMessage.formatOf(7).channels);
        assertNull(AudioMessage.formatOf(99));
    }

    /**
     * The channel count is what separates media from voice, and the microphone release in
     * CarlinkitSession depends on it. Media is stereo: 48000 Hz was measured with Android Auto
     * and 44100 Hz with an iPhone. Voice is mono, whether it is a call at 8000 Hz or the
     * assistant at 16000 Hz. A change here silently disables that release, which is how a guard
     * written against decodeType 4 alone turned into dead code the first time an iPhone
     * connected.
     */
    @Test
    public void separatesMediaFromVoiceByChannelCount() {
        for (int decodeType : new int[]{1, 2, 4, 7}) {
            assertEquals("decodeType " + decodeType + " should be media (stereo)",
                    2, AudioMessage.formatOf(decodeType).channels);
        }
        for (int decodeType : new int[]{3, 5, 6}) {
            assertEquals("decodeType " + decodeType + " should be voice (mono)",
                    1, AudioMessage.formatOf(decodeType).channels);
        }
    }

    @Test
    public void rejectsShortAudioPayload() {
        assertNull(AudioMessage.parse(new byte[8], 8));
        assertNull(AudioMessage.parse(null, 0));
    }

    // ------------------------------------------------- steering wheel

    @Test
    public void mapsSteeringWheelKeys() {
        assertEquals(CarlinkitProtocol.Command.NEXT,
                SteeringWheelMapper.toCarlinkitCommand(SteeringWheelMapper.HondaKey.TRACK_UP));
        assertEquals(CarlinkitProtocol.Command.NEXT,
                SteeringWheelMapper.toCarlinkitCommand(SteeringWheelMapper.HondaKey.TRACK_UP_ALT));
        assertEquals(CarlinkitProtocol.Command.PREV,
                SteeringWheelMapper.toCarlinkitCommand(SteeringWheelMapper.HondaKey.TRACK_DOWN));
        assertEquals(CarlinkitProtocol.Command.PREV,
                SteeringWheelMapper.toCarlinkitCommand(SteeringWheelMapper.HondaKey.TRACK_DOWN_ALT));
        assertEquals(CarlinkitProtocol.Command.ACCEPT_PHONE,
                SteeringWheelMapper.toCarlinkitCommand(SteeringWheelMapper.HondaKey.PICK_UP));
        assertEquals(CarlinkitProtocol.Command.REJECT_PHONE,
                SteeringWheelMapper.toCarlinkitCommand(SteeringWheelMapper.HondaKey.HANG_UP));
        assertEquals(CarlinkitProtocol.Command.SIRI,
                SteeringWheelMapper.toCarlinkitCommand(SteeringWheelMapper.HondaKey.TALK));
    }

    @Test
    public void returnsMinusOneForUnmappedKeys() {
        assertEquals(-1, SteeringWheelMapper.toCarlinkitCommand(99));
        assertEquals(-1, SteeringWheelMapper.toCarlinkitCommand(0));
    }

    @Test
    public void namesSteeringKeysForLogging() {
        assertEquals("TRACK_UP", SteeringWheelMapper.keyName(3));
        assertEquals("PICK_UP", SteeringWheelMapper.keyName(8));
        assertTrue(SteeringWheelMapper.keyName(77).startsWith("UNMAPPED"));
    }
}
