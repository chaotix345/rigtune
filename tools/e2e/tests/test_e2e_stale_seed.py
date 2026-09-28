"""The stale-seed scenario (docs/v0.5/SPEC.md AC2H.6, WS-H's RW-3): the new version's first start on a seeded state whose
staged group can never run (tools/e2e/seeds/v010-dh-app-reinstalled*)."""

import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import e2e_checks  # noqa: E402
import self_update_e2e as su  # noqa: E402

REPO = Path(__file__).resolve().parents[3]
SEEDS = REPO / "tools" / "e2e" / "seeds"


def failing(checks):
    return sorted(c.name for c in checks if not c.ok)


class SeedsTest(unittest.TestCase):
    def test_both_legs_carry_v010_dh_s_files_and_the_reinstalled_jar(self):
        for leg, extra in (("v010-dh-app-reinstalled", []), ("v010-dh-app-reinstalled-disabled", ["mods/fabric-26.2.jar.disabled"])):
            seed = su.load_seed(SEEDS / leg)
            for name in ("pending.json", "last-apply.json"):
                self.assertEqual((SEEDS / "v010-dh" / name).read_bytes(), (SEEDS / leg / name).read_bytes(), leg + " " + name)
            self.assertEqual(["mods/DistantHorizons-3.3.2-26.2-fabric-neoforge.jar"] + extra, [j["path"] for j in seed["jars"]])
            self.assertEqual([], seed["holdOpenAtOldExit"])

    def test_the_scenario_starts_the_new_jar_with_the_undo_driver(self):
        tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, tmp)
        run = su.Run(su.parse_args(["--name", "s", "--scenario", "stale-seed", "--seed", str(SEEDS / "v010-dh-app-reinstalled"),
                                    "--new-jar", "b.jar", "--work", str(tmp), "--java-home", "jdk", "--lock", "none"]))
        self.assertEqual(["stale-check"], list(run.checks))
        self.assertEqual([":26.2:e2eClient", "-Pe2e.driver=undo"], run.driver_args(":26.2:e2eClient"))
        with self.assertRaises(SystemExit):
            su.parse_args(["--name", "s", "--scenario", "stale-seed", "--new-jar", "b.jar", "--work", str(tmp), "--java-home", "jdk"])


class AfterStaleStartTest(unittest.TestCase):
    OPS = [{"id": "op-1", "type": "DISABLE_FILE", "path": "x/mods/fabric-26.2.jar"},
           {"id": "op-2", "type": "ENABLE_FILE", "from": "x/mods/dh.jar.rigtune-pending", "to": "x/mods/dh.jar"}]

    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root)
        self.config = self.root / "config" / "rigtune"
        self.config.mkdir(parents=True)
        (self.root / "mods").mkdir()
        (self.root / "mods" / "dh.jar").write_bytes(b"jar")
        self.before = e2e_checks.listing(self.root / "mods", recursive=True)
        self.history(["ABANDONED", "ABANDONED"])
        self.driver = {"ok": True, "statuses": [{"keys": ["rigtune.status.stale_installed"],
                                                 "text": "RigTune dropped its pending change to Distant Horizons: it is already installed (dh.jar)."}]}
        self.log = "[10:00:00] [main/INFO]: 2 staged RigTune change(s) can never run (the download is gone, or the mod is installed another way)\n"

    def history(self, statuses):
        changes = [{"id": "c" + str(i), "opId": op["id"], "status": s} for i, (op, s) in enumerate(zip(self.OPS, statuses))]
        (self.config / "history.json").write_text(json.dumps({"formatVersion": 1, "entries": [{"id": "e1", "changes": changes}]}), encoding="utf-8")

    def check(self, cmdlines=()):
        return e2e_checks.after_stale_start(self.root, self.OPS, ("distanthorizons", "Distant Horizons"), self.driver, self.log, list(cmdlines),
                                            self.before)

    def test_dropped_announced_marked_and_quiet_at_exit(self):
        self.assertEqual([], failing(self.check()))

    def test_a_kept_group_a_retry_line_and_a_helper_fail(self):
        (self.config / "pending.json").write_text(json.dumps({"ops": self.OPS}), encoding="utf-8")
        self.history(["STAGED", "STAGED"])
        self.driver["statuses"] = []
        self.log = "[10:00:00] [main/WARN]: 2 staged RigTune change(s) were not applied; they will be retried at the next exit\n"
        (self.root / "mods" / "dh.jar").rename(self.root / "mods" / "dh.jar.disabled")
        self.assertEqual(["History marks the dropped changes ABANDONED or DISCARDED", "latest.log: counted as never runnable, not as leftover to retry",
                          "no helper at exit, mods/ unchanged", "the drop is announced (status line)", "the stale group is dropped"],
                         failing(self.check(["java ApplyHelper"])))


if __name__ == "__main__":
    unittest.main()
