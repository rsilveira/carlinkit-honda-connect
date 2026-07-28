package it.smg.hu.projection;

import android.view.View;

/**
 * Key holder contract between SettingsActivity and KeymapFragment.
 *
 * In the fork this class was the Android Auto input device: it extended
 * {@code it.smg.libs.aasdk.projection.InputDevice} and forwarded touches and buttons into
 * the projection through {@code sendTouchEvent}/{@code sendButtonEvent}, native methods
 * implemented in {@code aasdk-jni.so}.
 *
 * That whole body is dead in the Carlinkit app and was removed:
 *  - the aasdk module (and its {@code aasdk-jni.so}) is not part of this project, so the
 *    static {@code nativeInit()} of the base class would blow up with
 *    UnsatisfiedLinkError as soon as the class was loaded;
 *  - the Carlinkit dongle has its own protocol: touches go through
 *    {@code CarlinkitSession.onTouch()} and the steering wheel through
 *    {@code CarlinkitActivity.onSteeringWheelKey()}, neither of which goes through here.
 *
 * The class was never instantiated by the copied code — only {@code OnKeyHolder} is used
 * ({@code SettingsActivity implements InputDevice.OnKeyHolder} and KeymapFragment casts
 * the Activity to it to capture the key being remapped). Keeping the name and the nested
 * interface means those two files need no changes.
 */
public class InputDevice {

    private InputDevice() {
    }

    public interface OnKeyHolder {
        void setOnKeyListener(View.OnKeyListener listener);
    }
}
