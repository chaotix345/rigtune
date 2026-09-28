"""tools/ci_streak.py's counting rules (docs/v0.5/SPEC.md AC1g.1) on fixture gh JSON."""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import ci_streak as cs  # noqa: E402

GREEN_JOBS = [{"name": name, "conclusion": "success", "startedAt": "2026-09-27T01:00:00Z", "completedAt": "2026-09-27T01:08:00Z"}
              for name in ("java", "python", "gametest-matrix", "rules-consistency", "rules-v1-compat",
                           "client game tests (26.2, OpenGL)", "client game tests (26.3, OpenGL)", "client game tests (26.3, Vulkan)")]


def run(run_id, minute, conclusion="success", attempt=1, event="workflow_dispatch", sha="abc123", status="completed"):
    return {"databaseId": run_id, "headSha": sha, "event": event, "attempt": attempt, "conclusion": conclusion, "status": status,
            "createdAt": "2026-09-27T01:%02d:00Z" % minute, "updatedAt": "2026-09-27T01:%02d:30Z" % minute, "url": "u%d" % run_id}


def jobs_with(**changes):
    jobs = [dict(j) for j in GREEN_JOBS]
    for job in jobs:
        if job["name"] in changes:
            job["conclusion"] = changes[job["name"]]
    return jobs


