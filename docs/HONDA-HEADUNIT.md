# App configuration on the Honda head unit (HondaPermissions)

Source: **cmdroid.com** documentation (`/tutorials`), authored by cmhonda.net.
Local copies in `reference/cmdroid/` (outside git).

⚠️ The site only answers over **HTTP** — `https://cmdroid.com` fails. Use
`http://www.cmdroid.com`.

## "Edit process control" parameters

Every installed app needs an entry in the system whitelist, edited through the
**HondaPermissions** app. The fields:

| Field | Meaning |
|---|---|
| **Process name** / **Package name** | Must match the app's **exactly**. This is the most common cause of "I configured it and it didn't work" |
| **AuthType** | `THIRD_PARTY` for third-party apps |
| **AppType** | `NAVI` (GPS), `MULTIMIDIA` (audio/video) or `BROWSER` |
| **VideoOut** | **`OFF` = the screen is NOT blocked while the car is moving.** This is the "parking brake released" parameter |
| **SoundOut** | **Exclusive** audio: opening the app terminates any other audio source. **This is what grants access to all speakers** |
| **SoundInterrupt** | **Non-exclusive** audio: coexists with another source, but is **restricted to the LEFT speaker** (typical GPS behavior) |
| **SoundInterruptMute** | Used together with `SoundInterrupt`: mutes the competing source on the left speaker |
| **LastMode** | Prevents the app from closing when reverse gear is engaged or MENU is pressed; and reopens it at car start-up if it was the last one in the foreground || **OomSetPerm** | Protection against the app being killed under memory pressure |
| **ProcessKillTarget** | Marks the process as a kill target |

### ⚠️ LastMode disabled is why the app dies in reverse gear (confirmed 10/Aug/2026)

Three test sessions were spent chasing the app being killed when reverse gear engaged: the
native decoder was suspected, then the vehicle state signals, then window focus. Instrumentation
showed the process dying in the foreground with no `onPause`, sometimes with a bare `focus lost`
and sometimes with no Java event at all, and pausing the decoder on that signal changed nothing.

The cause was configuration, not code: **LastMode had been disabled** for this package in
HondaPermissions. The parameter exists precisely to stop the head unit from closing an app when
reverse gear or MENU takes the screen, and it is listed in the table above.

Two lessons worth more than the fix:

- When the head unit closes an app on purpose, it looks exactly like a crash from inside the
  process. No lifecycle callback, no exception, no tombstone to find. Absence of evidence in the
  app is itself evidence that the decision was taken outside it.
- Check the whitelist entry before writing defensive code. `LastMode` and `OomSetPerm` decide
  whether the process survives events the app cannot see, and neither is visible from the API.

### Recommended configuration for the Carlinkit app

Since CarPlay/Android Auto delivers video, music, navigation and calls, it must behave as the
primary media source:

```
Package name:  it.smg.hu.carlinkit
AuthType:      THIRD_PARTY
AppType:       MULTIMIDIA
VideoOut:      OFF          -> screen stays active while the car is moving
SoundOut:      enabled      -> exclusive audio, all speakers
LastMode:      enabled      -> does not close on reverse gear / MENU
OomSetPerm:    enabled      -> avoids being killed under memory pressure
SoundInterrupt / SoundInterruptMute: disabled
```

`SoundInterrupt` would be **wrong** here: it would restrict audio to the left speaker.

⚠️ **This block is the recommendation, not the state of the installation.** The entry actually
applied on this head unit reads `appType=0`, which is `APP_TYPE_NAVI`, not MULTIMEDIA. Everything
else above matches. The difference matters because the process is force stopped after the TALK
button, and `appType` is the field the evidence points at. Read "Correction: `authType` is not what
kills the app after the TALK button" below before changing anything, and change one field at a time.

⚠️ **A NEW whitelist entry is required.** Carlinkit mode is a separate app
(`it.smg.hu.carlinkit`, `carlinkit` product flavor), installable alongside OpenDroidAuto
(`it.smg.hu`) without replacing it. Since the whitelist is indexed by package name, the
existing `it.smg.hu` entry does **not** cover it.

