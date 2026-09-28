"""The E2E and release workflows (docs/v0.5/SPEC.md AC3a.6, AC3c.1): no automatic retry and no network use in the E2E
besides the pinned, sha256-checked old jar; release.yml publishes the staged jars the E2E tested, never a rebuild, and
a dry run publishes nothing. Plain text checks (the python job has no YAML parser)."""

import re
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

REPO = Path(__file__).resolve().parents[3]
WORKFLOWS = REPO / ".github" / "workflows"


def text(name):
    """The workflow without its comment lines."""
    lines = (WORKFLOWS / name).read_text(encoding="utf-8").splitlines()
    return "\n".join(line for line in lines if not line.lstrip().startswith("#")) + "\n"


def jobs(workflow):
    """Job id -> its text (the lines indented under `jobs:`)."""
    body = workflow.split("\njobs:\n", 1)[1]
    out, current = {}, None
    for line in body.splitlines():
        match = re.match(r"^  ([A-Za-z0-9_-]+):\s*$", line)
        if match:
            current = match.group(1)
            out[current] = ""
        elif current:
            out[current] += line + "\n"
    return out


def steps(job):
    """The job's steps, each as its text."""
    return [("  - " + s) for s in re.split(r"\n\s{6}- ", "\n" + job.split("steps:", 1)[1])[1:]] if "steps:" in job else []


def runs(workflow):
    """Every run: block's script."""
    return re.findall(r"run: \|\n((?:\s{10,}.*\n|\n)+)|run: (.+)\n", workflow)


def scripts(workflow):
    return "\n".join(block or line for block, line in runs(workflow))


