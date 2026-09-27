"""Counts the consecutive qualifying build.yml runs on a branch (docs/v0.5/SPEC.md 1g; docs/v0.5/design/ws-ci.md).

    python tools/ci_streak.py [--branch feat/v0.5.0] [--sha <commit>] [--need 5] [--require <job name> ...]
                              [--write docs/v0.5/verification/ci-streak.md]

A run counts when its event is push or workflow_dispatch, its attempt is 1, every job it has concluded success (none
skipped), and it has ws-ci's minimum set: REQUIRED_JOBS and a job for each of REQUIRED_LEGS (with the split on, a leg's
parts count as that leg), plus any --require job (the RC streak adds WS-E's E2E push jobs). Runs are taken oldest first:
- a cancelled run neither counts nor breaks the streak (a newer push or dispatch cancels an overlapping one);
- a run from another event (pull_request, ...) neither counts nor breaks it;
- any other completed run that doesn't qualify (a failure, a re-run, a skipped or missing job) breaks it;
- with --sha, only runs of that commit are considered.
For the streak's runs the report adds, per game-test leg (from its job log): each game-test class's wall time, the
requests to the fake Modrinth, and tickHookOnVsReference with its twin.
Exit 0 when the latest streak is at least --need long, 1 otherwise. Needs the GitHub CLI (`gh`) for the real listing;
standard library otherwise. Dispatch runs one at a time: `gh workflow run build.yml --ref <branch>`.
"""

import argparse
import json
import re
import subprocess
import sys
from datetime import datetime
from pathlib import Path

REQUIRED_JOBS = ("java", "python", "gametest-matrix", "rules-consistency", "rules-v1-compat")
REQUIRED_LEGS = ("26.2, OpenGL", "26.3, OpenGL", "26.3, Vulkan")
GAME_TEST_PREFIX = "client game tests ("
COUNTED_EVENTS = ("push", "workflow_dispatch")
CLASS_LINE = re.compile(r"Game-test class (\w+) runTest (returned|threw) in (\d+) ms")
REQUESTS_LINE = re.compile(r"(\d+) requests to the fake Modrinth")
RATIO_LINE = re.compile(r'"(tickHookOnVsReference|tickHookOnTwinVsReference)": ([-0-9.eE+]+)')


def gh_text(args):
    return subprocess.run(["gh", *args], check=True, capture_output=True, text=True, encoding="utf-8", errors="replace").stdout


def gh_json(args):
    return json.loads(gh_text(args))


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


def job_log(job_id, repo=None):
    """The job's log, or None when GitHub doesn't give it (expired, rate-limited): the report then says "no log"."""
    try:
        return gh_text(["api", "repos/{}/actions/jobs/{}/logs".format(repo or "{owner}/{repo}", job_id)])
    except subprocess.CalledProcessError:
        return None


def leg_of(name):
    """"26.2, OpenGL" for "client game tests (26.2, OpenGL)" and its parts ("…, part 1/2)"); None for other jobs."""
    if not name.startswith(GAME_TEST_PREFIX) or not name.endswith(")"):
        return None
    return re.sub(r", part \d+/\d+$", "", name[len(GAME_TEST_PREFIX):-1])


def verdict(run, jobs, extra=()):
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
    legs = {leg_of(name) for name in names}
    missing = [name for name in REQUIRED_JOBS + tuple(extra) if name not in names]
    missing += [GAME_TEST_PREFIX + leg + ")" for leg in REQUIRED_LEGS if leg not in legs]
    if missing:
        return "break", "missing jobs: " + ", ".join(missing)
    return "count", "all %d jobs green" % len(jobs)


def streak(runs, jobs_of, sha=None, extra=()):
    """The latest unbroken streak of counting runs (oldest first), and the verdict of every run looked at."""
    current = []
    looked = []
    for run in sorted(runs, key=lambda r: r["createdAt"]):
        if sha and not run.get("headSha", "").startswith(sha):
            continue
        jobs = jobs_of(run["databaseId"]) if run.get("status") == "completed" and run.get("conclusion") == "success" else []
        kind, why = verdict(run, jobs, extra)
        looked.append((run, kind, why, jobs))
        if kind == "count":
            current.append((run, jobs))
        elif kind == "break":
            current = []
    return current, looked


