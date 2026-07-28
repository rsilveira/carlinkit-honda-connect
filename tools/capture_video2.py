#!/usr/bin/env python3
"""
Video capture from the Carlinkit dongle with the COMPLETE initialization sequence.

Difference from the previous versions (which got no video):
  - sends the SendFile (0x99) configuration messages: dpi, night_mode,
    hand_drive_mode, charge_mode, box_name
  - sends SendCommand(wifiConnect=1002) ~1s after init  <-- this was missing:
    it is this command that makes the dongle connect to the paired phone

Usage: sudo ./venv/bin/python tools/capture_video2.py [seconds]
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

NAL = {1: "P/B-slice", 5: "I-slice(IDR)", 6: "SEI", 7: "SPS", 8: "PPS", 9: "AUD"}
CMD_STATE = {
    1002: "wifiConnect", 1003: "scanningDevice", 1004: "deviceFound",
    1005: "deviceNotFound", 1006: "connectDeviceFailed", 1007: "btConnected",
    1008: "btDisconnected", 1009: "wifiConnected", 1010: "wifiDisconnected",
    1011: "btPairStart", 1012: "wifiPair", 1000: "wifiEnable",
    1001: "autoConnectEnable", 7: "mic", 23: "audioTransferOff", 25: "wifi5g",
}
PHONE = {1: "CarPlay(iPhone)", 3: "AndroidAuto", 4: "HiCar", 5: "AndroidMirror"}
stop = threading.Event()
ANDROID_MODE = True


def hdr(t, n):
    return struct.pack("<IIII", MAGIC, n, t, (t ^ -1) & 0xFFFFFFFF)


def send(dev, ep, t, payload=b""):
    dev.write(ep, hdr(t, len(payload)), timeout=3000)
    if payload:
        dev.write(ep, payload, timeout=3000)


def send_file(dev, ep, name, content):
    """SendFile (0x99): [len(name+NUL)][name+NUL][len(content)][content]"""
    nb = (name + "\0").encode("ascii")
    payload = struct.pack("<I", len(nb)) + nb + struct.pack("<I", len(content)) + content
    send(dev, ep, 0x99, payload)


def send_int_file(dev, ep, name, value):
    send_file(dev, ep, name, struct.pack("<I", value))


def heartbeat(dev, ep):
    while not stop.is_set():
        try:
            send(dev, ep, 0xAA)
        except usb.core.USBError:
            return
        stop.wait(2)


def start_codes(data, limit=8):
    out, i = [], 0
    while i < len(data) - 4 and len(out) < limit:
        if data[i] == 0 and data[i + 1] == 0:
            if data[i + 2] == 1:
                out.append((i, 3, data[i + 3] & 0x1F)); i += 4; continue
            if data[i + 2] == 0 and data[i + 3] == 1:
                out.append((i, 4, data[i + 4] & 0x1F)); i += 5; continue
        i += 1
    return out


def main():
    duration = int(sys.argv[1]) if len(sys.argv) > 1 else 45
    global ANDROID_MODE
    ANDROID_MODE = "--android" in sys.argv or True   # default: Android mode (S26 phone)
    os.makedirs(OUT_DIR, exist_ok=True)
    W, H, FPS, DPI = 800, 480, 20, 140

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

    print("--- Full initialization ---")
    send_int_file(dev, ep_out, "/tmp/screen_dpi", DPI)
    send(dev, ep_out, 0x01, struct.pack("<IIIIIII", W, H, FPS, 5, 49152, 2, 2))
    send_int_file(dev, ep_out, "/tmp/night_mode", 0)
    send_int_file(dev, ep_out, "/tmp/hand_drive_mode", 0)      # 0 = left-hand drive
    send_int_file(dev, ep_out, "/tmp/charge_mode", 1)
    send_file(dev, ep_out, "/etc/box_name", b"HondaHRV\0")
    # Android phones require this mode; without it the dongle does not bring up the BT/WiFi link
    if ANDROID_MODE:
        send_int_file(dev, ep_out, "/etc/android_work_mode", 1)
        print("  android_work_mode = 1")
    send(dev, ep_out, 0x19,
         ('{"mediaDelay":300,"syncTime":%d,"androidAutoSizeW":%d,"androidAutoSizeH":%d}'
          % (int(time.time() * 1000), W, H)).encode("ascii"))
    for c in (1000, 25, 7, 23):
        send(dev, ep_out, 0x08, struct.pack("<I", c)); time.sleep(0.05)
    print("  config + open + settings sent")

    threading.Thread(target=heartbeat, args=(dev, ep_out), daemon=True).start()

    def delayed_connect():
        time.sleep(1.0)
        send(dev, ep_out, 0x08, struct.pack("<I", 1002))   # wifiConnect
        print("  >> wifiConnect sent")
    threading.Thread(target=delayed_connect, daemon=True).start()

    path = os.path.join(OUT_DIR, "video-%s.h264" % time.strftime("%Y%m%d-%H%M%S"))
    fh = open(path, "wb")
    print("\n--- Capturing %ds ---" % duration)

    t0 = time.time()
    deadline = t0 + duration
    video = audio = total = 0
    first = None
    nal_hist = {}

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
        if mtype == 0x06:
            video += 1; total += len(payload)
            if first is None:
                first = payload
                print("  [%5.1fs] FIRST VIDEO FRAME (%d bytes)" % (el, length))
            sc = start_codes(payload, 1)
            fh.write(payload[sc[0][0] if sc else 20:])
            for _, _, n in start_codes(payload, 30):
                nal_hist[n] = nal_hist.get(n, 0) + 1
        elif mtype == 0x07:
            audio += 1
            if audio == 1:
                print("  [%5.1fs] FIRST AUDIO (%d bytes)" % (el, length))
        elif mtype == 0x08 and len(payload) >= 4:
            v = struct.unpack("<I", payload[:4])[0]
            print("  [%5.1fs] Command %-5d %s" % (el, v, CMD_STATE.get(v, "?")))
        elif mtype == 0x02:
            v = struct.unpack("<I", payload[:4])[0] if len(payload) >= 4 else -1
            print("  [%5.1fs] PLUGGED type=%s" % (el, PHONE.get(v, v)))
        elif mtype == 0x04:
            print("  [%5.1fs] UNPLUGGED" % el)

    stop.set(); fh.close()
    print("\n--- Result ---")
    print("  video: %d frames (%d bytes) | audio: %d packets" % (video, total, audio))

    if not video:
        os.remove(path)
        print("\n  Still no video.")
        return 2

    print("\n--- VIDEO_DATA format ---")
    print("  first 32 bytes: %s" % first[:32].hex(" "))
    sc = start_codes(first)
    if sc:
        off, l, n = sc[0]
        print("  1st start code: offset %d (%d bytes), NAL %d (%s)" % (off, l, n, NAL.get(n, "?")))
        print("  >> METADATA = %d bytes" % off)
        if off >= 4:
            print("  metadata uint32: %s" % (struct.unpack("<%dI" % (off // 4), first[:off - off % 4]),))
    print("\n  NAL units:")
    for n, c in sorted(nal_hist.items(), key=lambda x: -x[1]):
        print("    %-2d %-14s %d" % (n, NAL.get(n, "?"), c))
    sps = nal_hist.get(7, 0)
    print("\n  SPS: %d -> %s" % (sps, "repeated" if sps > 1 else "only at the start" if sps == 1 else "MISSING"))
    print("\n  file: %s (%d bytes)" % (path, os.path.getsize(path)))
    usb.util.release_interface(dev, intf.bInterfaceNumber)
    return 0


if __name__ == "__main__":
    sys.exit(main())
