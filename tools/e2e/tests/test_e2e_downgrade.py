"""The downgrade run (docs/v0.4/SPEC.md AC3.2; plan review H-M1): the released 0.3.0 starts on files 0.4 wrote (the
v040-written fixture sets), then 0.4 starts again."""

import gzip
import json
import os
import shutil
import sys
import tempfile
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import e2e_checks  # noqa: E402
import e2e_env  # noqa: E402
import self_update_e2e  # noqa: E402
import written  # noqa: E402
from test_self_update_e2e import make_run  # noqa: E402

REPO = Path(__file__).resolve().parents[3]
ROOT = REPO / "src" / "test" / "resources" / "v040-written"
OLD = "0.3.0+mc26.2"
OFF = "e2e-downgrade-off-1.0.0.jar"
SWITCH = "c3e7a1f6-8d0b-4e2f-9b3c-7f5e6d349c17"
STAGED_OP = "0a4f3c1e-5b7d-4e2a-9c61-7d2f1b8e4a01"


def failing(checks):
    return sorted(c.name for c in checks if not c.ok)


class Downgrade:
    """An instance composed from the placeholder sets, then as 0.3.0 leaves it: Undo last on the switch, its own Apply
    (disable the test mod), and its helper having applied that and 0.4's staged enable."""

    def __init__(self):
        self.root = Path(tempfile.mkdtemp())
        self.instance = self.root / "instance"
        written.compose(written.resolve(ROOT), self.instance)
        self.config = self.instance / "config" / "rigtune"
        self.mods = self.instance / "mods"
        self.mods.mkdir()
        self.seeded = self_update_e2e.seeded_state(self.instance)
        history = json.loads((self.config / "history.json").read_text(encoding="utf-8"))
        for e in history["entries"]:
            for c in e["changes"]:
                if e["id"] == SWITCH:
                    c["status"] = "REVERTED"
                if c.get("opId") == STAGED_OP:
                    c["status"] = "APPLIED"
                c.pop("modName", None)  # 0.3.0's rewrite drops fields it doesn't know
        history["entries"].append({"id": "u", "at": "2026-09-26T03:00:00Z", "kind": "undo", "rigtuneVersion": OLD, "undoOf": SWITCH, "changes": [
            {"id": "r1", "type": "setting", "key": "vanilla.renderDistance", "before": "8", "after": "12", "status": "APPLIED", "reverts": "d4f8b207-9e1c-4f30-8c4d-806f7e45ad18"},
            {"id": "r2", "type": "setting", "key": "vanilla.maxFps", "before": "60", "after": "120", "status": "APPLIED", "reverts": "e5a9c318-af2d-4041-9d5e-917a8f56be19"}]})
        history["entries"].append({"id": "own", "at": "2026-09-26T03:00:10Z", "kind": "apply", "rigtuneVersion": OLD, "changes": [
            {"id": "o1", "type": "file", "action": "disable", "modId": "e2e-downgrade-off", "file": OFF, "status": "APPLIED"}]})
        (self.config / "history.json").write_text(json.dumps(history, indent=2), encoding="utf-8")
        (self.config / "pending.json").unlink()
        (self.config / "last-apply.json").write_text(json.dumps({"finishedAt": "2026-09-26T03:01:00Z", "results": [
            {"op": {"type": "ENABLE_FILE", "from": str(self.mods / "e2e-seed-1.0.0.jar.rigtune-pending"), "to": str(self.mods / "e2e-seed-1.0.0.jar"),
                    "id": STAGED_OP}, "status": "OK"},
            {"op": {"type": "DISABLE_FILE", "path": str(self.mods / OFF), "id": "x"}, "status": "OK"}]}), encoding="utf-8")
        (self.mods / (OFF + ".disabled")).write_bytes(b"jar")
        (self.mods / "e2e-seed-1.0.0.jar").write_bytes(b"jar")
        ids = [e["id"] for e in history["entries"]]
        self.driver = {"ok": True, "rigtuneVersion": OLD,
                       "history": {"state": "OK", "entries": [{"id": i, "kindKey": "rigtune.history.kind.apply"} for i in ids[:3]]},
                       "undoPlan": {"undoOf": SWITCH, "problem": None, "items": ["REVERT a", "REVERT b"]}}
        self.log = "[03:00:00] [Render thread/INFO] (RigTune) Rules r13\n[03:00:01] [Render thread/WARN] (Minecraft) something\n"

    def old(self):
        return e2e_checks.after_downgrade_old(self.instance, self.driver, self.seeded, OLD, OFF, self.log)


