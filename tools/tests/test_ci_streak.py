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
    def count(self, runs, jobs=None, sha=None):
        jobs = jobs or {}
        current, looked = cs.streak(runs, lambda run_id: jobs.get(run_id, GREEN_JOBS), sha)
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


if __name__ == "__main__":
    unittest.main()
