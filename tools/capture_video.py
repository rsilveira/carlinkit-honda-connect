#!/usr/bin/env python3
"""
Captures and analyzes the video stream from the Carlinkit dongle.

Goal: determine exactly how the H.264 is packed inside the VIDEO_DATA messages,
so the OMX decoder on Android can be fed without guessing offsets.

Answers three questions:
  1. How many metadata bytes precede the H.264? (assumed 20, to be confirmed)
  2. Does SPS/PPS come once at the start or repeated on every keyframe?
  3. Which NAL unit types show up and in what order?

Prerequisite: the phone must be paired with the dongle (Bluetooth "AutoKit-*"),
otherwise the dongle emits no video and only control messages arrive.

Usage: sudo ./venv/bin/python tools/capture_video.py [seconds]
Output: captures/video-<timestamp>.h264  (raw stream, testable with ffmpeg/ffprobe)
"""
import os
import struct
import sys
import threading
import time

import usb.core
import usb.util

VID, PID, MAGIC = 0x1314, 0x1521, 0x55AA55AA
OUT_DIR = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "captures")

# Names of the H.264 NAL unit types that matter here
NAL = {1: "P/B-slice", 5: "I-slice(IDR)", 6: "SEI", 7: "SPS", 8: "PPS", 9: "AUD"}

stop = threading.Event()


def hdr(t, n):
    return struct.pack("<IIII", MAGIC, n, t, (t ^ -1) & 0xFFFFFFFF)


def send(dev, ep, t, payload=b""):
    dev.write(ep, hdr(t, len(payload)), timeout=2000)
    if payload:
        dev.write(ep, payload, timeout=2000)


def heartbeat(dev, ep):
    while not stop.is_set():
        try:
            send(dev, ep, 0xAA)
        except usb.core.USBError:
            return
        stop.wait(2)


def find_start_codes(data, limit=6):
    """Locates Annex-B start codes (00 00 01 / 00 00 00 01) and the following NAL type."""
    found = []
    i = 0
    while i < len(data) - 4 and len(found) < limit:
        if data[i] == 0 and data[i + 1] == 0:
            if data[i + 2] == 1:
                nal = data[i + 3] & 0x1F
                found.append((i, 3, nal))
                i += 4
                continue
            if data[i + 2] == 0 and data[i + 3] == 1 and i + 4 < len(data):
                nal = data[i + 4] & 0x1F
                found.append((i, 4, nal))
                i += 5
                continue
        i += 1
    return found


