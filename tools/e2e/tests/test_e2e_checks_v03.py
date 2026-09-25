"""Checks added for v0.3 (docs/v0.3/plans/ws-h.md): 0.2.x as the old side, the H-M2 seeded variant and the B-M3
per-entry undo."""

import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import e2e_checks  # noqa: E402
import e2e_env  # noqa: E402
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


DH_OLD = "fabric-26.2.jar"
DH_NEW = "DistantHorizons-3.3.2-26.2-fabric-neoforge.jar"
DH_QUEUED = "update/DistantHorizons-3.3.2 - 26.2 neo/fabric-26.2.jar"
DH_NAMES = ("distanthorizons", "Distant Horizons")
NOTICE = "rigtune.status.queued_update_dropped"


class SeededFixture:
    """H-M2: the user's 0.1.0 state (a DH update group whose disable failed, DH's own build queued in mods/update/) after
    the old version's self-update, whose helper run failed the DH group again (DH's updater held the jar)."""

    def __init__(self):
        self.fx = InstanceFixture()
        self.instance, self.mods, self.config = self.fx.instance, self.fx.mods, self.fx.rigtune_config
        for rel, version in ((DH_OLD, "3.3.0"), (DH_NEW + ".rigtune-pending", "3.3.2"), (DH_QUEUED, "3.3.2")):
            (self.mods / rel).parent.mkdir(parents=True, exist_ok=True)
            e2e_env.test_mod_jar(self.mods / rel, "distanthorizons", version, name="Distant Horizons")
        self.carried = [
            {"type": "DISABLE_FILE", "path": str(self.mods / DH_OLD), "id": "d1", "group": "g", "attempts": 1},
            {"type": "ENABLE_FILE", "from": str(self.mods / (DH_NEW + ".rigtune-pending")), "to": str(self.mods / DH_NEW),
             "id": "d2", "group": "g", "modId": "distanthorizons", "attempts": 1}]
        self.pending([dict(op, attempts=2) for op in self.carried])
        last = json.loads((self.config / "last-apply.json").read_text())
        self.failed = [dict(op) for op in self.carried]
        last["results"] += [{"op": op, "status": "FAILED", "message": "busy"} for op in self.failed]
        (self.config / "last-apply.json").write_text(json.dumps(last))
        self.mods_before = e2e_checks.listing(self.mods, recursive=True)
        self.driver = None
        self.log = ""
        self.helper_cmdlines = []

    def pending(self, ops):
        (self.config / "pending.json").write_text(json.dumps({"modsDir": str(self.mods), "ops": ops}))

    def after_update(self):
        return e2e_checks.after_update(self.instance, self.fx.old, self.fx.new, self.fx.driver, self.fx.server_log,
                                       self.fx.helper_cmdlines, carried=self.carried)

    def first_new_launch(self):
        """As 0.3.0 leaves the instance: the group dropped (download retired), journaled DISCARDED, WARN lines logged."""
        (self.config / "pending.json").unlink()
        (self.mods / (DH_NEW + ".rigtune-pending")).rename(self.mods / (DH_NEW + ".rigtune-superseded"))
        self.history = {"formatVersion": 1, "entries": [{"id": "e1", "kind": "legacy-import", "changes": [
            {"id": "c1", "type": "file", "action": "disable", "file": DH_OLD, "status": "DISCARDED", "opId": "d1"},
            {"id": "c2", "type": "file", "action": "enable", "modId": "distanthorizons", "file": DH_NEW,
             "status": "DISCARDED", "opId": "d2"}]}]}
        self.write_history()
        self.driver = {"ok": True, "statuses": [
            {"key": "rigtune.status.ready", "text": "Ready"},
            {"key": NOTICE, "text": "Cancelled RigTune's pending change of Distant Horizons: it has an update of its own "
                                   "waiting in mods/update."}],
            "modsAtQuit": e2e_checks.listing(self.mods, recursive=True)}
        self.log = "\n".join([
            "[10:00:01] [Render thread/INFO] (RigTune) Loaded rules r11",
            "[10:00:01] [main/WARN] (RigTune) The last apply failed: DISABLE_FILE fabric-26.2.jar: busy (attempt 2 of 3)",
            "[10:00:01] [main/WARN] (RigTune) The last apply failed: ENABLE_FILE distanthorizons: busy (attempt 2 of 3)"])

    def write_history(self):
        (self.config / "history.json").write_text(json.dumps(self.history))

    def seeded_verify(self):
        return e2e_checks.after_seeded_verify(self.instance, self.carried, DH_NAMES, self.driver, self.log, self.failed,
                                              self.helper_cmdlines, self.mods_before)


