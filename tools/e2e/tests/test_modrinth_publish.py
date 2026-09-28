"""release.yml's Modrinth publish (review-11 CI-2): the staged file through tools/modrinth_project.py's idempotent
upload-version, retried, with the payload Minotaur's `modrinth {}` block sent (build.gradle), and no Gradle."""

import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import modrinth_publish  # noqa: E402

CHANGELOG = """# Changelog

## [Unreleased]
- next

## [0.5.0] - 2026-10-01
- Measured Try It.
- The DH note.

## [0.4.0] - 2026-09-20
- older
"""


class PublishArgsTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.root)
        self.staged = self.root / "release-files"
        self.staged.mkdir()
        for name in ("rigtune-0.5.0+mc26.2.jar", "rigtune-0.5.0+mc26.2-sources.jar", "rigtune-0.5.0+mc26.3-pre1.jar"):
            (self.staged / name).write_bytes(name.encode())
        (self.staged / "SHA256SUMS").write_text("aa11  rigtune-0.5.0+mc26.2.jar\nbb22  rigtune-0.5.0+mc26.2-sources.jar\n"
                                                "cc33 *rigtune-0.5.0+mc26.3-pre1.jar\n", encoding="utf-8")
        (self.root / "CHANGELOG.md").write_text(CHANGELOG, encoding="utf-8")

    def test_minotaur_s_payload_from_the_staged_file(self):
        args = modrinth_publish.upload_args(self.staged, "v0.5.0", "26.2", self.root / "notes.md")
        self.assertEqual(["upload-version", "--file", str(self.staged / "rigtune-0.5.0+mc26.2.jar"), "--version-number", "0.5.0+mc26.2",
                          "--name", "RigTune 0.5.0 (MC 26.2)", "--game-versions", "26.2", "--loaders", "fabric",
                          "--version-type", "release", "--changelog-file", str(self.root / "notes.md"), "--sha256", "aa11"], args)

    def test_a_node_that_isn_t_a_plain_release_id_is_an_alpha(self):
        args = modrinth_publish.upload_args(self.staged, "v0.5.0", "26.3-pre1", self.root / "notes.md")
        self.assertEqual("alpha", args[args.index("--version-type") + 1])
        self.assertEqual("cc33", args[args.index("--sha256") + 1])

    def test_the_file_and_its_digest_must_be_staged_exactly_once(self):
        with self.assertRaises(modrinth_publish.PublishError):
            modrinth_publish.upload_args(self.staged, "v0.5.0", "26.9", self.root / "notes.md")
        (self.staged / "SHA256SUMS").write_text("bb22  rigtune-0.5.0+mc26.2-sources.jar\n", encoding="utf-8")
        with self.assertRaises(modrinth_publish.PublishError):
            modrinth_publish.upload_args(self.staged, "v0.5.0", "26.2", self.root / "notes.md")

    def test_the_changelog_is_the_version_s_section_as_build_gradle_reads_it(self):
        self.assertEqual("- Measured Try It.\n- The DH note.", modrinth_publish.changelog(self.root / "CHANGELOG.md", "0.5.0"))
        self.assertEqual("- Measured Try It.\n- The DH note.", modrinth_publish.changelog(self.root / "CHANGELOG.md", "0.5.0-rc1"))
        self.assertEqual("", modrinth_publish.changelog(self.root / "CHANGELOG.md", "0.9.0"))

    def test_each_node_is_retried_and_a_dry_run_says_so(self):
        seen = []
        status = modrinth_publish.publish(self.staged, "v0.5.0", ["26.2"], self.root / "CHANGELOG.md", self.root / "work", dry_run=True,
                                          run=lambda command: seen.append(command) or 0)
        self.assertEqual(0, status)
        self.assertEqual(["tools/ci/retry.sh", "python3", "tools/modrinth_project.py"], seen[0][:3])
        self.assertEqual("--dry-run", seen[0][-1])

    def test_a_failed_node_doesn_t_stop_the_others_and_fails_the_step(self):
        seen = []
        status = modrinth_publish.publish(self.staged, "v0.5.0", ["26.9", "26.2"], self.root / "CHANGELOG.md", self.root / "work",
                                          dry_run=False, run=lambda command: seen.append(command) or 0)
        self.assertEqual(1, status)
        self.assertEqual(1, len(seen))
        self.assertNotIn("--dry-run", seen[0])


    def test_the_preflight_reads_every_node_before_anything_is_public(self):
        # Review-12 R12REL-1/7: read-only, with the token, in a dry run too (no --dry-run passed on).
        seen = []
        status = modrinth_publish.publish(self.staged, "v0.5.0", ["26.2"], self.root / "CHANGELOG.md", self.root / "work", dry_run=True,
                                          preflight=True, run=lambda command: seen.append(command) or 0)
        self.assertEqual(0, status)
        self.assertEqual(["tools/ci/retry.sh", "python3", "tools/modrinth_project.py", "preflight", "--file",
                          str(self.staged / "rigtune-0.5.0+mc26.2.jar"), "--version-number", "0.5.0+mc26.2", "--sha256", "aa11"], seen[0])

    def test_a_missing_or_oversized_changelog_fails_the_preflight_and_warns_in_a_dry_run(self):
        # Review-12 R12REL-3.
        for tag, text in (("v0.9.0", CHANGELOG), ("v0.5.0", CHANGELOG.replace("- The DH note.", "x" * (modrinth_publish.MAX_CHANGELOG + 1)))):
            (self.root / "CHANGELOG.md").write_text(text, encoding="utf-8")
            args = (self.staged, tag, [], self.root / "CHANGELOG.md", self.root / "work")
            self.assertEqual(1, modrinth_publish.publish(*args, dry_run=False, preflight=True, run=lambda command: 0))
            self.assertEqual(0, modrinth_publish.publish(*args, dry_run=True, preflight=True, run=lambda command: 0))


if __name__ == "__main__":
    unittest.main()
