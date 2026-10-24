"""Building the dataset a job trains on, from the synced recordings plus their edits.

The synced datasets are never modified. Training reads a local copy (in the work directory, not
synced) with each episode's edits applied: excluded episodes left out, manual trims honoured, and
otherwise the idle stillness before the first motion and after the last trimmed off (see
nervus_act/datasets.py on the Dell for why that matters to ACT).

Video is re-encoded as h264 with crf 18: LeRobot's default crf 30 lost 3.8/255 on average, visible in
the gate's frame checks.
"""
from __future__ import annotations

import glob
import json
import shutil
import time
from pathlib import Path

import numpy as np

JOINTS = ["joint1", "joint2", "joint3", "joint4", "joint5", "joint6", "gripper"]
VCODEC = "h264"
CRF = 18


def read_json(p: Path, default=None):
    try:
        return json.loads(Path(p).read_text())
    except Exception:
        return default


def idle_bounds(states: np.ndarray, threshold: float, keep_before: int, keep_after: int) -> tuple[int, int]:
    """Same rule as the Dell's Data page shows (nervus_act/datasets.py), so what you see is what trains."""
    if len(states) < 2:
        return 0, len(states)
    thr = np.array([threshold] * 6 + [2.0])
    moving_lead = (np.abs(states - states[0]) > thr).any(axis=1)
    moving_tail = (np.abs(states - states[-1]) > thr).any(axis=1)
    start = max(0, int(np.argmax(moving_lead)) - keep_before) if moving_lead.any() else 0
    end = min(len(states), len(states) - int(np.argmax(moving_tail[::-1])) + keep_after) if moving_tail.any() else len(states)
    return start, max(start + 1, end)


def frames_table(root: Path):
    import pandas as pd
    files = sorted(glob.glob(str(root / "data" / "*" / "*.parquet")))
    df = pd.concat([pd.read_parquet(f) for f in files])
    return df.sort_values("index")


def plan_episodes(root: Path) -> list[dict]:
    """[{episode, start, end}] for one source dataset after edits."""
    edits = {"auto_trim": True, "keep_before": 5, "keep_after": 5, "threshold_deg": 1.0, "episodes": {}}
    edits.update(read_json(root / "nervus_edits.json", {}) or {})
    df = frames_table(root)
    out = []
    for ep, g in df.groupby("episode_index"):
        man = edits["episodes"].get(str(int(ep)), {})
        if man.get("exclude"):
            continue
        S = np.stack(g["observation.state"].values).astype(np.float64)
        if "start" in man or "end" in man:
            s0, s1 = int(man.get("start", 0)), int(man.get("end", len(S)))
        elif edits["auto_trim"]:
            s0, s1 = idle_bounds(S, edits["threshold_deg"], edits["keep_before"], edits["keep_after"])
        else:
            s0, s1 = 0, len(S)
        s0, s1 = max(0, s0), min(len(S), s1)
        if s1 - s0 >= 15:  # at least one second left
            out.append({"episode": int(ep), "start": s0, "end": s1, "frames": len(S)})
    return out


def features_of(root: Path) -> dict:
    info = json.loads((root / "meta" / "info.json").read_text())
    out = {}
    for k, f in info["features"].items():
        if k in ("observation.state", "action") or k.startswith("observation.images."):
            f = dict(f)
            f["shape"] = tuple(f["shape"])  # info.json has lists; LeRobot's frame validation wants tuples
            f.pop("info", None)             # codec info is regenerated on encode
            out[k] = f
    return out


def _quality(ds):
    enc = getattr(ds, "_streaming_encoder", None)
    if enc is not None:
        enc.crf = CRF
    return ds


def to_uint8_hwc(chw):
    import torch
    return torch.round(chw.clamp(0, 1) * 255).to(torch.uint8).permute(1, 2, 0).contiguous().numpy()


class Cancelled(Exception):
    """The operator cancelled the job while its dataset was being built."""