class E2eWorkflowTest(unittest.TestCase):
    def setUp(self):
        self.workflow = text("e2e.yml")
        self.jobs = jobs(self.workflow)

    def test_it_is_reusable_and_can_be_dispatched(self):
        self.assertIn("\n  workflow_call:\n", self.workflow)
        self.assertIn("\n  workflow_dispatch:\n", self.workflow)
        self.assertIn('python3 tools/e2e/e2e_matrix.py --tier "$TIER"', self.jobs["matrix"])

    def test_no_automatic_retry_of_a_scenario(self):
        self.assertNotRegex(self.workflow.lower(), r"max[_-]attempts|nick-fields/retry")
        self.assertEqual(1, self.workflow.count("tools/e2e/self_update_e2e.py"), "the harness runs once per job")
        for job in self.jobs.values():
            for step in steps(job):
                # The one failure a job carries on after: the JDK download's first try, which its retry step repeats.
                if "continue-on-error" in step:
                    self.assertIn("name: Set up the JDK (network)", step.split("\n", 1)[0])
                    self.assertIn("uses: actions/setup-java@", step)
                if "retry.sh" in step:
                    self.assertIn("(network)", step.split("\n", 1)[0], "only a download step retries: " + step[:80])
        self.assertIn("fail-fast: false", self.jobs["e2e"])
        self.assertIn("timeout-minutes: 20", self.jobs["e2e"])
        self.assertIn("max-parallel: 6", self.jobs["e2e"])

    def test_network_only_in_the_network_steps_and_gradle_offline_after_them(self):
        script = scripts(self.workflow)
        for forbidden in ("curl", "wget", "pip install", "apt-get", "git clone", "http://", "https://", "Invoke-WebRequest"):
            self.assertNotIn(forbidden, script, forbidden)
        for name, job in self.jobs.items():
            seen_network = False
            for step in steps(job):
                network = "(network)" in step.split("\n", 1)[0]
                for command in re.findall(r"\./gradlew[^\n]*", step):
                    if not network:
                        self.assertTrue(seen_network, "{}: Gradle before the network step: {}".format(name, command))
                        self.assertIn("--offline", command, name)
                seen_network |= network
        run = next(s for s in steps(self.jobs["e2e"]) if "self_update_e2e.py" in s)
        self.assertIn("--gradle-arg=--offline", run, "the harness's own Gradle calls run --offline")

    def test_the_old_jar_is_cached_downloaded_only_on_a_miss_and_checked_every_time(self):
        e2e = self.jobs["e2e"]
        cache = next(s for s in steps(e2e) if "actions/cache" in s)
        self.assertIn("key: e2e-old-${{ matrix.asset }}-${{ matrix.sha256 }}", cache)
        network = next(s for s in steps(e2e) if "name: Resolve dependencies (network)" in s.split("\n", 1)[0])
        self.assertIn('tools/ci/retry.sh ./gradlew --no-daemon ":$MC:prefetchDependencies" ":$MC:downloadAssets"', network)
        self.assertRegex(network, r'\[ -s "\$RUNNER_TEMP/old/\$OLD_ASSET" \] \\\n\s+\|\| tools/ci/retry\.sh gh release download')
        self.assertRegex(network, r'echo "\$OLD_SHA256  \$RUNNER_TEMP/old/\$OLD_ASSET" \| sha256sum -c -')
        self.assertIn("set -eu", network)

    def test_the_e2e_job_tests_the_caller_s_jars_and_never_builds_them(self):
        e2e = self.jobs["e2e"]
        self.assertIn("actions/download-artifact", e2e)
        self.assertIn("inputs.jars-artifact", e2e)
        self.assertNotRegex(e2e, r"gradlew[^\n]*\s(build|assemble|jar)\b")
        self.assertIn("if: ${{ !inputs.jars-artifact }}", self.jobs["jars"])

    # AC3e.2: the battery OSHI leg runs in the release tier only, per node, on the tmpfs battery mounted before the game,
    # and tests the caller's RigTune jar (-PgametestModJar), never a rebuilt one (review M2).
    def test_the_battery_oshi_leg(self):
        battery = self.jobs["battery-oshi"]
        self.assertIn("inputs.tier == 'release'", battery)
        self.assertIn("mc: ${{ fromJSON(needs.matrix.outputs.nodes) }}", battery)
        self.assertIn("inputs.jars-artifact", battery)
        self.assertNotRegex(battery, r"gradlew[^\n]*\s(build|assemble|jar)\b")
        self.assertNotRegex(battery, r'":\$MC:jar"')
        mount = next(i for i, s in enumerate(steps(battery)) if "tools/e2e/fake_battery.sh Discharging" in s)
        game = next(i for i, s in enumerate(steps(battery)) if "runProductionClientGameTest" in s)
        self.assertLess(mount, game)
        run = " ".join(steps(battery)[game].split())
        self.assertRegex(run, r"tools/ci/offline\.sh --timeout \d+m ./gradlew --no-daemon --offline ")
        self.assertIn('"-PgametestModJar=${found[0]}"', run)
        self.assertIn("-PgametestClasses=BatteryFlowGameTest", run)
        self.assertIn("-Doshi.os.linux.allowudev=false -Drigtune.gametest.fakeBattery=/sys/class/power_supply/BAT0/uevent", run)
        self.assertIn("if: always()", next(s for s in steps(battery) if "upload-artifact" in s))

    # AC3f.1: the release tier's stutter-script leg runs the caller's jar (never a rebuilt one) with no network.
    def test_the_stutter_script_leg(self):
        job = self.jobs["stutter-script"]
        self.assertIn("inputs.tier == 'release'", job)
        self.assertIn("mc: ${{ fromJSON(needs.matrix.outputs.nodes) }}", job)
        self.assertIn("python3 tools/e2e/e2e_matrix.py --nodes", self.jobs["matrix"])
        self.assertIn("inputs.jars-artifact", job)
        self.assertNotRegex(job, r"gradlew[^\n]*\s(build|assemble|jar)\b")
        run = " ".join(next(s for s in steps(job) if "stutter_run.py" in s).split())
        self.assertRegex(run, r"tools/ci/offline\.sh --timeout \d+m python3 tools/e2e/stutter_run\.py ")
        self.assertIn('--new-jar "${found[0]}"', run)
        self.assertIn("--gradle-arg=--offline", run)
        self.assertIn("if: always()", next(s for s in steps(job) if "upload-artifact" in s))

    def test_the_evidence_is_uploaded_on_success_and_failure(self):
        upload = next(s for s in steps(self.jobs["e2e"]) if "upload-artifact" in s)
        self.assertIn("if: always()", upload)
        self.assertIn("evidence/", upload)


class BuildWorkflowTest(unittest.TestCase):
    """build.yml's WS-E parts (docs/v0.5/SPEC.md 3a, 3b; AC3a.3, AC3b.1)."""

    def setUp(self):
        self.workflow = text("build.yml")
        self.jobs = jobs(self.workflow)

    def test_every_push_runs_the_e2e_push_tier_on_this_run_s_jars(self):
        e2e = self.jobs["e2e"]
        self.assertIn("needs: java", e2e)
        self.assertIn("uses: ./.github/workflows/e2e.yml", e2e)
        self.assertIn("jars-artifact: rigtune-jars", e2e)
        # review-11 SEC-7: never for a fork's pull request, whatever its branch is called.
        self.assertIn("tier: ${{ github.event_name == 'pull_request' && github.base_ref == 'main' && startsWith(github.head_ref, 'feat/v') "
                      "&& github.event.pull_request.head.repo.full_name == github.repository && 'release' || 'push' }}", e2e)
        self.assertIn("name: rigtune-jars", self.jobs["java"])

    def test_the_java_job_runs_both_compat_harnesses_on_the_pinned_jars(self):
        compat = next(s for s in steps(self.jobs["java"]) if "compat040.py" in s)
        self.assertIn('python3 tools/e2e/compat040.py --old-jar "$old/rigtune-0.4.0+mc26.2.jar"', compat)
        self.assertIn('python3 tools/e2e/compat030.py --old-jar "$old/rigtune-0.3.0+mc26.2.jar"', compat)
        self.assertNotIn("retry", compat)


