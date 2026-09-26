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
import written

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


# --- The profile part of undo-after-restart (docs/v0.4/design/ws-h.md; plan review P-H1) ------------------------------
# A profile switch is an ordinary apply entry of setting changes (docs/research/v0.4/profiles.md; SPEC item 4); 0.4
# labels it in config/rigtune/profiles.json (SPEC C1: switches [{entryId, profileId, templateId, name}]). Vanilla keys
# are set at once; Sodium keys are staged and written by the helper at exit. Two switches before one restart put the
# same staged key in both (P-H1): with 0.4's same-key replacement the older switch's staged change is DISCARDED and the
# newer one's must start at the file's value; without it (0.3.x) both are applied in order. Either way each key's
# APPLIED changes must chain from its value before the first switch to the last switch's value.

VANILLA = "vanilla."
SODIUM = "sodium."
READABLE = (VANILLA, SODIUM)
SWITCH_STATUSES = ("APPLIED", "DISCARDED")


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


def _flatten(obj, prefix, out):
    """SodiumConfigPatcher.flatten: nested objects joined with '.', primitives as Gson's getAsString."""
    for key, value in obj.items():
        if isinstance(value, dict):
            _flatten(value, prefix + key + ".", out)
        elif isinstance(value, bool):
            out[prefix + key] = "true" if value else "false"
        elif value is not None and not isinstance(value, list):
            out[prefix + key] = str(value)
    return out


def setting_values(instance):
    """Every setting RigTune can read on the E2E instance, as the journal names it: vanilla.* from options.txt and
    sodium.* from config/sodium-options.json."""
    instance = Path(instance)
    values = {VANILLA + k: v for k, v in options_values(instance).items()}
    sodium = _load(instance / "config" / "sodium-options.json")
    if isinstance(sodium, dict):
        _flatten(sodium, SODIUM, values)
    return values


def _settings(entry):
    return [c for c in (entry or {}).get("changes", []) if c.get("type") == "setting"]


def _keys(entries):
    return list(dict.fromkeys(c.get("key") for e in entries for c in _settings(e)))


def _last_ok(instance):
    last = _load(Path(instance) / "config" / "rigtune" / "last-apply.json") or {}
    return [r.get("status") for r in last.get("results") or [] if r.get("status") != "OK"]


def profile_labels(instance, entry_ids, names, check_name):
    """profiles.json (WS-P) labels each entry with its profile's name."""
    data = _load(Path(instance) / "config" / "rigtune" / "profiles.json")
    switches = data.get("switches") if isinstance(data, dict) else None
    got = {s.get("entryId"): s.get("name") for s in switches or [] if isinstance(s, dict)}
    want = dict(zip(entry_ids, names))
    ok = len(entry_ids) == len(names) and all(got.get(e) == n for e, n in want.items())
    return Check(check_name, ok, "labels {}; expected {}".format({e: got.get(e) for e in entry_ids}, want))


