# Proprietary dependency: `ada-ext-api.jar`

The app that runs **on the Honda head unit** depends on a proprietary JAR from **Fujitsu Ten**,
the manufacturer of the *Display Audio* module used by Honda. This file is **not included in
this repository** and must be obtained separately by whoever builds the project.

It is **not** required for the Python tools nor for the protocol library: only for the Android
app that integrates with the head unit.

## What it is

`ada-ext-api.jar` — 922 classes, all under the `com.fujitsu_ten.displayaudio.*` package.
These are the AIDL interfaces and support classes for the head unit's internal services:

| Package | What the project uses it for |
|---|---|
| `whitelist.common` | read the app's permission (`IWhiteList.getProcessControl`) |
| `steeringmenuservice.service` | receive steering wheel buttons |
| `ecncservice` | enable the microphone with echo cancellation |
| `modemanagement` | request and release audio focus (ModeMgr) |
| `statemanagement` | day/night state (headlights) |
| `oom` | protection against being killed under memory pressure |

## Why it isn't here

It is proprietary Fujitsu Ten software, extracted from the head unit itself. We have no
license to redistribute it, so it stays out of the repository and in `.gitignore`.

Worth noting that it is `compileOnly`: the JAR only serves to **compile** against the class
signatures. It is **not packaged into the APK** — at runtime the real classes come from the
head unit's own framework. In other words, the distributed binary contains no Fujitsu code.

## How to obtain it

### Option 1 — the OpenDroidAuto project (simplest)

[OpenDroidAuto](https://github.com/MaKi983/OpenDroidAuto) tracks the file at
`ext-libs/framework/ada-ext-api.jar` since commit `ca2be5f9` ("initial headunit
integration"):

```bash
git clone --depth 1 https://github.com/MaKi983/OpenDroidAuto.git /tmp/oda
cp /tmp/oda/ext-libs/framework/ada-ext-api.jar <project>/ext-libs/framework/
```

### Option 2 — extract it from the head unit itself

If you have access to your head unit's filesystem (root, recovery, or a firmware dump), the
JAR lives in Android's framework directory:

```
/system/framework/ada-ext-api.jar
```

> I have not verified this path on my own head unit — it is the Android convention for
> framework JARs added by the manufacturer. If the name differs, search with
> `find /system -name "*ada*ext*"`.

### Option 3 — generate your own stubs

To merely **compile** (without running on the head unit), you can create empty stubs with the
signatures in use. It is enough to declare the classes and methods the code references; since
the dependency is `compileOnly`, any implementation works — the real classes are resolved on
the device.

This route is useful for CI and for reviewing the code without having the head unit. The
required references can be listed with:

```bash
grep -rhoE "com\.fujitsu_ten\.[A-Za-z_.]+" --include=*.java app/src/ | sort -u
```

## Where to put it

```
<project>/ext-libs/framework/ada-ext-api.jar
```

The `app` module's `build.gradle` already expects that path:

```gradle
compileOnly files('../ext-libs/framework/ada-ext-api.jar')
```

## Verifying

```bash
unzip -l ext-libs/framework/ada-ext-api.jar | grep -c "\.class"
# expected: 922

javap -classpath ext-libs/framework/ada-ext-api.jar \
      -p com.fujitsu_ten.displayaudio.whitelist.common.Constants
# should list APP_TYPE_MULTIMEDIA = 1, VIDEO_OUT_OFF = 0, etc.
```

Without the JAR the build fails with `package com.fujitsu_ten.displayaudio... does not exist`.

## Other dependencies of the same kind

The app also uses native libraries and AOSP 4.0 headers under `ext-libs/` (`libbinder.so`,
`libmedia.so`, `libstagefright.so`, among others), required by the OMX decoder. They come from
the same origin and carry the same caveats. The AOSP headers are Apache 2.0; the `.so` files
are head unit system binaries.

## Head unit permission

Having the JAR is not enough for the app to work: the head unit keeps a **whitelist** indexed
by package name, edited through the `HondaPermissions` app. See
[HONDA-HEADUNIT.md](HONDA-HEADUNIT.md) for the parameters.
