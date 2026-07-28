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
 * <p><b>Why this gets rid of the permission prompt.</b> When Android delivers the
 * {@code USB_DEVICE_ATTACHED} intent to an app that declares the matching
 * {@code device_filter}, that app receives <b>implicit permission</b> for the device — with no
 * dialog. By opening the connection here, as soon as the broadcast arrives, we take advantage
 * of that permission.
 *
 * <p>The test logs showed why this was necessary: the dongle re-enumerates on its own
 * (devices 078, 079, 082, 083 within a few minutes) and every enumeration is a brand new
 * device, requiring authorization all over again. On top of that the head unit kills the app
 * process without calling {@code onDestroy}, so there is no way to keep state in the Activity
 * alone.
 *
 * <p>It starts the <b>Service</b>, never the Activity: that way the dongle is connected in the
 * background without interrupting the FM radio.
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
