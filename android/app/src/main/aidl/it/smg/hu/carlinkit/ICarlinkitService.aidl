package it.smg.hu.carlinkit;

import it.smg.hu.carlinkit.ICarlinkitCallback;
import android.view.Surface;
import android.hardware.usb.UsbDevice;

/**
 * Control interface for the Carlinkit service, which lives in the ":usb" process.
 *
 * Everything the UI needs to drive the session goes through here. The service owns the USB
 * connection, the protocol driver, the video decoder and the audio path; the Activity only
 * lends it a Surface.
 */
interface ICarlinkitService {

    /** True if the USB connection and the protocol session are already up. */
    boolean isStarted();

    /**
     * Opens the dongle if it is not open yet.
     *
     * The UsbDevice is passed from the UI process because that is where the permission dialog
     * is answered; the grant applies to the whole app (same UID), so the service can open it.
     */
    boolean ensureStarted(in UsbDevice device);

    void registerCallback(ICarlinkitCallback callback);
    void unregisterCallback();

    /**
     * Hands the drawing surface over. Surface is Parcelable, so it crosses the process
     * boundary — this is the same mechanism SurfaceView uses to talk to SurfaceFlinger.
     */
    void attachSurface(in Surface surface, int width, int height);

    /** Releases the surface and mutes audio, keeping USB and the heartbeat alive. */
    void detachSurface();

    /** Recreates the decoder, reinjects the cached SPS/PPS and asks for a keyframe. */
    void onSurfaceReady();

    /** Normalised coordinates (0..1). action: 14 down, 15 move, 16 up. */
    boolean sendTouch(int action, float xRatio, float yRatio);

    /** Raw protocol command (next/previous track, night mode, keyframe request...). */
    boolean sendCommand(int command);

    boolean setNightMode(boolean night);

    /** Honda key type, mapped to a dongle command by the service. */
    boolean sendSteeringWheelKey(int hondaKeyType);

    /** Stops the session and releases the dongle. */
    void shutdown();
}
