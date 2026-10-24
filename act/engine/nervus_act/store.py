"""The synced folder shared by the Dell (engine) and the Mac (worker): ~/Shared/NervusACT.

Syncthing carries it both ways: directly over the LAN when the two are together, and through the
relay on the Hetzner server when they aren't. Every file has exactly one writer, so the two machines
never edit the same file (a Syncthing conflict would silently fork it):

    CONTRACT.json                 both read; changed only together with both programs
    datasets/<name>/              Dell   LeRobot v3 dataset + nervus_dataset.json, thumbs/, traces/
    datasets/<name>/nervus_edits.json   Dell (trims and exclusions; never rewrites the video)
    jobs/<id>/job.json            Dell   one training request, immutable once written
    jobs/<id>/CANCEL              Dell   ask the Mac to stop that job
    jobs/<id>/status.json         Mac    progress, metrics, published checkpoints
    jobs/<id>/metrics.jsonl       Mac    one line per logged training step
    policies/<name>/              Mac    pretrained_model files + nervus_policy.json (atomic rename)
    policies/CURRENT.json         Mac    the promoted policy
    rollouts/<run_id>/            Dell   a policy run: episode.jsonl, <camera>.mp4, meta.json; READY last
    trials/results.csv            Dell   one row per labelled run
    posttrain/STATUS.json         Mac    post-training rounds
    posttrain/REQUEST             Dell   "start a round now"
    workers/mac.json              Mac    heartbeat: alive, busy with what
"""
from __future__ import annotations

import csv
import json
import os
import re
import time
from datetime import datetime, timezone
from pathlib import Path

CONTRACT = {
    "contract": "nervus-act",
    "version": 1,
    "units": {"joints": "deg", "gripper": "mm"},
    "joint_names": ["joint1", "joint2", "joint3", "joint4", "joint5", "joint6", "gripper"],
    "action": "action[t] = state[t+1] (the next measured state)",
    "fps": 15,
}

NAME_RE = re.compile(r"^[a-z0-9][a-z0-9_\-]{0,62}$")


def utc_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def write_json(path: Path, obj) -> None:
    """Atomic: write a temp file and rename, so Syncthing never ships half a file."""
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(f".{path.name}.tmp")
    tmp.write_text(json.dumps(obj, indent=2), encoding="utf-8")
    os.replace(tmp, path)


def read_json(path: Path, default=None):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return default


def safe_name(name: str) -> str:
    """Names come from the app (unauthenticated loopback): never let one escape its directory."""
    if not NAME_RE.match(name or ""):
        raise ValueError("names are lowercase letters, digits, '_' and '-', up to 63 characters")
    return name


