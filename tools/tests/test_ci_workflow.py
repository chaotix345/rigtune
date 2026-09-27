"""Rules for the CI workflows (docs/v0.5/SPEC.md AC1a.1, AC1c.3; docs/v0.5/design/ws-ci.md).

The workflows are read with a small parser for their fixed shape (jobs -> steps -> keys, block scalars), since the tools
use the standard library only.
"""

import re
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools" / "ci"))

import check_fake_modrinth as cfm  # noqa: E402

WORKFLOWS = ROOT / ".github" / "workflows"


def parse_jobs(text):
    """{job: {"keys": {key: value}, "steps": [{key: value}]}} for a workflow's jobs; block scalars keep their lines."""
    jobs = {}
    job = None
    step = None
    in_jobs = False
    block = None  # (target dict, key, indent)
    for raw in text.splitlines():
        if block is not None:
            target, key, indent = block
            if not raw.strip() or len(raw) - len(raw.lstrip(" ")) >= indent:
                target[key] += raw[indent:] + "\n"
                continue
            block = None
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        indent = len(raw) - len(raw.lstrip(" "))
        line = raw.strip()
        if indent == 0:
            in_jobs = line == "jobs:"
            job = None
            continue
        if not in_jobs:
            continue
        if indent == 2 and line.endswith(":"):
            job = jobs.setdefault(line[:-1], {"keys": {}, "steps": []})
            step = None
            continue
        if job is None:
            continue
        if indent == 6 and line.startswith("- "):
            step = {}
            job["steps"].append(step)
            line = line[2:]
            indent = 8
        target = step if (step is not None and indent == 8) else (job["keys"] if indent == 4 else None)
        if target is None or ":" not in line:
            continue
        key, _, value = line.partition(":")
        value = value.strip()
        if value in ("|", "|-", ">", ">-"):
            target[key] = ""
            block = (target, key, indent + 2)
        else:
            target[key] = value.strip("'\"")
    return jobs


def gradle_commands(run):
    """The lines that run Gradle (not ones that only mention it, like an error message)."""
    commands = []
    for line in run.replace("\\\n", " ").splitlines():
        line = line.strip()
        if line.startswith("./gradlew") or "retry.sh ./gradlew" in line or "offline.sh ./gradlew" in line:
            commands.append(line)
    return commands


class BuildWorkflowTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.text = (WORKFLOWS / "build.yml").read_text(encoding="utf-8")
        cls.jobs = parse_jobs(cls.text)

    def test_the_parser_sees_every_job(self):
        self.assertTrue({"java", "gametest-matrix", "client-gametest", "python", "rules-consistency", "rules-v1-compat"} <= set(self.jobs))

    # AC1a.1: in each job that builds, one "Resolve dependencies (network)" step comes before every other Gradle step, and
    # every Gradle command after it runs --offline.
    def test_gradle_runs_offline_after_the_one_resolve_step(self):
        for name in ("java", "client-gametest"):
            steps = self.jobs[name]["steps"]
            resolve = [i for i, s in enumerate(steps) if s.get("name") == "Resolve dependencies (network)"]
            self.assertEqual(1, len(resolve), name)
            for i, s in enumerate(steps):
                commands = gradle_commands(s.get("run", ""))
                if i < resolve[0]:
                    self.assertEqual([], commands, f"{name}: Gradle before the resolve step: {s.get('name')}")
                elif i > resolve[0]:
                    for command in commands:
                        self.assertIn("--offline", command, f"{name} / {s.get('name')}: {command}")

    # AC1a.1: retries only where something is downloaded; tests are never retried.
    def test_only_network_steps_retry(self):
        for name, job in self.jobs.items():
            for s in job["steps"]:
                if "retry.sh" in s.get("run", ""):
                    self.assertIn("network", s.get("name", ""), f"{name} / {s.get('name')} retries")

    # AC1a.1: the tests run with no network but loopback.
    def test_tests_run_in_the_loopback_only_namespace(self):
        game = [s for s in self.jobs["client-gametest"]["steps"] if s.get("name") == "Client game tests"]
        self.assertEqual(1, len(game))
        self.assertRegex(game[0]["run"], r"tools/ci/offline\.sh ./gradlew [^\n]*--offline[^\n]*:runProductionClientGameTest")
        build = [s for s in self.jobs["java"]["steps"] if s.get("name") == "Build and test"]
        self.assertIn("tools/ci/offline.sh ./gradlew", build[0]["run"])
        for s in self.jobs["python"]["steps"]:
            if "unittest" in s.get("run", ""):
                self.assertTrue(s["run"].startswith("tools/ci/offline.sh "), s["run"])
        script = (ROOT / "tools" / "ci" / "offline.sh").read_text(encoding="utf-8")
        self.assertIn("unshare --net", script)
        self.assertIn("ip link set lo up", script)

    def test_no_step_ignores_its_failure(self):
        self.assertNotIn("continue-on-error", self.text)

    # SPEC 1e / 1g: the game-test step times out at 15 min or more, inside a longer job timeout, and dumps threads first.
    def test_game_test_timeouts_and_thread_dump(self):
        job = self.jobs["client-gametest"]
        game = [s for s in job["steps"] if s.get("name") == "Client game tests"][0]
        self.assertGreaterEqual(int(game["timeout-minutes"]), 15)
        self.assertGreater(int(job["keys"]["timeout-minutes"]), int(game["timeout-minutes"]))
        self.assertIn("kill -QUIT", game["run"])
        self.assertIn("/proc/$p/comm", game["run"])


