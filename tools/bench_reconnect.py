#!/usr/bin/env python3
"""
Bench test for the two open questions about the dongle's reconnection behaviour.

Both questions came out of a road test where the app's automatic reconnection
made things worse: the dongle ended up enumerating and accepting a session while
never sending a single byte, and only a power cycle recovered it.

  TEST 1 (reset)  Does reopening the dongle too soon after a drop cause the mute
                  state? The app currently waits 8s, raised from 4s on a hunch.
                  This runs N cycles at each delay and counts how often the
                  reopened session stays silent.

  TEST 2 (idle)   Does the dongle send anything on its own when nothing is
                  happening? This decides whether a sliding-window watchdog is
                  safe. A capture during active projection measured 46.8 video
                  packets/s, but says nothing about an idle link.

Neither question can be answered from logs alone, which is why this exists.

Usage:
  sudo ./venv/bin/python tools/bench_reconnect.py reset --cycles 20
  sudo ./venv/bin/python tools/bench_reconnect.py reset --cycles 20 --delay 4
  sudo ./venv/bin/python tools/bench_reconnect.py idle --seconds 120

Results are printed as a table and appended to captures/bench-<test>.log.
"""
import argparse
import os
import struct
import sys
import threading
import time

import usb.core
import usb.util

VID, PID, MAGIC = 0x1314, 0x1521, 0x55AA55AA
BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(BASE, "captures")

# Message types seen coming from the dongle, for the idle histogram.
TYPE_NAME = {
    0x02: "Plugged", 0x03: "Phase", 0x04: "Unplugged", 0x06: "VideoData",
    0x07: "AudioData", 0x08: "Command", 0x0A: "BluetoothAddress",
    0x0C: "BluetoothPin", 0x0D: "BluetoothDeviceName", 0x0E: "WifiDeviceName",
    0x12: "BluetoothPairedList", 0x14: "ManufacturerInfo", 0x19: "BoxSettings",
    0x2A: "MediaData", 0xAA: "Heartbeat", 0xCC: "SoftwareVersion",
}

stop = threading.Event()


def log_line(test, msg):
    stamp = time.strftime("%H:%M:%S")
    line = "[%s] %s" % (stamp, msg)
    print("  " + line, flush=True)
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, "bench-%s.log" % test), "a") as f:
        f.write(line + "\n")


def hdr(t, n):
    return struct.pack("<IIII", MAGIC, n, t, (t ^ -1) & 0xFFFFFFFF)


class Link:
    """Minimal session: enough to open the dongle and read what it sends.

    Deliberately not the full init from session.py — this measures the dongle's
    own behaviour, and a smaller init means fewer variables in the experiment.
    """

    def __init__(self, dev, ep_in, ep_out):
        self.dev, self.ep_in, self.ep_out = dev, ep_in, ep_out
        self.lock = threading.Lock()

    def send(self, t, payload=b""):
        with self.lock:
            self.dev.write(self.ep_out, hdr(t, len(payload)), timeout=3000)
            if payload:
                self.dev.write(self.ep_out, payload, timeout=3000)

    def send_file(self, name, content):
        nb = (name + "\0").encode("ascii")
        self.send(0x99, struct.pack("<I", len(nb)) + nb
                  + struct.pack("<I", len(content)) + content)

    def send_int_file(self, name, v):
        self.send_file(name, struct.pack("<I", v))

    def init(self, w=800, h=480, fps=20, dpi=140):
        """Same sequence the app sends, so the dongle behaves as it does in the car."""
        self.send_int_file("/tmp/screen_dpi", dpi)
        self.send(0x01, struct.pack("<IIIIIII", w, h, fps, 5, 49152, 2, 2))
        self.send_int_file("/tmp/night_mode", 0)
        self.send_int_file("/tmp/hand_drive_mode", 0)
        self.send_int_file("/tmp/charge_mode", 1)
        self.send_file("/etc/box_name", b"HondaHRV\0")
        self.send_int_file("/etc/android_work_mode", 1)
        self.send(0x19, ('{"mediaDelay":300,"syncTime":%d,"androidAutoSizeW":%d,'
                         '"androidAutoSizeH":%d}'
                         % (int(time.time() * 1000), w, h)).encode("ascii"))
        for c in (1000, 25, 7, 23):
            self.send(0x08, struct.pack("<I", c))
            time.sleep(0.05)

    def heartbeat_loop(self):
        """The dongle ends the session without this — it must run during the tests."""
        while not stop.is_set():
            try:
                self.send(0xAA)
            except usb.core.USBError:
                return
            stop.wait(2)

    def read_message(self, timeout=1000):
        """@return (type, length) or (None, None) on timeout."""
        try:
            raw = self.dev.read(self.ep_in, 16, timeout=timeout)
        except usb.core.USBError:
            return None, None
        if len(raw) != 16:
            return None, None
        magic, length, mtype, _ = struct.unpack("<IIII", bytes(raw))
        if magic != MAGIC:
            return None, None
        rem = length
        while rem > 0:
            try:
                c = self.dev.read(self.ep_in, min(rem, 16384), timeout=2000)
            except usb.core.USBError:
                break
            rem -= len(c)
        return mtype, length


