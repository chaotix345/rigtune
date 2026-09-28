"""The held-group hand-over (vg §1.5, the coordinator's decision (b)): the released old version stages a mod update that
fails held at its exit and at its self-update's exit, and the new version's first exit finishes it."""

import json
import shutil
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import e2e_checks  # noqa: E402
import e2e_matrix  # noqa: E402
import self_update_e2e as su  # noqa: E402

REPO = Path(__file__).resolve().parents[3]
INSTALLED, UPDATE = "DistantHorizons-3.3.0-26.3-fabric-neoforge.jar", "DistantHorizons-3.3.2-26.3-fabric-neoforge.jar"
OLD, NEW = "rigtune-0.4.0+mc26.3.jar", "rigtune-0.5.0+mc26.3.jar"
SERVED = b"the served 3.3.2"


def failing(checks):
    return sorted(c.name for c in checks if not c.ok)


class Instance:
    def __init__(self):
        self.root = Path(tempfile.mkdtemp())
        self.mods = self.root / "mods"
        self.config = self.root / "config" / "rigtune"
        self.mods.mkdir()
        self.config.mkdir(parents=True)
        self.served = self.root / UPDATE
        self.served.write_bytes(SERVED)
        self.ops = [{"id": "d", "group": "g", "type": "DISABLE_FILE", "path": str(self.mods / INSTALLED), "attempts": 1},
                    {"id": "e", "group": "g", "type": "ENABLE_FILE", "from": str(self.mods / (UPDATE + ".rigtune-pending")),
                     "to": str(self.mods / UPDATE), "modId": "distanthorizons", "attempts": 1}]

    def write(self, name, data):
        (self.config / name).write_text(json.dumps(data), encoding="utf-8")

    def touch(self, *names, data=b"jar"):
        for name in names:
            (self.mods / name).write_bytes(data)

    def results(self, status, message=None):
        message = message if message is not None else ("Gave up after 10 tries: " + INSTALLED if status == "FAILED" else "")
        self.write("last-apply.json", {"results": [{"op": op, "status": status, "message": message} for op in self.ops]})

    def journal(self, dh_status, own_status="APPLIED"):
        self.write("history.json", {"formatVersion": 1, "entries": [
            {"id": "a", "kind": "apply", "changes": [{"id": "c1", "opId": "d", "status": dh_status},
                                                      {"id": "c2", "opId": "e", "status": dh_status}]},
            {"id": "b", "kind": "apply", "changes": [{"id": "c3", "type": "file", "action": "disable", "file": OLD, "status": own_status},
                                                      {"id": "c4", "type": "file", "action": "enable", "file": NEW, "status": own_status}]}]})


class AfterStageTest(unittest.TestCase):
    def setUp(self):
        self.i = Instance()
        self.addCleanup(shutil.rmtree, self.i.root)
        self.jars = {"installed": self.i.root / INSTALLED, "update": self.i.served}
        self.driver = {"ok": True, "update": {"filename": UPDATE, "currentFile": INSTALLED}}

    def check(self, cmdlines=("java ApplyHelper",)):
        return e2e_checks.after_handover_stage(self.i.root, self.driver, self.jars, list(cmdlines))

    def staged(self):
        self.i.touch(INSTALLED)
        self.i.touch(UPDATE + ".rigtune-pending", data=SERVED)
        self.i.write("pending.json", {"ops": self.i.ops})
        self.i.results("FAILED")
        self.i.journal("STAGED")

    def test_the_group_failed_held_and_stays(self):
        self.staged()
        self.assertEqual([], failing(self.check()))

    def test_a_group_the_helper_applied_fails(self):
        self.i.touch(INSTALLED + ".disabled", UPDATE)
        self.i.results("OK")
        self.i.journal("APPLIED")
        self.assertEqual(["history.json journals the group's changes, STAGED",
                          "last-apply.json: the group's ops FAILED on the held installed jar",
                          "mods/: the installed build in place, the download (the served bytes) still pending",
                          "pending.json keeps the group, one failed run counted"], failing(self.check()))

    def test_another_cause_or_other_bytes_fail(self):
        self.staged()
        self.i.results("FAILED", message="Gave up after 10 tries: some other file")
        self.i.touch(UPDATE + ".rigtune-pending", data=b"not the served jar")
        self.assertEqual(["last-apply.json: the group's ops FAILED on the held installed jar",
                          "mods/: the installed build in place, the download (the served bytes) still pending"], failing(self.check()))

    def test_no_staging_no_helper_or_no_journal_fail(self):
        self.i.touch(INSTALLED)
        self.i.touch(UPDATE + ".rigtune-pending", data=SERVED)
        self.i.write("pending.json", {"ops": [dict(op, attempts=0) for op in self.i.ops]})
        self.i.results("FAILED")
        self.driver = {"ok": False, "error": "no update:distanthorizons recommendation within 180 s"}
        self.assertEqual(["a helper ran at exit", "history.json journals the group's changes, STAGED",
                          "pending.json keeps the group, one failed run counted", "the driver staged the mod's update"],
                         failing(self.check(cmdlines=())))


