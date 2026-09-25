"""Assertions of the self-update end-to-end test (SPEC item 5), over the files a run leaves in the scratch instance.
Pure: no processes, no network."""

import hashlib
import json
import ntpath
import re
from dataclasses import dataclass
from pathlib import Path
from urllib.parse import unquote

import e2e_env


@dataclass(frozen=True)
class Check:
    name: str
    ok: bool
    detail: str


def digest(path, algorithm):
    return hashlib.new(algorithm, Path(path).read_bytes()).hexdigest()


def listing(directory):
    """File name -> sha256 of every file in a directory (not recursive)."""
    directory = Path(directory)
    if not directory.is_dir():
        return {}
    return {p.name: digest(p, "sha256") for p in sorted(directory.iterdir()) if p.is_file()}


def rigtune_jars(mods):
    return sorted(p.name for p in Path(mods).iterdir() if p.is_file() and p.name.lower().startswith("rigtune")
                  and p.name.lower().endswith(".jar"))


def classpath(command_line, separator):
    match = re.search(r'\s-(?:cp|classpath)\s+(?:"([^"]*)"|(\S+))', command_line)
    if not match:
        return []
    return [entry for entry in (match.group(1) or match.group(2)).split(separator) if entry]


def _norm(path):
    """Case-insensitive, either separator, and `..` resolved (ntpath also accepts '/')."""
    return ntpath.normpath(str(path)).replace("\\", "/").rstrip("/").lower()


def _inside(path, directory):
    return _norm(path).startswith(_norm(directory) + "/")


def _load(path):
    path = Path(path)
    return json.loads(path.read_text(encoding="utf-8")) if path.is_file() else None


def after_update(instance, old_jar, new_jar, driver, server_log, helper_cmdlines, separator=";", extra_disables=()):
    """SPEC 5.5 and AC5.2, after the old version applied the update and the post-exit helper finished. extra_disables:
    jar names the old version disabled in the same apply (so the 0.2 legacy import has something that is not RigTune's)."""
    instance, old_jar, new_jar = Path(instance), Path(old_jar), Path(new_jar)
    mods = instance / "mods"
    rigtune_dir = instance / "config" / "rigtune"
    checks = []

    offered = (driver or {}).get("update") or {}
    checks.append(Check("the driver applied the offered update",
                        bool(driver) and driver.get("ok") is True and offered.get("filename") == new_jar.name,
                        "driver: ok={} error={} offered={}".format((driver or {}).get("ok"), (driver or {}).get("error"),
                                                                    offered.get("filename"))))

    jars = rigtune_jars(mods)
    new_in_mods = mods / new_jar.name
    same = new_in_mods.is_file() and digest(new_in_mods, "sha512") == digest(new_jar, "sha512")
    checks.append(Check("exactly one RigTune jar, the new one", jars == [new_jar.name] and same,
                        "rigtune*.jar in mods: {}; bytes identical to the served jar: {}".format(jars, same)))

    disabled = mods / (old_jar.name + ".disabled")
    checks.append(Check("the old jar is disabled",
                        disabled.is_file() and digest(disabled, "sha256") == digest(old_jar, "sha256"),
                        "{} exists: {}".format(disabled.name, disabled.is_file())))
    if extra_disables:
        state = {name: ((mods / (name + ".disabled")).is_file(), (mods / name).exists()) for name in extra_disables}
        checks.append(Check("the other mod 0.1.0 changed is disabled", all(off and not on for off, on in state.values()),
                            "(.disabled exists, jar exists): {}".format(state)))

    pending = rigtune_dir / "pending.json"
    checks.append(Check("no pending.json", not pending.exists(), "pending.json exists: {}".format(pending.exists())))
    leftovers = sorted(p.name for p in mods.iterdir() if p.name.endswith(".rigtune-pending"))
    checks.append(Check("no leftover downloads", not leftovers, "*.rigtune-pending: {}".format(leftovers)))

    last = _load(rigtune_dir / "last-apply.json") or {}
    results = last.get("results") or []
    summary = [(r.get("op", {}).get("type"), Path(r.get("op", {}).get("path") or r.get("op", {}).get("to") or "").name,
                r.get("status")) for r in results]
    expected = sorted([("DISABLE_FILE", old_jar.name, "OK"), ("ENABLE_FILE", new_jar.name, "OK")]
                      + [("DISABLE_FILE", name, "OK") for name in extra_disables])
    checks.append(Check("last-apply.json: the update's two ops, all OK", sorted(summary) == expected,
                        "results: {}".format(summary)))

    helper_dir = rigtune_dir / "helper"
    entries = [classpath(line, separator) for line in helper_cmdlines]
    from_copies = bool(entries) and all(cp and all(_inside(e, helper_dir) for e in cp) for cp in entries)
    checks.append(Check("the helper ran from config/rigtune/helper copies", from_copies,
                        "helper classpaths seen: {}".format(entries or "none")))

    # RigTune's own requests carry its User-Agent (the redirect probe's don't count).
    agent = "chaotix345/rigtune/{} ".format(e2e_env.mod_json(old_jar)["version"])
    downloads = [r for r in server_log if r.get("method") == "GET" and unquote(r.get("path", "")).endswith("/" + new_jar.name)
                 and "chaotix345/rigtune/" in (r.get("userAgent") or "")]
    from_cdn = bool(downloads) and all(r.get("host") == e2e_env.CDN_HOST and r.get("status") == 200
                                       and (r.get("userAgent") or "").startswith(agent) for r in downloads)
    checks.append(Check("the old RigTune downloaded the jar from cdn.modrinth.com", from_cdn,
                        "download requests: {}".format([(r.get("host"), r.get("status"), r.get("userAgent")) for r in downloads])))

    checks.append(depends_not_stricter(old_jar, new_jar))
    return checks


