"""Fixture generations (docs/v0.5/SPEC.md 3b, X11): "written by 0.4" and "written by 0.5" sets composed into one 0.5
instance, the files an older version never reads, and the harness's default fixture roots."""

import json
import sys
import shutil
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import self_update_e2e  # noqa: E402
import written  # noqa: E402

REPO = Path(__file__).resolve().parents[3]
V040 = REPO / "src" / "test" / "resources" / "v040-written"


def write(folder, name, data):
    folder.mkdir(parents=True, exist_ok=True)
    (folder / name).write_text(json.dumps(data), encoding="utf-8")


def entry(entry_id, at):
    return {"id": entry_id, "at": at, "kind": "apply", "changes": []}


class GenerationTest(unittest.TestCase):
    def test_the_generation_follows_the_root_folder_name(self):
        self.assertIs(written.V040, written.generation_of(V040))
        self.assertIs(written.V050, written.generation_of(Path(tempfile.mkdtemp()) / "v050-written"))
        self.assertIs(written.V040, written.generation_of(Path(tempfile.mkdtemp())), "an unnamed root reads as v0.4's")

    def test_the_0_5_sets_and_new_files(self):
        # The v050-written README's table order (docs/v0.5/PLAN.md contracts item 17; plan review PLAN-20).
        self.assertEqual(("ws-l1", "ws-l2", "ws-s", "ws-s2", "ws-p", "ws-p2", "ws-b", "ws-t", "ws-w", "ws-w2", "ws-f", "ws-h"),
                         written.V050.sets)
        self.assertEqual(("stutter-fixes.json", "tryit.json", "server-profiles.json"), written.V050.new_files)
        self.assertEqual((written.SETS, written.NEW_FILES, written.KEPT), (written.V040.sets, written.V040.new_files, written.V040.kept))

    def test_what_an_older_version_never_reads(self):
        self.assertEqual(written.V050.new_files, written.new_files_for("0.4.0+mc26.2"))
        self.assertEqual(written.V040.new_files + written.V050.new_files, written.new_files_for("0.3.0+mc26.3"))
        self.assertEqual((), written.new_files_for("0.5.0+mc26.2"))

    def test_kept_is_every_present_generation_s(self):
        root = Path(tempfile.mkdtemp())
        write(root / "v050-written" / "ws-t", "benchmarks.json", {"schemaVersion": 1, "runs": []})
        sets = written.resolve_all([V040, root / "v050-written"])
        self.assertEqual(written.KEPT, {k: v for k, v in written.kept_for(sets).items() if k in written.KEPT})
        self.assertEqual(("servers",), written.kept_for(sets)["server-profiles.json"])
        self.assertNotIn("server-profiles.json", written.kept_for(written.resolve_all([V040])))


class ResolveAllTest(unittest.TestCase):
    def test_older_generation_first_each_in_its_set_order(self):
        root = Path(tempfile.mkdtemp()) / "v050-written"
        write(root / "ws-f", "awareness.json", {})
        write(root / "placeholder" / "ws-l1", "settings.json", {})
        sets = written.resolve_all([V040, root])
        self.assertEqual(list(written.SETS) + ["ws-l1", "ws-f"], [s.name for s in sets])
        self.assertEqual(["v040-written"] * len(written.SETS) + ["v050-written"] * 2, [s.generation for s in sets])
        self.assertEqual([True, False], [s.placeholder for s in sets[-2:]])

    def test_a_missing_root_is_skipped(self):
        self.assertEqual(written.resolve(V040), written.resolve_all([V040, Path(tempfile.mkdtemp()) / "v050-written"]))


class MergeTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.v4, self.v5 = self.root / "v040-written", self.root / "v050-written"
        self.instance = self.root / "instance"

    def compose(self, conflicts=None):
        written.compose(written.resolve_all([self.v4, self.v5]), self.instance, conflicts=conflicts)
        return json.loads((self.instance / "config" / "rigtune" / "awareness.json").read_text(encoding="utf-8"))

    def test_objects_merge_key_by_key_and_lists_without_exact_duplicates(self):
        write(self.v4 / "ws-w", "awareness.json", {"formatVersion": 1, "dismissed": ["a", "b"], "fingerprint": {"gpu": "x"}})
        write(self.v5 / "ws-f", "awareness.json", {"formatVersion": 1, "dismissed": ["b", "firstrun.guide"], "fingerprint": {"cpu": "y"}})
        write(self.v5 / "ws-w2", "awareness.json", {"formatVersion": 1, "acknowledgedStartupRegressions": ["r1"]})
        merged = self.compose()
        self.assertEqual({"formatVersion": 1, "dismissed": ["a", "b", "firstrun.guide"], "fingerprint": {"gpu": "x", "cpu": "y"},
                          "acknowledgedStartupRegressions": ["r1"]}, merged)

    def test_a_scalar_both_sets_hold_takes_the_later_set_s_value_and_is_reported(self):
        write(self.v4 / "ws-w", "awareness.json", {"lastSeenVersion": "0.4.0"})
        write(self.v5 / "ws-w2", "awareness.json", {"lastSeenVersion": "0.5.0"})
        write(self.v5 / "ws-f", "awareness.json", {"lastSeenVersion": "0.5.0-dev"})
        conflicts = []
        # v0.5's set order is ws-l1 … ws-w2, ws-f: ws-f comes last.
        self.assertEqual({"lastSeenVersion": "0.5.0-dev"}, self.compose(conflicts))
        self.assertEqual([("awareness.json", "lastSeenVersion", "ws-w", "ws-w2"), ("awareness.json", "lastSeenVersion", "ws-w2", "ws-f")],
                         conflicts)

    def test_history_across_generations_is_sorted_by_time_and_ids_stay_unique(self):
        write(self.v4 / "ws-a", "history.json", {"formatVersion": 1, "entries": [entry("old", "2026-09-01T00:00:00Z")]})
        write(self.v5 / "ws-h", "history.json", {"formatVersion": 1, "entries": [entry("new", "2026-09-20T00:00:00Z"),
                                                                                  entry("mid", "2026-09-10T00:00:00Z")]})
        written.compose(written.resolve_all([self.v4, self.v5]), self.instance)
        history = json.loads((self.instance / "config" / "rigtune" / "history.json").read_text(encoding="utf-8"))
        self.assertEqual(["old", "mid", "new"], [e["id"] for e in history["entries"]])
        write(self.v5 / "ws-t", "history.json", {"formatVersion": 1, "entries": [entry("old", "2026-09-21T00:00:00Z")]})
        with self.assertRaises(ValueError):
            written.compose(written.resolve_all([self.v4, self.v5]), self.instance)

    def test_history_can_keep_only_the_newest_entries(self):
        write(self.v4 / "ws-a", "history.json", {"formatVersion": 1, "entries": [entry("old", "2026-09-20T00:00:00Z")]})
        write(self.v5 / "ws-s2", "history.json", {"formatVersion": 1, "entries": [entry("new", "2026-09-26T00:00:00Z"),
                                                                                   entry("mid", "2026-09-22T00:00:00Z")]})
        dropped = []
        written.compose(written.resolve_all([self.v4, self.v5]), self.instance, newest=2, dropped=dropped)
        history = json.loads((self.instance / "config" / "rigtune" / "history.json").read_text(encoding="utf-8"))
        self.assertEqual((["mid", "new"], ["old"]), ([e["id"] for e in history["entries"]], dropped))
        # An entry a staged op belongs to stays however old.
        staged = dict(entry("old", "2026-09-20T00:00:00Z"), changes=[{"id": "c1", "opId": "op-1", "status": "STAGED"}])
        write(self.v4 / "ws-a", "history.json", {"formatVersion": 1, "entries": [staged]})
        write(self.v4 / "ws-a", "pending.json", {"ops": [{"id": "op-1", "type": "PATCH_JSON"}]})
        dropped = []
        written.compose(written.resolve_all([self.v4, self.v5]), self.instance, newest=2, dropped=dropped)
        history = json.loads((self.instance / "config" / "rigtune" / "history.json").read_text(encoding="utf-8"))
        self.assertEqual((["old", "new"], ["mid"]), ([e["id"] for e in history["entries"]], dropped))

    def test_benchmark_runs_are_concatenated_and_their_ids_stay_unique(self):
        write(self.v4 / "ws-b", "benchmarks.json", {"schemaVersion": 1, "runs": [{"id": "r1", "x": 1}]})
        write(self.v5 / "ws-b", "benchmarks.json", {"schemaVersion": 1, "runs": [{"id": "r2", "x": 1}]})
        write(self.v5 / "ws-t", "benchmarks.json", {"schemaVersion": 1, "runs": [{"id": "r3", "x": 1}]})
        written.compose(written.resolve_all([self.v4, self.v5]), self.instance)
        runs = json.loads((self.instance / "config" / "rigtune" / "benchmarks.json").read_text(encoding="utf-8"))["runs"]
        self.assertEqual(["r1", "r2", "r3"], [r["id"] for r in runs])
        write(self.v5 / "ws-t", "benchmarks.json", {"schemaVersion": 1, "runs": [{"id": "r1", "x": 2}]})
        with self.assertRaises(ValueError):
            written.compose(written.resolve_all([self.v4, self.v5]), self.instance)

    def test_profiles_keep_one_baseline_the_latest_set_s_and_merge_by_id(self):
        base = lambda pid, at: {"id": pid, "name": "My settings", "source": "baseline", "createdAt": at}
        write(self.v4 / "ws-p", "profiles.json", {"formatVersion": 1, "profiles": [base("b4", "2026-09-21T00:00:00Z"),
                                                                                    {"id": "s1", "name": "Evening", "source": "saved"}],
                                                  "active": "template:battery", "battery": {"previousProfile": "b4"}})
        write(self.v5 / "ws-p", "profiles.json", {"formatVersion": 1, "profiles": [base("b5", "2026-09-22T00:00:00Z"),
                                                                                    {"id": "s1", "name": "Evening 2", "source": "saved"}],
                                                  "battery": {"previousProfile": "b5"}})
        written.compose(written.resolve_all([self.v4, self.v5]), self.instance)
        profiles = json.loads((self.instance / "config" / "rigtune" / "profiles.json").read_text(encoding="utf-8"))
        self.assertEqual([("b5", "baseline"), ("s1", "saved")], [(p["id"], p["source"]) for p in profiles["profiles"]])
        self.assertEqual("Evening 2", profiles["profiles"][1]["name"])
        self.assertEqual(("template:battery", "b5"), (profiles["active"], profiles["battery"]["previousProfile"]))

    def test_a_stand_in_id_comes_from_the_file_name(self):
        self.assertEqual("e2e-held", written.stand_in_id("e2e-held-1.0.0.jar"))
        self.assertEqual("e2e-held", written.stand_in_id("e2e-held-1.1.0.jar.rigtune-pending"))
        self.assertEqual("sodium-fabric", written.stand_in_id("sodium-fabric-0.9.2+mc26.2.jar.disabled"))
        self.assertEqual("fabric", written.stand_in_id("fabric-26.2.jar"))
        self.assertEqual("sodium-fabric-mc26-2", written.stand_in_id("sodium-fabric-mc26.2-0.9.2.jar"))
        self.assertEqual("e2e-1-0", written.stand_in_id("1.0.jar"))
        self.assertEqual("e2e-", written.stand_in_id(".jar"))
        self.assertEqual(64, len(written.stand_in_id("a" * 80 + ".jar")))

    def test_a_set_s_expect_json_is_never_composed(self):
        write(self.v5 / "ws-t", "expect.json", {"set": "ws-t", "checks": []})
        write(self.v5 / "ws-t", "tryit.json", {"formatVersion": 1})
        report = written.compose(written.resolve_all([self.v4, self.v5]), self.instance)
        self.assertNotIn("expect.json", report)
        self.assertFalse((self.instance / "config" / "rigtune" / "expect.json").exists())
        self.assertEqual({"ws-t": self.v5 / "ws-t" / "expect.json"}, written.expectations(written.resolve_all([self.v4, self.v5])))

    def test_a_file_one_set_provides_keeps_its_bytes(self):
        (self.v5 / "ws-t").mkdir(parents=True)
        raw = b'{"formatVersion":1,  "pairs": []}\n'
        (self.v5 / "ws-t" / "tryit.json").write_bytes(raw)
        written.compose(written.resolve_all([self.v4, self.v5]), self.instance)
        self.assertEqual(raw, (self.instance / "config" / "rigtune" / "tryit.json").read_bytes())

    def test_instance_paths_in_a_merged_file_are_filled_in(self):
        write(self.v4 / "ws-s", "settings.json", {"a": "${INSTANCE}/mods"})
        write(self.v5 / "ws-l1", "settings.json", {"modFilesByRigTune": True})
        written.compose(written.resolve_all([self.v4, self.v5]), self.instance)
        settings = json.loads((self.instance / "config" / "rigtune" / "settings.json").read_text(encoding="utf-8"))
        self.assertEqual({"a": str(self.instance / "mods").replace("\\", "/"), "modFilesByRigTune": True},
                         {k: v.replace("\\", "/") if isinstance(v, str) else v for k, v in settings.items()})