class AfterVerifyTest(unittest.TestCase):
    def setUp(self):
        self.i = Instance()
        self.addCleanup(shutil.rmtree, self.i.root)
        self.before = {"c1": "STAGED", "c2": "STAGED", "c3": "APPLIED", "c4": "APPLIED"}

    def check(self, cmdlines=("java ApplyHelper",)):
        return e2e_checks.after_handover_verify(self.i.root, self.i.ops, Path(OLD), Path(NEW), self.i.served, list(cmdlines), self.before)

    def finished(self):
        self.i.touch(INSTALLED + ".disabled")
        self.i.touch(UPDATE, data=SERVED)
        self.i.results("OK")
        self.i.journal("APPLIED")

    def test_the_new_version_s_exit_finished_the_group(self):
        self.finished()
        self.assertEqual([], failing(self.check()))

    def test_other_bytes_enabled_fail(self):
        self.finished()
        self.i.touch(UPDATE, data=b"something else")
        self.assertEqual(["the served build is enabled, the installed one disabled"], failing(self.check()))

    def test_a_group_still_failing_or_another_change_moved_fails(self):
        self.i.touch(INSTALLED, UPDATE + ".rigtune-pending")
        self.i.results("FAILED")
        self.i.journal("FAILED")
        self.assertEqual(["a helper ran at the new version's exit",
                          "history.json: the group's changes APPLIED, RigTune's own update APPLIED, nothing else changed",
                          "last-apply.json: the carried-over group's ops OK", "the served build is enabled, the installed one disabled"],
                         failing(self.check(cmdlines=())))
        (self.i.mods / INSTALLED).unlink()
        (self.i.mods / (UPDATE + ".rigtune-pending")).unlink()
        self.finished()
        self.before["c3"] = "STAGED"
        self.assertEqual(["history.json: the group's changes APPLIED, RigTune's own update APPLIED, nothing else changed"],
                         failing(self.check()))


class ScenarioTest(unittest.TestCase):
    def run_for(self, tmp, mc="26.3"):
        return su.Run(su.parse_args(["--name", "h", "--scenario", "handover", "--mc", mc, "--old-jar", "a.jar", "--new-jar", "b.jar",
                                     "--work", str(tmp), "--java-home", "jdk", "--lock", "none"]))

    def test_one_release_row_on_26_3_from_0_4_0(self):
        rows = [r for r in e2e_matrix.rows(REPO, "release") if r["id"].startswith("handover")]
        self.assertEqual([("handover-from-0.4.0-dh", "26.3", "0.4.0+mc26.3", "--scenario handover")],
                         [(r["id"], r["mc"], r["old"], r["args"]) for r in rows])
        self.assertEqual([], [r for r in e2e_matrix.rows(REPO, "push") if r["id"].startswith("handover")])

    def test_three_starts_the_old_driver_and_the_stage_s_update_id(self):
        tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, tmp)
        run = self.run_for(tmp)
        self.assertEqual(["stage", "update", "verify"], list(run.checks))
        self.assertEqual(":26.3:e2eDriverJar", ":26.3:" + run.driver_jar_task())
        with self.assertRaises(SystemExit):
            su.parse_args(["--name", "h", "--scenario", "handover", "--new-jar", "b.jar", "--work", str(tmp), "--java-home", "jdk"])
        self.assertTrue(set(su.HANDOVER_PHASES) <= set(su.PHASE_TITLES))
        self.assertEqual({"stage": ["-Drigtune.e2e.updateId=update:distanthorizons"], "update": [], "verify": []},
                         {phase: run.phase_jvm_lines(phase) for phase in su.HANDOVER_PHASES})

    def test_the_fake_mod_its_project_and_the_hold(self):
        # Review round 3, L8: prepare_handover's jars and catalog entry, held_paths.
        tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, tmp)
        run = self.run_for(tmp)
        run.jars.mkdir(parents=True)
        run.mods.mkdir(parents=True)
        projects = run.prepare_handover()
        self.assertEqual([(su.HANDOVER_MOD["project"], "distanthorizons")], [(p, slug) for p, slug, _ in projects])
        self.assertEqual(["3.3.0", "3.3.2"], [v["version_number"] for v in projects[0][2]])
        for key, version in (("installed", "3.3.0"), ("update", "3.3.2")):
            with zipfile.ZipFile(run.handover_jars[key]) as jar:
                meta = json.loads(jar.read("fabric.mod.json"))
            self.assertEqual(("distanthorizons", version), (meta["id"], meta["version"]))
        self.assertTrue((run.mods / INSTALLED).is_file())
        self.assertEqual([run.mods / INSTALLED], run.held_paths())


if __name__ == "__main__":
    unittest.main()
