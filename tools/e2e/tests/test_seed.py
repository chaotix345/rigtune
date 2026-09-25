import hashlib
import json
import os
import stat
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import e2e_env  # noqa: E402
import fixtures  # noqa: E402
import make_seed  # noqa: E402
import self_update_e2e  # noqa: E402

REPO = Path(__file__).resolve().parents[3]
SEED = REPO / "tools" / "e2e" / "seeds" / "v010-dh"


class MakeSeedTest(unittest.TestCase):
    def setUp(self):
        base = Path(tempfile.mkdtemp())
        self.instance = base / "Fabric 26.2"
        self.config = self.instance / "config" / "rigtune"
        self.config.mkdir(parents=True)
        mods = str(self.instance / "mods")
        (self.config / "pending.json").write_text(json.dumps({"modsDir": mods, "gamePid": 12228, "ops": [
            {"type": "DISABLE_FILE", "path": os.path.join(mods, "fabric-26.2.jar"), "id": "d1", "attempts": 1}]}, indent=2))
        (self.config / "last-apply.json").write_text(json.dumps({"finishedAt": "t", "results": [
            {"op": {"type": "DISABLE_FILE", "path": os.path.join(mods, "fabric-26.2.jar")}, "status": "FAILED",
             "message": "busy: " + os.path.join(mods, "fabric-26.2.jar")}]}, indent=2))
        for f in self.config.iterdir():
            f.chmod(stat.S_IREAD)
        self.before = {f.name: (f.read_bytes(), f.stat().st_mtime_ns) for f in self.config.iterdir()}
        self.dest = base / "seed"

    def tearDown(self):
        for f in self.config.iterdir():
            f.chmod(stat.S_IREAD | stat.S_IWRITE)

    def test_templated_copies_and_the_source_hashes(self):
        source = make_seed.make_seed(self.config, self.instance, self.dest, "%APPDATA%/x/config/rigtune")

        pending = json.loads((self.dest / "pending.json").read_text(encoding="utf-8"))
        self.assertEqual("${INSTANCE}/mods", pending["modsDir"])
        self.assertEqual("${INSTANCE}/mods/fabric-26.2.jar", pending["ops"][0]["path"])
        self.assertEqual(12228, pending["gamePid"])
        last = json.loads((self.dest / "last-apply.json").read_text(encoding="utf-8"))
        self.assertEqual("busy: ${INSTANCE}/mods/fabric-26.2.jar", last["results"][0]["message"])
        for name in ("pending.json", "last-apply.json"):
            self.assertNotIn(str(self.instance), (self.dest / name).read_text(encoding="utf-8"))
            self.assertEqual(hashlib.sha256(self.before[name][0]).hexdigest(), source["files"][name]["sha256"])
        self.assertEqual("%APPDATA%/x/config/rigtune", json.loads((self.dest / "source.json").read_text())["location"])

    def test_the_source_folder_is_left_as_it_was(self):
        make_seed.make_seed(self.config, self.instance, self.dest, "x")
        self.assertEqual(self.before, {f.name: (f.read_bytes(), f.stat().st_mtime_ns) for f in self.config.iterdir()})


class CommittedSeedTest(unittest.TestCase):
    """tools/e2e/seeds/v010-dh: the user's real 0.1.0 files, templated (H-M2)."""

    def test_holds_the_failed_dh_group(self):
        pending = json.loads((SEED / "pending.json").read_text(encoding="utf-8"))
        ops = pending["ops"]
        self.assertEqual(["DISABLE_FILE", "ENABLE_FILE"], [op["type"] for op in ops])
        self.assertEqual({1}, {op["attempts"] for op in ops})
        self.assertEqual(1, len({op["group"] for op in ops}))
        self.assertEqual("distanthorizons", ops[1]["modId"])
        last = json.loads((SEED / "last-apply.json").read_text(encoding="utf-8"))
        failed = [r["op"]["id"] for r in last["results"] if r["status"] == "FAILED"]
        self.assertEqual(sorted(op["id"] for op in ops), sorted(failed))

    def test_no_user_path_is_left(self):
        for name in ("pending.json", "last-apply.json"):
            text = (SEED / name).read_text(encoding="utf-8")
            self.assertNotIn("Users", text)
            self.assertNotIn("ModrinthApp", text)
            self.assertIn(fixtures.TOKEN + "/mods/", text)

    def test_seed_json_describes_every_jar_the_group_names(self):
        seed = self_update_e2e.load_seed(SEED)
        paths = {jar["path"] for jar in seed["jars"]}
        ops = json.loads((SEED / "pending.json").read_text(encoding="utf-8"))["ops"]
        for op in ops:
            for key in ("path", "from"):
                if op.get(key):
                    self.assertIn(op[key].replace(fixtures.TOKEN + "/", ""), paths)
        self.assertTrue(any(p.startswith("mods/update/") for p in paths))
        self.assertEqual({"distanthorizons"}, {jar["id"] for jar in seed["jars"]})
        self.assertTrue(set(seed["holdOpenAtOldExit"]) <= paths)

    def test_instantiated_into_a_scratch_instance(self):
        instance = Path(tempfile.mkdtemp()) / "instance"
        pending = json.loads(fixtures.instantiate_json((SEED / "pending.json").read_text(encoding="utf-8"), instance))
        self.assertEqual(str(instance / "mods" / "fabric-26.2.jar"), pending["ops"][0]["path"])
        self.assertEqual(str(instance / "mods"), pending["modsDir"])


class SeedInstanceTest(unittest.TestCase):
    def test_the_harness_seeds_a_scratch_instance(self):
        tmp = Path(tempfile.mkdtemp())
        args = self_update_e2e.parse_args(["--name", "n", "--old-jar", "a.jar", "--new-jar", "b.jar", "--work", str(tmp),
                                           "--java-home", "jdk", "--seed", str(SEED)])
        run = self_update_e2e.Run(args)
        run.out.mkdir(parents=True)
        run.mods.mkdir(parents=True)

        run.seed_instance()

        installed = run.mods / "fabric-26.2.jar"
        queued = run.mods / "update" / "DistantHorizons-3.3.2 - 26.2 neo" / "fabric-26.2.jar"
        self.assertEqual(("distanthorizons", "3.3.0"), tuple(e2e_env.mod_json(installed)[k] for k in ("id", "version")))
        self.assertEqual("3.3.2", e2e_env.mod_json(queued)["version"])
        pending = json.loads((run.rigtune_dir / "pending.json").read_text(encoding="utf-8"))
        self.assertEqual(str(installed), pending["ops"][0]["path"])
        self.assertTrue((run.rigtune_dir / "last-apply.json").is_file())
        self.assertEqual(["041919d9-18c9-4b04-89c4-f15e4e4a80c5", "db7f487d-6371-43c5-944e-c053428ad70d"],
                         [op["id"] for op in run.carried])
        self.assertTrue((run.out / "seeded-pending.json").is_file())


if __name__ == "__main__":
    unittest.main()