The two apps are told apart on the head unit screen by their icon (amber arrow with wireless
waves, versus OpenDroidAuto's blue arrow) and by their name ("Carlinkit" and "OpenDroidAuto").

## Installing the APK

The head unit validates **only the file name**: it must be exactly
`HondaAppCenter_A1.apk`, with no suffixes such as `(1)`. The internal package can be anything.

USB stick method (the one that works for development):

1. Copy the APK to the **root** of the USB stick, renamed to `HondaAppCenter_A1.apk`
2. Plug it into **USB port 1** (driver's side) — the head unit reads that one first
3. Home screen → bottom-right icon (circle with 6 small squares)
4. Under "Application/Widget List" → **"Install app"** button
5. Select `HondaAppCenter_A1` → Install
6. **Delete the APK after installing** (otherwise it interferes with the next installation)

Prerequisite: Settings → allow installation from **"Unknown sources"**.

## Implication for the development cycle

Without adb, every iteration is: build → rename → copy to USB stick → install on the head
unit → delete the file. That is slow, and it is why as much as possible is validated on the PC
before going to the car — in particular protocol parsing and the video stream format.

Since there is no console, the app must **write logs to a file** on storage (the project
already has `ODALog`), so it can be diagnosed after running in the car.

## Reading the whitelist entry from the app, and decoding it (Aug 18, 2026)

The head unit exposes the entry it holds for the package, and the app logs it once per session.
This is the ground truth: the values below are what the head unit actually applied, not what the
configuration screen appears to show.

Observed on this installation:

```
whitelist: authType=1 appType=0 lastMode=1 oomSetPerm=1 soundOut=1 videoOut=0
           soundInterrupt=0 result=0
```

The numbers are meaningless without the framework's own constants, and the jar is the authoritative
source for them:

```bash
mkdir /tmp/whl && cd /tmp/whl
unzip -q <repo>/android/ext-libs/framework/ada-ext-api.jar
javap -constants -p -classpath . \
    com.fujitsu_ten.displayaudio.whitelist.common.Constants
```

Decoded:

| Field | Value here | Meaning | Other values |
|---|---|---|---|
| `authType` | 1 | `AUTH_TYPE_THIRD_PARTY` | PREINSTALL=0, HONDA_ORIGIN=2, OTHER=3 |
| `appType` | 0 | `APP_TYPE_NAVI` | MULTIMEDIA=1, BROWSER=2, HOME=3, VOICE_RECOGNITION=4, OTHER=5, HONDA_AUTOMOTIVE=6 |
| `videoOut` | 0 | `VIDEO_OUT_OFF` | STILL_IMAGE=1, MOVING_IMAGE=2 |
| `lastMode` | 1 | enabled, consistent with reverse gear working since 10/Aug | |

⚠️ **`videoOut=0` is not a mystery, and an earlier draft of this section wrongly called it one.**
The constant name `VIDEO_OUT_OFF` reads as "no video", which is what caused the confusion, but the
head unit's own semantics are documented in "Recommended configuration" above: OFF is what keeps
the screen active while the car is moving. The observed value is therefore the correct one.
**Do not change this field.** Setting it to `MOVING_IMAGE` is what may engage the
video-while-moving restriction and break what currently works.

⚠️ **`oomSetPerm` has no answer in the jar.** `ProcessControl` is a plain data container, ten public
fields and no constants, and no other class in the jar consumes that field. The semantics live in
the head unit, not in the API. Do not invent a meaning for it. What the data does say is that
whatever it is, it is not preventing the process from being killed.

### ⚠️ Correction: `authType` is not what kills the app after the TALK button

An earlier recommendation here was to change `authType` first, based on a report from the
OpenDroidAuto maintainer that an `authType` below PREINSTALL gets an app killed **during Bluetooth
calls**. That was extrapolated to this failure without testing, and the extrapolation was wrong.

Counting every microphone session across 6 days of logs:

| Condition | Sessions | Outcome |
|---|---|---|
| TALK button pressed 0.10 to 0.79 s before | 6 | process died in all 6 |
| No button: wake word, or a call | 15 | survived all 15, the longest 1022 s |

The app holds the microphone for 17 minutes during a call and does not die. Neither the microphone
nor the call kills it, so the maintainer's condition does not reproduce here. What kills it is the
TALK button, that is the head unit's own voice recognition mode.

Revised order, one field at a time:

1. `appType` from NAVI (0) to **VOICE_RECOGNITION (4)**. This is where the evidence points: the
   event that kills the process is the voice mode, and the framework has a category for exactly
   that.
2. `appType` to **MULTIMEDIA (1)**, to separate "NAVI is wrong" from "the type does not matter".
3. `authType` to **PREINSTALL (0)** last, with no supporting evidence of our own, purely as a test
   of the third party report.

⚠️ Changing `authType` and `appType` together makes the result useless, because there is no way to
tell which one acted.

### The death is a force stop, not memory pressure

The app has a `START_STICKY` recovery layer that should relaunch it, and it **never fired**. Across
every log, `service restarted after the process was killed`, `activity relaunched after the kill`
and `relaunch skipped` have zero occurrences, and the deliberate-exit flag is cleared correctly when
the app returns to the foreground.

Android does not restart a service belonging to an app that was force stopped, and force stop
requires a system component with process control privilege. That is the domain this whitelist
governs, which is what makes configuration the right place to attack it rather than code.

Timing measured on two separate drives:

```
TALK button -> onStop            0.62 s / 0.80 s
end of log  -> new process       1.75 s / 1.67 s
TALK button -> session active    2.68 s
```

⚠️ And the app does **not** come back on its own. Those 1.75 s intervals were read as the head unit
relaunching it via `LastMode`; it was reopened by hand both times. `scheduleActivityRelaunch` has a
5 s delay and writes a line that is absent from every log.
