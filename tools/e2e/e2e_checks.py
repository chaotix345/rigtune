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

# 3a's notice (RealController.droppedQueuedUpdates), when a staged update of a mod with its own update queued is dropped.
QUEUED_UPDATE_DROPPED = "rigtune.status.queued_update_dropped"
# 3e (v0.3 WS-B): the WARN line for a change the last exit couldn't apply names its attempt out of the helper's three.
ATTEMPT = re.compile(r"attempt \d+ of 3", re.IGNORECASE)


def history_expectation(old_version):
    """What the new version finds in history.json after updating from old_version: 0.1.x has no journal, so the new
    version imports its last apply once ("legacy-import"); 0.2.0 and later journal their own update as an apply entry
    ("own-update")."""
    match = re.match(r"(\d+)\.(\d+)", old_version or "")
    if match is None:
        raise ValueError("not a RigTune version: {!r}".format(old_version))
    major, minor = (int(part) for part in match.groups())
    return "legacy-import" if (major, minor) < (0, 2) else "own-update"


@dataclass(frozen=True)
class Check:
    name: str
    ok: bool
    detail: str


def digest(path, algorithm):
    return hashlib.new(algorithm, Path(path).read_bytes()).hexdigest()


def listing(directory, recursive=False):
    """File name -> sha256 of every file in a directory; recursive: the path relative to it, with '/'."""
    directory = Path(directory)
    if not directory.is_dir():
        return {}
    if recursive:
        return {p.relative_to(directory).as_posix(): digest(p, "sha256") for p in sorted(directory.rglob("*")) if p.is_file()}
    return {p.name: digest(p, "sha256") for p in sorted(directory.iterdir()) if p.is_file()}


def _name(path):
    return ntpath.basename(str(path)) if path else None


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


