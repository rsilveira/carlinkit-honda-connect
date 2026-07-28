package it.smg.hu;

import android.content.Context;

import androidx.multidex.MultiDexApplication;

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

        Settings.build(base);
        Log.init(new ODALog());
    }

    public void onCreate() {
        super.onCreate();

        // The Honda integration is what delivers the steering wheel, day/night and volume
        if (Settings.instance().advanced.hondaIntegrationEnabled()) {
            HondaConnectManager.init(getApplicationContext());
        }
    }
}
