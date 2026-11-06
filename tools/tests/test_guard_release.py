""".claude/hooks/guard_release.py: tag pushes, gh release writes and release.yml dispatches only from RigTune HQ."""

import io
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / ".claude" / "hooks"))

import guard_release as gr


def repo(marker):
    folder = Path(tempfile.mkdtemp())
    subprocess.run(["git", "init", "-q", str(folder)], check=True)
    if marker:
        gitdir = subprocess.run(["git", "-C", str(folder), "rev-parse", "--absolute-git-dir"],
                                capture_output=True, text=True, check=True).stdout.strip()
        (Path(gitdir) / gr.MARKER).write_text("RigTune HQ\n", encoding="utf-8")
    return folder


class ClassifyTests(unittest.TestCase):
    def blocked(self, command):
        return [a for a in (gr.release_action(argv) for argv in gr.commands(command)) if a]

    def test_tag_pushes_are_releases(self):
        for command in ["git push origin v0.5.2", "git push --tags", "git push origin --follow-tags",
                        "git push origin refs/tags/v0.6.0", "git push origin HEAD:refs/tags/v1", "git push origin +v0.5.2",
                        "git push origin :v0.5.2", "git push --delete origin v0.5.2", "git push origin tag v0.5.2",
                        "git push --mirror", "git -C C:/Dev/rigtune push origin v0.5.2", "git.exe push origin v0.5.2",
                        "cd /c/Dev/rigtune && git tag v0.5.2 && git push origin v0.5.2"]:
            with self.subTest(command):
                self.assertTrue(self.blocked(command))

    def test_branch_pushes_and_local_tags_are_not(self):
        for command in ["git push -u origin HEAD", "git push origin fix/issue-35", "git push origin ops/20261106-1200",
                        "git tag v0.5.2", "git push", "git push --force-with-lease origin feat/v0.6",
                        "git log --tags", "git fetch --tags"]:
            with self.subTest(command):
                self.assertFalse(self.blocked(command))

    def test_gh_release_writes_are_releases_and_reads_are_not(self):
        for command in ["gh release create v0.5.2 a.jar", "gh release edit v0.5.1 --notes x", "gh release delete v0.5.1",
                        "gh release upload v0.5.1 a.jar", "gh release delete-asset v0.5.1 a.jar",
                        "MSYS_NO_PATHCONV=1 gh release create v1"]:
            with self.subTest(command):
                self.assertTrue(self.blocked(command))
        for command in ["gh release view v0.5.1", "gh release list", "gh release download v0.5.1 -D out"]:
            with self.subTest(command):
                self.assertFalse(self.blocked(command))

    def test_release_dispatches_are_releases(self):
        for command in ["gh workflow run release.yml", "gh workflow run release -f e2e-tier=push",
                        "gh workflow run .github/workflows/release.yml --ref main",
                        "gh api repos/chaotix345/rigtune/actions/workflows/release.yml/dispatches -f ref=main",
                        "gh api -X POST repos/chaotix345/rigtune/releases -f tag_name=v1",
                        "gh api repos/chaotix345/rigtune/git/refs -f ref=refs/tags/v1 -f sha=abc",
                        "gh api --method PATCH repos/chaotix345/rigtune/releases/123"]:
            with self.subTest(command):
                self.assertTrue(self.blocked(command))
        for command in ["gh workflow run update-rules.yml", "gh workflow run snapshot-canary.yml",
                        "gh api repos/chaotix345/rigtune/releases", "gh api repos/chaotix345/rigtune/git/refs/tags",
                        "gh run list --workflow release.yml", "gh run view 123 --log"]:
            with self.subTest(command):
                self.assertFalse(self.blocked(command))

    def test_text_inside_quotes_is_not_a_command(self):
        for command in ['git commit -m "docs: how HQ runs gh release create and git push origin v0.5.2"',
                        "grep -n 'gh release create' docs/hq.md", 'echo "git push --tags"']:
            with self.subTest(command):
                self.assertFalse(self.blocked(command))

    def test_shell_wrappers_are_looked_inside(self):
        for command in ['bash -c "git push origin v0.5.2"', "pwsh -c 'gh release create v1'",
                        "cmd /c gh release create v1", "env GH_TOKEN=x gh release create v1",
                        "echo ok; gh release create v1", "true && git push --tags", "(git push --tags)"]:
            with self.subTest(command):
                self.assertTrue(self.blocked(command))

    def test_a_release_rerun_is_a_release(self):
        with mock.patch.object(gr, "run_workflow_name", return_value="release"):
            self.assertTrue(self.blocked("gh run rerun 36530247606 --failed"))
        with mock.patch.object(gr, "run_workflow_name", return_value="build"):
            self.assertFalse(self.blocked("gh run rerun 36530247606 --failed"))
        self.assertTrue(self.blocked("gh run rerun"))


class HookTests(unittest.TestCase):
    def run_hook(self, command, cwd, tool="Bash"):
        out = io.StringIO()
        payload = json.dumps({"tool_name": tool, "tool_input": {"command": command}, "cwd": str(cwd)})
        with mock.patch.object(sys, "stdin", io.StringIO(payload)), redirect_stdout(out):
            self.assertEqual(0, gr.main())
        return json.loads(out.getvalue()) if out.getvalue() else None

    def checkout(self, marker):
        folder = repo(marker)
        self.addCleanup(shutil.rmtree, folder, ignore_errors=True)
        return folder

    def test_a_worker_checkout_is_denied(self):
        result = self.run_hook("git push origin v0.5.2", self.checkout(marker=False))
        self.assertEqual("deny", result["hookSpecificOutput"]["permissionDecision"])
        self.assertIn("only RigTune HQ releases", result["hookSpecificOutput"]["permissionDecisionReason"])

    def test_powershell_is_checked_too(self):
        result = self.run_hook("gh release create v1", self.checkout(marker=False), tool="PowerShell")
        self.assertEqual("deny", result["hookSpecificOutput"]["permissionDecision"])

    def test_the_hq_checkout_is_allowed(self):
        self.assertIsNone(self.run_hook("git push origin v0.5.2", self.checkout(marker=True)))

    def test_ordinary_commands_pass_anywhere(self):
        self.assertIsNone(self.run_hook("git push -u origin HEAD", self.checkout(marker=False)))
        self.assertIsNone(self.run_hook("./gradlew build", Path(tempfile.gettempdir())))

    def test_outside_any_repo_is_denied(self):
        folder = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, folder, ignore_errors=True)
        result = self.run_hook("gh release create v1", folder)
        self.assertEqual("deny", result["hookSpecificOutput"]["permissionDecision"])

    def test_a_broken_payload_fails_closed(self):
        out = io.StringIO()
        with mock.patch.object(sys, "stdin", io.StringIO("not json")), redirect_stdout(out):
            gr.main()
        self.assertEqual("deny", json.loads(out.getvalue())["hookSpecificOutput"]["permissionDecision"])

    def test_a_failed_rerun_lookup_fails_closed(self):
        with mock.patch.object(gr, "run_workflow_name", side_effect=RuntimeError("offline")):
            result = self.run_hook("gh run rerun 1", self.checkout(marker=False))
        self.assertEqual("deny", result["hookSpecificOutput"]["permissionDecision"])


if __name__ == "__main__":
    unittest.main()
