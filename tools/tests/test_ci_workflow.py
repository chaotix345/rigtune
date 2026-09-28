"""Rules for the CI workflows (docs/v0.5/SPEC.md AC1a.1, AC1a.3, AC1c.3, 1e, 1g; docs/v0.5/design/ws-ci.md).

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


def jdk_retry_problems(jobs):
    """Where a workflow breaks the JDK download rule: continue-on-error only on a setup-java step with an id, followed by
    a wait and a retry that run only when it failed (and can't be ignored); every setup-java step is one of the two."""
    problems = []
    for name, job in jobs.items():
        steps = job["steps"]
        retries = set()
        for i, s in enumerate(steps):
            java = s.get("uses", "").startswith("actions/setup-java@")
            if "continue-on-error" not in s:
                if java and i not in retries:
                    problems.append(f"{name} / {s.get('name')}: a JDK download with no retry")
                continue
            ident = s.get("id")
            if not java or not ident or s.get("continue-on-error") != "true":
                problems.append(f"{name} / {s.get('name')}: continue-on-error outside the JDK download's first try")
                continue
            later = steps[i + 1:i + 3]
            conditional = [re.fullmatch(r"steps\.(\w+)\.outcome == 'failure'?", r.get("if", "")) for r in later]
            if (len(later) != 2 or not all(m and m.group(1) == ident for m in conditional)
                    or later[0].get("run", "").strip() != "sleep 30"
                    or not later[1].get("uses", "").startswith("actions/setup-java@") or "continue-on-error" in later[1]):
                problems.append(f"{name} / {s.get('name')}: no wait and retry after the first try")
                continue
            retries.add(i + 2)
    return problems


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
        self.assertRegex(game[0]["run"], r"tools/ci/offline\.sh (--timeout \S+ )?./gradlew [^\n]*--offline[^\n]*:runProductionClientGameTest")
        build = [s for s in self.jobs["java"]["steps"] if s.get("name") == "Build and test"]
        self.assertIn("tools/ci/offline.sh ./gradlew", build[0]["run"])
        for s in self.jobs["python"]["steps"]:
            if "unittest" in s.get("run", ""):
                self.assertTrue(s["run"].startswith("tools/ci/offline.sh "), s["run"])
        script = (ROOT / "tools" / "ci" / "offline.sh").read_text(encoding="utf-8")
        self.assertIn("unshare --net", script)
        self.assertIn("ip link set lo up", script)

    # AC1a.3: loopback carries multicast in the namespace, checked on every leg before the game tests.
    def test_every_leg_checks_loopback_multicast_in_the_namespace(self):
        script = (ROOT / "tools" / "ci" / "offline.sh").read_text(encoding="utf-8")
        self.assertIn("ip link set lo multicast on", script)
        self.assertIn("ip route add 224.0.0.0/4 dev lo", script)
        steps = self.jobs["client-gametest"]["steps"]
        names = [s.get("name") for s in steps]
        check = names.index("Check loopback multicast")
        self.assertEqual("tools/ci/offline.sh java tools/ci/MulticastCheck.java", steps[check]["run"].strip())
        self.assertNotIn("if", steps[check])
        self.assertLess(names.index("Resolve dependencies (network)"), check)
        self.assertLess(check, names.index("Client game tests"))
        source = (ROOT / "tools" / "ci" / "MulticastCheck.java").read_text(encoding="utf-8")
        self.assertIn('"224.0.2.60"', source)
        self.assertIn("new MulticastSocket(4445)", source)
        self.assertIn("{1, 1, 1, 1}), 443", source, "and something off the machine stays unreachable")

    # No step ignores its failure; the JDK download's first try is the one exception (JdkRetryTests).
    def test_no_step_ignores_its_failure(self):
        self.assertEqual([], jdk_retry_problems(self.jobs))

    # SPEC 1e / 1g: the game-test step times out at 15 min or more, inside a longer job timeout, and dumps threads first.
    def test_game_test_timeouts_and_thread_dump(self):
        job = self.jobs["client-gametest"]
        game = [s for s in job["steps"] if s.get("name") == "Client game tests"][0]
        self.assertGreaterEqual(int(game["timeout-minutes"]), 15)
        # Room for a cold cache and one retry cycle of the network step before the game tests (coordinator: 35 min).
        self.assertGreaterEqual(int(job["keys"]["timeout-minutes"]), int(game["timeout-minutes"]) + 20)
        # The dump at `sleep N`, then offline.sh's timeout (TERM as root, KILL 30 s later), both inside the step's limit.
        dump = int(re.search(r"sleep (\d+);", game["run"]).group(1))
        limit = re.search(r"offline\.sh --timeout (\d+)m ", game["run"])
        self.assertIsNotNone(limit, "the game-test run has offline.sh's timeout")
        self.assertLess(dump, int(limit.group(1)) * 60)
        self.assertLess(int(limit.group(1)) * 60 + 15, int(game["timeout-minutes"]) * 60 - 30)
        script = (ROOT / "tools" / "ci" / "offline.sh").read_text(encoding="utf-8")
        self.assertIn("timeout --kill-after=15s", script)
        self.assertLess(script.index('"${limit[@]}"'), script.index("setpriv"), "the timeout runs as root, outside setpriv")
        # What the timeout's TERM leaves (it returns once Gradle's launcher has exited) is killed within the step.
        self.assertIn("pkill -KILL -x java", game["run"])
        # TERM, KILL 15 s later, then up to 20 s of waiting: well inside the step's limit.
        wait = int(re.search(r'\[ "\$waited" -lt (\d+) \]', game["run"]).group(1))
        self.assertLess(int(limit.group(1)) * 60 + 15 + wait + 10, int(game["timeout-minutes"]) * 60)
        self.assertIn("kill -QUIT", game["run"])
        # The game's JVM runs KnotClient; Gradle's and the fake Modrinth's JVMs are java too (SPEC 1e). KnotClient comes
        # after the classpath, so the whole cmdline is searched, not pgrep -f's view of it.
        self.assertIn("pgrep -x java", game["run"])
        self.assertIn('/proc/$p/cmdline" 2>/dev/null | grep -qxF net.fabricmc.loader.impl.launch.knot.KnotClient', game["run"])
        self.assertNotIn("pgrep -f", game["run"])

    # SPEC 1g: the dormant split. A part's classes reach both Gradle steps, and its artifacts and job name say which part.
    def test_a_split_part_reaches_gradle_and_names_its_artifacts(self):
        job = self.jobs["client-gametest"]
        self.assertIn("GAMETEST_CLASSES: ${{ matrix.classes }}", self.text)
        self.assertIn("matrix.part", job["keys"]["name"])
        for s in job["steps"]:
            if s.get("name") in ("Resolve dependencies (network)", "Client game tests"):
                self.assertIn('"-PgametestClasses=$GAMETEST_CLASSES"', s["run"], s["name"])
        artifacts = re.findall(r"^\s+name: ((?:gametest|footprint)-.*)$", self.text.split("  client-gametest:")[1].split("\n  python:")[0], re.M)
        self.assertEqual(4, len(artifacts))
        for name in artifacts:
            self.assertTrue(name.endswith("${{ matrix.suffix }}"), name)
        # One flag in build.yml switches the split (1 = off; 2 since 2026-09-28); a dispatch input overrides it for one run.
        self.assertRegex(self.text, r"(?m)^env:\n(  #.*\n)*  GAMETEST_PARTS: [12]$")
        matrix = [s for s in self.jobs["gametest-matrix"]["steps"] if s.get("id") == "matrix"][0]
        self.assertIn('--parts "$PARTS"', matrix["run"])
        self.assertIn("PARTS: ${{ inputs.gametest_parts || env.GAMETEST_PARTS }}", self.text)


class StreakWorkflowsTests(unittest.TestCase):
    # The workflows in the 5-run acceptance (build.yml, and WS-E's e2e.yml once it exists) and release.yml.
    FILES = [WORKFLOWS / name for name in ("build.yml", "e2e.yml", "release.yml") if (WORKFLOWS / name).exists()]

    # AC1c.3: a fixed environment: no moving runner image, a patch-pinned JDK. snapshot-canary.yml and update-rules.yml
    # aren't in the streak and stay as they are.
    def test_pinned_runner_and_jdk(self):
        self.assertIn(WORKFLOWS / "build.yml", self.FILES)
        self.assertIn(WORKFLOWS / "release.yml", self.FILES)
        for file in self.FILES:
            text = file.read_text(encoding="utf-8")
            for runner in re.findall(r"runs-on:\s*(\S+)", text):
                self.assertRegex(runner, r"^ubuntu-\d\d\.\d\d$", file.name)
            for version in re.findall(r"java-version:\s*(\S+)", text):
                self.assertRegex(version.strip("'\""), r"^\d+\.\d+\.\d+$", file.name)

    # AC1a.1 for e2e.yml (SPEC 1a): outside a download step ("... (network)"), every Gradle command runs --offline.
    def test_e2e_gradle_runs_offline(self):
        file = WORKFLOWS / "e2e.yml"
        if not file.exists():
            self.skipTest("e2e.yml doesn't exist yet (WS-E)")
        for name, job in parse_jobs(file.read_text(encoding="utf-8")).items():
            for s in job["steps"]:
                if "(network)" in s.get("name", ""):
                    continue
                for command in gradle_commands(s.get("run", "")):
                    self.assertIn("--offline", command, f"e2e.yml {name} / {s.get('name')}: {command}")


class JdkRetryTests(unittest.TestCase):
    FILES = StreakWorkflowsTests.FILES

    def test_every_jdk_download_has_one_retry(self):
        for file in self.FILES:
            self.assertEqual([], jdk_retry_problems(parse_jobs(file.read_text(encoding="utf-8"))), file.name)

    def test_the_rule_catches_a_bare_download_an_ignored_step_and_a_missing_retry(self):
        bare = {"j": {"keys": {}, "steps": [{"uses": "actions/setup-java@v6"}]}}
        ignored = {"j": {"keys": {}, "steps": [{"name": "Tests", "run": "./gradlew test", "continue-on-error": "true"}]}}
        no_retry = {"j": {"keys": {}, "steps": [{"uses": "actions/setup-java@v6", "id": "jdk", "continue-on-error": "true"}]}}
        unguarded = {"j": {"keys": {}, "steps": [
            {"uses": "actions/setup-java@v6", "id": "jdk", "continue-on-error": "true"},
            {"if": "steps.jdk.outcome == 'failure", "run": "sleep 30"},
            {"uses": "actions/setup-java@v6", "if": "steps.jdk.outcome == 'failure", "continue-on-error": "true"}]}}
        good = {"j": {"keys": {}, "steps": [
            {"uses": "actions/setup-java@v6", "id": "jdk", "continue-on-error": "true"},
            {"if": "steps.jdk.outcome == 'failure", "run": "sleep 30"},
            {"uses": "actions/setup-java@v6", "if": "steps.jdk.outcome == 'failure"}]}}
        for jobs in (bare, ignored, no_retry, unguarded):
            self.assertNotEqual([], jdk_retry_problems(jobs), jobs)
        self.assertEqual([], jdk_retry_problems(good))


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
