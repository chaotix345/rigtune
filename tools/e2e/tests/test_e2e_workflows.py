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

    def test_no_automatic_retry(self):
        self.assertNotRegex(self.workflow.lower(), r"retry|retries|max[_-]attempts|continue-on-error")
        self.assertEqual(1, self.workflow.count("tools/e2e/self_update_e2e.py"), "the harness runs once per job")
        self.assertIn("fail-fast: false", self.jobs["e2e"])
        self.assertIn("timeout-minutes: 20", self.jobs["e2e"])

    def test_no_network_but_the_pinned_old_jar(self):
        script = scripts(self.workflow)
        for forbidden in ("curl", "wget", "pip install", "apt-get", "git clone", "http://", "https://", "Invoke-WebRequest"):
            self.assertNotIn(forbidden, script, forbidden)
        download = next(s for s in steps(self.jobs["e2e"]) if "gh release download" in s)
        self.assertRegex(download, r'echo "\$OLD_SHA256  \$RUNNER_TEMP/old/\$OLD_ASSET" \| sha256sum -c -')
        self.assertIn("set -eu", download)

    def test_the_e2e_job_tests_the_caller_s_jars_and_never_builds_them(self):
        e2e = self.jobs["e2e"]
        self.assertIn("actions/download-artifact", e2e)
        self.assertIn("inputs.jars-artifact", e2e)
        self.assertNotRegex(e2e, r"gradlew\s+(build|assemble|jar)\b")
        self.assertIn("if: ${{ !inputs.jars-artifact }}", self.jobs["jars"])

    def test_the_evidence_is_uploaded_on_success_and_failure(self):
        upload = next(s for s in steps(self.jobs["e2e"]) if "upload-artifact" in s)
        self.assertIn("if: always()", upload)
        self.assertIn("evidence/", upload)


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
        self.assertIn("./gradlew build", build)
        self.assertIn("name: release-files", build)

    def test_publish_uploads_the_downloaded_files_and_never_rebuilds(self):
        publish = self.jobs["publish"]
        self.assertIn("name: release-files", publish)
        self.assertIn("sha256sum -c SHA256SUMS", publish)
        self.assertNotRegex(publish, r"gradlew\s+(build|assemble|jar)\b")
        self.assertIn('"-PmodrinthFile=${found[0]}" -x assemble', publish)
        create = next(s for s in steps(publish) if "gh release create" in s)
        self.assertIn('staged="$RUNNER_TEMP/release-files"', create)
        self.assertIn('"${files[@]}"', create)

    def test_a_dry_run_publishes_nothing(self):
        publish = self.jobs["publish"]
        self.assertIn("DRY_RUN: ${{ github.event_name != 'push' }}", publish)
        create = next(s for s in steps(publish) if "gh release create" in s)
        self.assertLess(create.index('if [ "$DRY_RUN" = true ]'), create.index('gh release create "$TAG"'))
        modrinth = next(s for s in steps(publish) if ":modrinth" in s)
        self.assertIn("MODRINTH_TOKEN: ${{ github.event_name == 'push' && secrets.MODRINTH_TOKEN || '' }}", modrinth)
        self.assertIn("dry=(-PmodrinthDryRun)", modrinth)
        verify = next(s for s in steps(publish) if "release_verify.py" in s)
        self.assertIn("VERIFY_TAG: ${{ github.event_name == 'push' && needs.build.outputs.tag || inputs.verify-tag }}", verify)

    def test_only_publish_may_write(self):
        self.assertIn("\npermissions:\n  contents: read\n", self.workflow)
        self.assertEqual(["publish"], [job for job, body in self.jobs.items() if "contents: write" in body])

    def test_the_token_stays_out_of_the_build(self):
        self.assertNotIn("MODRINTH_TOKEN", self.jobs["build"])
        self.assertNotIn("secrets.", self.jobs["build"])


if __name__ == "__main__":
    unittest.main()