def build(sources: list[Path], dest: Path, repo_id: str, log=print, extra_episodes=None,
          features_from: Path | None = None, cancel=None) -> dict:
    """Write the training dataset to dest (atomically: dest.partial, then rename).

    extra_episodes: optional iterable of (states (T+1, 7), frames, task) appended after the sources
    (post-training rounds use it for converted rollouts). frames yields {camera: RGB frame} per state row,
    decoded as it goes: a long rollout held in memory would be a gigabyte per camera.
    features_from: the dataset whose features to use when sources is empty (a set of rollouts only).
    cancel: callable checked between episodes; when it returns True the build stops with Cancelled."""
    from lerobot.datasets.lerobot_dataset import LeRobotDataset
    if dest.exists():
        shutil.rmtree(dest)
    tmp = dest.with_name(dest.name + ".partial")
    shutil.rmtree(tmp, ignore_errors=True)
    ref = sources[0] if sources else features_from
    if ref is None:
        raise ValueError("build needs a source dataset or features_from")
    feats = features_of(ref)
    fps = json.loads((ref / "meta" / "info.json").read_text())["fps"]
    cams = [k.removeprefix("observation.images.") for k in feats if k.startswith("observation.images.")]
    out = LeRobotDataset.create(repo_id=repo_id, fps=fps, features=feats, root=tmp, robot_type="agilex_piper",
                                use_videos=True, vcodec=VCODEC, streaming_encoding=True)
    _quality(out)
    report, t0 = [], time.time()
    for src in sources:
        name = src.name
        plan = plan_episodes(src)
        ds = LeRobotDataset(f"local/{name}", root=src)
        ranges = {int(e["episode_index"]): (int(e["dataset_from_index"]), int(e["dataset_to_index"])) for e in ds.meta.episodes}
        task = (read_json(src / "nervus_dataset.json", {}) or {}).get("task") or "demonstration"
        for p in plan:
            if cancel and cancel():
                raise Cancelled()
            lo, _hi = ranges[p["episode"]]
            for gi in range(lo + p["start"], lo + p["end"]):
                x = ds[gi]
                frame = {"observation.state": x["observation.state"].numpy(), "action": x["action"].numpy(), "task": task}
                for c in cams:
                    frame[f"observation.images.{c}"] = to_uint8_hwc(x[f"observation.images.{c}"])
                out.add_frame(frame)
            out.save_episode()
            report.append({"source": name, **p, "kept": p["end"] - p["start"]})
            log(f"build: {name} ep {p['episode']}: kept {p['start']}..{p['end']} of {p['frames']} ({time.time() - t0:.0f} s)")
    for states, frames, task in (extra_episodes or []):
        if cancel and cancel():
            raise Cancelled()
        states = np.asarray(states, dtype=np.float32)
        written = 0
        for t, imgs in zip(range(len(states) - 1), frames):
            frame = {"observation.state": states[t], "action": states[t + 1], "task": task}
            for c in cams:
                frame[f"observation.images.{c}"] = imgs[c]
            out.add_frame(frame)
            written += 1
        if written:
            out.save_episode()
            report.append({"source": "rollout", "kept": written})
    if not report:
        shutil.rmtree(tmp, ignore_errors=True)
        raise ValueError("nothing to train on: every episode was excluded or is shorter than one second after trimming")
    out.finalize()
    tmp.rename(dest)
    summary = {"episodes": len(report), "frames": int(sum(r["kept"] for r in report)), "fps": fps, "cameras": cams,
               "report": report, "seconds": round(time.time() - t0, 1)}
    (dest / "BUILD_REPORT.json").write_text(json.dumps(summary, indent=2))
    return summary


def facts(root: Path) -> dict:
    """What the robot side needs to run a policy trained on this data: the demonstrated envelope, the
    99th percentile of each joint's per-step change (the runner's step limit is a multiple of it), and the
    typical start pose."""
    df = frames_table(root)
    S = np.stack(df["observation.state"].values).astype(np.float64)
    A = np.stack(df["action"].values).astype(np.float64)
    starts = np.stack([np.stack(g["observation.state"].values)[0] for _, g in df.groupby("episode_index")])
    return {
        "envelope": {j: [float(S[:, i].min()), float(S[:, i].max())] for i, j in enumerate(JOINTS)},
        "step_p99": {j: float(np.percentile(np.abs(A[:, i] - S[:, i]), 99)) for i, j in enumerate(JOINTS)},
        "start_pose": {j: float(np.median(starts[:, i])) for i, j in enumerate(JOINTS)},
    }


def episode_samples(root: Path, repo_id: str, episodes: list[int], stride: int = 15):
    """(episode, t, state, {camera: image}, actions[t:]) every `stride` frames: the offline gate's samples."""
    from lerobot.datasets.lerobot_dataset import LeRobotDataset
    ds = LeRobotDataset(repo_id, root=root)
    cams = [k for k in ds.features if k.startswith("observation.images.")]
    ranges = {int(e["episode_index"]): (int(e["dataset_from_index"]), int(e["dataset_to_index"])) for e in ds.meta.episodes}
    for e in episodes:
        lo, hi = ranges[e]
        cols = ds.hf_dataset.select(range(lo, hi)).with_format("numpy")
        S = np.stack(cols["observation.state"])
        A = np.stack(cols["action"])
        for t in range(0, hi - lo, stride):
            x = ds[lo + t]
            yield e, t, S[t], {k.removeprefix("observation.images."): to_uint8_hwc(x[k]) for k in cams}, A[t:]