def after_profile_apply(instance, driver, known_entry_ids, targets, labels):
    """Two switches in one start and the helper at exit. targets (the settings stand-in): per switch, the exact keys
    and values it set; None in profile mode, where the profiles decide. labels: the profile names profiles.json must
    give the entries (profile mode), or None. The values before the first switch are the driver's settingsBefore."""
    instance = Path(instance)
    driver = driver or {}
    count = len(targets) if targets is not None else len(labels or []) or 2
    checks = [Check("the driver switched {} times".format(count), driver.get("ok") is True and len(driver.get("applyMessages") or []) == count,
                    "error: {}; apply messages: {}".format(driver.get("error"), driver.get("applyMessages")))]
    new = [e for e in history_entries(instance) or [] if e.get("id") not in set(known_entry_ids)]
    ok = (len(new) == count and all(e.get("kind") == "apply" and _settings(e) and len(_settings(e)) == len(e.get("changes", []))
                                     and any(c.get("status") == "APPLIED" for c in _settings(e)) for e in new)
          and all((c.get("key") or "").startswith(READABLE) and c.get("status") in SWITCH_STATUSES for e in new for c in _settings(e)))
    checks.append(Check("history.json: two new apply entries of setting changes, applied (or discarded when replaced)", ok,
                        "new entries: {}".format([(e.get("kind"), [(c.get("key"), c.get("before"), c.get("after"), c.get("status"))
                                                                   for c in e.get("changes", [])]) for e in new])))
    before = driver.get("settingsBefore") or {}
    if targets is not None:
        got = [{c.get("key"): c.get("after") for c in _settings(e)} for e in new]
        checks.append(Check("history.json: each switch changed exactly its settings", got == list(targets),
                            "changed: {}; expected {}".format(got, list(targets))))
    final, broken = {}, {}
    for key in _keys(new):
        applied = [c for e in new for c in _settings(e) if c.get("key") == key and c.get("status") == "APPLIED"]
        final[key] = [c.get("after") for e in new for c in _settings(e) if c.get("key") == key][-1]
        chain = [before.get(key)] + [c.get("after") for c in applied]
        if not applied or [c.get("before") for c in applied] != chain[:-1] or chain[-1] != final[key]:
            broken[key] = [(c.get("before"), c.get("after"), c.get("status")) for e in new for c in _settings(e) if c.get("key") == key]
    checks.append(Check("history.json: each key's applied changes run from its value before the first switch to the last",
                        bool(final) and not broken, "broken chains (before, after, status): {}; values before: {}".format(
                            broken, {k: before.get(k) for k in final}) if broken else "{} key(s)".format(len(final))))
    values = setting_values(instance)
    wrong = {k: (values.get(k), v) for k, v in final.items() if values.get(k) != v}
    checks.append(Check("the settings hold the last switch's values", bool(final) and not wrong,
                        "(now, expected) where they differ: {}".format(wrong) if wrong else "{} value(s)".format(len(final))))
    checks.append(_clean(instance))
    failed = _last_ok(instance)
    checks.append(Check("last-apply.json: every op OK", not failed, "statuses not OK: {}".format(failed)))
    if labels is not None:
        checks.append(profile_labels(instance, [e.get("id") for e in new], labels, "profiles.json labels each switch entry"))
    return checks


def after_profile_undo(instance, driver, entry_ids, originals, labels, undo_all):
    """Undo last twice (the newer switch, then the older), or Undo all, in the start after the switches, and the helper
    at exit: every key back at its value before the first switch."""
    instance = Path(instance)
    driver = driver or {}
    entries = history_entries(instance) or []
    switches = [e for e in entries if e.get("id") in entry_ids]
    plans = driver.get("undoPlans") or []
    want = ["all"] if undo_all else list(reversed(entry_ids))
    name = "the driver undid everything (Undo all)" if undo_all else "the driver undid the newer switch, then the older (Undo last twice)"
    checks = [Check(name, driver.get("ok") is True and [p.get("undoOf") for p in plans] == want
                    and all(p.get("problem") is None and (p.get("items") or 0) > 0 for p in plans),
                    "error: {}; plans (undoOf, items, problem): {}; expected {}".format(
                        driver.get("error"), [(p.get("undoOf"), p.get("items"), p.get("problem")) for p in plans], want))]
    undos = [e for e in entries if e.get("kind") == "undo" and e.get("undoOf") in want]
    undo_changes = [c for e in undos for c in e.get("changes", [])]
    done = [c for e in switches for c in _settings(e) if c.get("status") != "DISCARDED"]
    ok = (sorted(e.get("undoOf") for e in undos) == sorted(want) and bool(done) and all(c.get("status") == "REVERTED" for c in done)
          and bool(undo_changes) and all(c.get("status") == "APPLIED" and c.get("reverts") in {d.get("id") for d in done} for c in undo_changes))
    checks.append(Check("history.json: the switches' changes REVERTED by undo entries, all applied", ok,
                        "switch changes: {}; undo entries: {}; undo changes: {}".format(
                            [(c.get("key"), c.get("status")) for c in done], [e.get("undoOf") for e in undos],
                            [(c.get("key"), c.get("after"), c.get("status"), c.get("reverts")) for c in undo_changes])))
    values = setting_values(instance)
    keys = _keys(switches)
    wrong = {k: (values.get(k), originals.get(k)) for k in keys if values.get(k) != originals.get(k)}
    checks.append(Check("every key is back at its value before the first switch", bool(keys) and not wrong,
                        "(now, before the switches) where they differ: {}".format(wrong) if wrong else "{} key(s)".format(len(keys))))
    checks.append(_clean(instance))
    failed = _last_ok(instance)
    checks.append(Check("last-apply.json: every op OK", not failed, "statuses not OK: {}".format(failed)))
    if labels is not None:
        checks.append(profile_labels(instance, entry_ids, labels, "profiles.json still labels each switch entry"))
    return checks


