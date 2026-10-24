"""A simulated arm and cameras, for running the ACT engine (and ACT Studio) with no hardware.

    python -m nervus_act --sim

SimDriver serves the part of the PiPER driver's HTTP API (hardware/piper/piper_webctl.py) that the engine
uses, with the same field names, the same one-controller-at-a-time rule and the same refusals (motors off,
stale feedback, someone else holding the arm). Its arm moves toward each command at a speed set by the speed
percentage, and drag-teach can be switched on, after which the joints go wherever the "operator" drags them:

    POST /sim/teach {"on": true}                          as if the arm's teach button had been pressed
    POST /sim/drag  {"joints": [6 x deg], "gripper_mm": g} where the operator's hands move it, while teaching

SimCamera renders a small tabletop scene from the arm's pose: a red target, a blue cube, and the gripper
(a circle that grows as the fingers open). The wrist camera sees the scene from the gripper, the scene camera
from the side, so a policy trained on simulated demonstrations has something real to learn from. None of this
pretends to be physics; it exists so the whole pipeline (record, train, run, correct, post-train, promote)
can be tested end to end.
"""
from __future__ import annotations

import json
import math
import threading
import time
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

import numpy as np

JOINT_LIMITS = {1: (-154.0, 154.0), 2: (0.0, 195.0), 3: (-175.0, 0.0),
                4: (-106.0, 106.0), 5: (-75.0, 75.0), 6: (-100.0, 100.0)}
GRIPPER_MM = (0.0, 95.0)
READY_POSE = [0.0, 45.0, -60.0, 0.0, 45.0, 0.0]
DEADMAN = {"servo": 0.4, "stream": 0.5}
HEARTBEAT_TIMEOUT = 1.5


class SimArm:
    def __init__(self):
        self.lock = threading.Lock()
        self.joints = list(READY_POSE)
        self.target = list(READY_POSE)
        self.gripper = 10.0
        self.gripper_target = 10.0
        self.enabled = True
        self.estopped = False
        self.teaching = False
        self.drag = None
        self.speed = 40
        self.owner = None  # {"client", "kind", "last"}
        self.updated = time.time()
        self._stop = threading.Event()
        threading.Thread(target=self._run, daemon=True, name="sim-arm").start()

    def _run(self):
        dt = 0.005
        while not self._stop.is_set():
            time.sleep(dt)
            with self.lock:
                if self.teaching and self.drag is not None:
                    goal, g_goal, rate = self.drag[0], self.drag[1], 120.0
                elif self.enabled and not self.estopped and not self.teaching:
                    goal, g_goal, rate = self.target, self.gripper_target, 180.0 * self.speed / 100.0
                else:
                    goal, g_goal, rate = None, None, 0.0
                if goal is not None:
                    step = rate * dt
                    self.joints = [j + max(-step, min(step, g - j)) for j, g in zip(self.joints, goal)]
                    self.gripper += max(-150 * dt, min(150 * dt, g_goal - self.gripper))
                self.updated = time.time()

    def owner_fresh(self, now):
        o = self.owner
        if o and now - o["last"] < DEADMAN.get(o["kind"], HEARTBEAT_TIMEOUT):
            return o
        return None

    def claim(self, client, kind):
        now = time.time()
        o = self.owner_fresh(now)
        if o and o["client"] != client:
            raise PermissionError(o["client"])
        self.owner = {"client": client, "kind": kind, "last": now}

    def end_pose(self):
        """A rough planar forward kinematics, good enough to plot: mm and degrees."""
        j1, j2, j3, j4, j5, j6 = (math.radians(v) for v in self.joints)
        r = 250 * math.sin(j2) + 220 * math.sin(j2 + j3 + math.pi / 2)
        z = 120 + 250 * math.cos(j2) + 220 * math.cos(j2 + j3 + math.pi / 2)
        return [round(r * math.cos(j1), 1), round(r * math.sin(j1), 1), round(z, 1),
                round(math.degrees(j4), 1), round(math.degrees(j5) + 90, 1), round(math.degrees(j6), 1)]

    def state(self) -> dict:
        now = time.time()
        with self.lock:
            age = now - self.updated
            o = self.owner_fresh(now)
            ctrl = 0x02 if self.teaching else (0x01 if self.enabled else 0x00)
            return {
                "current": [round(v, 3) for v in self.joints], "targets": list(self.target), "mode": "stream",
                "pose_current": self.end_pose(), "arm_status": "normal", "gripper_mm": self.gripper_target,
                "enabled": self.enabled, "estopped": self.estopped, "speed": self.speed,
                "limits": {str(k): list(v) for k, v in JOINT_LIMITS.items()}, "gripper_range": list(GRIPPER_MM),
                "speed_range": [1, 100], "time": now, "feedback_fresh": age < 0.3,
                "feedback_age_ms": round(age * 1000, 1), "can_fps": 2300.0,
                "status": {"ctrl_mode": "teach" if self.teaching else "can", "ctrl_mode_code": ctrl,
                           "arm_status": "normal", "arm_status_code": 0},
                "teaching": self.teaching, "soft_fault": False, "gripper_actual_mm": round(self.gripper, 2),
                "gripper_effort_nm": 0.0, "motors_enabled": [self.enabled] * 6,
                "owner": ({"client": o["client"], "kind": o["kind"], "age_ms": round((now - o["last"]) * 1000)}
                          if o else None),
                "ready_pose": READY_POSE, "firmware": "SIM", "sim": True,
            }

    def close(self):
        self._stop.set()


