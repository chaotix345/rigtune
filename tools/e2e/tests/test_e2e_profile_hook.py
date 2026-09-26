"""The Phase 5 hook in the undo scenario (docs/v0.4/plans/ws-h.md T4): undo-after-restart of a profile-switch entry,
which is an ordinary apply entry of setting changes (docs/research/v0.4/profiles.md), labelled in profiles.json."""

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import e2e_checks  # noqa: E402
import self_update_e2e  # noqa: E402
from test_self_update_e2e import make_run  # noqa: E402

TARGETS = {"renderDistance": "6", "maxFps": "90"}
ORIGINALS = {"renderDistance": "12", "maxFps": "120"}


def failing(checks):
    return sorted(c.name for c in checks if not c.ok)


class ProfileInstance:
    """An instance after the switch (an apply entry of two vanilla setting changes) and before its undo."""

    def __init__(self):
        self.instance = Path(tempfile.mkdtemp()) / "instance"
        self.config = self.instance / "config" / "rigtune"
        self.config.mkdir(parents=True)
        (self.instance / "mods").mkdir()
        (self.instance / "mods" / "fabric-api.jar").write_bytes(b"api")
        self.entries = [{"id": "old", "kind": "apply", "changes": [
            {"id": "m1", "type": "file", "action": "enable", "file": "e2e-first-1.0.0.jar", "status": "APPLIED"}]}]
        self.entries.append({"id": "sw", "kind": "apply", "changes": [
            {"id": "s1", "type": "setting", "key": "vanilla.renderDistance", "before": "12", "after": "6", "status": "APPLIED"},
            {"id": "s2", "type": "setting", "key": "vanilla.maxFps", "before": "120", "after": "90", "status": "APPLIED"}]})
        self.options(TARGETS)
        self.write()
        self.driver = {"ok": True, "applyMessage": "Applied 2 setting(s).", "settingsAfter": dict(TARGETS)}

    def options(self, values):
        lines = ["version:4786", "guiScale:0"] + ["{}:{}".format(k, v) for k, v in values.items()]
        (self.instance / "options.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")

    def write(self):
        (self.config / "history.json").write_text(json.dumps({"formatVersion": 1, "entries": self.entries}))

    def label(self, entry_id, name="Battery"):
        (self.config / "profiles.json").write_text(json.dumps({"formatVersion": 1, "switches": [
            {"entryId": entry_id, "profileId": "p1", "templateId": "battery", "name": name}]}))

    def undo(self):
        """What the undo leaves: the settings back, an undo entry reverting both changes."""
        self.options(ORIGINALS)
        for change in self.entries[1]["changes"]:
            change["status"] = "REVERTED"
        self.entries.append({"id": "u1", "kind": "undo", "undoOf": "sw", "changes": [
            {"id": "r1", "type": "setting", "key": "vanilla.renderDistance", "before": "6", "after": "12", "status": "APPLIED", "reverts": "s1"},
            {"id": "r2", "type": "setting", "key": "vanilla.maxFps", "before": "90", "after": "120", "status": "APPLIED", "reverts": "s2"}]})
        self.write()
        self.driver = {"ok": True, "undoOf": "sw", "viaScreen": True, "settingsAfter": dict(ORIGINALS), "entryPlan": [
            {"action": "REVERT", "needsRestart": False, "changeIds": ["s1"]},
            {"action": "REVERT", "needsRestart": False, "changeIds": ["s2"]}]}


class OptionsTest(unittest.TestCase):
    def test_reads_options_txt(self):
        fx = ProfileInstance()
        self.assertEqual({"version": "4786", "guiScale": "0", "renderDistance": "6", "maxFps": "90"}, e2e_checks.options_values(fx.instance))

    def test_string_values_are_unquoted_like_the_journal(self):
        fx = ProfileInstance()
        (fx.instance / "options.txt").write_text('graphicsPreset:"fast"\nlang:en_us\nresourcePacks:["vanilla"]\n', encoding="utf-8")
        self.assertEqual({"graphicsPreset": "fast", "lang": "en_us", "resourcePacks": '["vanilla"]'}, e2e_checks.options_values(fx.instance))

    def test_missing_file_is_empty(self):
        self.assertEqual({}, e2e_checks.options_values(Path(tempfile.mkdtemp())))


