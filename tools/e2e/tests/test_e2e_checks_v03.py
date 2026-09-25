"""Checks added for v0.3 (docs/v0.3/plans/ws-h.md): 0.2.x as the old side, the H-M2 seeded variant and the B-M3
per-entry undo."""

import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import e2e_checks  # noqa: E402
from test_e2e_checks import InstanceFixture  # noqa: E402

OWN_UPDATE = "history.json: the old version's own update, both changes APPLIED"


def names(checks):
    return sorted(c.name for c in checks if not c.ok)


class OwnUpdateHistoryTest(unittest.TestCase):
    """0.2.x → new: 0.2.x journals its own update as an apply entry, its helper marks it APPLIED, and the new version
    reads that journal without rewriting it (no legacy import: 0.2.x isn't 0.1.x)."""

    def setUp(self):
        self.fx = InstanceFixture()
        self.entries = [{"id": "e1", "kind": "apply", "rigtuneVersion": "0.2.0+mc26.2", "changes": [
            {"id": "c1", "type": "file", "action": "disable", "modId": "rigtune", "file": self.fx.old.name,
             "resultFile": self.fx.old.name + ".disabled", "status": "APPLIED", "opId": "o1"},
            {"id": "c2", "type": "file", "action": "enable", "modId": "rigtune", "file": self.fx.new.name,
             "status": "APPLIED", "opId": "o2"}]}]
        self.write()
        self.before = e2e_checks.history_statuses(self.fx.instance)

    def write(self):
        (self.fx.rigtune_config / "history.json").write_text(json.dumps({"formatVersion": 1, "entries": self.entries}))

    def check(self):
        return e2e_checks.own_update_history(self.fx.instance, self.fx.old, self.fx.new, self.before)

    def test_passes(self):
        check = self.check()
        self.assertEqual(OWN_UPDATE, check.name)
        self.assertTrue(check.ok, check.detail)

    def test_legacy_import_entry(self):
        self.entries.append({"id": "e2", "kind": "legacy-import", "changes": []})
        self.write()
        self.assertFalse(self.check().ok)

    def test_change_still_staged(self):
        self.entries[0]["changes"][1]["status"] = "STAGED"
        self.write()
        self.before = e2e_checks.history_statuses(self.fx.instance)
        self.assertFalse(self.check().ok)

    def test_wrong_file(self):
        self.entries[0]["changes"][1]["file"] = "rigtune-other.jar"
        self.write()
        self.assertFalse(self.check().ok)

    def test_relaunch_changed_a_status(self):
        self.entries[0]["changes"][0]["status"] = "REVERTED"
        self.write()
        self.assertFalse(self.check().ok)

    def test_no_history(self):
        (self.fx.rigtune_config / "history.json").unlink()
        self.assertFalse(self.check().ok)

    def test_after_verify_runs_it_for_own_update(self):
        mods_before = e2e_checks.listing(self.fx.mods)
        (self.fx.rigtune_config / "rigtune.json").write_text(json.dumps({"goal": "QUALITY", "lastShownApply": "t"}))
        driver = {"ok": True, "rigtuneVersion": "0.2.0-dev+mc26.2", "rigtuneOrigin": [str(self.fx.mods / self.fx.new.name)],
                  "goal": "QUALITY", "reportOnline": True, "updateOffered": False}
        checks = e2e_checks.after_verify(self.fx.instance, self.fx.new, driver, "t", mods_before, "own-update",
                                         old_jar=self.fx.old, statuses_before=self.before)
        self.assertEqual([], names(checks))
        self.assertIn(OWN_UPDATE, [c.name for c in checks])


if __name__ == "__main__":
    unittest.main()
