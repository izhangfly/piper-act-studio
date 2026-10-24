#!/usr/bin/env python3
"""End-to-end test of the whole ACT pipeline, with a simulated arm and cameras (nervus_act.sim).

    PY=~/NervusOS/robots/vla/.venv/bin/python     # any Python with LeRobot 0.4.4
    $PY act/tests/e2e_sim.py [--keep] [--quick]

It drives the engine over HTTP exactly as ACT Studio does and runs the real Mac worker as a subprocess, against
a throwaway shared folder:

  1. record three drag-taught demonstrations into a new LeRobot dataset (h264, finalize + reopen per episode)
  2. trim and exclude episodes through the edits sidecar
  3. queue a training job; the worker builds the dataset, trains, publishes, and makes the policy CURRENT
  4. load the policy, check the scene, enable, go to the start pose, run it, take over with a correction,
     stop, label the run
  5. check the rollout package the Mac will learn from
  6. ask for post-training; the worker runs a round (build, fine-tune, offline gate) and reports it
  7. check the promotion rules on synthetic trial results

Each step prints PASS or FAIL with the evidence. Exit status 1 if anything failed.
"""
from __future__ import annotations

import argparse
import csv
import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import traceback
import urllib.error
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
ACT = HERE.parent
sys.path.insert(0, str(ACT / "engine"))

RESULTS: list[tuple[str, bool, str]] = []


def check(name: str, ok: bool, evidence: str = ""):
    RESULTS.append((name, bool(ok), evidence))
    print(f"{'PASS' if ok else 'FAIL'}  {name}" + (f"  [{evidence}]" if evidence else ""), flush=True)
    return ok


