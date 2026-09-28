"""guard-apply (docs/v0.5/SPEC.md 3f, AC3f.7): in the undo scenario, a pinned update and an update a staged addition declares
incompatible are refused; the addition in between goes in."""

import json
import shutil
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import e2e_checks  # noqa: E402
import self_update_e2e as su  # noqa: E402
from test_self_update_e2e import make_run  # noqa: E402

INSTALLED = ["e2e-pin-target-1.0.0.jar", "e2e-pinner-1.0.0.jar", "e2e-rev-target-1.0.0.jar"]
ADDED = "e2e-rev-add-1.0.0.jar"
PIN_STATUS = ("Download failed: Update e2e-pin-target: RigTune E2E test mod e2e-pinner, which is installed, needs RigTune E2E test mod "
              "e2e-pin-target 1.0.x, not 2.0.0")
REVERSE_STATUS = "Download failed: Update e2e-rev-target: Modrinth marks e2e-rev-add, which is waiting for a restart, as incompatible with e2e-rev-target"


def failing(checks):
    return sorted(c.name for c in checks if not c.ok)


class GuardCatalogTest(unittest.TestCase):
    def setUp(self):
        tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, tmp)
        self.run = make_run(tmp, "--scenario", "undo")
        self.run.jars.mkdir()

    def test_the_projects_versions_and_the_pin(self):
        projects = {p[0]: p for p in self.run.guard_catalog()}
        pin = projects[su.PIN_TARGET_PROJECT][2]
        self.assertEqual(["1.0.0", "2.0.0"], [v["version_number"] for v in pin])
        rev = projects[su.REV_TARGET_PROJECT][2]
        self.assertEqual([("E2ERevT1", "1.0.0"), (su.REV_TARGET_NEXT, "1.1.0")], [(v["id"], v["version_number"]) for v in rev])
        add = projects[su.REV_ADD_PROJECT][2]
        self.assertEqual([su.REV_ADD_VERSION], [v["id"] for v in add])
        # Incompatible with the update only, so the addition itself is allowed next to the installed 1.0.0.
        self.assertEqual([{"project_id": su.REV_TARGET_PROJECT, "version_id": su.REV_TARGET_NEXT, "file_name": None,
                           "dependency_type": "incompatible"}], add[0]["dependencies"])
        with zipfile.ZipFile(self.run.guard_jars["e2e-pinner-1.0.0.jar"]) as jar:
            mod = json.loads(jar.read("fabric.mod.json"))
        self.assertEqual({"fabricloader": ">=0.19.5", su.PIN_TARGET: "1.0.x"}, mod["depends"])

    def test_the_driver_gets_the_three_targets(self):
        self.assertIn("guard-apply", self.run.checks)
        self.assertEqual(list(su.UNDO_PHASES + su.ENTRY_PHASES + su.GUARD_PHASES), list(self.run.checks))


class AfterGuardApplyTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root)
        (self.root / "mods").mkdir()
        (self.root / "config" / "rigtune").mkdir(parents=True)
        for name in INSTALLED + [ADDED]:
            (self.root / "mods" / name).write_bytes(b"jar")
        self.driver = {"ok": True, "pinStatus": PIN_STATUS, "addStatus": "1 change waiting for a restart", "reverseStatus": REVERSE_STATUS}
        self.log = [{"method": "GET", "path": "/v2/versions", "query": "ids=%5B%22" + su.REV_ADD_VERSION + "%22%5D"}]

    def check(self):
        return e2e_checks.after_guard_apply(self.root, self.driver, self.log, INSTALLED, ADDED, (su.PIN_TARGET, "1.0.x", "2.0.0"),
                                            su.REV_ADD_VERSION, (su.REV_TARGET, su.PIN_TARGET))

    def test_both_refused_and_the_addition_in(self):
        self.assertEqual([], failing(self.check()))

    def test_an_update_that_went_through_fails(self):
        (self.root / "mods" / "e2e-pin-target-2.0.0.jar").write_bytes(b"jar")
        (self.root / "config" / "rigtune" / "last-apply.json").write_text(json.dumps(
            {"results": [{"op": {"type": "ENABLE_FILE", "modId": su.PIN_TARGET}, "status": "OK"}]}), encoding="utf-8")
        self.driver["pinStatus"] = "1 change waiting for a restart"
        self.assertEqual(["only the addition went in; the refused updates changed nothing", "the pinned update is refused with the pin (WS-G1)"],
                         failing(self.check()))

    def test_no_reverse_refusal_or_no_version_read_back_fails(self):
        self.driver["reverseStatus"] = "1 change waiting for a restart"
        self.log = []
        self.assertEqual(["RigTune read the staged version back from Modrinth",
                          "the update the staged addition declares incompatible is refused (2d's reverse check)"], failing(self.check()))


if __name__ == "__main__":
    unittest.main()