def after_update(instance, old_jar, new_jar, driver, server_log, helper_cmdlines, separator=";", extra_disables=(),
                 carried=()):
    """SPEC 5.5 and AC5.2, after the old version applied the update and the post-exit helper finished. extra_disables:
    jar names the old version disabled in the same apply (so its journal, or the new version's legacy import of 0.1.x,
    has something that is not RigTune's).
    carried: the seeded pending.json's ops (H-M2), which the helper retried and failed again, so they stay pending."""
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
        checks.append(Check("the other mod the old version changed is disabled", all(off and not on for off, on in state.values()),
                            "(.disabled exists, jar exists): {}".format(state)))

    pending = rigtune_dir / "pending.json"
    leftovers = sorted(p.name for p in mods.iterdir() if p.name.endswith(".rigtune-pending"))
    if carried:
        ops = (_load(pending) or {}).get("ops") or []
        got = sorted((op.get("id"), op.get("attempts")) for op in ops)
        want = sorted((op.get("id"), (op.get("attempts") or 0) + 1) for op in carried)
        checks.append(Check("pending.json holds only the carried-over group, one attempt more", got == want,
                            "ops (id, attempts): {}; expected {}".format(got, want)))
        downloads = sorted(_name(op.get("from")) for op in carried if op.get("type") == "ENABLE_FILE" and op.get("from"))
        checks.append(Check("no leftover downloads but the carried-over group's", leftovers == downloads,
                            "*.rigtune-pending: {}; expected {}".format(leftovers, downloads)))
    else:
        checks.append(Check("no pending.json", not pending.exists(), "pending.json exists: {}".format(pending.exists())))
        checks.append(Check("no leftover downloads", not leftovers, "*.rigtune-pending: {}".format(leftovers)))

    last = _load(rigtune_dir / "last-apply.json") or {}
    results = last.get("results") or []
    summary = [(r.get("op", {}).get("type"), _name(r.get("op", {}).get("path") or r.get("op", {}).get("to") or ""),
                r.get("status")) for r in results]
    expected = sorted([("DISABLE_FILE", old_jar.name, "OK"), ("ENABLE_FILE", new_jar.name, "OK")]
                      + [("DISABLE_FILE", name, "OK") for name in extra_disables]
                      + [(op.get("type"), _name(op.get("path") or op.get("to")), "FAILED") for op in carried])
    checks.append(Check("last-apply.json: the update's two ops OK, the carried-over ops FAILED" if carried
                        else "last-apply.json: the update's two ops, all OK", sorted(summary) == expected,
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
    None, "legacy-import" (a 0.1.x old side; True means the same) or "own-update" (a 0.2.0 or later old side: old_jar and
    the history statuses before the relaunch are needed); see history_expectation. legacy_disables: jar names the old
    version disabled in the same apply, which the legacy import or its own entry must hold as APPLIED disables."""
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
    if mods_before is not None:
        after = listing(mods)
        checks.append(Check("mods unchanged by the relaunch", after == mods_before,
                            "before {} after {}".format(sorted(mods_before), sorted(after)) if after != mods_before else "unchanged"))
    checks.append(Check("no new pending.json", not (rigtune_dir / "pending.json").exists(), ""))

    if expect_history == "own-update":
        checks.append(own_update_history(instance, old_jar, new_jar, statuses_before, legacy_disables=legacy_disables))
    elif expect_history:
        # v0.2 SPEC item 3: the new version's first run imports 0.1.x's last-apply.json once, as one legacy-import entry, without
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


def after_seeded_verify(instance, carried, mod_names, driver, log_text, failed_ops, helper_cmdlines, mods_before):
    """H-M2, the first start of the new version on the seeded 0.1.0 instance: RigTune's update of a loaded mod whose own
    update waits in mods/update/ (3a) is dropped with its group and the notice, the journal marks it DISCARDED, each
    failed op of the last apply gets a WARN line in latest.log (3e), and nothing in mods/ changes at exit.
    carried: the seeded group's ops; mod_names: the mod's id and name; failed_ops: the FAILED ops of the last-apply.json
    the new version read; helper_cmdlines: helpers seen during the launch; mods_before: listing(mods, recursive=True)
    before it. The driver records the statuses it saw and the mods folder when it quit (modsAtQuit)."""
    instance = Path(instance)
    mods = instance / "mods"
    driver = driver or {}
    ids = {op.get("id") for op in carried}

    plan = _load(instance / "config" / "rigtune" / "pending.json")
    left = sorted(op.get("id") for op in (plan or {}).get("ops") or [] if op.get("id") in ids)
    checks = [Check("the carried-over group is dropped", not left,
                    "pending.json {}; carried-over ops still in it: {}".format("present" if plan is not None else "absent", left))]

    statuses = driver.get("statuses") or []
    wanted = [n.lower() for n in mod_names]
    notices = [s for s in statuses if s.get("key") == QUEUED_UPDATE_DROPPED and any(n in (s.get("text") or "").lower() for n in wanted)]
    checks.append(Check("the drop is announced (status notice)", bool(notices),
                        "statuses seen: {}".format([(s.get("key"), s.get("text")) for s in statuses])))

    journaled = {}
    for c in (c for e in history_entries(instance) or [] for c in e.get("changes", []) if c.get("opId") in ids):
        journaled.setdefault(c.get("opId"), []).append(c.get("status"))
    checks.append(Check("history.json: the carried-over changes are DISCARDED",
                        set(journaled) == ids and all(s == "DISCARDED" for statuses in journaled.values() for s in statuses),
                        "statuses by op id: {}".format(journaled)))

    # SPEC 3e: op, file, reason, attempt. Identical lines count once.
    warns = list(dict.fromkeys(line for line in log_text.splitlines()
                               if "/WARN]" in line and ATTEMPT.search(line)))

    def files(op):
        """The op's own file names only: one group's ops share a mod id, and a reason can name another op's file."""
        return [k for k in (_name(op.get("path")), _name(op.get("to")), _name(op.get("from"))) if k]

    candidates = [[i for i, line in enumerate(warns) if (op.get("type") or "") in line and any(f in line for f in files(op))]
                  for op in failed_ops]
    matched = _distinct_matches(candidates)
    checks.append(Check("latest.log: a WARN line per failed op, with its attempt (3e)",
                        bool(failed_ops) and matched == len(failed_ops),
                        "ops with a line of their own: {} of {}; failed ops: {}; WARN lines with an attempt: {}".format(
                            matched, len(failed_ops), [(op.get("type"), files(op)) for op in failed_ops], warns)))

    at_quit = driver.get("modsAtQuit")
    after = listing(mods, recursive=True)
    checks.append(Check("nothing in mods/ changes at exit", not helper_cmdlines and at_quit == after,
                        "helper runs at exit: {}; mods/ at quit -> after exit: {}".format(
                            len(helper_cmdlines), "unchanged" if at_quit == after else _diff(at_quit or {}, after))))

    installed = [_name(op.get("path")) for op in carried if op.get("type") == "DISABLE_FILE"]
    queued = [k for k in mods_before if k.startswith("update/")]
    targets = [_name(op.get("to")) for op in carried if op.get("type") == "ENABLE_FILE"]
    kept = {k: (mods_before.get(k) is not None and after.get(k) == mods_before.get(k)) for k in installed + queued}
    enabled = [t for t in targets if (mods / t).exists()]
    checks.append(Check("the installed and queued jars are untouched, RigTune's build not enabled",
                        bool(kept) and all(kept.values()) and not enabled,
                        "unchanged: {}; RigTune's build enabled: {}".format(kept, enabled)))

    downloads = {_name(op.get("from")) for op in carried if op.get("type") == "ENABLE_FILE" and op.get("from")}
    retired = {d[:-len(".rigtune-pending")] + ".rigtune-superseded" for d in downloads if d.endswith(".rigtune-pending")}
    at_quit = at_quit or {}
    removed = set(mods_before) - set(at_quit)
    added = set(at_quit) - set(mods_before)
    changed = {k for k in set(mods_before) & set(at_quit) if mods_before[k] != at_quit[k]}
    checks.append(Check("during the session mods/ changed only by retiring the dropped download",
                        bool(at_quit) and not changed and removed <= downloads
                        and all(any(k == r or k.startswith(r + ".") for r in retired) for k in added),
                        _diff(mods_before, at_quit) if at_quit else "no modsAtQuit from the driver"))
    return checks


def _distinct_matches(candidates):
    """candidates[i]: the line numbers op i may use. How many ops get a line of their own (bipartite matching)."""
    owner = {}

    def assign(op, seen):
        for line in candidates[op]:
            if line not in seen:
                seen.add(line)
                if line not in owner or assign(owner[line], seen):
                    owner[line] = op
                    return True
        return False

    return sum(assign(op, set()) for op in range(len(candidates)))


def _diff(before, after):
    return "removed {}, added {}, changed {}".format(sorted(set(before) - set(after)), sorted(set(after) - set(before)),
                                                     sorted(k for k in set(before) & set(after) if before[k] != after[k]))


def own_update_history(instance, old_jar, new_jar, statuses_before, legacy_disables=()):
    """A 0.2.0 or later old side journals its own update as one apply entry (disable the old jar, enable the new one, plus
    legacy_disables: other jars it disabled in the same apply), which its helper marks APPLIED. The new version must
    read that journal as it is: no legacy import, no status changed by the relaunch (SPEC compatibility promise: every
    file an older version wrote keeps working)."""
    entries = history_entries(instance)
    got = sorted((c.get("type"), c.get("action"), c.get("file"), c.get("status"))
                 for e in entries or [] for c in e.get("changes", []))
    wanted = sorted([("file", "disable", Path(old_jar).name, "APPLIED"), ("file", "enable", Path(new_jar).name, "APPLIED")]
                    + [("file", "disable", name, "APPLIED") for name in legacy_disables])
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
    """The installed RigTune (0.2 or later) applied {add a mod from Modrinth, disable another} and the helper ran."""
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
    """RigTune undid the last apply (both changes staged as reversals) and the helper ran after the restart."""
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


# --- Undo this on an older Apply, after a restart (plan review B-M3) ---------------------------------------------------

def _file_changes(entry):
    return [(c.get("type"), c.get("action"), c.get("file"), c.get("status")) for c in (entry or {}).get("changes", [])]


def after_entry_apply(instance, first_jar, second_jar, driver, known_entry_ids):
    """Two Applies in one start, each adding a mod from Modrinth (first_jar, then second_jar), and the helper ran."""
    instance, first_jar, second_jar = Path(instance), Path(first_jar), Path(second_jar)
    mods = instance / "mods"
    driver = driver or {}
    checks = [Check("the driver applied twice, one mod each", driver.get("ok") is True and len(driver.get("applyMessages") or []) == 2,
                    "error: {}; apply messages: {}".format(driver.get("error"), driver.get("applyMessages")))]
    same = {jar.name: (mods / jar.name).is_file() and digest(mods / jar.name, "sha512") == digest(jar, "sha512")
            for jar in (first_jar, second_jar)}
    checks.append(Check("both added mods are in mods (the served bytes)", all(same.values()), "served bytes in mods: {}".format(same)))
    checks.append(_clean(instance))
    ops = _ops(instance)
    checks.append(Check("last-apply.json: both enables OK",
                        ops == sorted([("ENABLE_FILE", first_jar.name, "OK"), ("ENABLE_FILE", second_jar.name, "OK")]),
                        "results: {}".format(ops)))
    new = [e for e in history_entries(instance) or [] if e.get("id") not in set(known_entry_ids)]
    changes = [_file_changes(e) for e in new]
    ok = ([e.get("kind") for e in new] == ["apply", "apply"]
          and changes == [[("file", "enable", first_jar.name, "APPLIED")], [("file", "enable", second_jar.name, "APPLIED")]]
          and (new[0].get("at") or "") <= (new[1].get("at") or ""))
    checks.append(Check("history.json: two new apply entries, the older adding {}, both APPLIED".format(first_jar.stem.rsplit("-", 1)[0]),
                        ok, "new entries: {}".format([(e.get("kind"), e.get("at"), _file_changes(e)) for e in new])))
    return checks


def after_entry_undo(instance, first_name, second_name, driver, older_id, newer_id):
    """Undo this on the older Apply (not the last one), a restart and the helper: only its mod is disabled."""
    instance = Path(instance)
    mods = instance / "mods"
    driver = driver or {}
    entries = {e.get("id"): e for e in history_entries(instance) or []}
    older_changes = [c.get("id") for c in (entries.get(older_id) or {}).get("changes", [])]
    items = [i for i in driver.get("entryPlan") or [] if i.get("action") != "SKIP"]
    checks = [Check("the driver undid the older Apply only (one revert after a restart)",
                    driver.get("ok") is True and driver.get("undoOf") == older_id and len(items) == 1
                    and items[0].get("action") == "REVERT" and items[0].get("needsRestart") is True
                    and items[0].get("changeIds") == older_changes and bool(older_changes),
                    "error: {}; undoOf: {} (older {}); via the undo screen: {}; plan: {}".format(
                        driver.get("error"), driver.get("undoOf"), older_id, driver.get("viaScreen"), driver.get("entryPlan")))]
    checks.append(Check("the older Apply's mod is disabled", (mods / (first_name + ".disabled")).is_file() and not (mods / first_name).exists(),
                        "{}.disabled exists: {}; {} exists: {}".format(first_name, (mods / (first_name + ".disabled")).is_file(),
                                                                       first_name, (mods / first_name).exists())))
    checks.append(Check("the newer Apply's mod is still enabled", (mods / second_name).is_file() and not (mods / (second_name + ".disabled")).exists(),
                        "{} exists: {}".format(second_name, (mods / second_name).is_file())))
    checks.append(_clean(instance))
    ops = _ops(instance)
    checks.append(Check("last-apply.json: the reversal op OK", ops == [("DISABLE_FILE", first_name, "OK")], "results: {}".format(ops)))
    undos = [e for e in entries.values() if e.get("kind") == "undo" and e.get("undoOf") in (older_id, newer_id)]
    undo_changes = [c for e in undos for c in e.get("changes", [])]
    older = {c.get("id"): c.get("status") for c in (entries.get(older_id) or {}).get("changes", [])}
    newer = {c.get("id"): c.get("status") for c in (entries.get(newer_id) or {}).get("changes", [])}
    ok = ([e.get("undoOf") for e in undos] == [older_id] and len(undo_changes) == 1
          and undo_changes[0].get("status") == "APPLIED" and undo_changes[0].get("reverts") in older
          and set(older.values()) == {"REVERTED"} and set(newer.values()) == {"APPLIED"})
    checks.append(Check("history.json: one undo of the older Apply, its change REVERTED, the newer APPLIED", ok,
                        "undo entries of these applies: {}; undo changes: {}; older: {}; newer: {}".format(
                            [e.get("undoOf") for e in undos],
                            [(c.get("action"), c.get("file"), c.get("status"), c.get("reverts")) for c in undo_changes], older, newer)))
    return checks


def after_entry_check(instance, first_id, second_id, other_id, driver, mods_before, statuses_before):
    """The next start: the older Apply's mod is off, the newer's and the M14 part's are on, the older has nothing left."""
    instance = Path(instance)
    driver = driver or {}
    loaded = driver.get("loadedMods") or []
    checks = [Check("the driver checked", driver.get("ok") is True, "error: {}".format(driver.get("error")))]
    checks.append(Check("only the older Apply's mod is off", first_id not in loaded and second_id in loaded and other_id in loaded,
                        "loaded: {} {}, {} {}, {} {}".format(first_id, first_id in loaded, second_id, second_id in loaded,
                                                             other_id, other_id in loaded)))
    problem = (driver.get("entryPlanAfterMeta") or {}).get("problem")
    checks.append(Check("nothing left to undo on the older Apply",
                        driver.get("entryUndoableAfter") == 0 and bool(driver.get("entryPlanMethod")) and problem is None,
                        "undoable items: {}; plan problem: {}; method: {}".format(driver.get("entryUndoableAfter"), problem,
                                                                                 driver.get("entryPlanMethod"))))
    crashes = sorted(p.name for p in (instance / "crash-reports").glob("*")) if (instance / "crash-reports").is_dir() else []
    checks.append(Check("no crash report", not crashes, "crash-reports: {}".format(crashes)))
    after = listing(instance / "mods")
    checks.append(Check("mods unchanged by the relaunch", after == mods_before, "unchanged" if after == mods_before else _diff(mods_before, after)))
    statuses = history_statuses(instance)
    checks.append(Check("history.json statuses unchanged", statuses == statuses_before,
                        "unchanged" if statuses == statuses_before else "before {} after {}".format(statuses_before, statuses)))
    checks.append(_clean(instance))
    return checks


# --- Phase 5 hook: undo after a restart of a profile-switch entry (docs/v0.4/design/ws-h.md) --------------------------
# A profile switch is an ordinary apply entry of setting changes (docs/research/v0.4/profiles.md, section 0); 0.4 labels
# it in config/rigtune/profiles.json ({switches: [{entryId, profileId, templateId, name}]}). On the fresh E2E instance
# every managed setting is vanilla, applied and reverted at once, so options.txt shows each step.

VANILLA = "vanilla."


def options_values(instance):
    """options.txt as key -> value, a JSON string value unquoted as SettingsBridge.readVanilla (the journal's values)
    does: options.txt stores e.g. graphicsPreset:"fast", the journal fast."""
    path = Path(instance) / "options.txt"
    if not path.is_file():
        return {}
    pairs = (line.split(":", 1) for line in path.read_text(encoding="utf-8", errors="replace").splitlines() if ":" in line)
    return {key: _unquote(value) for key, value in pairs}


def _unquote(value):
    if len(value) >= 2 and value.startswith('"') and value.endswith('"'):
        try:
            return json.loads(value)
        except ValueError:
            return value
    return value


def _settings(entry):
    return {c.get("key"): c for c in (entry or {}).get("changes", []) if c.get("type") == "setting"}


def _options_hold(instance, values, name):
    options = options_values(instance)
    wrong = {key: (options.get(key[len(VANILLA):]), value) for key, value in values.items()
             if options.get(key[len(VANILLA):]) != value}
    return Check(name, bool(values) and not wrong, "(options.txt, expected) where they differ: {}".format(wrong) if wrong else
                 "{} value(s) as expected".format(len(values)))


def profile_label(instance, entry_id, name, check_name):
    """profiles.json (WS-P) has one switch for entry_id, named name."""
    data = _load(Path(instance) / "config" / "rigtune" / "profiles.json")
    switches = data.get("switches") if isinstance(data, dict) else None
    mine = [s for s in switches or [] if isinstance(s, dict) and s.get("entryId") == entry_id]
    return Check(check_name, entry_id is not None and len(mine) == 1 and mine[0].get("name") == name,
                 "switches for entry {}: {}; expected name {}".format(entry_id, mine, name))


def after_profile_apply(instance, driver, known_entry_ids, originals, targets, label):
    """The switch launch: one new apply entry of vanilla setting changes, APPLIED, and options.txt holding the new values.
    targets (the settings stand-in): the exact bare key -> value it set, with originals (options.txt before the launch)
    as each change's before; None in profile mode, where the profile decides the keys. label: the profile's name that
    profiles.json must give the entry (profile mode), or None."""
    instance = Path(instance)
    driver = driver or {}
    checks = [Check("the driver switched", driver.get("ok") is True and bool(driver.get("applyMessage")),
                    "error: {}; apply message: {}".format(driver.get("error"), driver.get("applyMessage")))]
    new = [e for e in history_entries(instance) or [] if e.get("id") not in set(known_entry_ids)]
    entry = new[0] if len(new) == 1 else None
    settings = _settings(entry)
    ok = (entry is not None and entry.get("kind") == "apply" and bool(settings)
          and len(settings) == len(entry.get("changes", [])) and all(k and k.startswith(VANILLA) for k in settings)
          and all(c.get("status") == "APPLIED" for c in settings.values()))
    checks.append(Check("history.json: one new apply entry of setting changes, all APPLIED", ok,
                        "new entries: {}".format([(e.get("kind"), [(c.get("type"), c.get("key"), c.get("before"), c.get("after"),
                                                                   c.get("status")) for c in e.get("changes", [])]) for e in new])))
    if targets is not None:
        got = {k: (c.get("before"), c.get("after")) for k, c in settings.items()}
        wanted = {VANILLA + k: (originals.get(k), v) for k, v in targets.items()}
        unusable = {k: originals.get(k) for k, v in targets.items() if originals.get(k) in (None, v)}
        checks.append(Check("history.json: the switch changed exactly the chosen settings", got == wanted and not unusable,
                            "(before, after) by key: {}; expected {}{}".format(got, wanted, "; missing from options.txt or "
                                                                              "already at the target: {}".format(unusable)
                                                                              if unusable else "")))
    checks.append(_options_hold(instance, {k: c.get("after") for k, c in settings.items()}, "options.txt holds the switched values"))
    checks.append(_clean(instance))
    if label is not None:
        checks.append(profile_label(instance, entry.get("id") if entry else None, label, "profiles.json labels the switch entry"))
    return checks


def after_profile_undo(instance, driver, entry_id, label):
    """Undo this on the switch entry in the next launch: every change reverted at once, no restart needed."""
    instance = Path(instance)
    driver = driver or {}
    entries = {e.get("id"): e for e in history_entries(instance) or []}
    switch = _settings(entries.get(entry_id))
    items = [i for i in driver.get("entryPlan") or [] if i.get("action") != "SKIP"]
    planned = sorted(c for i in items for c in i.get("changeIds") or [])
    checks = [Check("the driver undid the switch entry now (no restart needed)",
                    driver.get("ok") is True and driver.get("undoOf") == entry_id and bool(items)
                    and all(i.get("action") == "REVERT" and i.get("needsRestart") is False for i in items)
                    and planned == sorted(c.get("id") for c in switch.values()),
                    "error: {}; undoOf: {} (switch {}); plan: {}".format(driver.get("error"), driver.get("undoOf"), entry_id,
                                                                        driver.get("entryPlan")))]
    checks.append(_options_hold(instance, {k: c.get("before") for k, c in switch.items()},
                                "options.txt holds the values from before the switch"))
    undos = [e for e in entries.values() if e.get("kind") == "undo" and e.get("undoOf") == entry_id]
    undo_changes = [c for e in undos for c in e.get("changes", [])]
    ok = (len(undos) == 1 and bool(switch) and all(c.get("status") == "REVERTED" for c in switch.values())
          and all(c.get("status") == "APPLIED" for c in undo_changes)
          and sorted(c.get("reverts") for c in undo_changes) == sorted(c.get("id") for c in switch.values()))
    checks.append(Check("history.json: one undo of the switch, its changes REVERTED", ok,
                        "switch changes: {}; undo entries: {}; undo changes: {}".format(
                            {k: c.get("status") for k, c in switch.items()}, len(undos),
                            [(c.get("key"), c.get("status"), c.get("reverts")) for c in undo_changes])))
    checks.append(_clean(instance))
    if label is not None:
        checks.append(profile_label(instance, entry_id, label, "profiles.json still labels the switch entry"))
    return checks


def after_profile_check(instance, driver, entry_id, mods_before, statuses_before):
    """The next start: the game runs with the values from before the switch and nothing is left to undo on it."""
    instance = Path(instance)
    driver = driver or {}
    switch = _settings({e.get("id"): e for e in history_entries(instance) or []}.get(entry_id))
    wanted = {k[len(VANILLA):]: c.get("before") for k, c in switch.items()}
    now = driver.get("settingsNow") or {}
    checks = [Check("the driver checked", driver.get("ok") is True, "error: {}".format(driver.get("error")))]
    checks.append(Check("the game runs with the values from before the switch", bool(wanted) and now == wanted,
                        "live values {}; expected {}".format(now, wanted)))
    problem = (driver.get("entryPlanAfterMeta") or {}).get("problem")
    checks.append(Check("nothing left to undo on the switch entry", driver.get("entryUndoableAfter") == 0 and problem is None,
                        "undoable items: {}; plan problem: {}".format(driver.get("entryUndoableAfter"), problem)))
    crashes = sorted(p.name for p in (instance / "crash-reports").glob("*")) if (instance / "crash-reports").is_dir() else []
    checks.append(Check("no crash report", not crashes, "crash-reports: {}".format(crashes)))
    after = listing(instance / "mods")
    checks.append(Check("mods unchanged by the relaunch", after == mods_before, "unchanged" if after == mods_before else _diff(mods_before, after)))
    statuses = history_statuses(instance)
    checks.append(Check("history.json statuses unchanged", statuses == statuses_before,
                        "unchanged" if statuses == statuses_before else "before {} after {}".format(statuses_before, statuses)))
    checks.append(_clean(instance))
    return checks
