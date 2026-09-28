"""The launcher-brand leg (docs/v0.5/SPEC.md AC4j.3): Apply everything under the Modrinth App's brand, the helper holds the
staged file group, and the next start's held notice cancels it."""

import json
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import e2e_checks  # noqa: E402
import e2e_matrix  # noqa: E402
import self_update_e2e as su  # noqa: E402

REPO = Path(__file__).resolve().parents[3]
OPS = [{"id": "op-1", "type": "ENABLE_FILE", "from": "x/mods/e2e-seed-1.0.0.jar.rigtune-pending", "to": "x/mods/e2e-seed-1.0.0.jar"}]


def failing(checks):
    return sorted(c.name for c in checks if not c.ok)


class Instance:
    def __init__(self):
        self.root = Path(tempfile.mkdtemp())
        self.config = self.root / "config" / "rigtune"
        self.config.mkdir(parents=True)
        (self.root / "mods").mkdir()
        (self.root / "mods" / "e2e-seed-1.0.0.jar.rigtune-pending").write_bytes(b"jar")
        (self.root / "options.txt").write_text("renderDistance:12\n", encoding="utf-8")

    def write(self, name, data):
        (self.config / name).write_text(json.dumps(data) if not isinstance(data, str) else data, encoding="utf-8")


class AfterBrandApplyTest(unittest.TestCase):
    def setUp(self):
        self.i = Instance()
        self.addCleanup(shutil.rmtree, self.i.root)
        self.before = e2e_checks.listing(self.i.root / "mods", recursive=True)
        self.driver = {"ok": True, "applied": ["set:vanilla.renderDistance"], "applyMessage": "Applied 1 setting(s)."}

    def check(self):
        return e2e_checks.after_brand_apply(self.i.root, self.driver, self.before, OPS, "patch")

    def journal(self, patch_status="APPLIED"):
        self.i.write("history.json", {"formatVersion": 1, "entries": [{"id": "e", "changes": [
            {"id": "c", "type": "setting", "key": "vanilla.renderDistance", "before": "12", "after": "8", "status": "APPLIED"},
            {"id": "patch", "type": "setting", "key": "sodium.performance.chunk_builder_threads", "before": "0", "after": "3",
             "status": patch_status, "opId": "op-p"}]}]})

    def files(self, render_distance):
        (self.i.root / "options.txt").write_text("renderDistance:{}\n".format(render_distance), encoding="utf-8")
        (self.i.root / "config" / "sodium-options.json").write_text(json.dumps({"performance": {"chunk_builder_threads": 3}}), encoding="utf-8")

    def test_settings_applied_files_held(self):
        self.files(8)
        self.journal()
        self.i.write("pending.json", {"ops": OPS})
        self.i.write("helper.log", "[t] [RigTune apply] OK PATCH_JSON: Patched sodium-options.json\n"
                     "[t] [RigTune apply] Held 1 operation(s) of mod-file changes for the player's choice\n[t] [RigTune apply] All operations done\n")
        self.assertEqual([], failing(self.check()))

    def test_a_value_the_files_don_t_hold_or_a_staged_patch_fails(self):
        self.files(12)
        self.journal()
        self.assertIn("the settings changed", failing(self.check()))
        self.files(8)
        self.journal(patch_status="STAGED")
        self.assertIn("the settings changed", failing(self.check()))

    def test_a_renamed_jar_no_patch_or_no_hold_fail(self):
        (self.i.root / "mods" / "e2e-seed-1.0.0.jar.rigtune-pending").rename(self.i.root / "mods" / "e2e-seed-1.0.0.jar")
        self.i.write("helper.log", "[t] [RigTune apply] OK ENABLE_FILE: Enabled e2e-seed-1.0.0.jar\n")
        self.driver = {"ok": True, "applied": [], "applyMessage": "Nothing to apply."}
        self.assertEqual(["mods/ byte-identical", "the helper applied the settings patch", "the settings changed",
                          "the staged file group is held (helper.log, pending.json)"], failing(self.check()))


class AfterBrandCancelTest(unittest.TestCase):
    def setUp(self):
        self.i = Instance()
        self.addCleanup(shutil.rmtree, self.i.root)
        self.before = e2e_checks.listing(self.i.root / "mods", recursive=True)
        self.driver = {"ok": True, "heldNotice": "1 mod change(s) from an earlier Apply are waiting", "heldActions": ["cancel", "apply"],
                       "cancelled": True}

    def history(self, status):
        self.i.write("history.json", {"formatVersion": 1, "entries": [{"id": "e", "changes": [{"id": "c", "opId": "op-1", "status": status}]}]})

    def check(self, cmdlines=()):
        return e2e_checks.after_brand_cancel(self.i.root, self.driver, self.before, OPS, list(cmdlines))

    def test_cancelled_superseded_and_discarded(self):
        (self.i.root / "mods" / "e2e-seed-1.0.0.jar.rigtune-pending").rename(self.i.root / "mods" / "e2e-seed-1.0.0.jar.rigtune-superseded")
        self.history("DISCARDED")
        self.assertEqual([], failing(self.check()))

    def test_a_kept_op_a_kept_download_or_no_notice_fail(self):
        self.i.write("pending.json", {"ops": OPS})
        self.history("STAGED")
        self.driver = {"ok": False, "error": "no held-mod-changes notice"}
        self.assertEqual(["History marks the cancelled changes DISCARDED", "no mod-file op left in pending.json",
                          "the download is superseded and nothing else in mods/ changed", "the held notice is shown with Cancel them"],
                         failing(self.check(["java ApplyHelper"])))


class BrandRowTest(unittest.TestCase):
    def test_one_release_row_on_26_2(self):
        rows = [r for r in e2e_matrix.rows(REPO, "release") if r["id"] == "brand-theseus"]
        self.assertEqual([("26.2", "--scenario brand", "")], [(r["mc"], r["args"], r["old"]) for r in rows])
        self.assertEqual(list(su.BRAND_PHASES), ["brand-apply", "brand-cancel"])


if __name__ == "__main__":
    unittest.main()
