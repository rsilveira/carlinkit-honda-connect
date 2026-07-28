# Carlinkit on the 2016 HR-V EXL — initial exploration (Jul 27, 2026)

Goal: use the Carlinkit dongle on the HR-V's old Android head unit, since the official app does
not support the head unit's Android version.

## Confirmed hardware

Dongle plugged into a Linux PC and successfully interrogated:

| Field | Value |
|---|---|
| USB ID | `1314:1521` |
| iManufacturer / iProduct | Magic Communication Tec. / **Auto Box** |
| Bluetooth name | **AutoKit-e38a** |
| WiFi name | **AutoBox-3c61** |
| Firmware | **2022.11.19.1218CHY** |
| bcdDevice | 4.09 |
| SoC | i.MX6 UltraLite (ARM Cortex-A7) — mass storage is `File-Stor Gadget` |

**This is the correct model.** `node-CarPlay` warns that *wired→wireless* dongles (U2W) **do
not work**; you need the type that turns an Android head unit into CarPlay/AA
(Autokit / CCPA). The `AutoKit-*` name and `productId 0x1521` — which is explicitly in
`node-CarPlay`'s supported list (`DongleDriver.ts`) — confirm the right type.

### USB interfaces
| Interface | Class | Endpoints | Use |
|---|---|---|---|
| 0 | Vendor Specific (0xFF) | `0x01` OUT, `0x81` IN — bulk 512B | **protocol channel** |
| 1 | Mass Storage (0x08) | `0x02` OUT, `0x82` IN | FAT12 8MB, label "APK" |

The mass storage contains `BoxHelper.apk` (628KB, 2020):
```
package: cn.manstep.phonemirrorBox   ("BOX Installer" / 盒子安装器)
sdkVersion: 16          <-- requires Android 4.1
targetSdkVersion: 29
```
**This is the root of the problem**: the official installer requires API 16 and the head unit
is older.

## Protocol validated on a Linux PC

16-byte header, little-endian:
```
magic     uint32 = 0x55AA55AA
length    uint32 = payload size
type      uint32 = MessageType
typeCheck uint32 = (type ^ -1) & 0xFFFFFFFF
```
The payload follows in a separate bulk transfer.

`SendOpen` (type `0x01`) payload = 7×uint32: `width, height, fps, format, packetMax,
iBoxVersion, phoneWorkMode`. Defaults used: `800, 480, 20, 5, 49152, 2, 2`.

Initialization sequence that worked:
```
SendOpen -> SendBoxSettings(JSON) -> Command(wifiEnable=1000)
         -> Command(wifi5g=25) -> Command(mic=7) -> Command(audioTransferOff=23)
         + HeartBeat (0xAA) every 2s
```
The `SendBoxSettings` payload is ASCII JSON:
`{"mediaDelay":300,"syncTime":<ms>,"androidAutoSizeW":800,"androidAutoSizeH":480}`

Responses received: `Open`, `Command`, `BoxSettings`, `SoftwareVersion`,
`BluetoothDeviceName`, `WifiDeviceName`, `HiCarLink`, `BluetoothPairedList`, `0x26`(?).

Test scripts: `test_protocol.py` (minimal) and `test_handshake.py` (full sequence).
Both run from a scratch directory with a venv holding pyusb. They require `sudo`.

Cloned reference: `node-CarPlay/` (rhysmorgan134) — source of the specification.
See also `ludwig-v/wireless-carplay-dongle-reverse-engineering` (firmware/hardware).

## Reusable assets from OpenDroidAuto

`~/git/OpenDroidAuto` (fork of MaKi983, with our own Honda stability commits):

| Module | Why it matters |
|---|---|
| `omxvideocodec` | **Decodes H.264 via native OMX/stagefright** into an `ANativeWindow`. API 15 has no `MediaCodec` (only from 16 onwards) — this module solves the hardest problem |
| `aasdk/usb/LibUsb.java` | Custom USB layer |
| `app/manager/USBManager.java` | USB device/permission management |
| Honda / i-MID integration | Steering wheel, system audio, avoiding head unit crashes |

Project config: `minSdk=15`, `targetSdk=15`, ABI `armeabi-v7a`, NDK `17.2.4988734`,
`apkName=HondaAppCenter_A1` (disguised as a Honda app). Debug APK already built under
`app/build/outputs/apk/debug/`.

## Important architectural difference

- **Android Auto (aasdk)**: the phone is an AOAP accessory; the head unit is the USB *host*
  and speaks protobuf over USB/TCP.
- **Carlinkit**: the dongle bridges wirelessly to the phone and delivers **H.264 + PCM** to
  the head unit over bulk USB, using the simple protocol above.

