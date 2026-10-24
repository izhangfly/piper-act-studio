"""Running a learned ACT policy on the arm, and recording each run for post-training.

The control loop runs at the dataset's 15 Hz. ACT predicts a chunk of future actions per inference;
on the Dell's i7-8650U one inference takes 350-700 ms, about 5-10 control steps, so planning is
asynchronous: a new chunk is requested every `replan_every` steps from the observation of that step,
and steps it arrives too late for are skipped. Everything below was fixed on the real arm in the
Piper-ACT project (UPDATE.md, 2026-09-14/15):

* Inference runs in its own process. Sharing a GIL with busy threads doubled inference time.
* Step limit on the COMMAND, not on the measured position: the gripper answers with 0.5-0.8 s of dead
  time, and limiting from the measurement pinned it half-open, after which the model flipped between
  open and close. A separate lead limit caps how far joint commands may run ahead of the arm.
* Joints are averaged over the newest two overlapping plans (ACT's temporal ensembling, done
  asynchronously). The gripper is never averaged: the mean of open and closed is half-open, which is
  exactly what dropped the cube.
* The grasp latch: once the fingers have stopped on an object a little wider than the command, the
  command stays at "measured minus squeeze", ignoring single-step flickers to open or to close fully;
  releasing takes `gripper_release_steps` open requests in a row. Effort defaults to 500 (1000
  crushed a printed cube), and a few mm of squeeze gives grip force (commanding exactly the
  demonstrated contact width asks for zero force).
* Stale arm feedback or a stale camera for a moment holds the last command; only a stale stretch
  longer than the grace period ends the run (a 310 ms CPU stall used to stop runs).
* A correction (the operator presses the arm's drag-teach button, or Correct / C in the app) is checked
  BEFORE any stale break, switches the run's segment to "human", and keeps recording. Human segments
  are what post-training learns from.
* A run is labelled once: the first Success/Fail stands, for the rollout and for the trial record.
"""
from __future__ import annotations

import json
import math
import multiprocessing as mp
import os
import queue
import threading
import time
import traceback
from collections import deque
from datetime import datetime
from pathlib import Path

import numpy as np

from .datasets import Datasets
from .hardware import JOINTS, Arm, ArmError, Camera, mono
from .store import CONTRACT, Store, read_json, utc_now, write_json

DEFAULT_PARAMS = {
    "replan_every": 10,          # steps between new chunks (1..chunk_size)
    "crossfade": 4,              # steps blended when a new chunk takes over (4 suits 350-700 ms inference)
    "speed_pct": 40,             # firmware MOVE J speed, percent
    "step_limit_x": 1.5,         # max per-step change of the command, x the demos' 99th percentile (0 = off)
    "lead_limit_x": 4.0,         # max joint command lead over the measured arm, x the demo p99 (0 = off)
    "ensemble": 2,               # joint targets = mean of the newest N overlapping plans
    "envelope_margin": 5.0,      # targets clamped to the demonstrated range +/- this (deg; mm for the gripper)
    "gripper_effort": 500,       # 0.001 N*m; 1000 crushed a 3D-printed cube
    "gripper_squeeze_mm": 1.5,   # commanded this much tighter than the measured width while holding
    "gripper_release_steps": 8,  # while holding, open requests needed in a row to let go (0 = off)
    "record_rollouts": 1,        # save each run for post-training
    "intervene_on_teach": 1,     # drag-teach during a run = a human correction (recorded), not a stop
    "max_duration_s": 90,
    "stale_camera_ms": 500,
    "stale_arm_ms": 300,
    "stale_arm_grace_s": 1.0,
    "stale_camera_grace_s": 1.0,
    "heartbeat_s": 3.0,          # stop if the app stops talking for this long while running
    "dry_run": 0,                # 1 = plan and log everything but send nothing to the arm
}
LIMITS = {
    "replan_every": (1, 100, int), "crossfade": (0, 10, int), "speed_pct": (5, 100, int),
    "step_limit_x": (0.0, 5.0, float), "lead_limit_x": (0.0, 10.0, float), "ensemble": (1, 3, int),
    "envelope_margin": (0.0, 30.0, float), "gripper_effort": (100, 5000, int),
    "gripper_squeeze_mm": (0.0, 10.0, float), "gripper_release_steps": (0, 45, int),
    "record_rollouts": (0, 1, int), "intervene_on_teach": (0, 1, int), "max_duration_s": (5, 900, int),
    "stale_camera_ms": (100, 3000, int), "stale_arm_ms": (100, 3000, int),
    "stale_arm_grace_s": (0.2, 3.0, float), "stale_camera_grace_s": (0.2, 3.0, float),
    "heartbeat_s": (0.5, 10.0, float), "dry_run": (0, 1, int),
}
PREVIEW_GOOD, PREVIEW_WEAK = 20.0, 8.0   # degrees the model plans to move within one chunk


