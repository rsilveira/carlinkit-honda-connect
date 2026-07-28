package it.smg.libs.carlinkit;

/**
 * USB protocol constants for the Carlinkit dongle (CPC200 "Auto Box").
 *
 * Protocol validated against the real dongle (1314:1521, firmware 2022.11.19.1218CHY).
 * Specification derived from rhysmorgan134/node-CarPlay.
 *
 * Message structure: a 16-byte little-endian header, followed by an optional payload
 * in a separate bulk transfer.
 *
 *   magic     uint32 = 0x55AA55AA
 *   length    uint32 = payload size
 *   type      uint32 = MessageType
 *   typeCheck uint32 = (type ^ -1) &amp; 0xFFFFFFFF
 */
public final class CarlinkitProtocol {

    private CarlinkitProtocol() {
    }

    /** Known USB IDs of compatible dongles (Autokit/CCPA family). */
    public static final int USB_VENDOR_ID = 0x1314;
    public static final int USB_PRODUCT_ID_1520 = 0x1520;
    public static final int USB_PRODUCT_ID_1521 = 0x1521;

    public static final int MAGIC = 0x55AA55AA;
    public static final int HEADER_SIZE = 16;

    /** Message types. */
    public static final class Type {
        public static final int OPEN = 0x01;
        public static final int PLUGGED = 0x02;
        public static final int PHASE = 0x03;
        public static final int UNPLUGGED = 0x04;
        public static final int TOUCH = 0x05;
        public static final int VIDEO_DATA = 0x06;
        public static final int AUDIO_DATA = 0x07;
        public static final int COMMAND = 0x08;
        public static final int LOGO_TYPE = 0x09;
        public static final int BLUETOOTH_ADDRESS = 0x0A;
        public static final int BLUETOOTH_PIN = 0x0C;
        public static final int BLUETOOTH_DEVICE_NAME = 0x0D;
        public static final int WIFI_DEVICE_NAME = 0x0E;
        public static final int DISCONNECT_PHONE = 0x0F;
        public static final int BLUETOOTH_PAIRED_LIST = 0x12;
        public static final int MANUFACTURER_INFO = 0x14;
        public static final int CLOSE_DONGLE = 0x15;
        public static final int MULTI_TOUCH = 0x17;
        public static final int HICAR_LINK = 0x18;
        public static final int BOX_SETTINGS = 0x19;
        public static final int MEDIA_DATA = 0x2A;
        public static final int SEND_FILE = 0x99;
        public static final int HEARTBEAT = 0xAA;
        public static final int SOFTWARE_VERSION = 0xCC;

        private Type() {
        }
    }

    /** Commands sent through a COMMAND message. */
    public static final class Command {
        public static final int START_RECORD_AUDIO = 1;
        public static final int STOP_RECORD_AUDIO = 2;
        public static final int REQUEST_HOST_UI = 3;
        public static final int SIRI = 5;
        public static final int MIC = 7;
        public static final int FRAME = 12;
        public static final int BOX_MIC = 15;
        public static final int ENABLE_NIGHT_MODE = 16;
        public static final int DISABLE_NIGHT_MODE = 17;
        public static final int AUDIO_TRANSFER_ON = 22;
        public static final int AUDIO_TRANSFER_OFF = 23;
        public static final int WIFI_24G = 24;
        public static final int WIFI_5G = 25;
        public static final int BUTTON_LEFT = 100;
        public static final int BUTTON_RIGHT = 101;
        public static final int SELECT_DOWN = 104;
        public static final int SELECT_UP = 105;
        public static final int BACK = 106;
        public static final int UP = 113;
        public static final int DOWN = 114;
        public static final int HOME = 200;
        public static final int PLAY = 201;
        public static final int PAUSE = 202;
        public static final int PLAY_OR_PAUSE = 203;
        public static final int NEXT = 204;
        public static final int PREV = 205;
        public static final int ACCEPT_PHONE = 300;
        public static final int REJECT_PHONE = 301;
        public static final int REQUEST_VIDEO_FOCUS = 500;
        public static final int RELEASE_VIDEO_FOCUS = 501;
        public static final int WIFI_ENABLE = 1000;
        public static final int AUTO_CONNECT_ENABLE = 1001;
        public static final int WIFI_CONNECT = 1002;

        private Command() {
        }
    }

    /** Touch actions (payload of the TOUCH message). */
    public static final class TouchAction {
        public static final int DOWN = 14;
        public static final int MOVE = 15;
        public static final int UP = 16;

        private TouchAction() {
        }
    }

    public static boolean isSupportedDevice(int vendorId, int productId) {
        return vendorId == USB_VENDOR_ID
                && (productId == USB_PRODUCT_ID_1520 || productId == USB_PRODUCT_ID_1521);
    }

    /** Human-readable type name, for logging. */
    public static String typeName(int type) {
        switch (type) {
            case Type.OPEN: return "Open";
            case Type.PLUGGED: return "Plugged";
            case Type.PHASE: return "Phase";
            case Type.UNPLUGGED: return "Unplugged";
            case Type.TOUCH: return "Touch";
            case Type.VIDEO_DATA: return "VideoData";
            case Type.AUDIO_DATA: return "AudioData";
            case Type.COMMAND: return "Command";
            case Type.LOGO_TYPE: return "LogoType";
            case Type.BLUETOOTH_ADDRESS: return "BluetoothAddress";
            case Type.BLUETOOTH_PIN: return "BluetoothPIN";
            case Type.BLUETOOTH_DEVICE_NAME: return "BluetoothDeviceName";
            case Type.WIFI_DEVICE_NAME: return "WifiDeviceName";
            case Type.DISCONNECT_PHONE: return "DisconnectPhone";
            case Type.BLUETOOTH_PAIRED_LIST: return "BluetoothPairedList";
            case Type.MANUFACTURER_INFO: return "ManufacturerInfo";
            case Type.CLOSE_DONGLE: return "CloseDongle";
            case Type.MULTI_TOUCH: return "MultiTouch";
            case Type.HICAR_LINK: return "HiCarLink";
            case Type.BOX_SETTINGS: return "BoxSettings";
            case Type.MEDIA_DATA: return "MediaData";
            case Type.SEND_FILE: return "SendFile";
            case Type.HEARTBEAT: return "HeartBeat";
            case Type.SOFTWARE_VERSION: return "SoftwareVersion";
            default: return "Unknown(0x" + Integer.toHexString(type) + ")";
        }
    }
}