def leg_details(log):
    """From a game-test job's log: [(class, outcome, ms)] (each class once), the fake Modrinth's request count and the tick
    ratios."""
    classes = {}
    for m in CLASS_LINE.finditer(log):
        classes.setdefault(m.group(1), (m.group(1), m.group(2), int(m.group(3))))
    classes = list(classes.values())
    requests = REQUESTS_LINE.search(log)
    ratios = {m.group(1): float(m.group(2)) for m in RATIO_LINE.finditer(log)}
    return {"classes": classes, "requests": int(requests.group(1)) if requests else None, "ratios": ratios}


def duration(start, end):
    fmt = "%Y-%m-%dT%H:%M:%SZ"
    try:
        return int((datetime.strptime(end, fmt) - datetime.strptime(start, fmt)).total_seconds())
    except (TypeError, ValueError):
        return None


def describe_leg(name, details):
    if details is None:
        return name + ": no log"
    parts = []
    if details["requests"] is not None:
        parts.append("%d requests to the fake Modrinth" % details["requests"])
    ratio = details["ratios"].get("tickHookOnVsReference")
    if ratio is not None:
        parts.append("tickHookOnVsReference %.3f (twin %.3f)" % (ratio, details["ratios"].get("tickHookOnTwinVsReference", float("nan"))))
    if details["classes"]:
        parts.append("classes: " + ", ".join("%s %.1f s%s" % (c, ms / 1000, "" if outcome == "returned" else " THREW")
                                             for c, outcome, ms in details["classes"]))
    return name + ": " + ("; ".join(parts) if parts else "nothing recorded")


def markdown(branch, sha, need, current, looked, details=None):
    details = details or {}
    lines = ["# CI streak: build.yml on `%s`" % branch, ""]
    if sha:
        lines.append("Commit `%s`." % sha)
    lines.append("Needed: %d consecutive qualifying runs (docs/v0.5/SPEC.md 1g). Latest streak: **%d**." % (need, len(current)))
    lines += ["", "| run | SHA | event | attempt | conclusion | total | game-test legs |", "|---|---|---|---|---|---|---|"]
    for run, jobs in current:
        legs = []
        for job in jobs:
            if leg_of(job.get("name", "")) is not None:
                seconds = duration(job.get("startedAt"), job.get("completedAt"))
                legs.append("%s %s" % (job["name"][len(GAME_TEST_PREFIX):-1], "%d s" % seconds if seconds is not None else "?"))
        total = duration(run.get("createdAt"), run.get("updatedAt"))
        lines.append("| [%s](%s) | `%s` | %s | %s | %s | %s | %s |" % (run["databaseId"], run.get("url", ""), run.get("headSha", "")[:8],
                                                               run.get("event"), run.get("attempt"), run.get("conclusion"),
                                                               "%d s" % total if total is not None else "?", "; ".join(legs)))
    if details:
        lines += ["", "Per leg (from the job logs of the last %d runs):" % need]
        for run, jobs in current[-need:]:
            lines += ["", "Run %s:" % run["databaseId"]]
            for job in jobs:
                if leg_of(job.get("name", "")) is not None:
                    lines.append("- " + describe_leg(job["name"][len(GAME_TEST_PREFIX):-1], details.get(job.get("databaseId"))))
    lines += ["", "Every run looked at, oldest first:", ""]
    for run, kind, why, _ in looked:
        lines.append("- %s `%s` %s: %s (%s)" % (run["databaseId"], run.get("headSha", "")[:8], run.get("event"), kind, why))
    return "\n".join(lines) + "\n"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--branch", default="feat/v0.5.0")
    parser.add_argument("--sha")
    parser.add_argument("--need", type=int, default=5)
    parser.add_argument("--require", action="append", default=[], help="another job every run must have (repeatable)")
    parser.add_argument("--repo")
    parser.add_argument("--write")
    args = parser.parse_args(argv)
    runs = list_runs(args.branch, args.repo)
    current, looked = streak(runs, lambda run_id: list_jobs(run_id, args.repo), args.sha, args.require)
    details = {}
    for _, jobs in current[-args.need:]:
        for job in jobs:
            if leg_of(job.get("name", "")) is not None and job.get("databaseId"):
                log = job_log(job["databaseId"], args.repo)
                if log is not None:
                    details[job["databaseId"]] = leg_details(log)
    report = markdown(args.branch, args.sha, args.need, current, looked, details)
    if args.write:
        Path(args.write).write_text(report, encoding="utf-8", newline="\n")
    print(report)
    return 0 if len(current) >= args.need else 1


if __name__ == "__main__":
    sys.exit(main())
