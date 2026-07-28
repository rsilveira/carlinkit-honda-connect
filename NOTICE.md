# Attribution and licensing

This project is licensed under the **GNU General Public License v3** (see [LICENSE](LICENSE)).

## Derived from OpenDroidAuto

The head-unit integration layer is derived from
[OpenDroidAuto](https://github.com/MaKi983/OpenDroidAuto) by **MaKi983**, also GPL v3.
Files taken from that project, kept in their original `it.smg.hu` / `it.smg.libs` packages:

| Component | Origin | Notes |
|---|---|---|
| `common/` | OpenDroidAuto | logging and JNI helpers |
| `omxvideocodec/` | OpenDroidAuto | H.264 decoder over the OMX stack |
| `it.smg.hu.manager.HondaConnectManager` | OpenDroidAuto | head-unit services (steering wheel, audio focus, day/night, microphone) |
| `it.smg.hu.config.Settings`, `ODALog` | OpenDroidAuto | settings storage and logging |
| `it.smg.hu.ui.SettingsActivity` and `ui.settings.*` | OpenDroidAuto | settings screens |
| `it.smg.hu.projection.DayNightSensor`, `TwilightCalculator` | OpenDroidAuto | day/night detection |
| `it.smg.libs.aasdk.{ICarConfiguration, ChannelId, ISensor}` | OpenDroidAuto | 3 small types the above depend on |
| `ext-libs/` | OpenDroidAuto | AOSP 4.0 headers and head-unit system libraries |

Changes made relative to upstream are described in [docs/FINDINGS.md](docs/FINDINGS.md) and
include: audio focus and ModeMgr handling for `THIRD_PARTY` apps, dynamic package name for
the whitelist lookup, and the removal of the Android Auto cable stack.

### What was NOT carried over

The `aasdk` module (the wired Android Auto protocol stack, ~415 MB) is not part of this
project. The Carlinkit dongle speaks its own protocol over USB bulk transfers, so none of
it is needed. If you want wired Android Auto, use OpenDroidAuto itself.

## Written for this project

The Carlinkit protocol implementation (`android/carlinkit/`), the app layer
(`it.smg.hu.carlinkit.*`), the Python tools and all documentation were written from scratch
for this project, informed by [node-CarPlay](https://github.com/rhysmorgan134/node-CarPlay)
(MIT) as a protocol reference. They are released under GPL v3 together with the rest.

## Proprietary dependency

Building the Android app requires `ada-ext-api.jar`, a proprietary Fujitsu Ten framework
library that is **not redistributed here**. See
[docs/FUJITSU-DEPENDENCY.md](docs/FUJITSU-DEPENDENCY.md) for how to obtain it.

It is a `compileOnly` dependency: it is never packaged into the APK, and at runtime the
real classes come from the head unit's own framework.