def depends_not_stricter(old_jar, new_jar):
    """Plan review M12: a stricter `depends` (or a new `breaks`) in the update could leave the game unable to start after
    the post-exit helper swaps the jars, with no RigTune left to undo it. Any added or changed entry needs a human look.
    Nested jar-in-jar mods aren't compared."""
    old_json, new_json = e2e_env.mod_json(old_jar), e2e_env.mod_json(new_jar)
    changes = []
    for field in ("depends", "breaks"):
        old = old_json.get(field) or {}
        changes += ["{}.{}: {} -> {}".format(field, key, old.get(key, "(absent)"), value)
                    for key, value in sorted((new_json.get(field) or {}).items()) if old.get(key) != value]
    return Check("the update's depends and breaks are no stricter (M12)", not changes,
                 "; ".join(changes) if changes else "same or fewer entries: depends {} breaks {}".format(
                     new_json.get("depends") or {}, new_json.get("breaks") or {}))


def after_verify(instance, new_jar, driver, last_apply_finished_at, mods_before, expect_history, legacy_disables=(),
                 old_jar=None, statuses_before=None):
    """SPEC 5.6: the new version, relaunched on the same instance, reads the old version's state. expect_history:
    None, "legacy-import" (a 0.1.x old side; True means the same) or "own-update" (a 0.2.x old side: old_jar and the
    history statuses before the relaunch are needed). legacy_disables: jar names the legacy import must hold as APPLIED
    disables."""
    instance, new_jar = Path(instance), Path(new_jar)
    mods = instance / "mods"
    rigtune_dir = instance / "config" / "rigtune"
    driver = driver or {}
    checks = [Check("the driver verified", driver.get("ok") is True, "error: {}".format(driver.get("error")))]

    version = e2e_env.mod_json(new_jar)["version"]
    origin = driver.get("rigtuneOrigin") or []
    checks.append(Check("the new RigTune is loaded from mods/",
                        driver.get("rigtuneVersion") == version and [_norm(p) for p in origin] == [_norm(mods / new_jar.name)],
                        "loaded {} from {}".format(driver.get("rigtuneVersion"), origin)))
    checks.append(Check("the goal is kept", driver.get("goal") == "QUALITY", "goal: {}".format(driver.get("goal"))))

    state = _load(rigtune_dir / "rigtune.json") or {}
    checks.append(Check("the apply result was shown (rigtune.json lastShownApply)",
                        last_apply_finished_at is not None and state.get("lastShownApply") == last_apply_finished_at,
                        "lastShownApply {} vs last-apply.json finishedAt {}".format(state.get("lastShownApply"),
                                                                                   last_apply_finished_at)))
    checks.append(Check("no further RigTune update is offered",
                        driver.get("reportOnline") is True and driver.get("updateOffered") is False,
                        "online={} updateOffered={}".format(driver.get("reportOnline"), driver.get("updateOffered"))))

    crashes = sorted(p.name for p in (instance / "crash-reports").glob("*")) if (instance / "crash-reports").is_dir() else []
    checks.append(Check("no crash report", not crashes, "crash-reports: {}".format(crashes)))
    after = listing(mods)
    checks.append(Check("mods unchanged by the relaunch", after == mods_before,
                        "before {} after {}".format(sorted(mods_before), sorted(after)) if after != mods_before else "unchanged"))
    checks.append(Check("no new pending.json", not (rigtune_dir / "pending.json").exists(), ""))

    if expect_history == "own-update":
        checks.append(own_update_history(instance, old_jar, new_jar, statuses_before))
    elif expect_history:
        # SPEC item 3: the first 0.2 run imports 0.1.0's last-apply.json once, as one legacy-import entry, without
        # RigTune's own jars. A file of another shape fails rather than passing with nothing checked.
        history = _load(rigtune_dir / "history.json")
        entries = history.get("entries") if isinstance(history, dict) else None
        imports = [e for e in entries if e.get("kind") == "legacy-import"] if isinstance(entries, list) else []
        own = [c for e in imports for c in e.get("changes", [])
               if c.get("modId") == "rigtune" or str(c.get("file") or "").lower().startswith("rigtune")]
        imported = {c.get("file") for e in imports for c in e.get("changes", [])
                    if c.get("type") == "file" and c.get("action") == "disable" and c.get("status") == "APPLIED"}
        missing = [name for name in legacy_disables if name not in imported]
        checks.append(Check("history.json: one legacy import, without RigTune's own jars",
                            isinstance(entries, list) and len(imports) == 1 and not own and not missing,
                            "history.json entries: {}; legacy-import entries: {}; RigTune changes: {}; expected disables "
                            "missing: {}; imported changes: {}".format("missing" if not isinstance(entries, list) else len(entries),
                                                                       len(imports), own, missing,
                                                                       [e.get("changes") for e in imports])))
    return checks