def find_endpoints(dev):
    cfg = dev.get_active_configuration()
    iface = cfg[(0, 0)]
    ep_in = ep_out = None
    for ep in iface:
        if usb.util.endpoint_direction(ep.bEndpointAddress) == usb.util.ENDPOINT_IN:
            ep_in = ep.bEndpointAddress
        else:
            ep_out = ep.bEndpointAddress
    return iface, ep_in, ep_out


def open_link(test, timeout_s=30):
    """Waits for the dongle to appear and opens it.

    @return (Link, elapsed_seconds) or (None, elapsed) if it never showed up.
    """
    t0 = time.time()
    while time.time() - t0 < timeout_s:
        if stop.is_set():
            return None, time.time() - t0
        dev = usb.core.find(idVendor=VID, idProduct=PID)
        if dev is not None:
            try:
                dev.get_active_configuration()
                iface, ep_in, ep_out = find_endpoints(dev)
                if ep_in is None or ep_out is None:
                    raise usb.core.USBError("endpoints not found")
                try:
                    usb.util.claim_interface(dev, iface)
                except usb.core.USBError:
                    pass   # already claimed is fine
                return Link(dev, ep_in, ep_out), time.time() - t0
            except usb.core.USBError as e:
                log_line(test, "found but could not open (%s) — retrying" % e)
        time.sleep(0.2)
    return None, time.time() - t0


def close_link(link):
    """Drops the link the way the app does when it releases the dongle."""
    try:
        usb.util.dispose_resources(link.dev)
    except Exception:
        pass


def test_reset(cycles, delay):
    """Measures how often a session reopened after `delay` seconds stays mute.

    A cycle is: open, init, confirm traffic, drop, wait `delay`, reopen, and check
    whether anything arrives within the listen window.
    """
    LISTEN_S = 12          # above the app's 10s mute timeout
    log_line("reset", "=" * 62)
    log_line("reset", "RESET TEST — %d cycles, %.1fs between drop and reopen"
             % (cycles, delay))
    log_line("reset", "=" * 62)

    results = []
    for i in range(1, cycles + 1):
        if stop.is_set():
            break
        link, took = open_link("reset")
        if link is None:
            log_line("reset", "cycle %d: dongle never appeared (%.1fs) — aborting"
                     % (i, took))
            results.append(("no-device", 0.0, took))
            continue

        hb = threading.Thread(target=link.heartbeat_loop, daemon=True)
        hb.start()
        try:
            link.init()
        except usb.core.USBError as e:
            log_line("reset", "cycle %d: init failed (%s)" % (i, e))
            close_link(link)
            results.append(("init-failed", 0.0, took))
            continue

        # Listen for the first byte: this is the measurement that matters.
        first = None
        packets = 0
        t0 = time.time()
        while time.time() - t0 < LISTEN_S:
            mtype, _ = link.read_message(timeout=500)
            if mtype is not None:
                packets += 1
                if first is None:
                    first = time.time() - t0
                if packets >= 5:      # clearly alive, no need to keep listening
                    break

        verdict = "MUTE" if first is None else "alive"
        log_line("reset", "cycle %2d: enumerated in %.2fs | first byte %s | %d packets | %s"
                 % (i, took,
                    "none in %ds" % LISTEN_S if first is None else "%.3fs" % first,
                    packets, verdict))
        results.append((verdict, first or 0.0, took))

        close_link(link)
        stop.wait(delay)

    alive = sum(1 for v, _, _ in results if v == "alive")
    mute = sum(1 for v, _, _ in results if v == "MUTE")
    other = len(results) - alive - mute
    enum = [t for _, _, t in results if t > 0]
    firsts = [f for v, f, _ in results if v == "alive"]

    log_line("reset", "-" * 62)
    log_line("reset", "delay=%.1fs  alive=%d  MUTE=%d  other=%d  (n=%d)"
             % (delay, alive, mute, other, len(results)))
    if enum:
        log_line("reset", "enumeration: min %.2fs  max %.2fs  mean %.2fs"
                 % (min(enum), max(enum), sum(enum) / len(enum)))
    if firsts:
        log_line("reset", "first byte:  min %.3fs  max %.3fs  mean %.3fs"
                 % (min(firsts), max(firsts), sum(firsts) / len(firsts)))
    log_line("reset", "")
    log_line("reset", "Reading: run this at --delay 4 and --delay 8. If MUTE only")
    log_line("reset", "shows up at 4s, the 8s wait in the app is justified. If both")
    log_line("reset", "are clean, the delay is not what prevents the mute state and")
    log_line("reset", "the app can go back to 4s.")