def main():
    duration = int(sys.argv[1]) if len(sys.argv) > 1 else 20
    os.makedirs(OUT_DIR, exist_ok=True)

    dev = usb.core.find(idVendor=VID, idProduct=PID)
    if dev is None:
        print("ERROR: dongle not found")
        return 1

    intf = dev.get_active_configuration()[(0, 0)]
    ep_out = usb.util.find_descriptor(intf, custom_match=lambda e:
        usb.util.endpoint_direction(e.bEndpointAddress) == usb.util.ENDPOINT_OUT).bEndpointAddress
    ep_in = usb.util.find_descriptor(intf, custom_match=lambda e:
        usb.util.endpoint_direction(e.bEndpointAddress) == usb.util.ENDPOINT_IN).bEndpointAddress
    try:
        usb.util.claim_interface(dev, intf.bInterfaceNumber)
    except usb.core.USBError:
        pass

    print("--- Initializing ---")
    send(dev, ep_out, 0x01, struct.pack("<IIIIIII", 800, 480, 20, 5, 49152, 2, 2))
    send(dev, ep_out, 0x19,
         ('{"mediaDelay":300,"syncTime":%d,"androidAutoSizeW":800,"androidAutoSizeH":480}'
          % int(time.time() * 1000)).encode("ascii"))
    for cmd in (1000, 25, 7, 23):          # wifiEnable, wifi5g, mic, audioTransferOff
        send(dev, ep_out, 0x08, struct.pack("<I", cmd))
        time.sleep(0.05)
    send(dev, ep_out, 0x08, struct.pack("<I", 500))   # requestVideoFocus
    threading.Thread(target=heartbeat, args=(dev, ep_out), daemon=True).start()

    path = os.path.join(OUT_DIR, "video-%s.h264" % time.strftime("%Y%m%d-%H%M%S"))
    fh = open(path, "wb")

    print("--- Capturing %ds ---" % duration)
    print("    (if there is no video, pair the phone with the dongle over Bluetooth)")

    counts, first_video, nal_hist = {}, None, {}
    video_msgs = total_bytes = 0
    deadline = time.time() + duration

    while time.time() < deadline:
        try:
            raw = dev.read(ep_in, 16, timeout=1200)
        except usb.core.USBTimeoutError:
            continue
        except usb.core.USBError as e:
            print("  USBError: %s" % e)
            break
        if len(raw) != 16:
            continue
        magic, length, mtype, _ = struct.unpack("<IIII", bytes(raw))
        if magic != MAGIC:
            continue

        payload = b""
        remaining = length
        while remaining > 0:
            try:
                chunk = dev.read(ep_in, min(remaining, 16384), timeout=1500)
            except usb.core.USBError:
                break
            payload += bytes(chunk)
            remaining -= len(chunk)

        counts[mtype] = counts.get(mtype, 0) + 1

        if mtype == 0x06:  # VideoData
            video_msgs += 1
            total_bytes += len(payload)
            if first_video is None:
                first_video = payload
            # Write starting at the offset where the H.264 begins
            sc = find_start_codes(payload, limit=1)
            offset = sc[0][0] if sc else 20
            fh.write(payload[offset:])
            for _, _, nal in find_start_codes(payload, limit=20):
                nal_hist[nal] = nal_hist.get(nal, 0) + 1

    stop.set()
    fh.close()

    print("\n--- Messages ---")
    for t, n in sorted(counts.items(), key=lambda x: -x[1]):
        name = {0x06: "VideoData", 0x07: "AudioData", 0x02: "Plugged", 0x08: "Command",
                0xAA: "HeartBeat", 0x2A: "MediaData", 0x12: "BluetoothPairedList",
                0x18: "HiCarLink", 0xCC: "SoftwareVersion"}.get(t, "0x%02X" % t)
        print("  %-22s %d" % (name, n))

    if not video_msgs:
        print("\nNO video frame received.")
        print("The dongle only emits video when a phone is connected to it.")
        os.remove(path)
        return 2

    print("\n--- VIDEO_DATA analysis ---")
    print("  messages: %d | payload bytes: %d" % (video_msgs, total_bytes))
    print("  first 32 bytes of the 1st message:")
    print("    %s" % first_video[:32].hex(" "))
    sc = find_start_codes(first_video)
    if sc:
        off, sclen, nal = sc[0]
        print("  1st start code at offset %d (%d bytes), NAL type %d (%s)"
              % (off, sclen, nal, NAL.get(nal, "?")))
        print("  -> METADATA = %d bytes before the H.264" % off)
        if off >= 16:
            meta = struct.unpack("<%dI" % (off // 4), first_video[:off - off % 4])
            print("  metadata as uint32: %s" % (meta,))
    else:
        print("  no start code found - format may not be Annex-B")

    print("\n  NAL units seen:")
    for nal, n in sorted(nal_hist.items(), key=lambda x: -x[1]):
        print("    type %-2d %-14s %d" % (nal, NAL.get(nal, "?"), n))
    has_sps = nal_hist.get(7, 0)
    print("\n  SPS (type 7): %d occurrences -> %s"
          % (has_sps, "repeated on keyframes" if has_sps > 1
             else "only at the start" if has_sps == 1 else "MISSING (problem)"))

    print("\n  file: %s (%d bytes)" % (path, os.path.getsize(path)))
    print("  validate with: ffprobe %s" % path)

    usb.util.release_interface(dev, intf.bInterfaceNumber)
    return 0


if __name__ == "__main__":
    sys.exit(main())
