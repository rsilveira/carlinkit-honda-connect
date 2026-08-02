package it.smg.hu.carlinkit;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import it.smg.libs.carlinkit.CarlinkitProtocol;
import it.smg.libs.common.Log;

/**
 * Receives {@code USB_DEVICE_ATTACHED} and hands the dongle over to {@link CarlinkitService}.
 *
 * <p><b>The intent was to avoid the permission prompt, and on this head unit it does not
 * work.</b> When Android delivers {@code USB_DEVICE_ATTACHED} to an app that declares the
 * matching {@code device_filter}, that app receives implicit permission for the device, with
 * no dialog. That is the documented mechanism and the reason this receiver exists — but the
 * broadcast never arrives here. Across 11 test sessions and 4274 log lines this receiver was
 * invoked <b>zero times</b>, and the permission dialog appeared on essentially every launch.
 * Something in the head unit firmware does not deliver the broadcast to apps.
 *
 * <p>The receiver is kept because it is harmless, it is the correct mechanism on a standard
 * Android device, and a firmware update could start delivering the broadcast. Do not assume
 * the prompt is handled: the Activity still has to request permission explicitly, and that
 * path is the one that runs in practice.
 *
 * <p><b>Do not move the filter to the Activity.</b> It was tried and reverted: leaving the app
 * releases the dongle, the head unit re-enumerates it, and the intent relaunched the Activity —
 * making it impossible to listen to the FM radio. See the note in {@code AndroidManifest.xml}.
 *
 * <p>The dongle re-enumerates constantly on its own (devices 078, 079, 082, 083 within a few
 * minutes) and every enumeration is a brand new device, requiring authorization all over again.
 * On top of that the head unit kills the app process without calling {@code onDestroy}, so
 * there is no way to keep state in the Activity alone.
 *
 * <p>It starts the <b>Service</b>, never the Activity: that way the dongle would be connected
 * in the background without interrupting the FM radio.
 */
public final class CarlinkitUsbReceiver extends BroadcastReceiver {

    private static final String TAG = "CarlinkitUsbReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if (device == null) {
            return;
        }
        if (!CarlinkitProtocol.isSupportedDevice(device.getVendorId(), device.getProductId())) {
            return;
        }
        CarlinkitFileLog.init(context);
        String msg = "dongle attached (" + device.getVendorId() + ":" + device.getProductId()
                + " " + device.getDeviceName() + ") — handing it over to the service";
        Log.i(TAG, msg);
        CarlinkitFileLog.log(TAG, msg);

        // Forwards the device to the service: the implicit permission from this broadcast is
        // valid for it as well, since it is the same app (same UID).
        Intent svc = new Intent(context, CarlinkitService.class);
        svc.setAction(CarlinkitService.ACTION_DEVICE_ATTACHED);
        svc.putExtra(UsbManager.EXTRA_DEVICE, device);
        try {
            context.startService(svc);
        } catch (Throwable t) {
            Log.e(TAG, "failed to start the service", t);
            CarlinkitFileLog.log(TAG, "failed to start the service", t);
        }
    }
}
