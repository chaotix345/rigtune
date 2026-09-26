"""The profile part of undo-after-restart (docs/v0.4/plans/ws-h.md T4, plan review P-H1): two profile switches in one
start (each an ordinary apply entry of setting changes; vanilla keys set at once, Sodium keys staged for the helper),
a restart, then Undo last twice, or Undo all on a copy of the instance from the same point, and a check start."""

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import e2e_checks  # noqa: E402
import self_update_e2e  # noqa: E402
from test_self_update_e2e import make_run  # noqa: E402

RD, FPS, THREADS, FOG = "vanilla.renderDistance", "vanilla.maxFps", "sodium.performance.chunk_builder_threads", "sodium.performance.use_fog_occlusion"
ORIGINALS = {RD: "12", FPS: "120", THREADS: "0", FOG: "true"}
TARGETS = [{RD: "6", FPS: "90", THREADS: "2", FOG: "false"}, {RD: "10", FPS: "60", THREADS: "4"}]


def failing(checks):
    return sorted(c.name for c in checks if not c.ok)


def change(cid, key, before, after, status="APPLIED", reverts=None):
    out = {"id": cid, "type": "setting", "key": key, "before": before, "after": after, "status": status}
    if reverts:
        out["reverts"] = reverts
    return out


class Instance:
    """An instance after the two switches and the helper (v0.3 semantics: both staged ops applied in order)."""

    def __init__(self, replacement=False):
        self.instance = Path(tempfile.mkdtemp()) / "instance"
        self.config = self.instance / "config"
        (self.config / "rigtune").mkdir(parents=True)
        (self.instance / "mods").mkdir()
        (self.instance / "mods" / "sodium.jar").write_bytes(b"s")
        a = [change("a1", RD, "12", "6"), change("a2", FPS, "120", "90"),
             change("a3", THREADS, "0", "2", "DISCARDED" if replacement else "APPLIED"), change("a4", FOG, "true", "false")]
        b = [change("b1", RD, "6", "10"), change("b2", FPS, "90", "60"), change("b3", THREADS, "0" if replacement else "2", "4")]
        self.entries = [{"id": "A", "kind": "apply", "changes": a}, {"id": "B", "kind": "apply", "changes": b}]
        self.values({RD: "10", FPS: "60", THREADS: "4", FOG: "false"})
        self.write()
        self.driver = {"ok": True, "applyMessages": ["Applied 2 setting(s). Restart ...", "Applied 2 setting(s). Restart ..."],
                       "settingsBefore": dict(ORIGINALS, **{"vanilla.guiScale": "0"})}

    def values(self, values):
        lines = ["version:4786"] + ["{}:{}".format(k[len("vanilla."):], v) for k, v in values.items() if k.startswith("vanilla.")]
        (self.instance / "options.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
        sodium = {"quality": {"weather_quality": "DEFAULT"}, "performance": {}}
        for k, v in values.items():
            if k.startswith("sodium.performance."):
                name = k[len("sodium.performance."):]
                sodium["performance"][name] = int(v) if v.isdigit() else v == "true" if v in ("true", "false") else v
        (self.config / "sodium-options.json").write_text(json.dumps(sodium), encoding="utf-8")

    def write(self):
        (self.config / "rigtune" / "history.json").write_text(json.dumps({"formatVersion": 1, "entries": self.entries}))

    def label(self, names):
        (self.config / "rigtune" / "profiles.json").write_text(json.dumps({"formatVersion": 1, "switches": [
            {"entryId": e, "profileId": None, "templateId": n.lower(), "name": n} for e, n in names.items()]}))

    def undo_last_twice(self):
        for c in self.entries[0]["changes"] + self.entries[1]["changes"]:
            if c["status"] == "APPLIED":
                c["status"] = "REVERTED"
        self.entries.append({"id": "U1", "kind": "undo", "undoOf": "B", "changes": [
            change("u1", RD, "10", "6", reverts="b1"), change("u2", FPS, "60", "90", reverts="b2"), change("u3", THREADS, "4", "2", reverts="b3")]})
        self.entries.append({"id": "U2", "kind": "undo", "undoOf": "A", "changes": [
            change("u4", RD, "6", "12", reverts="a1"), change("u5", FPS, "90", "120", reverts="a2"), change("u6", THREADS, "2", "0", reverts="a3"),
            change("u7", FOG, "false", "true", reverts="a4")]})
        self.write()
        self.values(ORIGINALS)
        self.driver = {"ok": True, "undoPlans": [{"undoOf": "B", "problem": None, "items": 3}, {"undoOf": "A", "problem": None, "items": 4}]}


class SettingValuesTest(unittest.TestCase):
    def test_vanilla_from_options_txt_and_sodium_flattened(self):
        fx = Instance()
        values = e2e_checks.setting_values(fx.instance)
        self.assertEqual("10", values[RD])
        self.assertEqual("4", values[THREADS])
        self.assertEqual("false", values[FOG])
        self.assertEqual("DEFAULT", values["sodium.quality.weather_quality"])

    def test_options_txt_strings_are_unquoted_like_the_journal(self):
        fx = Instance()
        (fx.instance / "options.txt").write_text('graphicsPreset:"fast"\nresourcePacks:["vanilla"]\n', encoding="utf-8")
        values = e2e_checks.setting_values(fx.instance)
        self.assertEqual("fast", values["vanilla.graphicsPreset"])
        self.assertEqual('["vanilla"]', values["vanilla.resourcePacks"])


class ProfileApplyTest(unittest.TestCase):
    def check(self, fx, targets=TARGETS, labels=None):
        return e2e_checks.after_profile_apply(fx.instance, fx.driver, [], targets, labels)

    def test_passes_with_v03_staging(self):
        self.assertEqual([], failing(self.check(Instance())))

    def test_passes_with_same_key_replacement(self):
        # P-H1's fix: switch B's staged op replaces A's (A's change DISCARDED), and B's change starts at the file's value.
        self.assertEqual([], failing(self.check(Instance(replacement=True))))

    def test_replacement_with_the_wrong_before_fails(self):
        fx = Instance(replacement=True)
        fx.entries[1]["changes"][2]["before"] = "2"
        fx.write()
        self.assertEqual(["history.json: each key's applied changes run from its value before the first switch to the last"],
                         failing(self.check(fx)))

    def test_the_file_must_hold_the_last_switch(self):
        fx = Instance()
        fx.values({RD: "10", FPS: "60", THREADS: "2", FOG: "false"})
        self.assertEqual(["the settings hold the last switch's values"], failing(self.check(fx)))

    def test_the_stand_in_must_change_exactly_its_settings(self):
        fx = Instance()
        del fx.entries[0]["changes"][3]
        fx.write()
        self.assertIn("history.json: each switch changed exactly its settings", failing(self.check(fx)))

    def test_a_change_still_staged_after_the_helper_fails(self):
        fx = Instance()
        fx.entries[1]["changes"][2]["status"] = "STAGED"
        fx.write()
        self.assertIn("history.json: two new apply entries of setting changes, applied (or discarded when replaced)", failing(self.check(fx)))

    def test_a_key_without_a_reader_fails(self):
        fx = Instance()
        fx.entries[0]["changes"].append(change("x", "dh.client.foo", "1", "2"))
        fx.write()
        self.assertIn("history.json: two new apply entries of setting changes, applied (or discarded when replaced)",
                      failing(self.check(fx, targets=None)))

    def test_profile_mode_checks_both_labels(self):
        fx = Instance()
        self.assertEqual(["profiles.json labels each switch entry"], failing(self.check(fx, targets=None, labels=["Battery", "Max FPS"])))
        fx.label({"A": "Battery", "B": "Max FPS"})
        self.assertEqual([], failing(self.check(fx, targets=None, labels=["Battery", "Max FPS"])))

    def test_pending_json_left_fails(self):
        fx = Instance()
        (fx.config / "rigtune" / "pending.json").write_text("{}")
        self.assertEqual(["no pending.json, no leftover downloads"], failing(self.check(fx)))


class ProfileUndoTest(unittest.TestCase):
    def setUp(self):
        self.fx = Instance()
        self.fx.undo_last_twice()

    def check(self, labels=None, undo_all=False):
        return e2e_checks.after_profile_undo(self.fx.instance, self.fx.driver, ["A", "B"], ORIGINALS, labels, undo_all)

    def test_undo_last_twice_passes(self):
        self.assertEqual([], failing(self.check()))

    def test_every_key_must_be_back(self):
        self.fx.values(dict(ORIGINALS, **{THREADS: "2"}))
        self.assertEqual(["every key is back at its value before the first switch"], failing(self.check()))

    def test_plans_in_the_wrong_order(self):
        self.fx.driver["undoPlans"].reverse()
        self.assertIn("the driver undid the newer switch, then the older (Undo last twice)", failing(self.check()))

    def test_a_change_left_applied(self):
        self.fx.entries[0]["changes"][3]["status"] = "APPLIED"
        self.fx.write()
        self.assertIn("history.json: the switches' changes REVERTED by undo entries, all applied", failing(self.check()))

    def test_undo_all(self):
        self.fx.entries = self.fx.entries[:2]
        self.fx.entries.append({"id": "U", "kind": "undo", "undoOf": "all", "changes": [
            change("u1", RD, "10", "12", reverts="b1"), change("u2", FPS, "60", "120", reverts="b2"),
            change("u3", THREADS, "4", "0", reverts="b3"), change("u4", FOG, "false", "true", reverts="a4")]})
        for c in self.fx.entries[0]["changes"] + self.fx.entries[1]["changes"]:
            c["status"] = "REVERTED"
        self.fx.write()
        self.fx.driver = {"ok": True, "undoPlans": [{"undoOf": "all", "problem": None, "items": 4}]}
        self.assertEqual([], failing(self.check(undo_all=True)))
        self.assertIn("the driver undid the newer switch, then the older (Undo last twice)", failing(self.check(undo_all=False)))

    def test_labels_kept(self):
        self.assertEqual(["profiles.json still labels each switch entry"], failing(self.check(labels=["Battery", "Max FPS"])))
        self.fx.label({"A": "Battery", "B": "Max FPS"})
        self.assertEqual([], failing(self.check(labels=["Battery", "Max FPS"])))


class ProfileCheckTest(unittest.TestCase):
    def setUp(self):
        self.fx = Instance()
        self.fx.undo_last_twice()
        self.mods = e2e_checks.listing(self.fx.instance / "mods")
        self.statuses = e2e_checks.history_statuses(self.fx.instance)
        self.driver = {"ok": True, "settingsNow": dict(ORIGINALS), "entryUndoable": {"A": 0, "B": 0}, "entryProblems": {"A": None, "B": None}}

    def check(self):
        return e2e_checks.after_profile_check(self.fx.instance, self.driver, ["A", "B"], ORIGINALS, self.mods, self.statuses)

    def test_passes(self):
        self.assertEqual([], failing(self.check()))

    def test_game_runs_with_other_values(self):
        self.driver["settingsNow"][THREADS] = "2"
        self.assertEqual(["the game runs with every key at its value before the first switch"], failing(self.check()))

    def test_something_left_to_undo(self):
        self.driver["entryUndoable"]["A"] = 1
        self.assertEqual(["nothing left to undo on either switch"], failing(self.check()))

    def test_statuses_changed(self):
        self.fx.entries[0]["changes"][0]["status"] = "APPLIED"
        self.fx.write()
        self.assertEqual(["history.json statuses unchanged"], failing(self.check()))


class ProfileScenarioTest(unittest.TestCase):
    def test_off_by_default(self):
        run = make_run(Path(tempfile.mkdtemp()), "--scenario", "undo")
        self.assertNotIn("profile-apply", run.checks)

    def test_the_hook_adds_its_phases_after_the_per_entry_ones(self):
        run = make_run(Path(tempfile.mkdtemp()), "--scenario", "undo", "--profile-switch", "settings")
        self.assertEqual(list(self_update_e2e.UNDO_PHASES + self_update_e2e.ENTRY_PHASES + self_update_e2e.PROFILE_PHASES), list(run.checks))
        self.assertEqual(("profile-apply", "profile-undo", "profile-check", "profile-undo-all", "profile-check-all"),
                         self_update_e2e.PROFILE_PHASES)

    def test_the_plan_file_per_mode(self):
        run = make_run(Path(tempfile.mkdtemp()), "--scenario", "undo", "--profile-switch", "settings")
        self.assertEqual({"mode": "settings", "switches": [{"name": "stand-in A", "settings": TARGETS[0]},
                                                           {"name": "stand-in B", "settings": TARGETS[1]}]}, run.profile_plan())
        run = make_run(Path(tempfile.mkdtemp()), "--scenario", "undo", "--profile-switch", "profile", "--profile-names", "Quality,Max FPS")
        self.assertEqual({"mode": "profile", "switches": [{"name": "Quality"}, {"name": "Max FPS"}]}, run.profile_plan())

    def test_profile_names_default_and_need_profile_mode(self):
        base = ["--name", "n", "--scenario", "undo", "--new-jar", "b.jar", "--work", "w", "--java-home", "jdk"]
        self.assertEqual(["Battery", "Max FPS"], self_update_e2e.parse_args(base + ["--profile-switch", "profile"]).profile_names)
        with self.assertRaises(SystemExit):
            self_update_e2e.parse_args(base + ["--profile-switch", "settings", "--profile-names", "Quality"])
        with self.assertRaises(SystemExit):
            self_update_e2e.parse_args(base + ["--profile-switch", "profile", "--profile-names", "Battery"])
        with self.assertRaises(SystemExit):
            self_update_e2e.parse_args(base + ["--expect-history", "auto"])

    def test_only_for_the_undo_scenario(self):
        with self.assertRaises(SystemExit):
            self_update_e2e.parse_args(["--name", "n", "--old-jar", "a.jar", "--new-jar", "b.jar", "--work", "w",
                                        "--java-home", "jdk", "--profile-switch", "settings"])

    def test_profile_phases_keep_the_settings_files(self):
        run = make_run(Path(tempfile.mkdtemp()), "--scenario", "undo", "--profile-switch", "settings")
        run.out.mkdir()
        (run.instance / "config" / "rigtune").mkdir(parents=True)
        (run.instance / "mods").mkdir()
        (run.instance / "options.txt").write_text("maxFps:90", encoding="utf-8")
        (run.instance / "config" / "sodium-options.json").write_text("{}", encoding="utf-8")
        run.snapshot("profile-apply")
        run.snapshot("mod-check")
        self.assertEqual("maxFps:90", (run.out / "options-after-profile-apply.txt").read_text(encoding="utf-8"))
        self.assertTrue((run.out / "sodium-options-after-profile-apply.json").is_file())
        self.assertFalse((run.out / "options-after-mod-check.txt").exists())


if __name__ == "__main__":
    unittest.main()
