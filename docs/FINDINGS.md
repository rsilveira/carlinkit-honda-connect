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
`{"mediaDelay":1000,"syncTime":<ms>,"androidAutoSizeW":800,"androidAutoSizeH":480}`

⚠️ That first field read `300` until 26/Aug, and 300 is the bottom of the range and the value most
prone to stuttering. The box documents 1000 ms as its default, a 300 to 2000 range, and that a
larger delay makes stuttering less likely. See "Wireless degradation" below.

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


# Steering wheel, calls and reverse gear: measured in the car (Aug 10, 2026)

Four instrumented sessions, with the phone unpaired from the car's Bluetooth so that calls
would go through Android Auto instead of the native telephony.

## ⚠️ Correction: the wheel service always bound

The previous version of this document claimed the bind to `ISteeringMenuService` "never
succeeded, 7 of 7 sessions", based on `wheelServiceBound=false` in the diagnostic line. That
conclusion was wrong, and the mistake is worth recording because it is easy to repeat.

`bindService` is asynchronous. The diagnostic line runs in `onResume`; `onServiceConnected`
lands 13 to 30 ms later. Reading the flag at that moment always shows false:

```
08:58:13.520  wheelServiceBound=false     <- diagnostic line
08:58:13.550  wheel service CONNECTED     <- 30ms later, in all four sessions
```

The resolution is unambiguous and needs no permission:

```
action resolves to 1 service(s)
com.fujitsu_ten.displayaudio.steeringmenuservice/....service.SteeringMenuService   perm=null
bindService(...) returned true
```

**Lesson: never conclude a failure from a synchronous flag read before an asynchronous
callback.** The package scan written to work around the phantom problem was removed; the
resolution logging stayed, since it costs one line and detects a firmware change.

## The head unit intercepts the phone and TALK buttons

The TALK button arrives as a `KeyEvent` with the framework code, and the app handles it
correctly. The command even reaches the phone:

```
08:58:43.291  talk button (keyEvent -65526) sent      <- SIRI sent to the dongle
08:58:43.346  onUserLeaveHint — user left the app     <- the head unit takes the screen
08:58:43.396  microphone: EcNc session started        <- the phone ASKED for the mic
```

That third line is the proof the command worked: 105 ms after sending, the dongle requested
the microphone, which is what a voice assistant does. But the head unit switches to its own
screen in parallel, so the projection is gone.

There is no hook to refuse it. `onFinishView`, which returns a boolean and could deny the
transition, is **never called**. The unit does not ask, it switches.

`rcvStrgKeyEvent` (the ModeMgr route) never fired either, so the head unit does not deliver
these keys to apps through any of the three routes; it consumes them.

**Status: not solvable from inside the app with the interfaces known today.** The buttons work,
in the sense that the command reaches the phone, but the screen is lost in the same gesture.

## Reverse gear: the signal is focus, not the vehicle state

The vehicle route does not exist on this head unit. Zero `head unit state` lines across four
sessions, while `dayNightState` arrived 10 times in the same logs: the StateMgr callback is
alive and the unit simply never publishes `parkingSensor` or `videoAddress`.

What reverse gear does produce is exactly one Java event:

```
09:02:17.482  alive | dongle sending=true phone=false
09:02:18.435  focus lost
(log ends, process dead)
```

No `onPause`, `onStop`, `onTrimMemory` or `onSaveInstanceState`. Compare with the TALK button
on the same day, which produced `onUserLeaveHint`, `onPause`, `focus lost`, `surfaceDestroyed`
and `onStop` in order. **Two different mechanisms**: the TALK path is an ordinary Android screen
change; reverse gear puts a window on top WITHOUT pausing the Activity, which is why `onPause`
never arrives and why anything hooked to it cannot help.

The absence of `onTrimMemory` also rules out the lowmemorykiller, leaving the native decoder
writing into a reclaimed Surface as the standing hypothesis.

The defence now hangs off `onWindowFocusChanged`: losing focus pauses the native decoder, and
regaining it recreates the decoder, reinjects the cached SPS/PPS and requests a keyframe. It is
deliberately not filtered by "was it really reverse": any window on top means feeding the
decoder buys nothing, and a false positive costs one keyframe.

Layer 2 (relaunch after a kill) did not fire: no `service restarted` line. Either the head unit
kills the process in a way that defeats START_STICKY, or the service died without Android
restarting it. Still open.

## Calls: audio arrives, the voice does not leave

With the phone unpaired from the car, the call did stay inside Android Auto, which confirms the
routing conclusion from AUDIO-STREAM.md. The far end was audible; the local voice never got
there.

