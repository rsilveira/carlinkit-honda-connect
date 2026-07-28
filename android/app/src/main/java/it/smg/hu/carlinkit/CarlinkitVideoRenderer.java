package it.smg.hu.carlinkit;

import android.view.Surface;

import java.nio.ByteBuffer;

import it.smg.libs.carlinkit.VideoMessage;
import it.smg.libs.common.Log;
import it.smg.libs.omxvideocodec.OMXVideoCodec;

/**
 * Renders the Carlinkit dongle video using the native OMX decoder.
 *
 * Uses {@link OMXVideoCodec} directly (and not {@code OMXVideoOutput}) so as not to depend
 * on aasdk — Carlinkit has its own protocol.
 *
 * <p>Two things to watch out for, learned from validation on the hardware:
 *
 * <ol>
 *   <li><b>A direct ByteBuffer is mandatory.</b> The JNI uses {@code GetDirectBufferAddress};
 *       a heap buffer would return NULL. That is why the payload is copied into a reused
 *       {@code allocateDirect} buffer.</li>
 *   <li><b>SPS/PPS must be reinjected.</b> The dongle sends the parameters only once at the
 *       start of the stream. If the Surface is recreated, the new decoder would not have
 *       them — hence {@link #reinjectParameterSets(byte[])}.</li>
 * </ol>
 *
 * The {@code mediaDecode} of OMXVideoCodec already detects SPS on its own
 * ({@code buf.get(4) & 0x1f == 7}), which works because Carlinkit uses a 4-byte start code.
 */
public final class CarlinkitVideoRenderer {

    private static final String TAG = "CarlinkitVideo";

    private volatile Surface surface;
    private volatile int surfaceWidth;
    private volatile int surfaceHeight;
    private final int fps;

    private OMXVideoCodec codec;
    private ByteBuffer direct;
    private volatile boolean running;
    private long frames;

    public CarlinkitVideoRenderer(int fps) {
        this.fps = fps;
    }

    /** Creates the decoder and binds it to the Surface. Must be called with a valid Surface. */
    /**
     * Attaches (or detaches, with {@code null}) the drawing surface. The renderer lives in
     * the Service and outlives the Activity; the Surface is the only resource that belongs
     * to the UI.
     */
    public synchronized void setSurface(Surface surface, int width, int height) {
        this.surface = surface;
        this.surfaceWidth = width;
        this.surfaceHeight = height;
    }

    public synchronized boolean hasSurface() {
        return surface != null && surface.isValid() && surfaceWidth > 0 && surfaceHeight > 0;
    }

    public synchronized boolean start() {
        if (!hasSurface()) {
            Log.w(TAG, "start with no Surface — ignored");
            return false;
        }
        if (running) {
            return true;
        }
        try {
            codec = new OMXVideoCodec(fps);
            codec.setSurface(surface, surfaceWidth, surfaceHeight);
            if (!codec.init()) {
                Log.e(TAG, "OMXVideoCodec.init() failed");
                codec = null;
                return false;
            }
            running = true;
            frames = 0;
            Log.i(TAG, "decoder started (" + surfaceWidth + "x" + surfaceHeight
                    + " @" + fps + "fps)");
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "failed to start the decoder", t);
            codec = null;
            return false;
        }
    }

    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        try {
            if (codec != null) {
                codec.shutdown();
            }
        } catch (Throwable t) {
            Log.e(TAG, "error shutting down the decoder", t);
        }
        codec = null;
        Log.i(TAG, "decoder stopped after " + frames + " frames");
    }

    public boolean isRunning() {
        return running;
    }

    /** Hands a dongle frame to the decoder. */
    public synchronized void onFrame(VideoMessage msg, byte[] payload) {
        if (!running || codec == null || msg == null) {
            return;
        }
        try {
            ByteBuffer buf = ensureCapacity(msg.dataLength);
            buf.clear();
            buf.put(payload, msg.dataOffset, msg.dataLength);
            buf.flip();
            // timestamp in microseconds; the dongle does not send its own PTS
            codec.mediaDecode(System.nanoTime() / 1000L, buf, msg.dataLength);
            frames++;
        } catch (Throwable t) {
            Log.e(TAG, "error decoding frame", t);
        }
    }

    /**
     * Reinjects the cached SPS/PPS. Needed when the decoder is recreated, since the dongle
     * does not resend the stream parameters.
     */
    public synchronized void reinjectParameterSets(byte[] parameterSets) {
        if (!running || codec == null || parameterSets == null || parameterSets.length < 5) {
            return;
        }
        try {
            ByteBuffer buf = ensureCapacity(parameterSets.length);
            buf.clear();
            buf.put(parameterSets);
            buf.flip();
            codec.mediaDecode(System.nanoTime() / 1000L, buf, parameterSets.length);
            Log.i(TAG, "SPS/PPS reinjected (" + parameterSets.length + " bytes)");
        } catch (Throwable t) {
            Log.e(TAG, "error reinjecting SPS/PPS", t);
        }
    }

    /** The JNI requires a direct buffer; it is reused and grows on demand. */
    private ByteBuffer ensureCapacity(int needed) {
        if (direct == null || direct.capacity() < needed) {
            direct = ByteBuffer.allocateDirect(Math.max(needed, 64 * 1024));
        }
        return direct;
    }

    public long framesRendered() {
        return frames;
    }
}
