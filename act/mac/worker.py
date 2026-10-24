#!/usr/bin/env python3
"""The ACT training worker on the Mac.

Watches ~/Shared/NervusACT (synced with the Dell by Syncthing, directly or through the Hetzner relay) and:

  * trains every queued job: builds the job's dataset from the recordings and their edits, runs
    lerobot-train under train_supervisor (crash/hang/NaN recovery, resume from the last complete
    checkpoint), mirrors progress to jobs/<id>/status.json and metrics.jsonl, and publishes a few
    checkpoints to policies/ with the facts the robot needs (nervus_policy.json);
  * keeps a heartbeat in workers/mac.json, so the Dell can tell whether anyone will pick a job up;
  * post-trains: turns labelled runs with human corrections (and clean successes) into training
    episodes, fine-tunes CURRENT on them, gates the candidates offline, and publishes those that pass;
  * promotes a candidate to CURRENT only on real results: at least 10 labelled trials and a success rate
    no worse than CURRENT's last 10.

A training job survives the worker: the supervisor runs in its own session, and a restarted worker
re-attaches to it (supervisor.pid) instead of starting a second trainer on the same output directory.
A post-training round does not: it is stopped with the worker and marked interrupted.

    python worker.py                 # run in the foreground
    python worker.py --once          # one pass, for testing

Runs as the launchd agent com.nervus.act-worker (see install.sh), with the LeRobot 0.4.4 venv.
"""
from __future__ import annotations

import argparse
import csv
import json
import math
import os
import platform
import re
import shutil
import signal
import subprocess
import sys
import threading
import time
import traceback
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import build  # noqa: E402

SHARED = Path(os.environ.get("NERVUS_ACT_SHARED", "~/Shared/NervusACT")).expanduser()
WORK = Path(os.environ.get("NERVUS_ACT_WORK", "~/NervusACT-work")).expanduser()
PY = Path(sys.executable)
LEROBOT_TRAIN = PY.parent / "lerobot-train"
SYNC_FOLDER = "mac-dell-shared"
CONTRACT_VERSION = 1
TERMINAL = {"finished", "failed", "cancelled"}
LOG_FREQ = 100
ROUND_STEPS = int(os.environ.get("NERVUS_ACT_ROUND_STEPS", 20_000))
ROUND_SAVE = int(os.environ.get("NERVUS_ACT_ROUND_SAVE", 5_000))
TRIALS_FOR_PROMOTION = 10
MIN_HUMAN_ROWS = 30          # a correction shorter than 2 s is a twitch, not a demonstration
HUMAN_SHARE = 0.15           # corrections are duplicated up to this share of the round's episodes
ROUND_RETRY_AFTER = timedelta(minutes=30)

LOSS = re.compile(r"step:(\S+) .*?loss:([0-9.]+|nan|inf) grdn:([0-9.]+|nan|inf) lr:(\S+) updt_s:([0-9.]+) data_s:([0-9.]+)")
TQDM = re.compile(r"Training:[^\r\n]*?(\d+)/(\d+) \[")
MARK = re.compile(r"===== supervisor launch #\d+ [^\n]*? — ([^\n]*?) =====")


def build_summary(data: Path) -> dict | None:
    """What a policy was trained on: episodes and frames per source after the edits (from BUILD_REPORT.json)."""
    rep = read_json(data / "BUILD_REPORT.json")
    if not rep:
        return None
    per: dict[str, dict] = {}
    for r in rep.get("report", []):
        s = per.setdefault(r["source"], {"episodes": 0, "frames": 0})
        s["episodes"] += 1
        s["frames"] += r["kept"]
    return {"episodes": rep.get("episodes"), "frames": rep.get("frames"), "fps": rep.get("fps"), "by_source": per}


class Stopping(Exception):
    """The worker is shutting down."""