The logs showed `EcNc session started` three times and nothing else, because every message in
`CarlinkitMicrophone` went to `Log.i` (logcat), which this head unit does not expose. Opening
the microphone successfully says nothing about whether the samples carry sound.

The class now logs to the file, and reports **amplitude**, which is what separates the possible
causes:

```
peak 0            digital zero: the head unit did not route the mic to the app
peak below ~150   noise floor only, nothing usable for the far end
peak 2000+        real speech, so the problem is downstream (protocol/dongle)
```

The chunk counter, the accepted `AudioSource` (VOICE_RECOGNITION or the MIC fallback), the
`sendMicAudio` failures and a one-line verdict at the end of the capture are logged too.

## The dongle limit that forced a physical removal

```
08:58:56.208  dongle disconnected
08:58:56.254  dongle is restarting — searching again in 8s (attempt 1/1)
08:59:04.260  dongle found
08:59:07.401  dongle disconnected
08:59:07.422  dongle dropped again after 1 reconnection — not retrying
08:59:18.900  dongle dropped again after 1 reconnection — not retrying
```

The app gave up by its own rule, not because the hardware was unrecoverable, and unplugging
the dongle was the only way back. `MAX_DONGLE_RECONNECT_ATTEMPTS` was 1.

The limit exists to stop a reconnection cascade, and a cascade is many attempts in a few
seconds, not a few spread over a trip. It is now 5 attempts inside a 120 s sliding window:
attempts older than the window do not count, so an occasional hiccup is recovered indefinitely
while a real loop still reaches the ceiling and shows the power cycle message.

## Bug found in our own instrumentation

`registerModeMgrSWKeyEventCallback` returns the **index**, not zero, on success:

```
registerModeMgrSWKeyEventCallback(idx=213) ret=213
```

The first version treated `ret != 0` as refusal and discarded the callback, so every
`initAudioBinding` registered it again: 15 registrations across four sessions, all successful
and all thrown away. Now `ret == idx` counts as success.

---

# Wireless degradation: the dongle starves audio and video (Aug 17 to 27, 2026)

The interface becomes very slow and music unlistenable. It starts normal and degrades within
minutes. Restarting the phone and power cycling the dongle do not fix it. Selecting a route in a
navigation app reproduces it almost immediately, and on 26/Aug music also stuttered on a static
player screen, with no navigation involved.

This section exists mostly to record what was **refuted**, because ten plausible explanations
were eliminated by measurement before the cause was isolated, and several of them were the
maintainer's own.

## The dongle, identified

`SoftwareVersion` and `BoxSettings` were parsed and discarded. Decoding them gives the box
identity, which the investigation needed and did not have:

```
product     A15W          boxType   YA
hardware    YMYE-WB58-0000
firmware    2022.11.19.1218CAY        MFD 20221119
Wi-Fi       channel 36, AP 1x1 VHT 80 MHz
interface   HTTP on the dongle's own AP
RPC         /cgi-bin/server.cgi with cmd=infos, cmd=set, cmd=reset
```

Its own web interface documents three settings that matter here, and the values found in the box:

| Setting | Box guidance | Found as |
|---|---|---|
| `bitRate` | for stuttering, use under 8 Mbps | 0 |
| `fps` | for visible lag, use 20 | 0 |
| `mediaDelay` | default 1000 ms, range 300 to 2000, larger stutters less | 300 |

The app was sending 300 on every session, overriding the box with the worst value in the range.
That is fixed. `bitRate=4` and `fps=20` were applied to the box, and a factory reset was performed
and the settings reapplied. **None of it removed the symptom.**

The manufacturer's own update endpoint returns an empty version, size 0 and no path, even when
queried while claiming a 2020 firmware. There is no official image to roll back to, so third party
firmware is not an option here.

## What the app measures now, and how to read it

The heartbeat line carries every counter that separates one suspect from another:

```
alive | dongle sending=true phone=unknown video rx=N rendered=M connectReq=N
        decode max=Xms slow=N avg=Yms
        audioWrite type=4 tracks=1 in=N inBytes=N maxGap=Xms buffer=NB
                   q=N/16 qmax=N writes=N max=Xms slow=N avg=Yms
```

| Reading | Conclusion |
|---|---|
| `maxGap` large and `in` falling | the dongle stopped delivering PCM, upstream of this app |
| `maxGap` small, `in` steady, audio still bad | playback or the head unit, not delivery |
| `qdrop` climbing | the worker is not keeping up, but no longer blocks USB |
| `decode max` large or `slow` non zero | the decoder is throttling the read loop |
| `sendDropped` or `sendFailed` non zero | the app's own write path to the dongle is failing |

`in` counts PCM messages per 5 s interval, so 117 is the healthy figure at 48 kHz stereo, roughly
23 per second.

