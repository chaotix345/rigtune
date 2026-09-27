"""v0.5 rules work (docs/v0.5/SPEC.md 2S L2, 2R L4, 5 C20's rules side, 4i, 2T; docs/v0.5/design/ws-r.md)."""

import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import update_rules as ur

FIXTURES = Path(__file__).resolve().parent / "fixtures"


def v2_doc(revision, mods=()):
    return {"schemaVersion": 2, "revision": revision, "generatedAt": "2026-09-01T00:00:00Z", "mods": list(mods)}


def v1_doc(revision, mods=()):
    return {"schemaVersion": 1, "revision": revision, "generatedAt": "2026-09-01T00:00:00Z", "mods": list(mods)}


# One release revision R for every regeneration of a release (docs/v0.5/SPEC.md "Compatibility promise"): --revision pins
# it; without the option the updater bumps as before (the weekly bot).
class RevisionPinTests(unittest.TestCase):
    def changed(self, old_revision, revision):
        return ur.finalize_documents([({"schemaVersion": 2, "mods": [{"slug": "new"}]}, v2_doc(old_revision)),
                                      ({"schemaVersion": 1, "mods": [{"slug": "new"}]}, v1_doc(old_revision))], revision=revision)

    def test_a_pinned_revision_is_written_to_both_files(self):
        finals = self.changed(16, 17)
        self.assertEqual([f["revision"] for f in finals], [17, 17])
        self.assertEqual(finals[0]["generatedAt"], finals[1]["generatedAt"])

    def test_regenerating_inside_the_same_revision_keeps_it(self):
        self.assertEqual([f["revision"] for f in self.changed(17, 17)], [17, 17])

    def test_a_pinned_revision_may_skip_ahead(self):
        self.assertEqual([f["revision"] for f in self.changed(16, 18)], [18, 18])

    def test_a_revision_never_goes_down(self):
        with self.assertRaises(ur.UpdateRulesError) as e:
            self.changed(17, 16)
        self.assertIn("below", str(e.exception))
        with self.assertRaises(ur.UpdateRulesError):
            ur.finalize_documents([({"schemaVersion": 2, "mods": []}, None)], revision=0)

    def test_the_highest_old_revision_counts(self):
        with self.assertRaises(ur.UpdateRulesError):
            ur.finalize_documents([({"schemaVersion": 2, "mods": [{"slug": "new"}]}, v2_doc(17)),
                                   ({"schemaVersion": 1, "mods": [{"slug": "new"}]}, v1_doc(16))], revision=16)

    def test_unchanged_content_writes_nothing_even_when_pinned(self):
        old = v2_doc(16, [{"slug": "a"}])
        self.assertIsNone(ur.finalize_documents([(ur.strip_meta(old), old)], revision=17))

    def test_without_the_option_the_bot_bumps_as_before(self):
        self.assertEqual([f["revision"] for f in self.changed(16, None)], [17, 17])

    def test_a_pinned_revision_stays_under_the_client_limit(self):
        with self.assertRaises(ur.UpdateRulesError):
            self.changed(16, ur.MAX_SAFE_REVISION)

    def test_main_writes_the_pinned_revision(self):
        knowledge = json.loads((FIXTURES / "knowledge_sample.json").read_text(encoding="utf-8"))
        content = ur.assemble_content(knowledge, knowledge["mods"], {}, {})

        def fake_pipeline(knowledge, client, override, old_doc, nodes=None):
            return content, "# review\n", {}, ["26.2"], {"fabulouslyOptimized": None, "additive": None}, set(), set()

        with tempfile.TemporaryDirectory() as tmp, mock.patch.object(ur, "run_pipeline", fake_pipeline):
            rules = Path(tmp) / "rules"
            rules.mkdir()
            (rules / "rules-v2.json").write_text(json.dumps(v2_doc(16)), encoding="utf-8")
            (rules / "rules-v1.json").write_text(json.dumps(v1_doc(16)), encoding="utf-8")
            argv = ["--knowledge", str(FIXTURES / "knowledge_sample.json"), "--out-dir", tmp, "--mc-versions", "26.2"]
            self.assertEqual(ur.main(argv + ["--revision", "18"]), 0)
            written = [json.loads((rules / name).read_text(encoding="utf-8"))["revision"] for name in ("rules-v2.json", "rules-v1.json")]
            bundled = json.loads((Path(tmp) / "src" / "main" / "resources" / "rigtune" / "rules-v2.json").read_text(encoding="utf-8"))
            self.assertEqual(written + [bundled["revision"]], [18, 18, 18])
            self.assertEqual(ur.main(argv + ["--revision", "17"]), 1, "below the files' revision")
            self.assertEqual(json.loads((rules / "rules-v2.json").read_text(encoding="utf-8"))["revision"], 18)


if __name__ == "__main__":
    unittest.main()