class ProfileApplyTest(unittest.TestCase):
    def setUp(self):
        self.fx = ProfileInstance()

    def check(self, targets=TARGETS, label=None):
        return e2e_checks.after_profile_apply(self.fx.instance, self.fx.driver, ["old"], ORIGINALS, targets, label)

    def test_passes(self):
        self.assertEqual([], failing(self.check()))

    def test_settings_not_in_options_txt(self):
        self.fx.options(ORIGINALS)
        self.assertEqual(["options.txt holds the switched values"], failing(self.check()))

    def test_a_file_change_in_the_switch_fails(self):
        self.fx.entries[1]["changes"].append({"id": "x", "type": "file", "action": "disable", "file": "a.jar", "status": "APPLIED"})
        self.fx.write()
        self.assertEqual(["history.json: one new apply entry of setting changes, all APPLIED"], failing(self.check()))

    def test_two_new_entries_fail(self):
        self.fx.entries.append({"id": "extra", "kind": "apply", "changes": []})
        self.fx.write()
        self.assertIn("history.json: one new apply entry of setting changes, all APPLIED", failing(self.check()))

    def test_the_stand_in_must_change_exactly_its_settings(self):
        self.assertEqual(["history.json: the switch changed exactly the chosen settings"],
                         failing(self.check(targets={"renderDistance": "6"})))

    def test_a_target_already_at_its_value_is_named(self):
        checks = e2e_checks.after_profile_apply(self.fx.instance, self.fx.driver, ["old"], dict(ORIGINALS, maxFps="90"), TARGETS, None)
        detail = next(c.detail for c in checks if c.name == "history.json: the switch changed exactly the chosen settings")
        self.assertIn("already at the target: {'maxFps': '90'}", detail)

    def test_before_must_be_the_value_before_the_launch(self):
        self.fx.entries[1]["changes"][0]["before"] = "10"
        self.fx.write()
        self.assertEqual(["history.json: the switch changed exactly the chosen settings"], failing(self.check()))

    def test_profile_mode_checks_the_label(self):
        self.assertEqual(["profiles.json labels the switch entry"], failing(self.check(targets=None, label="Battery")))
        self.fx.label("sw")
        self.assertEqual([], failing(self.check(targets=None, label="Battery")))
        self.fx.label("sw", name="Quality")
        self.assertEqual(["profiles.json labels the switch entry"], failing(self.check(targets=None, label="Battery")))

    def test_pending_json_fails(self):
        (self.fx.config / "pending.json").write_text("{}")
        self.assertEqual(["no pending.json, no leftover downloads"], failing(self.check()))


class ProfileUndoTest(unittest.TestCase):
    def setUp(self):
        self.fx = ProfileInstance()
        self.fx.undo()

    def check(self, label=None):
        return e2e_checks.after_profile_undo(self.fx.instance, self.fx.driver, "sw", label)

    def test_passes(self):
        self.assertEqual([], failing(self.check()))

    def test_values_not_back(self):
        self.fx.options(TARGETS)
        self.assertEqual(["options.txt holds the values from before the switch"], failing(self.check()))

    def test_plan_needing_a_restart_or_undoing_another_entry(self):
        self.fx.driver["entryPlan"][0]["needsRestart"] = True
        self.assertEqual(["the driver undid the switch entry now (no restart needed)"], failing(self.check()))
        self.fx.undo()
        self.fx.driver["undoOf"] = "old"
        self.assertEqual(["the driver undid the switch entry now (no restart needed)"], failing(self.check()))

    def test_plan_missing_a_change(self):
        self.fx.driver["entryPlan"].pop()
        self.assertEqual(["the driver undid the switch entry now (no restart needed)"], failing(self.check()))

    def test_change_not_reverted(self):
        self.fx.entries[1]["changes"][1]["status"] = "APPLIED"
        self.fx.write()
        self.assertEqual(["history.json: one undo of the switch, its changes REVERTED"], failing(self.check()))

    def test_profile_mode_keeps_the_label(self):
        self.assertEqual(["profiles.json still labels the switch entry"], failing(self.check(label="Battery")))
        self.fx.label("sw")
        self.assertEqual([], failing(self.check(label="Battery")))


