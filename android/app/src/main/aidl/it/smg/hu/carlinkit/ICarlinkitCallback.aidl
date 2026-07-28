package it.smg.hu.carlinkit;

/**
 * Callbacks from the Carlinkit service back to the UI.
 *
 * The service runs in a separate process (":usb") so that it survives the head unit killing
 * the UI process. That keeps the USB connection and the protocol heartbeat alive, which in
 * turn stops the dongle from rebooting and re-enumerating — and re-enumeration is what makes
 * Android ask for USB permission again.
 *
 * Because the boundary is now a real process boundary, the callback has to be AIDL: a plain
 * Java interface cannot cross it.
 */
interface ICarlinkitCallback {

    /** phoneType: 5 = Android Auto, 3 = CarPlay. */
    void onPhoneConnected(int phoneType);

    void onPhoneDisconnected();

    /** State reported by the dongle (searching for phone, bluetooth connected, ...). */
    void onStatus(String status);

    /**
     * The phone asked for the microphone (voice assistant or phone call). The UI process owns
     * the head unit integration, so it is the one that enables the microphone through the
     * EcNc service.
     *
     * Deliberately NOT oneway: the service waits for the head unit to release the microphone
     * before starting capture, otherwise the recording comes back empty or full of echo.
     */
    boolean onMicRequested(boolean start);
}
