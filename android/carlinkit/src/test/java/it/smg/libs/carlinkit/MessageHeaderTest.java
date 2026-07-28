package it.smg.libs.carlinkit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * Validates the protocol implementation against real values.
 *
 * The bytes used here reproduce messages actually exchanged with the dongle
 * (1314:1521, firmware 2022.11.19.1218CHY) during validation on a Linux PC.
 */
public class MessageHeaderTest {

    /** HeartBeat (0xAA) header with no payload, as sent to the dongle. */
    @Test
    public void buildsHeartbeatHeader() {
        byte[] h = MessageHeader.toBytes(CarlinkitProtocol.Type.HEARTBEAT, 0);
        assertEquals(16, h.length);
        // magic 0x55AA55AA little-endian
        assertArrayEquals(new byte[]{(byte) 0xAA, 0x55, (byte) 0xAA, 0x55},
                new byte[]{h[0], h[1], h[2], h[3]});
        // length = 0
        assertArrayEquals(new byte[]{0, 0, 0, 0}, new byte[]{h[4], h[5], h[6], h[7]});
        // type = 0xAA
        assertArrayEquals(new byte[]{(byte) 0xAA, 0, 0, 0},
                new byte[]{h[8], h[9], h[10], h[11]});
        // typeCheck = ~0xAA = 0xFFFFFF55
        assertArrayEquals(new byte[]{0x55, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF},
                new byte[]{h[12], h[13], h[14], h[15]});
    }

    /** SendOpen carries 28 payload bytes (7 x uint32). */
    @Test
    public void buildsOpenHeaderWithPayloadLength() {
        byte[] h = MessageHeader.toBytes(CarlinkitProtocol.Type.OPEN, 28);
        MessageHeader parsed = MessageHeader.fromBytes(h, 0, h.length);
        assertEquals(CarlinkitProtocol.Type.OPEN, parsed.type);
        assertEquals(28, parsed.length);
        assertTrue(parsed.hasPayload());
    }

    /** Round-trip for every type in use. */
    @Test
    public void roundTripsAllTypes() {
        int[] types = {
                CarlinkitProtocol.Type.OPEN, CarlinkitProtocol.Type.PLUGGED,
                CarlinkitProtocol.Type.TOUCH, CarlinkitProtocol.Type.VIDEO_DATA,
                CarlinkitProtocol.Type.AUDIO_DATA, CarlinkitProtocol.Type.COMMAND,
                CarlinkitProtocol.Type.BOX_SETTINGS, CarlinkitProtocol.Type.HEARTBEAT,
                CarlinkitProtocol.Type.SOFTWARE_VERSION, CarlinkitProtocol.Type.HICAR_LINK,
                CarlinkitProtocol.Type.MEDIA_DATA,
        };
        for (int type : types) {
            byte[] h = MessageHeader.toBytes(type, 123);
            MessageHeader p = MessageHeader.fromBytes(h, 0, h.length);
            assertEquals("type " + CarlinkitProtocol.typeName(type), type, p.type);
            assertEquals(123, p.length);
        }
    }

    /** typeCheck is the one's complement of the type. */
    @Test
    public void typeCheckIsOnesComplement() {
        assertEquals(0xFFFFFF55, MessageHeader.typeCheck(0xAA));
        assertEquals(0xFFFFFFFE, MessageHeader.typeCheck(0x01));
        assertEquals(0xFFFFFF33, MessageHeader.typeCheck(0xCC));
    }

    @Test
    public void rejectsBadMagic() {
        byte[] h = MessageHeader.toBytes(CarlinkitProtocol.Type.HEARTBEAT, 0);
        h[0] = 0x00; // corrupt the magic
        try {
            MessageHeader.fromBytes(h, 0, h.length);
            fail("should reject an invalid magic");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("magic"));
        }
    }

    @Test
    public void rejectsBadTypeCheck() {
        byte[] h = MessageHeader.toBytes(CarlinkitProtocol.Type.OPEN, 0);
        h[12] = 0x00; // corrupt the typeCheck
        try {
            MessageHeader.fromBytes(h, 0, h.length);
            fail("should reject an invalid typeCheck");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("typeCheck"));
        }
    }

    @Test
    public void rejectsWrongSize() {
        try {
            MessageHeader.fromBytes(new byte[8], 0, 8);
            fail("should reject a size other than 16");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("16"));
        }
    }

    /** Only the Autokit/CCPA family PIDs are accepted. */
    @Test
    public void recognisesSupportedDevices() {
        assertTrue(CarlinkitProtocol.isSupportedDevice(0x1314, 0x1521)); // the HR-V one
        assertTrue(CarlinkitProtocol.isSupportedDevice(0x1314, 0x1520));
        assertFalse(CarlinkitProtocol.isSupportedDevice(0x1314, 0x1234));
        assertFalse(CarlinkitProtocol.isSupportedDevice(0x0000, 0x1521));
    }

    @Test
    public void namesTypesForLogging() {
        assertEquals("HeartBeat", CarlinkitProtocol.typeName(0xAA));
        assertEquals("SoftwareVersion", CarlinkitProtocol.typeName(0xCC));
        assertEquals("VideoData", CarlinkitProtocol.typeName(0x06));
        assertTrue(CarlinkitProtocol.typeName(0x77).startsWith("Unknown"));
    }
}
