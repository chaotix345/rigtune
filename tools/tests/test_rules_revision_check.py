"""A pull request into main that changes the rules raises their revision (review-11 CI-6; tools/rules_revision_check.py)."""

import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import rules_revision_check as check  # noqa: E402

REPO = Path(__file__).resolve().parents[2]


class RevisionCheckTest(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.dir)

    def write(self, name, revision, mods, generated="2026-09-01T00:00:00Z"):
        path = self.dir / name
        path.write_text(json.dumps({"schema": 2, "revision": revision, "generatedAt": generated, "mods": mods}), encoding="utf-8")
        return path

    def test_changed_rules_need_a_higher_revision(self):
        base = self.write("main.json", 17, ["a"])
        self.assertEqual([], check.problems(base, self.write("pr.json", 18, ["a", "b"])))
        self.assertEqual(1, len(check.problems(base, self.write("same-r.json", 17, ["a", "b"]))))
        self.assertEqual(1, len(check.problems(base, self.write("lower.json", 16, ["a", "b"]))))

    def test_unchanged_rules_keep_the_revision(self):
        base = self.write("main.json", 17, ["a"])
        self.assertEqual([], check.problems(base, self.write("pr.json", 17, ["a"], generated="2026-09-20T00:00:00Z")))
        self.assertEqual(1, len(check.problems(base, self.write("bumped.json", 18, ["a"]))))

    def test_the_repository_s_file_passes_against_itself(self):
        rules = REPO / "rules" / "rules-v2.json"
        self.assertEqual([], check.problems(rules, rules))

    def test_the_cli_fails_on_a_problem(self):
        base = self.write("main.json", 17, ["a"])
        self.assertEqual(0, check.main(["--base", str(base), "--head", str(self.write("pr.json", 18, ["b"]))]))
        self.assertEqual(1, check.main(["--base", str(base), "--head", str(self.write("bad.json", 17, ["b"]))]))


class WorkflowTest(unittest.TestCase):
    def test_rules_consistency_checks_a_pull_request_into_main(self):
        build = (REPO / ".github" / "workflows" / "build.yml").read_text(encoding="utf-8")
        job = build.split("\n  rules-consistency:\n", 1)[1].split("\n  rules-v1-compat:", 1)[0]
        self.assertIn("if: github.event_name == 'pull_request' && github.base_ref == 'main'", job)
        self.assertIn("tools/ci/retry.sh git fetch --depth=1 origin main", job)
        self.assertIn('python3 tools/rules_revision_check.py --base "$RUNNER_TEMP/main-rules-v2.json" --head rules/rules-v2.json', job)


if __name__ == "__main__":
    unittest.main()
