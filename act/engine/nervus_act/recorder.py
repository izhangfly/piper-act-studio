"""Demonstration recording: drag-teach the arm while both cameras film it, into a LeRobot v3 dataset.

Carried over from the Piper-ACT recorder, every rule below was learned the hard way:

* action[t] = state[t+1]: a single arm has no leader, so the action is "where the arm is next". The
  loop therefore lags one tick and drops each episode's last frame.
* finalize() and reopen after every episode. LeRobot buffers episode metadata in memory and writes
  the parquet footer only on finalize, so a crash mid-session used to lose every episode recorded
  so far. Reopening in resume mode advances the file index, so nothing is overwritten, and what
  Syncthing carries to the Mac is always a complete dataset.
* vcodec is passed on every reopen: LeRobotDataset() defaults to AV1, and episodes 1-49 of the first
  dataset silently became AV1 that way.
* Encoding runs in-process (parallel_encoding=False); a process per camera re-imports torch each time.
* Each camera has its own thread and the loop takes the newest frame, so a slow camera repeats
  frames instead of slowing the recording; but a camera below min_cam_fps refuses to record, and
  one that collapses mid-take aborts it, rather than recording 14 of 15 frames as duplicates.
* One dropped read is skipped; only a gap longer than max_gap_s throws the take away.
* A dataset's camera set is fixed: appending episodes with different image keys would hand the Mac
  a dataset it can't train on.
* Cartesian traces and thumbnails are sidecar files, never LeRobot features: an unexpected feature
  key would change what the policy sees.
"""
from __future__ import annotations

import json
import shutil
import threading
import time
import traceback
from pathlib import Path

import numpy as np

from .hardware import JOINTS, Arm, Camera, mono
from .store import CONTRACT, Store, read_json, safe_name, utc_now, write_json