# ---------------------------------------------------------------------------------------------------
# The policy, in its own process
# ---------------------------------------------------------------------------------------------------
class Policy:
    def __init__(self, path: Path, threads: int):
        import torch
        from lerobot.policies.act.modeling_act import ACTPolicy
        from lerobot.policies.factory import make_pre_post_processors
        torch.set_num_threads(threads)
        self.torch = torch
        self.model = ACTPolicy.from_pretrained(str(path))
        self.model.config.device = "cpu"
        self.model.to("cpu").eval()
        cpu = {"device_processor": {"device": "cpu"}}
        self.pre, self.post = make_pre_post_processors(self.model.config, pretrained_path=str(path),
                                                       preprocessor_overrides=cpu, postprocessor_overrides=cpu)
        self.chunk_size = int(self.model.config.chunk_size)
        self.image_keys = [k for k in self.model.config.input_features if k.startswith("observation.images.")]

    def chunk(self, state: list[float], images: dict[str, np.ndarray]) -> np.ndarray:
        from lerobot.policies.utils import prepare_observation_for_inference
        obs = {"observation.state": np.array(state, dtype=np.float32)}
        for k in self.image_keys:
            obs[k] = images[k.removeprefix("observation.images.")]
        with self.torch.inference_mode():
            batch = self.pre(prepare_observation_for_inference(obs, self.torch.device("cpu"), None, "agilex_piper"))
            actions = self.model.predict_action_chunk(batch)
            return np.stack([self.post(actions[:, i, :]).squeeze(0).cpu().numpy() for i in range(actions.shape[1])])


def _policy_worker(conn, path, threads):
    try:
        pol = Policy(Path(path), threads)
        conn.send(("ready", pol.chunk_size, pol.image_keys))
    except Exception as e:
        conn.send(("error", f"{type(e).__name__}: {e}"))
        return
    while True:
        try:
            msg = conn.recv()
        except (EOFError, OSError):
            return
        if msg is None:
            return
        try:
            t = time.perf_counter()
            out = pol.chunk(*msg)
            conn.send(("ok", out, (time.perf_counter() - t) * 1000))
        except Exception as e:
            conn.send(("error", f"{type(e).__name__}: {e}"))


class PolicyProcess:
    def __init__(self, path: Path, threads: int):
        ctx = mp.get_context("spawn")
        self.path = path
        self.last_ms = None
        self.lock = threading.Lock()
        self.conn, child = ctx.Pipe()
        self.proc = ctx.Process(target=_policy_worker, args=(child, str(path), threads), daemon=True)
        self.proc.start()
        child.close()
        if not self.conn.poll(300):
            self.close()
            raise RuntimeError("the policy process did not start within 300 s")
        msg = self.conn.recv()
        if msg[0] != "ready":
            self.close()
            raise RuntimeError(msg[1])
        _, self.chunk_size, self.image_keys = msg

    def chunk(self, state: list[float], images: dict[str, np.ndarray]) -> np.ndarray:
        with self.lock:
            if not self.proc.is_alive():
                raise RuntimeError("the policy process died")
            t = time.perf_counter()
            self.conn.send((list(state), images))
            if not self.conn.poll(30):
                raise RuntimeError("the policy process did not answer within 30 s")
            msg = self.conn.recv()
            if msg[0] != "ok":
                raise RuntimeError(msg[1])
            self.last_ms = (time.perf_counter() - t) * 1000
            return msg[1]

    def close(self):
        try:
            self.conn.send(None)
        except Exception:
            pass
        try:
            self.proc.join(3)
            if self.proc.is_alive():
                self.proc.terminate()
        except Exception:
            pass