def after_profile_check(instance, driver, entry_ids, originals, mods_before, statuses_before):
    """The next start: the game runs with every key at its value before the first switch, nothing is left to undo."""
    instance = Path(instance)
    driver = driver or {}
    keys = _keys([e for e in history_entries(instance) or [] if e.get("id") in entry_ids])
    now = driver.get("settingsNow") or {}
    wrong = {k: (now.get(k), originals.get(k)) for k in keys if now.get(k) != originals.get(k)}
    checks = [Check("the driver checked", driver.get("ok") is True, "error: {}".format(driver.get("error")))]
    checks.append(Check("the game runs with every key at its value before the first switch", bool(keys) and not wrong,
                        "(live, before the switches) where they differ: {}".format(wrong) if wrong else "{} key(s)".format(len(keys))))
    undoable = driver.get("entryUndoable") or {}
    problems = driver.get("entryProblems") or {}
    checks.append(Check("nothing left to undo on either switch",
                        all(undoable.get(e) == 0 for e in entry_ids) and not any(problems.get(e) for e in entry_ids),
                        "undoable items: {}; plan problems: {}".format(undoable, problems)))
    crashes = sorted(p.name for p in (instance / "crash-reports").glob("*")) if (instance / "crash-reports").is_dir() else []
    checks.append(Check("no crash report", not crashes, "crash-reports: {}".format(crashes)))
    after = listing(instance / "mods")
    checks.append(Check("mods unchanged by the relaunch", after == mods_before, "unchanged" if after == mods_before else _diff(mods_before, after)))
    statuses = history_statuses(instance)
    checks.append(Check("history.json statuses unchanged", statuses == statuses_before,
                        "unchanged" if statuses == statuses_before else "before {} after {}".format(statuses_before, statuses)))
    checks.append(_clean(instance))
    return checks


# --- The downgrade run (docs/v0.4/SPEC.md AC3.2; plan review H-M1) ----------------------------------------------------
# The released 0.3.0 starts on files 0.4 wrote (the v040-written fixture sets, composed by written.py), undoes the last
# entry, applies a change of its own; then 0.4 starts again on what 0.3.0 left.

def rigtune_log_problems(text):
    """A RigTune ERROR line, a stack frame in RigTune's code, or a refusal of a file a newer RigTune wrote."""
    out = []
    for line in text.splitlines():
        if "/ERROR]" in line and re.search(r"rigtune", line, re.IGNORECASE) and "RigTune E2E" not in line:
            out.append(line)
        elif "at io.github.chaotix345.rigtune." in line and ".rigtune.e2e." not in line:
            out.append(line)
        elif "written by a newer RigTune" in line:
            out.append(line)
    return list(dict.fromkeys(out))


def _bad_or_crash(instance):
    config = Path(instance) / "config" / "rigtune"
    bad = sorted(p.name for p in config.iterdir() if ".bad" in p.name) if config.is_dir() else []
    crashes = sorted(p.name for p in (Path(instance) / "crash-reports").glob("*")) if (Path(instance) / "crash-reports").is_dir() else []
    return Check("no .bad file, no crash report", not bad and not crashes, ".bad: {}; crash-reports: {}".format(bad, crashes))


def _log_check(log_text):
    problems = rigtune_log_problems(log_text)
    return Check("latest.log: no RigTune ERROR, stack trace or refusal of a newer file", not problems,
                 "{} line(s): {}".format(len(problems), problems[:5]) if problems else "none")


