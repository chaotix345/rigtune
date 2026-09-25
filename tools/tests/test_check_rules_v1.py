import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import check_rules_v1 as check
import update_rules as ur

FIXTURES = Path(__file__).resolve().parent / "fixtures"


def load_fixture(name):
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


class CheckRulesV1Tests(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        knowledge = load_fixture("knowledge_sample.json")
        knowledge["advice"].append({"id": "big-screen", "when": {"displayPixelsAtLeast": 3686400}, "title": "T", "text": "t", "kind": "info"})
        knowledge["settingLabels"] = {"vanilla.renderDistance": {"name": "Render distance"}}
        self.write_knowledge(knowledge)
        self.generate(knowledge)

    def write_knowledge(self, knowledge):
        path = self.tmp / "rules" / "source" / "knowledge.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(knowledge), encoding="utf-8")

    def generate(self, knowledge):
        mods = ur.mods_with_upstream(knowledge["mods"], {"sodium"}, set())
        content = ur.assemble_content(knowledge, mods, {"26.2": ["sodium"]},
                                      ur.top_level_upstream("26.2", {"sodium"}, None, set()))
        v1, _ = ur.v1_projection(content)
        final_v2, final_v1 = ur.finalize_documents([(ur.v2_content(content), None), (v1, {"revision": 4})])
        ur.write_json(self.tmp / "rules" / "rules-v2.json", final_v2)
        ur.write_json(self.tmp / "rules" / "rules-v1.json", final_v1)

    def edit(self, name, change):
        path = self.tmp / "rules" / name
        doc = json.loads(path.read_text(encoding="utf-8"))
        change(doc)
        path.write_text(json.dumps(doc), encoding="utf-8")

    def assert_problem(self, fragment):
        problems = check.check(self.tmp)
        self.assertTrue(any(fragment in p for p in problems), problems)

    def test_consistent_pair_passes(self):
        self.assertEqual(check.check(self.tmp), [])
        self.assertEqual(check.main(["--repo-root", str(self.tmp)]), 0)

    def test_v2_condition_key_in_v1_fails(self):
        self.edit("rules-v1.json", lambda d: d["advice"][0]["when"].update({"not": {"gpuModelMatches": "rtx"}}))
        self.assert_problem("gpuModelMatches")
        self.assertEqual(check.main(["--repo-root", str(self.tmp)]), 1)

    def test_requires_in_v1_fails(self):
        self.edit("rules-v1.json", lambda d: d["mods"][0].update({"requires": ["x"]}))
        self.assert_problem("requires")

    def test_setting_labels_in_v1_fails(self):
        self.edit("rules-v1.json", lambda d: d.update({"settingLabels": {}}))
        self.assert_problem("settingLabels")

    def test_unknown_flag_in_v1_fails(self):
        self.edit("rules-v1.json", lambda d: d["advice"][0]["when"].update({"flags": ["mesh-shaders"]}))
        self.assert_problem("mesh-shaders")

    def test_dh_key_in_v1_fails(self):
        self.edit("rules-v1.json", lambda d: d["settings"].append({"key": "dh.x", "value": 1, "reason": "r"}))
        self.assert_problem("dh.x")

    def test_hand_edited_v1_fails_the_projection_check(self):
        self.edit("rules-v1.json", lambda d: d["settings"][0].update({"value": 32}))
        self.assert_problem("projection")

    def test_different_revisions_fail(self):
        self.edit("rules-v1.json", lambda d: d.update({"revision": d["revision"] + 1}))
        self.assert_problem("revision")

    def test_stale_v2_fails(self):
        knowledge = json.loads((self.tmp / "rules" / "source" / "knowledge.json").read_text(encoding="utf-8"))
        knowledge["mods"][0]["reason"] = "Edited without regenerating."
        self.write_knowledge(knowledge)
        self.assert_problem("rules-v2.json")

    def test_wrong_schema_version_fails(self):
        self.edit("rules-v1.json", lambda d: d.update({"schemaVersion": 2}))
        self.assert_problem("schemaVersion")

    def test_invalid_knowledge_fails(self):
        knowledge = json.loads((self.tmp / "rules" / "source" / "knowledge.json").read_text(encoding="utf-8"))
        knowledge["advice"][0]["when"] = {"meshShaders": True}
        self.write_knowledge(knowledge)
        self.assert_problem("meshShaders")

    def test_missing_file_fails(self):
        (self.tmp / "rules" / "rules-v2.json").unlink()
        self.assert_problem("rules-v2.json")


if __name__ == "__main__":
    unittest.main()
