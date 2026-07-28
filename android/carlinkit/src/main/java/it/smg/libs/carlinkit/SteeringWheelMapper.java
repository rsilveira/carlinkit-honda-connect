package it.smg.libs.carlinkit;

/**
 * Translates the Honda head unit steering wheel buttons into Carlinkit dongle commands.
 *
 * The {@code keyType} codes come from the {@code ISteeringMenuServiceCallback} callback
 * of the Fujitsu Ten framework, delivered by
 * {@code HondaConnectManager.HondaListener.onSteeringWheelKey(int)}.
 *
 * <p><b>Volume does not go through here.</b> The steering wheel +/- buttons are handled by the
 * head unit itself (via {@code HondaConnectManager.increaseVolume()/decreaseVolume()}), which
 * controls the amplifier. The Carlinkit protocol has no volume command — the {@code volume}
 * field of the audio messages is informational only.
 */
public final class SteeringWheelMapper {

    /** keyType codes from the Honda head unit (observed in OpenDroidAuto). */
    public static final class HondaKey {
        /** Some units use 1/2 for track change, others 3/4. */
        public static final int TRACK_UP_ALT = 1;
        public static final int TRACK_DOWN_ALT = 2;
        public static final int TRACK_UP = 3;
        public static final int TRACK_DOWN = 4;
        public static final int PICK_UP = 8;
        public static final int HANG_UP = 9;
        public static final int TALK = 10;

        private HondaKey() {
        }
    }

    private SteeringWheelMapper() {
    }

    /**
     * Converts a steering wheel keyType into the matching dongle command.
     *
     * @return a {@link CarlinkitProtocol.Command} value, or -1 if there is no mapping
     */
    public static int toCarlinkitCommand(int hondaKeyType) {
        switch (hondaKeyType) {
            case HondaKey.TRACK_UP_ALT:
            case HondaKey.TRACK_UP:
                return CarlinkitProtocol.Command.NEXT;          // 204
            case HondaKey.TRACK_DOWN_ALT:
            case HondaKey.TRACK_DOWN:
                return CarlinkitProtocol.Command.PREV;          // 205
            case HondaKey.PICK_UP:
                return CarlinkitProtocol.Command.ACCEPT_PHONE;  // 300
            case HondaKey.HANG_UP:
                return CarlinkitProtocol.Command.REJECT_PHONE;  // 301
            case HondaKey.TALK:
                // Triggers the assistant (Google Assistant on Android Auto / Siri on CarPlay)
                return CarlinkitProtocol.Command.SIRI;          // 5
            default:
                return -1;
        }
    }

    /**
     * Tells whether the keyType is a volume key and therefore must be handled by the head
     * unit instead of being sent to the dongle. Kept to document the decision — the Honda
     * head unit does not deliver volume through this callback, but through keyboard keyCodes.
     */
    public static boolean isVolumeKey(int hondaKeyType) {
        return false;
    }

    /** Human-readable name, for logging. */
    public static String keyName(int hondaKeyType) {
        switch (hondaKeyType) {
            case HondaKey.TRACK_UP_ALT:
            case HondaKey.TRACK_UP:
                return "TRACK_UP";
            case HondaKey.TRACK_DOWN_ALT:
            case HondaKey.TRACK_DOWN:
                return "TRACK_DOWN";
            case HondaKey.PICK_UP:
                return "PICK_UP";
            case HondaKey.HANG_UP:
                return "HANG_UP";
            case HondaKey.TALK:
                return "TALK";
            default:
                return "UNMAPPED(" + hondaKeyType + ")";
        }
    }
}
