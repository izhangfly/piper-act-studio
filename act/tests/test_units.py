"""Fast unit tests for the ACT pipeline's pure logic (no LeRobot, no hardware).

    python3 -m pytest act/tests/test_units.py      or      python3 act/tests/test_units.py
"""
import sys
from pathlib import Path

ACT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ACT / "mac"))
sys.path.insert(0, str(ACT / "engine"))


def published_steps(steps, save_freq):
    """The checkpoints the supervisor would publish under the worker's plan (its own rule, restated)."""
    import worker
    every, first = worker.Worker.publish_plan(steps, save_freq)
    ckpts = sorted(set(list(range(save_freq, steps + 1, save_freq)) + [steps]))
    return [s for s in ckpts if s >= first and (s % every == 0 or s == steps)]


def test_publish_plan_always_publishes_the_final_checkpoint():
    for steps, save in [(100, 500), (500, 500), (7000, 5000), (20000, 5000), (60000, 5000), (100000, 5000), (100000, 10000)]:
        p = published_steps(steps, save)
        assert p and p[-1] == steps, (steps, save, p)


def test_publish_plan_publishes_about_four_from_the_second_half():
    for steps, save in [(20000, 5000), (60000, 5000), (100000, 5000), (100000, 10000)]:
        p = published_steps(steps, save)
        assert 2 <= len(p) <= 5 and p[0] >= steps * 0.3, (steps, save, p)


def _store(tmp):
    from nervus_act.store import Store
    return Store(tmp)


def test_job_names_are_limited_so_job_ids_stay_valid_names(tmp_path=None):
    import tempfile
    from nervus_act.store import NAME_RE
    st = _store(Path(tempfile.mkdtemp()))
    jid = st.new_job({"name": "a" * 40})
    assert NAME_RE.match(jid), jid
    try:
        st.new_job({"name": "a" * 41})
    except ValueError as e:
        assert "40" in str(e)
    else:
        raise AssertionError("a 41-character job name was accepted")


def test_trim_edits_are_bounded_and_ordered():
    import tempfile
    from nervus_act.datasets import Datasets
    root = Path(tempfile.mkdtemp())
    st = _store(root)
    (st.dataset_dir("d") / "meta").mkdir(parents=True)
    (st.dataset_dir("d") / "meta" / "info.json").write_text("{}")
    ds = Datasets(st)
    e = ds.set_edits("d", {"keep_before": 999, "threshold_deg": -1, "episodes": {"0": {"start": -5, "end": 40}}})
    assert e["keep_before"] == 150 and e["threshold_deg"] == 0.1 and e["episodes"]["0"] == {"start": 0, "end": 40}, e
    try:
        ds.set_edits("d", {"episodes": {"1": {"start": 50, "end": 10}}})
    except ValueError:
        pass
    else:
        raise AssertionError("a trim ending before it starts was accepted")


def test_a_policy_is_loadable_only_when_every_file_has_arrived():
    import json
    import tempfile
    st = _store(Path(tempfile.mkdtemp()))
    d = st.root / "policies" / "p1"
    d.mkdir(parents=True)
    (d / "model.safetensors").write_text("x")
    (d / "config.json").write_text("{}")
    p = {x["name"]: x for x in st.policies()}["p1"]
    assert not p["complete"] and "nervus_policy.json" in p["missing"], p
    (d / "nervus_policy.json").write_text("{}")
    for pj, sf in (("policy_preprocessor.json", "pre.safetensors"), ("policy_postprocessor.json", "post.safetensors")):
        (d / pj).write_text(json.dumps({"steps": [{"state_file": sf}]}))
    p = {x["name"]: x for x in st.policies()}["p1"]
    assert not p["complete"] and set(p["missing"]) == {"pre.safetensors", "post.safetensors"}, p
    (d / "pre.safetensors").write_text("x")
    (d / "post.safetensors").write_text("x")
    assert {x["name"]: x for x in st.policies()}["p1"]["complete"]


if __name__ == "__main__":
    import inspect
    fails = 0
    for name, fn in sorted(inspect.getmembers(sys.modules[__name__], inspect.isfunction)):
        if name.startswith("test_"):
            try:
                fn()
                print(f"PASS {name}")
            except AssertionError as e:
                fails += 1
                print(f"FAIL {name}: {e}")
    sys.exit(1 if fails else 0)
