#!/usr/bin/env python3
"""
PERSISTENT session with the Carlinkit dongle.

Reason: the dongle ends the session and drops the link with the phone when the host
heartbeat stops. Short capture scripts (which finish in 40s) make the phone
"pair but not stay connected". This daemon keeps the session alive indefinitely.

Continuously records video (.h264) and audio (one .wav per format), and keeps a
human-readable status file so it can be followed from the outside.

Usage:
  sudo ./venv/bin/python tools/session.py            # foreground (Ctrl+C to stop)
  sudo nohup ./venv/bin/python tools/session.py &    # background

Status:  captures/session-status.txt
Log:     captures/session.log
"""
import argparse
import os
import signal
import struct
import sys
import threading
import time
import wave

import usb.core
import usb.util

VID, PID, MAGIC = 0x1314, 0x1521, 0x55AA55AA
BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(BASE, "captures")
STATUS = os.path.join(OUT, "session-status.txt")
LOG = os.path.join(OUT, "session.log")

FORMATS = {1: (44100, 2, 16), 2: (44100, 2, 16), 3: (8000, 1, 16), 4: (48000, 2, 16),
           5: (16000, 1, 16), 6: (24000, 1, 16), 7: (16000, 2, 16)}
AUDIO_CMD = {1: "OutputStart", 2: "OutputStop", 3: "InputConfig", 4: "PhonecallStart",
             5: "PhonecallStop", 6: "NaviStart", 7: "NaviStop", 8: "SiriStart",
             9: "SiriStop", 10: "MediaStart", 11: "MediaStop", 12: "AlertStart",
             13: "AlertStop"}
CMD = {1002: "wifiConnect", 1003: "scanningDevice", 1004: "deviceFound",
       1005: "deviceNotFound", 1006: "connectFailed", 1007: "btConnected",
       1008: "btDisconnected", 1009: "wifiConnected", 1010: "wifiDisconnected",
       1011: "btPairStart", 1012: "wifiPair"}
PHONE = {1: "AndroidMirror", 3: "CarPlay", 4: "iPhoneMirror", 5: "AndroidAuto", 6: "HiCar"}

stop = threading.Event()
state = {"video": 0, "audio": 0, "phone": None, "last_cmd": "", "started": time.time(),
         "video_bytes": 0, "audio_streams": {}, "last_event": ""}

# Per-window delivery metrics, mirroring the Android heartbeat so a bench run and a run in the
# car produce the same numbers and can be compared directly.
#
# The window is 5 s because that is the app's HEARTBEAT_MS, and the reference figures come from
# that cadence: 117 PCM messages per window with a median gap near 70 ms is healthy, while the
# degraded state measured on 27/Aug ran 23 to 42 per window with gaps up to 4.6 s.
#
# last_pcm is deliberately NOT reset between windows: a silence that straddles the boundary is
# exactly the event of interest, and resetting it would hide the worst gaps.
WINDOW_S = 5.0
win = {"start": time.time(), "pcm": 0, "bytes": 0, "gap_max": 0.0, "video": 0, "last_pcm": None}


def window_tick(force=False):
    """Emits one line per WINDOW_S with delivery rate and the largest silence seen."""
    now = time.time()
    if not force and now - win["start"] < WINDOW_S:
        return
    span = now - win["start"]
    if win["pcm"] or win["video"]:
        log("window | in=%d inBytes=%d maxGap=%dms video=%d over %.1fs"
            % (win["pcm"], win["bytes"], int(win["gap_max"] * 1000), win["video"], span))
    win["start"] = now
    win["pcm"] = 0
    win["bytes"] = 0
    win["gap_max"] = 0.0
    win["video"] = 0


def log(msg):
    line = "[%s] %s" % (time.strftime("%H:%M:%S"), msg)
    print(line, flush=True)
    with open(LOG, "a") as f:
        f.write(line + "\n")


