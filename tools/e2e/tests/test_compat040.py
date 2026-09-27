"""The released-0.4.0 compatibility harness's runner (compat040.py; docs/v0.5/SPEC.md 3b, AC3b.1), and compat030's
fixture roots (AC3b.2)."""

import json
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
import written  # noqa: E402

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


def write(folder, name, data):
    folder.mkdir(parents=True, exist_ok=True)
    (folder / name).write_text(json.dumps(data), encoding="utf-8")


PENDING = {"createdAt": "2026-09-20T10:00:05Z", "gamePid": 4242, "modsDir": "${INSTANCE}/mods", "configDir": "${INSTANCE}/config", "ops": [
    {"type": "DISABLE_FILE", "path": "${INSTANCE}/mods/e2e-held-1.0.0.jar", "id": "o1", "group": "g1", "modId": "e2e-held", "attempts": 0},
    {"type": "ENABLE_FILE", "from": "${INSTANCE}/mods/e2e-held-1.1.0.jar.rigtune-pending", "to": "${INSTANCE}/mods/e2e-held-1.1.0.jar",
     "id": "o2", "group": "g1", "modId": "e2e-held", "attempts": 0},
    {"type": "PATCH_JSON", "path": "${INSTANCE}/config/sodium-options.json", "patches": {"performance.chunk_builder_threads": "2"},
     "id": "o3", "group": "g2", "attempts": 0}]}


class PerSetTest(unittest.TestCase):
    """Each set with an expect.json is composed alone, twice (the read-only instance and a spare the writing checks use)."""

    def setUp(self):
        self.work = Path(tempfile.mkdtemp())
        self.v5 = self.work / "fixtures" / "v050-written"
        write(self.v5 / "ws-l2", "pending.json", PENDING)
        write(self.v5 / "ws-l2", "expect.json", {"set": "ws-l2", "checks": [{"class": "PendingActions", "file": "pending.json", "ops": 3}]})
        write(self.v5 / "placeholder" / "ws-f", "awareness.json", {"formatVersion": 1, "dismissed": ["firstrun.guide"]})
        write(self.v5 / "placeholder" / "ws-f", "expect.json", {"set": "ws-f", "checks": [{"class": "AwarenessStore", "file": "awareness.json", "state": "OK"}]})
        write(self.v5 / "ws-b", "benchmarks.json", {"schemaVersion": 1, "runs": []})
        self.sets = written.resolve_all([self.v5])

    def test_every_set_with_expectations_gets_its_own_instance_and_a_spare(self):
        per_set, missing = compat040.set_instances(self.sets, self.work / "sets")
        self.assertEqual(["ws-l2", "ws-f"], [name for name, *_ in per_set])
        self.assertEqual(["ws-b"], missing)
        name, config, expect, spare = per_set[0]
        self.assertEqual(["pending.json"], sorted(p.name for p in (config / "rigtune").iterdir()))
        self.assertEqual(self.v5 / "ws-l2" / "expect.json", expect)
        self.assertNotEqual(config, spare)
        spare_pending = json.loads((spare / "rigtune" / "pending.json").read_text(encoding="utf-8"))
        self.assertTrue(spare_pending["ops"][0]["path"].startswith(str(spare.parent)), "the spare's paths point into the spare")

    def test_the_files_a_set_s_pending_ops_touch_exist(self):
        instance = self.work / "one"
        written.compose([s for s in self.sets if s.name == "ws-l2"], instance)
        written.materialize(instance)
        mods = instance / "mods"
        self.assertTrue((mods / "e2e-held-1.0.0.jar").is_file())
        self.assertTrue((mods / "e2e-held-1.1.0.jar.rigtune-pending").is_file())
        self.assertEqual({}, json.loads((instance / "config" / "sodium-options.json").read_text(encoding="utf-8")))
        with zipfile.ZipFile(mods / "e2e-held-1.1.0.jar.rigtune-pending") as jar:
            self.assertEqual("e2e-held", json.loads(jar.read("fabric.mod.json"))["id"])

    def test_the_interpreter_knows_the_readme_s_check_kinds(self):
        readme = (REPO / "src" / "test" / "resources" / "v050-written" / "README.md").read_text(encoding="utf-8")
        documented = set(re.findall(r"\b([A-Z][A-Za-z]+)\b", readme.split("- `class`:", 1)[1].split("\n- `file`", 1)[0]))
        source = JAVA_CHECKS.read_text(encoding="utf-8")
        known = set(re.findall(r'"([A-Z][A-Za-z]+)"', source.split("Set<String> CLASSES = Set.of(", 1)[1].split(");", 1)[0]))
        self.assertEqual(known, documented & known)
        self.assertLessEqual({"Journal", "HistoryModel", "UndoPlanner", "BenchmarkHistory", "PendingActions", "ApplyHelper", "ClientSettings",
                              "StutterStore", "AwarenessStore", "ProfileStore", "ServerLimitsStore", "RestoreMarker", "Unread"}, known)


if __name__ == "__main__":
    unittest.main()
