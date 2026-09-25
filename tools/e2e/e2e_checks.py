"""Assertions of the self-update end-to-end test (SPEC item 5), over the files a run leaves in the scratch instance.
Pure: no processes, no network."""

import hashlib
import json
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
    return str(path).replace("\\", "/").rstrip("/").lower()


def _inside(path, directory):
    return _norm(path).startswith(_norm(directory) + "/")


def _load(path):
    path = Path(path)
    return json.loads(path.read_text(encoding="utf-8")) if path.is_file() else None


def after_update(instance, old_jar, new_jar, driver, server_log, helper_cmdlines, separator=";"):
    """SPEC 5.5 and AC5.2, after the old version applied the update and the post-exit helper finished."""
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

    pending = rigtune_dir / "pending.json"
    checks.append(Check("no pending.json", not pending.exists(), "pending.json exists: {}".format(pending.exists())))
    leftovers = sorted(p.name for p in mods.iterdir() if p.name.endswith(".rigtune-pending"))
    checks.append(Check("no leftover downloads", not leftovers, "*.rigtune-pending: {}".format(leftovers)))

    last = _load(rigtune_dir / "last-apply.json") or {}
    results = last.get("results") or []
    summary = [(r.get("op", {}).get("type"), Path(r.get("op", {}).get("path") or r.get("op", {}).get("to") or "").name,
                r.get("status")) for r in results]
    expected = sorted([("DISABLE_FILE", old_jar.name, "OK"), ("ENABLE_FILE", new_jar.name, "OK")])
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
    """Plan review M12: a stricter `depends` in the update could leave the game unable to start after the post-exit
    helper swaps the jars, with no RigTune left to undo it. Any added or changed entry needs a human look."""
    old = e2e_env.mod_json(old_jar).get("depends") or {}
    new = e2e_env.mod_json(new_jar).get("depends") or {}
    changes = ["{}: {} -> {}".format(key, old.get(key, "(absent)"), value) for key, value in sorted(new.items())
               if old.get(key) != value]
    return Check("the update's depends are no stricter (M12)", not changes,
                 "; ".join(changes) if changes else "same or fewer entries: {}".format(new))


def after_verify(instance, new_jar, driver, last_apply_finished_at, mods_before, expect_history):
    """SPEC 5.6: the new version, relaunched on the same instance, reads the old version's state."""
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

    if expect_history:
        history = _load(rigtune_dir / "history.json")
        imports = [e for e in (history or {}).get("entries", []) if e.get("kind") == "legacy-import"]
        own = [c for e in imports for c in e.get("changes", [])
               if c.get("modId") == "rigtune" or str(c.get("file") or "").lower().startswith("rigtune")]
        checks.append(Check("history.json: legacy import without RigTune's own jars", history is not None and not own,
                            "history.json exists: {}; legacy-import entries: {}; RigTune changes: {}".format(
                                history is not None, len(imports), own)))
    return checks
