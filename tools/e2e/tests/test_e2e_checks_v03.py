"""Checks added for v0.3 (docs/v0.3/plans/ws-h.md): 0.2.x as the old side, the H-M2 seeded variant and the B-M3
per-entry undo."""

import json
import shutil
import tempfile
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
        # WS-B's format (docs/v0.3/design/ws-b.md); the enable's reason names the disable's file.
        self.warn_disable = ("[10:00:01] [main/WARN]: RigTune could not apply a change at the last exit (attempt 2 of 3; it's "
                             "retried at the next exit): DISABLE_FILE fabric-26.2.jar: Gave up after 10 attempt(s)")
        self.warn_enable = ("[10:00:01] [main/WARN]: RigTune could not apply a change at the last exit (attempt 2 of 3; it's "
                            "retried at the next exit): ENABLE_FILE " + DH_NEW + ": Not applied because disabling fabric-26.2.jar failed")
        self.log = "\n".join(["[10:00:01] [Render thread/INFO]: Loaded rules r11", self.warn_disable, self.warn_enable])

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

    def test_one_line_twice_is_not_a_line_per_op(self):
        self.sx.log = "\n".join([self.sx.warn_disable, self.sx.warn_disable])
        self.assertEqual(["latest.log: a WARN line per failed op, with its attempt (3e)"], self.failing())
        self.sx.log = "\n".join([self.sx.warn_enable, self.sx.warn_enable])
        self.assertEqual(["latest.log: a WARN line per failed op, with its attempt (3e)"], self.failing())

    def test_the_enable_line_first_still_matches_each_op(self):
        self.sx.log = "\n".join([self.sx.warn_enable, self.sx.warn_disable])
        self.assertEqual([], self.failing())

    def test_mod_id_alone_does_not_name_an_op(self):
        self.sx.log = "\n".join([self.sx.warn_disable, self.sx.warn_enable.replace(DH_NEW, "distanthorizons")])
        self.assertEqual(["latest.log: a WARN line per failed op, with its attempt (3e)"], self.failing())

    def test_another_status_naming_the_mod_is_not_the_notice(self):
        self.sx.driver["statuses"][1]["key"] = "rigtune.status.update_queued"
        self.assertEqual(["the drop is announced (status notice)"], self.failing())

    def test_a_second_journal_record_of_an_op_must_be_discarded_too(self):
        self.sx.history["entries"].append({"id": "e2", "kind": "apply", "changes": [
            {"id": "c3", "type": "file", "action": "disable", "file": DH_OLD, "status": "STAGED", "opId": "d1"}]})
        self.sx.write_history()
        self.assertEqual(["history.json: the carried-over changes are DISCARDED"], self.failing())

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


FIRST = "e2e-first-1.0.0.jar"
SECOND = "e2e-second-1.0.0.jar"


class EntryFixture:
    """B-M3, after the M14 part: two more Applies, each adding a mod (the older adds e2e-first), applied by the helper."""

    def __init__(self):
        self.root = Path(tempfile.mkdtemp())
        self.first_jar = e2e_env.test_mod_jar(self.root / FIRST, "e2e-first")
        self.second_jar = e2e_env.test_mod_jar(self.root / SECOND, "e2e-second")
        self.instance = self.root / "instance"
        self.mods = self.instance / "mods"
        self.config = self.instance / "config" / "rigtune"
        self.mods.mkdir(parents=True)
        self.config.mkdir(parents=True)
        shutil.copy(self.first_jar, self.mods / FIRST)
        shutil.copy(self.second_jar, self.mods / SECOND)
        self.last_apply([("ENABLE_FILE", FIRST), ("ENABLE_FILE", SECOND)])
        self.m14 = [{"id": "m1", "kind": "apply", "changes": [{"id": "x1", "status": "REVERTED"}]},
                    {"id": "m2", "kind": "undo", "undoOf": "m1", "changes": [{"id": "x2", "status": "APPLIED", "reverts": "x1"}]}]
        self.older = {"id": "a1", "kind": "apply", "at": "2026-09-26T01:00:00Z", "changes": [
            {"id": "c1", "type": "file", "action": "enable", "modId": "e2e-first", "file": FIRST, "status": "APPLIED", "opId": "o1"}]}
        self.newer = {"id": "a2", "kind": "apply", "at": "2026-09-26T01:00:05Z", "changes": [
            {"id": "c2", "type": "file", "action": "enable", "modId": "e2e-second", "file": SECOND, "status": "APPLIED", "opId": "o2"}]}
        self.entries = self.m14 + [self.older, self.newer]
        self.write_history()
        self.known = ["m1", "m2"]
        self.driver = {"ok": True, "applyMessages": ["Applied", "Applied"]}

    def last_apply(self, ops):
        results = []
        for kind, name in ops:
            op = {"type": kind, "path": str(self.mods / name)} if kind == "DISABLE_FILE" else \
                {"type": kind, "from": str(self.mods / (name + ".x")), "to": str(self.mods / name)}
            results.append({"op": op, "status": "OK", "message": ""})
        (self.config / "last-apply.json").write_text(json.dumps({"finishedAt": "t", "results": results}))

    def write_history(self):
        (self.config / "history.json").write_text(json.dumps({"formatVersion": 1, "entries": self.entries}))

    def undone(self):
        """As the instance is after Undo this on the older Apply, a restart and the helper."""
        (self.mods / FIRST).rename(self.mods / (FIRST + ".disabled"))
        self.last_apply([("DISABLE_FILE", FIRST)])
        self.older["changes"][0]["status"] = "REVERTED"
        self.undo = {"id": "u1", "kind": "undo", "undoOf": "a1", "changes": [
            {"id": "u1c", "type": "file", "action": "disable", "file": FIRST, "status": "APPLIED", "reverts": "c1"}]}
        self.entries.append(self.undo)
        self.write_history()
        self.driver = {"ok": True, "undoOf": "a1", "viaScreen": True, "entryPlan": [
            {"action": "REVERT", "needsRestart": True, "changeIds": ["c1"], "description": "Disable " + FIRST}]}


