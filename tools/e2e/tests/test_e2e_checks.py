import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import e2e_checks  # noqa: E402
from test_e2e_env import make_jar  # noqa: E402

DEPENDS = {"fabricloader": ">=0.19.5", "minecraft": "~26.2", "java": ">=25", "fabric-api": "*"}


class InstanceFixture:
    """A scratch instance as the helper leaves it after a successful self-update, which each test then breaks."""

    def __init__(self):
        self.root = Path(tempfile.mkdtemp())
        self.jars = self.root / "jars"
        self.jars.mkdir()
        self.old = make_jar(self.jars / "rigtune-0.1.0.jar", "rigtune", "0.1.0", DEPENDS)
        self.new = make_jar(self.jars / "rigtune-0.2.0-dev+mc26.2.jar", "rigtune", "0.2.0-dev+mc26.2", DEPENDS)
        self.instance = self.root / "instance"
        self.mods = self.instance / "mods"
        self.rigtune_config = self.instance / "config" / "rigtune"
        self.mods.mkdir(parents=True)
        self.rigtune_config.mkdir(parents=True)
        shutil.copy(self.old, self.mods / "rigtune-0.1.0.jar.disabled")
        shutil.copy(self.new, self.mods / self.new.name)
        (self.mods / "fabric-api-0.161.0+26.2.jar").write_bytes(b"api")
        self.write_last_apply("OK", "OK")
        (self.rigtune_config / "helper").mkdir()
        (self.rigtune_config / "helper.log").write_text("[x] [RigTune apply] All operations done\n")
        self.driver = {"ok": True, "updateOffered": True, "update": {"filename": self.new.name},
                       "rigtuneVersion": "0.1.0"}
        helper = self.rigtune_config / "helper"
        self.helper_cmdlines = ['"C:\\jdk\\bin\\java.exe" -cp {0};{1} io.github.chaotix345.rigtune.core.apply.ApplyHelper 4242 {2}'
                                .format(helper / "0-rigtune-0.1.0.jar", helper / "1-gson-2.13.2.jar",
                                        self.rigtune_config / "pending.json")]
        agent = "chaotix345/rigtune/0.1.0 (github.com/chaotix345/rigtune)"
        self.server_log = [
            {"method": "POST", "host": "api.modrinth.com", "path": "/v2/version_files/update", "status": 200, "userAgent": agent},
            {"method": "GET", "host": "cdn.modrinth.com", "path": "/data/E2ERigTn/versions/E2Enew01/" + self.new.name,
             "status": 200, "userAgent": agent},
        ]

    def write_last_apply(self, disable_status, enable_status):
        results = [
            {"op": {"type": "DISABLE_FILE", "path": str(self.mods / "rigtune-0.1.0.jar")}, "status": disable_status, "message": ""},
            {"op": {"type": "ENABLE_FILE", "from": str(self.mods / (self.new.name + ".rigtune-pending")),
                    "to": str(self.mods / self.new.name), "modId": "rigtune"}, "status": enable_status, "message": ""},
        ]
        (self.rigtune_config / "last-apply.json").write_text(json.dumps({"finishedAt": "2026-09-25T01:00:00Z", "results": results}))

    def after_update(self):
        return {c.name: c for c in e2e_checks.after_update(self.instance, self.old, self.new, self.driver, self.server_log,
                                                           self.helper_cmdlines)}


class AfterUpdateTest(unittest.TestCase):
    def setUp(self):
        self.fx = InstanceFixture()

    def failing(self):
        return sorted(name for name, check in self.fx.after_update().items() if not check.ok)

    def test_a_clean_self_update_passes_every_check(self):
        checks = self.fx.after_update()
        self.assertEqual([], [c.name + ": " + c.detail for c in checks.values() if not c.ok])
        self.assertGreaterEqual(len(checks), 9)

    def test_both_jars_active(self):
        shutil.copy(self.fx.old, self.fx.mods / "rigtune-0.1.0.jar")
        self.assertEqual(["exactly one RigTune jar, the new one"], self.failing())

    def test_new_jar_with_other_bytes(self):
        (self.fx.mods / self.fx.new.name).write_bytes(b"tampered")
        self.assertEqual(["exactly one RigTune jar, the new one"], self.failing())

    def test_old_jar_not_disabled(self):
        (self.fx.mods / "rigtune-0.1.0.jar.disabled").unlink()
        self.assertEqual(["the old jar is disabled"], self.failing())

    def test_pending_json_left(self):
        (self.fx.rigtune_config / "pending.json").write_text("{}")
        (self.fx.mods / (self.fx.new.name + ".rigtune-pending")).write_bytes(b"x")
        self.assertEqual(["no leftover downloads", "no pending.json"], self.failing())

    def test_an_op_that_was_not_ok(self):
        self.fx.write_last_apply("OK", "SKIPPED_ALREADY_DONE")
        self.assertEqual(["last-apply.json: the update's two ops, all OK"], self.failing())

    def test_helper_run_from_the_mods_folder(self):
        self.fx.helper_cmdlines = ["java -cp {0} io.github.chaotix345.rigtune.core.apply.ApplyHelper 1 x".format(
            self.fx.mods / "rigtune-0.1.0.jar")]
        self.assertEqual(["the helper ran from config/rigtune/helper copies"], self.failing())

    def test_helper_never_seen(self):
        self.fx.helper_cmdlines = []
        self.assertEqual(["the helper ran from config/rigtune/helper copies"], self.failing())

    def test_download_from_elsewhere(self):
        self.fx.server_log[1]["host"] = "api.modrinth.com"
        self.assertEqual(["the jar was downloaded from cdn.modrinth.com"], self.failing())

    def test_driver_failure(self):
        self.fx.driver = {"ok": False, "error": "no update:rigtune recommendation"}
        self.assertIn("the driver applied the offered update", self.failing())


