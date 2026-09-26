"""Harness changes for v0.4 (docs/v0.4/plans/ws-h.md): 0.3.0 as an old side, the old side's journal derived from its
version, and version-neutral names."""

import json
import re
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import e2e_checks  # noqa: E402
import e2e_env  # noqa: E402
import self_update_e2e  # noqa: E402
from test_e2e_env import make_jar  # noqa: E402

REPO = Path(__file__).resolve().parents[3]
DEPENDS = {"fabricloader": ">=0.19.5", "minecraft": "~26.2", "java": ">=25", "fabric-api": "*"}
LEGACY = "e2e-legacy-1.0.0.jar"


def failing(checks):
    return sorted(c.name for c in checks if not c.ok)


class ReleasedJarTest(unittest.TestCase):
    def test_every_released_old_side_is_pinned(self):
        self.assertEqual({"0.1.0", "0.2.0+mc26.2", "0.3.0+mc26.2"}, set(self_update_e2e.RELEASED))
        self.assertEqual(("rigtune-0.3.0+mc26.2.jar", "5717f65cb90c71aaeda844b7bd56e3ce9255e83f44418af0cfc6a589050cd7e9"),
                         self_update_e2e.RELEASED["0.3.0+mc26.2"])

    def test_ci_pins_the_same_jars(self):
        workflow = (REPO / ".github" / "workflows" / "build.yml").read_text(encoding="utf-8")
        pinned = dict((name, sha) for sha, name in re.findall(r"^\s*([0-9a-f]{64})  \$old/(\S+)$", workflow, re.MULTILINE))
        self.assertEqual({name: sha for name, sha in self_update_e2e.RELEASED.values()}, pinned)
        for name in pinned:
            self.assertIn('"-Pe2e.oldJar=$old/{}"'.format(name), workflow)

    def test_a_released_version_must_have_the_release_bytes(self):
        sha = self_update_e2e.RELEASED["0.3.0+mc26.2"][1]
        self.assertIsNone(self_update_e2e.released_problem("0.3.0+mc26.2", sha))
        problem = self_update_e2e.released_problem("0.3.0+mc26.2", "0" * 64)
        self.assertIn(sha, problem)

    def test_other_versions_are_not_checked(self):
        self.assertIsNone(self_update_e2e.released_problem("0.4.0-dev+mc26.2", "0" * 64))
        self.assertIsNone(self_update_e2e.released_problem("0.2.1-e2e-old", "0" * 64))


class HistoryExpectationTest(unittest.TestCase):
    def test_0_1_x_has_no_journal_so_the_new_version_imports_it(self):
        self.assertEqual("legacy-import", e2e_checks.history_expectation("0.1.0"))
        self.assertEqual("legacy-import", e2e_checks.history_expectation("0.1.3+mc26.2"))

    def test_0_2_and_later_journal_their_own_update(self):
        for version in ("0.2.0+mc26.2", "0.3.0+mc26.2", "0.4.0-dev+mc26.2", "0.10.0", "1.0.0"):
            self.assertEqual("own-update", e2e_checks.history_expectation(version), version)

    def test_resolve_auto(self):
        self.assertEqual("own-update", self_update_e2e.resolve_expect_history("auto", "0.3.0+mc26.2"))
        self.assertEqual("legacy-import", self_update_e2e.resolve_expect_history("auto", "0.1.0"))
        self.assertIsNone(self_update_e2e.resolve_expect_history(None, "0.3.0+mc26.2"))

    def test_an_explicit_value_must_match_the_old_version(self):
        self.assertEqual("legacy-import", self_update_e2e.resolve_expect_history("legacy-import", "0.1.0"))
        self.assertEqual("own-update", self_update_e2e.resolve_expect_history("own-update", "0.2.0+mc26.2"))
        with self.assertRaises(SystemExit):
            self_update_e2e.resolve_expect_history("legacy-import", "0.3.0+mc26.2")
        with self.assertRaises(SystemExit):
            self_update_e2e.resolve_expect_history("own-update", "0.1.0")

    def test_args_accept_auto_and_keep_the_v03_spellings(self):
        base = ["--name", "n", "--old-jar", "a.jar", "--new-jar", "b.jar", "--work", "w", "--java-home", "jdk"]
        self.assertEqual("auto", self_update_e2e.parse_args(base + ["--expect-history", "auto"]).expect_history)
        self.assertEqual("legacy-import", self_update_e2e.parse_args(base + ["--expect-history"]).expect_history)
        self.assertEqual("own-update", self_update_e2e.parse_args(base + ["--expect-history", "own-update"]).expect_history)


class VersionNeutralNamesTest(unittest.TestCase):
    def test_phase_titles_name_no_version(self):
        for phase, title in self_update_e2e.PHASE_TITLES.items():
            self.assertIsNone(re.search(r"\b0\.\d", title), phase + ": " + title)