The Carlinkit protocol is **simpler** than Android Auto's. The heavy lifting (H.264 on API 15,
USB, Honda integration) is already solved in OpenDroidAuto.

## Honda head unit — installation rules and permissions

### File name lock
The head unit only installs an APK whose **file name** is exactly `HondaAppCenter_A1.apk`.
It validates **only the file name** — the internal package can be anything, and the app shows
up under its real name once installed. Confirmed on XDA (thread "Honda Connect Android
System"): "change the name of any app you want to install to HondaAppCenter_A1.apk".
The name must not carry suffixes such as `(1)`, `(2)`.

That is why the project sets `apkName = 'HondaAppCenter_A1'` in `build.gradle`.

### Permissions: the actual mechanism
The head unit runs the **Fujitsu Ten Display Audio** framework. The project accesses it via
`ext-libs/framework/ada-ext-api.jar` (compileOnly) + the `android.permission.ADA_SERVICE_ACCESS`
permission.

Privilege control (running with the parking brake released, using all speakers) goes through a
**system whitelist indexed by package name**:
```java
// HondaConnectManager.java:274
pControl_ = IWhiteList.getProcessControl("it.smg.hu", null);
```
Relevant classes in the jar: `com.fujitsu_ten.displayaudio.whitelist.common.{IWhiteList,
ProcessControl, Constants}`, `...statemanagement.StateMgrManager` (vehicle state),
`...steeringmenuservice.ISteeringMenuService` (steering wheel).

The **HondaPermissions** app ("S_Mike's Honda Permissions", from XDA) is the editor for that
whitelist.

**Consequence**: an app with a new package must be registered in HondaPermissions from scratch.
`it.smg.hu` is already registered and working in the car.

Alternative finding from XDA (untested): the head unit grants *driving mode* automatically to
certain packages — one user renamed the root package to `com.garmin.autooem` and got the
permission without editing the whitelist. Useful as a plan B.

## ARCHITECTURAL DECISION — integrate, don't build a separate app

Both are technically viable (the only lock is on the file name), but integrating Carlinkit
**as a second mode inside OpenDroidAuto** (keeping `it.smg.hu`) reuses:

| Already solved in the project | If it were a new app |
|---|---|
| `omxvideocodec` — H.264 on API 15 | reimplement native OMX |
| Whitelist / `ADA_SERVICE_ACCESS` | register a new package in HondaPermissions |
| Steering wheel (`ISteeringMenuService`) | reimplement |
| Day/night (`StateMgrManager`) | reimplement |
| Audio focus + `SystemAudioOutput` | reimplement |
| Head unit anti-crash hardening | rediscover the hard way |

Only the protocol layer changes: `aasdk` (Android Auto) → `carlinkit`. The Carlinkit protocol
is **simpler** than Android Auto's.

## Implementation status (Jul 27)

`carlinkit` module created under `~/git/OpenDroidAuto/carlinkit/`, registered in
`settings.gradle`:

| File | Contents |
|---|---|
| `CarlinkitProtocol.java` | message types, commands, touch actions, USB IDs |
| `MessageHeader.java` | build/parse of the 16-byte header with validation |
| `CarlinkitDriver.java` | USB bulk, handshake, reader and heartbeat threads, sendTouch |
| `MessageHeaderTest.java` | 9 tests — **all passing** |

API 15 compatibility respected: only `bulkTransfer` (API 12+), no `StandardCharsets`
(API 19+), no `java.util.function`.

### ⚠️ The build requires Java 17
With Java 25 as the default, Gradle 8.11 fails with
`Unsupported class file major version 69`. Always:
```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
./gradlew :carlinkit:testDebugUnitTest
```

### Screen resolution
The physical resolution is not recorded anywhere in the head unit software (checked
the decompiled sources — nothing). Indirect clues:
`VIDEO_RESOLUTION_DEFAULT_VALUE = 1` (480p) and `VIDEO_DPI_DEFAULT_VALUE = 140`, consistent
with ~800x480, which matches the physical screen → go with **800x480**.
Better still: derive it from `SurfaceView.surfaceChanged()` at runtime, avoiding a hardcoded
value.

### Next steps
1. **Video**: wire `onVideoData` → `OMXVideoCodec.mediaDecode()`. Risk: `omxvideocodec` was
   written for the Android Auto stream; there may be differences in SPS/PPS or in the 20 bytes
   of `VIDEO_DATA` metadata that I currently discard.
2. **Touch**: `MotionEvent` → `sendTouch()` (already implemented in the driver).
3. **Audio**: PCM → `AudioTrack` (API 15 has it). Parse the format from the `AUDIO_DATA`
   header.
4. No adb on the head unit: the test cycle is manual sideloading. Log to a file for diagnosis
   (the project already has `ODALog`).

---
