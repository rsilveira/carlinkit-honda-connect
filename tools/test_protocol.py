#!/usr/bin/env python3
"""
USB protocol test for the Carlinkit dongle (CPC200 "Auto Box", 1314:1521).

Goal: validate on a Linux PC that the protocol works before writing any Android code.
If the dongle answers the handshake, the implementation on the HR-V head unit is
feasible.

Protocol (as per rhysmorgan134/node-CarPlay):
  Header: 16 bytes little-endian
    magic     uint32 = 0x55AA55AA
    length    uint32 = payload size
    type      uint32 = MessageType
    typeCheck uint32 = (type ^ -1) & 0xFFFFFFFF
  Payload (optional) follows the header, in a separate bulk transfer.
"""
import struct
import sys
import time

import usb.core
import usb.util

VID, PID = 0x1314, 0x1521
MAGIC = 0x55AA55AA

MSG = {
    0x01: "Open",          0x02: "Plugged",        0x03: "Phase",
    0x04: "Unplugged",     0x05: "Touch",          0x06: "VideoData",
    0x07: "AudioData",     0x08: "Command",        0x09: "LogoType",
    0x0A: "BluetoothAddress", 0x0C: "BluetoothPIN", 0x0D: "BluetoothDeviceName",
    0x0E: "WifiDeviceName",   0x0F: "DisconnectPhone",
    0x12: "BluetoothPairedList", 0x14: "ManufacturerInfo", 0x15: "CloseDongle",
    0x17: "MultiTouch",    0x18: "HiCarLink",      0x19: "BoxSettings",
    0x2A: "MediaData",     0x99: "SendFile",       0xAA: "HeartBeat",
    0xCC: "SoftwareVersion",
}


def header(msg_type: int, payload_len: int) -> bytes:
    type_check = (msg_type ^ -1) & 0xFFFFFFFF
    return struct.pack("<IIII", MAGIC, payload_len, msg_type, type_check)


def send(dev, ep_out, msg_type: int, payload: bytes = b"") -> None:
    dev.write(ep_out, header(msg_type, len(payload)), timeout=2000)
    if payload:
        dev.write(ep_out, payload, timeout=2000)


def send_open(dev, ep_out, width=800, height=480, fps=20, fmt=5,
              packet_max=49152, ibox=2, phone_mode=2) -> None:
    """Payload: width, height, fps, format, packetMax, iBoxVersion, phoneWorkMode"""
    payload = struct.pack("<IIIIIII", width, height, fps, fmt, packet_max, ibox, phone_mode)
    send(dev, ep_out, 0x01, payload)


def read_message(dev, ep_in, timeout=3000):
    """Reads a 16-byte header and the corresponding payload."""
    raw = dev.read(ep_in, 16, timeout=timeout)
    if len(raw) != 16:
        return None, None, f"incomplete header ({len(raw)} bytes)"
    magic, length, mtype, tcheck = struct.unpack("<IIII", bytes(raw))
    if magic != MAGIC:
        return None, None, f"invalid magic: 0x{magic:08X}"
    expected = (mtype ^ -1) & 0xFFFFFFFF
    if tcheck != expected:
        return None, None, f"invalid typeCheck: 0x{tcheck:08X} != 0x{expected:08X}"
    payload = b""
    if length:
        try:
            payload = bytes(dev.read(ep_in, length, timeout=timeout))
        except usb.core.USBTimeoutError:
            return mtype, b"", "payload timeout"
    return mtype, payload, None


def main():
    dev = usb.core.find(idVendor=VID, idProduct=PID)
    if dev is None:
        print("ERROR: dongle 1314:1521 not found")
        return 1
    print(f"Dongle: {dev.manufacturer} / {dev.product}  (bcdDevice {dev.bcdDevice:#06x})")

    cfg = dev.get_active_configuration()
    # Interface 0 = Vendor Specific (protocol channel). Interface 1 = Mass Storage.
    intf = cfg[(0, 0)]
    print(f"Interface {intf.bInterfaceNumber}, class 0x{intf.bInterfaceClass:02X}")

    ep_out = usb.util.find_descriptor(
        intf, custom_match=lambda e: usb.util.endpoint_direction(e.bEndpointAddress) == usb.util.ENDPOINT_OUT)
    ep_in = usb.util.find_descriptor(
        intf, custom_match=lambda e: usb.util.endpoint_direction(e.bEndpointAddress) == usb.util.ENDPOINT_IN)
    if ep_out is None or ep_in is None:
        print("ERROR: bulk endpoints not found")
        return 1
    print(f"EP OUT 0x{ep_out.bEndpointAddress:02X} | EP IN 0x{ep_in.bEndpointAddress:02X}")

    try:
        usb.util.claim_interface(dev, intf.bInterfaceNumber)
    except usb.core.USBError as e:
        print(f"WARNING on claim: {e}")

    print("\n--- Sending SendOpen (800x480 @20fps) ---")
    try:
        send_open(dev, ep_out.bEndpointAddress)
        print("  sent successfully")
    except usb.core.USBError as e:
        print(f"  ERROR while sending: {e}")
        return 1

    print("\n--- Reading responses (8s) ---")
    seen, deadline = {}, time.time() + 8
    while time.time() < deadline:
        try:
            mtype, payload, err = read_message(dev, ep_in.bEndpointAddress, timeout=1500)
        except usb.core.USBTimeoutError:
            continue
        except usb.core.USBError as e:
            print(f"  USBError: {e}")
            break
        if err and mtype is None:
            print(f"  discarded: {err}")
            continue
        name = MSG.get(mtype, f"Unknown(0x{mtype:02X})")
        seen[name] = seen.get(name, 0) + 1
        if seen[name] <= 2:
            extra = ""
            if payload and len(payload) <= 64:
                extra = f" payload={payload[:48].hex()}"
            elif payload:
                extra = f" payload={len(payload)} bytes"
            print(f"  <- {name}{extra}")

    print("\n--- Summary ---")
    if seen:
        for k, v in sorted(seen.items(), key=lambda x: -x[1]):
            print(f"  {k}: {v}x")
        print("\nRESULT: the dongle RESPONDS to the protocol. Implementation feasible.")
    else:
        print("  no message received")
        print("\nRESULT: no response - check the dongle power/state.")

    usb.util.release_interface(dev, intf.bInterfaceNumber)
    return 0


if __name__ == "__main__":
    sys.exit(main())
