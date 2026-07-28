package it.smg.hu.carlinkit;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Binder;
import android.os.IBinder;
import android.view.SurfaceView;

import it.smg.hu.R;
import it.smg.libs.common.Log;

/**
 * Keeps the connection to the Carlinkit dongle alive independently of the Activity.
 *
 * <p><b>Why it exists.</b> While the connection belonged to the Activity, leaving the app
 * closed the {@code UsbDeviceConnection}; the head unit re-enumerated the dongle (observed
 * in the log: devices 070, 071, 072, 073, 075, 076 within a few minutes) and every
 * enumeration is a brand new device as far as Android is concerned — requiring USB
 * permission all over again. By keeping the connection here, the dongle is not re-enumerated
 * and the permission stays valid.
 *
 * <p>What stays in the Activity: only the {@code Surface}. On exit, the video stops and the
 * audio is muted (so it does not compete with the FM radio), but the heartbeat keeps running
 * and the phone stays paired — coming back is instant.
 *
 * <p>Runs in the foreground to reduce the chance of being killed for low memory; the head
 * unit is limited and the whitelist {@code OomSetPerm} helps, but is not enough on its own.
 */
public final class CarlinkitService extends Service {

    private static final String TAG = "CarlinkitService";
    private static final int NOTIFICATION_ID = 4884;

    public static final String ACTION_STOP = "it.smg.hu.carlinkit.STOP";
    /** Sent by the receiver when the USB broadcast arrives, carrying the implicit permission. */
    public static final String ACTION_DEVICE_ATTACHED = "it.smg.hu.carlinkit.DEVICE_ATTACHED";

    private final CarlinkitBinder binder = new CarlinkitBinder();
    private CarlinkitSession session;
    private UsbDeviceConnection connection;
    private boolean started;

    public final class CarlinkitBinder extends Binder {
        public CarlinkitService service() {
            return CarlinkitService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            Log.i(TAG, "stop requested");
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
                // any) points to a device that no longer exists.
                CarlinkitFileLog.init(this);
                logBoth("device received from the receiver: " + device.getDeviceName());
                UsbManager usb = (UsbManager) getSystemService(USB_SERVICE);
                if (usb != null && usb.hasPermission(device)) {
                    stopSession();   // drops the old connection (stale device)
                    if (ensureStarted(device, usb, null)) {
                        logBoth("connected with no permission dialog");
                    } else {
                        logBoth("failed to connect from the broadcast");
                    }
                } else {
                    logBoth("NO implicit permission for the received device"
                            + " — the dialog will be required");
                }
            }
        }
        // START_STICKY: if the head unit kills the process, the service comes back and reconnects
        return START_STICKY;
    }

    private void startForegroundCompat() {
        try {
            Intent open = new Intent(this, CarlinkitActivity.class);
            PendingIntent pi = PendingIntent.getActivity(this, 0, open, 0);

            // API 15: Notification.Builder has existed since 11; no channels.
            Notification n = new Notification.Builder(this)
                    .setContentTitle("Carlinkit")
                    .setContentText("Dongle connected")
                    .setSmallIcon(R.drawable.logo)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .getNotification();
            startForeground(NOTIFICATION_ID, n);
        } catch (Throwable t) {
            Log.e(TAG, "failed to go foreground", t);
        }
    }

    /**
     * Opens the dongle and starts the session, if it is not active yet.
     *
     * @return true if there is an active session on return
     */
    public synchronized boolean ensureStarted(UsbDevice device, UsbManager usbManager,
                                              CarlinkitSession.Callback callback) {
        if (started && session != null) {
            if (callback != null) {
                session.setCallback(callback);
            }
            logBoth("session already active — reusing the USB connection");
            return true;
        }
        connection = usbManager.openDevice(device);
        if (connection == null) {
            Log.e(TAG, "openDevice failed");
            return false;
        }
        session = new CarlinkitSession(20);
        if (callback != null) {
            session.setCallback(callback);
        }
        if (!session.start(device, connection)) {
            Log.e(TAG, "session.start failed");
            closeConnection();
            session = null;
            return false;
        }
        started = true;
        logBoth("session started in the service");
        return true;
    }

    public synchronized CarlinkitSession session() {
        return session;
    }

    public synchronized boolean isStarted() {
        return started && session != null;
    }

    /** Attaches the Activity Surface to the existing session. */
    public synchronized void attachSurface(SurfaceView view) {
        if (session != null) {
            session.attachSurface(view);
        }
    }

    /** Releases the Surface, keeping the USB connection and the heartbeat. */
    public synchronized void detachSurface() {
        if (session != null) {
            session.detachSurface();
        }
    }

    public synchronized void setCallback(CarlinkitSession.Callback cb) {
        if (session != null) {
            session.setCallback(cb);
        }
    }

    private synchronized void stopSession() {
        started = false;
        if (session != null) {
            session.stop();
            session = null;
        }
        closeConnection();
    }

    private void logBoth(String msg) {
        Log.i(TAG, msg);
        CarlinkitFileLog.log(TAG, msg);
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

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy");
        stopSession();
        try {
            stopForeground(true);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }
}
