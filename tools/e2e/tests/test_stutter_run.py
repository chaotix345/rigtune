"""stutter_run.evaluate (docs/v0.5/SPEC.md 3f, AC3f.1) on v0.4's recorded C1r run (docs/v0.4/verification/stutter/C-rerun/,
verdict PASS there), and on that run with each criterion broken."""

import copy
import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import stutter_run  # noqa: E402

C1R = Path(__file__).resolve().parents[3] / "docs" / "v0.4" / "verification" / "stutter" / "C-rerun"


def failing(checks):
    return sorted(name for name, ok, _ in checks if not ok)


class EvaluateTest(unittest.TestCase):
    def setUp(self):
        self.lines = (C1R / "C1r-log-excerpt.txt").read_text(encoding="utf-8").splitlines()
        self.stutter = json.loads((C1R / "C1r-stutter.json").read_text(encoding="utf-8"))
        self.gc = (C1R / "C1r-gc.log").read_text(encoding="utf-8")

    def session(self):
        return [s for s in self.stutter["sessions"] if s["source"] == "monitor"][-1]

    def test_v0_4_s_recorded_run_passes(self):
        checks, facts = stutter_run.evaluate(self.lines, self.stutter, self.gc)
        self.assertEqual([], failing(checks))
        self.assertEqual(22, facts["tpSession"])
        self.assertEqual({"afterTeleport": 12, "chunksLoading": 9}, facts["tags"])

    def test_the_logged_stutter_json_is_read_back_from_the_log(self):
        self.assertEqual(self.stutter, stutter_run.stutter_json(self.lines))

    def test_an_unfinished_script_fails_first(self):
        lines = [line for line in self.lines if "Dev stutter: PASSED" not in line]
        self.assertEqual(["the dev script finished (monitor session saved)"], failing(stutter_run.evaluate(lines, self.stutter, self.gc)[0]))
        checks, facts = stutter_run.evaluate(self.lines, {"sessions": []}, self.gc)
        self.assertEqual((["the dev script finished (monitor session saved)"], {}), (failing(checks), facts))

    def test_a_gc_claim_with_no_pause_fails(self):
        self.assertIn("no GC milliseconds claimed without an overlapping pause", failing(stutter_run.evaluate(self.lines, self.stutter, "")[0]))
        stutter = copy.deepcopy(self.stutter)
        session = [s for s in stutter["sessions"] if s["source"] == "monitor"][-1]
        session["worst"].append({"t": 1.0, "ms": 50.0, "baseMs": 8.3, "causes": ["gc:high"]})
        self.assertEqual(["no GC milliseconds claimed without an overlapping pause"], failing(stutter_run.evaluate(self.lines, stutter, self.gc)[0]))

    def test_missing_tags_or_remainder_fail(self):
        stutter = copy.deepcopy(self.stutter)
        session = [s for s in stutter["sessions"] if s["source"] == "monitor"][-1]
        session["tags"] = {}
        session["causes"] = {"gc": 1.0}
        for w in session["worst"]:
            w["causes"] = [n for n in w["causes"] if not n.startswith("chunksLoading:")]
        self.assertEqual(["\"chunks loading\" from the first chunk load after the teleport on", "the spikes after the teleport carry \"after teleport\"",
                          "the unexplained remainder is shown"], failing(stutter_run.evaluate(self.lines, stutter, self.gc)[0]))

    def test_an_untagged_spike_after_the_first_chunk_load_fails(self):
        stutter = copy.deepcopy(self.stutter)
        session = [s for s in stutter["sessions"] if s["source"] == "monitor"][-1]
        session["worst"].append({"t": 30.0, "ms": 30.0, "baseMs": 8.3, "causes": ["render:low", "afterTeleport:context"]})
        self.assertEqual(["\"chunks loading\" from the first chunk load after the teleport on"],
                         failing(stutter_run.evaluate(self.lines, stutter, self.gc)[0]))

    # C1r: capture on at 02:12:36, world entry at +2 s, tp at +22 s; the product's window is 10 s.
    def test_the_teleport_window_s_edges(self):
        def failing_with(*extra):
            stutter = copy.deepcopy(self.stutter)
            [s for s in stutter["sessions"] if s["source"] == "monitor"][-1]["worst"].extend(extra)
            return failing(stutter_run.evaluate(self.lines, stutter, self.gc)[0])
        name = "the spikes after the teleport carry \"after teleport\""
        chunks = {"causes": ["render:low", "chunksLoading:context"], "ms": 30.0, "baseMs": 8.3}
        self.assertEqual([name], failing_with(dict(chunks, t=27.0)))
        self.assertEqual([], failing_with(dict(chunks, t=32.5)))
        self.assertEqual([name], failing_with({"t": 50.0, "ms": 30.0, "baseMs": 8.3, "causes": ["afterTeleport:context"]}))
        self.assertEqual([], failing_with({"t": 12.5, "ms": 30.0, "baseMs": 8.3, "causes": ["afterTeleport:context"]}))

    def test_gc_pauses_are_read_with_their_end_time(self):
        pauses = stutter_run.gc_pauses(self.gc)
        self.assertEqual((2 * 3600 + 12 * 60 + 21.020, 1.896, "Pause Young"), pauses[0])


if __name__ == "__main__":
    unittest.main()
