package it.smg.hu.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageButton;

import androidx.fragment.app.FragmentActivity;

import it.smg.hu.R;
import it.smg.hu.carlinkit.CarlinkitFileLog;
import it.smg.hu.config.Settings;
import it.smg.hu.projection.InputDevice;
import it.smg.hu.ui.settings.AdvancedFragment;
import it.smg.hu.ui.settings.CarFragment;
import it.smg.hu.ui.settings.ConnectivityFragment;
import it.smg.hu.ui.settings.KeymapFragment;
import it.smg.hu.ui.settings.VideoFragment;
import it.smg.libs.common.Log;

public class SettingsActivity extends FragmentActivity implements InputDevice.OnKeyHolder {

    private static final String TAG = "SettingsActivity";
    private View.OnKeyListener keyListener_;
    private Settings settings_;

    /**
     * Writes to the file log, since the head unit has no adb and ODALog only reaches logcat.
     *
     * This screen closes by itself every so often when the bottom icons are tapped, and it had
     * never been captured: the crash handler writes uncaught exceptions to the file log and
     * nothing ever appeared there, so it is not a Java exception — the process is being killed
     * or something native is dying. Without any trace of how far it got, there was nothing to
     * work from.
     *
     * Free heap is included because the likeliest suspect is memory: this screen opens on top of
     * a live session, so the USB service, the OMX decoder and the AudioTrack are all still
     * allocated, on a device with Android 4.0.4. If the log stops right after a tap and the heap
     * was tight, that points at the low memory killer.
     */
    private static void logStep(String what) {
        Runtime rt = Runtime.getRuntime();
        long usedKb = (rt.totalMemory() - rt.freeMemory()) / 1024;
        long maxKb = rt.maxMemory() / 1024;
        CarlinkitFileLog.log(TAG, what + " | heap " + usedKb + "K/" + maxKb + "K");
    }

    /**
     * Swaps the fragment shown by the bottom icons.
     *
     * commitAllowingStateLoss() rather than commit(): a tap processed after the activity was
     * paused throws IllegalStateException ("Can not perform this action after
     * onSaveInstanceState"), and the head unit pauses activities on its own whenever it puts
     * something on screen. Losing the state of a settings screen is harmless; crashing is not.
     */
    private void showFragment(String name, androidx.fragment.app.Fragment fragment) {
        logStep("bottom icon: " + name);
        try {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.main_content, fragment)
                    .commitAllowingStateLoss();
        } catch (RuntimeException e) {
            CarlinkitFileLog.log(TAG, "failed to show " + name, e);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        settings_ = Settings.instance();

        logStep("onCreate");

        setContentView(R.layout.activity_settings);

        ImageButton carImage = findViewById(R.id.car_settings);
        carImage.setOnClickListener(
                listener -> showFragment("car", new CarFragment())
        );

        ImageButton advancedImage = findViewById(R.id.advanced_settings);
        advancedImage.setOnClickListener(
                listener -> showFragment("advanced", new AdvancedFragment())
        );

        ImageButton videoImage = findViewById(R.id.video_settings);
        videoImage.setOnClickListener(
                listener -> showFragment("video", new VideoFragment())
        );

        ImageButton keymapImage = findViewById(R.id.keymap_settings);
        keymapImage.setOnClickListener(
                listener -> showFragment("keymap", new KeymapFragment())
        );

        ImageButton connImage = findViewById(R.id.conn_settings);
        connImage.setOnClickListener(
                listener -> showFragment("connectivity", new ConnectivityFragment())
        );

        showFragment("car (initial)", new CarFragment());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Log.isDebug()) Log.d(TAG, "onResume");
        logStep("onResume");
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (Log.isDebug()) Log.d(TAG, "onPause");
        logStep("onPause");
    }

    /**
     * A normal exit logs onDestroy. Its absence after a bottom-icon tap means the process was
     * killed instead of the screen being closed, which is the distinction that was missing.
     */
    @Override
    protected void onDestroy() {
        logStep("onDestroy (closed normally)");
        super.onDestroy();
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        logStep("*** onLowMemory — the system is reclaiming memory");
    }

    @Override
    public void onBackPressed() {
        logStep("back pressed");
        Intent i = new Intent();
        setResult(Activity.RESULT_OK, i);
        super.onBackPressed();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (Log.isDebug()) Log.d(TAG, "onKeyDown: " + keyCode);
        return keyListener_ != null ? keyListener_.onKey(null, keyCode, event) : super.onKeyDown(keyCode, event);
    }

    @Override
    public void setOnKeyListener(View.OnKeyListener listener) {
        keyListener_ = listener;
    }

}
