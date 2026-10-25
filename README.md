# PiPER ACT Studio

Teach an AgileX PiPER a task by moving it by hand, train an ACT policy, run what it learned, correct it when
it fails, and let those corrections train the next version, all from one desktop app.

- **Record** drag-taught demonstrations from a wrist and a scene camera straight into LeRobot datasets.
- **Edit** takes without rewriting video: trim idle starts and ends, exclude bad takes.
- **Train** on another machine (a Mac with Apple silicon today, a rented GPU next) through a shared folder,
  with live loss curves, hang detection and resumable runs.
- **Run** any published policy on the arm with the safeguards learned on the real arm: step and lead
  limits, an envelope around the demonstrations, a start pose, a dead-man heartbeat.
- **Post-train**: take over when a run fails, record the correction, and train candidates on it. A candidate
  replaces the current policy only after it passes an offline gate and then wins real runs.

The policy is [ACT](https://arxiv.org/abs/2304.13705) (Action Chunking with Transformers), from
[LeRobot](https://github.com/huggingface/lerobot) 0.4.4.

```
 Robot host (Ubuntu, at the arm)                               Training machine (Mac today)
 ┌──────────────────────────────┐                              ┌───────────────────────────────┐
 │ ACT Studio (desktop app)     │                              │ act/mac/worker.py (launchd)   │
 │        │ HTTP, loopback      │      ~/Shared/NervusACT      │  build the training set       │
 │ ACT engine 127.0.0.1:8795    │  ◄──── Syncthing ─────►      │  train under a supervisor     │
 │  record · edit · run · label │   (one writer per file)      │  publish · gate · promote     │
 │        │                     │                              └───────────────────────────────┘
 │ PiPER driver · cameras       │
 └──────────────────────────────┘
```

`act/README.md` explains the shared-folder contract, every rule built in, and the post-training loop.

## Hardware

- AgileX PiPER on a USB-CAN adapter (`can0`)
- A wrist camera and a fixed scene camera (UVC; opened by their `/dev/v4l/by-id` names)
- The robot host: Ubuntu 24.04. The training machine: a Mac with Apple silicon (MPS), or any Linux box with
  a GPU and LeRobot 0.4.4
- [Syncthing](https://syncthing.net) between the two, sharing `~/Shared`

## Install

On the robot host:

```sh
git clone https://github.com/izhangfly/piper-act-studio && cd piper-act-studio
sudo ./install.sh
```

On the training machine (Mac): `act/mac/install.sh` (a launchd agent; Python with LeRobot 0.4.4).
Share `~/Shared` between them with Syncthing, then open **ACT Studio** on the robot host.

Camera names are set in `/etc/systemd/system/nervus-act.service`
(`--cameras wrist=Dabai_DC1,scene=046d_0825`: part of each camera's by-id name).

## Try it without an arm

```sh
PYTHONPATH=act/engine python -m nervus_act --sim --shared /tmp/acttest/NervusACT --port 8899
python act/tests/e2e_sim.py           # the whole loop against the simulated arm
cd act-studio && ACT_ENGINE_TEST=http://127.0.0.1:8899 ./gradlew test   # the app's UI test
```

## Repository

| Path | What |
|---|---|
| `act/engine/` | The ACT engine (Python): recorder, dataset edits, runner with safeguards, post-training labels |
| `act/mac/` | The training worker: dataset builder, train supervisor, gate, promotion |
| `act/tests/` | End-to-end test on a simulated arm, unit tests |
| `act-studio/` | The app (Kotlin, Compose Desktop) |
| `driver/` | The PiPER driver (Python, piper_sdk): HTTP API on loopback, safety layer, systemd units, and **PiPER Control**, its joint/Cartesian panel as a desktop app |
| `desktop/` | Desktop entry, icon, `nervus-standalone` launcher |
| `nervus-app-sdk/`, `nervus-packaging/`, `nervus-ipc/` | NervusOS app SDK, used when it runs inside NervusOS |

## Credits

Author: **Ian Zhang** ([@izhangfly](https://github.com/izhangfly)).

Built on [LeRobot](https://github.com/huggingface/lerobot) (Apache-2.0) and ACT (Zhao et al., RSS 2023),
with [AgileX piper_sdk](https://github.com/agilexrobotics/piper_sdk) and
[Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform). See `NOTICE.md`.

## Licence

GPL-3.0 (see `LICENSE`).