class StreakTests(unittest.TestCase):
    def count(self, runs, jobs=None, sha=None, extra=()):
        jobs = jobs or {}
        current, looked = cs.streak(runs, lambda run_id: jobs.get(run_id, GREEN_JOBS), sha, extra)
        return [r["databaseId"] for r, _ in current], {r["databaseId"]: kind for r, kind, _, _ in looked}

    def test_five_green_dispatches_count(self):
        ids, _ = self.count([run(i, i) for i in range(1, 6)])
        self.assertEqual([1, 2, 3, 4, 5], ids)

    def test_a_re_run_breaks_the_streak_even_when_it_ended_green(self):
        ids, kinds = self.count([run(1, 1), run(2, 2, attempt=2), run(3, 3)])
        self.assertEqual("break", kinds[2])
        self.assertEqual([3], ids)

    def test_a_cancelled_run_neither_counts_nor_breaks(self):
        ids, kinds = self.count([run(1, 1), run(2, 2, conclusion="cancelled"), run(3, 3)])
        self.assertEqual("ignore", kinds[2])
        self.assertEqual([1, 3], ids)

    def test_a_skipped_job_breaks_the_streak(self):
        ids, kinds = self.count([run(1, 1), run(2, 2), run(3, 3)], {2: jobs_with(**{"client game tests (26.3, Vulkan)": "skipped"})})
        self.assertEqual("break", kinds[2])
        self.assertEqual([3], ids)

    def test_a_failure_breaks_and_a_missing_leg_breaks(self):
        ids, kinds = self.count([run(1, 1, conclusion="failure"), run(2, 2), run(3, 3)],
                                {3: [j for j in GREEN_JOBS if j["name"] != "client game tests (26.3, Vulkan)"]})
        self.assertEqual(("break", "count", "break"), (kinds[1], kinds[2], kinds[3]))
        self.assertEqual([], ids)

    def test_other_events_and_other_commits_are_left_out(self):
        ids, kinds = self.count([run(1, 1), run(2, 2, event="pull_request", conclusion="failure"), run(3, 3, sha="def456"), run(4, 4)],
                                sha="abc")
        self.assertEqual("ignore", kinds[2])
        self.assertNotIn(3, kinds)
        self.assertEqual([1, 4], ids)

    # SPEC 1g: with the split on, a leg's parts stand for the leg; a leg with no job at all breaks the streak.
    def test_a_legs_parts_count_as_the_leg(self):
        split = [j for j in GREEN_JOBS if not j["name"].startswith("client game tests (")]
        for leg in ("26.2, OpenGL", "26.3, OpenGL", "26.3, Vulkan"):
            for part in ("1/2", "2/2"):
                split.append(dict(GREEN_JOBS[0], name="client game tests (%s, part %s)" % (leg, part)))
        ids, _ = self.count([run(1, 1)], {1: split})
        self.assertEqual([1], ids)
        without_vulkan = [j for j in split if "Vulkan" not in j["name"]]
        _, kinds = self.count([run(1, 1)], {1: without_vulkan})
        self.assertEqual("break", kinds[1])

    # PLAN-21: the run's own jobs all green, plus ws-ci's minimum set, plus what a streak requires on top (the RC: E2E).
    def test_a_required_extra_job_must_be_there(self):
        extra = ("e2e (push, 26.2)",)
        ids, kinds = self.count([run(1, 1)], extra=extra)
        self.assertEqual("break", kinds[1])
        with_e2e = GREEN_JOBS + [dict(GREEN_JOBS[0], name="e2e (push, 26.2)")]
        ids, _ = self.count([run(1, 1)], {1: with_e2e}, extra=extra)
        self.assertEqual([1], ids)
        ids, kinds = self.count([run(1, 1)], {1: GREEN_JOBS + [dict(GREEN_JOBS[0], name="compat040", conclusion="failure")]})
        self.assertEqual("break", kinds[1])

    def test_a_legs_log_gives_class_times_requests_and_ratios(self):
        log = "\n".join([
            "2026-09-27T05:30:01Z [16:00:01] [Test thread/INFO]: Game-test class RigTuneClientGameTest runTest returned in 12345 ms",
            "2026-09-27T05:33:01Z [16:03:01] [Test thread/INFO]: Game-test class BenchmarkGameTest runTest threw in 180000 ms",
            "2026-09-27T05:33:01Z [16:03:01] [Test thread/INFO]: Game-test class BenchmarkGameTest runTest threw in 180000 ms",
            '2026-09-27T05:34:01Z     "tickHookOnVsReference": 1.311,',
            '2026-09-27T05:34:01Z     "tickHookOnTwinVsReference": 2.643,',
            "2026-09-27T05:36:01Z 820 requests to the fake Modrinth, the start-up lookups answered, every answer 2xx or a lookup's 404",
        ])
        details = cs.leg_details(log)
        self.assertEqual([("RigTuneClientGameTest", "returned", 12345), ("BenchmarkGameTest", "threw", 180000)], details["classes"])
        self.assertEqual(820, details["requests"])
        self.assertEqual({"tickHookOnVsReference": 1.311, "tickHookOnTwinVsReference": 2.643}, details["ratios"])
        line = cs.describe_leg("26.2, OpenGL", details)
        self.assertIn("820 requests", line)
        self.assertIn("tickHookOnVsReference 1.311 (twin 2.643)", line)
        self.assertIn("RigTuneClientGameTest 12.3 s, BenchmarkGameTest 180.0 s THREW", line)
        self.assertEqual({"tickHookOnVsReference": 1.5e-3}, cs.leg_details('"tickHookOnVsReference": 1.5e-3,')["ratios"])

    # Review: a log GitHub won't give (expired, rate-limited) leaves that leg "no log" instead of ending the script.
    def test_a_missing_log_is_none(self):
        def fail(*args, **kwargs):
            raise cs.subprocess.CalledProcessError(1, "gh")
        original = cs.gh_text
        cs.gh_text = fail
        try:
            self.assertIsNone(cs.job_log(123))
        finally:
            cs.gh_text = original

    # review-11 CI-1: build.yml calls e2e.yml, whose `jars` job and release-tier legs are skipped by design in every push or
    # dispatch run. The real job list of run 36380625735 (feat/v0.5.0 111cb2be, the split on) counts.
    POST_E6 = [("rules-consistency", "success"), ("java", "success"), ("rules-v1-compat", "success"), ("gametest-matrix", "success"),
               ("python", "success"), ("client game tests (26.3, Vulkan, part 1/2)", "success"),
               ("client game tests (26.3, OpenGL, part 1/2)", "success"), ("client game tests (26.3, Vulkan, part 2/2)", "success"),
               ("client game tests (26.2, OpenGL, part 2/2)", "success"), ("client game tests (26.3, OpenGL, part 2/2)", "success"),
               ("client game tests (26.2, OpenGL, part 1/2)", "success"), ("e2e / matrix", "success"), ("e2e / jars", "skipped"),
               ("e2e / e2e upgrade-from-0.4.0 (26.2)", "success"), ("e2e / e2e upgrade-from-0.4.0 (26.3)", "success"),
               ("e2e / battery OSHI leg (${{ matrix.mc }})", "skipped"), ("e2e / stutter script (${{ matrix.mc }})", "skipped")]

    def post_e6(self, **changes):
        jobs = [dict(GREEN_JOBS[0], name=name, conclusion=changes.get(name, conclusion)) for name, conclusion in self.POST_E6]
        return [j for j in jobs if j["conclusion"] != "missing"]

    def test_a_post_e6_run_with_its_design_skipped_e2e_jobs_counts(self):
        ids, kinds = self.count([run(1, 1)], {1: self.post_e6()})
        self.assertEqual([1], ids, kinds)
        # The RC streak also requires the E2E push jobs.
        ids, _ = self.count([run(1, 1)], {1: self.post_e6()}, extra=("e2e / e2e upgrade-from-0.4.0 (26.2)", "e2e / e2e upgrade-from-0.4.0 (26.3)"))
        self.assertEqual([1], ids)

    def test_only_the_design_skipped_e2e_jobs_may_be_skipped(self):
        for name in ("client game tests (26.2, OpenGL, part 2/2)", "e2e / e2e upgrade-from-0.4.0 (26.3)", "e2e / matrix", "java"):
            with self.subTest(name=name):
                _, kinds = self.count([run(1, 1)], {1: self.post_e6(**{name: "skipped"})})
                self.assertEqual("break", kinds[1])
        # A required job that is one of the design-skipped names still has to succeed.
        _, kinds = self.count([run(1, 1)], {1: self.post_e6()}, extra=("e2e / jars",))
        self.assertEqual("break", kinds[1])

    def test_a_split_leg_needs_every_part(self):
        _, kinds = self.count([run(1, 1)], {1: self.post_e6(**{"client game tests (26.3, OpenGL, part 2/2)": "missing"})})
        self.assertEqual("break", kinds[1])

    def test_runs_are_taken_oldest_first_whatever_the_listing_order(self):
        ids, _ = self.count([run(3, 3), run(1, 1, conclusion="failure"), run(2, 2)])
        self.assertEqual([2, 3], ids)

    def test_the_report_lists_the_streak_and_every_run(self):
        runs = [run(1, 1), run(2, 2, conclusion="cancelled")]
        current, looked = cs.streak(runs, lambda run_id: GREEN_JOBS)
        text = cs.markdown("feat/v0.5.0", None, 5, current, looked)
        self.assertIn("Latest streak: **1**", text)
        self.assertIn("[1](u1)", text)
        self.assertIn("2 `abc123` workflow_dispatch: ignore (cancelled)", text)
        self.assertIn("26.2, OpenGL 480 s", text)
        jobs = [dict(j, databaseId=i) for i, j in enumerate(GREEN_JOBS)]
        details = {5: cs.leg_details("Game-test class UiGameTest runTest returned in 2000 ms\n7 requests to the fake Modrinth")}
        text = cs.markdown("feat/v0.5.0", None, 5, [(runs[0], jobs)], looked, details)
        self.assertIn("- 26.2, OpenGL: 7 requests to the fake Modrinth; classes: UiGameTest 2.0 s", text)
        self.assertIn("- 26.3, OpenGL: no log", text)


if __name__ == "__main__":
    unittest.main()
