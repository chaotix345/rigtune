import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import e2e_checks  # noqa: E402
import e2e_env  # noqa: E402
from test_e2e_checks import InstanceFixture  # noqa: E402

LEGACY = "e2e-legacy-1.0.0.jar"


def names(checks):
    return sorted(c.name for c in checks if not c.ok)


class SelfUpdateWithAnotherChangeTest(unittest.TestCase):
    """The final v0.1.0 run also disables a test mod, so the 0.2 legacy import has something that isn't RigTune's."""

    def setUp(self):
        self.fx = InstanceFixture()
        e2e_env.test_mod_jar(self.fx.jars / LEGACY, "e2e-legacy")
        shutil.copy(self.fx.jars / LEGACY, self.fx.mods / (LEGACY + ".disabled"))
        last = json.loads((self.fx.rigtune_config / "last-apply.json").read_text())
        last["results"].append({"op": {"type": "DISABLE_FILE", "path": str(self.fx.mods / LEGACY)}, "status": "OK",
                                "message": "Disabled {0} -> {0}.disabled".format(LEGACY)})
        (self.fx.rigtune_config / "last-apply.json").write_text(json.dumps(last))

    def after_update(self):
        return e2e_checks.after_update(self.fx.instance, self.fx.old, self.fx.new, self.fx.driver, self.fx.server_log,
                                       self.fx.helper_cmdlines, extra_disables=[LEGACY])

    def test_passes_with_the_extra_disable(self):
        self.assertEqual([], names(self.after_update()))

    def test_extra_disable_missing(self):
        (self.fx.mods / (LEGACY + ".disabled")).unlink()
        (self.fx.mods / LEGACY).write_bytes(b"x")
        self.assertEqual(["the other mod 0.1.0 changed is disabled"], names(self.after_update()))

    def test_without_the_extra_op_in_last_apply(self):
        self.fx.write_last_apply("OK", "OK")
        self.assertEqual(["last-apply.json: the update's two ops, all OK"], names(self.after_update()))

    def test_history_must_hold_the_other_change(self):
        mods_before = e2e_checks.listing(self.fx.mods)
        (self.fx.rigtune_config / "rigtune.json").write_text(json.dumps({"goal": "QUALITY", "lastShownApply": "2026-09-25T01:00:00Z"}))
        driver = {"ok": True, "rigtuneVersion": "0.2.0-dev+mc26.2", "rigtuneOrigin": [str(self.fx.mods / self.fx.new.name)],
                  "goal": "QUALITY", "reportOnline": True, "updateOffered": False}
        history = {"formatVersion": 1, "entries": [{"kind": "legacy-import", "changes": []}]}
        name = "history.json: one legacy import, without RigTune's own jars"

        def failing():
            (self.fx.rigtune_config / "history.json").write_text(json.dumps(history))
            return names(e2e_checks.after_verify(self.fx.instance, self.fx.new, driver, "2026-09-25T01:00:00Z", mods_before,
                                                 True, legacy_disables=[LEGACY]))

        self.assertEqual([name], failing())
        history["entries"][0]["changes"] = [{"type": "file", "action": "disable", "modId": "e2e-legacy", "file": LEGACY,
                                             "status": "APPLIED"}]
        self.assertEqual([], failing())
        history["entries"][0]["changes"][0]["status"] = "STAGED"
        self.assertEqual([name], failing())


