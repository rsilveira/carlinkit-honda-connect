# carlinkit-hrv

Wireless **CarPlay / Android Auto** on **Honda Connect** head units running **Android 4.0**,
using a Carlinkit dongle. Developed and tested on a 2016 Honda HR-V EXL.

## Why this exists

The dongle's official app (`BoxHelper.apk`, `cn.manstep.phonemirrorBox`) declares
`minSdkVersion 16` (Android 4.1). The HR-V head unit runs **Android 4.0.4 (API 15)**, one
version below, so it simply refuses to install.

The dongle itself, however, speaks a reasonably simple USB protocol: it delivers **H.264
video and PCM audio** over bulk transfers. That is perfectly implementable on API 15 — this
project does exactly that.

## Status

Working in the car, verified on the target head unit:

| Feature | Status |
|---|---|
| Video (H.264 → OMX → screen, full screen 800x480) | working |
| Audio out (PCM → `AudioTrack`, all speakers) | working |
| Touch input | working |
| Steering wheel: volume, next/previous track | working |
| Microphone: voice assistant | working |
| Phone calls | working, handled by the car's own telephony (see below) |
| Day/night following the headlights | working |
| Android Auto (Android phone) | working |
| CarPlay (iPhone) | working |
| Returning to FM radio and back | working |

Known limitations are listed in [docs/FINDINGS.md](docs/FINDINGS.md).

## What to expect in daily use

The features above work, but three behaviours are visible every time you use it. None is a
defect in this app. The first two come from the dongle and the head unit, the third is a
consequence of how the car handles telephony, and knowing about them saves a lot of confusion.

**A USB permission dialog appears on almost every launch.** The dongle re-enumerates on the
USB bus constantly (its own watchdog reboots it roughly 9s after the host stops talking to
it), and Android grants USB permission *per device instance*. A new instance means a new
authorization. Measured across test sessions: 5 to 6 distinct device numbers in 7 to 11
launches. Tick "use by default" and accept — the app cannot suppress the prompt.

The standard way to avoid it is a `device_filter` plus a `USB_DEVICE_ATTACHED` intent filter,
which grants implicit permission. That is implemented, and on this head unit it never fires:
the broadcast was delivered zero times across 11 test sessions. Something in the firmware does
not hand it to apps.

**Occasionally the dongle needs a power cycle.** It can enumerate, accept a session and then
send nothing at all — the screen says the session started and no phone ever connects.
Reopening the app does not help, because the dongle itself is wedged. Turn the car off and on.
The app detects this state after 10s and says so on screen, rather than leaving you guessing.

**A phone call takes over the screen, and you come back manually.** With the phone paired to
the car over Bluetooth, an incoming or outgoing call is handled by the head unit's own phone
app over HFP, exactly as it would be with no dongle plugged in. The Carlinkit screen is left
behind and you have to switch back to the app when the call ends.

This is a tradeoff rather than a fault. Call audio goes through Honda's telephony stack, with
the echo cancellation the manufacturer tuned for this cabin, so the far end hears what it would
hear normally. What you give up is the projected in call UI and the automatic return to
projection afterwards. It also means the microphone capture in this app serves the **voice
assistant only**: during a call the app never sees the audio.

If you want calls to go through the dongle instead, unpair the phone from the car's Bluetooth.
That has not been tested here, and it means giving up the native echo cancellation.

## Target hardware

| | |
|---|---|
| Dongle | Carlinkit CPC200 "Auto Box" — USB `1314:1521` |
| Tested firmware | `2022.11.19.1218CHY` |
| Head unit | Honda Connect / Fujitsu Ten Display Audio (`FUJITSU-TEN MY15ADA`), Android 4.0.4 (API 15) |
| Vehicle | Honda HR-V EXL 2016 |

**Important:** *wired-to-wireless* dongles (Carlinkit U2W) will **not** work. You need the
type that turns an Android head unit into a CarPlay/Android Auto screen (Autokit / CCPA).

## Repository layout

```
android/     standalone Android app (Gradle, API 15, armeabi-v7a)
  app/         the app itself plus the head unit integration
  carlinkit/   Carlinkit USB protocol library (+ 23 unit tests)
  common/      logging and JNI helpers
  omxvideocodec/  H.264 decoder over OMX (MediaCodec only exists from API 16)
  ext-libs/    AOSP 4.0 headers and head unit system libraries
docs/        protocol findings and head unit documentation
tools/       Python scripts to validate the protocol from a PC
```

