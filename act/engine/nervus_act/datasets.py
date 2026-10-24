"""Looking at and editing recorded datasets without rewriting them.

Edits are a sidecar, nervus_edits.json, that the Mac applies when it builds the training set:

    {"auto_trim": true, "keep_before": 5, "threshold_deg": 1.0,
     "episodes": {"3": {"exclude": true}, "7": {"start": 40, "end": 610}}}

auto_trim removes the stillness before an episode's first motion (keeping keep_before frames) and after
its last. That rule came out of the first real runs: 18 of 50 demonstrations waited more than 2 s
before moving, action = state for every one of those frames, so ACT learned "wait here", and at the
start pose it waited forever. ACT has no clock to escape that with. Trimming fixed the frozen start.
"""
from __future__ import annotations

import glob
from pathlib import Path

import numpy as np

from .hardware import JOINTS
from .store import Store, read_json, safe_name, write_json

DEFAULT_EDITS = {"auto_trim": True, "keep_before": 5, "keep_after": 5, "threshold_deg": 1.0, "episodes": {}}


def _frames(root: Path):
    import pandas as pd
    files = sorted(glob.glob(str(root / "data" / "*" / "*.parquet")))
    if not files:
        return None
    df = pd.concat([pd.read_parquet(f, columns=["observation.state", "action", "episode_index", "frame_index", "index"])
                    for f in files])
    return df.sort_values("index")


def idle_bounds(states: np.ndarray, threshold: float, keep_before: int, keep_after: int) -> tuple[int, int]:
    """[start, end) after removing leading and trailing stillness. A frame counts as moving when any arm
    joint is more than `threshold` degrees from where the episode started (or ended), or the gripper
    has moved more than 2 mm."""
    if len(states) < 2:
        return 0, len(states)
    thr = np.array([threshold] * 6 + [2.0])
    lead = np.abs(states - states[0]) > thr
    tail = np.abs(states - states[-1]) > thr
    moving_lead = lead.any(axis=1)
    moving_tail = tail.any(axis=1)
    start = max(0, int(np.argmax(moving_lead)) - keep_before) if moving_lead.any() else 0
    end = min(len(states), len(states) - int(np.argmax(moving_tail[::-1])) + keep_after) if moving_tail.any() else len(states)
    return start, max(start + 1, end)


class Datasets:
    def __init__(self, store: Store):
        self.store = store

    def edits(self, name: str) -> dict:
        e = read_json(self.store.dataset_dir(name) / "nervus_edits.json")
        out = dict(DEFAULT_EDITS)
        if e:
            out.update(e)
        return out

    def set_edits(self, name: str, upd: dict) -> dict:
        root = self.store.dataset_dir(name)
        if not (root / "meta" / "info.json").exists():
            raise ValueError(f"no dataset '{name}'")
        e = self.edits(name)
        bounds = {"keep_before": (0, 150), "keep_after": (0, 150), "threshold_deg": (0.1, 30.0)}
        for k in ("auto_trim", "keep_before", "keep_after", "threshold_deg"):
            if k in upd:
                v = type(DEFAULT_EDITS[k])(upd[k])
                if k in bounds:
                    lo, hi = bounds[k]
                    v = type(v)(min(hi, max(lo, v)))
                e[k] = v
        for ep, v in (upd.get("episodes") or {}).items():
            ep = str(int(ep))
            cur = dict(e["episodes"].get(ep, {}))
            for k in ("exclude", "start", "end"):
                if k in v:
                    cur[k] = v[k] if v[k] is None else (bool(v[k]) if k == "exclude" else max(0, int(v[k])))
            if cur.get("start") is not None and cur.get("end") is not None and cur["end"] <= cur["start"]:
                raise ValueError(f"episode {ep}: the trim end must be after its start")
            cur = {k: v for k, v in cur.items() if v is not None and v is not False}  # 0 is a real trim
            if cur:
                e["episodes"][ep] = cur
            else:
                e["episodes"].pop(ep, None)
        write_json(root / "nervus_edits.json", e)
        return e

    def episodes(self, name: str) -> dict:
        """Per-episode facts for the Data page: length, how much it moved, the idle trim the Mac will
        apply, and any manual edit."""
        root = self.store.dataset_dir(safe_name(name))
        info = read_json(root / "meta" / "info.json")
        if info is None:
            raise ValueError(f"no dataset '{name}'")
        df = _frames(root)
        e = self.edits(name)
        meta = read_json(root / "nervus_dataset.json", {})
        out = []
        if df is not None:
            for ep, g in df.groupby("episode_index"):
                S = np.stack(g["observation.state"].values).astype(np.float64)
                A = np.stack(g["action"].values).astype(np.float64)
                s0, s1 = idle_bounds(S, e["threshold_deg"], e["keep_before"], e["keep_after"])
                manual = e["episodes"].get(str(int(ep)), {})
                shift_ok = bool(len(S) < 2 or np.allclose(A[:-1], S[1:], atol=1e-4))
                out.append({
                    "episode": int(ep), "frames": int(len(S)), "seconds": round(len(S) / info["fps"], 1),
                    "travel_deg": round(float(np.abs(np.diff(S[:, :6], axis=0)).sum()), 1),
                    "gripper_range_mm": [round(float(S[:, 6].min()), 1), round(float(S[:, 6].max()), 1)],
                    "auto_trim": [s0, s1], "idle_lead_s": round(s0 / info["fps"], 1),
                    "idle_tail_s": round((len(S) - s1) / info["fps"], 1),
                    "exclude": bool(manual.get("exclude")), "manual": manual or None,
                    "action_shift_ok": shift_ok,
                    "thumbs": sorted(p.name for p in (root / "thumbs").glob(f"episode_{int(ep):06d}_*.jpg")),
                })
        return {"name": name, "fps": info["fps"], "task": meta.get("task"), "cameras": meta.get("cameras"),
                "edits": e, "episodes": out}

    def curve(self, name: str, episode: int, points: int = 300) -> dict:
        """One episode's joint trajectories, downsampled, for the trim editor."""
        root = self.store.dataset_dir(safe_name(name))
        df = _frames(root)
        if df is None:
            raise ValueError("no frames")
        g = df[df["episode_index"] == int(episode)]
        if g.empty:
            raise ValueError(f"no episode {episode}")
        S = np.stack(g["observation.state"].values)
        idx = np.linspace(0, len(S) - 1, min(points, len(S))).astype(int)
        return {"episode": int(episode), "frames": int(len(S)), "index": idx.tolist(),
                "joints": {j: [round(float(v), 2) for v in S[idx, i]] for i, j in enumerate(JOINTS)}}

    def stats(self, name: str) -> dict:
        """Envelope, per-step change and the typical start pose: what the policy runner needs to clamp
        commands to what people demonstrated, used when a policy has no nervus_policy.json of its own."""
        root = self.store.dataset_dir(safe_name(name))
        df = _frames(root)
        if df is None:
            raise ValueError("no frames")
        S = np.stack(df["observation.state"].values).astype(np.float64)
        A = np.stack(df["action"].values).astype(np.float64)
        starts = np.stack([np.stack(g["observation.state"].values)[0] for _, g in df.groupby("episode_index")])
        return {
            "envelope": {j: [float(S[:, i].min()), float(S[:, i].max())] for i, j in enumerate(JOINTS)},
            "step_p99": {j: float(np.percentile(np.abs(A[:, i] - S[:, i]), 99)) for i, j in enumerate(JOINTS)},
            "start_pose": {j: float(np.median(starts[:, i])) for i, j in enumerate(JOINTS)},
        }