class UndoFixture:
    """A 0.2 instance after the undo scenario's first launch: the added mod enabled, the other one disabled."""

    ADDED = "e2e-added-1.0.0.jar"
    OTHER = "e2e-disable-me-1.0.0.jar"

    def __init__(self):
        self.root = Path(tempfile.mkdtemp())
        self.added_jar = e2e_env.test_mod_jar(self.root / self.ADDED, "e2e-added")
        self.other_jar = e2e_env.test_mod_jar(self.root / self.OTHER, "e2e-disable-me")
        self.instance = self.root / "instance"
        self.mods = self.instance / "mods"
        self.config = self.instance / "config" / "rigtune"
        self.mods.mkdir(parents=True)
        self.config.mkdir(parents=True)
        shutil.copy(self.added_jar, self.mods / self.ADDED)
        shutil.copy(self.other_jar, self.mods / (self.OTHER + ".disabled"))
        self.last_apply([("ENABLE_FILE", self.ADDED), ("DISABLE_FILE", self.OTHER)])
        self.apply_changes = [
            {"id": "c1", "type": "file", "action": "enable", "modId": "e2e-added", "file": self.ADDED, "status": "APPLIED", "opId": "o1"},
            {"id": "c2", "type": "file", "action": "disable", "modId": "e2e-disable-me", "file": self.OTHER, "status": "APPLIED", "opId": "o2"},
        ]
        self.entries = [{"id": "e1", "kind": "apply", "undoOf": None, "changes": self.apply_changes}]
        self.write_history()
        self.driver = {"ok": True, "phase": "mod-apply"}

    def last_apply(self, ops):
        results = []
        for kind, name in ops:
            op = {"type": kind, "path": str(self.mods / name)} if kind == "DISABLE_FILE" else \
                {"type": kind, "from": str(self.mods / (name + ".x")), "to": str(self.mods / name)}
            results.append({"op": op, "status": "OK", "message": ""})
        (self.config / "last-apply.json").write_text(json.dumps({"finishedAt": "t", "results": results}))

    def write_history(self):
        (self.config / "history.json").write_text(json.dumps({"formatVersion": 1, "entries": self.entries}))

    def undone(self):
        """As the instance is after the undo launch and its helper run."""
        (self.mods / self.ADDED).rename(self.mods / (self.ADDED + ".disabled"))
        (self.mods / (self.OTHER + ".disabled")).rename(self.mods / self.OTHER)
        self.last_apply([("DISABLE_FILE", self.ADDED), ("ENABLE_FILE", self.OTHER)])
        for change in self.apply_changes:
            change["status"] = "REVERTED"
        self.entries.append({"id": "e2", "kind": "undo", "undoOf": "e1", "changes": [
            {"id": "u1", "type": "file", "action": "disable", "file": self.ADDED, "status": "APPLIED", "reverts": "c1"},
            {"id": "u2", "type": "file", "action": "enable", "file": self.OTHER, "status": "APPLIED", "reverts": "c2"}]})
        self.write_history()
        self.driver = {"ok": True, "phase": "mod-undo", "undoOf": "e1",
                       "undoPlan": [{"action": "REVERT", "needsRestart": True}, {"action": "REVERT", "needsRestart": True}]}


class ModApplyTest(unittest.TestCase):
    def setUp(self):
        self.fx = UndoFixture()

    def failing(self):
        return names(e2e_checks.after_mod_apply(self.fx.instance, self.fx.added_jar, self.fx.OTHER, self.fx.driver))

    def test_passes(self):
        self.assertEqual([], self.failing())

    def test_change_still_staged(self):
        self.fx.apply_changes[0]["status"] = "STAGED"
        self.fx.write_history()
        self.assertEqual(["history.json: one apply entry, both changes APPLIED"], self.failing())

    def test_added_mod_missing_and_other_still_active(self):
        (self.fx.mods / self.fx.ADDED).unlink()
        (self.fx.mods / (self.fx.OTHER + ".disabled")).rename(self.fx.mods / self.fx.OTHER)
        self.assertEqual(["the added mod is in mods (the served bytes)", "the other mod is disabled"], self.failing())


class ModUndoTest(unittest.TestCase):
    def setUp(self):
        self.fx = UndoFixture()
        self.fx.undone()

    def failing(self):
        return names(e2e_checks.after_mod_undo(self.fx.instance, self.fx.ADDED, self.fx.OTHER, self.fx.driver, "e1"))

    def test_passes(self):
        self.assertEqual([], self.failing())

    def test_originals_not_reverted(self):
        self.fx.apply_changes[1]["status"] = "APPLIED"
        self.fx.write_history()
        self.assertEqual(["history.json: the undo APPLIED, the apply's changes REVERTED"], self.failing())

    def test_plan_that_skipped_something(self):
        self.fx.driver["undoPlan"][1] = {"action": "SKIP", "needsRestart": False, "reason": "x"}
        self.assertEqual(["the driver undid the last apply (two reverts after a restart)"], self.failing())

    def test_files_not_reverted(self):
        (self.fx.mods / self.fx.OTHER).rename(self.fx.mods / (self.fx.OTHER + ".disabled"))
        self.assertEqual(["the other mod is back"], self.failing())


class ModCheckTest(unittest.TestCase):
    def setUp(self):
        self.fx = UndoFixture()
        self.fx.undone()
        self.before = e2e_checks.listing(self.fx.mods)
        self.statuses = e2e_checks.history_statuses(self.fx.instance)
        self.driver = {"ok": True, "loadedMods": ["rigtune", "e2e-disable-me"], "undoableItems": 0}

    def failing(self):
        return names(e2e_checks.after_mod_check(self.fx.instance, "e2e-added", "e2e-disable-me", self.driver, self.before,
                                                self.statuses))

    def test_passes(self):
        self.assertEqual([], self.failing())

    def test_added_mod_still_loaded(self):
        self.driver["loadedMods"].append("e2e-added")
        self.assertEqual(["the undone mods are as before the apply"], self.failing())

    def test_something_left_to_undo_and_history_changed(self):
        self.driver["undoableItems"] = 1
        self.fx.apply_changes[0]["status"] = "APPLIED"
        self.fx.write_history()
        self.assertEqual(["history.json statuses unchanged", "nothing left to undo"], self.failing())


if __name__ == "__main__":
    unittest.main()