def own_update_history(instance, old_jar, new_jar, statuses_before):
    """A 0.2.x old side journals its own update as one apply entry (disable the old jar, enable the new one), which its
    helper marks APPLIED. The new version must read that journal as it is: no legacy import, no status changed by the
    relaunch (SPEC compatibility promise: every file 0.2.0 wrote keeps working)."""
    entries = history_entries(instance)
    got = sorted((c.get("type"), c.get("action"), c.get("file"), c.get("status"))
                 for e in entries or [] for c in e.get("changes", []))
    wanted = sorted([("file", "disable", Path(old_jar).name, "APPLIED"), ("file", "enable", Path(new_jar).name, "APPLIED")])
    kinds = [e.get("kind") for e in entries or []]
    statuses = history_statuses(instance)
    ok = entries is not None and kinds == ["apply"] and got == wanted and statuses == statuses_before
    return Check("history.json: the old version's own update, both changes APPLIED", ok,
                 "entries: {}; changes: {}; statuses unchanged by the relaunch: {}".format(
                     kinds if entries is not None else "missing", got, statuses == statuses_before))


# --- The end-to-end undo after a restart (plan review M14) ------------------------------------------------------------

def history_entries(instance):
    history = _load(Path(instance) / "config" / "rigtune" / "history.json")
    entries = history.get("entries") if isinstance(history, dict) else None
    return entries if isinstance(entries, list) else None


def history_statuses(instance):
    """Change id -> status, over every entry."""
    return {c.get("id"): c.get("status") for e in history_entries(instance) or [] for c in e.get("changes", [])}


def _ops(instance):
    last = _load(Path(instance) / "config" / "rigtune" / "last-apply.json") or {}
    return sorted((r.get("op", {}).get("type"), Path(r.get("op", {}).get("path") or r.get("op", {}).get("to") or "").name,
                   r.get("status")) for r in last.get("results") or [])


def _clean(instance):
    mods = Path(instance) / "mods"
    pending = Path(instance) / "config" / "rigtune" / "pending.json"
    leftovers = sorted(p.name for p in mods.iterdir() if p.name.endswith(".rigtune-pending"))
    return Check("no pending.json, no leftover downloads", not pending.exists() and not leftovers,
                 "pending.json exists: {}; *.rigtune-pending: {}".format(pending.exists(), leftovers))


def after_mod_apply(instance, added_jar, other_name, driver):
    """0.2 applied {add a mod from Modrinth, disable another} and the helper ran."""
    instance, added_jar = Path(instance), Path(added_jar)
    mods = instance / "mods"
    driver = driver or {}
    checks = [Check("the driver applied the mod changes", driver.get("ok") is True, "error: {}".format(driver.get("error")))]
    added = mods / added_jar.name
    checks.append(Check("the added mod is in mods (the served bytes)",
                        added.is_file() and digest(added, "sha512") == digest(added_jar, "sha512"),
                        "{} exists: {}".format(added.name, added.is_file())))
    checks.append(Check("the other mod is disabled", (mods / (other_name + ".disabled")).is_file() and not (mods / other_name).exists(),
                        "{}.disabled exists: {}".format(other_name, (mods / (other_name + ".disabled")).is_file())))
    checks.append(_clean(instance))
    ops = _ops(instance)
    checks.append(Check("last-apply.json: both ops OK",
                        ops == sorted([("DISABLE_FILE", other_name, "OK"), ("ENABLE_FILE", added_jar.name, "OK")]),
                        "results: {}".format(ops)))
    entries = history_entries(instance) or []
    applies = [e for e in entries if e.get("kind") == "apply"]
    changes = {(c.get("type"), c.get("action"), c.get("file"), c.get("status")) for e in applies for c in e.get("changes", [])}
    wanted = {("file", "enable", added_jar.name, "APPLIED"), ("file", "disable", other_name, "APPLIED")}
    checks.append(Check("history.json: one apply entry, both changes APPLIED", len(applies) == 1 and changes == wanted,
                        "apply entries: {}; changes: {}".format(len(applies), sorted(changes, key=str))))
    return checks


