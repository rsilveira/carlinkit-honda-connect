# Audio and session — validated (Jul 27, 2026)

## ⚠️ The dongle has a WATCHDOG — it requires an active host

**The most important finding of this stage.** Without a host keeping communication alive, the
dongle **reboots every ~9 seconds**: 204 disconnect/reconnect cycles were recorded in `dmesg`
while no script was running.

This produced a misleading symptom: the phone "paired but wouldn't stay connected". The cause
was neither the phone nor the pairing — it was the dongle rebooting and dropping the link.

**An incorrect diagnosis I ruled out**: I initially blamed USB power (the dongle declares
500 mA and was on a passive hub). Moving it to a direct PC port, also at 500 mA, did **not**
fix it — the resets continued. What fixes it is keeping the host talking.

### Practical consequences

1. Short capture scripts are unworkable: as soon as they finish, the session drops.
   Solution: `tools/session.py`, a daemon that keeps a continuous heartbeat.
2. The code must **tolerate the reset**: reopen the USB device and redo the `init`.
   `session.py` does this via `open_device()` + reinitialization on `USBError`.
3. **Risk in the car**: if the head unit is slow to start communication after boot, the dongle
   may enter that cycle. The app must start the heartbeat **immediately** after obtaining USB
   permission, and reopen the device if it disappears from the bus.

### Successful connection sequence (with a persistent session)

```
[+1s] Command 1003  scanningDevice
[+2s] Command 1007  btConnected
[+3s] PLUGGED       phone: AndroidAuto
[+4s] Command 1009  wifiConnected
[+4s] Command 1004  deviceFound
[+7s] FIRST VIDEO FRAME
[+8s] AudioCommand  MediaStart / OutputStart
[+8s] PCM starts    48000Hz 2ch
```

Full connection in ~8 seconds. The daemon resends `wifiConnect (1002)` every 10 s while no
phone is present, which makes the phone reconnect on its own.

## `AUDIO_DATA` (0x07) format

Payload:

| Offset | Field | Type |
|---|---|---|
| 0 | decodeType | uint32 — index into the format table |
| 4 | volume | **float32** |
| 8 | audioType | uint32 |
| 12+ | contents | see below |

The contents depend on how many bytes remain:

| Remaining bytes | Meaning |
|---|---|
| 1 | `AudioCommand` (int8) — start/stop of media, call, navigation, Siri |
| 4 | `volumeDuration` (float32) |
| > 4 | **PCM Int16 LE** |

### Format table (decodeType)

| decodeType | Sample rate | Channels | Bits |
|---|---|---|---|
| 1, 2 | 44100 | 2 | 16 |
| 3 | 8000 | 1 | 16 |
| **4** | **48000** | **2** | **16** |
| 5 | 16000 | 1 | 16 |
| 6 | 24000 | 1 | 16 |
| 7 | 16000 | 2 | 16 |

### AudioCommand

| Value | Name |
|---|---|
| 1 / 2 | OutputStart / OutputStop |
| 3 | InputConfig |
| 4 / 5 | PhonecallStart / PhonecallStop |
| 6 / 7 | NaviStart / NaviStop |
| 8 / 9 | SiriStart / SiriStop |
| 10 / 11 | MediaStart / MediaStop |
| 12 / 13 | AlertStart / AlertStop |

## What was observed in the real capture

Android Auto playing music for 87 seconds:

- **A single stream**: `decodeType=4, audioType=1` → **48000 Hz, 2 channels, 16-bit**
- 1095 audio messages, 4169 video frames
- Generated WAV with **87.4 s** of continuous audio
- Signal validated: RMS between **-15 and -23 dBFS**, peaks around 24000 out of 32768 — real
  music, not silence
- `AudioCommand MediaStart` and `OutputStart` arrive before the PCM, signalling the start