## Measured signatures

Same head unit, same app build, same afternoon:

| Condition | `in` median | gap median | gap p90 | gap max | windows over 500 ms |
|---|---|---|---|---|---|
| Wireless, degraded | 23 to 42 | 131 to 3430 ms | 3797 ms | 4578 ms | 26 of 54, 4 of 6 |
| Wireless, healthy | 117 | 70 to 76 ms | 183 ms | 202 ms | 0 of 34 |
| Wired, 17 min | 117 | 71 ms | 175 ms | 228 ms | 0 |
| Wireless, healthy 30 min | 117 | 70 ms | 183 ms | 4369 ms | 3 of 365, all in the first 25 s |

Three things follow. Cable and healthy wireless are **indistinguishable**, so the head unit, the
decoder, the USB path and the phone can all sustain the stream. The degraded state is
**intermittent**, not a fixed ceiling: degraded and healthy sessions alternate within the same
afternoon with identical settings, and one 76 minute session on 17/Aug was clean throughout. And
once a session locks in healthy it tends to stay healthy: in the 30 minute session the only bad
windows are the first three.

In every degraded session the queue stayed at `q=0` with `qmax` between 1 and 3 against a capacity
of 16, `qdrop` stayed at zero, and `writes` tracked `in` exactly. **The app is not the bottleneck.**

## Hypotheses refuted, with the evidence

| Hypothesis | What killed it |
|---|---|
| The phone cannot do wireless Android Auto | The same phone runs it in another car's factory head unit without stuttering |
| The H.264 decoder blocks the read loop | Instrumented: `decode max` 0 to 11 ms, `slow=0`, average 0 ms, including during collapse |
| The app fails writing to the dongle | `sendDropped=0` and `sendFailed=0` in every session |
| Resolution or codec is wrong | The real SPS says H.264 Baseline L3.1, **800x480**, zero B frames. Already correct |
| USB re-enumeration proves a defective dongle | It is the normal watchdog. With a host heartbeat it stops. See "The dongle re-enumerates constantly" |
| Unpairing Bluetooth sent media over Bluetooth | The dongle's Bluetooth profile offers only a calls toggle, no A2DP. Media stays on Android Auto over Wi-Fi |
| A HondaPermissions change caused it | The whitelist is byte identical in good and bad sessions |
| Newer firmware exists | The manufacturer's endpoint returns an empty version and size 0 |
| `AudioTrack.write` blocks the USB thread | A dedicated worker was implemented. Queue stayed `q=0, qmax=1, qdrop=0`. Better architecture, same symptom |
| Access point band steering | Reported disabled on the access point |

⚠️ The pattern in that list is worth more than any single row. **Five of the ten fell to a counter
added specifically to test them**: decode timing, the write path counters, the SPS parse, the
whitelist log line, and the audio queue metrics. The other five fell to a targeted probe rather
than to argument: the manufacturer's update endpoint, the dongle's Bluetooth profile, the access
point configuration, the dongle watchdog behaviour on a bench host, and running the same phone in
another car.

None of them fell to reasoning. The two that reasoning alone would have kept alive, the decoder and
this app's write path, were the two leading theories at the time.

## The stall detector, and why it only asks for a keyframe

The existing watchdog deliberately fires only when **nothing** was ever received, because a dongle
that goes quiet on a static screen is normal. That leaves a real failure uncovered: on 24/Aug the
frame count froze at 2446 for 35 s with the phone still connected and the picture stuck, while
every other indicator looked healthy.

The threshold is 20 s, calibrated on 6 days of logs counting only stretches with the phone
connected and the screen owned by the app: 11 healthy sessions over 4 days never stalled past
10 s, and the real freeze lasted 35 s. So 20 s is twice the worst healthy case and still catches
the real one.

It logs **before** acting, and the action is only a keyframe request, the same call already made
when the head unit hands the screen back. There is no session restart on purpose: a wedged dongle
needed a car power cycle in the 01/Aug tests, and this stall recovered on its own, so tearing the
session down would be a guess with a real cost. The reverse gear defence once became a plausible
trigger for the very crash it was written to prevent, which is why anything touching the video path
has to be visible in the log first.

## Where this stands

The cause is isolated to the dongle ceasing to deliver PCM over Wi-Fi, intermittently. The app side
is measured clean on every path that was suspect. Cable is proven stable over 17 and 41 minute
runs, so a wired connection is the reliable workaround and a different dongle is the hardware
option. Nothing in this app is known to be able to fix it, and the counters above are what would
detect a regression if it ever is fixed.

---

# The bench rig: what it validates, and what it cannot (Sep 1, 2026)