class EntryApplyTest(unittest.TestCase):
    def setUp(self):
        self.fx = EntryFixture()

    def failing(self):
        return names(e2e_checks.after_entry_apply(self.fx.instance, self.fx.first_jar, self.fx.second_jar, self.fx.driver,
                                                  self.fx.known))

    def test_passes(self):
        self.assertEqual([], self.failing())

    def test_one_apply_for_both(self):
        self.fx.older["changes"].append(self.fx.newer["changes"][0])
        self.fx.entries.remove(self.fx.newer)
        self.fx.write_history()
        self.fx.driver["applyMessages"] = ["Applied"]
        self.assertEqual(["history.json: two new apply entries, the older adding e2e-first, both APPLIED",
                          "the driver applied twice, one mod each"], self.failing())

    def test_order_swapped(self):
        self.fx.entries[2:] = [self.fx.newer, self.fx.older]
        self.fx.older["at"], self.fx.newer["at"] = self.fx.newer["at"], self.fx.older["at"]
        self.fx.write_history()
        self.assertEqual(["history.json: two new apply entries, the older adding e2e-first, both APPLIED"], self.failing())

    def test_second_mod_missing(self):
        (self.fx.mods / SECOND).unlink()
        self.assertEqual(["both added mods are in mods (the served bytes)"], self.failing())


class EntryUndoTest(unittest.TestCase):
    def setUp(self):
        self.fx = EntryFixture()
        self.fx.undone()

    def failing(self):
        return names(e2e_checks.after_entry_undo(self.fx.instance, FIRST, SECOND, self.fx.driver, "a1", "a2"))

    def test_passes(self):
        self.assertEqual([], self.failing())

    def test_plan_also_touching_the_newer_entry(self):
        self.fx.driver["entryPlan"].append({"action": "REVERT", "needsRestart": True, "changeIds": ["c2"]})
        self.assertEqual(["the driver undid the older Apply only (one revert after a restart)"], self.failing())

    def test_no_per_entry_api(self):
        self.fx.driver = {"ok": False, "error": "no per-entry undo API"}
        self.assertIn("the driver undid the older Apply only (one revert after a restart)", self.failing())

    def test_newer_mod_disabled_too(self):
        (self.fx.mods / SECOND).rename(self.fx.mods / (SECOND + ".disabled"))
        self.assertEqual(["the newer Apply's mod is still enabled"], self.failing())

    def test_newer_change_reverted_in_the_journal(self):
        self.fx.newer["changes"][0]["status"] = "REVERTED"
        self.fx.write_history()
        self.assertEqual(["history.json: one undo of the older Apply, its change REVERTED, the newer APPLIED"], self.failing())

    def test_undo_recorded_against_the_newer_entry(self):
        self.fx.undo["undoOf"] = "a2"
        self.fx.write_history()
        self.assertEqual(["history.json: one undo of the older Apply, its change REVERTED, the newer APPLIED"], self.failing())

    def test_older_mod_still_enabled(self):
        (self.fx.mods / (FIRST + ".disabled")).rename(self.fx.mods / FIRST)
        self.assertEqual(["the older Apply's mod is disabled"], self.failing())


class EntryCheckTest(unittest.TestCase):
    def setUp(self):
        self.fx = EntryFixture()
        self.fx.undone()
        self.before = e2e_checks.listing(self.fx.mods)
        self.statuses = e2e_checks.history_statuses(self.fx.instance)
        self.driver = {"ok": True, "loadedMods": ["e2e-disable-me", "e2e-second", "rigtune"], "entryUndoableAfter": 0,
                       "entryPlanMethod": "RigTuneController.undoPlanFor",
                       "entryPlanAfterMeta": {"undoOf": None, "at": None, "problem": None}}

    def failing(self):
        return names(e2e_checks.after_entry_check(self.fx.instance, "e2e-first", "e2e-second", "e2e-disable-me", self.driver,
                                                  self.before, self.statuses))

    def test_passes(self):
        self.assertEqual([], self.failing())

    def test_first_still_loaded(self):
        self.driver["loadedMods"].append("e2e-first")
        self.assertEqual(["only the older Apply's mod is off"], self.failing())

    def test_second_not_loaded(self):
        self.driver["loadedMods"].remove("e2e-second")
        self.assertEqual(["only the older Apply's mod is off"], self.failing())

    def test_older_entry_still_undoable(self):
        self.driver["entryUndoableAfter"] = 1
        self.assertEqual(["nothing left to undo on the older Apply"], self.failing())

    def test_an_unavailable_plan_is_not_nothing_to_undo(self):
        self.driver["entryPlanAfterMeta"]["problem"] = "rigtune.undo.busy"
        self.assertEqual(["nothing left to undo on the older Apply"], self.failing())
        self.driver["entryPlanAfterMeta"]["problem"] = None
        self.driver["entryPlanMethod"] = None
        self.assertEqual(["nothing left to undo on the older Apply"], self.failing())


if __name__ == "__main__":
    unittest.main()
