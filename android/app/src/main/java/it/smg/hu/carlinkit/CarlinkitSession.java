package it.smg.hu.carlinkit;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.view.MotionEvent;
import android.view.SurfaceView;

import it.smg.libs.carlinkit.AudioMessage;
import it.smg.libs.carlinkit.CarlinkitDriver;
import it.smg.libs.carlinkit.CarlinkitProtocol;
import it.smg.libs.carlinkit.SteeringWheelMapper;
import it.smg.libs.carlinkit.VideoMessage;
import it.smg.libs.common.Log;

/**
 * Carlinkit dongle projection session: wires the USB driver to the video decoder, the audio
 * player and the steering wheel controls of the Honda head unit.
 *
 * Typical use:
 * <pre>
 *   session = new CarlinkitSession(surfaceView, 20);
 *   session.start(usbDevice, usbConnection);
 *   // forward touches:                session.onTouch(event)
 *   // forward steering wheel buttons: session.onSteeringWheelKey(keyType)
 *   session.stop();
 * </pre>
 *
 * <p><b>Volume does not go through here.</b> The steering wheel +/- buttons are handled by
 * the head unit ({@code HondaConnectManager.increaseVolume()/decreaseVolume()}), which acts
 * on the amplifier. The Carlinkit protocol has no volume command.
 */
public final class CarlinkitSession implements CarlinkitDriver.Listener {

    private static final String TAG = "CarlinkitSession";

    public interface Callback {
        /** phoneType: 5 = AndroidAuto, 3 = CarPlay. */
        void onPhoneConnected(int phoneType);

        void onPhoneDisconnected();

        /** State reported by the dongle (scanningDevice, btConnected, wifiConnected...). */
        void onStatus(String status);

        /**
         * The phone asked for the microphone (voice assistant or phone call). The head unit
         * must enable the mic through the EcNc service before the capture starts.
         *
         * @return true if the head unit microphone was enabled
         */
        boolean onMicRequested(boolean start);
    }

    private volatile SurfaceView surfaceView;
    private final CarlinkitVideoRenderer video;
    private final CarlinkitAudioPlayer audio;
    private CarlinkitMicrophone mic;
    private Callback callback;

    private CarlinkitDriver driver;
    private volatile boolean surfaceReady;
    /**
     * True once anything at all arrives from the dongle.
     *
     * The dongle can enumerate, accept the Open command and then stay completely silent —
     * observed on 01/Aug across three consecutive launches, which looked identical to a
     * healthy start from the outside. Status commands are not a reliable liveness signal:
     * when the session is reopened while the phone is still connected the dongle sends no
     * status at all, yet works fine (measured: a session with zero status messages that
     * ran the voice assistant 35s later). Video and audio traffic is the honest signal.
     */
    private volatile boolean receivedData;
    /** Last day/night state reported by the head unit, applied once the session is ready. */
    private Boolean pendingNightMode;
    /**
     * True between PhonecallStart and PhonecallStop. Lets the PICKUP steering wheel button
     * toggle: accept when idle (a call is ringing), hang up when a call is active. The HR-V
     * wheel has a single phone button, so accept and reject must share it.
     */
    private volatile boolean phoneCallActive;

    public CarlinkitSession(int fps) {
        this.video = new CarlinkitVideoRenderer(fps);
        this.audio = new CarlinkitAudioPlayer();
    }

    /**
     * Attaches the Activity surface. Called when the screen appears; the dongle stays
     * connected when it goes away, so that no USB re-enumeration happens.
     */
    public void attachSurface(SurfaceView view) {
        this.surfaceView = view;
        video.setSurfaceView(view);
    }

    /** Detaches the surface and the audio, keeping the USB connection and heartbeat alive. */
    public void detachSurface() {
        video.stop();
        video.setSurfaceView(null);
        this.surfaceView = null;
        surfaceReady = false;
        audio.setMuted(true);
    }

    /** Size negotiated with the dongle in the Open command; does not change afterwards. */
    public boolean isStarted() {
        return driver != null;
    }

    /** @return true if the dongle has sent anything since the session opened */
    public boolean hasReceivedData() {
        return receivedData;
    }

    public void setCallback(Callback cb) {
        this.callback = cb;
    }