class Store:
    def __init__(self, root: Path):
        self.root = Path(root).expanduser()
        for d in ("datasets", "jobs", "policies", "rollouts", "trials", "posttrain", "workers"):
            (self.root / d).mkdir(parents=True, exist_ok=True)
        if read_json(self.root / "CONTRACT.json") is None:
            write_json(self.root / "CONTRACT.json", CONTRACT)
        readme = self.root / "README.md"
        if not readme.exists():
            readme.write_text(__doc__ or "", encoding="utf-8")

    # ---- paths ----
    def dataset_dir(self, name: str) -> Path:
        return self.root / "datasets" / safe_name(name)

    def policy_dir(self, name: str) -> Path:
        return self.root / "policies" / safe_name(name)

    def job_dir(self, job_id: str) -> Path:
        return self.root / "jobs" / safe_name(job_id)

    # ---- datasets ----
    def datasets(self) -> list[dict]:
        out = []
        for d in sorted((self.root / "datasets").iterdir()):
            info = read_json(d / "meta" / "info.json")
            if not d.is_dir() or info is None or d.name.startswith("."):
                continue
            meta = read_json(d / "nervus_dataset.json", {})
            cams = sorted(k.removeprefix("observation.images.") for k in info.get("features", {})
                          if k.startswith("observation.images."))
            out.append({
                "name": d.name, "episodes": info.get("total_episodes", 0), "frames": info.get("total_frames", 0),
                "fps": info.get("fps"), "cameras": cams, "task": meta.get("task"), "created": meta.get("created"),
                "units": meta.get("units"), "edits": bool(read_json(d / "nervus_edits.json")),
                "seconds": round(info.get("total_frames", 0) / max(1, info.get("fps") or 15), 1),
            })
        return out

    # ---- policies ----
    # Everything LeRobot needs to load a policy, plus the facts the runner uses. Syncthing delivers a policy file
    # by file, so the folder can appear on the Dell before its last file does.
    POLICY_FILES = ("config.json", "model.safetensors", "nervus_policy.json",
                    "policy_preprocessor.json", "policy_postprocessor.json")

    def policy_missing(self, d: Path) -> list[str]:
        missing = [f for f in self.POLICY_FILES if not (d / f).is_file()]
        for pj in ("policy_preprocessor.json", "policy_postprocessor.json"):
            for step in (read_json(d / pj, {}) or {}).get("steps", []):
                sf = step.get("state_file")
                if sf and not (d / sf).is_file():
                    missing.append(sf)
        return missing

    def policies(self) -> list[dict]:
        cur = (read_json(self.root / "policies" / "CURRENT.json") or {}).get("name")
        out = []
        for d in sorted((self.root / "policies").iterdir()):
            if not d.is_dir() or d.name.startswith("."):
                continue
            if not ((d / "model.safetensors").is_file() or (d / "config.json").is_file()):
                continue
            meta = read_json(d / "nervus_policy.json", {}) or {}
            missing = self.policy_missing(d)
            out.append({"name": d.name, "current": d.name == cur, "complete": not missing, "missing": missing[:5],
                        **{k: meta.get(k) for k in (
                "dataset", "datasets", "job", "step", "cameras", "chunk_size", "role", "loss", "created", "parent",
                "gate", "task", "build")}})
        out.sort(key=lambda p: (p.get("created") or "", p["name"]), reverse=True)
        return out

    def current_policy(self) -> str | None:
        return (read_json(self.root / "policies" / "CURRENT.json") or {}).get("name")

    # ---- jobs ----
    def new_job(self, spec: dict) -> str:
        name = safe_name(spec["name"])
        if len(name) > 40:
            # The id adds a 16-character timestamp, and ids are names too (63 characters at most).
            raise ValueError("job names are at most 40 characters")
        job_id = datetime.now().strftime("%Y%m%d-%H%M%S") + "-" + name
        d = self.job_dir(job_id)
        d.mkdir(parents=True, exist_ok=False)
        write_json(d / "job.json", dict(spec, id=job_id, created=utc_now(), contract=CONTRACT["version"]))
        return job_id

    def jobs(self, limit: int = 30) -> list[dict]:
        out = []
        dirs = sorted((p for p in (self.root / "jobs").iterdir() if p.is_dir()), reverse=True)[:limit]
        for d in dirs:
            job = read_json(d / "job.json")
            if job is None:
                continue
            st = read_json(d / "status.json", {"state": "queued"})
            out.append({"id": d.name, "job": job, "status": st, "cancel_requested": (d / "CANCEL").exists()})
        return out

    def job_metrics(self, job_id: str, max_points: int = 400) -> list[dict]:
        p = self.job_dir(job_id) / "metrics.jsonl"
        if not p.exists():
            return []
        rows = []
        for line in p.read_text(encoding="utf-8").splitlines():
            try:
                rows.append(json.loads(line))
            except Exception:
                pass
        if len(rows) > max_points:
            step = len(rows) / max_points
            rows = [rows[int(i * step)] for i in range(max_points)] + [rows[-1]]
        return rows

    def cancel_job(self, job_id: str):
        (self.job_dir(job_id) / "CANCEL").write_text(utc_now(), encoding="utf-8")

    # ---- trials ----
    TRIAL_FIELDS = ["run_id", "policy", "case", "outcome", "duration_s", "steps", "stop_reason", "interventions",
                    "latency_ms", "note", "time"]

    def add_trial(self, row: dict) -> bool:
        """One row per run, first label wins (matching the rollout's READY). A double-click must not
        count as two trials: promotion is decided on these rows."""
        path = self.root / "trials" / "results.csv"
        if path.exists():
            with open(path, newline="", encoding="utf-8") as f:
                if any(r.get("run_id") == row["run_id"] for r in csv.DictReader(f)):
                    return False
        new = not path.exists()
        with open(path, "a", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=self.TRIAL_FIELDS)
            if new:
                w.writeheader()
            w.writerow({k: row.get(k, "") for k in self.TRIAL_FIELDS})
        return True

    def trials(self, limit: int = 100) -> list[dict]:
        path = self.root / "trials" / "results.csv"
        if not path.exists():
            return []
        with open(path, newline="", encoding="utf-8") as f:
            return list(csv.DictReader(f))[-limit:]

    # ---- worker heartbeat ----
    def worker(self) -> dict:
        w = read_json(self.root / "workers" / "mac.json")
        if not w:
            return {"seen": False}
        age = time.time() - float(w.get("epoch", 0))
        return dict(w, seen=True, age_s=round(age), alive=age < 120)