class ProfileCheckTest(unittest.TestCase):
    def setUp(self):
        self.fx = ProfileInstance()
        self.fx.undo()
        self.mods = e2e_checks.listing(self.fx.instance / "mods")
        self.statuses = e2e_checks.history_statuses(self.fx.instance)
        self.driver = {"ok": True, "settingsNow": dict(ORIGINALS), "entryUndoableAfter": 0, "entryPlanAfterMeta": {"problem": None}}

    def check(self):
        return e2e_checks.after_profile_check(self.fx.instance, self.driver, "sw", self.mods, self.statuses)

    def test_passes(self):
        self.assertEqual([], failing(self.check()))

    def test_game_loaded_other_values(self):
        self.driver["settingsNow"]["maxFps"] = "90"
        self.assertEqual(["the game runs with the values from before the switch"], failing(self.check()))

    def test_something_left_to_undo(self):
        self.driver["entryUndoableAfter"] = 1
        self.assertEqual(["nothing left to undo on the switch entry"], failing(self.check()))

    def test_statuses_changed(self):
        self.fx.entries[1]["changes"][0]["status"] = "APPLIED"
        self.fx.write()
        self.assertEqual(["history.json statuses unchanged"], failing(self.check()))


class ProfileScenarioTest(unittest.TestCase):
    def test_off_by_default(self):
        run = make_run(Path(tempfile.mkdtemp()), "--scenario", "undo")
        self.assertNotIn("profile-apply", run.checks)

    def test_the_hook_adds_three_phases_after_the_per_entry_ones(self):
        run = make_run(Path(tempfile.mkdtemp()), "--scenario", "undo", "--profile-switch", "settings")
        self.assertEqual(list(self_update_e2e.UNDO_PHASES + self_update_e2e.ENTRY_PHASES + self_update_e2e.PROFILE_PHASES), list(run.checks))
        self.assertEqual(("profile-apply", "profile-undo", "profile-check"), self_update_e2e.PROFILE_PHASES)

    def test_jvm_args_pick_the_mode(self):
        run = make_run(Path(tempfile.mkdtemp()), "--scenario", "undo", "--profile-switch", "profile", "--profile-name", "Quality")
        self.assertEqual(["-Drigtune.e2e.profileMode=profile", "-Drigtune.e2e.profileName=Quality"], run.profile_jvm_args())
        run = make_run(Path(tempfile.mkdtemp()), "--scenario", "undo", "--profile-switch", "settings")
        self.assertEqual(["-Drigtune.e2e.profileMode=settings", "-Drigtune.e2e.profileSettings=" + self_update_e2e.PROFILE_SETTINGS_ARG],
                         run.profile_jvm_args())

    def test_profile_name_defaults_to_battery_and_needs_profile_mode(self):
        base = ["--name", "n", "--scenario", "undo", "--new-jar", "b.jar", "--work", "w", "--java-home", "jdk"]
        self.assertEqual("Battery", self_update_e2e.parse_args(base + ["--profile-switch", "profile"]).profile_name)
        with self.assertRaises(SystemExit):
            self_update_e2e.parse_args(base + ["--profile-switch", "settings", "--profile-name", "Quality"])
        with self.assertRaises(SystemExit):
            self_update_e2e.parse_args(base + ["--expect-history", "auto"])

    def test_only_for_the_undo_scenario(self):
        with self.assertRaises(SystemExit):
            self_update_e2e.parse_args(["--name", "n", "--old-jar", "a.jar", "--new-jar", "b.jar", "--work", "w",
                                        "--java-home", "jdk", "--profile-switch", "settings"])

    def test_stand_in_settings(self):
        self.assertEqual("renderDistance:6,maxFps:90", self_update_e2e.PROFILE_SETTINGS_ARG)
        self.assertEqual({"renderDistance": "6", "maxFps": "90"}, self_update_e2e.PROFILE_SETTINGS)


if __name__ == "__main__":
    unittest.main()
