"""Counts the consecutive qualifying build.yml runs on a branch (docs/v0.5/SPEC.md 1g; docs/v0.5/design/ws-ci.md).

    python tools/ci_streak.py [--branch feat/v0.5.0] [--sha <commit>] [--need 5] [--write docs/v0.5/verification/ci-streak.md]

A run counts when its event is push or workflow_dispatch, its attempt is 1, and every job concluded success (none
skipped, none missing from REQUIRED_JOBS, at least MIN_GAME_TEST_LEGS client game-test legs). Runs are taken oldest first:
- a cancelled run neither counts nor breaks the streak (a newer push or dispatch cancels an overlapping one);
- a run from another event (pull_request, ...) neither counts nor breaks it;
- any other completed run that doesn't qualify (a failure, a re-run, a skipped job) breaks it;
- with --sha, only runs of that commit are considered.
Exit 0 when the latest streak is at least --need long, 1 otherwise. Needs the GitHub CLI (`gh`) for the real listing;
standard library otherwise. Dispatch runs one at a time: `gh workflow run build.yml --ref <branch>`.
"""

import argparse
import json
import subprocess
import sys
from datetime import datetime
from pathlib import Path

REQUIRED_JOBS = ("java", "python", "gametest-matrix", "rules-consistency", "rules-v1-compat")
GAME_TEST_PREFIX = "client game tests ("
MIN_GAME_TEST_LEGS = 3
COUNTED_EVENTS = ("push", "workflow_dispatch")


def gh_json(args):
    out = subprocess.run(["gh", *args], check=True, capture_output=True, text=True).stdout
    return json.loads(out)


def list_runs(branch, repo=None):
    args = ["run", "list", "--workflow", "build.yml", "--branch", branch, "--limit", "200", "--json",
            "databaseId,headSha,event,attempt,conclusion,status,createdAt,updatedAt,url"]
    if repo:
        args += ["-R", repo]
    return gh_json(args)


def list_jobs(run_id, repo=None):
    args = ["run", "view", str(run_id), "--json", "jobs"]
    if repo:
        args += ["-R", repo]
    return gh_json(args)["jobs"]


def verdict(run, jobs):
    """"count", "ignore" or "break", and why."""
    if run.get("status") != "completed":
        return "ignore", "not completed"
    if run.get("conclusion") == "cancelled":
        return "ignore", "cancelled"
    if run.get("event") not in COUNTED_EVENTS:
        return "ignore", "event " + str(run.get("event"))
    if run.get("attempt", 1) != 1:
        return "break", "attempt %s (re-run)" % run.get("attempt")
    if run.get("conclusion") != "success":
        return "break", "conclusion " + str(run.get("conclusion"))
    names = [j.get("name", "") for j in jobs]
    not_green = [j.get("name") + "=" + str(j.get("conclusion")) for j in jobs if j.get("conclusion") != "success"]
    if not_green:
        return "break", "jobs not green: " + ", ".join(not_green)
    missing = [name for name in REQUIRED_JOBS if name not in names]
    if missing:
        return "break", "missing jobs: " + ", ".join(missing)
    legs = [n for n in names if n.startswith(GAME_TEST_PREFIX)]
    if len(legs) < MIN_GAME_TEST_LEGS:
        return "break", "%d game-test legs" % len(legs)
    return "count", "all %d jobs green" % len(jobs)


def streak(runs, jobs_of, sha=None):
    """The latest unbroken streak of counting runs (oldest first), and the verdict of every run looked at."""
    current = []
    looked = []
    for run in sorted(runs, key=lambda r: r["createdAt"]):
        if sha and not run.get("headSha", "").startswith(sha):
            continue
        jobs = jobs_of(run["databaseId"]) if run.get("status") == "completed" and run.get("conclusion") == "success" else []
        kind, why = verdict(run, jobs)
        looked.append((run, kind, why, jobs))
        if kind == "count":
            current.append((run, jobs))
        elif kind == "break":
            current = []
    return current, looked


def duration(start, end):
    fmt = "%Y-%m-%dT%H:%M:%SZ"
    try:
        return int((datetime.strptime(end, fmt) - datetime.strptime(start, fmt)).total_seconds())
    except (TypeError, ValueError):
        return None


def markdown(branch, sha, need, current, looked):
    lines = ["# CI streak: build.yml on `%s`" % branch, ""]
    if sha:
        lines.append("Commit `%s`." % sha)
    lines.append("Needed: %d consecutive qualifying runs (docs/v0.5/SPEC.md 1g). Latest streak: **%d**." % (need, len(current)))
    lines += ["", "| run | SHA | event | attempt | conclusion | total | game-test legs |", "|---|---|---|---|---|---|---|"]
    for run, jobs in current:
        legs = []
        for job in jobs:
            if job.get("name", "").startswith(GAME_TEST_PREFIX):
                seconds = duration(job.get("startedAt"), job.get("completedAt"))
                legs.append("%s %s" % (job["name"][len(GAME_TEST_PREFIX):-1], "%d s" % seconds if seconds is not None else "?"))
        total = duration(run.get("createdAt"), run.get("updatedAt"))
        lines.append("| [%s](%s) | `%s` | %s | %s | %s | %s | %s |" % (run["databaseId"], run.get("url", ""), run.get("headSha", "")[:8],
                                                               run.get("event"), run.get("attempt"), run.get("conclusion"),
                                                               "%d s" % total if total is not None else "?", "; ".join(legs)))
    lines += ["", "Every run looked at, oldest first:", ""]
    for run, kind, why, _ in looked:
        lines.append("- %s `%s` %s: %s (%s)" % (run["databaseId"], run.get("headSha", "")[:8], run.get("event"), kind, why))
    return "\n".join(lines) + "\n"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--branch", default="feat/v0.5.0")
    parser.add_argument("--sha")
    parser.add_argument("--need", type=int, default=5)
    parser.add_argument("--repo")
    parser.add_argument("--write")
    args = parser.parse_args(argv)
    runs = list_runs(args.branch, args.repo)
    current, looked = streak(runs, lambda run_id: list_jobs(run_id, args.repo), args.sha)
    report = markdown(args.branch, args.sha, args.need, current, looked)
    if args.write:
        Path(args.write).write_text(report, encoding="utf-8", newline="\n")
    print(report)
    return 0 if len(current) >= args.need else 1


if __name__ == "__main__":
    sys.exit(main())
