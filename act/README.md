# ACT: teaching the PiPER by demonstration

ACT Studio and the two programs behind it let you teach the arm a task by moving it by hand, train a policy on the
Mac, run what it learned, correct it when it fails, and let those corrections train the next version. The policy is
[ACT](https://arxiv.org/abs/2304.13705) (Action Chunking with Transformers) from LeRobot 0.4.4.

It is the successor to `legacy/piper-act`, the Windows-era project where all of this was worked out on the real
arm. The rules that project learned are built in here, and listed below.

```text
 Robot host (Ubuntu, at the arm)                               Mac (Apple silicon)
 ┌──────────────────────────────┐                              ┌───────────────────────────────┐
 │ ACT Studio (NervusOS app)    │                              │ worker.py (launchd agent)     │
 │        │ HTTP, loopback      │      ~/Shared/NervusACT      │  build the training set       │
 │ ACT engine (nervus-act)      │  ◄──── Syncthing ─────►      │  train under train_supervisor │
 │  record · edit · run · label │  LAN, or the server relay    │  publish · gate · promote     │
 │        │                     │                              └───────────────────────────────┘
 │ PiPER driver · Dabai · C270  │
 └──────────────────────────────┘
```

| Part | Where | What it does |
|---|---|---|
| `engine/nervus_act/` | Robot host, `nervus-act.service`, `127.0.0.1:8795` | Records drag-taught demonstrations into LeRobot datasets, keeps trims and exclusions as a sidecar, queues jobs, runs policies on the arm, records runs and corrections for post-training. |
| `mac/worker.py` | Mac, launchd `com.nervus.act-worker` | Builds each job's dataset (edits applied, h264 at CRF 18), trains under `train_supervisor.py`, mirrors progress, publishes checkpoints, runs post-training rounds with an offline gate, promotes on real results. |
| `src/act-studio` | NervusOS app `com.nervus.act` | The screen for all of it: Record, Data, Train, Policies, Run, Post-train, System. |
| `tests/` | anywhere with LeRobot 0.4.4 | `e2e_sim.py` runs the whole loop against a simulated arm; `test_units.py` covers the pure logic. |

## The shared folder

Everything between the two machines goes through `~/Shared/NervusACT`, which Syncthing keeps in step: directly on
the same network, through the relay on the server otherwise (`server/`). Every file has exactly one writer, so the
machines never edit the same file:

| Path | Writer | Contents |
|---|---|---|
| `datasets/<name>/` | Robot host | LeRobot v3 dataset, `nervus_dataset.json`, `thumbs/`, `traces/` |
| `datasets/<name>/nervus_edits.json` | Robot host | trims and exclusions (the video is never rewritten) |
| `jobs/<id>/job.json`, `CANCEL` | Robot host | a training request; a request to stop it |
| `jobs/<id>/status.json`, `metrics.jsonl` | Mac | progress, loss per logged step, published checkpoints |
| `policies/<name>/` | Mac | the model plus `nervus_policy.json` (envelope, step sizes, start pose, cameras) |
| `policies/CURRENT.json` | Mac | the policy in use and the evidence for it |
| `rollouts/<run>/` | Robot host | a run: `episode.jsonl`, one mp4 per camera, `meta.json`, and `READY` once labelled |
| `trials/results.csv` | Robot host | one row per labelled run |
| `posttrain/STATUS.json` | Mac | rounds, gate numbers, candidates, what was accepted |
| `posttrain/REQUEST`, `PROMOTE` | Robot host | "post-train now", "make this CURRENT" |
| `workers/mac.json` | Mac | heartbeat |

## Rules learned on the real arm

**Recording**
- `action[t] = state[t+1]`: one arm has no leader, so the action is where the arm is next.
- Finalize and reopen the dataset after every take, passing the codec every time. LeRobot keeps episode metadata in
  memory until `finalize()`, so a crash lost every take before it; reopening without the codec silently switched
  episodes 1-49 of the first dataset to AV1.
- Cameras are opened by `/dev/v4l/by-id` name, never by index (the order changes when anything is plugged in).
  Each camera has its own thread and the loop takes the newest frame; a camera below 5 fps refuses to record.
- Mains anti-flicker on, and exposure may not stretch the frame period (`exposure_dynamic_framerate=0`), or 15 fps
  becomes 12.5. udev sets both on plug-in (`hosted/libexec/camera-defaults`).

**Data**
- Idle stillness at the start and end of each take is trimmed off. 18 of the first 50 demonstrations waited more than
  2 s before moving; ACT learned "wait here" and froze at the start pose. The Data tab shows the same trim the Mac
  applies (`act/tests` checks the two implementations agree).

**Training**
- Chunk 30 (2 s at 15 Hz), not ACT's default 100.
- The supervisor resumes from the last *complete* checkpoint after a crash, a hang (log silent for 15 minutes) or a
  NaN loss, and the worker warns when training crawls (more than 6x slower than its best step in the job, usually
  memory pressure on a 16 GB Mac).
- A running job outlives the worker: a restarted worker re-attaches to the supervisor instead of starting a second
  trainer on the same output.
- About four checkpoints from the second half of the run are published, the final one always; each is ~200 MB.

**Running**
- Inference runs in its own process (sharing the GIL doubled its time) and plans asynchronously: one inference takes
  350-700 ms on the robot host's CPU.
- The step limit applies to the command, not the measurement (the gripper's 0.5-0.8 s dead time pinned it half open).
- Joints are averaged over the newest two plans; the gripper never is (the mean of open and closed dropped the cube).
- A grasp latch holds the gripper at "measured minus a little squeeze" once the fingers stop on an object, and lets
  go only after several open requests in a row. Grip effort 500: 1000 crushed a printed cube.
- A stale camera or arm reading holds the last command for up to a second before ending the run.
- The scene check asks the policy what it would do from here: a policy that would sit still is seeing a scene unlike
  its demonstrations (different light, a moved camera).

**Post-training**
- Corrections are recorded, not lost: Correct (or the arm's teach button) mid-run hands the arm over and records your
  hands as a "human" segment. Each contiguous human segment of 30 rows or more becomes an episode, success or fail;
  a whole run only if it succeeded untouched; a failed policy segment never.
- About 15% of new corrections are held out as gate set B. Corrections below 15% of the round's episodes are written
  twice (LeRobot 0.4.4 has no sampling weights).
- The offline gate: a candidate must stay within 1.10x of CURRENT's error on base episodes (no forgetting) and beat
  it on the held-out corrections.
- Promotion only on real results: 10 labelled runs and a success rate no lower than CURRENT's last 10. Only
  candidates published after CURRENT took over count, so two equal ones never swap back and forth.

## Setting it up

On the robot host, `sudo hosted/install-hosted.sh` installs the engine (CPU torch, LeRobot 0.4.4) as `nervus-act.service`,
which starts and stops with NervusOS. Install ACT Studio from the App Store. The cameras it expects are set on the
service's command line: `wrist=Dabai_DC1,scene=046d_0825` (by-id substrings).

On the Mac:

```sh
act/mac/install.sh            # finds or creates a Python with LeRobot 0.4.4, installs the launchd agent
tail -f ~/NervusACT-work/worker.log
```

Both machines need Syncthing sharing `~/Shared` (folder ID `mac-dell-shared`).

## Testing

```sh
PY=~/NervusOS/robots/vla/.venv/bin/python     # any Python with LeRobot 0.4.4
$PY act/tests/test_units.py                     # seconds
$PY act/tests/e2e_sim.py                        # the whole loop on a simulated arm, ~10 min with post-training
```

`python -m nervus_act --sim` runs the engine on the simulated arm and cameras, so ACT Studio can be tried with no
hardware.

## When something is wrong

| Symptom | Cause and fix |
|---|---|
| A camera shows 0 fps and says it is on the Thunderbolt controller | The robot host's USB-C/Thunderbolt USB keeps resetting the C270. Plug it into a USB-A port. |
| The Mac shows OFFLINE | `launchctl print gui/$UID/com.nervus.act-worker`; the log is `~/NervusACT-work/worker.log`. Queued jobs wait. |
| A job says "waiting for dataset(s)" | Syncthing has not finished carrying the recording to the Mac; the System tab shows how much is left. |
| A job warns it is many times slower | The Mac is short of memory. Close large apps (a local LLM is enough to do it). |
| The policy "would likely sit still" | The scene or light differs from the demonstrations, or a camera moved. Fix the scene, not the policy. |
| "another app is controlling the arm" | Robot Arm or Hand Teleop holds it. Close them or press Stop there. |
