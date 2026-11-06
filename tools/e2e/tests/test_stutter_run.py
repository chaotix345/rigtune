"""stutter_run.evaluate (docs/v0.5/SPEC.md 3f, AC3f.1) on v0.4's recorded C1r run (docs/v0.4/verification/stutter/C-rerun/,
verdict PASS there), and on that run with each criterion broken."""

import copy
import datetime
import gzip
import json
import re
import shutil
import sys
import tempfile
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

    # C1r's spikes with chunk loads after the tp run from 22.3 s to 28.7 s.
    def test_an_untagged_spike_while_chunks_load_fails(self):
        stutter = copy.deepcopy(self.stutter)
        session = [s for s in stutter["sessions"] if s["source"] == "monitor"][-1]
        session["worst"].append({"t": 27.5, "ms": 30.0, "baseMs": 8.3, "causes": ["render:low", "afterTeleport:context"]})
        self.assertEqual(["\"chunks loading\" from the first chunk load after the teleport on"],
                         failing(stutter_run.evaluate(self.lines, stutter, self.gc)[0]))

    def test_an_untagged_spike_after_the_loading_ended_passes(self):
        # Run 36431601035's 26.2 leg: loading ended before the teleport window's last spike.
        stutter = copy.deepcopy(self.stutter)
        session = [s for s in stutter["sessions"] if s["source"] == "monitor"][-1]
        session["worst"].append({"t": 30.0, "ms": 30.0, "baseMs": 8.3, "causes": ["render:low", "afterTeleport:context"]})
        self.assertEqual([], failing(stutter_run.evaluate(self.lines, stutter, self.gc)[0]))

    # Release run 36448687667's 26.2 leg: 31 spikes, 10 listed; after the tp only one early spike and later loading ones.
    def test_a_full_list_is_judged_with_the_session_s_counts(self):
        run = C1R.parents[3] / "v0.5" / "verification" / "stutter" / "ac3f1-stutter-script" / "run-36448687667" / "mc26.2"
        lines = (run / "log-excerpt.txt").read_text(encoding="utf-8").splitlines()
        stutter = json.loads((run / "stutter.json").read_text(encoding="utf-8"))
        gc = (run / "gc.log").read_text(encoding="utf-8")
        self.assertEqual([], failing(stutter_run.evaluate(lines, stutter, gc)[0]))
        # The same session with its later loading spikes dropped: a list below the cap is complete, so nothing shows the tags.
        session = [s for s in stutter["sessions"] if s["source"] == "monitor"][-1]
        session["worst"] = [w for w in session["worst"] if w["t"] < 40]
        self.assertEqual(["\"chunks loading\" from the first chunk load after the teleport on"],
                         failing(stutter_run.evaluate(lines, stutter, gc)[0]))

    # Issue #33, release run 36530247606 attempt 1 (26.2), rebuilt from its logged checks (the artifacts are gone): capture on,
    # world entry at +7 s, tp at +27 s, all 10 listed spikes forced GCs; "after teleport" tagged near the tp only at 36.8,
    # 2 tagged in all with 10.5 (the world entry's); "chunks loading" 11, 4 unlisted.
    ISSUE_33 = [(10.5, "TL"), (15.8, "L"), (21.2, "L"), (26.4, "L"), (36.8, "T"), (42.1, "L"), (47.3, "L"), (52.6, "L"), (57.9, ""), (63.1, "")]

    def issue_33_run(self, spikes=ISSUE_33, chunks_loading=11):
        cap = 6 * 3600 + 20 * 60 + 50

        def stamp(s):
            return "[{:02d}:{:02d}:{:02d}]".format(int(s) // 3600, int(s) // 60 % 60, int(s) % 60)
        lines = [stamp(cap) + " [Render thread/INFO]: Stutter Doctor: capture on (collector g1)",
                 stamp(cap + 7) + " [Render thread/INFO]: Dev stutter: in the benchmark world; standing still for 20 s",
                 stamp(cap + 27) + " [Render thread/INFO]: Dev stutter: tp @a 200000 200 200000",
                 stamp(cap + 80) + " [Render thread/INFO]: Dev stutter: PASSED; stopping the client"]
        worst = [{"t": t, "ms": 300.0, "baseMs": 80.0, "causes": ["gc:high:FULL:EXPLICIT"] + (["afterTeleport:context"] if "T" in tags else [])
                  + (["chunksLoading:context"] if "L" in tags else [])} for t, tags in spikes]
        session = {"source": "monitor", "worst": worst, "causes": {"gc": 0.91, "tick": 0.01, "unknown": 0.08},
                   "tags": {"afterTeleport": 1 + sum(1 for _, tags in spikes if "T" in tags), "chunksLoading": chunks_loading}}
        gc = "\n".join("[{}+0000][{:.3f}s][info][gc       ] GC({}) Pause Full (System.gc()) 900M->200M(4096M) 290.000ms".format(
            (datetime.datetime(2026, 9, 29) + datetime.timedelta(seconds=cap + 0.62 + t)).isoformat(timespec="milliseconds"), 30 + t, i)
            for i, (t, _) in enumerate(spikes))
        return lines, {"sessions": [session]}, gc

    def test_a_gc_hitch_between_the_world_entry_s_loading_and_the_teleport_s_passes(self):
        # 26.4 s is the world entry's loading (no "after teleport": before the tp), inside the log's tp - 1 s; the tp's
        # never-generated chunks arrive from 42.1 s on. The GC hitch at 36.8 s lies between the two.
        checks, facts = stutter_run.evaluate(*self.issue_33_run())
        self.assertEqual([], failing(checks))
        self.assertEqual(27, facts["tpSession"])
        detail = next(d for n, _, d in checks if n.startswith("\"chunks loading\""))
        self.assertIn("first at 42.1 s, last at 52.6 s; untagged between them: []", detail)

    def test_an_untagged_spike_inside_the_teleport_s_loading_still_fails(self):
        spikes = sorted(self.ISSUE_33 + [(44.7, "")])
        self.assertEqual(["\"chunks loading\" from the first chunk load after the teleport on"],
                         failing(stutter_run.evaluate(*self.issue_33_run(spikes))[0]))

    def test_the_world_entry_s_loading_alone_does_not_show_the_teleport_s(self):
        # No chunk load tagged after the tp: the entry's 26.4 s spike, inside tp - 1 s, no longer stands in for it.
        spikes = [(t, tags.replace("L", "") if t > 30 else tags) for t, tags in self.ISSUE_33]
        self.assertEqual(["\"chunks loading\" from the first chunk load after the teleport on"],
                         failing(stutter_run.evaluate(*self.issue_33_run(spikes, chunks_loading=4))[0]))

    def test_a_tagged_spike_in_the_tp_s_slop_starts_the_teleport_s_loading(self):
        # tp - 1 .. tp + 1: "after teleport" says the spike came after the product's tp, so its loading is the tp's.
        spikes = [(t, "TL" if t == 26.4 else tags) for t, tags in self.ISSUE_33]
        self.assertEqual(["\"chunks loading\" from the first chunk load after the teleport on"],
                         failing(stutter_run.evaluate(*self.issue_33_run(spikes))[0]))

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

    def test_a_run_across_midnight_passes_too(self):
        # review-11 CI-3: the capture starts 10 s before local midnight; the tp, the world entry and the GC pauses come after.
        shift = (23 * 3600 + 59 * 60 + 50) - (2 * 3600 + 12 * 60 + 36)

        def moved(line):
            m = re.match(r"\[(\d\d):(\d\d):(\d\d)\]", line)
            if not m:
                return line
            t = (int(m.group(1)) * 3600 + int(m.group(2)) * 60 + int(m.group(3)) + shift) % 86400
            return "[{:02d}:{:02d}:{:02d}]".format(t // 3600, t // 60 % 60, t % 60) + line[10:]

        def moved_gc(line):
            m = stutter_run.GC_LINE.match(line) or re.match(r"\[(\d{4}-\d\d-\d\dT[\d:.]+)", line)
            if not m:
                return line
            stamp = m.group(1)
            return line.replace(stamp, (datetime.datetime.fromisoformat(stamp) + datetime.timedelta(seconds=shift)).isoformat(timespec="milliseconds"), 1)
        lines = [moved(line) for line in self.lines]
        gc = "\n".join(moved_gc(line) for line in self.gc.splitlines())
        self.assertIn("[23:59:50] [Render thread/INFO]: Stutter Doctor: capture on", "\n".join(lines))
        checks, facts = stutter_run.evaluate(lines, self.stutter, gc)
        self.assertEqual([], failing(checks))
        self.assertEqual(22, facts["tpSession"])

    def test_the_client_log_includes_what_rolled_over_during_the_run(self):
        # review-11 CI-3: log4j rolls latest.log over at local midnight; the rotated part comes first.
        logs = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, logs)
        with gzip.open(logs / "2026-09-27-1.log.gz", "wt", encoding="utf-8") as f:
            f.write("[23:59:59] a\n")
        (logs / "latest.log").write_text("[00:00:01] b\n", encoding="utf-8")
        self.assertEqual(["[23:59:59] a", "[00:00:01] b"], stutter_run.client_log(logs))

    def test_sodium_only_when_the_node_has_a_build_of_it(self):
        # review-11 CI-4
        self.assertEqual([("net.fabricmc.fabric-api", "fabric-api", "0.161.0+26.2"), ("maven.modrinth", "sodium", "mc26.2-0.9.2-fabric")],
                         stutter_run.mod_coordinates("fabric_api_version=0.161.0+26.2" + chr(10) + "sodium_version=mc26.2-0.9.2-fabric" + chr(10)))
        self.assertEqual([("net.fabricmc.fabric-api", "fabric-api", "0.170.0+26.9")], stutter_run.mod_coordinates("fabric_api_version=0.170.0+26.9" + chr(10)))

    def test_gc_pauses_are_read_with_their_end_time(self):
        pauses = stutter_run.gc_pauses(self.gc)
        self.assertEqual((2 * 3600 + 12 * 60 + 21.020, 1.896, "Pause Young"), pauses[0])


if __name__ == "__main__":
    unittest.main()