class SeededAfterUpdateTest(unittest.TestCase):
    def setUp(self):
        self.sx = SeededFixture()

    def test_passes_with_the_carried_group(self):
        self.assertEqual([], names(self.sx.after_update()))

    def test_group_gone(self):
        self.sx.pending([])
        self.assertEqual(["pending.json holds only the carried-over group, one attempt more"], names(self.sx.after_update()))

    def test_attempts_not_counted(self):
        self.sx.pending(self.sx.carried)
        self.assertEqual(["pending.json holds only the carried-over group, one attempt more"], names(self.sx.after_update()))

    def test_the_group_applied_after_all(self):
        last = json.loads((self.sx.config / "last-apply.json").read_text())
        for r in last["results"][2:]:
            r["status"] = "OK"
        (self.sx.config / "last-apply.json").write_text(json.dumps(last))
        self.assertEqual(["last-apply.json: the update's two ops OK, the carried-over ops FAILED"], names(self.sx.after_update()))

    def test_another_leftover_download(self):
        (self.sx.mods / "other.jar.rigtune-pending").write_bytes(b"x")
        self.assertEqual(["no leftover downloads but the carried-over group's"], names(self.sx.after_update()))


class SeededVerifyTest(unittest.TestCase):
    def setUp(self):
        self.sx = SeededFixture()
        self.sx.first_new_launch()

    def failing(self):
        return names(self.sx.seeded_verify())

    def test_passes(self):
        checks = self.sx.seeded_verify()
        self.assertEqual([], [c.name + ": " + c.detail for c in checks if not c.ok])
        self.assertEqual(7, len(checks))

    def test_group_still_pending(self):
        self.sx.pending([dict(op, attempts=2) for op in self.sx.carried])
        self.assertEqual(["the carried-over group is dropped"], self.failing())

    def test_no_notice(self):
        self.sx.driver["statuses"] = self.sx.driver["statuses"][:1]
        self.assertEqual(["the drop is announced (status notice)"], self.failing())

    def test_notice_for_another_mod(self):
        self.sx.driver["statuses"][1]["text"] = "Cancelled RigTune's pending change of Sodium: ..."
        self.assertEqual(["the drop is announced (status notice)"], self.failing())

    def test_journal_left_staged_or_missing(self):
        self.sx.history["entries"][0]["changes"][1]["status"] = "STAGED"
        self.sx.write_history()
        self.assertEqual(["history.json: the carried-over changes are DISCARDED"], self.failing())
        self.sx.history["entries"][0]["changes"] = self.sx.history["entries"][0]["changes"][:1]
        self.sx.write_history()
        self.assertEqual(["history.json: the carried-over changes are DISCARDED"], self.failing())

    def test_warn_line_missing_for_one_op(self):
        self.sx.log = self.sx.log.rsplit("\n", 1)[0]
        self.assertEqual(["latest.log: a WARN line per failed op, with its attempt (3e)"], self.failing())

    def test_info_line_does_not_count(self):
        self.sx.log = self.sx.log.replace("[main/WARN]", "[main/INFO]")
        self.assertEqual(["latest.log: a WARN line per failed op, with its attempt (3e)"], self.failing())

    def test_helper_ran_at_exit(self):
        self.sx.helper_cmdlines = ["java -cp x io.github.chaotix345.rigtune.core.apply.ApplyHelper 1 p"]
        self.assertEqual(["nothing in mods/ changes at exit"], self.failing())

    def test_mods_changed_after_the_driver_quit(self):
        (self.sx.mods / DH_OLD).rename(self.sx.mods / (DH_OLD + ".disabled"))
        self.assertEqual(["nothing in mods/ changes at exit",
                          "the installed and queued jars are untouched, RigTune's build not enabled"], self.failing())

    def test_rigtunes_dh_build_enabled(self):
        (self.sx.mods / (DH_NEW + ".rigtune-superseded")).rename(self.sx.mods / DH_NEW)
        self.sx.driver["modsAtQuit"] = e2e_checks.listing(self.sx.mods, recursive=True)
        self.assertEqual(["during the session mods/ changed only by retiring the dropped download",
                          "the installed and queued jars are untouched, RigTune's build not enabled"], self.failing())

    def test_queued_jar_removed_during_the_session(self):
        (self.sx.mods / DH_QUEUED).unlink()
        self.sx.driver["modsAtQuit"] = e2e_checks.listing(self.sx.mods, recursive=True)
        self.assertEqual(["during the session mods/ changed only by retiring the dropped download",
                          "the installed and queued jars are untouched, RigTune's build not enabled"], self.failing())


class ListingTest(unittest.TestCase):
    def test_recursive_listing_uses_relative_forward_slash_paths(self):
        sx = SeededFixture()
        listing = e2e_checks.listing(sx.mods, recursive=True)
        self.assertIn(DH_QUEUED, listing)
        self.assertIn(DH_OLD, listing)
        self.assertNotIn(DH_QUEUED, e2e_checks.listing(sx.mods))


if __name__ == "__main__":
    unittest.main()