`tools/session.py` drives the dongle from a PC over USB, with no head unit and no
Android app involved. It now reports the same per-window figures as the app's heartbeat,
so bench and car numbers are directly comparable.

## Validated against the car, over a cable

```
                        in_med   gap_med   gap_max   windows over 500 ms
car, cable, 17 min        117      71 ms    228 ms   0
bench, cable, 3.9 min     117      67 ms    191 ms   0 of 47
```

Number for number. The rig is therefore trustworthy for anything that travels over the
cable, and a bench result can stand in for a drive.

That also settles a doubt raised while investigating: the harness has no protocol defect.
It held an Android Auto session for 3.9 minutes with a single PLUGGED, zero UNPLUGGED and
continuous media. Its init sequence, all 14 config fields, its command set and its
heartbeat cadence are identical to the app's, checked field by field.

## Wireless on the bench fails differently from the car

```
                    drops/min   in_med   gap_med   gap_max   windows over 500 ms
car, degraded            0.00      42     131 ms   4578 ms   26 of 54
bench, wireless          2.73      58      90 ms     94 ms    0 of 4
bench, cable             0.00     117      67 ms    191 ms    0 of 47
```

The car's degraded sessions had **zero** phone drops with continuous media and starved
delivery. The bench has the opposite: while connected the delivery is clean, and the
session dies every 12 seconds.

⚠️ **The 12 s figure is regular, so it is a timeout and not interference**, and the event
order says who decides: `UNPLUGGED` arrives first and `btDisconnected` follows, so the
dongle drops the phone and only then releases Bluetooth. Cause not found. It is specific to
the wireless path, since the same code over a cable holds the session indefinitely.

Ruled out for that drop, each by measurement: a protocol gap against the app, USB resets
(zero kernel events during the runs), supply current (the hub is self-powered), the phone
leaving for the house network (zero DHCP and zero association at the gateway, checked in
two separate windows), distance and placement, and `REQUEST_VIDEO_FOCUS`, which exists as
a constant and the app does not send either.

## The Wi-Fi channel cannot be changed

The dongle exposes `WiFiChannel` and value 1 corresponds to channel 36. Writing it is
accepted and silently ignored:

```
item=bitRate       err=0    4 -> 5 -> 4   writes, confirmed by read-back
item=WiFiChannel   err=0    1 -> 1        accepted and IGNORED
item=wifiChannel   err=255                name not recognised
```

The three outcomes are distinguishable, and only a read-back separates "accepted" from
"applied". `bitRate` is the control that proves the syntax works. Channel width is not
exposed at all, so neither of the two settings that would help in a congested band can be
reached.

For the record, measured where the bench sits: the 80 MHz block the dongle is locked to,
36 to 48, carries 5 access points including a hidden one on channel 40 at maximum signal,
while the non-DFS block 149 to 165 carries 2. So the RF case is real and unreachable.

The write path is POST multipart to `/cgi-bin/server.cgi` with `cmd`, `item` and `val`. A
query string returns `err=255`.

## Amazon Music and YouTube Music deliver the same

Same dongle, same cable, same settings, 3.9 minutes each:

```
                p50    p75    p90    p95    max     over 200 ms   drops
YouTube Music   67ms   69ms  178ms  184ms  191ms         0          0
Amazon Music    68ms   73ms  188ms  192ms  268ms         2          0
```

Both negotiate 48000 Hz stereo, decodeType 4. Amazon is marginally worse at the tail and
the difference is milliseconds against a degraded reference of 4578 ms. Whatever is heard
as a small stutter with Amazon Music is not a delivery difference.

## ⚠️ The rig measures delivery, not playback

It writes PCM to a file. It does not exercise the app's audio worker or the head unit's
speakers. So a clean bench result does not clear those two, and a small stutter with clean
delivery points at one of them.

## Reading the next drive's log

With media playing and the picture live, the heartbeat answers this without further tooling:

| Reading | Conclusion |
|---|---|
| `in` near 117, `maxGap` low, yet it stutters | delivery is fine: playback, the audio worker or the head unit |
| `in` falling and `maxGap` in seconds | delivery, the failure measured on 27/Aug |
| `qdrop` climbing above zero | the app's own audio worker, and the first time it would have happened |
| `sendDropped` or `sendFailed` non-zero | the app's write path to the dongle |
| `decode max` high or `slow` non-zero | the decoder throttling the read loop |

`qdrop` stayed at zero across the 365 windows of the clean 30 minute session on 27/Aug, and
that session was YouTube Music.

## Running it

```bash
cd android/..                       # repository root
sudo ./venv/bin/python tools/session.py --seconds 240 --tag "what-you-changed"
```

Anything associated to the dongle's own access point consumes airtime on the very link
being measured, so nothing else should be joined to it during a run.
