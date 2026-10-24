"""The ACT engine: one process on the Dell that records demonstrations, runs learned policies and hands
training to the Mac. ACT Studio (a NervusOS app) is its user interface; it listens on loopback only.

    python -m nervus_act --shared ~/Shared/NervusACT --cameras wrist=Dabai_DC1,scene=046d_0825
"""
from __future__ import annotations

import argparse
import json
import os
import re
import signal
import threading
import time
import traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

from .datasets import Datasets
from .hardware import Arm, ArmError, Camera
from .link import SyncLink
from .recorder import Recorder
from .runner import Runner
from .store import Store, read_json, safe_name, utc_now, write_json

THUMB_RE = re.compile(r"^episode_\d{6}_[a-z0-9_]+_\d\.jpg$")
JOB_FIELDS = {"steps": (100, 500_000), "chunk_size": (5, 200), "batch_size": (1, 64), "save_freq": (500, 100_000)}


class Engine:
    def __init__(self, args):
        self.args = args
        self.store = Store(Path(args.shared))
        self.sim = None
        if getattr(args, "sim", False):
            from .sim import SimDriver
            self.sim = SimDriver()
            args.driver = self.sim.url
        self.arm = Arm(args.driver)
        self.cameras: dict[str, Camera] = {}
        for spec in args.cameras.split(","):
            if "=" in spec:
                role, match = (x.strip() for x in spec.split("=", 1))
                if self.sim:
                    from .sim import SimCamera
                    self.cameras[role] = SimCamera(role, self.sim.arm, fps=args.fps)
                else:
                    self.cameras[role] = Camera(role, match, fps=args.fps)
        self.datasets = Datasets(self.store)
        self.recorder = Recorder(self.store, self.arm, self.cameras, fps=args.fps)
        self.runner = Runner(self.store, self.datasets, self.arm, self.cameras, threads=args.threads)
        self.link = SyncLink(Path.home())
        self.started = utc_now()
        self.last_request = time.monotonic()
        threading.Thread(target=self._camera_demand, daemon=True, name="camera-demand").start()

    CAMERA_IDLE_S = 60

    def touch(self):
        self.last_request = time.monotonic()

    def _camera_demand(self):
        """Cameras run while ACT Studio is open (it polls every second or so) or a take or a run needs them,
        and are released a minute after the last of those. Waking them takes about a second."""
        while True:
            busy = self._busy_recording() or self._busy_running()
            want = busy or time.monotonic() - self.last_request < self.CAMERA_IDLE_S
            for c in self.cameras.values():
                if c.active != want:
                    c.want(want)
            time.sleep(1.0)

    # ---- exclusivity: one user of the arm and cameras at a time ----
    def _busy_recording(self) -> bool:
        return self.recorder.state in ("recording", "saving")

    def _busy_running(self) -> bool:
        return self.runner.state in ("running", "moving", "loading")

    def status(self) -> dict:
        return {
            "time": utc_now(), "engine": {"started": self.started, "pid": os.getpid(), "sim": self.sim is not None,
                                          "sim_driver": self.sim.url if self.sim else None},
            "arm": self.arm.info(),
            "cameras": {r: c.info() for r, c in self.cameras.items()},
            "recorder": self.recorder.status(),
            "runner": self.runner.status(),
            "worker": self.store.worker(),
            "link": self.link.status(),
            "posttrain": read_json(self.store.root / "posttrain" / "STATUS.json", {}),
            "current_policy": self.store.current_policy(),
            "shared": str(self.store.root),
        }

    def rollouts(self, limit: int = 40) -> list[dict]:
        d = self.store.root / "rollouts"
        out = []
        for p in sorted((x for x in d.iterdir() if x.is_dir() and not x.name.startswith(".")), reverse=True)[:limit]:
            m = read_json(p / "meta.json", {})
            out.append({"run_id": p.name, "policy": m.get("policy"), "outcome": m.get("outcome"),
                        "interventions": m.get("interventions"), "frames": m.get("frames"),
                        "elapsed_s": m.get("elapsed_s"), "stop_reason": m.get("stop_reason"),
                        "ready": (p / "READY").exists(), "created": m.get("created")})
        return out

    def new_job(self, body: dict) -> str:
        name = safe_name(body.get("name", ""))
        datasets = body.get("datasets") or []
        if not datasets:
            raise ValueError("pick at least one dataset")
        known = {d["name"]: d for d in self.store.datasets()}
        cams = None
        for ds in datasets:
            if ds not in known:
                raise ValueError(f"unknown dataset {ds!r}")
            if cams is None:
                cams = known[ds]["cameras"]
            elif known[ds]["cameras"] != cams:
                raise ValueError("datasets in one job must share the same cameras")
        spec = {"type": "train", "name": name, "datasets": datasets, "cameras": cams,
                "task": known[datasets[0]].get("task"), "policy": "act", "notes": str(body.get("notes", ""))[:500]}
        for k, (lo, hi) in JOB_FIELDS.items():
            spec[k] = int(min(hi, max(lo, int(body.get(k, {"steps": 60000, "chunk_size": 30, "batch_size": 8, "save_freq": 5000}[k])))))
        parent = body.get("parent")
        if parent:
            if parent not in [p["name"] for p in self.store.policies()]:
                raise ValueError(f"unknown parent policy {parent!r}")
            spec["parent"] = parent
        # n_action_steps must not exceed the chunk; executing a full chunk at 15 Hz is ~2 s of motion,
        # which is the length the ACT authors recommend ("one chunk ~ 1-2 s of robot motion").
        spec["n_action_steps"] = spec["chunk_size"]
        spec["worker"] = body.get("worker", "mac")
        if spec["worker"] not in ("mac", "any"):
            raise ValueError("worker is mac or any")
        return self.store.new_job(spec)

    def promote(self, name: str):
        """Ask the Mac to make a policy CURRENT now. The Mac writes CURRENT.json (one writer per file)."""
        if name not in [p["name"] for p in self.store.policies()]:
            raise ValueError(f"unknown policy {name!r}")
        write_json(self.store.root / "posttrain" / "PROMOTE", {"name": name, "time": utc_now()})

    def close(self):
        self.runner.close()
        try:
            if self.recorder.state not in ("closed",):
                self.recorder.close()
        except Exception:
            pass
        for c in self.cameras.values():
            c.close()
        self.arm.release()
        self.arm.close()
        if self.sim:
            self.sim.close()