def _view(driver):
    view = (driver or {}).get("history") or {}
    entries = view.get("entries") or []
    return view.get("state"), [e.get("id") for e in entries], [e.get("id") for e in entries if str(e.get("kindKey") or "").endswith(".unknown")]


def _results(instance):
    last = _load(Path(instance) / "config" / "rigtune" / "last-apply.json") or {}
    return last.get("results") or []


def after_downgrade_old(instance, driver, seeded, old_version, off_name, log_text):
    """0.3.0 on 0.4's files, and its helper at exit. seeded: self_update_e2e.seeded_state before the launch; off_name: the
    test mod jar 0.3.0's own Apply disables."""
    instance = Path(instance)
    mods = instance / "mods"
    driver = driver or {}
    checks = [Check("the driver ran the released 0.3.0", driver.get("ok") is True and driver.get("rigtuneVersion") == old_version,
                    "error: {}; loaded {}".format(driver.get("error"), driver.get("rigtuneVersion")))]
    checks.append(_log_check(log_text))
    state, ids, unknown = _view(driver)
    seeded_ids = [e.get("id") for e in seeded["entries"]]
    missing = [i for i in seeded_ids if i not in ids]
    checks.append(Check("History lists every entry 0.4 wrote (state OK)", state == "OK" and not missing and not unknown,
                        "state {}; {} of {} listed; missing {}; unknown kinds {}".format(state, len(seeded_ids) - len(missing),
                                                                                      len(seeded_ids), missing, unknown)))
    entries = history_entries(instance) or []
    by_id = {e.get("id"): e for e in entries}
    undo_of = seeded.get("undoLast")
    plan = driver.get("undoPlan") or {}
    undos = [e for e in entries if e.get("kind") == "undo" and e.get("undoOf") == undo_of and e.get("rigtuneVersion") == old_version]
    left = [c.get("id") for c in (by_id.get(undo_of) or {}).get("changes", []) if c.get("status") in ("APPLIED", "STAGED")]
    ok = (plan.get("undoOf") == undo_of and plan.get("problem") is None and bool(plan.get("items")) and len(undos) == 1
          and all(c.get("status") == "APPLIED" for c in undos[0].get("changes", [])) and undo_of in by_id and not left)
    checks.append(Check("Undo last reverted the newest undoable entry, recorded by 0.3.0", ok,
                        "plan: {}; expected undoOf {}; 0.3.0 undo entries of it: {}; its changes still applied or staged: {}".format(
                            plan, undo_of, [[(c.get("key") or c.get("file"), c.get("status")) for c in e.get("changes", [])] for e in undos], left)))
    results = _results(instance)
    disabled = [r for r in results if (r.get("op") or {}).get("type") == "DISABLE_FILE" and _name((r.get("op") or {}).get("path")) == off_name]
    journaled = [c for e in entries if e.get("kind") == "apply" and e.get("rigtuneVersion") == old_version for c in e.get("changes", [])
                 if c.get("action") == "disable" and c.get("file") == off_name and c.get("status") == "APPLIED"]
    checks.append(Check("0.3.0's own Apply staged and applied (disable {})".format(off_name),
                        [r.get("status") for r in disabled] == ["OK"] and (mods / (off_name + ".disabled")).is_file()
                        and not (mods / off_name).exists() and len(journaled) == 1,
                        "last-apply: {}; {}.disabled: {}; journaled by 0.3.0: {}".format([r.get("status") for r in disabled], off_name,
                                                                                         (mods / (off_name + ".disabled")).is_file(), len(journaled))))
    ops = seeded.get("pendingOps") or []
    status_by_id = {(r.get("op") or {}).get("id"): r.get("status") for r in results}
    staged = [c for e in entries for c in e.get("changes", []) if c.get("opId") in {op.get("id") for op in ops}]
    pending = instance / "config" / "rigtune" / "pending.json"
    checks.append(Check("0.4's staged ops (with projectId) applied by 0.3.0's helper",
                        bool(ops) and all(status_by_id.get(op.get("id")) == "OK" for op in ops) and bool(staged)
                        and all(c.get("status") == "APPLIED" for c in staged) and not pending.exists(),
                        "ops {} -> {}; their journal changes {}; pending.json left: {}".format(
                            [op.get("id") for op in ops], [status_by_id.get(op.get("id")) for op in ops],
                            [c.get("status") for c in staged], pending.exists())))
    now = {name: digest(instance / "config" / "rigtune" / name, "sha256") if (instance / "config" / "rigtune" / name).is_file() else None
           for name in seeded["newFiles"]}
    changed = sorted(n for n in now if now[n] != seeded["newFiles"][n])
    checks.append(Check("the files only 0.4 writes are byte-identical", not changed,
                        "changed: {}".format(changed) if changed else "{} file(s): {}".format(len(now), sorted(now))))
    checks.append(_bad_or_crash(instance))
    return checks