## Building

Requires **Java 17** (Java 21+ fails on this Gradle version) and the Android NDK
`17.2.4988734`.

```bash
cd android
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/HondaAppCenter_A1.apk
```

You also need a proprietary Fujitsu Ten library that is not redistributed here — see
[docs/FUJITSU-DEPENDENCY.md](docs/FUJITSU-DEPENDENCY.md). It is a `compileOnly` dependency
and is never packaged into the APK.

Two caveats worth knowing:

- **Do not run `./gradlew clean`.** The native clean task fails and leaves the modules in an
  inconsistent state. To rebuild from scratch, delete the specific `<module>/build`
  directories and build `:common` before `:app`.
- **Keep AGP at 8.3.2.** With 8.7.2 the prefab integration emits an empty static library for
  `common` and the native link fails with `undefined reference to Log::isVerbose()`.

## Installing on the head unit

The head unit only accepts a file named exactly `HondaAppCenter_A1.apk`.

1. Copy the APK to the **root** of a USB stick, with that exact name
2. Insert it into **USB port 1** (driver's side)
3. App list → **Install app** → select `HondaAppCenter_A1`
4. Configure the permissions — see [docs/HONDA-HEADUNIT.md](docs/HONDA-HEADUNIT.md)

Step 4 is not optional: without a whitelist entry for `it.smg.hu.carlinkit` the screen is
blocked while the vehicle moves and the audio does not reach all speakers.

## Diagnostics

The head unit exposes no adb, so the app writes a log file. It prefers a mounted USB stick,
which makes retrieval easy: leave one plugged in, then read it on a PC. The log path is shown
on screen, and the file records every connection step, the protocol state machine, steering
wheel key codes and any uncaught exception.

## Tools

Protocol validation scripts, to run on a Linux PC with the dongle plugged in:

```bash
python3 -m venv venv
./venv/bin/pip install -r tools/requirements.txt
sudo ./venv/bin/python tools/test_protocol.py    # minimal handshake
sudo ./venv/bin/python tools/session.py          # persistent session, records video/audio
```

`sudo` is required to claim the vendor-specific USB interface.

`tools/bench_reconnect.py` answers two questions that car logs cannot, since each road test
costs a drive:

```bash
# does reopening too soon cause the wedged state? compare the two
sudo ./venv/bin/python tools/bench_reconnect.py reset --cycles 20 --delay 4
sudo ./venv/bin/python tools/bench_reconnect.py reset --cycles 20 --delay 8

# does the dongle send anything on its own? longest gap decides if a
# sliding-window watchdog is safe
sudo ./venv/bin/python tools/bench_reconnect.py idle --seconds 120
```

## Documentation

- [docs/FINDINGS.md](docs/FINDINGS.md) — protocol details and the findings that made this
  work, including the ones that are easy to get wrong
- [docs/VIDEO-STREAM.md](docs/VIDEO-STREAM.md) — video format
- [docs/AUDIO-STREAM.md](docs/AUDIO-STREAM.md) — audio format, both directions
- [docs/HONDA-HEADUNIT.md](docs/HONDA-HEADUNIT.md) — head unit permissions and installation
- [docs/FUJITSU-DEPENDENCY.md](docs/FUJITSU-DEPENDENCY.md) — the proprietary dependency

## License

GPL v3 — see [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md).

The head unit integration derives from
[OpenDroidAuto](https://github.com/MaKi983/OpenDroidAuto) by MaKi983 (GPL v3). The Carlinkit
protocol implementation, the app layer, the tools and the documentation were written for this
project.

## Credits

- [rhysmorgan134/node-CarPlay](https://github.com/rhysmorgan134/node-CarPlay) — dongle
  protocol reference (MIT)
- [ludwig-v/wireless-carplay-dongle-reverse-engineering](https://github.com/ludwig-v/wireless-carplay-dongle-reverse-engineering)
  — firmware and hardware reverse engineering
- [MaKi983/OpenDroidAuto](https://github.com/MaKi983/OpenDroidAuto) — the head unit
  integration this builds on
- The [Honda Connect Android System](https://forum.xda-developers.com/t/honda-connect-android-system.3179549/)
  thread on XDA — accumulated head unit knowledge
- cmdroid.com — head unit permission documentation

## Disclaimer

Independent project, unaffiliated with Honda, Carlinkit, Google or Apple.

Use responsibly: changing head unit permissions so the screen stays active while the vehicle
is moving has safety implications, and possibly warranty ones.