    /** @return true if the dongle was opened and the session started */
    public boolean start(UsbDevice device, UsbDeviceConnection connection) {
        if (!CarlinkitProtocol.isSupportedDevice(device.getVendorId(), device.getProductId())) {
            Log.e(TAG, "unsupported device: " + device.getVendorId()
                    + ":" + device.getProductId());
            return false;
        }
        CarlinkitDriver.Config cfg = new CarlinkitDriver.Config();
        // The resolution comes from the Surface: avoids hardcoding and adapts to any head unit
        int w = surfaceView != null ? surfaceView.getWidth() : 0;
        int h = surfaceView != null ? surfaceView.getHeight() : 0;
        if (w > 0 && h > 0) {
            cfg.width = w;
            cfg.height = h;
        }
        cfg.androidWorkMode = true;   // required for Android phones

        driver = CarlinkitDriver.open(device, connection, cfg, this);
        if (driver == null) {
            Log.e(TAG, "could not open the dongle");
            return false;
        }
        audio.start();
        mic = new CarlinkitMicrophone(driver);
        driver.start();
        if (pendingNightMode != null) {
            setNightMode(pendingNightMode.booleanValue());
            Log.i(TAG, "pending night mode applied: " + pendingNightMode);
            pendingNightMode = null;
        }
        Log.i(TAG, "session started (" + cfg.width + "x" + cfg.height + ")");
        return true;
    }

    public void stop() {
        if (mic != null) {
            mic.stop();
            mic = null;
        }
        if (driver != null) {
            driver.stop();
            driver = null;
        }
        video.stop();
        audio.stop();
        surfaceReady = false;
        Log.i(TAG, "session stopped");
    }

    /**
     * Must be called when the Surface becomes ready (surfaceCreated/surfaceChanged).
     * Creates the decoder and restores the stream parameters — the dongle does not resend
     * SPS/PPS, so we reinject them from the cache and request a fresh keyframe.
     */
    public void onSurfaceReady() {
        surfaceReady = true;
        audio.setMuted(false);
        if (!video.start()) {
            return;
        }
        if (driver != null) {
            byte[] params = driver.parameterSetCache().parameterSets();
            if (params != null) {
                video.reinjectParameterSets(params);
            }
            driver.requestKeyFrame();
        }
    }

    public void onSurfaceDestroyed() {
        surfaceReady = false;
        video.stop();
    }

    /**
     * True while the head unit has the screen (reverse camera). Feeding the native decoder
     * in that window is the prime suspect for the foreground deaths: OMX writing into a
     * Surface that was reclaimed by hardware, a SIGSEGV no Java handler catches.
     */
    private volatile boolean screenTakenByHeadUnit;

    /**
     * Called when the head unit takes the screen for itself (reverse camera) and when it
     * hands it back. Only the video pauses; audio keeps playing, matching what the native
     * sources do during a reverse manoeuvre.
     *
     * Resuming reuses the same sequence as returning from background: recreate the decoder,
     * reinject the cached SPS/PPS (the dongle never resends them) and request a keyframe.
     * All idempotent, so a false trigger costs one keyframe.
     */
    public void onScreenTakenByHeadUnit(boolean taken) {
        screenTakenByHeadUnit = taken;
        if (taken) {
            video.stop();
            Log.i(TAG, "video decoder paused: head unit took the screen");
        } else if (surfaceReady) {
            if (!video.start()) {
                Log.w(TAG, "video decoder did not restart after the screen came back");
                return;
            }
            if (driver != null) {
                byte[] params = driver.parameterSetCache().parameterSets();
                if (params != null) {
                    video.reinjectParameterSets(params);
                }
                driver.requestKeyFrame();
            }
            Log.i(TAG, "video decoder resumed: screen handed back");
        }
    }

