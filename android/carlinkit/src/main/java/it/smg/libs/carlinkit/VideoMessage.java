package it.smg.libs.carlinkit;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Parses VIDEO_DATA (0x06) and keeps an SPS/PPS cache.
 *
 * <p><b>Why the cache exists.</b> In the real capture of 4169 frames, SPS (NAL 7), PPS (NAL 8)
 * and the single IDR keyframe (NAL 5) appeared <b>once each</b>, at the start of the stream — the
 * other 4166 frames were P-slices. The dongle never resends the parameter sets.
 *
 * <p>Consequence: if the {@code Surface} is recreated (user leaves and returns to the app,
 * orientation change) or if the app starts while the dongle is already streaming, the decoder
 * will never receive SPS/PPS and will not be able to decode anything. This cache makes it
 * possible to reinject them. In addition, a fresh keyframe must be requested with
 * {@link CarlinkitProtocol.Command#FRAME}.
 *
 * <p>Payload layout (validated):
 * <pre>
 *   [0..3]   width   uint32   (800)
 *   [4..7]   height  uint32   (480)
 *   [8..11]  flags   uint32   (3)
 *   [12..15] ?       uint32   (0)
 *   [16..19] ?       uint32   (0)
 *   [20..]   H.264 Annex-B, 4-byte start code (00 00 00 01)
 * </pre>
 */
public final class VideoMessage {

    /** Size of the metadata header that precedes the H.264 data. */
    public static final int METADATA_SIZE = 20;

    /** Relevant NAL unit types. */
    public static final int NAL_P_SLICE = 1;
    public static final int NAL_IDR = 5;
    public static final int NAL_SEI = 6;
    public static final int NAL_SPS = 7;
    public static final int NAL_PPS = 8;

    public final int width;
    public final int height;
    public final int flags;
    /** Offset of the start of the H.264 data within the payload. */
    public final int dataOffset;
    public final int dataLength;

    private VideoMessage(int width, int height, int flags, int dataOffset, int dataLength) {
        this.width = width;
        this.height = height;
        this.flags = flags;
        this.dataOffset = dataOffset;
        this.dataLength = dataLength;
    }

    /**
     * @return the parsed message, or null if the payload is smaller than the metadata
     */
    public static VideoMessage parse(byte[] payload, int length) {
        if (payload == null || length <= METADATA_SIZE) {
            return null;
        }
        ByteBuffer bb = ByteBuffer.wrap(payload, 0, METADATA_SIZE);
        bb.order(ByteOrder.LITTLE_ENDIAN);
        int w = bb.getInt();
        int h = bb.getInt();
        int f = bb.getInt();
        return new VideoMessage(w, h, f, METADATA_SIZE, length - METADATA_SIZE);
    }

    /**
     * Type of the first NAL unit after the start code, or -1 if there is no start code.
     * Accepts 3-byte and 4-byte start codes.
     */
    public static int firstNalType(byte[] data, int offset, int length) {
        int end = offset + length;
        for (int i = offset; i < end - 4; i++) {
            if (data[i] == 0 && data[i + 1] == 0) {
                if (data[i + 2] == 1) {
                    return data[i + 3] & 0x1F;
                }
                if (data[i + 2] == 0 && data[i + 3] == 1 && i + 4 < end) {
                    return data[i + 4] & 0x1F;
                }
            }
        }
        return -1;
    }

    /**
     * Cache of the stream parameter sets. Keeps the raw sequence containing SPS/PPS so that
     * it can be reinjected into the decoder when needed.
     */
    public static final class ParameterSetCache {
        private byte[] parameterSets;
        private int width;
        private int height;

        /**
         * Records the payload if it contains SPS. Called for every received frame;
         * it only stores data when the parameter sets are found.
         *
         * @return true if this frame carried SPS (and was cached)
         */
        public synchronized boolean offer(VideoMessage msg, byte[] payload) {
            int nal = firstNalType(payload, msg.dataOffset, msg.dataLength);
            if (nal != NAL_SPS) {
                return false;
            }
            // Keep from the SPS to the end of the frame: usually SPS + PPS (+ IDR) together
            parameterSets = new byte[msg.dataLength];
            System.arraycopy(payload, msg.dataOffset, parameterSets, 0, msg.dataLength);
            width = msg.width;
            height = msg.height;
            return true;
        }

        public synchronized boolean hasParameterSets() {
            return parameterSets != null;
        }

        /** Copy of the cached SPS/PPS, for reinjection. Null if not received yet. */
        public synchronized byte[] parameterSets() {
            if (parameterSets == null) {
                return null;
            }
            byte[] copy = new byte[parameterSets.length];
            System.arraycopy(parameterSets, 0, copy, 0, parameterSets.length);
            return copy;
        }

        public synchronized int width() {
            return width;
        }

        public synchronized int height() {
            return height;
        }

        public synchronized void clear() {
            parameterSets = null;
        }
    }
}