class HarnessRootsTest(unittest.TestCase):
    def test_the_default_roots_are_v040_plus_v050_when_it_exists(self):
        repo = Path(tempfile.mkdtemp())
        resources = repo / "src" / "test" / "resources"
        (resources / "v040-written").mkdir(parents=True)
        self.assertEqual([resources / "v040-written"], self_update_e2e.default_written(repo))
        (resources / "v050-written").mkdir()
        self.assertEqual([resources / "v040-written", resources / "v050-written"], self_update_e2e.default_written(repo))

    def test_written_may_repeat(self):
        args = self_update_e2e.parse_args(["--name", "n", "--scenario", "downgrade", "--old-jar", "a.jar", "--new-jar", "b.jar",
                                           "--work", "w", "--java-home", "jdk", "--written", "x", "--written", "y"])
        self.assertEqual(["x", "y"], args.written)
        args = self_update_e2e.parse_args(["--name", "n", "--new-jar", "b.jar", "--old-jar", "a.jar", "--work", "w", "--java-home", "jdk"])
        self.assertEqual([str(p) for p in self_update_e2e.default_written(self_update_e2e.REPO)], args.written)

    def test_seeded_state_uses_the_old_version_s_new_files_and_the_sets_kept(self):
        instance = Path(tempfile.mkdtemp()) / "instance"
        written.compose(written.resolve(V040), instance)
        seeded = self_update_e2e.seeded_state(instance, new_files=("profiles.json",), kept={"stutter.json": ("sessions",)})
        self.assertEqual(["profiles.json"], sorted(seeded["newFiles"]))
        self.assertEqual(["stutter.json"], sorted(seeded["json"]))
        self.assertEqual({"stutter.json": ["sessions"]}, {k: list(v) for k, v in seeded["kept"].items()})



class DowngradeTrimTest(unittest.TestCase):
    """The downgrade instance's journal over the real sets (self_update_e2e.DOWNGRADE_HISTORY): the trim keeps the fold
    entries and what profiles.json and pending.json name."""

    def test_the_real_sets_keep_their_baselines_switches_and_staged_entries(self):
        instance = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, instance)
        dropped = []
        written.compose(written.resolve_all([V040, REPO / "src" / "test" / "resources" / "v050-written"]), instance, newest=46, dropped=dropped)
        ids = [e["id"] for e in json.loads((instance / "config" / "rigtune" / "history.json").read_text(encoding="utf-8"))["entries"]]
        self.assertTrue(dropped, "the real sets are over the cap")
        for kept in ("baseline-7d226e52-56e8-454c-9d4b-9a63a2731c20", "c3e7a1f6-8d0b-4e2f-9b3c-7f5e6d349c17", "8ee686e5-f831-4836-80a2-33551c9bbe31"):
            self.assertIn(kept, ids)
        self.assertLessEqual(len(ids), 46 + 8)


if __name__ == "__main__":
    unittest.main()
