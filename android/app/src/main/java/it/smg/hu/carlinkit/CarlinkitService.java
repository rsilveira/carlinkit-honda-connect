package it.smg.hu.carlinkit;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.IBinder;
import android.os.RemoteException;
import android.view.Surface;

import it.smg.hu.R;
import it.smg.libs.carlinkit.AudioMessage;
import it.smg.libs.common.Log;

/**
 * Owns the Carlinkit USB connection, in a <b>separate process</b> ({@code :usb}).
 *
 * <p><b>Why a separate process.</b> The head unit kills the UI process without warning — no
 * {@code onDestroy} is ever logged. While the connection lived in that process it died with
 * it, the protocol heartbeat stopped, and the dongle's ~9s watchdog rebooted it. The dongle
 * then came back as a <i>new</i> USB device (observed: 087, 090, 091, 092, 093, 094 within
 * minutes), and Android grants USB permission per device instance — so the permission dialog
 * appeared on nearly every launch.
 *
 * <p>Running here, the connection and the heartbeat survive the UI being killed, the dongle
 * is not re-enumerated, and the permission stays valid.
 *
 * <p>Consequence: the UI boundary is a real process boundary, so communication is AIDL
 * ({@link ICarlinkitService} / {@link ICarlinkitCallback}). The head unit integration
 * (steering wheel, audio focus, microphone) stays in the UI process, which is where the
 * Fujitsu services are bound; this service asks for the microphone through the callback.
 */
public final class CarlinkitService extends Service {

    private static final String TAG = "CarlinkitService";
    private static final int NOTIFICATION_ID = 4884;

    public static final String ACTION_STOP = "it.smg.hu.carlinkit.STOP";

    /**
     * Sent by {@link CarlinkitUsbReceiver} when the dongle is attached, carrying the device in
     * {@link UsbManager#EXTRA_DEVICE}. The broadcast grants implicit USB permission to the whole
     * app, so opening the device from here needs no dialog.
     */
    public static final String ACTION_DEVICE_ATTACHED = "it.smg.hu.carlinkit.DEVICE_ATTACHED";

    private CarlinkitSession session;
    private UsbDeviceConnection connection;
    private boolean started;
    private ICarlinkitCallback callback;

    private final ICarlinkitService.Stub binder = new ICarlinkitService.Stub() {

        @Override
        public boolean isStarted() {
            return CarlinkitService.this.isSessionActive();
        }

        @Override
        public boolean ensureStarted(UsbDevice device) {
            return CarlinkitService.this.openDongle(device);
        }

        @Override
        public void registerCallback(ICarlinkitCallback cb) {
            callback = cb;
            logBoth("UI callback registered");
        }

        @Override
        public void unregisterCallback() {
            callback = null;
        }

        @Override
        public void attachSurface(Surface surface, int width, int height) {
            synchronized (CarlinkitService.this) {
                if (session != null) {
                    session.attachSurface(surface, width, height);
                    logBoth("surface attached (" + width + "x" + height + ")");
                }
            }
        }

        @Override
        public void detachSurface() {
            synchronized (CarlinkitService.this) {
                if (session != null) {
                    session.detachSurface();
                    logBoth("surface detached — USB connection kept alive");
                }
            }
        }

        @Override
        public void onSurfaceReady() {
            synchronized (CarlinkitService.this) {
                if (session != null) {
                    session.onSurfaceReady();
                }
            }
        }

        @Override
        public boolean sendTouch(int action, float xRatio, float yRatio) {
            CarlinkitSession s = session;
            return s != null && s.sendTouch(action, xRatio, yRatio);
        }

        @Override
        public boolean sendCommand(int command) {
            CarlinkitSession s = session;
            return s != null && s.sendCommand(command);
        }

        @Override
        public boolean setNightMode(boolean night) {
            CarlinkitSession s = session;
            return s != null && s.setNightMode(night);
        }

        @Override
        public boolean sendSteeringWheelKey(int hondaKeyType) {
            CarlinkitSession s = session;
            return s != null && s.onSteeringWheelKey(hondaKeyType);
        }

        @Override
        public void shutdown() {
            logBoth("shutdown requested by the UI");
            stopSession();
            stopSelf();
        }
    };