def make_handler(eng: Engine):
    class H(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *a):
            pass

        def _send(self, code: int, body: bytes, ctype: str):
            self.send_response(code)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(body)

        def _json(self, obj, code: int = 200):
            self._send(code, json.dumps(obj).encode(), "application/json")

        def _err(self, e: Exception, code: int = 400):
            self._json({"error": str(e)}, code)

        def do_GET(self):
            eng.touch()
            u = urlparse(self.path)
            parts = [p for p in u.path.split("/") if p]
            try:
                if u.path == "/api/status":
                    return self._json(eng.status())
                if len(parts) == 3 and parts[:2] == ["api", "camera"] and parts[2].endswith(".jpg"):
                    cam = eng.cameras.get(parts[2][:-4])
                    jpg = cam.jpeg() if cam else None
                    if jpg is None:
                        return self._json({"error": "no frame"}, 404)
                    return self._send(200, jpg, "image/jpeg")
                if u.path == "/api/datasets":
                    return self._json(eng.store.datasets())
                if len(parts) == 4 and parts[:2] == ["api", "datasets"] and parts[3] == "episodes":
                    return self._json(eng.datasets.episodes(parts[2]))
                if len(parts) == 5 and parts[:2] == ["api", "datasets"] and parts[3] == "curve":
                    return self._json(eng.datasets.curve(parts[2], int(parts[4])))
                if len(parts) == 5 and parts[:2] == ["api", "datasets"] and parts[3] == "thumb":
                    if not THUMB_RE.match(parts[4]):
                        return self._json({"error": "bad name"}, 400)
                    p = eng.store.dataset_dir(parts[2]) / "thumbs" / parts[4]
                    if not p.is_file():
                        return self._json({"error": "no thumbnail"}, 404)
                    return self._send(200, p.read_bytes(), "image/jpeg")
                if u.path == "/api/policies":
                    return self._json(eng.store.policies())
                if u.path == "/api/jobs":
                    return self._json(eng.store.jobs())
                if len(parts) == 4 and parts[:2] == ["api", "jobs"] and parts[3] == "metrics":
                    return self._json(eng.store.job_metrics(parts[2]))
                if u.path == "/api/trials":
                    return self._json(eng.store.trials())
                if u.path == "/api/rollouts":
                    return self._json(eng.rollouts())
                return self._json({"error": "not found"}, 404)
            except (ValueError, RuntimeError, ArmError) as e:
                return self._err(e)
            except Exception as e:
                traceback.print_exc()
                return self._err(e, 500)

        def do_POST(self):
            eng.touch()
            n = int(self.headers.get("Content-Length", 0) or 0)
            try:
                body = json.loads(self.rfile.read(n) or b"{}")
            except Exception:
                return self._json({"error": "body is not JSON"}, 400)
            u = urlparse(self.path)
            parts = [p for p in u.path.split("/") if p]
            try:
                return self._json(self._post(u.path, parts, body) or {"ok": True})
            except (ValueError, RuntimeError, ArmError) as e:
                return self._err(e, 409 if isinstance(e, RuntimeError) else 400)
            except Exception as e:
                traceback.print_exc()
                return self._err(e, 500)

        def _post(self, path: str, parts: list[str], b: dict):
            r, rec = eng.runner, eng.recorder
            if path == "/api/heartbeat":
                r.heartbeat()
                return None
            # arm
            if path == "/api/arm/enable":
                r.enable()
                return None
            if path == "/api/arm/stop":
                r.stop()
                eng.arm.stop()
                return None
            if path == "/api/arm/estop":
                r.estop()
                return None
            if path == "/api/arm/recover":
                eng.arm.recover()
                return None
            if path == "/api/arm/release":
                eng.arm.release()
                return None
            # recording
            if path == "/api/record/open":
                if eng._busy_running():
                    raise RuntimeError("a policy is running")
                rec.open(b.get("name", ""), b.get("task", ""), list(b.get("cameras") or eng.cameras.keys()))
                return None
            if path == "/api/record/start":
                if eng._busy_running():
                    raise RuntimeError("a policy is running")
                rec.start()
                return None
            if path == "/api/record/stop":
                rec.stop(discard=bool(b.get("discard")))
                return None
            if path == "/api/record/close":
                rec.close()
                return None
            # datasets
            if len(parts) == 4 and parts[:2] == ["api", "datasets"] and parts[3] == "edits":
                return eng.datasets.set_edits(parts[2], b)
            # jobs
            if path == "/api/jobs":
                return {"id": eng.new_job(b)}
            if len(parts) == 4 and parts[:2] == ["api", "jobs"] and parts[3] == "cancel":
                eng.store.cancel_job(parts[2])
                return None
            if path == "/api/policies/promote":
                eng.promote(str(b.get("name", "")))
                return {"message": "Asked the Mac to make it CURRENT; it changes within a minute while the Mac is online."}
            if path == "/api/posttrain/request":
                write_json(eng.store.root / "posttrain" / "REQUEST", {"time": utc_now(), "note": b.get("note", "")})
                return None
            # running
            if path == "/api/run/load":
                if eng._busy_recording():
                    raise RuntimeError("a demonstration is being recorded")
                r.load(b.get("name", ""))
                return None
            if path == "/api/run/unload":
                r.unload()
                return None
            if path == "/api/run/preview":
                return r.preview()
            if path == "/api/run/goto_start":
                r.goto_start()
                return None
            if path == "/api/run/start":
                if eng._busy_recording():
                    raise RuntimeError("a demonstration is being recorded")
                r.start()
                return None
            if path == "/api/run/stop":
                r.stop()
                return None
            if path == "/api/run/correct":
                r.correct()
                return None
            if path == "/api/run/mark":
                return {"message": r.mark(b.get("outcome", ""), str(b.get("case", "A"))[:20], str(b.get("note", ""))[:300])}
            if path == "/api/run/params":
                r.set_params(b)
                return None
            raise ValueError(f"unknown action {path}")

    return H


