#!/usr/bin/env python3
"""
Diagnostics of the Carlinkit dongle connection state.

Decodes the Command (0x08) and Plugged (0x02) messages, which report the pairing
progress: scanning -> deviceFound -> btConnected -> wifiConnected -> video.

Usage: sudo ./venv/bin/python tools/diag_connection.py [seconds]
"""
import struct
import sys
import threading
import time

import usb.core
import usb.util

VID, PID, MAGIC = 0x1314, 0x1521, 0x55AA55AA

# Values the dongle sends to report state (CommandMapping from node-CarPlay)
CMD_STATE = {
    1000: "wifiEnable", 1001: "autoConnectEnable", 1002: "wifiConnect",
    1003: "scanningDevice  (looking for phone)",
    1004: "deviceFound     (phone found)",
    1005: "deviceNotFound  (no phone)",
    1006: "connectDeviceFailed (connection failed)",
    1007: "btConnected     (Bluetooth connected)",
    1008: "btDisconnected  (Bluetooth disconnected)",
    1009: "wifiConnected   (WiFi connected - video should start)",
    1010: "wifiDisconnected",
    1011: "btPairStart     (pairing started)",
    1012: "wifiPair",
    3: "requestHostUI", 500: "requestVideoFocus", 501: "releaseVideoFocus",
    12: "frame", 22: "audioTransferOn", 23: "audioTransferOff",
    7: "mic", 15: "boxMic", 24: "wifi24g", 25: "wifi5g",
}
PHONE_TYPE = {1: "CarPlay (iPhone)", 3: "Android Auto", 4: "HiCar", 5: "AndroidMirror"}
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


def main():
    duration = int(sys.argv[1]) if len(sys.argv) > 1 else 40
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

    send(dev, ep_out, 0x01, struct.pack("<IIIIIII", 800, 480, 20, 5, 49152, 2, 2))
    send(dev, ep_out, 0x19,
         ('{"mediaDelay":300,"syncTime":%d,"androidAutoSizeW":800,"androidAutoSizeH":480}'
          % int(time.time() * 1000)).encode("ascii"))
    for c in (1000, 25, 7, 23):
        send(dev, ep_out, 0x08, struct.pack("<I", c))
        time.sleep(0.05)
    # Request video focus and signal autoconnect: without this the dongle may not start the stream
    send(dev, ep_out, 0x08, struct.pack("<I", 1001))  # autoConnectEnable
    send(dev, ep_out, 0x08, struct.pack("<I", 500))   # requestVideoFocus
    threading.Thread(target=heartbeat, args=(dev, ep_out), daemon=True).start()

    print("--- Monitoring state for %ds ---\n" % duration)
    t0 = time.time()
    deadline = t0 + duration
    video = audio = 0

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
        rem = length
        while rem > 0:
            try:
                c = dev.read(ep_in, min(rem, 16384), timeout=1500)
            except usb.core.USBError:
                break
            payload += bytes(c)
            rem -= len(c)

        el = time.time() - t0
        if mtype == 0x08 and len(payload) >= 4:
            v = struct.unpack("<I", payload[:4])[0]
            print("  [%5.1fs] Command  %-5d %s" % (el, v, CMD_STATE.get(v, "?")))
        elif mtype == 0x02:
            v = struct.unpack("<I", payload[:4])[0] if len(payload) >= 4 else -1
            print("  [%5.1fs] PLUGGED  type=%s  (phone connected!)"
                  % (el, PHONE_TYPE.get(v, v)))
        elif mtype == 0x04:
            print("  [%5.1fs] UNPLUGGED (phone disconnected)" % el)
        elif mtype == 0x03:
            print("  [%5.1fs] Phase    %s" % (el, payload[:16].hex(" ")))
        elif mtype == 0x06:
            video += 1
            if video <= 3 or video % 50 == 0:
                print("  [%5.1fs] VIDEO    #%d (%d bytes)" % (el, video, length))
        elif mtype == 0x07:
            audio += 1
            if audio <= 3:
                print("  [%5.1fs] AUDIO    #%d (%d bytes)" % (el, audio, length))
        elif mtype == 0x0D:
            print("  [%5.1fs] BT name  %s" % (el, payload.decode("utf-8", "replace").strip()))
        elif mtype == 0x0E:
            print("  [%5.1fs] WiFi name %s" % (el, payload.decode("utf-8", "replace").strip()))
        elif mtype == 0x12:
            print("  [%5.1fs] PairedList %s" % (el, payload.hex(" ")[:60]))

    stop.set()
    print("\n--- Result ---")
    print("  video frames: %d | audio packets: %d" % (video, audio))
    if not video:
        print("\n  No video. Check on the phone:")
        print("   - Bluetooth paired with 'AutoKit-e38a'?")
        print("   - Connected to WiFi 'AutoBox-3c61'? (may require accepting the network)")
        print("   - iPhone: accept the CarPlay prompt; Android: accept Android Auto")
    usb.util.release_interface(dev, intf.bInterfaceNumber)
    return 0


if __name__ == "__main__":
    sys.exit(main())
