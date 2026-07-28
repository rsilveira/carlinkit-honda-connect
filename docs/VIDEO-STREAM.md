# Dongle video stream — format validated (Jul 27, 2026)

Captured from the real dongle (1314:1521, fw 2022.11.19.1218CHY) with a paired Samsung S26
running Android Auto. **630 frames, 3.0 MB, all decoded successfully.**

## The finding that unblocked everything: `android_work_mode`

With the "standard" initialization sequence the dongle responded, but **never sent video**:

```
Command 1003  scanningDevice
Command 1008  btDisconnected
Command 1005  deviceNotFound
```

The phone showed up in `BluetoothPairedList` and paired, but never connected — and the phone's
WiFi stayed on the home network instead of migrating to `AutoBox-*`.

**Cause**: Android phones require the dongle to be put into Android mode, via `SendFile` to
`/etc/android_work_mode`. In `node-CarPlay` this is conditional
(`if (config.androidWorkMode)`) and easy to miss.

```python
send_int_file(dev, ep_out, "/etc/android_work_mode", 1)
```

Once that file is written, the BT/WiFi link comes up and video starts within ~18s.

### Sequence that works (in order)
```
SendFile  /tmp/screen_dpi        = 140
Open      (w, h, fps, format=5, packetMax=49152, iBox=2, phoneWorkMode=2)
SendFile  /tmp/night_mode        = 0
SendFile  /tmp/hand_drive_mode   = 0     (left-hand drive)
SendFile  /tmp/charge_mode       = 1
SendFile  /etc/box_name          = "HondaHRV"
SendFile  /etc/android_work_mode = 1     <-- ESSENTIAL for Android
BoxSettings (JSON)
Command   wifiEnable(1000), wifi5g(25), mic(7), audioTransferOff(23)
--- 1 second later ---
Command   wifiConnect(1002)
+ HeartBeat(0xAA) every 2s from the start
```

## `VIDEO_DATA` (0x06) payload format

```
first 32 bytes:
20 03 00 00  e0 01 00 00  03 00 00 00  00 00 00 00  00 00 00 00 | 00 00 00 01 67 42 80 1f ...
[  800    ][  480     ][    3     ][    0     ][    0     ] | H.264 Annex-B
```

**Metadata = exactly 20 bytes** (5 × uint32 LE), confirming the assumption taken from
node-CarPlay:

| Offset | Field | Observed value |
|---|---|---|
| 0 | width | 800 |
| 4 | height | 480 |
| 8 | ? (flags/type) | 3 |
| 12 | ? | 0 |
| 16 | ? | 0 |

The H.264 data starts at offset 20, with a **4-byte** start code (`00 00 00 01`).

## Stream characteristics

| Property | Value | Why it matters |
|---|---|---|
| Codec | H.264 **Baseline** | simplest profile — good for an old OMX |
| Level | 3.1 | |
| Resolution | 800x480 (the one we asked for in `Open`) | the dongle **honours** the requested size |
| Pixel format | yuv420p | OMX's native format |
| **B-frames** | **0** | no reordering → simple decoding and low latency |
| Rate | ~630 frames in ~27s ≈ 23 fps | |

Validated with `ffprobe` + `ffmpeg`: 630 of 630 frames decoded, with a frame extracted to PNG
showing the Android Auto interface (map, contacts, player).

Baseline with no B-frames is the best possible scenario for API 15's `omxvideocodec`.

## ⚠️ SPS/PPS appear ONLY ONCE, at the beginning

NAL unit distribution in the capture:

| Type | Name | Occurrences |
|---|---|---|
| 7 | SPS | **1** |
| 8 | PPS | **1** |
| 5 | I-slice (IDR) | **1** |
| 1 | P-slice | 629 |

SPS: `67 42 80 1f da 03 20 f6` (profile_idc 0x42 = Baseline, level 0x1f = 3.1)

**Consequences for the implementation:**

1. The app **must cache** SPS/PPS on arrival and feed them to the decoder before any P-slice.
   `omxvideocodec` already has `nativeSetSps()` for that — and the project's `mediaDecode()`
   detects SPS. It fits well.
2. If the app starts after the dongle is already transmitting, or if the `Surface` is recreated
   (rotation, leaving and returning to the app), **there will be no new SPS** and the decoder
   will have no way to start. In that case a keyframe must be forced with `Command frame (12)`,
   or the cached SPS/PPS resent.
3. Only **one** IDR in 630 frames: losing the initial keyframe means a black screen until the
   next one. The `frame (12)` command should be used to request a refresh whenever the surface
   changes.

## Audio

113 `AUDIO_DATA` (0x07) packets in the same capture. The first one is 13 bytes — probably just
a header/format metadata, with no PCM. Format **not analyzed yet** — next step.

## Tools

- `tools/capture_video2.py` — full sequence + automatic offset/NAL analysis
- `tools/diag_connection.py` — decodes status messages (useful when there is no video)
- Captures land in `captures/` (outside git)
