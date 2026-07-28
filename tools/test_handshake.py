#!/usr/bin/env python3
"""
Full handshake with the Carlinkit dongle (1314:1521) to extract identification.

Sequence as per node-CarPlay/DongleDriver.ts:
  SendOpen -> SendBoxSettings(JSON) -> wifiEnable -> wifi5g -> mic -> audioTransferOff
Then sends a periodic HeartBeat and collects SoftwareVersion / ManufacturerInfo.
"""
import json
import struct
import sys
import threading
import time

import usb.core
import usb.util

VID, PID, MAGIC = 0x1314, 0x1521, 0x55AA55AA

MSG = {
    0x01: "Open", 0x02: "Plugged", 0x03: "Phase", 0x04: "Unplugged",
    0x06: "VideoData", 0x07: "AudioData", 0x08: "Command", 0x09: "LogoType",
    0x0A: "BluetoothAddress", 0x0C: "BluetoothPIN", 0x0D: "BluetoothDeviceName",
    0x0E: "WifiDeviceName", 0x12: "BluetoothPairedList", 0x14: "ManufacturerInfo",
    0x17: "MultiTouch", 0x18: "HiCarLink", 0x19: "BoxSettings",
    0x2A: "MediaData", 0xAA: "HeartBeat", 0xCC: "SoftwareVersion",
}
CMD = {"wifiEnable": 1000, "wifi5g": 25, "mic": 7, "audioTransferOff": 23,
       "frame": 12, "requestVideoFocus": 500}

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

    print("--- Initialization sequence ---")
    # 800x480 is the typical resolution of a 2016 Honda head unit
    send(dev, ep_out, 0x01, struct.pack("<IIIIIII", 800, 480, 20, 5, 49152, 2, 2))
    print("  SendOpen (800x480@20)")

    settings = json.dumps({"mediaDelay": 300, "syncTime": int(time.time() * 1000),
                           "androidAutoSizeW": 800, "androidAutoSizeH": 480})
    send(dev, ep_out, 0x19, settings.encode("ascii"))
    print("  SendBoxSettings")

    for name in ("wifiEnable", "wifi5g", "mic", "audioTransferOff"):
        send(dev, ep_out, 0x08, struct.pack("<I", CMD[name]))
        print(f"  SendCommand({name})")
        time.sleep(0.1)

    threading.Thread(target=heartbeat, args=(dev, ep_out), daemon=True).start()

    print("\n--- Collecting (12s) ---")
    seen, info = {}, {}
    deadline = time.time() + 12
    while time.time() < deadline:
        try:
            raw = dev.read(ep_in, 16, timeout=1200)
        except usb.core.USBTimeoutError:
            continue
        except usb.core.USBError as e:
            print(f"  USBError: {e}"); break
        if len(raw) != 16:
            continue
        magic, length, mtype, _ = struct.unpack("<IIII", bytes(raw))
        if magic != MAGIC:
            continue
        payload = b""
        if length:
            try:
                payload = bytes(dev.read(ep_in, length, timeout=1200))
            except usb.core.USBError:
                pass
        name = MSG.get(mtype, f"0x{mtype:02X}")
        seen[name] = seen.get(name, 0) + 1

        if name in ("SoftwareVersion", "ManufacturerInfo", "HiCarLink",
                    "BluetoothDeviceName", "WifiDeviceName", "BluetoothAddress"):
            if name not in info:
                txt = payload.decode("utf-8", "replace").strip("\x00").strip()
                info[name] = txt if txt.isprintable() and txt else payload[:64].hex()
        if name == "Plugged" and "Plugged" not in info:
            info["Plugged"] = payload.hex()

    stop.set()
    print("\n--- Messages received ---")
    for k, v in sorted(seen.items(), key=lambda x: -x[1]):
        print(f"  {k:24} {v}x")
    print("\n--- Identification extracted ---")
    if info:
        for k, v in info.items():
            print(f"  {k}: {v[:200]}")
    else:
        print("  (nothing identifiable)")

    usb.util.release_interface(dev, intf.bInterfaceNumber)
    return 0


if __name__ == "__main__":
    sys.exit(main())