class DependsTest(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.old = make_jar(self.dir / "old.jar", "rigtune", "0.1.0", DEPENDS)

    def test_same_depends_pass(self):
        new = make_jar(self.dir / "new.jar", "rigtune", "0.2.0", dict(DEPENDS))
        self.assertTrue(e2e_checks.depends_not_stricter(self.old, new).ok)

    def test_changed_or_added_depends_fail(self):
        changed = make_jar(self.dir / "changed.jar", "rigtune", "0.2.0", dict(DEPENDS, fabricloader=">=0.20.0"))
        added = make_jar(self.dir / "added.jar", "rigtune", "0.2.0", dict(DEPENDS, sodium="*"))
        for jar in (changed, added):
            check = e2e_checks.depends_not_stricter(self.old, jar)
            self.assertFalse(check.ok)
        self.assertIn("fabricloader", e2e_checks.depends_not_stricter(self.old, changed).detail)

    def test_dropped_depends_pass(self):
        fewer = {k: v for k, v in DEPENDS.items() if k != "fabric-api"}
        self.assertTrue(e2e_checks.depends_not_stricter(self.old, make_jar(self.dir / "fewer.jar", "rigtune", "0.2.0", fewer)).ok)


class AfterVerifyTest(unittest.TestCase):
    def setUp(self):
        self.fx = InstanceFixture()
        self.mods_before = e2e_checks.listing(self.fx.mods)
        (self.fx.rigtune_config / "rigtune.json").write_text(json.dumps({"goal": "QUALITY", "lastShownApply": "2026-09-25T01:00:00Z"}))
        self.driver = {"ok": True, "rigtuneVersion": "0.2.0-dev+mc26.2", "rigtuneOrigin": [str(self.fx.mods / self.fx.new.name)],
                       "goal": "QUALITY", "reportOnline": True, "updateOffered": False}

    def checks(self, expect_history=False):
        return {c.name: c for c in e2e_checks.after_verify(self.fx.instance, self.fx.new, self.driver, "2026-09-25T01:00:00Z",
                                                           self.mods_before, expect_history)}

    def failing(self, expect_history=False):
        return sorted(name for name, check in self.checks(expect_history).items() if not check.ok)

    def test_a_clean_relaunch_passes(self):
        self.assertEqual([], [c.name + ": " + c.detail for c in self.checks().values() if not c.ok])

    def test_old_version_still_loaded(self):
        self.driver["rigtuneVersion"] = "0.1.0"
        self.assertEqual(["the new RigTune is loaded from mods/"], self.failing())

    def test_loaded_from_outside_mods(self):
        self.driver["rigtuneOrigin"] = [str(self.fx.root / "build" / "libs" / self.fx.new.name)]
        self.assertEqual(["the new RigTune is loaded from mods/"], self.failing())

    def test_goal_lost(self):
        self.driver["goal"] = "BALANCED"
        self.assertEqual(["the goal is kept"], self.failing())

    def test_toast_not_shown(self):
        (self.fx.rigtune_config / "rigtune.json").write_text(json.dumps({"goal": "QUALITY", "lastShownApply": None}))
        self.assertEqual(["the apply result was shown (rigtune.json lastShownApply)"], self.failing())

    def test_update_offered_again_or_offline(self):
        self.driver["updateOffered"] = True
        self.assertEqual(["no further RigTune update is offered"], self.failing())
        self.driver["updateOffered"] = False
        self.driver["reportOnline"] = False
        self.assertEqual(["no further RigTune update is offered"], self.failing())

    def test_crash_report_and_changed_mods(self):
        (self.fx.instance / "crash-reports").mkdir()
        (self.fx.instance / "crash-reports" / "crash.txt").write_text("boom")
        (self.fx.mods / "extra.jar").write_bytes(b"x")
        self.assertEqual(["mods unchanged by the relaunch", "no crash report"], self.failing())

    def test_history_expected(self):
        self.assertEqual(["history.json: legacy import without RigTune's own jars"], self.failing(expect_history=True))
        history = {"formatVersion": 1, "entries": [{"kind": "legacy-import", "changes": [
            {"type": "file", "action": "disable", "modId": "indium", "file": "indium-1.0.jar"}]}]}
        (self.fx.rigtune_config / "history.json").write_text(json.dumps(history))
        self.assertEqual([], self.failing(expect_history=True))
        history["entries"][0]["changes"].append({"type": "file", "action": "disable", "modId": None, "file": "rigtune-0.1.0.jar"})
        (self.fx.rigtune_config / "history.json").write_text(json.dumps(history))
        self.assertEqual(["history.json: legacy import without RigTune's own jars"], self.failing(expect_history=True))


class HelperClasspathTest(unittest.TestCase):
    def test_parses_quoted_and_bare_classpaths(self):
        self.assertEqual(["C:\\a b\\x.jar", "C:\\y.jar"],
                         e2e_checks.classpath('java.exe -cp "C:\\a b\\x.jar;C:\\y.jar" Main', ";"))
        self.assertEqual(["/a/x.jar", "/b/y.jar"], e2e_checks.classpath("java -cp /a/x.jar:/b/y.jar Main", ":"))
        self.assertEqual([], e2e_checks.classpath("java Main", ";"))


if __name__ == "__main__":
    unittest.main()