def _item_key(item):
    if isinstance(item, dict):
        return item.get("id") or item.get("at") or json.dumps(item, sort_keys=True)
    return json.dumps(item, sort_keys=True)


def _lost(seed, now, fields):
    """The seeded items (list entries by id/at, dict keys) of these fields that `now` no longer has."""
    lost = {}
    for field in fields:
        before, after = (seed or {}).get(field), (now or {}).get(field)
        if isinstance(before, dict):
            gone = [k for k in before if not isinstance(after, dict) or k not in after]
        else:
            kept = {_item_key(i) for i in after} if isinstance(after, list) else set()
            gone = [_item_key(i) for i in before or [] if _item_key(i) not in kept]
        if gone:
            lost[field] = gone
    return lost


def after_downgrade_new(instance, driver, new_jar, seeded, log_text, kept=None):
    """0.4 starts again on what 0.3.0 left: it loads, logs no RigTune error, lists the journal, keeps the profile labels
    of entries 0.3.0 kept, and reads its own files back. kept: file -> fields (written.KEPT)."""
    instance, new_jar = Path(instance), Path(new_jar)
    config = instance / "config" / "rigtune"
    driver = driver or {}
    version = e2e_env.mod_json(new_jar)["version"]
    origin = driver.get("rigtuneOrigin") or []
    checks = [Check("0.4 loaded from mods/ again", driver.get("ok") is True and driver.get("rigtuneVersion") == version
                    and [_norm(p) for p in origin] == [_norm(instance / "mods" / new_jar.name)],
                    "error: {}; loaded {} from {}".format(driver.get("error"), driver.get("rigtuneVersion"), origin))]
    checks.append(_log_check(log_text))
    checks.append(_bad_or_crash(instance))
    state, ids, unknown = _view(driver)
    journal = [e.get("id") for e in history_entries(instance) or []]
    checks.append(Check("History lists every entry of history.json (state OK)", state == "OK" and sorted(ids) == sorted(journal) and not unknown,
                        "state {}; listed {} of {}; unknown kinds {}".format(state, len(ids), len(journal), unknown)))
    seeded_switches = [s for s in ((seeded.get("profiles") or {}).get("switches") or []) if isinstance(s, dict)]
    now = _load(config / "profiles.json") or {}
    labels = {s.get("entryId"): s.get("name") for s in now.get("switches") or [] if isinstance(s, dict)}
    expected = {s.get("entryId"): s.get("name") for s in seeded_switches if s.get("entryId") in journal}
    checks.append(Check("profiles.json still labels the switch entries 0.3.0 kept", bool(expected) and all(labels.get(k) == v for k, v in expected.items()),
                        "expected {}; labels now {}".format(expected, labels)))
    lost = {}
    for name, fields in (kept or written.KEPT).items():
        if name in seeded.get("json", {}):
            current = _load(config / name) if (config / name).is_file() else None
            gone = _lost(seeded["json"][name], current, fields)
            if current is None or gone:
                lost[name] = gone or "missing"
    checks.append(Check("0.4 read its own files back (none reset or moved to .bad)", not lost,
                        "lost: {}".format(lost) if lost else "{} file(s) kept their items".format(len(seeded.get("json", {})))))
    return checks