class SimDriver:
    """The HTTP face of SimArm, on loopback."""

    def __init__(self, port: int = 0):
        self.arm = SimArm()
        arm = self.arm

        class H(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, *a):
                pass

            def _json(self, code, obj):
                raw = json.dumps(obj).encode()
                self.send_response(code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)

            def do_GET(self):
                if urlparse(self.path).path == "/api/state":
                    return self._json(200, arm.state())
                return self._json(404, {"error": "not found"})

            def do_POST(self):
                n = int(self.headers.get("Content-Length", 0) or 0)
                try:
                    d = json.loads(self.rfile.read(n) or b"{}")
                except Exception:
                    return self._json(400, {"error": "body is not JSON"})
                p = urlparse(self.path).path
                client = str(d.get("client") or "panel")[:32]
                try:
                    with arm.lock:
                        return self._json(*self._dispatch(p, d, client))
                except PermissionError as e:
                    return self._json(409, {"error": "busy", "owner": str(e)})
                except (ValueError, TypeError) as e:
                    return self._json(400, {"error": str(e)})

            def _dispatch(self, p, d, client):
                if p == "/api/heartbeat":
                    if arm.owner and arm.owner["client"] == client:
                        arm.owner["last"] = time.time()
                    return 200, {"ok": True}
                if p == "/api/estop":
                    arm.enabled, arm.estopped, arm.owner = False, True, None
                    return 200, {"ok": True}
                if p in ("/api/stop", "/api/release"):
                    if p == "/api/stop" or (arm.owner and arm.owner["client"] == client):
                        arm.owner = None
                    arm.target = list(arm.joints)
                    return 200, {"ok": True}
                if p == "/api/enable":
                    arm.enabled = bool(d.get("on"))
                    if arm.enabled:
                        arm.teaching, arm.drag, arm.estopped = False, None, False
                        arm.target = list(arm.joints)
                    return 200, {"ok": True}
                if p == "/api/recover":
                    arm.estopped, arm.enabled, arm.owner = False, True, None
                    arm.target = list(arm.joints)
                    return 200, {"ok": True}
                if p == "/api/speed":
                    arm.speed = int(max(1, min(100, int(d.get("speed", 15)))))
                    return 200, {"ok": True, "speed": arm.speed}
                if p == "/api/stream":
                    joints = [float(v) for v in d.get("joints", [])]
                    if len(joints) != 6 or not all(math.isfinite(v) for v in joints):
                        raise ValueError("joints must be 6 finite numbers in degrees")
                    arm.claim(client, "stream")
                    if not arm.enabled:
                        return 409, {"error": "motors are not enabled"}
                    arm.target = [max(JOINT_LIMITS[i + 1][0], min(JOINT_LIMITS[i + 1][1], v)) for i, v in enumerate(joints)]
                    if d.get("gripper_mm") is not None:
                        arm.gripper_target = max(GRIPPER_MM[0], min(GRIPPER_MM[1], float(d["gripper_mm"])))
                    return 200, {"ok": True, "joints": arm.target, "gripper_mm": arm.gripper_target}
                if p == "/api/gripper":
                    arm.claim(client, {"act": "stream", "piperd": "servo"}.get(client, "panel"))
                    arm.gripper_target = max(GRIPPER_MM[0], min(GRIPPER_MM[1], float(d.get("mm", 0.0))))
                    return 200, {"ok": True, "mm": arm.gripper_target}
                if p == "/sim/teach":
                    arm.teaching = bool(d.get("on"))
                    arm.drag = (list(arm.joints), arm.gripper) if arm.teaching else None
                    return 200, {"ok": True, "teaching": arm.teaching}
                if p == "/sim/drag":
                    if not arm.teaching:
                        return 409, {"error": "not in drag-teach"}
                    joints = [float(v) for v in d.get("joints", arm.joints)]
                    arm.drag = (joints, float(d.get("gripper_mm", arm.gripper)))
                    return 200, {"ok": True}
                return 404, {"error": f"the simulator has no {p}"}

        self.httpd = ThreadingHTTPServer(("127.0.0.1", port), H)
        self.httpd.daemon_threads = True
        self.port = self.httpd.server_address[1]
        self.url = f"http://127.0.0.1:{self.port}"
        threading.Thread(target=self.httpd.serve_forever, daemon=True, name="sim-driver").start()

    def close(self):
        self.httpd.shutdown()
        self.arm.close()


