"""The released-0.4.0 compatibility harness's runner (compat040.py; docs/v0.5/SPEC.md 3b, AC3b.1), and compat030's
fixture roots (AC3b.2)."""

import re
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import compat030  # noqa: E402
import compat040  # noqa: E402
import self_update_e2e  # noqa: E402

REPO = Path(__file__).resolve().parents[3]
JAVA_CHECKS = REPO / "tools" / "e2e" / "compat" / "Compat040.java"


def fake_jar(path, version):
    path = Path(path)
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("fabric.mod.json", '{"schemaVersion": 1, "id": "rigtune", "version": "%s"}' % version)
    return path


class GateTest(unittest.TestCase):
    def test_only_the_released_0_4_0_jar_is_accepted(self):
        tmp = Path(tempfile.mkdtemp())
        with self.assertRaises(SystemExit) as wrong_bytes:
            compat040.check_old_jar(fake_jar(tmp / "rigtune-0.4.0+mc26.2.jar", "0.4.0+mc26.2"))
        self.assertIn(self_update_e2e.RELEASED["0.4.0+mc26.2"][1], str(wrong_bytes.exception))
        with self.assertRaises(SystemExit):
            compat040.check_old_jar(fake_jar(tmp / "rigtune-0.3.0+mc26.2.jar", "0.3.0+mc26.2"))

    def test_the_pin_is_the_github_release_digest(self):
        self.assertEqual(("rigtune-0.4.0+mc26.2.jar", "801cd3b8e91c6a27b819d64c5b433bea6d7ac99d4776cb853c873a9cafdd868a"),
                         self_update_e2e.RELEASED[compat040.OLD_VERSION])


class ChecksTest(unittest.TestCase):
    def test_every_expected_check_must_be_reported(self):
        lines = [(True, name, "") for name in compat040.EXPECTED[:-1]]
        self.assertEqual([compat040.EXPECTED[-1]], compat040.missing(lines))
        self.assertEqual([], compat040.missing(lines + [(True, compat040.EXPECTED[-1], "")]))

    def test_the_java_program_reports_exactly_the_expected_checks(self):
        source = JAVA_CHECKS.read_text(encoding="utf-8")
        names = set(re.findall(r'check\("([^"]+)"', source))
        self.assertEqual(set(compat040.EXPECTED), names)

    def test_the_program_s_scratch_folder_is_outside_the_instance(self):
        work = Path(tempfile.mkdtemp())
        instance, scratch = compat040.folders(work)
        self.assertFalse(str(scratch.resolve()).startswith(str(instance.resolve())))


class RootsTest(unittest.TestCase):
    def test_both_harnesses_default_to_every_generation(self):
        default = [str(p) for p in self_update_e2e.default_written(REPO)]
        self.assertEqual(default, compat040.parse_args(["--old-jar", "x.jar"]).written)
        self.assertEqual(default, compat030.parse_args(["--old-jar", "x.jar"]).written)

    def test_written_may_repeat(self):
        self.assertEqual(["a", "b"], compat040.parse_args(["--old-jar", "x.jar", "--written", "a", "--written", "b"]).written)
        self.assertEqual(["a", "b"], compat030.parse_args(["--old-jar", "x.jar", "--written", "a", "--written", "b"]).written)


if __name__ == "__main__":
    unittest.main()
