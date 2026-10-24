"""The arm and the cameras, as the engine sees them.

The arm is never driven directly: the PiPER driver (nervus-piper.service) owns the CAN bus, and the
engine talks to it over loopback like every other arm client, as client "act". The driver's
one-controller-at-a-time rule keeps the engine, Hand Teleop and the Robot Arm app from fighting.

Cameras are opened by their stable /dev/v4l/by-id name, never by index: the index order shifts
whenever a USB device comes or goes, and silently recording or running on the wrong camera is a
miserable bug (it happened in the Piper-ACT project when the laptop's own webcam sat between the two).
"""
from __future__ import annotations

import glob
import json
import math
import os
import subprocess
import threading
import time
import urllib.error
import urllib.request
from collections import deque

import numpy as np

JOINTS = ["joint1", "joint2", "joint3", "joint4", "joint5", "joint6", "gripper"]
TEACHING = 0x02


def mono() -> float:
    return time.monotonic()


def usb_controller(dev: str) -> str | None:
    """The PCI USB controller a /dev/videoN hangs off, as lspci names it ("... Thunderbolt 3 USB Controller")."""
    import re
    try:
        node = os.path.basename(os.path.realpath(dev))
        path = os.path.realpath(f"/sys/class/video4linux/{node}/device")
        pci = [p for p in path.split("/") if re.fullmatch(r"[0-9a-f]{4}:[0-9a-f]{2}:[0-9a-f]{2}\.[0-7]", p)]
        if not pci:
            return None
        out = subprocess.run(["lspci", "-s", pci[-1]], capture_output=True, text=True, timeout=3).stdout.strip()
        return out.split(": ", 1)[-1] or None
    except Exception:
        return None


class ArmError(RuntimeError):
    pass