def main():
    ap = argparse.ArgumentParser(description="NervusOS ACT engine")
    ap.add_argument("--shared", default=os.path.expanduser("~/Shared/NervusACT"))
    ap.add_argument("--driver", default="http://127.0.0.1:8770")
    ap.add_argument("--cameras", default="wrist=Dabai_DC1,scene=046d_0825",
                    help="role=by-id substring, comma separated (never a /dev/videoN index)")
    ap.add_argument("--fps", type=int, default=15)
    ap.add_argument("--threads", type=int, default=4, help="torch threads for inference")
    ap.add_argument("--bind", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8795)
    ap.add_argument("--sim", action="store_true", help="a simulated arm and cameras, for trying it without hardware")
    args = ap.parse_args()
    if args.bind not in ("127.0.0.1", "localhost", "::1"):
        raise SystemExit("the engine moves the arm and has no authentication: it listens on loopback only")
    eng = Engine(args)
    srv = ThreadingHTTPServer((args.bind, args.port), make_handler(eng))
    srv.daemon_threads = True

    def stop(*_):
        threading.Thread(target=srv.shutdown, daemon=True).start()

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    print(f"ACT engine on http://{args.bind}:{args.port}, shared folder {eng.store.root}", flush=True)
    try:
        srv.serve_forever()
    finally:
        eng.close()
        print("ACT engine stopped", flush=True)


if __name__ == "__main__":
    main()
