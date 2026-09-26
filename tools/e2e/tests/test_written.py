"""The "written by 0.4" fixture sets (plan review H-M1): composing them into an instance, and what the instance must hold
to match their journal."""

import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import written  # noqa: E402

REPO = Path(__file__).resolve().parents[3]
ROOT = REPO / "src" / "test" / "resources" / "v040-written"


def entry(entry_id, at, changes=()):
    return {"id": entry_id, "at": at, "kind": "apply", "changes": list(changes)}


class ResolveTest(unittest.TestCase):
    def test_the_repository_has_a_set_for_every_owner(self):
        sets = written.resolve(ROOT)
        self.assertEqual(list(written.SETS), [s.name for s in sets])

    def test_a_real_set_replaces_its_placeholder(self):
        root = Path(tempfile.mkdtemp())
        shutil.copytree(ROOT / "placeholder", root / "placeholder")
        (root / "ws-b").mkdir()
        (root / "ws-b" / "benchmarks.json").write_text('{"schemaVersion": 1, "runs": []}', encoding="utf-8")
        sets = {s.name: s for s in written.resolve(root)}
        self.assertFalse(sets["ws-b"].placeholder)
        self.assertEqual(root / "ws-b", sets["ws-b"].folder)
        self.assertTrue(sets["ws-p"].placeholder)

    def test_a_missing_set_is_left_out(self):
        root = Path(tempfile.mkdtemp())
        (root / "placeholder" / "ws-f").mkdir(parents=True)
        self.assertEqual(["ws-f"], [s.name for s in written.resolve(root)])


class ComposeTest(unittest.TestCase):
    def setUp(self):
        self.instance = Path(tempfile.mkdtemp()) / "instance"

    def test_the_placeholders_compose_into_one_config(self):
        report = written.compose(written.resolve(ROOT), self.instance)
        config = self.instance / "config" / "rigtune"
        self.assertEqual(sorted(["awareness.json", "benchmarks.json", "history.json", "pending.json", "profiles.json",
                                 "server-limits.json", "settings.json", "startup-times.json", "stutter.json"]),
                         sorted(p.name for p in config.iterdir()))
        self.assertEqual(["ws-a", "ws-p"], report["history.json"])
        entries = json.loads((config / "history.json").read_text(encoding="utf-8"))["entries"]
        self.assertEqual(sorted(e["at"] for e in entries), [e["at"] for e in entries])
        self.assertEqual(3, len(entries))

    def test_instance_paths_are_filled_in(self):
        written.compose(written.resolve(ROOT), self.instance)
        pending = json.loads((self.instance / "config" / "rigtune" / "pending.json").read_text(encoding="utf-8"))
        self.assertNotIn("${INSTANCE}", json.dumps(pending))
        self.assertEqual(self.instance / "mods", Path(pending["ops"][0]["from"]).parent)

    def test_files_without_instance_paths_keep_their_bytes(self):
        written.compose(written.resolve(ROOT), self.instance)
        self.assertEqual((ROOT / "placeholder" / "ws-p" / "profiles.json").read_bytes(),
                         (self.instance / "config" / "rigtune" / "profiles.json").read_bytes())

    def test_only_history_may_come_from_two_sets(self):
        root = Path(tempfile.mkdtemp())
        for name in ("ws-a", "ws-b"):
            (root / name).mkdir()
            (root / name / "stutter.json").write_text("{}", encoding="utf-8")
        with self.assertRaises(ValueError):
            written.compose(written.resolve(root), self.instance)

    def test_entry_ids_must_be_unique_across_sets(self):
        root = Path(tempfile.mkdtemp())
        for name in ("ws-a", "ws-p"):
            (root / name).mkdir()
            (root / name / "history.json").write_text(json.dumps({"formatVersion": 1, "entries": [entry("same", "2026-01-01T00:00:00Z")]}),
                                                      encoding="utf-8")
        with self.assertRaises(ValueError):
            written.compose(written.resolve(root), self.instance)

    def test_a_sets_history_format_version_is_kept(self):
        root = Path(tempfile.mkdtemp())
        (root / "ws-p").mkdir()
        (root / "ws-p" / "history.json").write_text(json.dumps({"formatVersion": 2, "entries": []}), encoding="utf-8")
        written.compose(written.resolve(root), self.instance)
        self.assertEqual(2, json.loads((self.instance / "config" / "rigtune" / "history.json").read_text(encoding="utf-8"))["formatVersion"])
        (root / "ws-a").mkdir()
        (root / "ws-a" / "history.json").write_text(json.dumps({"formatVersion": 1, "entries": []}), encoding="utf-8")
        with self.assertRaises(ValueError):
            written.compose(written.resolve(root), self.instance)

    def test_new_files_are_the_0_4_only_ones(self):
        self.assertEqual(("profiles.json", "stutter.json", "server-limits.json", "awareness.json", "startup-times.json"),
                         written.NEW_FILES)


class InstanceStateTest(unittest.TestCase):
    def test_what_the_placeholders_need_in_the_instance(self):
        instance = Path(tempfile.mkdtemp()) / "instance"
        written.compose(written.resolve(ROOT), instance)
        state = written.instance_state(instance)
        self.assertEqual({"mods/e2e-seed-1.0.0.jar.rigtune-pending": "e2e-seed", "mods/e2e-named-1.0.0.jar.disabled": "e2e-named"},
                         state["jars"])
        self.assertEqual({"renderDistance": "8", "maxFps": "60"}, state["options"])

    def test_latest_applied_value_wins_and_reverted_changes_are_ignored(self):
        instance = Path(tempfile.mkdtemp()) / "instance"
        config = instance / "config" / "rigtune"
        config.mkdir(parents=True)
        (config / "history.json").write_text(json.dumps({"formatVersion": 1, "entries": [
            entry("a", "1", [{"type": "setting", "key": "vanilla.maxFps", "before": "120", "after": "60", "status": "APPLIED"},
                             {"type": "setting", "key": "sodium.x", "before": "1", "after": "2", "status": "APPLIED"},
                             {"type": "file", "action": "enable", "modId": "m", "file": "m.jar", "status": "REVERTED"}]),
            entry("b", "2", [{"type": "setting", "key": "vanilla.maxFps", "before": "60", "after": "90", "status": "APPLIED"},
                             {"type": "file", "action": "enable", "modId": "n", "file": "n.jar", "status": "APPLIED"},
                             {"type": "file", "action": "disable", "modId": "o", "file": "o.jar", "status": "APPLIED"}])]}),
            encoding="utf-8")
        state = written.instance_state(instance)
        self.assertEqual({"maxFps": "90"}, state["options"])
        self.assertEqual({"mods/n.jar": "n", "mods/o.jar.disabled": "o"}, state["jars"])


if __name__ == "__main__":
    unittest.main()
