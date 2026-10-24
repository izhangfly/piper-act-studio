"""Offline gate for post-training candidates: how far a policy's chunk is from what was actually done.

Mean absolute error of the predicted chunk against the recorded actions, in degrees (millimetres for the
gripper), sampled every 15th frame. ACT inference is deterministic (the CVAE latent is zero at inference,
no dropout), so there is no seed. Every policy sees identical samples; "hold still" (predicting the
current state for the whole chunk) is the baseline anything useful must beat by a wide margin.

Rules, from the Piper-ACT post-training spec:
    set A (no forgetting):        candidate <= 1.10 x CURRENT on base episodes
    set B (learned corrections):  candidate <  CURRENT on held-out human corrections (skipped if empty)
"""
from __future__ import annotations

from pathlib import Path

import numpy as np

from build import episode_samples


class PolicyRunner:
    def __init__(self, policy_dir: Path, device: str = "mps"):
        import torch
        from lerobot.policies.act.modeling_act import ACTPolicy
        from lerobot.policies.factory import make_pre_post_processors
        if device == "mps" and not torch.backends.mps.is_available():
            device = "cpu"
        self.torch = torch
        self.device = torch.device(device)
        self.model = ACTPolicy.from_pretrained(str(policy_dir))
        self.model.config.device = device
        self.model.to(self.device).eval()
        dev = {"device_processor": {"device": device}}
        self.pre, self.post = make_pre_post_processors(self.model.config, pretrained_path=str(policy_dir),
                                                       preprocessor_overrides=dev, postprocessor_overrides=dev)
        self.chunk_size = int(self.model.config.chunk_size)
        self.image_keys = [k for k in self.model.config.input_features if k.startswith("observation.images.")]

    def chunk(self, state, images: dict) -> np.ndarray:
        from lerobot.policies.utils import prepare_observation_for_inference
        obs = {"observation.state": np.asarray(state, dtype=np.float32)}
        for k in self.image_keys:
            obs[k] = images[k.removeprefix("observation.images.")]
        with self.torch.inference_mode():
            batch = self.pre(prepare_observation_for_inference(obs, self.device, None, "agilex_piper"))
            actions = self.model.predict_action_chunk(batch)
            return np.stack([self.post(actions[:, i, :]).squeeze(0).cpu().numpy() for i in range(actions.shape[1])])


def evaluate(policies: dict[str, Path], sets: dict[str, tuple[Path, str, list[int]]], log=print) -> dict:
    runners = {name: PolicyRunner(p) for name, p in policies.items()}
    out = {}
    for set_name, (root, repo_id, episodes) in sets.items():
        if not episodes:
            out[set_name] = {"samples": 0, "episodes": [], "skipped": "no episodes"}
            continue
        err = {name: [] for name in runners}
        hold = []
        for _e, _t, s, imgs, acts in episode_samples(root, repo_id, episodes):
            n = min(len(acts), min(r.chunk_size for r in runners.values()))
            target = acts[:n]
            hold.append(float(np.abs(target - s[None, :]).mean()))
            for name, r in runners.items():
                err[name].append(float(np.abs(r.chunk(s, imgs)[:n] - target).mean()))
        out[set_name] = {"samples": len(hold), "episodes": episodes, "hold_still": round(float(np.mean(hold)), 4),
                         **{name: round(float(np.mean(v)), 4) for name, v in err.items()}}
        log(f"gate set {set_name}: {out[set_name]}")
    return out


def decide(metrics: dict, candidate: str, current: str) -> dict:
    a = metrics["A"]
    a_ok = a[candidate] <= 1.10 * a[current]
    b = metrics.get("B", {})
    if b.get("samples"):
        b_ok = b[candidate] < b[current]
        b_note = f"B {b[candidate]} < CURRENT {b[current]}: {b_ok}"
    else:
        b_ok, b_note = True, "B empty (no held-out corrections): not applicable"
    score = a[candidate] / a[current] + (b[candidate] / b[current] if b.get("samples") else a[candidate] / a[current])
    return {"pass": bool(a_ok and b_ok), "A_ok": bool(a_ok), "B_ok": bool(b_ok), "score": round(score, 4),
            "A_rule": f"{a[candidate]} <= 1.10 x {a[current]} = {round(1.10 * a[current], 4)}", "B_rule": b_note}