    /** Bridges session events to the UI process, tolerating a dead UI. */
    private final CarlinkitSession.Callback sessionCallback = new CarlinkitSession.Callback() {

        @Override
        public void onPhoneConnected(int phoneType) {
            ICarlinkitCallback cb = callback;
            if (cb != null) {
                try {
                    cb.onPhoneConnected(phoneType);
                } catch (RemoteException e) {
                    callback = null;   // UI process is gone
                }
            }
        }

        @Override
        public void onPhoneDisconnected() {
            ICarlinkitCallback cb = callback;
            if (cb != null) {
                try {
                    cb.onPhoneDisconnected();
                } catch (RemoteException e) {
                    callback = null;
                }
            }
        }

        @Override
        public void onStatus(String status) {
            ICarlinkitCallback cb = callback;
            if (cb != null) {
                try {
                    cb.onStatus(status);
                } catch (RemoteException e) {
                    callback = null;
                }
            }
        }

        @Override
        public boolean onMicRequested(boolean start) {
            ICarlinkitCallback cb = callback;
            if (cb == null) {
                // No UI: the head unit microphone cannot be enabled, so capture would be
                // silent or full of echo. Better to report failure than to record noise.
                logBoth("microphone requested but the UI is not connected");
                return false;
            }
            try {
                return cb.onMicRequested(start);
            } catch (RemoteException e) {
                callback = null;
                return false;
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        CarlinkitFileLog.init(this);
        logBoth("service created in process " + android.os.Process.myPid());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            logBoth("stop requested");
            stopSession();
            stopSelf();
            return START_NOT_STICKY;
        }
        startForegroundCompat();
        if (intent != null && ACTION_DEVICE_ATTACHED.equals(intent.getAction())) {
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (device != null) {
                // Takes advantage of the implicit permission carried by the broadcast: no
                // dialog here. The dongle re-enumerates often, so the previous session (if
                // any) points to a device that no longer exists — drop it first.
                logBoth("device received from the receiver: " + device.getDeviceName());
                stopSession();
                if (openDongle(device)) {
                    logBoth("connected with no permission dialog");
                } else {
                    logBoth("failed to connect from the broadcast");
                }
            }
        }
        // START_STICKY: if the head unit kills this process too, come back and let the UI
        // re-establish the session.
        return START_STICKY;
    }

    private void startForegroundCompat() {
        try {
            Intent open = new Intent(this, CarlinkitActivity.class);
            PendingIntent pi = PendingIntent.getActivity(this, 0, open, 0);
            Notification n = new Notification.Builder(this)
                    .setContentTitle("Carlinkit")
                    .setContentText("Dongle connected")
                    .setSmallIcon(R.drawable.logo)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .getNotification();
            startForeground(NOTIFICATION_ID, n);
        } catch (Throwable t) {
            Log.e(TAG, "could not enter foreground", t);
        }
    }

    private synchronized boolean isSessionActive() {
        return started && session != null;
    }

    /**
     * Opens the dongle if needed.
     *
     * The USB permission was granted in the UI process, but it applies to the whole app
     * (same UID), so opening it here works.
     */
    private synchronized boolean openDongle(UsbDevice device) {
        if (started && session != null) {
            logBoth("session already active — reusing the USB connection");
            return true;
        }
        UsbManager usb = (UsbManager) getSystemService(USB_SERVICE);
        if (usb == null) {
            return false;
        }
        if (!usb.hasPermission(device)) {
            logBoth("no USB permission for " + device.getDeviceName());
            return false;
        }
        connection = usb.openDevice(device);
        if (connection == null) {
            logBoth("openDevice failed");
            return false;
        }
        session = new CarlinkitSession(20);
        session.setCallback(sessionCallback);
        if (!session.start(device, connection)) {
            logBoth("session.start failed");
            closeConnection();
            session = null;
            return false;
        }
        started = true;
        logBoth("session started, holding the USB connection");
        return true;
    }

    private synchronized void stopSession() {
        started = false;
        if (session != null) {
            session.stop();
            session = null;
        }
        closeConnection();
    }

    private void closeConnection() {
        if (connection != null) {
            try {
                connection.close();
            } catch (Throwable ignored) {
            }
            connection = null;
        }
    }

    private void logBoth(String msg) {
        Log.i(TAG, msg);
        CarlinkitFileLog.log(TAG, msg);
    }

    @Override
    public void onDestroy() {
        logBoth("onDestroy");
        stopSession();
        try {
            stopForeground(true);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }
}