    /** Forwards a SurfaceView touch to the phone. */
    public boolean onTouch(MotionEvent event) {
        if (driver == null) {
            return false;
        }
        int action;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                action = CarlinkitProtocol.TouchAction.DOWN;
                break;
            case MotionEvent.ACTION_MOVE:
                action = CarlinkitProtocol.TouchAction.MOVE;
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                action = CarlinkitProtocol.TouchAction.UP;
                break;
            default:
                return false;
        }
        if (surfaceView == null) {
            return false;
        }
        int w = surfaceView.getWidth();
        int h = surfaceView.getHeight();
        if (w <= 0 || h <= 0) {
            return false;
        }
        return driver.sendTouch(action, event.getX() / w, event.getY() / h);
    }

    /**
     * Forwards a steering wheel button. Called by
     * {@code HondaConnectManager.HondaListener.onSteeringWheelKey(int)}.
     *
     * Previous/next track, answer and end call, and voice assistant.
     * Volume does not arrive here — it is handled by the head unit.
     */
    public boolean onSteeringWheelKey(int hondaKeyType) {
        if (driver == null) {
            return false;
        }
        int cmd = SteeringWheelMapper.toCarlinkitCommand(hondaKeyType);
        if (cmd == -1) {
            Log.d(TAG, "unmapped steering wheel button: " + hondaKeyType);
            return false;
        }
        Log.i(TAG, "steering wheel " + SteeringWheelMapper.keyName(hondaKeyType)
                + " -> command " + cmd);
        return driver.sendCommand(cmd);
    }

    /**
     * Forwards day/night to the dongle. The head unit reports the change via
     * {@code HondaConnectManager.HondaListener.onDayNightUpdate}.
     */
    public boolean setNightMode(boolean isNight) {
        if (driver == null) {
            // The head unit reports day/night before the session exists; store it to apply later
            pendingNightMode = isNight;
            return false;
        }
        return driver.sendCommand(isNight
                ? CarlinkitProtocol.Command.ENABLE_NIGHT_MODE
                : CarlinkitProtocol.Command.DISABLE_NIGHT_MODE);
    }

    /** Sends a raw protocol command (used by the buttons that arrive as a KeyEvent). */
    public boolean sendCommand(int command) {
        return driver != null && driver.sendCommand(command);
    }

    // -------------------------------------------------- CarlinkitDriver.Listener

    @Override
    public void onVideoFrame(VideoMessage msg, byte[] payload, boolean isParameterSet) {
        receivedData = true;
        if (!surfaceReady) {
            return;   // with no Surface there is nowhere to draw; the cache keeps the SPS
        }
        if (screenTakenByHeadUnit) {
            return;   // reverse camera owns the screen; without this check the next frame
                      // would restart the decoder right after onScreenTakenByHeadUnit stopped it
        }
        if (!video.isRunning()) {
            video.start();
        }
        video.onFrame(msg, payload);
    }

    @Override
    public void onAudio(AudioMessage msg, byte[] payload) {
        receivedData = true;
        audio.onAudio(msg, payload);
        if (msg != null && msg.kind == AudioMessage.KIND_COMMAND) {
            handleMicCommand(msg.command);
        }
    }

    /**
     * The phone signals the start/end of microphone use through the AudioCommands.
     * SiriStart/PhonecallStart ask for capture; the matching Stop commands end it.
     * InputConfig merely announces the input format.
     */
    private void handleMicCommand(int command) {
        switch (command) {
            case AudioMessage.Command.PHONECALL_START:
                phoneCallActive = true;
                startMic(command);
                break;
            case AudioMessage.Command.SIRI_START:
                startMic(command);
                break;
            case AudioMessage.Command.PHONECALL_STOP:
                phoneCallActive = false;
                stopMic();
                break;
            case AudioMessage.Command.SIRI_STOP:
                stopMic();
                break;
            default:
                break;
        }
    }

    /** @return true while a phone call is in progress (between PhonecallStart and Stop) */
    public boolean isPhoneCallActive() {
        return phoneCallActive;
    }

    private void startMic(int command) {
        if (mic == null || mic.isRunning()) {
            return;
        }
        // The head unit must release the microphone (EcNc) before the capture
        boolean centralOk = callback == null || callback.onMicRequested(true);
        boolean started = mic.start();
        Log.i(TAG, "microphone requested (cmd " + command + "): head unit="
                + centralOk + " capture=" + started);
    }

    private void stopMic() {
        if (mic == null || !mic.isRunning()) {
            return;
        }
        mic.stop();
        if (callback != null) {
            callback.onMicRequested(false);
        }
        Log.i(TAG, "microphone stopped");
    }

    @Override
    public void onPhoneConnected(int phoneType) {
        String kind = phoneType == 5 ? "AndroidAuto" : phoneType == 3 ? "CarPlay" : String.valueOf(phoneType);
        Log.i(TAG, "phone connected: " + kind);
        if (callback != null) {
            callback.onPhoneConnected(phoneType);
        }
    }

    @Override
    public void onPhoneDisconnected() {
        Log.i(TAG, "phone disconnected");
        if (callback != null) {
            callback.onPhoneDisconnected();
        }
    }

    @Override
    public void onDongleCommand(int command) {
        String s = statusName(command);
        if (s != null) {
            Log.i(TAG, "dongle: " + s);
            if (callback != null) {
                callback.onStatus(s);
            }
        }
    }

    @Override
    public void onMessage(int type, byte[] payload) {
        receivedData = true;
        if (Log.isVerbose()) {
            Log.v(TAG, "msg " + CarlinkitProtocol.typeName(type));
        }
    }

    @Override
    public void onError(String message, Throwable cause) {
        Log.e(TAG, "driver: " + message, cause);
    }

    private static String statusName(int command) {
        switch (command) {
            case 1003: return "searching for phone";
            case 1004: return "phone found";
            case 1005: return "phone not found";
            case 1006: return "connection failed";
            case 1007: return "bluetooth connected";
            case 1008: return "bluetooth disconnected";
            case 1009: return "wifi connected";
            case 1010: return "wifi disconnected";
            case 1011: return "pairing started";
            default: return null;
        }
    }
}
