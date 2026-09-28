"""tools/gametest/returning_seed.py: the returning player's config/rigtune (review-11 PERF-3)."""

import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "gametest"))

import returning_seed as rs  # noqa: E402


class ReturningSeedTests(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def test_the_seed_is_a_returning_players_folder_with_nothing_staged(self):
        out = self.tmp / "seed"
        names = rs.build(out, 50)
        self.assertNotIn("pending.json", names)
        for name in ("history.json", "last-apply.json", "awareness.json", "stutter.json", "stutter-fixes.json", "tryit.json",
                     "startup-times.json", "profiles.json", "benchmarks.json"):
            self.assertIn(name, names)
        history = json.loads((out / "history.json").read_text(encoding="utf-8"))
        ids = [e["id"] for e in history["entries"]]
        self.assertGreaterEqual(len(ids), 50)
        self.assertEqual(len(ids), len(set(ids)))
        for path in out.iterdir():
            text = path.read_text(encoding="utf-8")
            self.assertNotIn(str(self.tmp), text, path.name)
            self.assertNotIn(str(self.tmp).replace("\\", "/"), text, path.name)
        self.assertIn("${INSTANCE}", (out / "last-apply.json").read_text(encoding="utf-8"))
        self.assertFalse((self.tmp / "seed-instance").exists(), "the compose folder is removed")

    def test_a_short_history_is_padded_with_older_copies(self):
        history = {"formatVersion": 1, "entries": [{"id": "a", "at": "2026-09-20T10:00:00Z"}, {"id": "b", "at": "2026-09-21T10:00:00Z"}]}
        padded = rs.pad_history(history, 5)
        entries = padded["entries"]
        self.assertEqual(5, len(entries))
        self.assertEqual(5, len({e["id"] for e in entries}))
        self.assertEqual(["a", "b"], [e["id"] for e in entries[-2:]], "the real entries stay the newest")
        self.assertEqual(history, rs.pad_history(history, 2))


if __name__ == "__main__":
    unittest.main()