class AllWorkflowsTests(unittest.TestCase):
    # AC1c.3: a fixed environment: no moving runner image, a patch-pinned JDK.
    def test_pinned_runner_and_jdk(self):
        files = sorted(WORKFLOWS.glob("*.yml"))
        self.assertTrue(files)
        for file in files:
            text = file.read_text(encoding="utf-8")
            for runner in re.findall(r"runs-on:\s*(\S+)", text):
                self.assertRegex(runner, r"^ubuntu-\d\d\.\d\d$", file.name)
            for version in re.findall(r"java-version:\s*(\S+)", text):
                self.assertRegex(version.strip("'\""), r"^\d+\.\d+\.\d+$", file.name)


class CheckFakeModrinthTests(unittest.TestCase):
    STARTUP = [
        {"method": "POST", "path": "/v2/version_files", "status": 200},
        {"method": "POST", "path": "/v2/version_files/update", "status": 200},
        {"method": "GET", "path": "/v2/projects", "status": 200},
        {"method": "GET", "path": "/v2/project/gvQqBUqZ/version", "status": 200},
    ]

    def test_a_clean_leg_passes(self):
        lines = ["[Test thread/INFO]: fine\n",
                 "[RigTune network/WARN]: Modrinth lookups failed; using offline data: ModrinthException: Modrinth is off in RigTune's settings\n"]
        self.assertEqual([], cfm.problems(self.STARTUP, lines))

    def test_a_missing_start_up_lookup_a_server_error_or_an_offline_fallback_fails(self):
        self.assertEqual(["the fake Modrinth logged no request"], cfm.problems([], []))
        missing = cfm.problems(self.STARTUP[:3], [])
        self.assertEqual(1, len(missing))
        self.assertIn("/v2/project/<id>/version", missing[0])
        errors = cfm.problems(self.STARTUP + [{"method": "GET", "path": "/v2/versions", "status": 500}], [])
        self.assertEqual(1, len(errors))
        fallback = cfm.problems(self.STARTUP, ["[RigTune network/WARN]: Modrinth lookups failed; using offline data: IOException: Stream 5 cancelled\n"])
        self.assertEqual(1, len(fallback))

    def test_the_fakes_own_errors_fail_but_an_unknown_project_or_hash_is_an_answer(self):
        answers = [{"method": "GET", "path": "/v2/project/nope/version", "status": 404},
                   {"method": "GET", "path": "/v2/project/nope", "status": 404},
                   {"method": "GET", "path": "/v2/version_file/0123abcd", "status": 404}]
        self.assertEqual([], cfm.problems(self.STARTUP + answers, []))
        for bad in ({"method": "POST", "path": "/v2/version_files", "status": 400},
                    {"method": "DELETE", "path": "/v2/projects", "status": 405},
                    {"method": "GET", "path": "/v2/search", "status": 404},
                    {"method": "GET", "path": "/v2/versions"}):
            found = cfm.problems(self.STARTUP + [bad], [])
            self.assertEqual(1, len(found), bad)
            self.assertIn(bad["path"], found[0])


if __name__ == "__main__":
    unittest.main()
