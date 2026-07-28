package it.smg.hu;

import android.content.Context;

import androidx.multidex.MultiDexApplication;

import it.smg.hu.carlinkit.CarlinkitFileLog;
import it.smg.hu.config.ODALog;
import it.smg.hu.config.Settings;
import it.smg.hu.manager.HondaConnectManager;
import it.smg.libs.common.Log;

/**
 * Application of the standalone Carlinkit app.
 *
 * It exists because Settings and HondaConnectManager are static singletons that need a
 * Context before any Activity runs: {@code Settings.instance()} returns null until
 * {@code Settings.build()} is called, and {@code HondaConnectManager.instance()} returns
 * null until {@code init()} stores the application Context. CarlinkitActivity only calls
 * {@code instance()}, so without this class both would NPE.
 *
 * <p><b>Runs once per process.</b> CarlinkitService is declared with
 * {@code android:process=":usb"}, so Android creates a second instance of this Application in
 * that process and calls {@code attachBaseContext}/{@code onCreate} again. What each process
 * needs is different:
 * <ul>
 *   <li>{@code Settings.build} + {@code Log.init}: <b>both</b> processes. CarlinkitSession
 *       lives in ":usb" and reads Settings and logs through {@code Log} on every frame;
 *       without them it would NPE immediately.</li>
 *   <li>{@code HondaConnectManager.init}: <b>main process only</b>. It binds the Fujitsu
 *       services (steering wheel, day/night, audio focus); initialising it in ":usb" would
 *       bind them a second time, with two clients competing for the same audio focus and
 *       receiving duplicated steering wheel keys. The service process has no UI either, so
 *       it has nothing to do with those events — the Activity handles them and forwards the
 *       result over the binder.</li>
 * </ul>
 *
 * Pruned relative to the fork's ODAApplication:
 *  - USBManager/WIFIManager: the AOAP/WiFi stack of the Android Auto cable mode, dropped.
 *    Behaviour is unchanged, since the fork already skipped them for the ".carlinkit"
 *    package to stop the two apps from receiving each other's "it.smg.hu.*" broadcasts.
 *  - AppBadge: belongs to the Android Auto background notifications, not copied here.
 *  - it.smg.libs.aasdk.Runtime.initLog(log) replaced by the equivalent
 *    it.smg.libs.common.Log.init(log) — that is literally all initLog did — so the 415MB
 *    aasdk module is not needed.
 */
public class ODAApplication extends MultiDexApplication {

    private static final String TAG = "ODAApplication";

    @Override
    public void onTerminate() {
        super.onTerminate();
        Log.shutdown();
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);

        // Needed in every process: CarlinkitSession (":usb") reads Settings and logs via Log
        Settings.build(base);
        Log.init(new ODALog());
    }

    public void onCreate() {
        super.onCreate();

        // Which process are we? Read from /proc/self/cmdline — see
        // CarlinkitFileLog.processName(), shared so the parsing exists in one place only.
        String process = CarlinkitFileLog.processName();

        if (CarlinkitFileLog.isUsbProcess()) {
            // Bare minimum only: Settings and Log are already up from attachBaseContext,
            // and the Honda integration must stay in the main process (see the class doc).
            Log.i(TAG, "onCreate in the USB service process (" + process
                    + ") — Honda integration NOT initialised here");
            return;
        }

        Log.i(TAG, "onCreate in the main process (" + process + ")");

        // The Honda integration is what delivers the steering wheel, day/night and volume
        if (Settings.instance().advanced.hondaIntegrationEnabled()) {
            HondaConnectManager.init(getApplicationContext());
        }
    }
}
