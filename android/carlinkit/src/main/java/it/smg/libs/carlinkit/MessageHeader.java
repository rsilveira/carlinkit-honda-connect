package it.smg.libs.carlinkit;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 16-byte header of the Carlinkit dongle messages.
 *
 * Little-endian layout:
 *   [0..3]   magic     = 0x55AA55AA
 *   [4..7]   length    = size of the payload that follows
 *   [8..11]  type      = MessageType
 *   [12..15] typeCheck = (type ^ -1)
 *
 * Does not use java.nio.file nor any post-API 15 API: compatible with Android 4.0.3.
 */
public final class MessageHeader {

    public final int length;
    public final int type;

    public MessageHeader(int type, int length) {
        this.type = type;
        this.length = length;
    }

    /** Computes the check field from the type. */
    public static int typeCheck(int type) {
        return type ^ 0xFFFFFFFF;
    }

    /** Serializes the header for sending. */
    public static byte[] toBytes(int type, int payloadLength) {
        ByteBuffer bb = ByteBuffer.allocate(CarlinkitProtocol.HEADER_SIZE);
        bb.order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(CarlinkitProtocol.MAGIC);
        bb.putInt(payloadLength);
        bb.putInt(type);
        bb.putInt(typeCheck(type));
        return bb.array();
    }

    /**
     * Parses a received header.
     *
     * @throws IllegalArgumentException if the size, the magic or the typeCheck is invalid.
     */
    public static MessageHeader fromBytes(byte[] data, int offset, int len) {
        if (len != CarlinkitProtocol.HEADER_SIZE) {
            throw new IllegalArgumentException(
                    "header must be " + CarlinkitProtocol.HEADER_SIZE + " bytes, got " + len);
        }
        ByteBuffer bb = ByteBuffer.wrap(data, offset, len);
        bb.order(ByteOrder.LITTLE_ENDIAN);

        int magic = bb.getInt();
        if (magic != CarlinkitProtocol.MAGIC) {
            throw new IllegalArgumentException(
                    "invalid magic: 0x" + Integer.toHexString(magic));
        }
        int length = bb.getInt();
        int type = bb.getInt();
        int check = bb.getInt();
        if (check != typeCheck(type)) {
            throw new IllegalArgumentException("invalid typeCheck for type 0x"
                    + Integer.toHexString(type));
        }
        if (length < 0) {
            throw new IllegalArgumentException("negative length: " + length);
        }
        return new MessageHeader(type, length);
    }

    public boolean hasPayload() {
        return length > 0;
    }

    @Override
    public String toString() {
        return CarlinkitProtocol.typeName(type) + "[" + length + " bytes]";
    }
}