One detail: the `volume` field came through as **0.00** in the commands, yet the PCM has normal
amplitude. So `volume` must **not** be used to scale the samples — it is informational
(probably the phone's UI volume). Applying that value as gain would mute the audio.

## Implications for the Android implementation

`AudioTrack` has been available since API 3, so API 15 is not a problem:

```java
new AudioTrack(AudioManager.STREAM_MUSIC, 48000,
        AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
        bufferSize, AudioTrack.MODE_STREAM);
```

Points to watch:

1. `decodeType` can change at runtime (navigation at 16 kHz mono, calls at 8 kHz). The code
   must **recreate the `AudioTrack`** when the format changes, and keep separate instances per
   `audioType` if concurrent audio is needed.
2. On the Honda head unit, routing matters: per the cmdroid documentation, `SoundOut`
   (exclusive) uses all speakers while `SoundInterrupt` restricts output to the left speaker.
   Since Android Auto mixes media and navigation into a single stream (`audioType=1` here),
   `SoundOut` is the appropriate setting.
3. `MediaStart`/`MediaStop` are good hooks for requesting and releasing audio focus on the head
   unit.
4. **`AudioTrack.write` must not run on the thread that reads from USB.** The call blocks, and
   creating, writing, flushing and releasing the track all used to happen on the read loop, so a
   stall inside `AudioTrack` would stop video and audio alike. The playback path now owns a
   dedicated worker fed by a bounded queue. Details and the numbers behind the sizing are in
   [FINDINGS.md](FINDINGS.md), section "Wireless degradation".

   Two decisions there are worth repeating because they are counter intuitive:

   - When the queue fills it drops the **oldest** PCM, not the newest. In audio the stale end of
     the buffer is the part nobody wants to hear.
   - The buffer handed in by the driver is **reused**, so the worker has to copy it. A pool keeps
     that from becoming roughly 50 allocations a second at 48 kHz stereo.

## Whether phone calls reach this code path depends on the Bluetooth pairing

The `PhonecallStart`/`PhonecallStop` commands and the 8 kHz mono format exist in the protocol, and
whether they are exercised depends entirely on the Bluetooth pairing between phone and car.

**Phone paired to the car (observed Aug 9).** When a call starts, the head unit switches away from
the projection app to its own phone application and handles the call over HFP, the same way it
does with no dongle connected. Returning to projection afterwards is manual. The app receives no
call audio and its microphone capture is not involved. Call quality is the head unit's native
telephony, including the manufacturer's echo cancellation, so there is nothing to implement or
tune, and nothing this app can break.

⚠️ **Phone NOT paired to the car (observed Aug 10, and this corrects the section above).** The
original version of this text concluded that `CarlinkitMicrophone` "serves the voice assistant
only" and that the 8 kHz path "has not been tested here". Both statements were wrong within a day
of being written. With the phone unpaired from the car, the call goes through Android Auto, this
code path handles it end to end, and calls were confirmed working in **both directions** in the
car.

⚠️ **The format trap that costs the whole call.** The capture was always healthy. The failure was
that the microphone sent 16000 Hz while the phone had asked for 8000 Hz through the `InputConfig`
audio command, and that command arrives **4 ms after** `PhonecallStart`, on a capture that has
already started:

```
12:54:55.195  mic: capture started ... 16000Hz mono (decodeType 5)
12:54:55.196  mic requested (cmd 4 = PhonecallStart)
12:54:55.199  InputConfig: the phone asks for capture decodeType 3
```

Storing that value "for the next capture" means the entire call runs in the wrong format.
`setDecodeType` has to restart the capture when the format changes. `InputConfig` was parsed and
discarded in the inherited code, with a comment calling it a mere announcement, and it is the only
source for this information.

⚠️ **Measure amplitude, not state.** Three sessions of hypothesis ended with one number. Checking
that a recorder opened says nothing: `peak 0` means the head unit did not route the microphone to
the app, and `peak 2000+` means real speech was captured and any loss is downstream. The grey
microphone icon in Android Auto is cosmetic; audio flows regardless.

An "Enable HFP" checkbox existed in the settings screen, inherited from OpenDroidAuto. Nothing
in this project ever read it: the only audio related negotiation sent to the dongle is the
`MIC` / `BOX_MIC` command choosing which microphone to use. The checkbox was removed rather than
left in place suggesting control that does not exist.

## Tools

- `tools/session.py` — **recommended daemon**: keeps the session alive, tolerates resets,
  records video (.h264) and audio (.wav per format), writes `captures/session-status.txt`
- `tools/capture_audio.py` — one-off audio analysis
- `tools/capture_video2.py` — one-off video analysis
- `tools/diag_connection.py` — decodes status messages