class Recorder:
    def __init__(self, store: Store, arm: Arm, cameras: dict[str, Camera], fps: int = 15,
                 min_frames: int = 30, min_cam_fps: float = 5.0, max_gap_s: float = 0.5, vcodec: str = "h264"):
        self.store, self.arm, self.cameras = store, arm, cameras
        self.fps, self.min_frames, self.min_cam_fps, self.max_gap_s, self.vcodec = fps, min_frames, min_cam_fps, max_gap_s, vcodec
        self.state = "closed"        # closed | opening | idle | recording | saving | error
        self.message = "Open or create a dataset to record."
        self.dataset = None
        self.name: str | None = None
        self.task = ""
        self.cams: list[str] = []
        self.ep_frames = 0
        self.saved = 0
        self.loop_fps = 0.0
        self.lock = threading.RLock()
        self._want_start = threading.Event()
        self._want_stop = threading.Event()
        self._discard = False
        self._stop_thread = threading.Event()
        self._thread: threading.Thread | None = None

    # ---- dataset ----
    def _features(self, cams: list[str]) -> dict:
        f = {
            "observation.state": {"dtype": "float32", "shape": (len(JOINTS),), "names": list(JOINTS)},
            "action": {"dtype": "float32", "shape": (len(JOINTS),), "names": list(JOINTS)},
        }
        for c in cams:
            cam = self.cameras[c]
            f[f"observation.images.{c}"] = {"dtype": "video", "shape": (cam.h, cam.w, 3),
                                            "names": ["height", "width", "channels"]}
        return f

    def _open_dataset(self, name: str, cams: list[str]):
        from lerobot.datasets.lerobot_dataset import LeRobotDataset
        root = self.store.dataset_dir(name)
        repo_id = f"local/{name}"
        ep_dir = root / "meta" / "episodes"
        exists = ep_dir.exists() and any(ep_dir.rglob("*.parquet"))
        if not exists and (root / "meta" / "info.json").exists():
            # A run killed before its first saved episode leaves a stub. Resuming it falls through to the
            # Hugging Face Hub and dies on a 401, so scrap it, but only if it holds no recorded frames.
            if any((root / "data").rglob("*.parquet")) or any((root / "videos").rglob("*.mp4")):
                raise RuntimeError(f"{root} holds recorded data but no episode metadata (killed before finalize). "
                                   "Move it aside and start a new dataset rather than lose it silently.")
            shutil.rmtree(root)
        threads = 4 * max(1, len(cams))
        if exists:
            ds = LeRobotDataset(repo_id, root=root, vcodec=self.vcodec)
            have = sorted(k.removeprefix("observation.images.") for k in ds.features if k.startswith("observation.images."))
            if have != sorted(cams):
                raise RuntimeError(f"dataset '{name}' was recorded with cameras {have}; this session has {sorted(cams)}. "
                                   "Use a new dataset name for a different camera set.")
            ds.start_image_writer(num_processes=0, num_threads=threads)
        else:
            ds = LeRobotDataset.create(repo_id, fps=self.fps, features=self._features(cams), root=root,
                                       robot_type="agilex_piper", use_videos=True, image_writer_processes=0,
                                       image_writer_threads=threads, vcodec=self.vcodec)
        return ds

    def open(self, name: str, task: str, cams: list[str]):
        name = safe_name(name)
        if not task.strip():
            raise ValueError("describe the task in one sentence (it is stored with every frame)")
        for c in cams:
            if c not in self.cameras:
                raise ValueError(f"unknown camera role '{c}'")
        if not cams:
            raise ValueError("record with at least one camera")
        with self.lock:
            if self.state in ("recording", "saving", "opening"):
                raise RuntimeError(f"recorder is {self.state}")
            self.state, self.message = "opening", f"Opening dataset {name}…"
        try:
            ds = self._open_dataset(name, cams)
        except Exception as e:
            with self.lock:
                self.state, self.message = "error", f"{e}"
            raise
        root = self.store.dataset_dir(name)
        meta = read_json(root / "nervus_dataset.json", {})
        meta.update({"name": name, "task": task.strip(), "cameras": sorted(cams), "fps": self.fps,
                     "units": CONTRACT["units"], "joint_names": JOINTS, "recorder": "nervus-act engine",
                     "camera_devices": {c: self.cameras[c].by_id for c in cams}})
        meta.setdefault("created", utc_now())
        write_json(root / "nervus_dataset.json", meta)
        with self.lock:
            self.dataset, self.name, self.task, self.cams = ds, name, task.strip(), sorted(cams)
            self.saved = ds.meta.total_episodes
            self.state = "idle"
            self.message = (f"Dataset '{name}' ready: {self.saved} episode(s). Press the drag-teach button on the arm, "
                            "then Start, demonstrate, Stop.")
        if self._thread is None or not self._thread.is_alive():
            self._stop_thread.clear()
            self._thread = threading.Thread(target=self._run, daemon=True, name="recorder")
            self._thread.start()

    def close(self):
        with self.lock:
            if self.state in ("recording", "saving"):
                raise RuntimeError("stop the take first")
        self._stop_thread.set()
        if self._thread:
            self._thread.join(timeout=60)
        with self.lock:
            ds, self.dataset = self.dataset, None
            self.state, self.message, self.name = "closed", "Dataset closed.", None
        if ds is not None:
            try:
                ds.finalize()
            except Exception:
                traceback.print_exc()

    def start(self):
        with self.lock:
            if self.state != "idle":
                raise RuntimeError(f"cannot start while {self.state}")
        problem = self._camera_problem()
        if problem:
            raise RuntimeError(problem)
        _, age = self.arm.read()
        if age > 0.3:
            raise RuntimeError("no live feedback from the arm. If it is idle (standby it stops reporting), press its "
                               "drag-teach button once so the light is on")
        self._want_start.set()

    def stop(self, discard: bool = False):
        with self.lock:
            if self.state != "recording":
                raise RuntimeError("not recording")
            self._discard = discard
        self._want_stop.set()

    def _camera_problem(self) -> str | None:
        for c in self.cams:
            cam = self.cameras[c]
            if cam.error:
                return f"{c} camera: {cam.error}"
            if cam.fps() < self.min_cam_fps:
                return (f"{c} camera is delivering {cam.fps():.1f} fps (< {self.min_cam_fps:g}). Fix it before "
                        "recording: plug it straight into the laptop, add light")
        return None

    def status(self) -> dict:
        with self.lock:
            return {"state": self.state, "message": self.message, "dataset": self.name, "task": self.task,
                    "cameras": self.cams, "episode_frames": self.ep_frames,
                    "episode_seconds": round(self.ep_frames / self.fps, 1), "saved_episodes": self.saved,
                    "loop_fps": round(self.loop_fps, 1), "teaching": self.arm.teaching()}

    # ---- sidecars ----
    def _write_sidecars(self, episode_index: int, trace: list[dict], thumbs: dict[str, list[np.ndarray]]):
        import cv2
        root = self.store.dataset_dir(self.name)
        (root / "traces").mkdir(exist_ok=True)
        with open(root / "traces" / f"episode_{episode_index:06d}.jsonl", "w", encoding="utf-8") as f:
            for row in trace:
                f.write(json.dumps(row) + "\n")
        tdir = root / "thumbs"
        tdir.mkdir(exist_ok=True)
        for cam, frames in thumbs.items():
            for k, img in enumerate(frames):
                small = cv2.resize(img, (320, 240))
                cv2.imwrite(str(tdir / f"episode_{episode_index:06d}_{cam}_{k}.jpg"),
                            cv2.cvtColor(small, cv2.COLOR_RGB2BGR), [int(cv2.IMWRITE_JPEG_QUALITY), 80])

    # ---- loop ----
    def _frames(self) -> dict[str, np.ndarray] | None:
        out = {}
        for c in self.cams:
            img, age = self.cameras[c].latest()
            if img is None or age > 0.5:
                return None
            out[c] = img
        return out

    def _run(self):
        period = 1.0 / self.fps
        prev = None            # (state vec, frames, pose) waiting for its action
        gap = 0
        trace: list[dict] = []
        snaps: dict[str, list[np.ndarray]] = {}
        next_tick = time.perf_counter()
        n_fps, t_fps = 0, mono()
        while not self._stop_thread.is_set():
            next_tick += period
            sl = next_tick - time.perf_counter()
            if sl > 0:
                time.sleep(sl)
            else:
                next_tick = time.perf_counter()
            n_fps += 1
            if mono() - t_fps >= 1.0:
                self.loop_fps, n_fps, t_fps = n_fps / (mono() - t_fps), 0, mono()

            if self._want_start.is_set():
                self._want_start.clear()
                self._discard = False
                # Every buffer that belongs to a take is reset HERE, the one place all takes pass through.
                prev, gap, trace, snaps = None, 0, [], {c: [] for c in self.cams}
                with self.lock:
                    self.ep_frames, self.state, self.message = 0, "recording", "Recording… drag the arm through the task."

            if self.state == "recording":
                state, age = self.arm.read()
                frames = self._frames()
                if state is None or age > 0.3 or frames is None:
                    gap += 1
                    if gap * period < self.max_gap_s:
                        continue
                    self._abort(f"lost the arm or a camera for {gap * period:.1f} s; episode discarded")
                    prev, gap = None, 0
                    continue
                problem = self._camera_problem()
                if problem:
                    self._abort(f"{problem}; episode discarded")
                    prev = None
                    continue
                gap = 0
                vec = np.array([state[j] for j in JOINTS], dtype=np.float32)
                if prev is not None:
                    frame = {"observation.state": prev[0], "action": vec, "task": self.task}
                    for c, img in prev[1].items():
                        frame[f"observation.images.{c}"] = img
                    try:
                        self.dataset.add_frame(frame)
                    except Exception as e:
                        traceback.print_exc()
                        self._abort(f"add_frame failed: {e}")
                        prev = None
                        continue
                    i = self.ep_frames
                    pose = prev[2]
                    trace.append({"frame_index": i, "joints": [round(float(v), 3) for v in prev[0]],
                                  "pose": [round(float(v), 2) for v in pose] if pose else None})
                    if i == 0:
                        for c, img in prev[1].items():
                            snaps[c].append(img)
                    with self.lock:
                        self.ep_frames += 1
                prev = (vec, frames, self.arm.end_pose())

            if self._want_stop.is_set():
                self._want_stop.clear()
                discard, self._discard = self._discard, False
                if self.state != "recording":
                    continue  # the take was already aborted (lost a camera or the arm): keep that message
                n = self.ep_frames
                with self.lock:
                    self.state, self.message = "saving", "Saving episode…"
                last_frames = prev[1] if prev else None
                prev = None
                try:
                    if discard or n < self.min_frames:
                        self.dataset.clear_episode_buffer()
                        msg = (f"Discarded ({n} frames)." if discard else
                               f"Discarded: only {n} frames, need at least {self.min_frames} ({self.min_frames / self.fps:.0f} s).")
                    else:
                        t0 = time.time()
                        self.dataset.save_episode(parallel_encoding=False)
                        self.dataset.finalize()
                        self.dataset = self._open_dataset(self.name, self.cams)
                        ep = self.dataset.meta.total_episodes - 1
                        if last_frames:
                            for c, img in last_frames.items():
                                snaps.setdefault(c, []).append(img)
                        self._write_sidecars(ep, trace, snaps)
                        with self.lock:
                            self.saved = self.dataset.meta.total_episodes
                        msg = f"Saved episode {self.saved}: {n} frames ({n / self.fps:.1f} s) in {time.time() - t0:.1f} s."
                        joints = np.array([row["joints"] for row in trace], dtype=np.float64) if trace else np.zeros((0, 7))
                        moved = float(np.abs(np.diff(joints[:, :6], axis=0)).sum()) if len(joints) > 1 else 0.0
                        if moved < 5.0:
                            # A stiff arm (teach mode off) records a take of nothing; it would teach "stand still".
                            msg += (f" It barely moved ({moved:.0f}° in total): was the arm in teach mode? "
                                    "Exclude it on the Data tab if it is not a real demonstration.")
                except Exception as e:
                    traceback.print_exc()
                    msg = f"Save failed: {e}"
                with self.lock:
                    self.ep_frames, self.state, self.message = 0, "idle", msg
                next_tick = time.perf_counter()

    def _abort(self, msg: str):
        try:
            self.dataset.clear_episode_buffer()
        except Exception:
            pass
        with self.lock:
            self.state, self.message, self.ep_frames = "idle", msg, 0