class Planner:
    """Async chunking: the observation at step s gives chunk[i] = target for step s+i."""

    def __init__(self, policy: PolicyProcess):
        self.policy = policy
        self.plan: dict[int, np.ndarray] = {}
        self.history = deque(maxlen=3)
        self.ens_n = 1
        self.inflight = None
        self.ready = None
        self.last_obs_step = None
        self.lock = threading.Lock()
        self.latencies = deque(maxlen=20)
        self.starved = 0
        self.error = None

    def request(self, step: int, state: list[float], images: dict[str, np.ndarray]) -> bool:
        with self.lock:
            if self.inflight is not None:
                return False
            self.inflight = step
        imgs = {k: v.copy() for k, v in images.items()}
        threading.Thread(target=self._work, args=(step, list(state), imgs), daemon=True, name="plan").start()
        return True

    def _work(self, step, state, images):
        t = mono()
        try:
            ch = self.policy.chunk(state, images)
            with self.lock:
                self.ready = (step, ch)
                self.latencies.append(mono() - t)
        except Exception as e:
            self.error = f"policy: {e}"
        finally:
            with self.lock:
                self.inflight = None

    def integrate(self, k: int, crossfade: int):
        with self.lock:
            got, self.ready = self.ready, None
        if not got:
            return None
        s, ch = got
        self.last_obs_step = s
        self.history.append((s, ch))
        new = {s + i: ch[i] for i in range(len(ch)) if s + i >= k}
        for n, step in enumerate(sorted(new)):
            if n < crossfade and step in self.plan:
                w = (n + 1) / (crossfade + 1)
                new[step] = (1 - w) * self.plan[step] + w * new[step]
        self.plan = new
        return max(0, k - s)

    def take(self, k: int, ensemble: int = 1):
        a = self.plan.pop(k, None)
        for old in [x for x in self.plan if x < k]:
            self.plan.pop(old)
        if a is None:
            self.starved += 1
            return None
        self.ens_n = 1
        if ensemble > 1:
            vals = [c[k - s] for s, c in list(self.history)[-ensemble:] if 0 <= k - s < len(c)]
            if len(vals) > 1:
                m = np.mean(vals, axis=0)
                m[6] = a[6]  # the gripper takes the newest plan, never an average
                self.ens_n = len(vals)
                return m
        return a

    def remaining(self, k):
        return sum(1 for x in self.plan if x >= k)


# ---------------------------------------------------------------------------------------------------
# Rollout packages for post-training
# ---------------------------------------------------------------------------------------------------
class RolloutRecorder:
    """rollouts/<run_id>/: episode.jsonl (one row per control step), <camera>.mp4 (frame i = the image the
    policy saw at row i), meta.json, and READY once the operator labels the run, so the Mac never takes a
    half-written or unlabelled run. Rows and frames share one queue so they can't misalign."""

    def __init__(self, root: Path, run_id: str, meta: dict, fps: int):
        self.dir = root / run_id
        self.dir.mkdir(parents=True, exist_ok=True)
        self.meta = dict(meta, run_id=run_id, fps=fps, created=utc_now())
        self.fps = fps
        self.q = queue.Queue(maxsize=90)
        self.frames = 0
        self.dropped = 0
        self.error = None
        self._f = open(self.dir / "episode.jsonl", "w", encoding="utf-8")
        self._writers = {}
        self._t = threading.Thread(target=self._run, daemon=True, name="rollout-writer")
        self._t.start()

    def add(self, row: dict, images: dict[str, np.ndarray]):
        try:
            self.q.put_nowait((row, images))
        except queue.Full:
            self.dropped += 1

    def _run(self):
        import cv2
        while True:
            item = self.q.get()
            if item is None:
                return
            row, imgs = item
            try:
                for cam, img in imgs.items():
                    w = self._writers.get(cam)
                    if w is None:
                        h, wd = img.shape[:2]
                        w = cv2.VideoWriter(str(self.dir / f"{cam}.mp4"), cv2.VideoWriter_fourcc(*"mp4v"), self.fps, (wd, h))
                        self._writers[cam] = w
                    w.write(cv2.cvtColor(img, cv2.COLOR_RGB2BGR))
                row["frame"] = self.frames
                self.frames += 1
                self._f.write(json.dumps(row) + "\n")
            except Exception as e:
                self.error = str(e)

    def close(self, summary: dict):
        self.q.put(None)
        self._t.join(timeout=60)
        for w in self._writers.values():
            w.release()
        self._f.close()
        self.meta.update(summary, frames=self.frames, dropped=self.dropped, writer_error=self.error,
                         videos={c: f"{c}.mp4" for c in self._writers})
        write_json(self.dir / "meta.json", self.meta)

    def finalize(self, outcome: str, case: str, note: str) -> bool:
        if (self.dir / "READY").exists():
            return False
        self.meta.update(outcome=outcome, case=case, note=note, labelled=utc_now())
        write_json(self.dir / "meta.json", self.meta)
        (self.dir / "READY").write_text(outcome, encoding="utf-8")
        return True