class ReleaseWorkflowTest(unittest.TestCase):
    def setUp(self):
        self.workflow = text("release.yml")
        self.jobs = jobs(self.workflow)

    def test_build_then_e2e_on_the_staged_jars_then_publish(self):
        self.assertEqual(["build", "e2e", "publish"], list(self.jobs))
        self.assertIn("uses: ./.github/workflows/e2e.yml", self.jobs["e2e"])
        self.assertIn("jars-artifact: release-files", self.jobs["e2e"])
        self.assertIn("needs: build", self.jobs["e2e"])
        self.assertIn("needs: [build, e2e]", self.jobs["publish"])
        self.assertIn("tier: ${{ github.event_name == 'push' && 'release' || inputs.e2e-tier }}", self.jobs["e2e"])

    def test_the_build_job_keeps_the_tag_guard_and_stages_the_files(self):
        build = self.jobs["build"]
        self.assertIn('grep -qxF "mod_version=${TAG#v}" gradle.properties', build)
        self.assertIn("tools/ci/retry.sh ./gradlew --no-daemon prefetchDependencies", build)
        self.assertIn("tools/ci/offline.sh ./gradlew --no-daemon --offline build", build)
        self.assertIn("name: release-files", build)

    def test_publish_uploads_the_downloaded_files_and_never_rebuilds(self):
        publish = self.jobs["publish"]
        self.assertIn("name: release-files", publish)
        self.assertIn("sha256sum -c SHA256SUMS", publish)
        self.assertNotRegex(publish, r"gradlew\s+(build|assemble|jar)\b")
        create = next(s for s in steps(publish) if "gh release create" in s)
        self.assertIn('staged="$RUNNER_TEMP/release-files"', create)
        self.assertIn('"${files[@]}"', create)

    def test_a_dry_run_publishes_nothing(self):
        publish = self.jobs["publish"]
        self.assertIn("DRY_RUN: ${{ github.event_name != 'push' }}", publish)
        create = next(s for s in steps(publish) if "gh release create" in s)
        self.assertLess(create.index('if [ "$DRY_RUN" = true ]'), create.index('gh release create "$TAG"'))
        modrinth = next(s for s in steps(publish) if "Publish to Modrinth" in s)
        self.assertIn("MODRINTH_TOKEN: ${{ github.event_name == 'push' && secrets.MODRINTH_TOKEN || '' }}", modrinth)
        self.assertIn('dry=(--dry-run)', modrinth)
        verify = next(s for s in steps(publish) if "release_verify.py" in s)
        self.assertIn("VERIFY_TAG: ${{ github.event_name == 'push' && needs.build.outputs.tag || inputs.verify-tag }}", verify)

    def test_modrinth_gets_the_staged_files_through_the_stdlib_tool_retried(self):
        # review-11 CI-2: no Gradle in publish (a cold runner resolving plugins and Minecraft files online, after the GitHub
        # release is public, with the token in its configuration); the idempotent upload, retried per node.
        publish = self.jobs["publish"]
        modrinth = next(s for s in steps(publish) if "Publish to Modrinth" in s)
        self.assertNotIn("gradlew", publish)
        self.assertIn('python3 tools/e2e/modrinth_publish.py --staged "$RUNNER_TEMP/release-files" --tag "$TAG"', modrinth)

    def test_publish_s_checkout_keeps_no_git_credentials(self):
        # review-11 SEC-6: nothing in publish pushes; gh takes GITHUB_TOKEN from the environment.
        checkout = next(s for s in steps(self.jobs["publish"]) if "actions/checkout" in s)
        self.assertIn("persist-credentials: false", checkout)

    def test_a_tag_push_without_the_token_fails_before_the_github_release(self):
        # review-11 CI-5: never a GitHub-only release by accident.
        publish = steps(self.jobs["publish"])
        guard = next(i for i, s in enumerate(publish) if "MODRINTH_TOKEN" in s and "exit 1" in s)
        create = next(i for i, s in enumerate(publish) if "gh release create" in s)
        self.assertLess(guard, create)
        self.assertIn("github.event_name == 'push'", publish[guard])

    def test_only_publish_may_write(self):
        self.assertIn("\npermissions:\n  contents: read\n", self.workflow)
        self.assertEqual(["publish"], [job for job, body in self.jobs.items() if "contents: write" in body])

    def test_the_token_stays_out_of_the_build(self):
        self.assertNotIn("MODRINTH_TOKEN", self.jobs["build"])
        self.assertNotIn("secrets.", self.jobs["build"])


if __name__ == "__main__":
    unittest.main()