class Arm:
    """Client of the PiPER driver API. State is polled on a thread so readers never wait on HTTP."""

    CLIENT = "act"

    def __init__(self, base: str = "http://127.0.0.1:8770", poll_hz: float = 50.0):
        self.base = base.rstrip("/")
        self.poll_period = 1.0 / poll_hz
        self.latest: dict | None = None
        self.latest_at = 0.0
        self.error: str | None = None
        self._stop = threading.Event()
        self._rx = deque(maxlen=50)
        threading.Thread(target=self._poll, daemon=True, name="arm-poll").start()

    # ---- transport ----
    def _get(self, path: str, timeout: float = 1.0) -> dict:
        with urllib.request.urlopen(self.base + path, timeout=timeout) as r:
            return json.loads(r.read())

    def _post(self, path: str, body: dict | None = None, timeout: float = 3.0) -> dict:
        data = dict(body or {})
        data["client"] = self.CLIENT
        req = urllib.request.Request(self.base + path, data=json.dumps(data).encode(),
                                     headers={"Content-Type": "application/json"}, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return json.loads(r.read() or b"{}")
        except urllib.error.HTTPError as e:
            try:
                msg = json.loads(e.read()).get("error", str(e))
            except Exception:
                msg = str(e)
            if e.code == 409 and msg == "busy":
                raise ArmError("another app is controlling the arm (Robot Arm or Hand Teleop): close it or press Stop there")
            raise ArmError(msg)
        except urllib.error.URLError as e:
            raise ArmError(f"arm driver unreachable: {e.reason} (is NervusOS running?)")

    def _poll(self):
        while not self._stop.is_set():
            t0 = mono()
            try:
                s = self._get("/api/state?lite=1", timeout=0.5)
                self.latest, self.latest_at, self.error = s, mono(), None
                self._rx.append(self.latest_at)
            except Exception as e:
                self.error = f"arm driver: {e}"
            dt = self.poll_period - (mono() - t0)
            if dt > 0:
                time.sleep(dt)

    # ---- reading ----
    def read(self) -> tuple[dict | None, float]:
        """(state, age_s). state maps JOINTS to degrees and the gripper to millimetres. The age is how old
        the arm's own feedback is, plus how long ago we polled it; feedback the driver reports stale is
        infinitely old, never a pose (cached zeros look like a plausible pose)."""
        s = self.latest
        if s is None:
            return None, math.inf
        if not s.get("feedback_fresh"):
            return None, math.inf
        age = (mono() - self.latest_at) + (s.get("feedback_age_ms") or 0) / 1000.0
        g = s.get("gripper_actual_mm")
        if g is None:
            return None, math.inf
        state = {f"joint{i + 1}": float(s["current"][i]) for i in range(6)}
        state["gripper"] = float(g)
        return state, age

    def info(self) -> dict:
        s = self.latest or {}
        st = s.get("status") or {}
        return {
            "reachable": self.latest is not None and mono() - self.latest_at < 1.0,
            "error": self.error,
            "fresh": bool(s.get("feedback_fresh")),
            "feedback_age_ms": s.get("feedback_age_ms"),
            "can_fps": s.get("can_fps"),
            "enabled": bool(s.get("enabled")),
            "motors": s.get("motors_enabled"),
            "estopped": bool(s.get("estopped")),
            "teaching": bool(s.get("teaching")),
            "ctrl_mode": st.get("ctrl_mode"),
            "arm_status": st.get("arm_status"),
            "soft_fault": bool(s.get("soft_fault")),
            "owner": s.get("owner"),
            "pose": s.get("pose_current"),
            "joints": s.get("current"),
            "gripper_mm": s.get("gripper_actual_mm"),
            "gripper_range": s.get("gripper_range"),
            "limits": s.get("limits"),
            "firmware": s.get("firmware"),
        }

    def teaching(self) -> bool:
        s = self.latest or {}
        return bool(s.get("teaching")) and mono() - self.latest_at < 1.0

    def hard_fault(self) -> str | None:
        s = self.latest or {}
        st = s.get("status") or {}
        if not s.get("feedback_fresh"):
            return None  # staleness is handled by the age checks
        if st.get("arm_status_code") not in (None, 0x00) and not s.get("soft_fault"):
            return f"arm status {st.get('arm_status')}"
        return None

    def end_pose(self) -> list | None:
        s = self.latest or {}
        return s.get("pose_current") if s.get("feedback_fresh") else None

    # ---- commanding ----
    def enable(self):
        self._post("/api/enable", {"on": True}, timeout=6.0)

    def stop(self):
        self._post("/api/stop")

    def release(self):
        try:
            self._post("/api/release")
        except ArmError:
            pass

    def estop(self):
        self._post("/api/estop")

    def recover(self):
        self._post("/api/recover", timeout=8.0)

    def speed(self, pct: int):
        self._post("/api/speed", {"speed": int(pct)})

    def stream(self, target: dict, gripper_effort: int):
        """One joint target (degrees) plus the gripper (mm), sent now. The driver re-sends the gripper only
        when it changes, which stopped a stalled gripper buzzing against the object it held."""
        self._post("/api/stream", {
            "joints": [float(target[j]) for j in JOINTS[:6]],
            "gripper_mm": float(target["gripper"]),
            "gripper_effort": int(gripper_effort),
        }, timeout=1.0)

    def close(self):
        self._stop.set()


# ---------------------------------------------------------------------------------------------------
class Camera:
    """Latest-frame capture on its own thread, so one slow camera can't stall the control or record loop
    (in the old recorder a 3 fps camera dragged the whole loop down to 1 fps)."""

    def __init__(self, role: str, by_id: str, width: int = 640, height: int = 480, fps: int = 15):
        self.role, self.by_id = role, by_id
        self.w, self.h, self.fps_req = width, height, fps
        self.frame: np.ndarray | None = None
        self.seq = 0
        self.stamp = 0.0
        self.times = deque(maxlen=30)
        self.error: str | None = None
        self.device: str | None = None
        self.controller: str | None = None
        self._stop = threading.Event()
        self._wanted = threading.Event()
        self._wanted.set()
        self._jpeg: tuple[int, bytes] | None = None
        self._lock = threading.Lock()
        threading.Thread(target=self._run, daemon=True, name=f"cam-{role}").start()

    def want(self, on: bool):
        """Open the device while someone needs frames; release it (and its USB bandwidth) when nobody does."""
        if on:
            self._wanted.set()
        else:
            self._wanted.clear()

    @property
    def active(self) -> bool:
        return self._wanted.is_set()

    def _resolve(self) -> str:
        hits = sorted(glob.glob(f"/dev/v4l/by-id/*{self.by_id}*-video-index0"))
        if not hits:
            raise RuntimeError(f"{self.role} camera ({self.by_id}) is not plugged in")
        return os.path.realpath(hits[0])

    def _stall(self, dev: str, seconds: float) -> str:
        msg = f"{self.role} camera: no frames for {seconds:.0f} s"
        ctl = self.controller or ""
        if "Thunderbolt" in ctl:
            # Seen on the Dell: the JHL6540's USB keeps resetting a C270 plugged into a USB-C port.
            msg += (" — it is on the Thunderbolt (USB-C) controller, which keeps resetting it: "
                    "plug it into one of the laptop's USB-A ports")
        else:
            msg += " — unplug and replug it, or try another USB port"
        return msg

    def _apply_controls(self, dev: str):
        # Fixed frame rate and mains anti-flicker (also set by udev on plug; repeated here in case the
        # camera reset them). Exposure must not stretch the frame period, or 15 fps quietly becomes 12.5.
        for ctrl in ("exposure_dynamic_framerate=0", "power_line_frequency=1"):
            subprocess.run(["v4l2-ctl", "-d", dev, "-c", ctrl], capture_output=True, timeout=3)

    def _run(self):
        import cv2
        while not self._stop.is_set():
            if not self._wanted.is_set():
                with self._lock:
                    self.frame, self.stamp = None, 0.0
                self.times.clear()
                self._wanted.wait(1.0)
                continue
            cap = None
            try:
                dev = self._resolve()
                if dev != self.device:
                    self.controller = usb_controller(dev)
                self.device = dev
                self._apply_controls(dev)
                cap = cv2.VideoCapture(dev, cv2.CAP_V4L2)
                cap.set(cv2.CAP_PROP_FOURCC, cv2.VideoWriter_fourcc(*"MJPG"))
                cap.set(cv2.CAP_PROP_FRAME_WIDTH, self.w)
                cap.set(cv2.CAP_PROP_FRAME_HEIGHT, self.h)
                cap.set(cv2.CAP_PROP_FPS, self.fps_req)
                # Keep the driver's default buffer count. With one buffer the camera can't deliver while we
                # hold it, and 15 fps measured 10.8; this thread already keeps only the newest frame.
                try:
                    cap.set(getattr(cv2, "CAP_PROP_READ_TIMEOUT_MSEC", 69), 2000)  # V4L2 waits 10 s by default
                except Exception:
                    pass
                if not cap.isOpened():
                    raise RuntimeError(f"{self.role} camera {dev} did not open")
                misses, last_ok = 0, mono()
                while not self._stop.is_set() and self._wanted.is_set():
                    ok, bgr = cap.read()
                    if not ok:
                        misses += 1
                        stalled = mono() - last_ok
                        if stalled > 3.0:
                            self.error = self._stall(dev, stalled)  # say so now, keep trying
                        if misses > 30 or stalled > 20.0:
                            raise RuntimeError(self._stall(dev, stalled))  # reopen the device
                        time.sleep(0.02)
                        continue
                    misses, last_ok, self.error = 0, mono(), None
                    if bgr.shape[1] != self.w or bgr.shape[0] != self.h:
                        bgr = cv2.resize(bgr, (self.w, self.h))
                    rgb = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)  # training frames are RGB
                    t = mono()
                    with self._lock:
                        self.frame, self.stamp = rgb, t
                        self.seq += 1
                    self.times.append(t)
            except Exception as e:
                self.error = str(e)
                self.frame = None
            finally:
                if cap is not None:
                    cap.release()
            if self._wanted.is_set():
                self._stop.wait(2.0)  # it failed: try again shortly

    def latest(self) -> tuple[np.ndarray | None, float]:
        with self._lock:
            return self.frame, (mono() - self.stamp) if self.stamp else math.inf

    def fps(self) -> float:
        t = list(self.times)
        if len(t) < 2 or mono() - t[-1] > 1.0:
            return 0.0
        return (len(t) - 1) / (t[-1] - t[0]) if t[-1] > t[0] else 0.0

    def jpeg(self, quality: int = 70) -> bytes | None:
        """The latest frame as JPEG for the app's preview, encoded at most once per frame."""
        import cv2
        with self._lock:
            frame, seq = self.frame, self.seq
        if frame is None:
            return None
        cached = self._jpeg
        if cached and cached[0] == seq:
            return cached[1]
        ok, buf = cv2.imencode(".jpg", cv2.cvtColor(frame, cv2.COLOR_RGB2BGR), [int(cv2.IMWRITE_JPEG_QUALITY), quality])
        if not ok:
            return None
        self._jpeg = (seq, buf.tobytes())
        return self._jpeg[1]

    def info(self) -> dict:
        _, age = self.latest()
        return {"role": self.role, "device": self.device, "match": self.by_id, "fps": round(self.fps(), 1),
                "age_ms": None if math.isinf(age) else round(age * 1000), "error": self.error,
                "size": [self.w, self.h], "active": self.active, "controller": self.controller}

    def close(self):
        self._stop.set()