class SeededStateTest(unittest.TestCase):
    def test_records_what_0_4_wrote(self):
        instance = Path(tempfile.mkdtemp()) / "instance"
        written.compose(written.resolve(ROOT), instance)
        seeded = self_update_e2e.seeded_state(instance)
        self.assertEqual(3, len(seeded["entries"]))
        self.assertEqual([STAGED_OP], [op["id"] for op in seeded["pendingOps"]])
        self.assertEqual(sorted(written.NEW_FILES), sorted(seeded["newFiles"]))
        self.assertEqual(SWITCH, seeded["undoLast"])


class LogProblemsTest(unittest.TestCase):
    def test_rigtune_errors_stack_frames_and_newer_file_refusals(self):
        log = "\n".join([
            "[10:00:00] [Render thread/INFO] (RigTune) fine",
            "[10:00:01] [Render thread/ERROR] (RigTune) Could not read history.json",
            "[10:00:02] [Render thread/ERROR] (Minecraft) unrelated",
            "\tat io.github.chaotix345.rigtune.core.history.Journal.read(Journal.java:120)",
            "[10:00:03] [Render thread/WARN] (RigTune) Could not record 1 RigTune change(s) in x: it was written by a newer RigTune",
            "[10:00:04] [Render thread/INFO] (RigTune E2E downgrade) [E2E] t+1.0s FAILED: nothing",
        ])
        self.assertEqual(3, len(e2e_checks.rigtune_log_problems(log)))

    def test_the_session_log_includes_logs_rotated_during_the_launch(self):
        instance = Path(tempfile.mkdtemp())
        (instance / "logs").mkdir()
        old = instance / "logs" / "2026-09-25-1.log.gz"
        with gzip.open(old, "wt", encoding="utf-8") as f:
            f.write("before the launch\n")
        os.utime(old, (time.time() - 3600, time.time() - 3600))
        rotated = instance / "logs" / "2026-09-26-1.log.gz"
        with gzip.open(rotated, "wt", encoding="utf-8") as f:
            f.write("before midnight\n")
        (instance / "logs" / "latest.log").write_text("after midnight\n", encoding="utf-8")
        text = self_update_e2e.session_log(instance, time.time() - 60)
        self.assertEqual("before midnight\nafter midnight\n", text)


class AfterDowngradeOldTest(unittest.TestCase):
    def setUp(self):
        self.fx = Downgrade()

    def test_passes(self):
        self.assertEqual([], failing(self.fx.old()))

    def test_a_rigtune_error_in_the_log(self):
        self.fx.log += "[03:00:02] [Render thread/ERROR] (RigTune) Could not read benchmarks.json\n"
        self.assertEqual(["latest.log: no RigTune ERROR, stack trace or refusal of a newer file"], failing(self.fx.old()))

    def test_history_missing_an_entry(self):
        self.fx.driver["history"]["entries"].pop()
        self.assertEqual(["History lists every entry 0.4 wrote (state OK)"], failing(self.fx.old()))

    def test_undo_last_of_another_entry(self):
        self.fx.driver["undoPlan"]["undoOf"] = "5d1c7a90-2e4b-4f6a-8b3c-1a9e0d7f2c11"
        self.assertIn("Undo last reverted the newest undoable entry, recorded by 0.3.0", failing(self.fx.old()))

    def test_a_new_file_changed(self):
        (self.fx.config / "stutter.json").write_text("{}", encoding="utf-8")
        self.assertEqual(["the files only 0.4 writes are byte-identical"], failing(self.fx.old()))

    def test_own_apply_not_applied(self):
        (self.fx.mods / (OFF + ".disabled")).rename(self.fx.mods / OFF)
        self.assertEqual(["0.3.0's own Apply staged and applied (disable {})".format(OFF)], failing(self.fx.old()))

    def test_0_4_staged_op_not_applied(self):
        last = json.loads((self.fx.config / "last-apply.json").read_text(encoding="utf-8"))
        last["results"][0]["status"] = "FAILED"
        (self.fx.config / "last-apply.json").write_text(json.dumps(last), encoding="utf-8")
        self.assertIn("0.4's staged ops (with projectId) applied by 0.3.0's helper", failing(self.fx.old()))

    def test_a_bad_file(self):
        (self.fx.config / "benchmarks.json.bad").write_text("x", encoding="utf-8")
        self.assertEqual(["no .bad file, no crash report"], failing(self.fx.old()))


