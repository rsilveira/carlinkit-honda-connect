package it.smg.hu.carlinkit;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.content.ComponentName;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.IBinder;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

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
 *  - keep the {@link CarlinkitSession} alive (video, audio, touch)
 *  - forward the head unit steering wheel buttons
 *  - log everything to a file, since the head unit has no adb
 */
public final class CarlinkitActivity extends Activity
        implements SurfaceHolder.Callback, CarlinkitSession.Callback,
        HondaConnectManager.HondaListener, HondaConnectManager.ProjectionVideoListener,
        ServiceConnection {

    private static final String TAG = "CarlinkitActivity";
    private static final String ACTION_USB_PERMISSION = "it.smg.hu.CARLINKIT_USB_PERMISSION";

    private SurfaceView surfaceView;
    private TextView statusView;
    private CarlinkitService service;
    private UsbManager usbManager;
    private boolean serviceBound;
    private boolean sessionStarted;
    private volatile boolean phoneConnected;
    /** Set when the user leaves the app on purpose, so it does not reopen by itself. */
    private volatile boolean userLeft;
    /** Dongle detected before the Surface existed; opened once the Surface is ready. */
    private UsbDevice pendingDevice;
    /**
     * The dongle re-enumerates when the process dies (its ~9s watchdog reboots it), so right
     * after opening the app it may briefly not be in the device list. Observed in the car:
     * absent on one launch, present 11s later with a new device number. Retrying spares the
     * user from closing and reopening the app.
     */
    private boolean wheelDiagLogged;
    private int dongleSearchAttempts;
    private static final int MAX_DONGLE_SEARCH_ATTEMPTS = 10;
    private static final int DONGLE_SEARCH_INTERVAL_MS = 2000;
    /**
     * Wait before searching again after the dongle drops.
     *
     * The stale device instance lingers in the USB device list for a moment after the
     * detach. Searching immediately finds that dead instance and reopens it, which is
     * exactly what happened on 28/Jul: the session dropped 156ms and 74ms after starting.
     * Waiting lets the list settle so the retry loop picks up the new instance.
     *
     * Raised from 4s to 8s after the 01/Aug test: with 4s the reconnection did find the
     * new instance (device 112 -> 113 -> 114), but the dongle dropped again 8s later and
     * ended up in a state where it enumerated and accepted a session while never sending
     * any status. Reopening it while it is still rebooting appears to make that worse.
     */
    private static final int DONGLE_RECONNECT_DELAY_MS = 8000;
    /**
     * Automatic reconnection is attempted only once.
     *
     * On the 01/Aug test three reconnections in 20s produced a cascade of USB permission
     * dialogs and left the dongle unresponsive; only powering the car off and on brought
     * it back (the device number jumped from 115 to 119). Retrying harder does not help
     * when the dongle itself is the thing that needs a power cycle, so after one failed
     * attempt the app says so instead of looping.
     */
    private int dongleReconnectAttempts;
    private static final int MAX_DONGLE_RECONNECT_ATTEMPTS = 1;
    /**
     * How long to wait for the first status message before declaring the dongle mute.
     *
     * A healthy dongle reports its state about 1s after the session starts: measured
     * 1.019s, 1.018s and 1.017s across three good sessions. When it is wedged the session
     * starts normally and no status ever arrives — three consecutive launches sat silent
     * for 99s, 15s and 33s with the user unaware that nothing would happen. 10s is an
     * order of magnitude above the healthy case, so it does not fire on a slow start.
     */
    private static final int DONGLE_MUTE_TIMEOUT_MS = 10000;
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
                    logBoth("permission denied — attempt " + usbPermissionAttempts
                            + "/" + MAX_USB_PERMISSION_ATTEMPTS + ", asking again");
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
                    scheduleDongleReconnect();
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
                CarlinkitSession sess = session();
                return sess != null && sess.onTouch(event);
            }
        });

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        // The service owns the USB connection and outlives this screen: without it the
        // head unit re-enumerates the dongle and the USB permission is asked again.
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
        // Only a genuine return to the foreground resets the retry budget.
        //
        // The USB permission dialog takes the focus away and gives it back, so it runs
        // onResume too — and it runs BEFORE the permission broadcast arrives. Resetting
        // the counters here unconditionally meant every denial started over from zero:
        // the 01/Aug log shows "permission denied on attempt 0" twice in a row, so
        // MAX_USB_PERMISSION_ATTEMPTS was never reached and the app kept reopening the
        // dialog. Keeping the counters while a request is pending makes the limit real.
        if (!awaitingUsbPermission) {
            usbPermissionAttempts = 0;
            dongleSearchAttempts = 0;
            dongleReconnectAttempts = 0;
        }
        // The app is in the foreground: any previous exit (including going to the
        // Settings) no longer applies.
        if (userLeft) {
            logBoth("back to the foreground — reactivating");
            userLeft = false;
        }
        CarlinkitService.persistUserLeft(this, false);
        if (hondaEnabled()) {
            try {
                HondaConnectManager.instance().addListener(this);
                HondaConnectManager.instance().setProjectionVideoListener(this);
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
                if (!wheelDiagLogged) {
                    wheelDiagLogged = true;
                    // Once per app start: these values never change while running
                    logBoth("wheelServiceBound=" + HondaConnectManager.instance().isWheelServiceBound()
                            + " hasAudioFocus=" + HondaConnectManager.instance().hasAudioFocus()
                            + " steeringWheelIdx=" + Settings.instance().advanced.steeringWheelIdx()
                            + " modeMgrAudioIdx=" + Settings.instance().advanced.modeMgrAudioIdx()
                            + " | keymap PLUS=" + keyCode(KeymapFragment.KeyMap.PLUS)
                            + " MINUS=" + keyCode(KeymapFragment.KeyMap.MINUS)
                            + " LEFT=" + keyCode(KeymapFragment.KeyMap.LEFT)
                            + " RIGHT=" + keyCode(KeymapFragment.KeyMap.RIGHT));
                }
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
        // Logged because without it the log cannot tell the death hypotheses apart.
        //
        // Three sessions on Aug 3 ended in the middle of an ordinary line, with no
        // onBackPressed and no exception. That is consistent with at least three different
        // causes, and the logging at the time separated none of them:
        //
        //   a) the head unit kills the process (SIGKILL), leaving nothing to log
        //   b) a NATIVE crash in the OMX decoder (SIGSEGV in C++), which the
        //      UncaughtExceptionHandler cannot catch because it is Java
        //   c) the app went to the background and died there
        //
        // With onPause/onStop/onResume in the log: if the last line is onPause, the app left
        // the foreground before dying (reverse gear taking over the screen). If the last line
        // is an ordinary session event, it died in the foreground, and suspicion falls on the
        // native decoder.
        logBoth("onPause (app leaving the foreground)");
        onPauseHondaCleanup();
    }

    @Override
    protected void onStop() {
        super.onStop();
        logBoth("onStop (app no longer visible)");
    }

    @Override
    protected void onStart() {
        super.onStart();
        logBoth("onStart");
    }

    /**
     * Logs the warning Android issues BEFORE killing the process for lack of memory.
     *
     * This is the signal that finally separates the two remaining hypotheses for the death
     * when reverse gear is disengaged. In four sessions on Aug 6 the process died with no
     * onPause, no onStop and no exception, with the heartbeat at an exact 30.0s cadence right
     * up to the last line. The absence of any degradation already makes memory pressure
     * unlikely, because aggressive garbage collection would delay the timer, but that is
     * inference rather than evidence.
     *
     * With onTrimMemory and onLowMemory instrumented the conclusion becomes direct:
     *
     *   - if increasing levels appear before the death, it was the lowmemorykiller, and the
     *     fix is to reduce the memory footprint or make the process less killable;
     *   - if nothing appears, the system never asked for memory and the death came from
     *     outside Java: SIGSEGV in the native decoder, or a SIGKILL from the head unit.
     *
     * TRIM_MEMORY_COMPLETE is the last warning: it means this process is next to be killed.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        logBoth("onTrimMemory: " + trimLevelName(level) + " (" + level + ")");
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        logBoth("onLowMemory: system low on memory, the process may be killed");
    }

    /**
     * Android calls this before killing a process that can be restored later.
     *
     * It serves the same purpose as onTrimMemory: if the line shows up immediately before the
     * end of the log, the death was ordered by the system rather than a native crash.
     */
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        logBoth("onSaveInstanceState: the system intends to destroy the Activity");
    }

    private static String trimLevelName(int level) {
        switch (level) {
            case ComponentCallbacks2.TRIM_MEMORY_COMPLETE:
                return "COMPLETE (next to be killed)";
            case ComponentCallbacks2.TRIM_MEMORY_MODERATE:
                return "MODERATE";
            case ComponentCallbacks2.TRIM_MEMORY_BACKGROUND:
                return "BACKGROUND";
            case ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN:
                return "UI_HIDDEN";
            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL:
                return "RUNNING_CRITICAL";
            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW:
                return "RUNNING_LOW";
            case ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE:
                return "RUNNING_MODERATE";
            default:
                return "unknown";
        }
    }

    private void onPauseHondaCleanup() {
        if (hondaEnabled()) {
            try {
                HondaConnectManager.instance().sendToBackground();
                HondaConnectManager.instance().removeListener(this);
                HondaConnectManager.instance().setProjectionVideoListener(null);
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
        CarlinkitService.persistUserLeft(this, true);
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
        CarlinkitService.persistUserLeft(this, true);
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
        // Logged always, not only on error, because losing focus is the only Java signal
        // that would remain if reverse gear does not produce an onPause.
        //
        // In the four deaths on Aug 6 there was no onPause, no onStop and no
        // surfaceDestroyed: the log ends at a heartbeat with the dongle still sending data
        // normally. That is consistent with the head unit switching the camera video in
        // HARDWARE, outside the Android lifecycle, leaving the app decoding without knowing
        // it lost the screen. But it is equally consistent with a focus change that the
        // current logging throws away, because this method only wrote a line when the wheel
        // service was not bound.
        //
        // If "focus lost" shows up when reverse is engaged, an Android event does exist and
        // the switch is not purely hardware. If nothing shows up, the hardware hypothesis
        // gains weight and suspicion falls on the native decoder, which dies in C++ without
        // passing through the UncaughtExceptionHandler.
        CarlinkitFileLog.log(TAG, "focus " + (hasFocus ? "gained" : "lost"));
        if (hasFocus && hondaEnabled()) {
            try {
                HondaConnectManager.instance().initAudioBinding();
                if (!HondaConnectManager.instance().isWheelServiceBound()) {
                    CarlinkitFileLog.log(TAG, "focus regained but the wheel service is NOT bound");
                }
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
        // Pending delayed work outlives the Activity and would run against a dead one.
        if (surfaceView != null) {
            surfaceView.removeCallbacks(muteDongleCheck);
            surfaceView.removeCallbacks(sessionHeartbeat);
        }
        if (serviceBound) {
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
     * Reconnects by itself after the dongle drops, with no user action.
     *
     * The dongle restarts on its own roughly 9s after its heartbeat stops, which is what
     * happens whenever the app is closed. Reopening the app before that cycle finishes
     * grabs a device instance that is already dying: on 28/Jul the session died 156ms and
     * 74ms after "session started in the service", and the user had to leave and reopen
     * several times until the timing happened to line up.
     *
     * Waiting first, and only then reusing the regular search loop, lets the dead instance
     * leave the USB device list so the new one is picked up instead.
     */
    private void scheduleDongleReconnect() {
        if (userLeft) {
            return;
        }
        if (dongleReconnectAttempts >= MAX_DONGLE_RECONNECT_ATTEMPTS) {
            // Beyond this point the dongle needs a power cycle, not another open attempt.
            logBoth("dongle dropped again after " + dongleReconnectAttempts
                    + " reconnection — not retrying");
            setStatus("The dongle keeps restarting.\n"
                    + "Turn the car off and on again.");
            return;
        }
        dongleReconnectAttempts++;
        // A dialog left open would block the search forever, since findAndOpenDongle()
        // returns early while a permission request is pending.
        awaitingUsbPermission = false;
        dongleSearchAttempts = 0;
        logBoth("dongle is restarting — searching again in "
                + (DONGLE_RECONNECT_DELAY_MS / 1000) + "s"
                + " (attempt " + dongleReconnectAttempts
                + "/" + MAX_DONGLE_RECONNECT_ATTEMPTS + ")");
        setStatus("dongle restarting...\nreconnecting in "
                + (DONGLE_RECONNECT_DELAY_MS / 1000) + "s");
        surfaceView.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!sessionStarted && !userLeft) {
                    findAndOpenDongle();
                }
            }
        }, DONGLE_RECONNECT_DELAY_MS);
    }

    /**
     * Marks in the log that the session was still alive.
     *
     * Without this, a session that dies silently leaves the last steering wheel command as
     * its final line, and that may have been minutes earlier. There is no way to tell whether
     * the app sat idle for that time or died right after the command.
     *
     * With the marker, the gap between the last line and the real time of death shrinks to at
     * most one interval, and the text records the internal state at that moment: whether the
     * dongle was still sending data and whether the phone was connected. A death with normal
     * traffic points to an external cause (a kill by the head unit) or a native one (the
     * decoder); a death after traffic stops points at the link itself.
     *
     * The interval went from 30s to 5s because 30s is too coarse for the event under
     * investigation. In four sessions on Aug 6 the app died with the heartbeat at a perfect
     * cadence, 30.0s exactly, without a single pause, and the last line landed 17s, 39s and
     * 109s away from the last real event. Reversing out of a parking space takes a few
     * seconds, so the whole manoeuvre fitted inside the blind window. At 5s the window is
     * shorter than the manoeuvre, and any degradation in the final moments (GC pauses, timer
     * delay) stops being invisible.
     *
     * Cost: 6x more lines in the file. A real 35 min session produced 69 lines at 30s, so at
     * 5s it would be around 420. Acceptable while investigating, but worth putting back to
     * 30s once the cause is known.
     */
    private static final int HEARTBEAT_MS = 5000;

    private final Runnable sessionHeartbeat = new Runnable() {
        @Override
        public void run() {
            if (!sessionStarted || userLeft) {
                return;
            }
            CarlinkitSession sess = session();
            CarlinkitFileLog.log(TAG, "alive | dongle sending="
                    + (sess != null && sess.hasReceivedData())
                    + " phone=" + phoneConnected);
            if (surfaceView != null) {
                surfaceView.postDelayed(this, HEARTBEAT_MS);
            }
        }
    };

    /**
     * Warns when the dongle enumerates, accepts a session and then stays silent.
     *
     * This is the failure that cost the most time on 01/Aug: the app looked fine — "session
     * started (800x480)" — and nothing ever happened, because the dongle was wedged. There
     * is no error to detect, only the absence of traffic, so the check is a timeout.
     *
     * Liveness is taken from the session's own traffic, not from status messages: a session
     * reopened while the phone is still connected receives no status at all and is perfectly
     * healthy, so keying on status would warn on a working setup.
     *
     * Deliberately conservative: it only fires when nothing was ever received on this
     * session. A dongle that wedges after having worked is not caught, because "no traffic
     * for a while" is normal with a static screen and a false warning would be worse than
     * a missed one. All three wedged launches on 01/Aug opened a fresh session, so this
     * covers the case that was actually observed.
     */
    private final Runnable muteDongleCheck = new Runnable() {
        @Override
        public void run() {
            if (!sessionStarted || userLeft) {
                return;
            }
            CarlinkitSession sess = session();
            if (sess != null && sess.hasReceivedData()) {
                // Healthy session: the failure episode is over, so a later drop gets a
                // fresh reconnection attempt.
                dongleReconnectAttempts = 0;
                return;
            }
            logBoth("dongle opened but sent nothing in "
                    + (DONGLE_MUTE_TIMEOUT_MS / 1000) + "s — it is not responding");
            setStatus("The dongle is not responding.\n"
                    + "Turn the car off and on again.");
        }
    };

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
                dongleSearchAttempts = 0;
                // Hex, to match the "looking for 1314:1520/1521" message and the USB
                // convention. It used to be printed in decimal (4884:5409), which looked
                // like a different device and confused the log analysis.
                logBoth("dongle found: " + d.getDeviceName()
                        + " (" + Integer.toHexString(d.getVendorId())
                        + ":" + Integer.toHexString(d.getProductId()) + ")");
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
        if (dongleSearchAttempts < MAX_DONGLE_SEARCH_ATTEMPTS) {
            dongleSearchAttempts++;
            // Do not report failure yet: the dongle is probably still re-enumerating
            logBoth("dongle not in the device list — retry " + dongleSearchAttempts
                    + "/" + MAX_DONGLE_SEARCH_ATTEMPTS + " in "
                    + (DONGLE_SEARCH_INTERVAL_MS / 1000) + "s");
            setStatus("looking for the dongle... (" + dongleSearchAttempts + ")");
            surfaceView.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!sessionStarted && !userLeft) {
                        findAndOpenDongle();
                    }
                }
            }, DONGLE_SEARCH_INTERVAL_MS);
            return;
        }
        logBoth("no Carlinkit dongle found after " + dongleSearchAttempts
                + " attempts (looking for 1314:1520/1521)");
        setStatus("Dongle not found.\nCheck the USB connection.");
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
        boolean reused = service.isStarted();
        if (!service.ensureStarted(device, usbManager, this)) {
            logBoth("could not start the session in the service");
            setStatus("failed to open the dongle");
            return;
        }
        service.attachSurface(surfaceView);
        sessionStarted = true;
        applyCurrentNightMode();
        if (reused) {
            logBoth("USB connection reused from the service (no new permission)");
        }
        setStatus("waiting for phone...");
        logBoth("session started (" + surfaceView.getWidth() + "x" + surfaceView.getHeight()
                + ") — log at " + CarlinkitFileLog.path());
        // Arm the mute-dongle check: a healthy dongle answers in about 1s.
        surfaceView.removeCallbacks(muteDongleCheck);
        surfaceView.postDelayed(muteDongleCheck, DONGLE_MUTE_TIMEOUT_MS);
        surfaceView.removeCallbacks(sessionHeartbeat);
        surfaceView.postDelayed(sessionHeartbeat, HEARTBEAT_MS);
        CarlinkitSession sess = session();
        if (sess != null) {
            sess.onSurfaceReady();
        }
    }

    /**
     * Releases the Surface and mutes the audio, but keeps the USB connection in the
     * service. That is what prevents the dongle from being re-enumerated and the
     * permission from being asked on every return.
     */
    private void releaseSurface() {
        sessionStarted = false;
        if (surfaceView != null) {
            surfaceView.removeCallbacks(muteDongleCheck);
            surfaceView.removeCallbacks(sessionHeartbeat);
        }
        if (service != null) {
            service.detachSurface();
        }
    }

    /**
     * Stops the session and the service, releasing the dongle.
     *
     * A BACK long press was tried as a shortcut and does not work: the head unit handles
     * BACK and closes the app before the long press timeout. It is now triggered by a long
     * press on the status.
     */
    private void stopServiceAndSession() {
        sessionStarted = false;
        Intent stop = new Intent(this, CarlinkitService.class);
        stop.setAction(CarlinkitService.ACTION_STOP);
        startService(stop);
    }

    // ------------------------------------------------------ ServiceConnection

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        service = ((CarlinkitService.CarlinkitBinder) binder).service();
        serviceBound = true;
        logBoth("service connected (active session: " + service.isStarted() + ")");
        service.setCallback(this);
        if (service.isStarted()) {
            // Back in the app: the USB connection is still alive, we only reattach the screen
            service.attachSurface(surfaceView);
            sessionStarted = true;
            if (surfaceReady()) {
                CarlinkitSession sess = service.session();
                if (sess != null) {
                    sess.onSurfaceReady();
                }
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
        CarlinkitSession sess = session();
        if (sess != null) {
            if (service != null) {
                service.attachSurface(surfaceView);
            }
            // Recreates the decoder and reinjects SPS/PPS: the dongle sends the parameters only once
            sess.onSurfaceReady();
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        logBoth("surfaceDestroyed");
        if (service != null) {
            service.detachSurface();
        }
    }

    // ------------------------------------------------------- Session.Callback

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
                CarlinkitSession sess = session();
                boolean sent = sess != null && sess.setNightMode(night.booleanValue());
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
     */
    @Override
    public boolean onMicRequested(boolean start) {
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

    @Override
    public void onStatus(String status) {
        logBoth("state: " + status);
        setStatus(status);
    }

    // ------------------------------------------------- HondaConnectManager

    /**
     * Steering wheel buttons. Volume does not arrive here: the head unit handles it
     * directly on the amplifier.
     */
    @Override
    public void onSteeringWheelKey(int keyType) {
        // Phone and assistant get centralized handling (toggle + dedupe), because the same
        // physical press may arrive through more than one route: KeyEvent, the wheel service
        // callback and the ModeMgr SWKey callback. Track keys have no toggle, so a duplicate
        // is harmless there and they keep the direct path.
        switch (keyType) {
            case 8:    // HondaKey.PICK_UP
                phoneButton("service");
                return;
            case 9:    // HondaKey.HANG_UP
                endCallButton("service");
                return;
            case 10:   // HondaKey.TALK
                talkButton("service");
                return;
            default:
                break;
        }
        CarlinkitSession sess = session();
        boolean sent = sess != null && sess.onSteeringWheelKey(keyType);
        CarlinkitFileLog.log(TAG, "steering wheel keyType=" + keyType
                + (sess == null ? " (session inactive)" : sent ? " sent" : " unmapped"));
    }

    /**
     * The same physical button can reach the app through up to three routes (KeyEvent,
     * ISteeringMenuService callback, ModeMgr SWKey callback), and we will only learn
     * tomorrow which of them this head unit actually uses. For the phone button a duplicate
     * is not harmless: the second ACCEPT toggles into REJECT and hangs up the call being
     * answered. The window absorbs duplicates without eating deliberate repeated presses.
     */
    private static final long KEY_DEDUPE_MS = 600;
    private long lastPhoneButtonAt;
    private long lastTalkButtonAt;

    private boolean dedupe(long now, long lastAt) {
        return now - lastAt < KEY_DEDUPE_MS;
    }

    /** Single phone button: accept when idle, hang up during a call. */
    private void phoneButton(String source) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (dedupe(now, lastPhoneButtonAt)) {
            CarlinkitFileLog.log(TAG, "phone button (" + source + ") deduped");
            return;
        }
        lastPhoneButtonAt = now;
        CarlinkitSession sess = session();
        boolean inCall = sess != null && sess.isPhoneCallActive();
        int cmd = inCall ? CarlinkitProtocol.Command.REJECT_PHONE
                : CarlinkitProtocol.Command.ACCEPT_PHONE;
        boolean sent = sendToDongle(cmd);
        CarlinkitFileLog.log(TAG, "phone button (" + source + "): "
                + (inCall ? "hang up" : "accept") + (sent ? " sent" : " NOT sent"));
    }

    private void endCallButton(String source) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (dedupe(now, lastPhoneButtonAt)) {
            CarlinkitFileLog.log(TAG, "end call (" + source + ") deduped");
            return;
        }
        lastPhoneButtonAt = now;
        boolean sent = sendToDongle(CarlinkitProtocol.Command.REJECT_PHONE);
        CarlinkitFileLog.log(TAG, "end call (" + source + ")" + (sent ? " sent" : " NOT sent"));
    }

    /** Voice assistant: Google Assistant on Android Auto, Siri on CarPlay. */
    private void talkButton(String source) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (dedupe(now, lastTalkButtonAt)) {
            CarlinkitFileLog.log(TAG, "talk button (" + source + ") deduped");
            return;
        }
        lastTalkButtonAt = now;
        boolean sent = sendToDongle(CarlinkitProtocol.Command.SIRI);
        CarlinkitFileLog.log(TAG, "talk button (" + source + ")" + (sent ? " sent" : " NOT sent"));
    }

    /**
     * The head unit took the screen (reverse camera) or handed it back. Pausing the decoder
     * here is the defence against the suspected SIGSEGV: OMX writing into a Surface the
     * head unit reclaimed by hardware. See CarlinkitSession.onScreenTakenByHeadUnit.
     */
    @Override
    public void onProjectionVideoTaken(boolean taken) {
        CarlinkitSession sess = session();
        if (sess != null) {
            sess.onScreenTakenByHeadUnit(taken);
        }
        logBoth("projection screen " + (taken ? "taken (video paused)"
                : "returned (video resumed)") + (sess == null ? " [no session]" : ""));
    }

    @Override
    public void onDayNightUpdate(boolean isNight) {
        // The dongle protocol has its own day/night commands; previously this was only
        // written to the log, which is why the screen did not darken.
        CarlinkitSession sess = session();
        boolean sent = sess != null && sess.setNightMode(isNight);
        CarlinkitFileLog.log(TAG, "night mode: " + isNight
                + (sent ? " -> sent to the dongle" : " (session inactive)"));
    }

    /**
     * Steering wheel keyCodes of the Fujitsu Ten framework
     * ({@code ModeMgrManager.KEYCODE_STRG_*}, base -65536). The keymap defaults stored on
     * this head unit match these values exactly (PLUS=-65535, MINUS=-65534, LEFT=-65532,
     * RIGHT=-65533), which is the evidence that steering buttons arrive as ordinary
     * KeyEvents here. Declared locally because ada-ext-api.jar is compileOnly and constants
     * are inlined at compile time anyway.
     */
    private static final int KEYCODE_STRG_PICKUP = -65528;
    private static final int KEYCODE_STRG_TALK = -65526;

    /**
     * Steering wheel buttons that the head unit delivers as a KeyEvent (not through
     * onSteeringWheelKey): volume, track switching, and now the phone and voice assistant
     * buttons. The keyCodes are proprietary (negative) and every unmapped event is written
     * to the log; that is how we discover the real codes of this head unit.
     *
     * The phone/TALK handling is UNTESTED in the car. Whether the head unit delivers
     * PICKUP/TALK to a foreground app, or intercepts them for its own telephony, is exactly
     * what the log will answer: a handled line means delivered, an UNMAPPED line means the
     * code differs, and no line at all means intercepted.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        int action = event.getAction();
        if (action == KeyEvent.ACTION_DOWN) {
            // Raw key logging is only useful while mapping a new head unit; the mapped
            // results below are what matter day to day.

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
                if (sendToDongle(CarlinkitProtocol.Command.NEXT)) {
                    CarlinkitFileLog.log(TAG, "  -> next track");
                    return true;
                }
            } else if (code == keyCode(KeymapFragment.KeyMap.LEFT)
                    || code == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
                if (sendToDongle(CarlinkitProtocol.Command.PREV)) {
                    CarlinkitFileLog.log(TAG, "  -> previous track");
                    return true;
                }
            } else if (code == KEYCODE_STRG_PICKUP || code == KeyEvent.KEYCODE_CALL) {
                phoneButton("keyEvent " + code);
                return true;
            } else if (code == KeyEvent.KEYCODE_ENDCALL) {
                endCallButton("keyEvent " + code);
                return true;
            } else if (code == KEYCODE_STRG_TALK || code == KeyEvent.KEYCODE_SEARCH) {
                talkButton("keyEvent " + code);
                return true;
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

    private CarlinkitSession session() {
        return service != null ? service.session() : null;
    }

    private boolean sendToDongle(int command) {
        CarlinkitSession sess = session();
        return sess != null && sess.sendCommand(command);
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