def now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_time(s: str) -> datetime:
    try:
        return datetime.strptime(s, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
    except Exception:
        return datetime.min.replace(tzinfo=timezone.utc)


def read_json(p: Path, default=None):
    try:
        return json.loads(Path(p).read_text())
    except Exception:
        return default


def write_json(p: Path, obj):
    p.parent.mkdir(parents=True, exist_ok=True)
    tmp = p.with_name(f".{p.name}.tmp")
    tmp.write_text(json.dumps(obj, indent=2, default=str))
    os.replace(tmp, p)


def log(msg: str):
    line = f"[{now()}] {msg}"
    print(line, flush=True)
    with open(WORK / "worker.log", "a") as f:
        f.write(line + "\n")


def pid_alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
        return True
    except (OSError, TypeError):
        return False


def cmdline(pid: int) -> str:
    try:
        return subprocess.run(["ps", "-p", str(pid), "-o", "command="], capture_output=True, text=True, timeout=5).stdout
    except Exception:
        return ""


def process_is(pid, needle: str) -> bool:
    """Alive AND still the program we started: a pid file from before a reboot can name anything."""
    return bool(pid) and pid_alive(pid) and needle in cmdline(pid)


def sync_settled() -> tuple[bool, str]:
    """Has Syncthing finished bringing the shared folder to this Mac? A dataset whose info.json has arrived
    can still be missing videos; building from it would fail halfway or, worse, silently skip frames."""
    if os.environ.get("NERVUS_ACT_SYNC_CHECK", "1") == "0":
        return True, ""  # tests, or a shared folder that isn't synced
    cfg = Path("~/Library/Application Support/Syncthing/config.xml").expanduser()
    try:
        xml = cfg.read_text()
        key = re.search(r"<apikey>([^<]+)</apikey>", xml).group(1)
        gui = re.search(r"<gui[^>]*>.*?<address>([^<]+)</address>", xml, re.S)
        addr = gui.group(1) if gui else "127.0.0.1:8384"
        req = urllib.request.Request(f"http://{addr}/rest/db/status?folder={SYNC_FOLDER}", headers={"X-API-Key": key})
        with urllib.request.urlopen(req, timeout=3) as r:
            st = json.loads(r.read())
        if st.get("needFiles", 0) or st.get("state") in ("syncing", "sync-preparing"):
            return False, f"Syncthing is still bringing {st.get('needFiles', 0)} file(s) to this Mac"
        return True, ""
    except Exception:
        return True, ""  # no Syncthing API to ask: rely on the file checks


class LogFollower:
    """Reads lerobot-train's log incrementally and recovers each loss line's exact step.

    lerobot prints `step:1K` (rounded: 1000-1499 all read "1K"), so the step comes from the "Training:"
    tqdm counter printed just before each loss line, plus the step a resumed launch started from (the
    supervisor's "===== supervisor launch ... (step S) =====" marker): tqdm counts from zero on every launch."""

    def __init__(self, path: Path, state_path: Path):
        self.path, self.state_path = path, state_path
        st = read_json(state_path, {}) or {}
        self.offset = int(st.get("offset", 0))   # bytes consumed, the carry included
        self.base = int(st.get("base", 0))
        self.n = int(st.get("n", 0))
        self.last = int(st.get("last", -1))
        self.carry = b""

    @property
    def step(self) -> int | None:
        return self.base + self.n if (self.n or self.base) else None

    def poll(self) -> list[dict]:
        try:
            size = self.path.stat().st_size
        except FileNotFoundError:
            return []
        if size < self.offset:  # replaced: start over (the step guard below stops duplicate rows)
            self.offset, self.carry = 0, b""
        with open(self.path, "rb") as f:
            f.seek(self.offset)
            chunk = f.read(4_000_000)
        self.offset += len(chunk)
        data = self.carry + chunk
        cut = max(data.rfind(b"\n"), data.rfind(b"\r"))
        if cut < 0:
            self.carry = data[-200_000:]
            return []
        text, self.carry = data[:cut + 1].decode("utf-8", "replace"), data[cut + 1:]
        events = [(m.start(), 0, m) for m in MARK.finditer(text)]
        events += [(m.start(), 1, m) for m in TQDM.finditer(text)]
        events += [(m.start(), 2, m) for m in LOSS.finditer(text)]
        rows = []
        for _pos, kind, m in sorted(events, key=lambda e: (e[0], e[1])):
            if kind == 0:
                s = re.search(r"\(step (\d+)\)", m.group(1))
                self.base, self.n = (int(s.group(1)) if s else 0), 0
            elif kind == 1:
                self.n = int(m.group(1))
            else:
                step = int(round((self.base + self.n) / LOG_FREQ) * LOG_FREQ)
                if step <= self.last:
                    continue
                self.last = step
                f = lambda v: None if v in ("nan", "inf") else float(v)
                rows.append({"step": step, "loss": f(m.group(2)), "grad_norm": f(m.group(3)), "lr": m.group(4),
                             "updt_s": float(m.group(5)), "data_s": float(m.group(6))})
        write_json(self.state_path, {"offset": self.offset - len(self.carry), "base": self.base, "n": self.n,
                                     "last": self.last})
        return rows


class Worker:
    def __init__(self):
        WORK.mkdir(parents=True, exist_ok=True)
        for d in ("jobs", "policies", "rollouts", "trials", "posttrain", "workers", "datasets"):
            (SHARED / d).mkdir(parents=True, exist_ok=True)
        self.state = "idle"
        self.job: str | None = None
        self.stop = False
        self.versions = self._versions()
        self.ledger_path = WORK / "posttrain" / "ledger.json"
        self._last_pt_status = None
        self._scan_errors: dict[str, str] = {}

    def _versions(self) -> dict:
        try:
            import lerobot
            import torch
            dev = "mps" if torch.backends.mps.is_available() else ("cuda" if torch.cuda.is_available() else "cpu")
            return {"lerobot": lerobot.__version__, "torch": torch.__version__, "device": dev}
        except Exception as e:
            return {"error": str(e)}

    # ------------------------------------------------------------------ heartbeat
    def heartbeat(self):
        while not self.stop:
            try:
                write_json(SHARED / "workers" / "mac.json", {
                    "host": platform.node(), "epoch": time.time(), "time": now(), "state": self.state,
                    "job": self.job, "queue": [j["id"] for j in self.queue()], **self.versions,
                    "free_gb": round(shutil.disk_usage(WORK).free / 1e9, 1)})
            except Exception as e:
                log(f"heartbeat failed: {e}")
            for _ in range(20):
                if self.stop:
                    return
                time.sleep(1)

    # ------------------------------------------------------------------ jobs
    def queue(self) -> list[dict]:
        out = []
        for d in sorted((SHARED / "jobs").iterdir()):
            job = read_json(d / "job.json")
            if not job or job.get("worker", "mac") not in ("mac", "any"):
                continue
            if job.get("type") == "posttrain":
                continue  # rounds are run by maybe_round, never resumed as plain training
            st = read_json(d / "status.json", {}) or {}
            if st.get("state") in TERMINAL:
                continue
            out.append(job)
        return out

    def set_status(self, job_id: str, **kw):
        p = SHARED / "jobs" / job_id / "status.json"
        st = read_json(p, {}) or {}
        st.update(kw, updated=now(), worker=platform.node())
        write_json(p, st)
        return st

    def cancelled(self, job_id: str) -> bool:
        return (SHARED / "jobs" / job_id / "CANCEL").exists()

    @staticmethod
    def supervisor_pid(wdir: Path) -> int:
        try:
            pid = int((wdir / "supervisor.pid").read_text())
        except Exception:
            return 0
        return pid if process_is(pid, "train_supervisor.py") else 0

    def stop_supervisor(self, wdir: Path, pid: int, proc=None, wait_s: int = 150):
        """SIGTERM the supervisor: it stops its trainer (SIGINT, then SIGKILL after 60 s) and exits. If it
        doesn't, kill it and the trainer (each runs in its own session, so each is its own process group)."""
        alive = (lambda: proc.poll() is None) if proc is not None else (lambda: process_is(pid, "train_supervisor.py"))
        try:
            os.kill(pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
        for _ in range(wait_s):
            if not alive():
                break
            time.sleep(1)
        if alive():
            trainer = (read_json(wdir / "out.status.json", {}) or {}).get("pid")
            for p, needle in ((pid, "train_supervisor.py"), (trainer, "lerobot-train")):
                if process_is(p, needle):
                    try:
                        os.killpg(p, signal.SIGKILL)
                    except (ProcessLookupError, PermissionError):
                        pass
            log(f"supervisor {pid} did not stop on SIGTERM: killed")
        (wdir / "supervisor.pid").unlink(missing_ok=True)

    def run_job(self, job: dict) -> bool:
        """Returns False when the job can't start yet (its data is still syncing), so the next one can go."""
        jid = job["id"]
        wdir = WORK / "jobs" / jid
        wdir.mkdir(parents=True, exist_ok=True)
        st = read_json(SHARED / "jobs" / jid / "status.json", {}) or {}
        if self.cancelled(jid):
            pid = self.supervisor_pid(wdir)
            if pid:
                self.stop_supervisor(wdir, pid)
            self.set_status(jid, state="cancelled", phase="cancelled by the operator")
            return True
        data = wdir / "data"
        needs_build = not (data / "meta" / "info.json").exists() or st.get("state") in (None, "queued", "waiting", "preparing")
        if needs_build:
            missing = [n for n in job["datasets"] if not (SHARED / "datasets" / n / "meta" / "info.json").exists()]
            settled, why = sync_settled()
            if missing or not settled:
                msg = (f"waiting for dataset(s) {', '.join(missing)} to arrive from the Dell" if missing else why)
                if st.get("phase") != msg:
                    self.set_status(jid, state="waiting", phase=msg)
                return False
        self.job = jid
        try:
            if needs_build:
                self.state = "preparing"
                self.set_status(jid, state="preparing", phase="building the training dataset",
                                started=st.get("started") or now(), step=0, target=job.get("steps"))
                rep = build.build([SHARED / "datasets" / n for n in job["datasets"]], data, f"local/{jid}",
                                  log=lambda m: log(f"{jid}: {m}"), cancel=lambda: self.stop or self.cancelled(jid))
                write_json(wdir / "facts.json", build.facts(data))
                self.set_status(jid, dataset={"episodes": rep["episodes"], "frames": rep["frames"],
                                              "seconds": round(rep["frames"] / rep["fps"], 1), "build_s": rep["seconds"]})
            facts = read_json(wdir / "facts.json") or build.facts(data)
            self.train(job, wdir, data, facts)
        except build.Cancelled:
            if self.stop:
                raise Stopping()
            self.set_status(jid, state="cancelled", phase="cancelled while the dataset was being built")
        except Stopping:
            raise
        except Exception as e:
            traceback.print_exc()
            self.set_status(jid, state="failed", phase="failed", message=str(e)[:500])
            log(f"{jid}: failed: {e}")
        finally:
            self.job, self.state = None, "idle"
        return True

    @staticmethod
    def publish_plan(steps: int, save_freq: int) -> tuple[int, int]:
        """About four checkpoints from the second half of the run, the final one included (the supervisor
        always publishes it). Every policy is ~200 MB that Syncthing carries to the Dell, and the early ones
        are rarely the ones that work on the robot."""
        k = max(1, math.ceil(steps * 0.6 / save_freq / 4))
        every = save_freq * k
        # From the second half, and never past the end: a run shorter than one publishing interval still
        # publishes its final checkpoint.
        return every, min(steps, max(every, steps - 3 * every, steps // 2))

    def train(self, job: dict, wdir: Path, data: Path, facts: dict, keep_on_stop: bool = True):
        jid = job["id"]
        out = wdir / "out"
        cfg_path = wdir / "supervisor.json"
        pid_path = wdir / "supervisor.pid"
        every, first = self.publish_plan(job["steps"], job["save_freq"])
        fps = (read_json(data / "meta" / "info.json", {}) or {}).get("fps", 15)
        args = [f"--dataset.repo_id=local/{jid}", f"--dataset.root={data}", "--dataset.image_transforms.enable=true",
                "--policy.push_to_hub=false", f"--batch_size={job['batch_size']}", f"--save_freq={job['save_freq']}",
                f"--log_freq={LOG_FREQ}", f"--job_name={jid}", "--wandb.enable=false",
                f"--policy.device={self.versions.get('device', 'cpu')}"]
        parent = job.get("parent")
        if parent:
            pdir = SHARED / "policies" / parent
            if not (pdir / "config.json").exists():
                raise RuntimeError(f"parent policy {parent} is not on this Mac")
            args.insert(0, f"--policy.path={pdir}")  # its config (chunk size included) comes with it
        else:
            args += ["--policy.type=act", f"--policy.chunk_size={job['chunk_size']}",
                     f"--policy.n_action_steps={job['n_action_steps']}"]
        cfg = {"output_dir": str(out), "steps": job["steps"], "args": args, "lerobot_train": str(LEROBOT_TRAIN),
               "poll_s": 15, "hang_timeout_s": 900, "backoff_base_s": 30, "max_restarts": 12, "min_free_gb": 6,
               "publish_dir": str(SHARED / "policies"), "publish_prefix": job.get("publish_prefix", job["name"]),
               "publish_min_step": job.get("publish_min_step", first), "publish_every": job.get("publish_every", every),
               "publish_meta": {"job": jid, "datasets": job["datasets"], "dataset": job["datasets"][0],
                                "cameras": job.get("cameras"), "task": job.get("task"), "fps": fps,
                                "units": {"joints": "deg", "gripper": "mm"}, "role": job.get("role", "trained"),
                                "parent": parent, "contract": CONTRACT_VERSION, "build": build_summary(data), **facts}}
        write_json(cfg_path, cfg)
        self.state = "training"
        self.set_status(jid, state="training", phase="training", target=job["steps"])

        # Re-attach to a supervisor that outlived a previous worker, rather than start a second trainer.
        pid, proc = self.supervisor_pid(wdir), None
        if pid:
            log(f"{jid}: re-attaching to the running supervisor (pid {pid})")
        else:
            with open(wdir / "supervisor.stdout", "a") as so:
                proc = subprocess.Popen([str(PY), str(HERE / "train_supervisor.py"), "--config", str(cfg_path)],
                                        stdout=so, stderr=subprocess.STDOUT, start_new_session=True)
            pid = proc.pid
            pid_path.write_text(str(pid))
            # Keep the Mac awake for exactly as long as the training runs.
            subprocess.Popen(["/usr/bin/caffeinate", "-dimsu", "-w", str(pid)], start_new_session=True)
            log(f"{jid}: supervisor started (pid {pid}), publishing from step {first} every {every}")

        running = (lambda: proc.poll() is None) if proc is not None else (lambda: process_is(pid, "train_supervisor.py"))
        status_path = out.parent / f"{out.name}.status.json"
        follower = LogFollower(out.parent / f"{out.name}.log", wdir / "metrics_state.json")
        while running():
            for _ in range(10):
                if self.stop:
                    if keep_on_stop:
                        raise Stopping()  # the supervisor keeps training; the next worker re-attaches
                    os.kill(pid, signal.SIGTERM)  # it stops its trainer itself, worker or no worker
                    pid_path.unlink(missing_ok=True)
                    raise Stopping()
                time.sleep(1)
            if self.cancelled(jid):
                self.stop_supervisor(wdir, pid, proc)
                self.mirror(jid, status_path, follower)
                self.set_status(jid, state="cancelled", phase="cancelled by the operator")
                return
            self.mirror(jid, status_path, follower)
        self.mirror(jid, status_path, follower)
        pid_path.unlink(missing_ok=True)
        sup = read_json(status_path, {}) or {}
        if sup.get("state") == "finished":
            published = sup.get("published", [])
            self.set_status(jid, state="finished", phase="finished", published=published, step=sup.get("step"), pct=100.0,
                            message=f"published {len(published)} checkpoint(s)" if published else "finished")
            log(f"{jid}: finished, published {published}")
            if not read_json(SHARED / "policies" / "CURRENT.json") and published and job.get("role", "trained") == "trained":
                self.promote(published[-1], {"rule": "the first trained policy becomes CURRENT"})
        else:
            self.set_status(jid, state="failed", phase=sup.get("state", "failed"),
                            message=f"training ended in state {sup.get('state')}; see ~/NervusACT-work/jobs/{jid}")

    def mirror(self, jid: str, status_path: Path, follower: LogFollower):
        rows = follower.poll()
        if rows:
            with open(SHARED / "jobs" / jid / "metrics.jsonl", "a") as f:
                for r in rows:
                    f.write(json.dumps(r) + "\n")
        sup = read_json(status_path, {}) or {}
        if not sup:
            return
        m = sup.get("metrics") or {}
        per = (m.get("updt_s") or 0) + (m.get("data_s") or 0)
        step = follower.step if follower.step is not None else sup.get("step")
        target = sup.get("target") or 1
        # A trainer that keeps logging but crawls looks healthy to the hang check, so compare its step time with
        # the best it managed in this job. On the 16 GB Mac the usual cause is memory: other apps push the
        # trainer into swap and a step that took 0.4 s takes minutes.
        st_time, warning = sup.get("step_time_s"), None
        if st_time:
            follower.best = min(getattr(follower, "best", None) or st_time, st_time)
            if st_time > 3.0 and st_time > 6 * follower.best:
                warning = f"training is {st_time / follower.best:.0f}x slower than earlier in this job ({st_time:.0f} s a step)"
                mem, swap = sup.get("mem_free_pct"), sup.get("swap_used_gb")
                if (mem is not None and mem < 25) or (swap or 0) > 4:
                    warning += f": the Mac is short of memory ({mem}% free, {swap} GB in swap); close memory-hungry apps"
        self.set_status(jid, step=step, target=sup.get("target"), pct=round(100 * (step or 0) / target, 1), warning=warning,
                        step_time_s=st_time,
                        eta_hours=round((target - (step or 0)) * per / 3600, 2) if per else None,
                        metrics=m, restarts=sup.get("restarts"), checkpoints=sup.get("checkpoints"),
                        published=sup.get("published", []), steps_per_s=round(1 / per, 2) if per else None,
                        supervisor=sup.get("state"), free_gb=sup.get("free_gb"), mem_free_pct=sup.get("mem_free_pct"),
                        history=sup.get("history"))

    # ------------------------------------------------------------------ promotion
    def promote(self, name: str, evidence: dict):
        write_json(SHARED / "policies" / "CURRENT.json", {"name": name, "since": now(), "evidence": evidence})
        log(f"CURRENT is now {name} ({evidence})")

    def trials(self) -> list[dict]:
        p = SHARED / "trials" / "results.csv"
        if not p.exists():
            return []
        with open(p, newline="") as f:
            rows = list(csv.DictReader(f))
        seen, out = set(), []
        for r in rows:  # one row per run (the Dell already writes once; this is belt and braces)
            if r.get("run_id") in seen:
                continue
            seen.add(r.get("run_id"))
            out.append(r)
        return out

    def candidates(self) -> tuple[str | None, float | None, int, list[dict]]:
        """CURRENT, its success rate over its last 10 trials, how many that is, and every candidate
        published since CURRENT took over with its trial count and verdict."""
        cur_rec = read_json(SHARED / "policies" / "CURRENT.json") or {}
        cur, since = cur_rec.get("name"), cur_rec.get("since", "")
        trials = self.trials()
        cur_rows = [t for t in trials if t.get("policy") == cur][-TRIALS_FOR_PROMOTION:]
        cur_rate = sum(t.get("outcome") == "success" for t in cur_rows) / len(cur_rows) if cur_rows else None
        out = []
        for p in sorted((SHARED / "policies").iterdir()):
            meta = read_json(p / "nervus_policy.json", {}) or {}
            # Only candidates published after CURRENT took over: otherwise two equal candidates would keep
            # promoting each other.
            if meta.get("role") != "candidate" or p.name == cur or meta.get("created", "") <= since:
                continue
            rows = [t for t in trials if t.get("policy") == p.name]
            rate = sum(t.get("outcome") == "success" for t in rows) / len(rows) if rows else None
            if cur_rate is None:
                verdict = "CURRENT has no trials yet: run CURRENT too, or promote by hand"
            elif len(rows) < TRIALS_FOR_PROMOTION:
                verdict = f"needs {TRIALS_FOR_PROMOTION - len(rows)} more trial(s)"
            elif rate >= cur_rate:
                verdict = "promote"
            else:
                verdict = "rejected: worse than CURRENT on the arm"
            out.append({"name": p.name, "trials": len(rows), "success_rate": None if rate is None else round(rate, 3),
                        "verdict": verdict, "created": meta.get("created")})
        return cur, cur_rate, len(cur_rows), out

    def check_promotion(self):
        cur = (read_json(SHARED / "policies" / "CURRENT.json") or {}).get("name")
        req = read_json(SHARED / "posttrain" / "PROMOTE")
        seen = (read_json(WORK / "posttrain" / "promote_seen.json", {}) or {}).get("time", "")
        if req and req.get("time", "") > seen:
            write_json(WORK / "posttrain" / "promote_seen.json", {"time": req.get("time", "")})
            name = req.get("name")
            if name and name != cur and (SHARED / "policies" / name / "model.safetensors").exists():
                self.promote(name, {"rule": "chosen by hand in ACT Studio", "previous": cur})
            return
        cur, cur_rate, n_cur, cands = self.candidates()
        for c in cands:
            if c["verdict"] == "promote":
                self.promote(c["name"], {
                    "candidate_trials": c["trials"], "candidate_success_rate": c["success_rate"], "current": cur,
                    "current_trials_considered": n_cur, "current_success_rate": round(cur_rate, 3),
                    "rule": f">= {TRIALS_FOR_PROMOTION} labelled trials and a success rate >= CURRENT's last {TRIALS_FOR_PROMOTION}"})
                return

    # ------------------------------------------------------------------ post-training
    def ledger(self) -> dict:
        return read_json(self.ledger_path, {"runs": {}, "rounds": []}) or {"runs": {}, "rounds": []}

    @staticmethod
    def classify(d: Path) -> dict | None:
        """One labelled run -> its training episodes (the rules of the Piper-ACT post-training contract):
        every contiguous human segment of at least 30 rows, success or fail (it is a correction from a state
        the policy really reaches); the whole run only if it succeeded with no intervention; policy rows of a
        failed or corrected run never (they show the mistake). None = not complete yet, look again later."""
        if not (d / "READY").exists():
            return None
        meta = read_json(d / "meta.json", {}) or {}
        if not meta.get("outcome") or not (d / "episode.jsonl").exists() or not meta.get("videos") or \
                any(not (d / v).exists() for v in meta["videos"].values()):
            return None  # still syncing
        base = {"policy": meta.get("policy"), "outcome": meta.get("outcome"), "cameras": sorted(meta["videos"]),
                "scanned": now(), "trained_in": None, "episodes": []}
        rows = [json.loads(l) for l in (d / "episode.jsonl").read_text().splitlines() if l.strip()]
        reject = None
        if meta.get("contract") != CONTRACT_VERSION:
            reject = f"contract {meta.get('contract')} is not {CONTRACT_VERSION}"
        elif meta.get("writer_error"):
            reject = f"the recorder failed: {meta['writer_error']}"
        elif meta.get("frames") != len(rows):
            reject = f"{len(rows)} rows for {meta.get('frames')} frames"
        if reject:
            return dict(base, status="rejected", reason=reject)
        eps = []
        if (meta.get("interventions") or 0) > 0:
            seg = []
            for r in rows + [{}]:
                if r.get("segment") == "human" and r.get("state"):
                    seg.append(r["frame"])
                    continue
                if len(seg) >= MIN_HUMAN_ROWS:
                    eps.append({"kind": "human", "rows": [seg[0], seg[-1] + 1]})
                seg = []
        elif meta.get("outcome") == "success" and rows:
            eps.append({"kind": "success", "rows": [rows[0]["frame"], rows[-1]["frame"] + 1]})
        if not eps:
            why = ("its corrections were all shorter than 2 s" if meta.get("interventions")
                   else "a failed run without a correction teaches nothing")
            return dict(base, status="rejected", reason=why)
        return dict(base, status="accepted", reason=None, episodes=eps)

    def scan_rollouts(self) -> dict:
        led = self.ledger()
        changed = False
        for d in sorted((SHARED / "rollouts").iterdir()):
            if not d.is_dir() or d.name.startswith(".") or d.name in led["runs"]:
                continue
            try:
                entry = self.classify(d)
            except Exception as e:
                # A bug here must not reject the run: say so once, and look again after a restart.
                if self._scan_errors.get(d.name) != str(e):
                    self._scan_errors[d.name] = str(e)
                    log(f"rollout {d.name}: could not read it: {e}")
                continue
            if entry is None:
                continue
            led["runs"][d.name] = entry
            changed = True
            log(f"rollout {d.name}: {entry['status']} ({entry['reason'] or [e['kind'] for e in entry['episodes']]})")
        if changed:
            write_json(self.ledger_path, led)
        return led

    def rollout_episode(self, run_id: str, rows_range: list[int], cams: list[str], task: str):
        """(states, frames generator, task) for build.build, idle-trimmed at both ends; None if too short."""
        import cv2
        d = SHARED / "rollouts" / run_id
        rows = {r["frame"]: r for r in (json.loads(l) for l in (d / "episode.jsonl").read_text().splitlines() if l.strip())}
        idx = [i for i in range(*rows_range) if i in rows and rows[i].get("state")]
        if len(idx) < 2:
            return None
        S = np.array([rows[i]["state"] for i in idx], dtype=np.float32)
        s0, s1 = build.idle_bounds(S, 1.0, 5, 5)
        idx, S = idx[s0:s1], S[s0:s1]
        if len(idx) < 15:
            return None

        def frames():
            caps = {c: cv2.VideoCapture(str(d / f"{c}.mp4")) for c in cams}
            try:
                pos, cur = 0, {}
                for want in idx:
                    while pos <= want:
                        cur = {}
                        for c, cap in caps.items():
                            ok, bgr = cap.read()
                            if not ok:
                                return
                            cur[c] = cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB)
                        pos += 1
                    yield cur
            finally:
                for cap in caps.values():
                    cap.release()

        return S, frames(), task

    def maybe_round(self, blocked: str | None = None):
        led = self.scan_rollouts()
        cur = (read_json(SHARED / "policies" / "CURRENT.json") or {}).get("name")
        if not cur:
            self.write_pt_status(led, None, [], [], "waiting", {"message": "no CURRENT policy yet: train one first"})
            return
        cmeta = read_json(SHARED / "policies" / cur / "nervus_policy.json", {}) or {}
        cams = sorted(cmeta.get("cameras") or [])
        # Only runs recorded with the cameras CURRENT uses can train it.
        usable = lambda r: r["status"] == "accepted" and sorted(r.get("cameras") or []) == cams
        untrained = [(rid, e) for rid, r in led["runs"].items() if usable(r) and r["trained_in"] is None for e in r["episodes"]]
        humans = [x for x in untrained if x[1]["kind"] == "human"]
        if blocked:
            self.write_pt_status(led, cur, untrained, humans, "waiting", {"message": blocked})
            return
        req = read_json(SHARED / "posttrain" / "REQUEST")
        last = led["rounds"][-1] if led["rounds"] else None
        requested = bool(req) and req.get("time", "") > (last["started"] if last else "")
        if last and last.get("failed") and not requested and \
                datetime.now(timezone.utc) - parse_time(last.get("started", "")) < ROUND_RETRY_AFTER:
            self.write_pt_status(led, cur, untrained, humans, "waiting",
                                 {"message": "the last round failed; retrying 30 minutes after it started"})
            return
        if not (len(humans) >= 5 or len(untrained) >= 10 or (requested and untrained)):
            msg = ("Post-train now was pressed, but there are no new accepted episodes" if requested and not untrained
                   else None)
            self.write_pt_status(led, cur, untrained, humans, "waiting", {"message": msg})
            return
        settled, why = sync_settled()
        if not settled:
            self.write_pt_status(led, cur, untrained, humans, "waiting", {"message": why})
            return
        self.run_round(led, cur, cmeta, untrained, humans)

    def write_pt_status(self, led, cur, untrained, humans, state, extra=None):
        st = read_json(SHARED / "posttrain" / "STATUS.json", {}) or {}
        st.pop("message", None)
        _, cur_rate, n_cur, cands = self.candidates()
        recent = sorted(led["runs"].items(), key=lambda kv: kv[0], reverse=True)[:20]
        st.update({"state": state, "current": cur, "current_success_rate": cur_rate, "current_trials": n_cur,
                   "runs": {k: len([1 for r in led["runs"].values() if r["status"] == k]) for k in ("accepted", "rejected")},
                   "recent_runs": [{"run_id": k, "status": r["status"], "reason": r.get("reason"), "outcome": r.get("outcome"),
                                    "episodes": [e["kind"] for e in r["episodes"]], "trained_in": r.get("trained_in")}
                                   for k, r in recent],
                   "untrained_episodes": len(untrained), "untrained_corrections": len(humans),
                   "candidates": cands, "trigger": "5 new corrections, 10 new episodes, or Post-train now in ACT Studio",
                   "rounds": led["rounds"][-10:]})
        if extra:
            st.update({k: v for k, v in extra.items() if v is not None})
        key = json.dumps({k: v for k, v in st.items() if k != "updated"}, sort_keys=True, default=str)
        if key == self._last_pt_status:
            return  # unchanged: don't make Syncthing ship the same file every 15 s
        self._last_pt_status = key
        st["updated"] = now()
        write_json(SHARED / "posttrain" / "STATUS.json", st)

    def run_round(self, led, cur, cmeta, untrained, humans):
        import gate
        n = len(led["rounds"])
        rid = f"posttrain-r{n}"
        jid = datetime.now().strftime("%Y%m%d-%H%M%S") + "-" + rid
        datasets = cmeta.get("datasets") or ([cmeta["dataset"]] if cmeta.get("dataset") else [])
        cams = sorted(cmeta.get("cameras") or [])
        if not datasets or not cams:
            self.write_pt_status(led, cur, untrained, humans, "blocked",
                                 {"message": f"CURRENT {cur} has no dataset or camera record; train one in ACT Studio first"})
            return
        missing = [d for d in datasets if not (SHARED / "datasets" / d / "meta" / "info.json").exists()]
        if missing:
            self.write_pt_status(led, cur, untrained, humans, "blocked",
                                 {"message": f"CURRENT's dataset(s) {missing} are not in the shared folder"})
            return
        # Hold out ~15% of the new corrections (at least one once there are three) as gate set B. They are
        # not trained this round, and join training in the next one.
        k_hold = max(1 if len(humans) >= 3 else 0, round(0.15 * len(humans)))
        held = humans[-k_hold:] if k_hold else []
        train_eps = [x for x in untrained if x not in held]
        prior = [(r, e) for r, rec in led["runs"].items() if rec["status"] == "accepted" and rec["trained_in"] is not None
                 and sorted(rec.get("cameras") or []) == cams for e in rec["episodes"]]
        rollout_eps = prior + train_eps
        # LeRobot 0.4.4 has no per-episode sampling weights: corrections under 15% of the round's episodes are
        # written twice (newest first) until they reach it.
        n_base = sum(len(build.plan_episodes(SHARED / "datasets" / d)) for d in datasets)
        h = [x for x in rollout_eps if x[1]["kind"] == "human"]
        total = n_base + len(rollout_eps)
        dup = 0
        if h and len(h) / total < HUMAN_SHARE:
            dup = min(len(h), math.ceil((HUMAN_SHARE * total - len(h)) / (1 - HUMAN_SHARE)))
        rollout_eps = rollout_eps + h[::-1][:dup]
        base = cur.split("__")[0]
        job = {"id": jid, "type": "posttrain", "name": rid, "datasets": datasets, "cameras": cams,
               "task": cmeta.get("task"), "parent": cur, "steps": ROUND_STEPS, "save_freq": ROUND_SAVE,
               "batch_size": 8, "role": "candidate", "publish_prefix": f"{base}__pt{n}",
               "publish_min_step": 10 ** 9, "created": now(), "worker": "mac",
               "notes": f"{len(train_eps)} new + {len(prior)} earlier rollout episodes ({dup} corrections written twice), "
                        f"{len(held)} held out for the gate"}
        jdir = SHARED / "jobs" / jid
        write_json(jdir / "job.json", job)
        round_rec = {"round": n, "job": jid, "parent": cur, "started": now(), "new": len(train_eps),
                     "prior": len(prior), "held_out": len(held), "duplicated": dup, "base_episodes": n_base}
        led["rounds"].append(round_rec)
        write_json(self.ledger_path, led)
        self.write_pt_status(led, cur, untrained, humans, "round running", {"round": round_rec})
        self.job, self.state = jid, "preparing"
        wdir = WORK / "jobs" / jid
        task = cmeta.get("task") or "demonstration"
        eps = lambda pairs: (x for x in (self.rollout_episode(r, e["rows"], cams, task) for r, e in pairs) if x is not None)
        try:
            self.set_status(jid, state="preparing", phase="building the round dataset", started=now(), step=0,
                            target=ROUND_STEPS)
            data = wdir / "data"
            build.build([SHARED / "datasets" / d for d in datasets], data, f"local/{jid}", log=lambda m: log(f"{jid}: {m}"),
                        extra_episodes=eps(rollout_eps), cancel=lambda: self.stop)
            facts = build.facts(data)
            write_json(wdir / "facts.json", facts)
            report = read_json(data / "BUILD_REPORT.json")["report"]
            n_built_base = len([r for r in report if r["source"] != "rollout"])
            if n_built_base == 0:
                raise RuntimeError("no base episodes survived the edits: the gate's set A would be empty")
            setb, n_b = wdir / "setB", 0
            if held:
                try:
                    n_b = build.build([], setb, f"local/{jid}_B", extra_episodes=eps(held),
                                      features_from=SHARED / "datasets" / datasets[0], cancel=lambda: self.stop)["episodes"]
                except ValueError:
                    n_b = 0  # every held-out correction was too short after trimming: gate on A alone
            self.train(job, wdir, data, facts, keep_on_stop=False)  # publishes nothing itself: the gate decides
            st = read_json(jdir / "status.json", {}) or {}
            if st.get("state") != "finished":
                raise RuntimeError(f"round training ended {st.get('state')}")
            self.state = "gating"
            self.set_status(jid, state="gating", phase="offline gate against CURRENT")
            ckpts = sorted((wdir / "out" / "checkpoints").glob("[0-9]*"))
            set_a = sorted(set(int(i) for i in np.linspace(0, n_built_base - 1, min(5, n_built_base))))
            policies = {cur: SHARED / "policies" / cur}
            policies.update({c.name: c / "pretrained_model" for c in ckpts})
            sets = {"A": (data, f"local/{jid}", set_a)}
            if n_b:
                sets["B"] = (setb, f"local/{jid}_B", list(range(n_b)))
            metrics = gate.evaluate(policies, sets, log=lambda m: log(f"{jid}: {m}"))
            decisions = {c.name: gate.decide(metrics, c.name, cur) for c in ckpts}
            passing = [c for c in ckpts if decisions[c.name]["pass"]]
            chosen = set()
            if passing:
                chosen.add(min(passing, key=lambda c: decisions[c.name]["score"]).name)
                chosen.add(passing[-1].name)
            published = []
            fps = (read_json(data / "meta" / "info.json", {}) or {}).get("fps", 15)
            for c in ckpts:
                if c.name not in chosen:
                    continue
                name = f"{job['publish_prefix']}_{c.name}"
                tmp = SHARED / "policies" / f".{name}.partial"
                shutil.rmtree(tmp, ignore_errors=True)
                shutil.copytree(c / "pretrained_model", tmp)
                meta = dict(facts, job=jid, datasets=datasets, dataset=datasets[0], cameras=cams, task=cmeta.get("task"),
                            fps=fps, units={"joints": "deg", "gripper": "mm"}, role="candidate", parent=cur,
                            contract=CONTRACT_VERSION, step=int(c.name), build=build_summary(data),
                            gate={"metrics": metrics, "decision": decisions[c.name]}, created=now())
                (tmp / "nervus_policy.json").write_text(json.dumps(meta, indent=2))
                tmp.rename(SHARED / "policies" / name)
                published.append(name)
            for r, _e in train_eps:
                led["runs"][r]["trained_in"] = n
            round_rec.update(finished=now(), gate=metrics, decisions=decisions, published=published)
            write_json(self.ledger_path, led)
            self.set_status(jid, state="finished", phase="gated", published=published, gate=metrics, decisions=decisions,
                            message=(f"{len(published)} candidate(s) passed the gate; run at least {TRIALS_FOR_PROMOTION} "
                                     "labelled trials of one to promote it" if published
                                     else "no checkpoint passed the gate; CURRENT stays"))
            self.write_pt_status(led, cur, [], [], "idle", {"round": round_rec})
        except (Stopping, build.Cancelled):
            round_rec.update(failed="interrupted: the worker was stopped")
            write_json(self.ledger_path, led)
            self.set_status(jid, state="failed", phase="interrupted", message="the worker was stopped during the round")
            raise Stopping()
        except Exception as e:
            traceback.print_exc()
            round_rec.update(failed=str(e)[:300])
            write_json(self.ledger_path, led)
            self.set_status(jid, state="failed", phase="failed", message=str(e)[:500])
            self.write_pt_status(led, cur, untrained, humans, "round failed", {"round": round_rec})
        finally:
            self.job, self.state = None, "idle"

    # ------------------------------------------------------------------ main loop
    def recover_interrupted_rounds(self):
        """A round whose worker died (power cut, kill -9) is failed, and its trainer, if it is somehow still
        running, stopped: an orphan would hold the GPU for hours training something nobody will gate."""
        led = self.ledger()
        changed = False
        for d in (SHARED / "jobs").iterdir():
            job = read_json(d / "job.json") or {}
            st = read_json(d / "status.json", {}) or {}
            if job.get("type") != "posttrain" or job.get("worker", "mac") != "mac":
                continue
            wdir = WORK / "jobs" / d.name
            pid = self.supervisor_pid(wdir)
            if pid:
                log(f"{d.name}: stopping the orphaned round supervisor {pid}")
                self.stop_supervisor(wdir, pid)
            if st.get("state") not in TERMINAL:
                self.set_status(d.name, state="failed", phase="interrupted", message="the worker restarted during the round")
            for r in led["rounds"]:
                if r.get("job") == d.name and not r.get("finished") and not r.get("failed"):
                    r["failed"] = "interrupted: the worker restarted"
                    changed = True
        if changed:
            write_json(self.ledger_path, led)

    def run(self, once: bool = False):
        signal.signal(signal.SIGTERM, lambda *_: setattr(self, "stop", True))
        signal.signal(signal.SIGINT, lambda *_: setattr(self, "stop", True))
        threading.Thread(target=self.heartbeat, daemon=True, name="heartbeat").start()
        log(f"worker start: shared={SHARED} work={WORK} {self.versions}")
        self.recover_interrupted_rounds()
        while not self.stop:
            try:
                self.check_promotion()
                queued = self.queue()
                started = False
                for job in queued:
                    if self.run_job(job):
                        started = True
                        break
                if not started:
                    # A post-training round takes hours: never put one in front of a job someone asked for.
                    self.maybe_round(blocked="a training job is queued first" if queued else None)
            except Stopping:
                break
            except Exception as e:
                traceback.print_exc()
                log(f"loop error: {e}")
            if once:
                break
            for _ in range(15):
                if self.stop:
                    break
                time.sleep(1)
        log("worker stopped")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--once", action="store_true")
    Worker().run(once=ap.parse_args().once)
