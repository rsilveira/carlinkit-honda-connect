package it.smg.hu.carlinkit;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.ComponentName;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.HashMap;

import it.smg.hu.R;
import it.smg.hu.config.Settings;
import it.smg.hu.manager.HondaConnectManager;
import it.smg.hu.ui.SettingsActivity;
import it.smg.hu.ui.settings.KeymapFragment;
import it.smg.libs.carlinkit.CarlinkitProtocol;
import it.smg.libs.common.Log;

/**
 * Carlinkit mode screen: displays the CarPlay/Android Auto stream delivered by the dongle.
 *
 * Responsibilities:
 *  - locate the dongle and obtain USB permission
 *  - lend the Surface to {@link CarlinkitService} and forward touches
 *  - forward the head unit steering wheel buttons and the microphone requests
 *  - log everything to a file, since the head unit has no adb
 *
 * <p>The session itself lives in the service, which runs in the {@code :usb} process so the
 * USB connection survives the head unit killing this process. Every call to it therefore
 * goes through AIDL ({@link ICarlinkitService}) and may throw {@link RemoteException}; the
 * {@code svc*} helpers below keep the call sites readable.
 */
public final class CarlinkitActivity extends Activity
        implements SurfaceHolder.Callback,
        HondaConnectManager.HondaListener, ServiceConnection {

    private static final String TAG = "CarlinkitActivity";
    private static final String ACTION_USB_PERMISSION = "it.smg.hu.CARLINKIT_USB_PERMISSION";

    private SurfaceView surfaceView;
    private TextView statusView;
    private ICarlinkitService service;
    private UsbManager usbManager;
    private boolean serviceBound;
    private boolean sessionStarted;
    private volatile boolean phoneConnected;
    /** Set when the user leaves the app on purpose, so it does not reopen by itself. */
    private volatile boolean userLeft;
    /** Dongle detected before the Surface existed; opened once the Surface is ready. */
    private UsbDevice pendingDevice;
    /** True between requestPermission and the answer: the system dialog steals the focus
     *  and must not be mistaken for the user leaving the app. */
    private volatile boolean awaitingUsbPermission;
    /**
     * USB permission is granted per device instance, and this dongle re-enumerates often,
     * so the dialog cannot be avoided on this head unit. The dialog was also observed
     * dismissing itself, leaving the app stuck until reopened — hence the retry.
     */
    private int usbPermissionAttempts;
    private static final int MAX_USB_PERMISSION_ATTEMPTS = 3;

    /**
     * Events coming back from the service. These arrive on a Binder thread, so anything
     * touching views goes through {@link #setStatus(String)}/{@link #hideStatus()}, which
     * already post to the UI thread.
     */
    private final ICarlinkitCallback.Stub serviceCallback = new ICarlinkitCallback.Stub() {

        @Override
        public void onPhoneConnected(int phoneType) {
            final String kind = phoneType == 5 ? "Android Auto"
                    : phoneType == 3 ? "CarPlay" : "type " + phoneType;
            logBoth("phone connected: " + kind);
            phoneConnected = true;
            hideStatus();
        }

        @Override
        public void onPhoneDisconnected() {
            logBoth("phone disconnected");
            phoneConnected = false;
            setStatus("phone disconnected");
        }

        @Override
        public void onStatus(String status) {
            logBoth("state: " + status);
            setStatus(status);
        }

        @Override
        public boolean onMicRequested(boolean start) {
            return handleMicRequest(start);
        }
    };

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                awaitingUsbPermission = false;
                logBoth("USB permission " + (granted ? "granted" : "DENIED"));
                if (granted && device != null) {
                    usbPermissionAttempts = 0;
                    openDevice(device);
                } else if (usbPermissionAttempts < MAX_USB_PERMISSION_ATTEMPTS) {
                    // The dialog can dismiss itself here; ask again instead of leaving the
                    // app waiting for the user to reopen it.
                    logBoth("permission denied on attempt " + usbPermissionAttempts
                            + " — asking again");
                    setStatus("USB permission denied — asking again");
                    surfaceView.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            findAndOpenDongle();
                        }
                    }, 1500);
                } else {
                    logBoth("giving up after " + usbPermissionAttempts + " attempts");
                    setStatus("USB permission denied.\nClose and reopen the app to retry.");
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null && CarlinkitProtocol.isSupportedDevice(
                        device.getVendorId(), device.getProductId())) {
                    logBoth("dongle disconnected");
                    releaseSurface();
                    setStatus("dongle disconnected");
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                // It may be the dongle or a flash drive: if it is a flash drive, the log moves to it
                CarlinkitFileLog.reevaluateTarget(CarlinkitActivity.this);
                if (userLeft) {
                    logBoth("USB device attached, but the user left the app — ignoring");
                    return;
                }
                logBoth("USB device attached");
                findAndOpenDongle();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CarlinkitFileLog.init(this);
        installCrashHandler();
        logBoth("onCreate");

        // Without FULLSCREEN the head unit status bar steals 63px: the Surface ends up
        // 800x417 instead of 800x480, the video does not fill the screen and the touch
        // mapping comes out offset.
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_carlinkit);

        // Bind to the head unit services (steering wheel, day/night, audio).
        // Without this the steering wheel buttons never reach the app and the head unit
        // handles them on its own.
        if (hondaEnabled()) {
            try {
                HondaConnectManager.instance().initialize();
                HondaConnectManager.instance().adjustPermission();
            } catch (Throwable t) {
                logBoth("HondaConnectManager.initialize failed", t);
            }
        }

        surfaceView = findViewById(R.id.carlinkitSurface);
        statusView = findViewById(R.id.carlinkitStatus);
        surfaceView.getHolder().addCallback(this);

        // Shortcut: a long press while no phone is connected opens the Settings.
        // Once connected the Surface belongs to Android Auto, so the shortcut goes
        // inactive to avoid competing with the projection touches.
        statusView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (phoneConnected) {
                    return false;
                }
                logBoth("opening Settings (long press on status)");
                startActivity(new Intent(CarlinkitActivity.this, SettingsActivity.class));
                return true;
            }
        });
        // A short tap on the status stops the service and releases the dongle. It lives
        // here because a BACK long press never reaches the app: the head unit closes the
        // screen before the long press.
        statusView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (phoneConnected) {
                    return;
                }
                logBoth("stopping service and releasing the dongle (tap on status)");
                if (hondaEnabled()) {
                    try {
                        HondaConnectManager.instance().releaseAudioFocus();
                    } catch (Throwable ignored) {
                    }
                }
                stopServiceAndSession();
                finish();
            }
        });
        statusView.setClickable(true);
        statusView.setLongClickable(true);

        surfaceView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return handleTouch(event);
            }
        });

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        // The service owns the USB connection and outlives this screen: without it the
        // head unit re-enumerates the dongle and the USB permission is asked again.
        // startService comes first on purpose, so the service is not destroyed when this
        // screen unbinds.
        Intent svc = new Intent(this, CarlinkitService.class);
        startService(svc);
        bindService(svc, this, BIND_AUTO_CREATE);

        IntentFilter f = new IntentFilter(ACTION_USB_PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        registerReceiver(usbReceiver, f);

        // With no adb, showing the log path on screen is the only way to find it
        setStatus("searching for dongle...\nlog: " + CarlinkitFileLog.path()
                + "\n(" + CarlinkitFileLog.targetKind() + ")");
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Fresh foreground: allow asking for USB permission again from scratch
        usbPermissionAttempts = 0;
        // The app is in the foreground: any previous exit (including going to the
        // Settings) no longer applies.
        if (userLeft) {
            logBoth("back to the foreground — reactivating");
            userLeft = false;
        }
        if (hondaEnabled()) {
            try {
                HondaConnectManager.instance().addListener(this);
                // Announce to ModeMgr that we took over the mode. This is the step that
                // makes the head unit deliver the steering wheel events: the callback may
                // be registered at the correct index and still receive nothing without
                // the audio focus.
                HondaConnectManager.instance().requestAudioFocus();
                // Registers the steering wheel callback and takes over the display mode (dispMode=1)
                HondaConnectManager.instance().initAudioBinding();
                // The steering wheel callback is registered at a configurable proprietary
                // index. If it is wrong, no event arrives — logging it helps to compare
                // with the value that works in OpenDroidAuto.
                logBoth("wheelServiceBound=" + HondaConnectManager.instance().isWheelServiceBound()
                        + " hasAudioFocus=" + HondaConnectManager.instance().hasAudioFocus());
                logBoth("steeringWheelIdx=" + Settings.instance().advanced.steeringWheelIdx()
                        + " modeMgrAudioIdx=" + Settings.instance().advanced.modeMgrAudioIdx()
                        + " | keymap PLUS=" + keyCode(KeymapFragment.KeyMap.PLUS)
                        + " MINUS=" + keyCode(KeymapFragment.KeyMap.MINUS)
                        + " LEFT=" + keyCode(KeymapFragment.KeyMap.LEFT)
                        + " RIGHT=" + keyCode(KeymapFragment.KeyMap.RIGHT));
            } catch (Throwable t) {
                logBoth("Honda integration unavailable", t);
            }
        } else {
            logBoth("Honda integration DISABLED in the app settings"
                    + " -> steering wheel and night mode will not work");
        }
        findAndOpenDongle();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (hondaEnabled()) {
            try {
                HondaConnectManager.instance().sendToBackground();
                HondaConnectManager.instance().removeListener(this);
                // The audio is NOT released here: any system dialog goes through
                // onPause/onUserLeaveHint, and releasing it at this point killed the
                // session in a loop (observed: 8 cycles in 6s during the USB permission).
                // It is handed back in onBackPressed and in onDestroy.
            } catch (Throwable ignored) {
            }
        }
    }

    /** BACK / MENU button: the user wants to leave (e.g. to listen to FM radio). */
    @Override
    public void onBackPressed() {
        logBoth("onBackPressed — exit requested by the user");
        userLeft = true;
        releaseSurface();
        if (hondaEnabled()) {
            try {
                // Explicit exit: hand the audio back to the FM radio / native Bluetooth
                HondaConnectManager.instance().releaseAudioFocus();
                logBoth("audio handed back to the head unit");
            } catch (Throwable ignored) {
            }
        }
        super.onBackPressed();
    }

    @Override
    protected void onUserLeaveHint() {
        // Called when the user leaves via HOME/MENU, but not when another app puts itself
        // on top on its own — exactly the distinction we need.
        if (awaitingUsbPermission) {
            // The USB permission dialog takes the focus away from the Activity; that is not the user leaving
            logBoth("onUserLeaveHint ignored (USB permission dialog open)");
            super.onUserLeaveHint();
            return;
        }
        logBoth("onUserLeaveHint — user left the app");
        userLeft = true;
        super.onUserLeaveHint();
    }

    /**
     * Re-asserts the steering wheel registration when the focus comes back. Observed during
     * testing: track switching stopped working after a while and only came back by
     * restarting the app — a sign that the dispMode/callback was lost when the head unit
     * showed another screen (e.g. an incoming call).
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && hondaEnabled()) {
            try {
                HondaConnectManager.instance().initAudioBinding();
                CarlinkitFileLog.log(TAG, "focus regained — steering wheel registration re-asserted"
                        + " (wheelBound=" + HondaConnectManager.instance().isWheelServiceBound()
                        + " audioFocus=" + HondaConnectManager.instance().hasAudioFocus() + ")");
            } catch (Throwable t) {
                CarlinkitFileLog.log(TAG, "failed to re-assert the steering wheel registration", t);
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Back in the app: allow reconnecting again
        userLeft = false;
        logBoth("onNewIntent — app reopened");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        logBoth("onDestroy");
        releaseSurface();
        if (serviceBound) {
            // Unregister first: the service must not keep a callback into a dead process.
            if (service != null) {
                try {
                    service.unregisterCallback();
                } catch (RemoteException e) {
                    CarlinkitFileLog.log(TAG, "unregisterCallback failed", e);
                }
            }
            try {
                unbindService(this);
            } catch (Throwable ignored) {
            }
            serviceBound = false;
        }
        if (hondaEnabled()) {
            try {
                HondaConnectManager.instance().endAudioBinding();
            } catch (Throwable ignored) {
            }
        }
        try {
            unregisterReceiver(usbReceiver);
        } catch (Throwable ignored) {
        }
        CarlinkitFileLog.close();
    }

    // ------------------------------------------------------------------ USB

    /**
     * Looks for the dongle and asks for USB permission if needed.
     *
     * The request stays here, in the UI process, because this head unit never delivers
     * USB_DEVICE_ATTACHED for the dongle: the dialog is the only path to the grant. The
     * grant then applies app-wide (both processes share the UID), so the service can open
     * the device itself.
     */
    private void findAndOpenDongle() {
        if (sessionStarted || usbManager == null) {
            return;
        }
        if (awaitingUsbPermission) {
            // There is already a permission dialog open. Without this guard the app asked
            // twice: the dialog takes the focus away, onResume runs and asked again.
            logBoth("USB permission already requested — waiting for the answer");
            return;
        }
        HashMap<String, UsbDevice> devices = usbManager.getDeviceList();
        for (UsbDevice d : devices.values()) {
            if (CarlinkitProtocol.isSupportedDevice(d.getVendorId(), d.getProductId())) {
                logBoth("dongle found: " + d.getDeviceName()
                        + " (" + d.getVendorId() + ":" + d.getProductId() + ")");
                if (usbManager.hasPermission(d)) {
                    openDevice(d);
                } else {
                    setStatus("requesting USB permission...\ncheck \"use by default\"");
                    awaitingUsbPermission = true;
                    usbPermissionAttempts++;
                    PendingIntent pi = PendingIntent.getBroadcast(this, 0,
                            new Intent(ACTION_USB_PERMISSION), 0);
                    usbManager.requestPermission(d, pi);
                }
                return;
            }
        }
        logBoth("no Carlinkit dongle found (looking for 1314:1520/1521)");
        setStatus("dongle not found");
    }

    private void openDevice(UsbDevice device) {
        if (sessionStarted) {
            return;
        }
        // The resolution is sent to the dongle in the Open command and does not change
        // afterwards. If the Surface does not exist yet, getWidth() returns 0 and we would
        // fall back to the 800x480 default — that is what happened in the first test, with
        // the Surface at 800x417 receiving 800x480 video.
        if (!surfaceReady()) {
            logBoth("Surface not ready yet — session waiting for surfaceChanged");
            pendingDevice = device;
            return;
        }
        pendingDevice = null;
        if (service == null) {
            logBoth("service not connected yet — waiting");
            pendingDevice = device;
            return;
        }
        boolean reused = svcIsStarted();
        if (!svcEnsureStarted(device)) {
            logBoth("could not start the session in the service");
            setStatus("failed to open the dongle");
            return;
        }
        svcAttachSurface();
        sessionStarted = true;
        applyCurrentNightMode();
        if (reused) {
            logBoth("USB connection reused from the service (no new permission)");
        }
        setStatus("waiting for phone...");
        logBoth("session started (" + surfaceView.getWidth() + "x" + surfaceView.getHeight()
                + ") — log at " + CarlinkitFileLog.path());
        svcOnSurfaceReady();
    }

    /**
     * Releases the Surface and mutes the audio, but keeps the USB connection in the
     * service. That is what prevents the dongle from being re-enumerated and the
     * permission from being asked on every return.
     */
    private void releaseSurface() {
        sessionStarted = false;
        svcDetachSurface();
    }

    /**
     * Stops the session and the service, releasing the dongle.
     *
     * A BACK long press was tried as a shortcut and does not work: the head unit handles
     * BACK and closes the app before the long press timeout. It is now triggered by a tap
     * on the status.
     */
    private void stopServiceAndSession() {
        sessionStarted = false;
        if (service != null) {
            try {
                service.shutdown();
                return;
            } catch (RemoteException e) {
                CarlinkitFileLog.log(TAG, "shutdown failed", e);
            }
        }
        // Not bound (or the service process is gone): fall back to the stop intent.
        Intent stop = new Intent(this, CarlinkitService.class);
        stop.setAction(CarlinkitService.ACTION_STOP);
        startService(stop);
    }

    // ------------------------------------------------------ ServiceConnection

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        service = ICarlinkitService.Stub.asInterface(binder);
        serviceBound = true;
        boolean started = svcIsStarted();
        logBoth("service connected (active session: " + started + ")");
        try {
            service.registerCallback(serviceCallback);
        } catch (RemoteException e) {
            CarlinkitFileLog.log(TAG, "registerCallback failed", e);
        }
        if (started) {
            // Back in the app: the USB connection is still alive, we only reattach the screen
            svcAttachSurface();
            sessionStarted = true;
            if (surfaceReady()) {
                svcOnSurfaceReady();
            }
            setStatus("reattaching the screen...");
        } else if (pendingDevice != null) {
            UsbDevice d = pendingDevice;
            pendingDevice = null;
            openDevice(d);
        } else {
            findAndOpenDongle();
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        logBoth("service disconnected");
        service = null;
        serviceBound = false;
        sessionStarted = false;
    }

    // -------------------------------------------------------------- Surface

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        logBoth("surfaceCreated");
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        logBoth("surfaceChanged " + width + "x" + height);
        if (pendingDevice != null) {
            UsbDevice d = pendingDevice;
            pendingDevice = null;
            logBoth("Surface ready — opening the dongle now");
            openDevice(d);
            return;
        }
        if (svcIsStarted()) {
            svcAttachSurface();
            // Recreates the decoder and reinjects SPS/PPS: the dongle sends the parameters only once
            svcOnSurfaceReady();
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        logBoth("surfaceDestroyed");
        svcDetachSurface();
    }

    // ------------------------------------------------------------- Session

    /**
     * Reads the current day/night state from the head unit and applies it.
     *
     * Night mode follows the headlights. Relying on the callback alone would leave the
     * screen in day mode if the app were opened with the headlights already on and no
     * change happened afterwards — that is why the state is read actively here.
     */
    private void applyCurrentNightMode() {
        if (!hondaEnabled()) {
            return;
        }
        try {
            Boolean night = HondaConnectManager.instance().isNight();
            if (night != null) {
                boolean sent = svcSetNightMode(night.booleanValue());
                logBoth("current headlight state: " + (night.booleanValue() ? "night" : "day")
                        + (sent ? " -> applied" : " (will be applied on connect)"));
            }
        } catch (Throwable t) {
            logBoth("could not read the day/night state", t);
        }
    }

    /**
     * The phone asked for the microphone. On the head unit the mic goes through the EcNc
     * service, which applies echo cancellation — without enabling it the capture comes in
     * empty or with echo from the speaker.
     *
     * Called from the service through {@link ICarlinkitCallback}: the head unit services are
     * bound in this process, so only the UI can do it.
     */
    boolean handleMicRequest(boolean start) {
        if (!hondaEnabled()) {
            logBoth("microphone: Honda integration disabled, using the mic directly");
            return false;
        }
        try {
            if (start) {
                HondaConnectManager.instance().startMicSession();
                logBoth("microphone: EcNc session started on the head unit");
            } else {
                HondaConnectManager.instance().stopMicSession();
                logBoth("microphone: EcNc session stopped");
            }
            return true;
        } catch (Throwable t) {
            logBoth("microphone: EcNc session failed", t);
            return false;
        }
    }

    /**
     * Converts the MotionEvent into the normalised coordinates the protocol expects and
     * sends it to the service. The conversion happens here because this is where the
     * MotionEvent and the real Surface size live; sending the event itself across the
     * Binder would be wasteful.
     */
    private boolean handleTouch(MotionEvent event) {
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
        if (service == null || surfaceView == null) {
            return false;
        }
        int w = surfaceView.getWidth();
        int h = surfaceView.getHeight();
        if (w <= 0 || h <= 0) {
            return false;
        }
        try {
            return service.sendTouch(action, event.getX() / w, event.getY() / h);
        } catch (RemoteException e) {
            CarlinkitFileLog.log(TAG, "sendTouch failed", e);
            return false;
        }
    }

    // ------------------------------------------------- HondaConnectManager

    /**
     * Steering wheel buttons. Volume does not arrive here: the head unit handles it
     * directly on the amplifier.
     */
    @Override
    public void onSteeringWheelKey(int keyType) {
        boolean sent = svcSendSteeringKey(keyType);
        CarlinkitFileLog.log(TAG, "steering wheel keyType=" + keyType
                + (service == null ? " (session inactive)" : sent ? " sent" : " unmapped"));
    }

    @Override
    public void onDayNightUpdate(boolean isNight) {
        // The dongle protocol has its own day/night commands; previously this was only
        // written to the log, which is why the screen did not darken.
        boolean sent = svcSetNightMode(isNight);
        CarlinkitFileLog.log(TAG, "night mode: " + isNight
                + (sent ? " -> sent to the dongle" : " (session inactive)"));
    }

    /**
     * Steering wheel buttons that the head unit delivers as a KeyEvent (not through
     * onSteeringWheelKey): volume and, on many units, track switching. The keyCodes are
     * proprietary (negative) and configurable in the keymap, so every event is written to
     * the log — that is how we discover the real codes of this head unit.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        int action = event.getAction();
        if (action == KeyEvent.ACTION_DOWN) {
            CarlinkitFileLog.log(TAG, "KeyEvent DOWN keyCode=" + code);

            if (code == keyCode(KeymapFragment.KeyMap.PLUS)
                    || code == KeyEvent.KEYCODE_VOLUME_UP) {
                if (hondaEnabled()) {
                    try {
                        HondaConnectManager.instance().increaseVolume();
                        CarlinkitFileLog.log(TAG, "  -> volume +");
                        return true;
                    } catch (Throwable t) {
                        CarlinkitFileLog.log(TAG, "  error on volume +", t);
                    }
                }
            } else if (code == keyCode(KeymapFragment.KeyMap.MINUS)
                    || code == KeyEvent.KEYCODE_VOLUME_DOWN) {
                if (hondaEnabled()) {
                    try {
                        HondaConnectManager.instance().decreaseVolume();
                        CarlinkitFileLog.log(TAG, "  -> volume -");
                        return true;
                    } catch (Throwable t) {
                        CarlinkitFileLog.log(TAG, "  error on volume -", t);
                    }
                }
            } else if (code == keyCode(KeymapFragment.KeyMap.RIGHT)
                    || code == KeyEvent.KEYCODE_MEDIA_NEXT) {
                if (svcSendCommand(CarlinkitProtocol.Command.NEXT)) {
                    CarlinkitFileLog.log(TAG, "  -> next track");
                    return true;
                }
            } else if (code == keyCode(KeymapFragment.KeyMap.LEFT)
                    || code == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
                if (svcSendCommand(CarlinkitProtocol.Command.PREV)) {
                    CarlinkitFileLog.log(TAG, "  -> previous track");
                    return true;
                }
            }
        }
        if (action == KeyEvent.ACTION_DOWN && code != KeyEvent.KEYCODE_BACK) {
            // Unmapped: logging it prominently helps to discover the codes of this head unit.
            // BACK is left out because it is the normal way to exit the app.
            CarlinkitFileLog.log(TAG, "  keyCode " + code + " UNMAPPED"
                    + " (expected: PLUS=" + keyCode(KeymapFragment.KeyMap.PLUS)
                    + " MINUS=" + keyCode(KeymapFragment.KeyMap.MINUS)
                    + " LEFT=" + keyCode(KeymapFragment.KeyMap.LEFT)
                    + " RIGHT=" + keyCode(KeymapFragment.KeyMap.RIGHT) + ")");
        }
        return super.dispatchKeyEvent(event);
    }

    /** keyCode configured in the keymap for a button, falling back to the default. */
    private int keyCode(KeymapFragment.KeyMap key) {
        try {
            int k = Settings.instance().keymap.key(key.keyName() + "_code");
            return k != 0 ? k : key.defaultValue();
        } catch (Throwable t) {
            return key.defaultValue();
        }
    }

    // --------------------------------------------------------- service calls

    /**
     * Every method below wraps one AIDL call. RemoteException means the {@code :usb}
     * process died; there is nothing to do about it here beyond logging and reporting
     * failure, so the call sites stay free of try/catch.
     */

    private boolean svcIsStarted() {
        if (service == null) {
            return false;
        }
        try {
            return service.isStarted();
        } catch (RemoteException e) {
            CarlinkitFileLog.log(TAG, "isStarted failed", e);
            return false;
        }
    }

    private boolean svcEnsureStarted(UsbDevice device) {
        if (service == null) {
            return false;
        }
        try {
            return service.ensureStarted(device);
        } catch (RemoteException e) {
            CarlinkitFileLog.log(TAG, "ensureStarted failed", e);
            return false;
        }
    }

    /**
     * Hands the current Surface, with its real size, to the service. The Surface is
     * Parcelable, so the service in the other process can draw straight into it.
     */
    private void svcAttachSurface() {
        if (service == null || surfaceView == null) {
            return;
        }
        Surface surface = surfaceView.getHolder().getSurface();
        if (surface == null || !surface.isValid()) {
            CarlinkitFileLog.log(TAG, "attachSurface skipped — Surface not valid yet");
            return;
        }
        try {
            service.attachSurface(surface, surfaceView.getWidth(), surfaceView.getHeight());
        } catch (RemoteException e) {
            CarlinkitFileLog.log(TAG, "attachSurface failed", e);
        }
    }

    private void svcDetachSurface() {
        if (service == null) {
            return;
        }
        try {
            service.detachSurface();
        } catch (RemoteException e) {
            CarlinkitFileLog.log(TAG, "detachSurface failed", e);
        }
    }

    private void svcOnSurfaceReady() {
        if (service == null) {
            return;
        }
        try {
            service.onSurfaceReady();
        } catch (RemoteException e) {
            CarlinkitFileLog.log(TAG, "onSurfaceReady failed", e);
        }
    }

    private boolean svcSendCommand(int command) {
        if (service == null) {
            return false;
        }
        try {
            return service.sendCommand(command);
        } catch (RemoteException e) {
            CarlinkitFileLog.log(TAG, "sendCommand failed", e);
            return false;
        }
    }

    private boolean svcSendSteeringKey(int hondaKeyType) {
        if (service == null) {
            return false;
        }
        try {
            return service.sendSteeringWheelKey(hondaKeyType);
        } catch (RemoteException e) {
            CarlinkitFileLog.log(TAG, "sendSteeringWheelKey failed", e);
            return false;
        }
    }

    private boolean svcSetNightMode(boolean night) {
        if (service == null) {
            return false;
        }
        try {
            return service.setNightMode(night);
        } catch (RemoteException e) {
            CarlinkitFileLog.log(TAG, "setNightMode failed", e);
            return false;
        }
    }

    // ------------------------------------------------------------------ util

    /**
     * Shows status text over the screen. Once the phone has connected and the video has
     * started, the text is no longer shown — in the first test the "phone found" message
     * arrived after the connection and stayed pinned over the image.
     */
    private void setStatus(final String text) {
        if (phoneConnected) {
            return;
        }
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (statusView != null && !phoneConnected) {
                    statusView.setText(text);
                    statusView.setVisibility(View.VISIBLE);
                }
            }
        });
    }

    private void hideStatus() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (statusView != null) {
                    statusView.setVisibility(View.GONE);
                }
            }
        });
    }

    /**
     * Writes uncaught exceptions to the file log before the process dies.
     * In an earlier test the app closed when opening the Android Auto settings and nothing
     * was recorded — with no adb on the head unit, this handler is the only way to see the
     * stack trace.
     */
    private void installCrashHandler() {
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable ex) {
                try {
                    CarlinkitFileLog.log(TAG, "*** CRASH on thread " + thread.getName(), ex);
                    Throwable cause = ex.getCause();
                    int depth = 0;
                    while (cause != null && depth++ < 3) {
                        CarlinkitFileLog.log(TAG, "*** cause " + depth, cause);
                        cause = cause.getCause();
                    }
                    CarlinkitFileLog.close();
                } catch (Throwable ignored) {
                }
                if (previous != null) {
                    previous.uncaughtException(thread, ex);
                }
            }
        });
    }

    private boolean surfaceReady() {
        return surfaceView != null
                && surfaceView.getWidth() > 0
                && surfaceView.getHeight() > 0
                && surfaceView.getHolder().getSurface() != null
                && surfaceView.getHolder().getSurface().isValid();
    }

    /** The Honda integration is optional in the app settings; without it there is no steering wheel. */
    private boolean hondaEnabled() {
        try {
            return Settings.instance().advanced.hondaIntegrationEnabled();
        } catch (Throwable t) {
            return false;
        }
    }

    private void logBoth(String msg) {
        Log.i(TAG, msg);
        CarlinkitFileLog.log(TAG, msg);
    }

    private void logBoth(String msg, Throwable t) {
        Log.e(TAG, msg, t);
        CarlinkitFileLog.log(TAG, msg, t);
    }
}