class AfterDowngradeNewTest(unittest.TestCase):
    def setUp(self):
        self.fx = Downgrade()
        self.new = e2e_env.test_mod_jar(self.fx.root / "rigtune-0.4.0-dev+mc26.2.jar", "rigtune", "0.4.0-dev+mc26.2")
        shutil.copy(self.new, self.fx.mods / self.new.name)
        ids = [e["id"] for e in json.loads((self.fx.config / "history.json").read_text(encoding="utf-8"))["entries"]]
        self.driver = {"ok": True, "rigtuneVersion": "0.4.0-dev+mc26.2", "rigtuneOrigin": [str(self.fx.mods / self.new.name)],
                       "history": {"state": "OK", "entries": [{"id": i, "kindKey": "rigtune.history.kind.apply"} for i in ids]}}

    def check(self):
        return e2e_checks.after_downgrade_new(self.fx.instance, self.driver, self.new, self.fx.seeded, self.fx.log)

    def test_passes(self):
        self.assertEqual([], failing(self.check()))

    def test_a_label_lost(self):
        profiles = json.loads((self.fx.config / "profiles.json").read_text(encoding="utf-8"))
        profiles["switches"] = []
        (self.fx.config / "profiles.json").write_text(json.dumps(profiles), encoding="utf-8")
        self.assertEqual(["profiles.json still labels the switch entries 0.3.0 kept"], failing(self.check()))

    def test_a_new_file_reset(self):
        (self.fx.config / "startup-times.json").write_text(json.dumps({"formatVersion": 1, "runs": []}), encoding="utf-8")
        self.assertEqual(["0.4 read its own files back (none reset or moved to .bad)"], failing(self.check()))

    def test_0_4_may_append(self):
        times = json.loads((self.fx.config / "startup-times.json").read_text(encoding="utf-8"))
        times["runs"].append({"at": "2026-09-26T03:05:00Z", "ms": 14000})
        (self.fx.config / "startup-times.json").write_text(json.dumps(times), encoding="utf-8")
        self.assertEqual([], failing(self.check()))

    def test_history_view_short(self):
        self.driver["history"]["entries"].pop()
        self.assertEqual(["History lists every entry of history.json (state OK)"], failing(self.check()))


class DowngradeScenarioTest(unittest.TestCase):
    def test_phases_and_driver(self):
        run = make_run(Path(tempfile.mkdtemp()), "--scenario", "downgrade")
        self.assertEqual(["downgrade-old", "downgrade-new"], list(run.checks))
        self.assertEqual([":26.2:x", "-Pe2e.driver=downgrade", "-Pe2e.oldJar=" + str(Path("a.jar").resolve())], run.driver_args(":26.2:x"))

    def test_needs_the_old_jar(self):
        with self.assertRaises(SystemExit):
            self_update_e2e.parse_args(["--name", "n", "--scenario", "downgrade", "--new-jar", "b.jar", "--work", "w", "--java-home", "jdk"])


if __name__ == "__main__":
    unittest.main()
