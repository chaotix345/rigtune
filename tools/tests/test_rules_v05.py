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
REPO = Path(__file__).resolve().parent.parent.parent


def sample_knowledge(**sections):
    knowledge = json.loads((FIXTURES / "knowledge_sample.json").read_text(encoding="utf-8"))
    knowledge.update(sections)
    return knowledge


def repo_json(*parts):
    return json.loads(REPO.joinpath(*parts).read_text(encoding="utf-8"))


def repo_content(knowledge):
    """knowledge's full content with the repository's generated data (as check_rules_v1 rebuilds it offline)."""
    v2 = repo_json("rules", "rules-v2.json")
    upstream = {m["slug"]: m["upstream"] for m in v2["mods"]}
    mods = [dict(m, upstream=upstream[m["slug"]]) for m in knowledge["mods"]]
    return ur.assemble_content(knowledge, mods, v2["availability"], v2["upstream"])


def stutter_advice(**fields):
    rule = {"id": "s", "requires": ["stutter-doctor"], "kind": "info", "impact": "low", "title": "T", "text": "t",
            "when": {"stutterTaggedShareAtLeast": {"chunksLoading": 60}}}
    rule.update(fields)
    return rule


class Base(unittest.TestCase):
    def assert_valid(self, knowledge):
        ur.validate_knowledge(knowledge)

    def assert_invalid(self, fragment, knowledge):
        with self.assertRaises(ur.KnowledgeError) as e:
            ur.validate_knowledge(knowledge)
        self.assertIn(fragment, str(e.exception))


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


# L2 (AC2S.2): the "chunks loading" tag, which 0.4.0 already evaluates (Attributor.TAGS), is rules vocabulary inside the
# Stutter Doctor's sections and refused everywhere else.
class ChunksLoadingTagTests(Base):
    TAG = {"stutterTaggedShareAtLeast": {"chunksLoading": 60}}

    def test_accepted_in_stutter_advice(self):
        self.assert_valid(sample_knowledge(stutterAdvice=[stutter_advice()]))
        self.assert_valid(sample_knowledge(stutterAdvice=[stutter_advice(when={"stutterTaggedShareAtLeast": {"chunksLoading": "60"},
                                                                               "spikesPerMinuteAtLeast": 20})]))

    def test_its_share_is_a_whole_percentage(self):
        self.assert_invalid("chunksLoading must be a whole percentage",
                            sample_knowledge(stutterAdvice=[stutter_advice(when={"stutterTaggedShareAtLeast": {"chunksLoading": 101}})]))

    def test_refused_in_main_list_advice(self):
        knowledge = sample_knowledge()
        knowledge["advice"].append({"id": "x", "when": dict(self.TAG), "title": "T", "text": "t", "kind": "info"})
        self.assert_invalid("a Stutter Doctor key, allowed only inside stutterAdvice", knowledge)

    def test_refused_in_a_setting(self):
        knowledge = sample_knowledge()
        knowledge["settings"].append({"key": "vanilla.renderDistance", "max": 8, "when": dict(self.TAG), "requires": ["x"], "v1": False})
        self.assert_invalid("a Stutter Doctor key, allowed only inside stutterAdvice", knowledge)

    def test_refused_in_a_mod_rule(self):
        knowledge = sample_knowledge()
        knowledge["mods"][0]["recommendWhen"] = dict(self.TAG)
        self.assert_invalid("a Stutter Doctor key, allowed only inside stutterAdvice", knowledge)

    def test_refused_in_a_template(self):
        knowledge = sample_knowledge(profileTemplates={"templates": [
            {"id": "battery", "goal": "performance", "settings": [{"key": "vanilla.renderDistance", "max": 8, "when": dict(self.TAG)}]}]})
        self.assert_invalid("a Stutter Doctor key, allowed only inside stutterAdvice", knowledge)

    def test_an_unknown_tag_is_still_refused(self):
        self.assert_invalid("chunkLoading: not one of",
                            sample_knowledge(stutterAdvice=[stutter_advice(when={"stutterTaggedShareAtLeast": {"chunkLoading": 60}})]))


