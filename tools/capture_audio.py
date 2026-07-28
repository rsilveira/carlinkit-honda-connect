#!/usr/bin/env python3
"""
Captures and analyzes the AUDIO stream from the Carlinkit dongle.

AUDIO_DATA (0x07) payload structure, as per node-CarPlay:
  [0..3]   decodeType  uint32  -> index into the format table
  [4..7]   volume      float32
  [8..11]  audioType   uint32
  [12..]   content:
             1 byte   -> AudioCommand (media start/stop, call, navigation...)
             4 bytes  -> volumeDuration (float32)
             > 4      -> PCM Int16LE

Output: one .wav per (decodeType, audioType), ready to listen to and validate.

Prerequisite: phone paired AND playing audio.
Usage: sudo ./venv/bin/python tools/capture_audio.py [seconds]
"""
import os
import struct
import sys
import threading
import time
import wave

import usb.core
import usb.util

VID, PID, MAGIC = 0x1314, 0x1521, 0x55AA55AA
OUT_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "captures")

# decodeType -> (frequency, channels, bits)
FORMATS = {
    1: (44100, 2, 16), 2: (44100, 2, 16), 3: (8000, 1, 16), 4: (48000, 2, 16),
    5: (16000, 1, 16), 6: (24000, 1, 16), 7: (16000, 2, 16),
}
AUDIO_CMD = {
    1: "OutputStart", 2: "OutputStop", 3: "InputConfig", 4: "PhonecallStart",
    5: "PhonecallStop", 6: "NaviStart", 7: "NaviStop", 8: "SiriStart",
    9: "SiriStop", 10: "MediaStart", 11: "MediaStop", 12: "AlertStart", 13: "AlertStop",
}
stop = threading.Event()


def hdr(t, n):
    return struct.pack("<IIII", MAGIC, n, t, (t ^ -1) & 0xFFFFFFFF)


def send(dev, ep, t, payload=b""):
    dev.write(ep, hdr(t, len(payload)), timeout=3000)
    if payload:
        dev.write(ep, payload, timeout=3000)


def send_file(dev, ep, name, content):
    nb = (name + "\0").encode("ascii")
    send(dev, ep, 0x99,
         struct.pack("<I", len(nb)) + nb + struct.pack("<I", len(content)) + content)


def send_int_file(dev, ep, name, v):
    send_file(dev, ep, name, struct.pack("<I", v))


def heartbeat(dev, ep):
    while not stop.is_set():
        try:
            send(dev, ep, 0xAA)
        except usb.core.USBError:
            return
        stop.wait(2)