def after_mod_undo(instance, added_name, other_name, driver, apply_entry_id):
    """0.2 undid the last apply (both changes staged as reversals) and the helper ran after the restart."""
    instance = Path(instance)
    mods = instance / "mods"
    driver = driver or {}
    items = driver.get("undoPlan") or []
    reverts = [i for i in items if i.get("action") == "REVERT" and i.get("needsRestart") is True]
    checks = [Check("the driver undid the last apply (two reverts after a restart)",
                    driver.get("ok") is True and len(items) == 2 and len(reverts) == 2 and driver.get("undoOf") == apply_entry_id,
                    "error: {}; undoOf: {}; plan: {}".format(driver.get("error"), driver.get("undoOf"), items))]
    checks.append(Check("the added mod is disabled again", (mods / (added_name + ".disabled")).is_file() and not (mods / added_name).exists(),
                        "{}.disabled exists: {}".format(added_name, (mods / (added_name + ".disabled")).is_file())))
    checks.append(Check("the other mod is back", (mods / other_name).is_file() and not (mods / (other_name + ".disabled")).exists(),
                        "{} exists: {}".format(other_name, (mods / other_name).is_file())))
    checks.append(_clean(instance))
    ops = _ops(instance)
    checks.append(Check("last-apply.json: both reversal ops OK",
                        ops == sorted([("DISABLE_FILE", added_name, "OK"), ("ENABLE_FILE", other_name, "OK")]),
                        "results: {}".format(ops)))
    entries = history_entries(instance) or []
    applied = [e for e in entries if e.get("id") == apply_entry_id]
    undos = [e for e in entries if e.get("kind") == "undo"]
    original = {c.get("id"): c.get("status") for e in applied for c in e.get("changes", [])}
    undo_changes = [c for e in undos for c in e.get("changes", [])]
    ok = (len(undos) == 1 and undos[0].get("undoOf") == apply_entry_id and len(original) == 2
          and set(original.values()) == {"REVERTED"} and len(undo_changes) == 2
          and all(c.get("status") == "APPLIED" for c in undo_changes)
          and {c.get("reverts") for c in undo_changes} == set(original))
    checks.append(Check("history.json: the undo APPLIED, the apply's changes REVERTED", ok,
                        "apply changes: {}; undo entries: {}; undo changes: {}".format(
                            original, [e.get("undoOf") for e in undos],
                            [(c.get("action"), c.get("file"), c.get("status"), c.get("reverts")) for c in undo_changes])))
    return checks


def after_mod_check(instance, added_id, other_id, driver, mods_before, statuses_before):
    """The next start: the mods are as before the apply, and there is nothing left to undo."""
    instance = Path(instance)
    driver = driver or {}
    loaded = driver.get("loadedMods") or []
    checks = [Check("the driver checked", driver.get("ok") is True, "error: {}".format(driver.get("error")))]
    checks.append(Check("the undone mods are as before the apply", other_id in loaded and added_id not in loaded,
                        "{} loaded: {}; {} loaded: {}".format(other_id, other_id in loaded, added_id, added_id in loaded)))
    checks.append(Check("nothing left to undo", driver.get("undoableItems") == 0, "undoable items: {}".format(driver.get("undoableItems"))))
    crashes = sorted(p.name for p in (instance / "crash-reports").glob("*")) if (instance / "crash-reports").is_dir() else []
    checks.append(Check("no crash report", not crashes, "crash-reports: {}".format(crashes)))
    after = listing(instance / "mods")
    checks.append(Check("mods unchanged by the relaunch", after == mods_before, "unchanged" if after == mods_before else
                        "before {} after {}".format(sorted(mods_before), sorted(after))))
    statuses = history_statuses(instance)
    checks.append(Check("history.json statuses unchanged", statuses == statuses_before,
                        "unchanged" if statuses == statuses_before else "before {} after {}".format(statuses_before, statuses)))
    checks.append(_clean(instance))
    return checks