# AC2S.3 (python): the seed is v2-only, so rules-v1.json is the same with and without it.
class ChunksLoadingSeedV1Tests(unittest.TestCase):
    SEED = "stutter-chunks-loading-tag"

    def test_rules_v1_is_identical_with_and_without_the_l2_seed(self):
        knowledge = repo_json("rules", "source", "knowledge.json")
        self.assertIn(self.SEED, [a["id"] for a in knowledge["stutterAdvice"]])
        without = dict(knowledge, stutterAdvice=[a for a in knowledge["stutterAdvice"] if a["id"] != self.SEED])
        with_seed, _ = ur.v1_projection(repo_content(knowledge))
        without_seed, _ = ur.v1_projection(repo_content(without))
        self.assertEqual(with_seed, without_seed)
        self.assertEqual(ur.strip_meta(repo_json("rules", "rules-v1.json")), with_seed)


# The generated files as the release revision R carries them (v0.5 content; docs/v0.5/design/ws-r.md).
class GeneratedV05Tests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.v2 = repo_json("rules", "rules-v2.json")
        cls.v1 = repo_json("rules", "rules-v1.json")
        cls.r16 = repo_json("src", "test", "resources", "rules", "r16", "rules-v2.json")

    def stutter(self, advice_id):
        return next(a for a in self.v2["stutterAdvice"] if a["id"] == advice_id)

    # L4 (AC2R.1): no rule names a hardware type for a combined-tier condition any more; ids such as shaders-entry-level stay.
    def test_no_entry_level_wording(self):
        def texts(node, path=""):
            if isinstance(node, dict):
                for key, value in node.items():
                    if key in ("reason", "avoidReason", "text", "title") and isinstance(value, str):
                        yield f"{path}.{key}", value
                    else:
                        yield from texts(value, f"{path}.{key}")
            elif isinstance(node, list):
                for i, value in enumerate(node):
                    yield from texts(value, f"{path}[{i}]")

        for name, doc in (("knowledge.json", repo_json("rules", "source", "knowledge.json")), ("rules-v2.json", self.v2),
                          ("rules-v1.json", self.v1)):
            found = [where for where, text in texts(doc) if "entry-level" in text.lower()]
            self.assertEqual(found, [], name)
        self.assertIn("shaders-entry-level", [a["id"] for a in self.v2["advice"]])

    # AC2R.1: against r16, the settings are the same entries in the same order and only L4's seven reasons differ, in both
    # files (so keys, values, bounds, conditions and ticks are untouched).
    def test_l4_changed_only_reasons(self):
        r16_v1 = repo_json("src", "test", "resources", "rules", "r16", "rules-v1.json")
        for name, old, new in (("rules-v2.json", self.r16, self.v2), ("rules-v1.json", r16_v1, self.v1)):
            self.assertEqual(len(old["settings"]), len(new["settings"]), name)
            changed = []
            for i, (before, after) in enumerate(zip(old["settings"], new["settings"])):
                self.assertEqual({k: v for k, v in before.items() if k != "reason"}, {k: v for k, v in after.items() if k != "reason"}, f"{name} settings[{i}]")
                if before.get("reason") != after.get("reason"):
                    changed.append(i)
                    self.assertIn("entry-level", before["reason"], f"{name} settings[{i}]")
                    self.assertIn("estimated tier", after["reason"], f"{name} settings[{i}]")
            expected = [i for i, before in enumerate(old["settings"]) if "entry-level" in before.get("reason", "")]
            self.assertEqual(changed, expected, name)
            self.assertEqual(len(expected), 7 if name == "rules-v2.json" else 4, name)

    # L2 (SPEC 2S): one info entry on the tag, calibrated on P5C-F1's re-runs and AC5.8's A control (ChunksLoadingSeedTest).
    def test_the_l2_seed(self):
        seed = self.stutter("stutter-chunks-loading-tag")
        self.assertEqual(seed["requires"], ["stutter-doctor"])
        self.assertEqual((seed["kind"], seed["impact"]), ("info", "low"))
        self.assertEqual(seed["when"], {"stutterTaggedShareAtLeast": {"chunksLoading": 60}, "spikesPerMinuteAtLeast": 20})
        self.assertIn("happened while chunks were loading", seed["text"])
        self.assertIn("may reduce them", seed["text"])
        self.assertNotIn("v1", seed)
        self.assertNotIn("stutterAdvice", self.v1)


if __name__ == "__main__":
    unittest.main()
