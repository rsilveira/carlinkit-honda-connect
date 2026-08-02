# Carlinkit on the 2016 HR-V EXL — findings

Goal: use the Carlinkit dongle on the HR-V's old Android head unit, since the official app does
not support the head unit's Android version.

The first part of this document is the initial exploration (Jul 27, 2026): reverse engineering
the protocol on a PC and getting it to run on the head unit. The second part, *Dongle behaviour
in real use*, covers what was learned once the app was used in the car — a different class of
problem, and the one that took the most time.

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
The physical resolution is not recorded anywhere in the head unit software. The indirect
evidence points at ~800x480: `VIDEO_RESOLUTION_DEFAULT_VALUE = 1` (480p) and
`VIDEO_DPI_DEFAULT_VALUE = 140`.

Rather than hardcoding it, the app derives the size from `SurfaceView.surfaceChanged()` at
runtime and sends that to the dongle in the Open command. This turned out to matter: without
`FLAG_FULLSCREEN` the status bar takes 63px and the Surface is 800x**417**, so a hardcoded
800x480 makes the video overflow and the touch coordinates land in the wrong place.

### Status
Everything above is implemented and validated in the car. The README has the feature table,
and `docs/` covers the head unit, video, audio and microphone in detail.

Two constraints are worth repeating, because they shaped the design:

- **`omxvideocodec` was written for the Android Auto stream.** It decodes the Carlinkit H.264
  stream, but the 20 bytes of `VIDEO_DATA` metadata are discarded rather than parsed.
- **There is no adb on the head unit.** The test cycle is manual sideloading from a USB stick,
  which is why the app writes its own rotating file log — without it there is no way to see
  what happened.

---

# Dongle behaviour in real use (Jul 28 – Aug 1, 2026)

Everything above came from getting the protocol working. This section covers what was learned
once the app ran in the car for real, which turned out to be a different set of problems: not
"does the protocol work" but "what does the dongle do when nobody is watching". All numbers
here are measured from the app's own file logs.

## The dongle re-enumerates constantly

Its watchdog reboots it roughly 9s after the host heartbeat stops, which happens whenever the
app is closed. Every reboot is a new USB device instance. Device numbers observed within single
test sessions:

| Session | Launches | Distinct device numbers |
|---|---|---|
| Jul 28 (a) | 8 | 6 |
| Jul 28 (b) | 7 | 5 |
| Jul 29 | 7 | 5 |
| Aug 1 | 11 | 6 |

**Consequence: the USB permission dialog cannot be avoided.** Android grants USB permission per
device instance, so a new instance needs a new authorization.

The documented escape is a `device_filter` plus a `USB_DEVICE_ATTACHED` intent filter, which
grants implicit permission with no dialog. It is implemented in `CarlinkitUsbReceiver` and
**never fires on this head unit**: across 11 test sessions and 4274 log lines, the receiver was
invoked zero times. The firmware does not deliver the broadcast to apps. The code is kept
because it is correct on a standard device and costs nothing, but the Activity's explicit
request is the path that actually runs.

Do not move the filter to the Activity. It was tried and reverted: the app relaunched itself
whenever the dongle re-enumerated, which made listening to the FM radio impossible.

## Reopening too soon kills the session

Leaving the app releases the dongle, which starts its reboot cycle. Reopening during that
window grabs a device instance that is already dying.

| Time between leaving and reopening | Result |
|---|---|
| 2s | session died 74ms after starting |
| 5s | session died 156ms after starting |
| 41s | worked normally |

The app now waits before searching again after a drop, and reuses the retry loop so the dead
instance has time to leave the device list.

**How long re-enumeration actually takes is not settled.** Two reconnections were measured at
4.036s and 4.034s from detach to found — but the search delay in effect was 4000ms, so those
numbers only prove the dongle was *already back* at the 4s mark, not how much sooner. Had it
needed longer, the log would show `dongle not in the device list — retry`, which does not
appear. `tools/bench_reconnect.py` exists to measure this properly.

## The dongle can wedge: enumerated, opened, and silent

The failure that is hardest to diagnose, because nothing reports an error. The dongle appears
on the bus, accepts the Open command, the session starts — and not a single byte comes back.
Three consecutive launches in one session sat silent for 99s, 15s and 33s, with the screen
reading `session started (800x480)` the whole time.

Reopening the app does not fix it. Only cutting power to the dongle does: after the car was
switched off and on, the device number jumped from 115 to 119 and it worked immediately.

**Detection is a timeout, since there is no error to catch.** A healthy dongle reports its
state about 1s after the session starts — measured 1.019s, 1.018s and 1.017s across three good
sessions. The app waits 10s, an order of magnitude above that, then tells the user to power
cycle the car.

### Status messages are not a liveness signal

The obvious implementation is to watch for the first status command. It is wrong.

A session reopened while the phone is still connected receives **no status at all** and is
perfectly healthy. One measured session had zero status messages and zero phone-connected
events, yet the voice assistant was working 35s in. Keying the watchdog on status would have
warned on a working setup.

Liveness has to come from traffic: video, audio, or any protocol message. During active
projection a bench capture measured **46.8 video packets/s and 12.3 audio packets/s** — a
packet every 21ms — so absence of traffic is unambiguous while projecting. What is not yet
measured is an idle link with the phone connected and the screen untouched, which is why the
current check only fires when *nothing at all* was received on the session, rather than using
a sliding window.

## Two Android lifecycle traps on this head unit

**The permission dialog runs `onResume`, before the permission broadcast arrives.** The dialog
takes the focus away and gives it back, so any state reset in `onResume` happens *before* the
answer is processed. Resetting retry counters there made every denial start over from zero, so
the retry limit was never reached and the app reopened the dialog indefinitely. The log
signature is the attempt counter never advancing: `permission denied on attempt 0` repeated.

**The head unit kills the app process without calling `onDestroy`.** Cleanup that only runs in
`onDestroy` does not run. This is why the USB connection lives in a Service rather than the
Activity, and why absence of an `onDestroy` line in the log is itself evidence of a kill.

## Retrieving logs: a trap worth knowing

The app writes to a USB stick when one is mounted. On a PC, the stick can show up as *connected
but not mounted* — the old mount directory stays behind, empty, giving the impression that the
app never wrote anything. Check that the device is actually mounted before concluding the log
is missing.

---
