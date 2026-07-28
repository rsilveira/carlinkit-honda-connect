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
| **LastMode** | Prevents the app from closing when reverse gear is engaged or MENU is pressed; and reopens it at car start-up if it was the last one in the foreground |
| **OomSetPerm** | Protection against the app being killed under memory pressure |
| **ProcessKillTarget** | Marks the process as a kill target |

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