class SimCamera:
    """Same interface as hardware.Camera, rendering the simulated arm's scene at `fps`."""

    def __init__(self, role: str, arm: SimArm, width: int = 640, height: int = 480, fps: int = 15):
        self.role, self.by_id, self.arm = role, f"SIM-{role}", arm
        self.w, self.h, self.fps_req = width, height, fps
        self.frame = None
        self.seq = 0
        self.stamp = 0.0
        self.times = deque(maxlen=30)
        self.error = None
        self.device = f"sim:{role}"
        self._stop = threading.Event()
        self._lock = threading.Lock()
        self._jpeg = None
        self.active = True
        yy, xx = np.mgrid[0:height, 0:width]
        self._bg = np.stack([60 + 40 * yy / height, 70 + 30 * xx / width, 80 + 0 * xx], axis=-1).astype(np.uint8)
        threading.Thread(target=self._run, daemon=True, name=f"simcam-{role}").start()

    def _render(self) -> np.ndarray:
        import cv2
        with self.arm.lock:
            j = list(self.arm.joints)
            g = self.arm.gripper
        img = self._bg.copy()
        # The arm's tool position on the table, in pixels.
        tx = int(320 + 3.0 * j[0])
        ty = int(240 + 2.5 * (j[1] - 45.0) - 1.5 * (j[2] + 60.0))
        cube = (430, 300)
        target = (200, 320)
        if self.role.startswith("wrist"):
            ox, oy = 320 - tx, 240 - ty  # the world as seen from the gripper
            cv2.rectangle(img, (target[0] - 45 + ox, target[1] - 45 + oy), (target[0] + 45 + ox, target[1] + 45 + oy),
                          (200, 40, 40), -1)
            cv2.rectangle(img, (cube[0] - 18 + ox, cube[1] - 18 + oy), (cube[0] + 18 + ox, cube[1] + 18 + oy),
                          (40, 60, 220), -1)
            gap = int(10 + g * 1.2)
            cv2.rectangle(img, (320 - gap - 12, 380), (320 - gap, 479), (210, 210, 210), -1)
            cv2.rectangle(img, (320 + gap, 380), (320 + gap + 12, 479), (210, 210, 210), -1)
        else:
            cv2.rectangle(img, (0, 360), (639, 479), (120, 100, 80), -1)
            cv2.rectangle(img, (target[0] - 45, target[1] - 25), (target[0] + 45, target[1] + 25), (200, 40, 40), -1)
            cv2.rectangle(img, (cube[0] - 18, cube[1] - 30), (cube[0] + 18, cube[1] + 6), (40, 60, 220), -1)
            cv2.line(img, (320, 470), (tx, ty), (230, 230, 230), 10)
            cv2.circle(img, (tx, ty), int(8 + g / 4), (250, 250, 120), -1)
        cv2.putText(img, f"SIM {self.role}", (10, 24), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (255, 255, 255), 2)
        return img

    def _run(self):
        period = 1.0 / self.fps_req
        nxt = time.monotonic()
        while not self._stop.is_set():
            nxt += period
            frame = self._render()
            t = time.monotonic()
            with self._lock:
                self.frame, self.stamp = frame, t
                self.seq += 1
            self.times.append(t)
            if nxt < time.monotonic() - period:
                nxt = time.monotonic()  # fell behind (a slow first frame): don't burst to catch up
            time.sleep(max(0.0, nxt - time.monotonic()))

    def want(self, on: bool):
        self.active = bool(on)  # rendering is cheap; the flag is only reported

    def latest(self):
        with self._lock:
            return self.frame, (time.monotonic() - self.stamp) if self.stamp else math.inf

    def fps(self) -> float:
        t = list(self.times)
        if len(t) < 2 or time.monotonic() - t[-1] > 1.0:
            return 0.0
        return (len(t) - 1) / (t[-1] - t[0]) if t[-1] > t[0] else 0.0

    def jpeg(self, quality: int = 70):
        import cv2
        with self._lock:
            frame, seq = self.frame, self.seq
        if frame is None:
            return None
        if self._jpeg and self._jpeg[0] == seq:
            return self._jpeg[1]
        ok, buf = cv2.imencode(".jpg", cv2.cvtColor(frame, cv2.COLOR_RGB2BGR), [int(cv2.IMWRITE_JPEG_QUALITY), quality])
        if not ok:
            return None
        self._jpeg = (seq, buf.tobytes())
        return self._jpeg[1]

    def info(self) -> dict:
        _, age = self.latest()
        return {"role": self.role, "device": self.device, "match": self.by_id, "fps": round(self.fps(), 1),
                "age_ms": None if math.isinf(age) else round(age * 1000), "error": None,
                "size": [self.w, self.h], "sim": True, "active": self.active}

    def close(self):
        self._stop.set()