def write_status():
    up = int(time.time() - state["started"])
    with open(STATUS, "w") as f:
        f.write("session active for: %dm%02ds\n" % (up // 60, up % 60))
        f.write("phone: %s\n" % (state["phone"] or "not connected"))
        f.write("last event: %s\n" % state["last_event"])
        f.write("video frames: %d (%.1f MB)\n"
                % (state["video"], state["video_bytes"] / 1048576.0))
        f.write("audio packets: %d\n" % state["audio"])
        for k, n in sorted(state["audio_streams"].items()):
            f.write("  stream %s: %d msgs\n" % (k, n))


def hdr(t, n):
    return struct.pack("<IIII", MAGIC, n, t, (t ^ -1) & 0xFFFFFFFF)


class Session:
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
        self.send_int_file("/tmp/screen_dpi", dpi)
        self.send(0x01, struct.pack("<IIIIIII", w, h, fps, 5, 49152, 2, 2))
        self.send_int_file("/tmp/night_mode", 0)
        self.send_int_file("/tmp/hand_drive_mode", 0)
        self.send_int_file("/tmp/charge_mode", 1)
        self.send_file("/etc/box_name", b"HondaHRV\0")
        self.send_int_file("/etc/android_work_mode", 1)   # essential for Android
        # mediaDelay must match what the app sends, or a bench run is not comparable to a
        # measurement taken in the car. The app sent 300 until 26/Aug, which is the bottom of
        # the range and the value most prone to stuttering; the box documents 1000 as its own
        # default. See docs/FINDINGS.md, "Wireless degradation".
        self.send(0x19, ('{"mediaDelay":1000,"syncTime":%d,"androidAutoSizeW":%d,'
                         '"androidAutoSizeH":%d}'
                         % (int(time.time() * 1000), w, h)).encode("ascii"))
        for c in (1000, 25, 7, 23):
            self.send(0x08, struct.pack("<I", c))
            time.sleep(0.05)
        log("init sent (%dx%d @%dfps, Android mode)" % (w, h, fps))

    def heartbeat_loop(self):
        """Without this the dongle ends the session and drops the phone."""
        while not stop.is_set():
            try:
                self.send(0xAA)
            except usb.core.USBError as e:
                log("heartbeat failed: %s" % e)
                stop.set()
                return
            stop.wait(2)

    def reconnect_loop(self):
        """Resends wifiConnect while there is no phone connected."""
        time.sleep(1.0)
        while not stop.is_set():
            if state["phone"] is None:
                try:
                    self.send(0x08, struct.pack("<I", 1002))
                except usb.core.USBError:
                    return
            stop.wait(10)

    def read_message(self):
        raw = self.dev.read(self.ep_in, 16, timeout=1000)
        if len(raw) != 16:
            return None, None
        magic, length, mtype, _ = struct.unpack("<IIII", bytes(raw))
        if magic != MAGIC:
            return None, None
        payload, rem = b"", length
        while rem > 0:
            c = self.dev.read(self.ep_in, min(rem, 16384), timeout=2000)
            payload += bytes(c)
            rem -= len(c)
        return mtype, payload


def open_device(retries=30):
    """The dongle has a watchdog: with no host talking to it, it reboots every ~9s.
    Here we wait for it to reappear on the bus and reopen it."""
    for i in range(retries):
        if stop.is_set():
            return None
        dev = usb.core.find(idVendor=VID, idProduct=PID)
        if dev is not None:
            try:
                dev.get_active_configuration()
                return dev
            except usb.core.USBError:
                pass
        time.sleep(1)
    return None


def abrir_e_iniciar(args, tentativas=6):
    """Opens the dongle and completes the init, retrying if its watchdog resets mid-sequence.

    Why this retry exists, measured on 01/09/2026: while no host is talking to it the dongle
    reboots roughly every 11 s. A run that starts just before one of those resets dies partway
    through the init with a USB error, which looked like a broken dongle and cost a control
    experiment. The app on the head unit never sees this because it is always attached.
    """
    for n in range(1, tentativas + 1):
        dev = open_device()
        if dev is None:
            log("ERROR: dongle not found")
            return None, None
        intf = dev.get_active_configuration()[(0, 0)]
        ep_out = usb.util.find_descriptor(intf, custom_match=lambda e:
            usb.util.endpoint_direction(e.bEndpointAddress) == usb.util.ENDPOINT_OUT).bEndpointAddress
        ep_in = usb.util.find_descriptor(intf, custom_match=lambda e:
            usb.util.endpoint_direction(e.bEndpointAddress) == usb.util.ENDPOINT_IN).bEndpointAddress
        try:
            usb.util.claim_interface(dev, intf.bInterfaceNumber)
        except usb.core.USBError:
            pass
        s = Session(dev, ep_in, ep_out)
        try:
            s.init(w=args.width, h=args.height, fps=args.fps)
            return dev, s
        except usb.core.USBError as e:
            log("init failed on attempt %d/%d (%s); the dongle watchdog probably reset it, "
                "retrying" % (n, tentativas, e))
            time.sleep(3)
    log("ERROR: could not complete the init after %d attempts" % tentativas)
    return None, None


def main():
    # CLI so a configuration sweep is reproducible and each log says which settings produced
    # it. Without this the fps was hardcoded and four runs of a sweep would be
    # indistinguishable after the fact, which is how a comparison quietly becomes worthless.
    ap = argparse.ArgumentParser(description="Drives the dongle from a PC and measures delivery.")
    ap.add_argument("--fps", type=int, default=20, help="fps announced in the OPEN message")
    ap.add_argument("--width", type=int, default=800)
    ap.add_argument("--height", type=int, default=480)
    ap.add_argument("--seconds", type=int, default=0, help="stop after N seconds, 0 = until Ctrl-C")
    ap.add_argument("--tag", default="", help="label recorded in the log, e.g. the dongle bitRate")
    args = ap.parse_args()

    os.makedirs(OUT, exist_ok=True)
    open(LOG, "w").close()

    log("run config | fps=%d size=%dx%d seconds=%s tag=%s"
        % (args.fps, args.width, args.height, args.seconds or "unbounded", args.tag or "-"))
    dev, s = abrir_e_iniciar(args)
    if s is None:
        return 1
    if args.seconds:
        threading.Timer(args.seconds, stop.set).start()
    threading.Thread(target=s.heartbeat_loop, daemon=True).start()
    threading.Thread(target=s.reconnect_loop, daemon=True).start()

    stamp = time.strftime("%Y%m%d-%H%M%S")
    vpath = os.path.join(OUT, "session-%s.h264" % stamp)
    vfh = open(vpath, "wb")
    apcm = {}

    def shutdown(*_):
        stop.set()
    signal.signal(signal.SIGINT, shutdown)
    signal.signal(signal.SIGTERM, shutdown)

    log("session started - keeping the dongle alive. Connect the phone's Bluetooth.")
    last_status = 0

    while not stop.is_set():
        try:
            mtype, payload = s.read_message()
        except usb.core.USBTimeoutError:
            if time.time() - last_status > 3:
                write_status(); last_status = time.time()
            continue
        except usb.core.USBError as e:
            log("USBError on read (%s) - dongle reset, reopening..." % e)
            state["phone"] = None
            state["last_event"] = "dongle reset"
            dev2 = open_device()
            if dev2 is None:
                log("could not reopen the dongle")
                break
            try:
                intf2 = dev2.get_active_configuration()[(0, 0)]
                usb.util.claim_interface(dev2, intf2.bInterfaceNumber)
            except usb.core.USBError:
                pass
            s.dev = dev2
            s.init()
            log("session re-established")
            continue
        if mtype is None:
            continue

        if mtype == 0x06:                                  # VideoData
            state["video"] += 1
            state["video_bytes"] += len(payload)
            win["video"] += 1
            vfh.write(payload[20:])                        # 20 bytes of metadata
            if state["video"] == 1:
                log("FIRST VIDEO FRAME")
        elif mtype == 0x07 and len(payload) >= 12:         # AudioData
            state["audio"] += 1
            d, vol, a = struct.unpack("<IfI", payload[:12])
            body = payload[12:]
            key = "d%d-a%d" % (d, a)
            state["audio_streams"][key] = state["audio_streams"].get(key, 0) + 1
            if len(body) == 1:
                c = struct.unpack("<b", body)[0]
                log("AudioCommand %s (d=%d a=%d vol=%.2f)"
                    % (AUDIO_CMD.get(c, c), d, a, vol))
                state["last_event"] = "audio:" + AUDIO_CMD.get(c, str(c))
            elif len(body) > 4:
                # Real PCM, not an AudioCommand. Counted here so the window figures mean the
                # same thing as the app's "in": delivery of playable audio, nothing else.
                now = time.time()
                if win["last_pcm"] is not None:
                    gap = now - win["last_pcm"]
                    if gap > win["gap_max"]:
                        win["gap_max"] = gap
                win["last_pcm"] = now
                win["pcm"] += 1
                win["bytes"] += len(body)
                if key not in apcm:
                    fmt = FORMATS.get(d)
                    log("PCM starts %s %s" % (key, ("%dHz %dch" % fmt[:2]) if fmt else "?"))
                    apcm[key] = bytearray()
                apcm[key].extend(body)
        elif mtype == 0x08 and len(payload) >= 4:          # Command
            v = struct.unpack("<I", payload[:4])[0]
            if v in CMD:
                log("Command %d %s" % (v, CMD[v]))
                state["last_cmd"] = CMD[v]
                state["last_event"] = CMD[v]
                if v in (1008, 1010):
                    state["phone"] = None
        elif mtype == 0x02:                                # Plugged
            v = struct.unpack("<I", payload[:4])[0] if len(payload) >= 4 else -1
            state["phone"] = PHONE.get(v, str(v))
            state["last_event"] = "PLUGGED " + state["phone"]
            log("PLUGGED - phone connected: %s" % state["phone"])
        elif mtype == 0x04:                                # Unplugged
            state["phone"] = None
            state["last_event"] = "UNPLUGGED"
            log("UNPLUGGED")

        window_tick()
        if time.time() - last_status > 3:
            write_status(); last_status = time.time()

    # shutdown
    window_tick(force=True)
    vfh.close()
    log("shutting down: %d video frames, %d audio msgs" % (state["video"], state["audio"]))
    if state["video"] == 0:
        os.remove(vpath)
    else:
        log("video: %s (%.1f MB)" % (vpath, os.path.getsize(vpath) / 1048576.0))
    for key, pcm in apcm.items():
        d = int(key.split("-")[0][1:])
        fmt = FORMATS.get(d)
        if not fmt or not pcm:
            continue
        freq, ch, bits = fmt
        p = os.path.join(OUT, "session-%s-%s.wav" % (stamp, key))
        with wave.open(p, "wb") as w:
            w.setnchannels(ch); w.setsampwidth(bits // 8); w.setframerate(freq)
            w.writeframes(bytes(pcm))
        log("audio: %s (%.1fs)" % (p, len(pcm) / float(freq * ch * bits // 8)))
    write_status()
    try:
        usb.util.release_interface(dev, intf.bInterfaceNumber)
    except Exception:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
