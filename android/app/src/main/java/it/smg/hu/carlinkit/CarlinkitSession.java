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
    /**
     * True once the dongle has told us which capture format to use. Until then the format is a
     * guess, and the log says so; after it, the announced value always wins.
     */
    private volatile boolean inputConfigSeen;

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

    /**
     * Video frames that arrived from the dongle, and frames the decoder actually consumed.
     *
     * The two counters exist because "the picture froze" was reported on 10/Aug with the app
     * otherwise alive: the microphone kept capturing and the heartbeat kept beating. Without
     * separating these numbers there is no way to tell whether the dongle stopped sending video
     * or the decoder stopped rendering it, and the fix differs completely.
     */
    public long videoFramesReceived() {
        return videoFramesReceived;
    }

    public long videoFramesRendered() {
        return video.framesRendered();
    }

    private volatile long videoFramesReceived;

    /** Connection requests issued while the phone stays away, for the heartbeat line. */
    public long connectRequests() {
        return driver == null ? -1 : driver.connectRequests();
    }

    public long connectRequestsFailed() {
        return driver == null ? -1 : driver.connectRequestsFailed();
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
     * True while the head unit has the screen (reverse camera). Frames that arrive in this
     * window are dropped instead of being decoded into a surface we no longer own.
     */
    private volatile boolean screenTakenByHeadUnit;

    /**
     * Called when the head unit takes the screen for itself (reverse camera) and when it hands
     * it back.
     *
     * ⚠️ This deliberately does NOT stop the decoder, which is the lesson from 10/Aug. The
     * first version called video.stop(), and that runs codec.shutdown() and therefore
     * nativeDelete() on the OMX decoder. The log ends 92 ms after that call:
     *
     *     12:38:47.010  focus lost
     *     12:38:47.102  focus lost -> video decoder paused (protection)
     *     (log ends, process dead)
     *
     * Tearing down a native decoder whose output surface was just reclaimed is at best useless
     * here and at worst the trigger itself, and a SIGSEGV in C++ never reaches a Java handler.
     *
     * Not feeding it achieves the same protection for free: frames keep arriving and are dropped
     * in onVideoFrame, the decoder stays allocated and valid, and coming back needs no
     * recreation, no SPS/PPS reinjection and no keyframe, just a flag flip. This works because
     * reverse gear does not destroy the Surface; a genuine surfaceDestroyed still goes through
     * onSurfaceDestroyed, which stops the decoder properly.
     */
    public void onScreenTakenByHeadUnit(boolean taken) {
        screenTakenByHeadUnit = taken;
        if (taken) {
            Log.i(TAG, "video: head unit took the screen, dropping frames"
                    + " (decoder intentionally left alive)");
            return;
        }
        // Back on screen: ask for a keyframe so the picture recovers at once instead of waiting
        // for the next natural one, but only if the decoder really is still there.
        if (surfaceReady && video.isRunning() && driver != null) {
            driver.requestKeyFrame();
        }
        Log.i(TAG, "video: screen handed back, resuming frames");
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
        videoFramesReceived++;
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
            handleMicCommand(msg.command, msg.decodeType);
        }
    }

    /**
     * The phone signals microphone use through the AudioCommands, and announces the format it
     * wants through InputConfig.
     *
     * ⚠️ InputConfig used to be discarded here, with a comment calling it a mere announcement.
     * It is the opposite of that: it is the only place the phone states which format the
     * capture must use, and ignoring it meant always sending decodeType 5 (16000 Hz). On
     * 10/Aug that produced a call where the capture was demonstrably healthy (peak 2986, zero
     * silent chunks, 1.3 MB sent, no send failures) and the far end still heard silence.
     */
    private void handleMicCommand(int command, int decodeType) {
        switch (command) {
            case AudioMessage.Command.INPUT_CONFIG:
                CarlinkitFileLog.log(TAG, "InputConfig: the phone asks for capture decodeType "
                        + decodeType);
                if (mic != null) {
                    mic.setDecodeType(decodeType);
                    inputConfigSeen = true;
                }
                break;
            case AudioMessage.Command.PHONECALL_START:
                phoneCallActive = true;
                if (!inputConfigSeen && mic != null) {
                    // No InputConfig arrived, so the format has to be guessed. Calls get
                    // decodeType 3 (8000 Hz mono), the telephony format of this protocol,
                    // because 16000 Hz is what failed in the car twice with a healthy capture.
                    // A real InputConfig always wins over this, and the log says which applied.
                    CarlinkitFileLog.log(TAG, "no InputConfig so far, assuming decodeType 3"
                            + " (8000Hz) for the call");
                    mic.setDecodeType(3);
                }
                startMic(command);
                break;
            case AudioMessage.Command.SIRI_START:
                if (!inputConfigSeen && mic != null) {
                    // The assistant works at 16000 Hz: on 10/Aug the wake word and the command
                    // were both understood with this format.
                    mic.setDecodeType(5);
                }
                startMic(command);
                break;
            case AudioMessage.Command.PHONECALL_STOP:
                phoneCallActive = false;
                stopMic();
                break;
            case AudioMessage.Command.SIRI_STOP:
                if (phoneCallActive) {
                    // The assistant can be invoked during a call; ending it must not take the
                    // microphone away from the call that is still running.
                    CarlinkitFileLog.log(TAG, "SiriStop during an active call, mic kept");
                    break;
                }
                stopMic();
                break;
            default:
                // Logged because the command set was mapped from captures, not documentation:
                // an unexpected value here is a protocol detail we do not know about yet.
                CarlinkitFileLog.log(TAG, "audio command " + AudioMessage.Command.name(command)
                        + " (" + command + ") decodeType=" + decodeType + ", not handled");
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
        boolean centralAsked = callback == null || callback.onMicRequested(true);
        boolean started = mic.start();
        // In the file log, not only logcat: the head unit exposes no adb, and on 10/Aug this
        // exact line was the one missing to explain a call where the far end heard nothing.
        //
        // "EcNc asked" and not "EcNc ok" on purpose: startMicSession() returns void and gives
        // up silently when the mic is disabled in settings or the service is not bound, so a
        // true here means the request was issued, not that the head unit honoured it. The
        // amplitude of the captured audio is the only honest evidence of that.
        CarlinkitFileLog.log(TAG, "mic requested (cmd " + command + " = "
                + (command == AudioMessage.Command.PHONECALL_START ? "PhonecallStart" : "SiriStart")
                + "): EcNc asked=" + centralAsked + " capture=" + started);
        if (!started && callback != null) {
            // Hand the microphone back. Leaving the EcNc session open with nobody capturing is
            // the worst of both worlds: the head unit holds the mic and the far end still hears
            // nothing, which is indistinguishable from the failure we are chasing.
            callback.onMicRequested(false);
            CarlinkitFileLog.log(TAG, "mic: capture did not start, EcNc session released");
        }
    }

    private void stopMic() {
        if (mic == null) {
            return;
        }
        boolean wasRunning = mic.isRunning();
        mic.stop();
        // Release EcNc even when the capture had already ended on its own (every source silent,
        // or a fatal read). Guarding this behind isRunning() used to leave the head unit holding
        // the microphone with nothing capturing.
        if (callback != null) {
            callback.onMicRequested(false);
        }
        CarlinkitFileLog.log(TAG, "mic stopped (was running=" + wasRunning + "), "
                + mic.bytesSent() + " bytes sent in total");
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