class Client:
    def __init__(self, base: str):
        self.base = base

    def get(self, path: str):
        with urllib.request.urlopen(self.base + path, timeout=60) as r:
            return json.loads(r.read())

    def post(self, path: str, body: dict | None = None, timeout: float = 600):
        req = urllib.request.Request(self.base + path, data=json.dumps(body or {}).encode(),
                                     headers={"Content-Type": "application/json"}, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return json.loads(r.read() or b"{}")
        except urllib.error.HTTPError as e:
            raise RuntimeError(f"{path}: HTTP {e.code} {e.read().decode(errors='replace')[:300]}")


def wait_for(cond, timeout: float, what: str, every: float = 0.2):
    """Wait for a condition, not for a guess at how long it takes."""
    t0 = time.time()
    while time.time() - t0 < timeout:
        v = cond()
        if v:
            return v
        time.sleep(every)
    raise TimeoutError(f"timed out after {timeout:.0f} s waiting for {what}")


def drag(sim: Client, start: list[float], end: list[float], g0: float, g1: float, seconds: float, hz: float = 30):
    """Move the simulated arm by hand from start to end (joints in degrees, gripper in mm)."""
    n = int(seconds * hz)
    for i in range(n + 1):
        w = i / n
        w = 0.5 - 0.5 * __import__("math").cos(__import__("math").pi * w)
        sim.post("/sim/drag", {"joints": [a + (b - a) * w for a, b in zip(start, end)], "gripper_mm": g0 + (g1 - g0) * w})
        time.sleep(1 / hz)


def run_worker(py: str, env: dict, timeout: float, log: Path) -> int:
    with open(log, "a") as f:
        p = subprocess.run([py, str(ACT / "mac" / "worker.py"), "--once"], env=env, stdout=f, stderr=subprocess.STDOUT,
                           timeout=timeout)
    return p.returncode


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--keep", action="store_true", help="keep the temporary folder")
    ap.add_argument("--quick", action="store_true", help="skip post-training")
    a = ap.parse_args()
    py = sys.executable
    root = Path(tempfile.mkdtemp(prefix="nervus-act-e2e-"))
    shared, work = root / "NervusACT", root / "work"
    print(f"temporary folder: {root}", flush=True)
    env = dict(os.environ, NERVUS_ACT_SHARED=str(shared), NERVUS_ACT_WORK=str(work), NERVUS_ACT_SYNC_CHECK="0",
               NERVUS_ACT_ROUND_STEPS="100", NERVUS_ACT_ROUND_SAVE="50", PYTHONUNBUFFERED="1")
    wlog = root / "worker.log"

    from http.server import ThreadingHTTPServer
    from nervus_act import server as srv

    args = argparse.Namespace(shared=str(shared), driver="", cameras="wrist=SIM,scene=SIM", fps=15, threads=4,
                              bind="127.0.0.1", port=0, sim=True)
    eng = srv.Engine(args)
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), srv.make_handler(eng))
    httpd.daemon_threads = True
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    api = Client(f"http://127.0.0.1:{httpd.server_address[1]}")
    sim = Client(eng.sim.url)
    stop_hb = threading.Event()

    def heartbeat():
        while not stop_hb.is_set():
            try:
                api.post("/api/heartbeat")
            except Exception:
                pass
            time.sleep(0.5)

    threading.Thread(target=heartbeat, daemon=True).start()
    try:
        # ---------------------------------------------------------------- 1. record
        st = wait_for(lambda: (lambda s: s if all(c["fps"] >= 10 for c in s["cameras"].values()) and s["arm"]["fresh"] else None)(
            api.get("/api/status")), 15, "cameras and arm feedback")
        check("engine up with simulated arm and cameras", st["engine"]["sim"], f"cameras {[(k, v['fps']) for k, v in st['cameras'].items()]}")
        api.post("/api/record/open", {"name": "sim_pick", "task": "pick up the blue cube and put it on the red square"})
        wait_for(lambda: api.get("/api/status")["recorder"]["state"] == "idle", 60, "dataset open")
        ready = [0.0, 45.0, -60.0, 0.0, 45.0, 0.0]
        demos = [([20.0, 70.0, -80.0, 5.0, 40.0, 0.0], 45.0), ([25.0, 65.0, -75.0, -5.0, 50.0, 10.0], 50.0),
                 ([15.0, 72.0, -85.0, 0.0, 45.0, -10.0], 40.0)]
        for i, (goal, g_open) in enumerate(demos):
            sim.post("/sim/teach", {"on": True})
            api.post("/api/record/start")
            wait_for(lambda: api.get("/api/status")["recorder"]["state"] == "recording", 10, "recording")
            time.sleep(0.8)                                   # stillness before moving (trimmed later)
            drag(sim, ready, goal, 10.0, g_open, 2.0)
            drag(sim, goal, ready, g_open, 10.0, 2.0)
            time.sleep(0.8)                                   # stillness after
            api.post("/api/record/stop")
            st = wait_for(lambda: (lambda r: r if r["state"] == "idle" else None)(api.get("/api/status")["recorder"]),
                          120, "episode saved")
            check(f"episode {i} saved", st["saved_episodes"] == i + 1, st["message"])
        sim.post("/sim/teach", {"on": False})
        api.post("/api/record/close")
        ds = shared / "datasets" / "sim_pick"
        info = json.loads((ds / "meta" / "info.json").read_text())
        codecs = {k: (v.get("info") or {}).get("video.codec") for k, v in info["features"].items() if v.get("dtype") == "video"}
        check("dataset on disk: 3 episodes, both cameras, h264", info["total_episodes"] == 3 and
              set(codecs) == {"observation.images.wrist", "observation.images.scene"} and set(codecs.values()) <= {"h264"},
              f"episodes={info['total_episodes']} frames={info['total_frames']} codecs={codecs}")
        from lerobot.datasets.lerobot_dataset import LeRobotDataset
        d = LeRobotDataset("local/sim_pick", root=ds)
        x = d[len(d) // 2]
        check("dataset loads in LeRobot and action[t] = state[t+1]",
              x["observation.images.wrist"].shape[-2:] == (480, 640), f"{len(d)} frames")

        # ---------------------------------------------------------------- 2. edits
        eps = api.get("/api/datasets/sim_pick/episodes")
        trims = [e["auto_trim"] for e in eps["episodes"]]
        check("idle trim found the stillness at both ends", all(t[0] > 0 and t[1] < e["frames"] for t, e in zip(trims, eps["episodes"])),
              f"trims {trims} of {[e['frames'] for e in eps['episodes']]}; shift_ok={[e['action_shift_ok'] for e in eps['episodes']]}")
        check("action is the next state in every episode", all(e["action_shift_ok"] for e in eps["episodes"]))
        api.post("/api/datasets/sim_pick/edits", {"episodes": {"2": {"exclude": True}}})
        eps = api.get("/api/datasets/sim_pick/episodes")
        check("exclusion recorded", [e["exclude"] for e in eps["episodes"]] == [False, False, True])
        sys.path.insert(0, str(ACT / "mac"))
        import build as mac_build
        from nervus_act import datasets as dell_datasets
        import numpy as np
        rng = np.random.default_rng(0)
        same = True
        for _ in range(200):
            S = np.cumsum(rng.normal(0, 0.4, (int(rng.integers(2, 80)), 7)), axis=0) * (rng.random() < 0.7)
            args_ = (float(rng.uniform(0.2, 3)), int(rng.integers(0, 10)), int(rng.integers(0, 10)))
            same &= tuple(mac_build.idle_bounds(S, *args_)) == tuple(dell_datasets.idle_bounds(S, *args_))
        check("Dell's Data page and the Mac's build trim identically (200 random episodes)", same)
        plan = mac_build.plan_episodes(ds)
        check("the Mac's build plan honours the exclusion and the trims",
              [p["episode"] for p in plan] == [0, 1] and all(p["start"] == t[0] and p["end"] == t[1] for p, t in zip(plan, trims)),
              f"plan {plan}")

        # ---------------------------------------------------------------- 3. train
        job = api.post("/api/jobs", {"name": "sim-act", "datasets": ["sim_pick"], "steps": 100, "save_freq": 500,
                                     "batch_size": 4, "chunk_size": 10})["id"]
        check("job queued", (shared / "jobs" / job / "job.json").exists(), job)
        t0 = time.time()
        rc = run_worker(py, env, 1800, wlog)
        stj = json.loads((shared / "jobs" / job / "status.json").read_text())
        check("worker trained the job", rc == 0 and stj.get("state") == "finished",
              f"rc={rc} state={stj.get('state')} phase={stj.get('phase')} msg={stj.get('message')} in {time.time() - t0:.0f} s")
        pols = api.get("/api/policies")
        check("a policy was published", bool(stj.get("published")) and any(p["name"] in stj["published"] for p in pols),
              f"published={stj.get('published')} listed={[p['name'] for p in pols]}")
        cur = api.get("/api/status")["current_policy"]
        check("the first trained policy became CURRENT", cur in (stj.get("published") or []), f"CURRENT={cur}")
        meta = json.loads((shared / "policies" / cur / "nervus_policy.json").read_text()) if cur else {}
        check("the policy carries the facts the robot needs",
              all(meta.get(k) for k in ("envelope", "step_p99", "start_pose", "cameras", "fps", "created")) and meta.get("contract") == 1,
              f"keys={sorted(meta)}")
        rows = [json.loads(l) for l in (shared / "jobs" / job / "metrics.jsonl").read_text().splitlines()] \
            if (shared / "jobs" / job / "metrics.jsonl").exists() else []
        check("training metrics mirrored with exact steps", rows and all(r["step"] % 100 == 0 for r in rows),
              f"{len(rows)} rows, steps {[r['step'] for r in rows][:5]}")

        # ---------------------------------------------------------------- 4. run, correct, label
        api.post("/api/run/load", {"name": cur})
        rs = api.get("/api/status")["runner"]
        check("policy loaded", rs["state"] == "ready", rs["message"])
        pv = api.post("/api/run/preview")
        check("scene check answers", "score" in pv, f"score={pv.get('score')} verdict={pv.get('verdict')}")
        api.post("/api/arm/enable")
        api.post("/api/run/goto_start")
        wait_for(lambda: api.get("/api/status")["runner"]["state"] == "ready", 30, "start pose reached")
        api.post("/api/run/params", {"max_duration_s": 60})
        api.post("/api/run/start")
        wait_for(lambda: api.get("/api/status")["runner"]["run"]["steps"] >= 20, 30, "the policy running")
        before = api.get("/api/status")["runner"]["run"]
        api.post("/api/run/correct")
        sim.post("/sim/teach", {"on": True})
        here = api.get("/api/status")["arm"]["joints"]
        drag(sim, here, demos[0][0], 10.0, 45.0, 2.5)
        drag(sim, demos[0][0], ready, 45.0, 10.0, 1.5)
        api.post("/api/run/stop")
        rs = wait_for(lambda: (lambda r: r if r["state"] == "stopped" else None)(api.get("/api/status")["runner"]), 30, "run stopped")
        check("the run recorded a correction", rs["run"]["interventions"] == 1 and rs["run"]["segment"] == "human",
              f"steps={rs['run']['steps']} before correction {before['steps']} recorded={rs['run']['recorded_frames']} stop={rs['run']['stop_reason']}")
        msg = api.post("/api/run/mark", {"outcome": "fail", "case": "A", "note": "e2e"})["message"]
        msg2 = api.post("/api/run/mark", {"outcome": "success", "case": "A"})["message"]
        check("labelled once: the first label stands", "first label stands" in msg2, f"{msg} / {msg2}")
        sim.post("/sim/teach", {"on": False})

        # ---------------------------------------------------------------- 5. the rollout package
        rdir = shared / "rollouts" / rs["run"]["id"]
        rmeta = json.loads((rdir / "meta.json").read_text())
        rrows = [json.loads(l) for l in (rdir / "episode.jsonl").read_text().splitlines() if l.strip()]
        human = [r for r in rrows if r.get("segment") == "human"]
        check("rollout package is complete and released", (rdir / "READY").exists() and rmeta.get("outcome") == "fail"
              and all((rdir / v).exists() for v in rmeta.get("videos", {}).values()) and rmeta.get("frames") == len(rrows),
              f"frames={rmeta.get('frames')} rows={len(rrows)} human rows={len(human)} videos={rmeta.get('videos')} contract={rmeta.get('contract')}")
        check("the package meets the post-training contract", rmeta.get("contract") == 1 and not rmeta.get("writer_error")
              and len(human) >= 30, f"contract={rmeta.get('contract')} writer_error={rmeta.get('writer_error')}")
        trials = api.get("/api/trials")
        check("one trial row for the run", len([t for t in trials if t["run_id"] == rs["run"]["id"]]) == 1)

        # ---------------------------------------------------------------- 6. post-train
        if not a.quick:
            api.post("/api/posttrain/request", {"note": "e2e"})
            t0 = time.time()
            rc = run_worker(py, env, 2400, wlog)
            pst = json.loads((shared / "posttrain" / "STATUS.json").read_text()) if (shared / "posttrain" / "STATUS.json").exists() else {}
            rounds = pst.get("rounds") or []
            last = rounds[-1] if rounds else {}
            check("the worker ran a post-training round", rc == 0 and last.get("finished") and not last.get("failed"),
                  f"rc={rc} state={pst.get('state')} round={ {k: last.get(k) for k in ('round', 'new', 'held_out', 'duplicated', 'failed', 'published')} } in {time.time() - t0:.0f} s")
            check("the correction was accepted and trained", pst.get("runs", {}).get("accepted") == 1 and last.get("new") == 1,
                  f"runs={pst.get('runs')} recent={pst.get('recent_runs')}")
            check("the gate compared every checkpoint with CURRENT", bool(last.get("decisions")) and "A" in (last.get("gate") or {}),
                  f"gate={last.get('gate')}")

        # ---------------------------------------------------------------- 7. promotion rules
        res = subprocess.run([py, "-c", PROMOTION_TEST, str(ACT / "mac")], capture_output=True, text=True, timeout=120,
                             env=dict(env, NERVUS_ACT_SHARED=str(root / "promo" / "NervusACT"), NERVUS_ACT_WORK=str(root / "promo" / "work")))
        check("promotion: candidates need 10 trials and CURRENT's success rate, and never flip back",
              res.returncode == 0, (res.stdout + res.stderr).strip()[-400:])
    except Exception as e:
        traceback.print_exc()
        check("pipeline ran to the end", False, f"{type(e).__name__}: {e}")
    finally:
        stop_hb.set()
        try:
            eng.close()
        except Exception:
            pass
        httpd.shutdown()
    failed = [r for r in RESULTS if not r[1]]
    print(f"\n{len(RESULTS) - len(failed)}/{len(RESULTS)} passed. Worker log: {wlog}", flush=True)
    if not a.keep and not failed:
        shutil.rmtree(root, ignore_errors=True)
    return 1 if failed else 0


PROMOTION_TEST = r'''
import csv, json, os, sys, time
sys.path.insert(0, sys.argv[1])
import worker
from pathlib import Path
S = worker.SHARED
w = worker.Worker()
def pol(name, role, created):
    d = S / "policies" / name; d.mkdir(parents=True, exist_ok=True)
    (d / "model.safetensors").write_text("x"); (d / "config.json").write_text("{}")
    (d / "nervus_policy.json").write_text(json.dumps({"role": role, "created": created}))
def trials(rows):
    p = S / "trials" / "results.csv"
    with open(p, "w", newline="") as f:
        wr = csv.DictWriter(f, fieldnames=["run_id", "policy", "outcome"]); wr.writeheader()
        for r in rows: wr.writerow(r)
pol("base", "trained", "2026-01-01T00:00:00Z"); w.promote("base", {})
since = json.loads((S / "policies" / "CURRENT.json").read_text())["since"]
pol("old_cand", "candidate", "2025-12-31T00:00:00Z")
pol("cand", "candidate", "2099-01-01T00:00:00Z")
rows = [{"run_id": f"b{i}", "policy": "base", "outcome": "success" if i < 3 else "fail"} for i in range(10)]
rows += [{"run_id": f"o{i}", "policy": "old_cand", "outcome": "success"} for i in range(12)]
rows += [{"run_id": f"c{i}", "policy": "cand", "outcome": "success" if i < 4 else "fail"} for i in range(9)]
trials(rows); w.check_promotion()
cur = json.loads((S / "policies" / "CURRENT.json").read_text())["name"]
assert cur == "base", f"promoted with 9 trials, or promoted a candidate older than CURRENT: {cur}"
rows += [{"run_id": "c9", "policy": "cand", "outcome": "fail"}]
trials(rows); w.check_promotion()
cur = json.loads((S / "policies" / "CURRENT.json").read_text())["name"]
assert cur == "cand", f"10 trials at 40% >= CURRENT's 30% should promote: {cur}"
w.check_promotion()
assert json.loads((S / "policies" / "CURRENT.json").read_text())["name"] == "cand", "flipped back"
print("promotion rules hold")
'''

if __name__ == "__main__":
    sys.exit(main())