def test_idle(seconds):
    """Measures whether the dongle sends anything unprompted, and the longest gap.

    The gap is what decides whether a sliding-window watchdog is safe: the window
    has to be comfortably larger than the longest silence seen on a healthy link.
    """
    log_line("idle", "=" * 62)
    log_line("idle", "IDLE TEST — listening for %ds" % seconds)
    log_line("idle", "Leave the phone unconnected for the first run. Then repeat")
    log_line("idle", "with the phone connected and the screen untouched.")
    log_line("idle", "=" * 62)

    link, took = open_link("idle")
    if link is None:
        log_line("idle", "dongle never appeared (%.1fs)" % took)
        return
    log_line("idle", "opened in %.2fs" % took)

    hb = threading.Thread(target=link.heartbeat_loop, daemon=True)
    hb.start()
    try:
        link.init()
    except usb.core.USBError as e:
        log_line("idle", "init failed: %s" % e)
        close_link(link)
        return

    counts = {}
    gaps = []
    total = 0
    t0 = time.time()
    last = t0
    next_report = 15.0

    while time.time() - t0 < seconds and not stop.is_set():
        mtype, length = link.read_message(timeout=500)
        now = time.time()
        if mtype is not None:
            total += 1
            name = TYPE_NAME.get(mtype, "0x%02X" % mtype)
            counts[name] = counts.get(name, 0) + 1
            gaps.append(now - last)
            last = now
        elapsed = now - t0
        if elapsed >= next_report:
            since = now - last
            log_line("idle", "t=%3ds  %d packets  longest gap so far %.2fs  "
                             "current silence %.2fs"
                     % (int(elapsed), total,
                        max(gaps) if gaps else 0.0, since))
            next_report += 15.0

    close_link(link)
    dur = time.time() - t0

    log_line("idle", "-" * 62)
    log_line("idle", "duration %.1fs  packets %d  rate %.1f/s"
             % (dur, total, total / dur if dur else 0))
    if gaps:
        log_line("idle", "gaps: min %.3fs  max %.3fs  mean %.3fs"
                 % (min(gaps), max(gaps), sum(gaps) / len(gaps)))
        log_line("idle", "longest silence on a healthy link: %.2fs" % max(gaps))
    else:
        log_line("idle", "NOTHING received in %ds — the dongle is silent when idle"
                 % int(dur))
    for name, n in sorted(counts.items(), key=lambda kv: -kv[1]):
        log_line("idle", "  %-22s %6d" % (name, n))
    log_line("idle", "")
    log_line("idle", "Reading: a sliding-window watchdog needs a window well above")
    log_line("idle", "the longest silence measured here. If nothing arrives at all,")
    log_line("idle", "the current 'nothing ever received' check is the safe design.")


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="test", required=True)

    r = sub.add_parser("reset", help="drop/reopen cycles at a given delay")
    r.add_argument("--cycles", type=int, default=20)
    r.add_argument("--delay", type=float, default=8.0,
                   help="seconds between drop and reopen (default: 8, the app's value)")

    i = sub.add_parser("idle", help="listen on an idle link")
    i.add_argument("--seconds", type=int, default=120)

    args = ap.parse_args()

    if usb.core.find(idVendor=VID, idProduct=PID) is None:
        print("  dongle %04x:%04x not found on the bus." % (VID, PID))
        print("  Plug it into a USB port and run again (needs root for USB access).")
        return 1

    try:
        if args.test == "reset":
            test_reset(args.cycles, args.delay)
        else:
            test_idle(args.seconds)
    except KeyboardInterrupt:
        stop.set()
        print("\n  interrupted")
    return 0


if __name__ == "__main__":
    sys.exit(main())