def main():
    duration = int(sys.argv[1]) if len(sys.argv) > 1 else 40
    os.makedirs(OUT_DIR, exist_ok=True)
    W, H = 800, 480

    dev = usb.core.find(idVendor=VID, idProduct=PID)
    if dev is None:
        print("ERROR: dongle not found"); return 1
    intf = dev.get_active_configuration()[(0, 0)]
    ep_out = usb.util.find_descriptor(intf, custom_match=lambda e:
        usb.util.endpoint_direction(e.bEndpointAddress) == usb.util.ENDPOINT_OUT).bEndpointAddress
    ep_in = usb.util.find_descriptor(intf, custom_match=lambda e:
        usb.util.endpoint_direction(e.bEndpointAddress) == usb.util.ENDPOINT_IN).bEndpointAddress
    try:
        usb.util.claim_interface(dev, intf.bInterfaceNumber)
    except usb.core.USBError:
        pass

    print("--- Initializing (Android mode) ---")
    send_int_file(dev, ep_out, "/tmp/screen_dpi", 140)
    send(dev, ep_out, 0x01, struct.pack("<IIIIIII", W, H, 20, 5, 49152, 2, 2))
    send_int_file(dev, ep_out, "/tmp/night_mode", 0)
    send_int_file(dev, ep_out, "/tmp/hand_drive_mode", 0)
    send_int_file(dev, ep_out, "/tmp/charge_mode", 1)
    send_file(dev, ep_out, "/etc/box_name", b"HondaHRV\0")
    send_int_file(dev, ep_out, "/etc/android_work_mode", 1)
    send(dev, ep_out, 0x19,
         ('{"mediaDelay":300,"syncTime":%d,"androidAutoSizeW":%d,"androidAutoSizeH":%d}'
          % (int(time.time() * 1000), W, H)).encode("ascii"))
    for c in (1000, 25, 7, 23):
        send(dev, ep_out, 0x08, struct.pack("<I", c)); time.sleep(0.05)
    threading.Thread(target=heartbeat, args=(dev, ep_out), daemon=True).start()
    threading.Thread(target=lambda: (time.sleep(1.0),
                     send(dev, ep_out, 0x08, struct.pack("<I", 1002))), daemon=True).start()

    print("--- Capturing %ds (play a song on the phone) ---\n" % duration)
    t0, deadline = time.time(), time.time() + duration
    streams = {}          # (decodeType, audioType) -> bytearray of PCM
    commands, stats = [], {}
    vol_seen = set()

    while time.time() < deadline:
        try:
            raw = dev.read(ep_in, 16, timeout=1200)
        except usb.core.USBTimeoutError:
            continue
        except usb.core.USBError as e:
            print("  USBError: %s" % e); break
        if len(raw) != 16:
            continue
        magic, length, mtype, _ = struct.unpack("<IIII", bytes(raw))
        if magic != MAGIC:
            continue
        payload, rem = b"", length
        while rem > 0:
            try:
                c = dev.read(ep_in, min(rem, 16384), timeout=2000)
            except usb.core.USBError:
                break
            payload += bytes(c); rem -= len(c)

        el = time.time() - t0
        if mtype != 0x07 or len(payload) < 12:
            if mtype == 0x08 and len(payload) >= 4:
                v = struct.unpack("<I", payload[:4])[0]
                if v in (1002, 1003, 1004, 1007, 1009):
                    print("  [%5.1fs] Command %d" % (el, v))
            continue

        dtype, volume, atype = struct.unpack("<IfI", payload[:12])
        body = payload[12:]
        key = (dtype, atype)
        stats[key] = stats.get(key, 0) + 1

        if len(body) == 1:
            cmd = struct.unpack("<b", body)[0]
            commands.append((el, dtype, atype, cmd))
            print("  [%5.1fs] AudioCommand %-14s (decodeType=%d audioType=%d vol=%.2f)"
                  % (el, AUDIO_CMD.get(cmd, cmd), dtype, atype, volume))
        elif len(body) == 4:
            dur = struct.unpack("<f", body)[0]
            print("  [%5.1fs] volumeDuration=%.2f vol=%.2f" % (el, dur, volume))
        else:
            streams.setdefault(key, bytearray()).extend(body)
            if round(volume, 2) not in vol_seen:
                vol_seen.add(round(volume, 2))
            if stats[key] == 1:
                fmt = FORMATS.get(dtype)
                print("  [%5.1fs] PCM starts: decodeType=%d %s audioType=%d (%d bytes)"
                      % (el, dtype, ("%dHz %dch %dbit" % fmt) if fmt else "UNKNOWN FORMAT",
                         atype, len(body)))

    stop.set()
    print("\n--- Summary ---")
    if not stats:
        print("  no audio message received")
        return 2
    for (d, a), n in sorted(stats.items(), key=lambda x: -x[1]):
        fmt = FORMATS.get(d)
        print("  decodeType=%d audioType=%d: %d messages  %s"
              % (d, a, n, ("%dHz %dch %dbit" % fmt) if fmt else "?"))
    print("  volumes seen: %s" % sorted(vol_seen))

    print("\n--- Generated WAVs ---")
    for (d, a), pcm in streams.items():
        fmt = FORMATS.get(d)
        if not fmt:
            print("  decodeType=%d unknown - %d raw bytes not converted" % (d, len(pcm)))
            continue
        freq, ch, bits = fmt
        path = os.path.join(OUT_DIR, "audio-d%d-a%d-%s.wav"
                            % (d, a, time.strftime("%Y%m%d-%H%M%S")))
        with wave.open(path, "wb") as w:
            w.setnchannels(ch)
            w.setsampwidth(bits // 8)
            w.setframerate(freq)
            w.writeframes(bytes(pcm))
        secs = len(pcm) / float(freq * ch * bits // 8)
        print("  %s  (%d bytes, %.1fs of audio)" % (path, len(pcm), secs))

    usb.util.release_interface(dev, intf.bInterfaceNumber)
    return 0


if __name__ == "__main__":
    sys.exit(main())