class UpdateFrom030Fixture:
    """A scratch instance as 0.3.0's helper leaves it after updating itself to a 0.4.0-dev build, with 0.3.0 having also
    disabled a test mod in the same apply."""

    def __init__(self):
        root = Path(tempfile.mkdtemp())
        self.jars = root / "jars"
        self.jars.mkdir()
        self.old = make_jar(self.jars / "rigtune-0.3.0+mc26.2.jar", "rigtune", "0.3.0+mc26.2", DEPENDS)
        self.new = make_jar(self.jars / "rigtune-0.4.0-dev+mc26.2.jar", "rigtune", "0.4.0-dev+mc26.2", DEPENDS)
        self.instance = root / "instance"
        self.mods = self.instance / "mods"
        self.config = self.instance / "config" / "rigtune"
        self.mods.mkdir(parents=True)
        (self.config / "helper").mkdir(parents=True)
        shutil.copy(self.old, self.mods / (self.old.name + ".disabled"))
        shutil.copy(self.new, self.mods / self.new.name)
        e2e_env.test_mod_jar(self.jars / LEGACY, "e2e-legacy")
        shutil.copy(self.jars / LEGACY, self.mods / (LEGACY + ".disabled"))
        (self.mods / "fabric-api-0.161.0+26.2.jar").write_bytes(b"api")
        results = [
            {"op": {"type": "DISABLE_FILE", "path": str(self.mods / self.old.name)}, "status": "OK"},
            {"op": {"type": "ENABLE_FILE", "from": str(self.mods / (self.new.name + ".rigtune-pending")),
                    "to": str(self.mods / self.new.name), "modId": "rigtune"}, "status": "OK"},
            {"op": {"type": "DISABLE_FILE", "path": str(self.mods / LEGACY)}, "status": "OK"},
        ]
        (self.config / "last-apply.json").write_text(json.dumps({"finishedAt": "2026-09-26T01:00:00Z", "results": results}))
        self.history = {"formatVersion": 1, "entries": [{"id": "e1", "kind": "apply", "rigtuneVersion": "0.3.0+mc26.2", "changes": [
            {"id": "c1", "type": "file", "action": "disable", "modId": "rigtune", "file": self.old.name, "status": "APPLIED"},
            {"id": "c2", "type": "file", "action": "enable", "modId": "rigtune", "file": self.new.name, "status": "APPLIED"},
            {"id": "c3", "type": "file", "action": "disable", "modId": "e2e-legacy", "file": LEGACY, "status": "APPLIED"}]}]}
        self.write_history()
        self.driver = {"ok": True, "update": {"filename": self.new.name}}
        helper = self.config / "helper"
        self.cmdlines = ['java -cp {};{} io.github.chaotix345.rigtune.core.apply.ApplyHelper 1 x'.format(
            helper / "0-rigtune-0.3.0+mc26.2.jar", helper / "1-gson.jar")]
        agent = "chaotix345/rigtune/0.3.0+mc26.2 (github.com/chaotix345/rigtune)"
        self.server_log = [{"method": "GET", "host": e2e_env.CDN_HOST, "path": "/data/E2ERigTn/versions/E2Enew01/" + self.new.name,
                            "status": 200, "userAgent": agent}]

    def write_history(self):
        (self.config / "history.json").write_text(json.dumps(self.history))


class UpdateFrom030Test(unittest.TestCase):
    def setUp(self):
        self.fx = UpdateFrom030Fixture()

    def test_after_update_passes_with_no_0_1_names(self):
        checks = e2e_checks.after_update(self.fx.instance, self.fx.old, self.fx.new, self.fx.driver, self.fx.server_log,
                                         self.fx.cmdlines, extra_disables=[LEGACY])
        self.assertEqual([], failing(checks))
        self.assertIn("the other mod the old version changed is disabled", [c.name for c in checks])

    def test_the_download_must_carry_0_3_0s_user_agent(self):
        self.fx.server_log[0]["userAgent"] = "chaotix345/rigtune/0.1.0 (github.com/chaotix345/rigtune)"
        checks = e2e_checks.after_update(self.fx.instance, self.fx.old, self.fx.new, self.fx.driver, self.fx.server_log,
                                         self.fx.cmdlines, extra_disables=[LEGACY])
        self.assertEqual(["the old RigTune downloaded the jar from cdn.modrinth.com"], failing(checks))

    def own_update(self, legacy=(LEGACY,)):
        before = e2e_checks.history_statuses(self.fx.instance)
        return e2e_checks.own_update_history(self.fx.instance, self.fx.old, self.fx.new, before, legacy_disables=legacy)

    def test_own_update_holds_the_other_change_of_the_same_apply(self):
        check = self.own_update()
        self.assertTrue(check.ok, check.detail)

    def test_own_update_without_the_other_change_fails(self):
        del self.fx.history["entries"][0]["changes"][2]
        self.fx.write_history()
        self.assertFalse(self.own_update().ok)

    def test_an_unexpected_other_change_fails(self):
        self.assertFalse(self.own_update(legacy=()).ok)


if __name__ == "__main__":
    unittest.main()