# ---------------------------------------------------------------------------------------------------
# Controller
# ---------------------------------------------------------------------------------------------------
class Runner:
    FPS = 15

    def __init__(self, store: Store, datasets: Datasets, arm: Arm, cameras: dict[str, Camera], threads: int = 4):
        self.store, self.datasets, self.arm, self.cameras, self.threads = store, datasets, arm, cameras, threads
        self.params = dict(DEFAULT_PARAMS)
        self.state = "idle"          # idle, loading, ready, moving, running, stopped
        self.message = "Load a policy to run it."
        self.policy: PolicyProcess | None = None
        self.policy_name: str | None = None
        self.facts: dict = {}
        self.planner: Planner | None = None
        self.lock = threading.RLock()
        self.last_heartbeat = mono()
        self.run: dict | None = None
        self.rollout: RolloutRecorder | None = None
        self.target = None
        self.preview_result = None
        self.stop_flag = threading.Event()
        self.correct_flag = threading.Event()

    # ---- facts about the policy ----
    def _load_facts(self, name: str) -> dict:
        meta = read_json(self.store.policy_dir(name) / "nervus_policy.json", {}) or {}
        facts = {k: meta.get(k) for k in ("envelope", "step_p99", "start_pose", "cameras", "dataset", "task")}
        if not (facts.get("envelope") and facts.get("step_p99") and facts.get("start_pose")) and meta.get("dataset"):
            try:
                facts.update({k: v for k, v in self.datasets.stats(meta["dataset"]).items() if not facts.get(k)})
            except Exception:
                pass
        lim = (self.arm.info().get("limits") or {})
        if not facts.get("envelope"):
            facts["envelope"] = {f"joint{i}": lim.get(str(i), [-150, 150]) for i in range(1, 7)}
            facts["envelope"]["gripper"] = [0.0, 70.0]
        if not facts.get("step_p99"):
            facts["step_p99"] = {j: 4.0 for j in JOINTS}
        return facts

    def gripper_max(self) -> float:
        return float(self.facts.get("envelope", {}).get("gripper", [0, 70])[1] or 70.0)

    # ---- helpers ----
    def _clamp(self, target: dict, current: dict, last: dict | None) -> dict:
        p, env, p99 = self.params, self.facts["envelope"], self.facts["step_p99"]
        out = {}
        for j in JOINTS:
            v = float(target[j])
            lo, hi = env[j]
            v = min(hi + p["envelope_margin"], max(lo - p["envelope_margin"], v))
            if p["step_limit_x"] > 0 and last is not None:
                lim = max(0.2, p99[j]) * p["step_limit_x"] * (3.0 if j == "gripper" else 1.0)
                v = min(last[j] + lim, max(last[j] - lim, v))
            if j != "gripper" and p["lead_limit_x"] > 0 and current is not None:
                lead = max(0.2, p99[j]) * p["lead_limit_x"]
                v = min(current[j] + lead, max(current[j] - lead, v))
            out[j] = v
        out["gripper"] = max(0.0, out["gripper"])
        return out

    def _gripper(self, plan_g: float, meas_g: float, g: dict) -> tuple[float, str]:
        """The grasp latch (see the module doc). Millimetres; the thresholds scale with the gripper's
        demonstrated range so a different gripper or object works the same way."""
        p, gmax = self.params, self.gripper_max()
        sq = p["gripper_squeeze_mm"]
        last = g.get("last")
        hist = g.setdefault("hist", deque(maxlen=6))
        hist.append(meas_g)
        stable = len(hist) == hist.maxlen and max(hist) - min(hist) <= 0.7
        holding = (last is not None and stable and 0.25 * gmax <= meas_g <= 0.92 * gmax
                   and 1.0 <= meas_g - last <= 8.0)
        if holding and p["gripper_release_steps"] > 0:
            g["open_count"] = g.get("open_count", 0) + 1 if plan_g >= meas_g + 5.5 else 0
            if g["open_count"] < p["gripper_release_steps"]:
                cmd = max(0.0, meas_g - sq)
                g["last"] = cmd
                return cmd, "hold"
            g["open_count"] = 0
            g["last"] = plan_g
            return plan_g, "release"
        g["open_count"] = 0
        cmd = plan_g if plan_g >= 0.95 * gmax else max(0.0, plan_g - sq)
        g["last"] = cmd
        return cmd, "follow"

    def _images(self) -> tuple[dict[str, np.ndarray] | None, float]:
        """The newest frame of every camera the policy needs, and the oldest age among them."""
        out, worst = {}, 0.0
        for k in self.policy.image_keys:
            role = k.removeprefix("observation.images.")
            cam = self.cameras.get(role)
            if cam is None:
                return None, math.inf
            img, age = cam.latest()
            if img is None:
                return None, math.inf
            out[role] = img
            worst = max(worst, age)
        return out, worst

    def _need_policy(self):
        if self.policy is None:
            raise RuntimeError("load a policy first")

    # ---- actions ----
    def load(self, name: str):
        with self.lock:
            if self.state in ("running", "moving", "loading"):
                raise RuntimeError(f"not while {self.state}")
            listed = {p["name"]: p for p in self.store.policies()}
            if name not in listed:  # only names we list: never join arbitrary input onto a path
                raise RuntimeError(f"unknown policy {name!r}")
            if not listed[name]["complete"]:
                raise RuntimeError(f"{name} is incomplete (missing {', '.join(listed[name]['missing'])}): if the Mac "
                                   "published it, it is still arriving, so try again in a minute")
            self.state, self.message = "loading", f"Loading {name}…"
        try:
            facts = self._load_facts(name)
            pol = PolicyProcess(self.store.policy_dir(name), self.threads)
            missing = [k for k in pol.image_keys if k.removeprefix("observation.images.") not in self.cameras]
            if missing:
                pol.close()
                raise RuntimeError(f"the policy needs camera(s) {missing} that this machine doesn't have")
            # warm up and measure honestly
            imgs = {k.removeprefix("observation.images."): np.zeros((480, 640, 3), np.uint8) for k in pol.image_keys}
            sp = facts.get("start_pose") or {j: 0.0 for j in JOINTS}
            for _ in range(3):
                pol.chunk([sp[j] for j in JOINTS], imgs)
        except Exception as e:
            with self.lock:
                self.state, self.message = "idle", f"Load failed: {e}"
            raise
        with self.lock:
            old, self.policy, self.policy_name, self.facts = self.policy, pol, name, facts
            self.planner = Planner(pol)
            self.preview_result = None
            self.params["replan_every"] = min(self.params["replan_every"], pol.chunk_size)
            self.state = "ready"
            self.message = f"Loaded {name}: chunk {pol.chunk_size}, one inference ≈ {pol.last_ms:.0f} ms on this CPU."
        if old is not None:
            old.close()

    def unload(self):
        with self.lock:
            if self.state in ("running", "moving"):
                raise RuntimeError(f"not while {self.state}")
            old, self.policy, self.policy_name = self.policy, None, None
            self.state, self.message = "idle", "Policy unloaded."
        if old:
            old.close()

    def preview(self) -> dict:
        """What the policy WOULD do from the current pose and camera frames. Sends nothing to the arm.
        A frame from a different room or light makes ACT 'want' to sit still: below 8 degrees of planned
        motion, fix the scene before running (the demos' own frames scored ~57 on the first policy, a
        changed daylight room 2-8)."""
        self._need_policy()
        with self.lock:
            if self.state in ("running", "moving"):
                raise RuntimeError(f"not while {self.state}")
        state, age = self.arm.read()
        if state is None or age > 0.3:
            raise RuntimeError("the scene check needs live arm feedback")
        imgs, cam_age = self._images()
        if imgs is None or cam_age > 0.5:
            raise RuntimeError("the scene check needs fresh frames from every camera the policy uses")
        ch = self.policy.chunk([state[j] for j in JOINTS], imgs)
        dev = ch - np.array([state[j] for j in JOINTS])
        end = float(np.abs(dev[-1, :6]).max())
        verdict = ("plans to move" if end >= PREVIEW_GOOD else "weak: may hesitate" if end >= PREVIEW_WEAK
                   else "would likely sit still: the scene or light differs from the demonstrations")
        self.preview_result = {"at": datetime.now().strftime("%H:%M:%S"), "score": round(end, 1), "verdict": verdict,
                               "good": end >= PREVIEW_GOOD, "weak": PREVIEW_WEAK <= end < PREVIEW_GOOD,
                               "change": {j: round(float(dev[-1, i]), 1) for i, j in enumerate(JOINTS)},
                               "brightness": {r: round(float(i.mean()) / 255, 2) for r, i in imgs.items()},
                               "ms": round(self.policy.last_ms or 0)}
        return self.preview_result

    def enable(self):
        """Torque on, holding the measured pose. Works straight from drag-teach (the enable sequence takes
        the arm out of it)."""
        state, age = self.arm.read()
        if state is None or age > 0.3:
            raise RuntimeError("no live feedback from the arm. If it is idle, press its drag-teach button once "
                               "(light on), then Enable again")
        self.arm.enable()

    def goto_start(self):
        self._need_policy()
        with self.lock:
            if self.state in ("running", "moving"):
                raise RuntimeError(f"not while {self.state}")
            if not (self.arm.info().get("enabled")):
                raise RuntimeError("enable the motors first")
            self.state, self.message = "moving", "Moving to the start pose…"
            self.stop_flag.clear()
        threading.Thread(target=self._goto, args=(dict(self.facts["start_pose"]),), daemon=True, name="goto").start()

    def _goto(self, pose):
        try:
            cur, age = self.arm.read()
            if cur is None or age > 0.3:
                raise RuntimeError("no live arm feedback")
            self.arm.speed(20)
            steps = 60  # 4 s at 15 Hz, slow and smooth
            for i in range(1, steps + 1):
                if self.stop_flag.is_set():
                    break
                w = 0.5 - 0.5 * math.cos(math.pi * i / steps)
                tgt = {j: cur[j] + (pose[j] - cur[j]) * w for j in JOINTS}
                self.arm.stream(tgt, self.params["gripper_effort"])
                self.target = tgt
                time.sleep(1 / self.FPS)
            time.sleep(1.0)
            with self.lock:
                self.state, self.message = "ready", "At the start pose. Set the scene, then Run."
        except Exception as e:
            with self.lock:
                self.state, self.message = "ready", f"Start-pose move failed: {e}"
        finally:
            self.stop_flag.clear()

    def start(self):
        self._need_policy()
        with self.lock:
            if self.state in ("running", "moving", "loading"):
                raise RuntimeError(f"already {self.state}")
            imgs, cam_age = self._images()
            if imgs is None or cam_age * 1000 > self.params["stale_camera_ms"]:
                raise RuntimeError("no fresh frame from every camera the policy uses")
            for k in self.policy.image_keys:
                role = k.removeprefix("observation.images.")
                if self.cameras[role].fps() < 5:
                    raise RuntimeError(f"{role} camera at {self.cameras[role].fps():.1f} fps (< 5): fix it before running")
            state, age = self.arm.read()
            if state is None or age * 1000 > self.params["stale_arm_ms"]:
                raise RuntimeError("no live arm feedback")
            dry = bool(self.params["dry_run"])
            info = self.arm.info()
            if not dry and info.get("teaching"):
                raise RuntimeError("the arm is in drag-teach: press Enable to take it out")
            if not dry and not info.get("enabled"):
                raise RuntimeError("enable the motors first")
            rid = datetime.now().strftime("%Y%m%d-%H%M%S") + "_" + self.policy_name
            self.rollout = None
            if self.params["record_rollouts"] and not dry:
                self.rollout = RolloutRecorder(self.store.root / "rollouts", rid, {
                    "contract": CONTRACT["version"],  # the Mac rejects packages from any other contract
                    "source": "nervus-act engine", "policy": self.policy_name, "params": dict(self.params),
                    "task": self.facts.get("task"), "dataset": self.facts.get("dataset"),
                    "joint_names": JOINTS, "cameras": [k.removeprefix("observation.images.") for k in self.policy.image_keys],
                    "units": {"joints": "deg", "gripper": "mm"}}, self.FPS)
            self.planner = Planner(self.policy)
            self.run = {"id": rid, "policy": self.policy_name, "steps": 0, "elapsed_s": 0.0, "starved": 0,
                        "skipped_avg": None, "overruns": 0, "stop_reason": None, "interventions": 0,
                        "recorded_frames": 0, "segment": "policy", "labelled": None, "dry_run": dry,
                        "params": dict(self.params)}
            self.last_heartbeat = mono()
            self.stop_flag.clear()
            self.correct_flag.clear()
            self.state, self.message = "running", "Running the policy." + (" DRY RUN: nothing is sent to the arm." if dry else "")
        if not dry:
            try:
                self.arm.speed(self.params["speed_pct"])
            except ArmError:
                pass
        threading.Thread(target=self._loop, daemon=True, name="control").start()

    def stop(self, reason: str = "stopped by operator"):
        with self.lock:
            if self.state in ("running", "moving"):
                self.stop_flag.set()
                if self.run is not None and self.state == "running":
                    self.run["stop_reason"] = self.run.get("stop_reason") or reason

    def estop(self):
        self.stop_flag.set()
        try:
            self.arm.estop()
        finally:
            with self.lock:
                self.message = "EMERGENCY STOP sent. Use Recover (Robot Arm, or System here) before enabling again."

    def correct(self):
        with self.lock:
            if self.state != "running":
                raise RuntimeError("not running")
            self.correct_flag.set()
            self.message = "Correction: press the arm's drag-teach button, finish the task by hand, then Stop and mark the result."

    def heartbeat(self):
        self.last_heartbeat = mono()

    def mark(self, outcome: str, case: str, note: str) -> str:
        if outcome not in ("success", "fail"):
            raise ValueError("outcome is success or fail")
        r = self.run
        if not r or not r.get("stop_reason"):
            raise RuntimeError("no finished run to mark")
        if r.get("dry_run"):
            raise RuntimeError("dry runs aren't trials")
        lat = [x * 1000 for x in self.planner.latencies] if self.planner else []
        first = self.store.add_trial({
            "run_id": r["id"], "policy": r["policy"], "case": case, "outcome": outcome,
            "duration_s": r["elapsed_s"], "steps": r["steps"], "stop_reason": r["stop_reason"],
            "interventions": r["interventions"], "latency_ms": round(sum(lat) / len(lat)) if lat else "",
            "note": note, "time": utc_now()})
        msg = f"Recorded {outcome} for {r['policy']}." if first else "Already recorded: the first label stands."
        rec = self.rollout
        if first and rec is not None and rec.meta.get("run_id") == r["id"]:
            if rec.finalize(outcome, case, note):
                r["labelled"] = outcome
                msg += " The run goes to post-training."
        self.message = msg
        return msg

    def set_params(self, upd: dict):
        with self.lock:
            for k, v in upd.items():
                if k not in LIMITS:
                    continue
                lo, hi, typ = LIMITS[k]
                self.params[k] = typ(min(hi, max(lo, float(v))))
            if self.policy:
                self.params["replan_every"] = min(self.params["replan_every"], self.policy.chunk_size)

    def status(self) -> dict:
        pl = self.planner
        lat = [x * 1000 for x in pl.latencies] if pl else []
        run = self.run or {}
        return {
            "state": self.state, "message": self.message, "policy": self.policy_name,
            "chunk_size": self.policy.chunk_size if self.policy else None,
            "cameras": [k.removeprefix("observation.images.") for k in self.policy.image_keys] if self.policy else [],
            "latency_ms": round(sum(lat) / len(lat)) if lat else (round(self.policy.last_ms) if self.policy and self.policy.last_ms else None),
            "params": self.params, "limits": {k: [v[0], v[1]] for k, v in LIMITS.items()},
            "run": {k: run.get(k) for k in ("id", "policy", "steps", "elapsed_s", "starved", "skipped_avg", "overruns",
                                            "stop_reason", "interventions", "recorded_frames", "segment", "labelled", "dry_run")},
            "target": self.target, "preview": self.preview_result,
            "start_pose": self.facts.get("start_pose"),
        }

    # ---- the loop ----
    def _loop(self):
        p, r, pl = self.params, self.run, self.planner
        dt = 1.0 / self.FPS
        logdir = self.store.root / "rollouts" / ".runs"
        logdir.mkdir(parents=True, exist_ok=True)
        log = open(logdir / f"{r['id']}.steps.jsonl", "w", encoding="utf-8")
        k, skipped, last_sent, grip, segment = 0, [], None, {}, "policy"
        rec = self.rollout
        stale_since = cam_stale_since = None
        reason = None
        t_start = mono()
        try:
            state, _ = self.arm.read()
            imgs, _ = self._images()
            if state is None or imgs is None:
                raise RuntimeError("lost the arm or a camera before the first plan")
            pl.request(0, [state[j] for j in JOINTS], imgs)
            while pl.ready is None and not self.stop_flag.is_set():
                if pl.error:
                    raise RuntimeError(pl.error)
                time.sleep(0.005)
            # The clock starts once the first plan exists, so the wait isn't counted as overruns.
            t_start = mono()
            next_t = t_start
            while not self.stop_flag.is_set():
                tick = mono()
                state, arm_age = self.arm.read()
                imgs, cam_age = self._images()
                if pl.error:
                    reason = pl.error
                    break
                # Intervention FIRST, before any stale check: the explicit Correct button is the reliable
                # trigger; the arm reporting drag-teach is the backup.
                if segment == "policy" and (self.correct_flag.is_set() or self.arm.teaching()):
                    asked = self.correct_flag.is_set()
                    self.correct_flag.clear()
                    if p["intervene_on_teach"]:
                        # Hand over: stop commanding, so the arm is never stiffened in the operator's hands.
                        segment = "human"
                        r["interventions"] += 1
                        r["intervened_at_s"] = round(tick - t_start, 2)
                        self.message = ("HUMAN CORRECTION recording: drag the arm to finish, then Stop and mark the result."
                                        if rec is not None else
                                        "Correction: the policy has let go (rollout recording is off, so nothing is recorded).")
                    elif not p["dry_run"]:
                        reason = "correction requested" if asked else "the arm entered drag-teach"
                        break
                r["segment"] = segment
                if tick - self.last_heartbeat > p["heartbeat_s"]:
                    reason = "the app stopped responding"
                    break
                if tick - t_start > p["max_duration_s"]:
                    reason = f"reached the {p['max_duration_s']} s limit"
                    break
                fault = self.arm.hard_fault()
                if fault and segment == "policy":
                    reason = fault
                    break
                arm_stale = state is None or arm_age * 1000 > p["stale_arm_ms"]
                if arm_stale:
                    stale_since = stale_since or tick
                    if segment == "policy" and tick - stale_since > p["stale_arm_grace_s"]:
                        reason = f"arm feedback stale for {tick - stale_since:.1f} s"
                        break
                else:
                    stale_since = None
                cam_stale = imgs is None or cam_age * 1000 > p["stale_camera_ms"]
                if cam_stale:
                    cam_stale_since = cam_stale_since or tick
                    if segment == "policy" and tick - cam_stale_since > p["stale_camera_grace_s"]:
                        reason = f"camera frames stale for {tick - cam_stale_since:.1f} s"
                        break
                else:
                    cam_stale_since = None

                raw, sent, gmode = None, None, "human"
                if segment == "policy":
                    if arm_stale or cam_stale:
                        gmode = "stale"  # hold the last command; never steer from a stale read
                    else:
                        sk = pl.integrate(k, p["crossfade"])
                        if sk is not None:
                            skipped.append(sk)
                        due = pl.last_obs_step is None or k - pl.last_obs_step >= p["replan_every"] or pl.remaining(k) <= 3
                        if due:
                            pl.request(k, [state[j] for j in JOINTS], imgs)
                        raw = pl.take(k, p["ensemble"])
                        if raw is None:
                            tgt = dict(last_sent or state)
                            gmode = "starved"
                        else:
                            tgt = self._clamp({j: float(raw[i]) for i, j in enumerate(JOINTS)}, state, last_sent)
                            tgt["gripper"], gmode = self._gripper(tgt["gripper"], state["gripper"], grip)
                        sent = tgt
                        if not p["dry_run"]:
                            self.arm.stream(sent, p["gripper_effort"])
                        last_sent = sent
                        self.target = sent
                t_rel = round(tick - t_start, 4)
                st_vec = None if state is None else [round(float(state[j]), 3) for j in JOINTS]
                info = self.arm.info()
                row = {"k": k, "t": t_rel, "segment": segment, "state": st_vec,
                       "sent": None if sent is None else [round(float(sent[j]), 3) for j in JOINTS],
                       "plan": None if raw is None else [round(float(x), 3) for x in raw], "grip": gmode,
                       "obs_step": pl.last_obs_step, "ens": pl.ens_n,
                       "cam_age_ms": None if math.isinf(cam_age) else round(cam_age * 1000),
                       "arm_age_ms": None if math.isinf(arm_age) else round(arm_age * 1000),
                       "st": [info.get("ctrl_mode"), info.get("arm_status")]}
                log.write(json.dumps(row) + "\n")
                if rec is not None and state is not None and imgs is not None:
                    rec.add(dict(row), imgs)
                    r["recorded_frames"] = rec.frames
                k += 1
                r.update(steps=k, elapsed_s=round(mono() - t_start, 2), starved=pl.starved,
                         skipped_avg=round(sum(skipped) / len(skipped), 1) if skipped else None)
                next_t += dt
                slack = next_t - mono()
                if slack > 0:
                    time.sleep(slack)
                else:
                    r["overruns"] += 1
                    next_t = mono()
        except Exception as e:
            reason = f"control loop error: {e}"
            traceback.print_exc()
        finally:
            log.close()
            try:
                if segment == "policy" and not p["dry_run"]:
                    hold, age = self.arm.read()
                    if hold is not None and age < 0.5:
                        # Freeze where the arm IS, with a light grip from the measured width; torque stays on.
                        if hold["gripper"] < 0.95 * self.gripper_max():
                            hold["gripper"] = max(0.0, hold["gripper"] - p["gripper_squeeze_mm"])
                        self.arm.stream(hold, p["gripper_effort"])
                        self.target = hold
                    self.arm.release()
                # In a human segment the operator holds the arm in drag-teach: send nothing, or a mode
                # command would pull it out of drag-teach and stiffen it in their hands.
            except Exception:
                pass
            r["stop_reason"] = r.get("stop_reason") or reason or "stopped"
            lat = [x * 1000 for x in pl.latencies]
            if rec is not None:
                try:
                    rec.close({"steps": r["steps"], "elapsed_s": r["elapsed_s"], "stop_reason": r["stop_reason"],
                               "interventions": r["interventions"], "intervened_at_s": r.get("intervened_at_s"),
                               "latency_ms_mean": round(sum(lat) / len(lat)) if lat else None,
                               "overruns": r["overruns"]})
                    r["recorded_frames"] = rec.frames
                except Exception as e:
                    print(f"rollout close failed: {e}", flush=True)
            held = "The arm is free (drag-teach)." if segment == "human" else "Holding position."
            with self.lock:
                self.state = "stopped"
                self.message = (f"Run ended: {r['stop_reason']}. {held} "
                                + ("Mark Success or Fail." if not r.get("dry_run") else ""))
            self.stop_flag.clear()

    def close(self):
        self.stop("engine shutting down")
        if self.policy:
            self.policy.close()
