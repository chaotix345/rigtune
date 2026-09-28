"""tools/snapshot_canary.py (docs/v0.5/SPEC.md 3f, AC3f.8): snapshot-canary.yml's resolve step, on fixture manifests."""

import io
import json
import os
import shutil
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import snapshot_canary as sc

FIXTURES = Path(__file__).resolve().parent / "fixtures" / "snapshot-canary"


class SnapshotCanaryTests(unittest.TestCase):
    def setUp(self):
        folder = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, folder)
        self.output = folder / "output"
        self.summary = folder / "summary"
        env = mock.patch.dict(os.environ, {"GITHUB_OUTPUT": str(self.output), "GITHUB_STEP_SUMMARY": str(self.summary)})
        env.start()
        self.addCleanup(env.stop)

    def run_main(self, *args):
        out = io.StringIO()
        with redirect_stdout(out):
            sc.main(list(args))
        read = lambda p: p.read_text(encoding="utf-8") if p.exists() else ""
        return out.getvalue(), read(self.output), read(self.summary)

    def test_the_newest_snapshot_being_the_release_is_a_skip(self):
        log, output, summary = self.run_main("--manifest", str(FIXTURES / "snapshot-is-release.json"))
        self.assertEqual("skip=true\n", output)
        self.assertIn("::notice title=Snapshot canary skipped::The newest snapshot is the release (26.3): nothing newer to test.", log)
        self.assertEqual("Skipped: the newest snapshot is the release (26.3).\n", summary)

    def test_a_newer_snapshot_is_tested(self):
        log, output, summary = self.run_main("--manifest", str(FIXTURES / "snapshot-newer.json"))
        self.assertEqual("mc=26.4-snapshot-1\nskip=false\n", output)
        self.assertIn("Testing Minecraft 26.4-snapshot-1", log)
        self.assertEqual("", summary)

    def test_a_given_version_is_trimmed_and_needs_no_manifest(self):
        def no_manifest():
            raise AssertionError("fetched the manifest")
        self.assertEqual(("26.4-snapshot-2", False), sc.resolve("  26.4-snapshot-2\n", no_manifest))
        log, output, _ = self.run_main("--mc", " 26.3 ", "--manifest", str(FIXTURES / "missing.json"))
        self.assertEqual("mc=26.3\nskip=false\n", output)

    def test_a_blank_input_resolves_from_the_manifest(self):
        manifest = json.loads((FIXTURES / "snapshot-newer.json").read_text(encoding="utf-8"))
        self.assertEqual(("26.4-snapshot-1", False), sc.resolve(" \t", lambda: manifest))

    def test_a_manifest_without_latest_fails_the_run(self):
        for bad in ({}, {"latest": {"release": "26.3"}}, {"latest": {"release": "26.3", "snapshot": None}}, []):
            with self.assertRaises(SystemExit):
                sc.resolve("", lambda: bad)

    def test_a_network_error_is_retried_then_fails_the_run(self):
        calls, sleeps = [], []

        def opener(request, timeout):
            calls.append(request.get_header("User-agent"))
            raise OSError("unreachable")
        with self.assertRaises(SystemExit):
            sc.fetch_manifest(opener=opener, sleeper=sleeps.append)
        self.assertEqual([sc.USER_AGENT] * sc.ATTEMPTS, calls)
        self.assertEqual([sc.RETRY_DELAY] * (sc.ATTEMPTS - 1), sleeps)


if __name__ == "__main__":
    unittest.main()
