"""The "written by" fixture sets (docs/v0.4/SPEC.md amendment H-M1; docs/v0.5/SPEC.md 3b, X11): files a version writes,
committed by each feature workstream under src/test/resources/v0N0-written/<set>/ (placeholders in placeholder/<set>/
until then; see the README there). One generation per root: v040-written (0.4) and v050-written (0.5). The released-jar
compatibility harnesses (compat030.py, compat040.py) and the downgrade runs compose them into one instance's
config/rigtune/: a 0.5 instance holds what 0.4 wrote too, so the roots are composed oldest first."""

import copy
import json
import re
import shutil
from dataclasses import dataclass, field
from pathlib import Path

import e2e_env
import fixtures

# One fixture set per owner (PLAN hotspots: src/test/resources/v040-written/).
SETS = ("ws-a", "ws-p", "ws-b", "ws-s", "ws-w", "ws-f")
# Files only 0.4 writes and reads: a downgrade to 0.3.0 must leave them byte-identical.
NEW_FILES = ("profiles.json", "stutter.json", "server-limits.json", "awareness.json", "startup-times.json")
HISTORY = "history.json"
# What 0.4 must still hold when it starts again after a downgrade (it may add to them): per file, top-level collections
# whose seeded items (by id or at, else whole) must all still be there. awareness.json's lastSeen* fields and the
# fingerprint are refreshed at every start, so only its user decisions are compared.
KEPT = {"stutter.json": ("sessions",), "startup-times.json": ("runs",), "server-limits.json": ("servers",),
        "awareness.json": ("dismissed", "acknowledgedRegressions"), "benchmarks.json": ("runs",)}


@dataclass(frozen=True)
class Generation:
    """One fixture root: its folder name, the version that writes it, its sets (PLAN), the files that version was the
    first to write (an older version never reads them, so a downgrade must leave them byte-identical) and what that
    version must still hold when it starts again after a downgrade (see KEPT)."""
    name: str
    release: tuple
    sets: tuple
    new_files: tuple
    kept: dict = field(default_factory=dict)


V040 = Generation("v040-written", (0, 4), SETS, NEW_FILES, KEPT)
# The sets in src/test/resources/v050-written/README.md's order (docs/v0.5/PLAN.md contracts item 17, plan review PLAN-20).
# 0.5's new files join `kept` as their formats land.
V050 = Generation("v050-written", (0, 5), ("ws-l1", "ws-l2", "ws-s", "ws-s2", "ws-p", "ws-p2", "ws-b", "ws-t", "ws-w", "ws-w2",
                                           "ws-f", "ws-h"),
                  ("stutter-fixes.json", "tryit.json", "server-profiles.json"))
GENERATIONS = (V040, V050)
# A set's compat040 expectations (the v050-written README): never composed into the instance.
EXPECT = "expect.json"
# A differing value of these is a format bump, never a merge.
FORMAT_KEYS = ("formatVersion", "schemaVersion")


@dataclass(frozen=True)
class FixtureSet:
    name: str
    folder: Path
    placeholder: bool
    generation: str = V040.name


def generation_of(root):
    """The generation a root holds, by its folder name; any other name reads as v0.4's (the v0.4 tests' temp roots)."""
    return next((g for g in GENERATIONS if Path(root).name == g.name), V040)


def resolve(root, generation=None):
    """Each owner's set: the real one (root/<set>) when it exists, else the placeholder (root/placeholder/<set>)."""
    root = Path(root)
    generation = generation or generation_of(root)
    out = []
    for name in generation.sets:
        if (root / name).is_dir():
            out.append(FixtureSet(name, root / name, False, generation.name))
        elif (root / "placeholder" / name).is_dir():
            out.append(FixtureSet(name, root / "placeholder" / name, True, generation.name))
    return out


def resolve_all(roots):
    """Every root's sets, oldest generation first (a later set's value wins a merge); a missing root adds nothing."""
    ordered = sorted((Path(r) for r in roots), key=lambda r: GENERATIONS.index(generation_of(r)))
    return [s for root in ordered if root.is_dir() for s in resolve(root)]


def new_files_for(old_version):
    """The files every generation newer than old_version ("0.4.0+mc26.2") introduced: that version never reads them."""
    release = tuple(int(p) for p in re.findall(r"\d+", old_version.split("+", 1)[0])[:2])
    return tuple(name for g in GENERATIONS if g.release > release for name in g.new_files)


def kept_for(sets):
    """What the newer version must still hold after a downgrade: every present generation's KEPT."""
    present = {s.generation for s in sets}
    out = {}
    for g in GENERATIONS:
        if g.name in present:
            out.update(g.kept)
    return out


def expectations(sets):
    """Set name -> its expect.json, for the sets that have one."""
    out = {}
    for fixture in sets:
        path = fixture.folder / EXPECT
        if path.is_file():
            if fixture.name in out:
                raise ValueError("two sets named {} have an {}".format(fixture.name, EXPECT))
            out[fixture.name] = path
    return out


def compose(sets, instance, conflicts=None):
    """Writes every set's files into instance/config/rigtune: history.json's entries merged from every set by `at`,
    pending.json's ops from every set, benchmarks.json's runs from every set (run ids unique), any other file several
    sets provide deep-merged (objects key by key, lists
    without exact duplicates, a scalar from the later set, each such override appended to `conflicts` as (file, key
    path, earlier set, later set)); a file one set provides keeps its bytes. ${INSTANCE} paths are filled in. Returns
    file name -> the sets it came from."""
    conflicts = [] if conflicts is None else conflicts
    config = Path(instance) / "config" / "rigtune"
    config.mkdir(parents=True, exist_ok=True)
    sources = {}
    for fixture in sets:
        for path in sorted(p for p in fixture.folder.iterdir() if p.is_file() and p.suffix == ".json" and p.name != EXPECT):
            sources.setdefault(path.name, []).append((fixture.name, path))
    for name, provided in sources.items():
        if name == HISTORY:
            entries, versions = [], set()
            for _, path in provided:
                data = json.loads(path.read_text(encoding="utf-8"))
                entries += data.get("entries") or []
                versions.add(data.get("formatVersion"))
            ids = [e.get("id") for e in entries]
            if len(set(ids)) != len(ids):
                raise ValueError("history.json entry ids repeat across sets {}: {}".format([s for s, _ in provided], ids))
            if len(versions) != 1:
                raise ValueError("history.json formatVersion differs across sets {}: {}".format([s for s, _ in provided], versions))
            # A set's formatVersion is kept, so a bump reaches 0.3.0 (which must then refuse, and the check fails).
            text = json.dumps({"formatVersion": versions.pop(), "entries": sorted(entries, key=lambda e: e.get("at") or "")},
                              indent=2) + "\n"
            (config / name).write_text(fixtures.instantiate_json(text, instance) if fixtures.TOKEN in text else text,
                                       encoding="utf-8", newline="\n")
        elif name == "pending.json" and len(provided) > 1:
            # A profile switch stages config patches as well as ws-a's download: one plan with every set's ops.
            plans = [json.loads(path.read_text(encoding="utf-8")) for _, path in provided]
            merged = dict(plans[0], ops=[op for plan in plans for op in plan.get("ops") or []])
            ids = [op.get("id") for op in merged["ops"]]
            if len(set(ids)) != len(ids):
                raise ValueError("pending.json op ids repeat across sets {}: {}".format([s for s, _ in provided], ids))
            text = json.dumps(merged, indent=2) + "\n"
            (config / name).write_text(fixtures.instantiate_json(text, instance) if fixtures.TOKEN in text else text,
                                       encoding="utf-8", newline="\n")
        elif name == "benchmarks.json" and len(provided) > 1:
            runs = [run for _, path in provided for run in json.loads(path.read_text(encoding="utf-8")).get("runs") or []]
            ids = [run.get("id") for run in runs]
            if len(set(ids)) != len(ids):
                raise ValueError("benchmarks.json run ids repeat across sets {}: {}".format([s for s, _ in provided], ids))
            owners, merged = {}, None
            for set_name, path in provided:
                data = {k: v for k, v in json.loads(path.read_text(encoding="utf-8")).items() if k != "runs"}
                merged = _claim("", data, set_name, owners) if merged is None else _merge(name, "", merged, data, set_name, conflicts, owners)
            text = json.dumps(dict(merged, runs=runs), indent=2) + "\n"
            (config / name).write_text(fixtures.instantiate_json(text, instance) if fixtures.TOKEN in text else text,
                                       encoding="utf-8", newline="\n")
        elif len(provided) > 1:
            owners = {}
            merged = None
            for set_name, path in provided:
                data = json.loads(path.read_text(encoding="utf-8"))
                merged = _claim("", data, set_name, owners) if merged is None else _merge(name, "", merged, data, set_name, conflicts, owners)
            text = json.dumps(merged, indent=2) + "\n"
            (config / name).write_text(fixtures.instantiate_json(text, instance) if fixtures.TOKEN in text else text,
                                       encoding="utf-8", newline="\n")
        else:
            path = provided[0][1]
            text = path.read_text(encoding="utf-8")
            if fixtures.TOKEN in text:
                (config / name).write_text(fixtures.instantiate_json(text, instance), encoding="utf-8", newline="\n")
            else:
                shutil.copyfile(path, config / name)
    return {name: [s for s, _ in provided] for name, provided in sorted(sources.items())}


def _claim(path, value, owner, owners):
    """A value one set brought in: every scalar in it belongs to that set."""
    if isinstance(value, dict):
        return {k: _claim(path + "." + k if path else k, v, owner, owners) for k, v in value.items()}
    if not isinstance(value, list):
        owners[path] = owner
    return copy.deepcopy(value)


def _merge(file, path, old, new, owner, conflicts, owners):
    if isinstance(old, dict) and isinstance(new, dict):
        out = dict(old)
        for key, value in new.items():
            sub = path + "." + key if path else key
            out[key] = _merge(file, sub, old[key], value, owner, conflicts, owners) if key in old else _claim(sub, value, owner, owners)
        return out
    if isinstance(old, list) and isinstance(new, list):
        return old + [copy.deepcopy(item) for item in new if item not in old]
    if old != new:
        if path in FORMAT_KEYS:
            raise ValueError("{} {} differs across sets {} and {}: {} vs {}".format(file, path, owners.get(path), owner, old, new))
        conflicts.append((file, path, owners.get(path), owner))
    owners[path] = owner
    return copy.deepcopy(new)


def materialize(instance):
    """The files the composed pending.json's ops act on, so a helper can apply them: a minimal mod jar (fabric.mod.json
    only, the op's mod id) for every DISABLE_FILE path and ENABLE_FILE source, and an empty config file for every
    PATCH_* target. Files already there are left alone."""
    instance = Path(instance)
    pending = instance / "config" / "rigtune" / "pending.json"
    for op in (json.loads(pending.read_text(encoding="utf-8")).get("ops") or []) if pending.is_file() else []:
        kind = op.get("type") or ""
        source = op.get("path") if kind in ("DISABLE_FILE",) or kind.startswith("PATCH_") else op.get("from")
        if not source or Path(source).exists():
            continue
        target = Path(source)
        target.parent.mkdir(parents=True, exist_ok=True)
        if kind == "PATCH_JSON":
            target.write_text("{}\n", encoding="utf-8", newline="\n")
        elif kind.startswith("PATCH_"):
            target.write_text("", encoding="utf-8")
        else:
            e2e_env.test_mod_jar(target, op.get("modId") or "e2e-unknown")


def instance_state(instance):
    """What the instance must hold to match the composed journal: jars (path relative to the instance -> mod id), each
    mod as its latest applied file change left it, plus every pending ENABLE_FILE download; options (options.txt key ->
    value) and the sodium-options.json / iris.properties contents with the latest applied value of every vanilla,
    sodium.* and iris.* setting change (other config keys have no file here)."""
    instance = Path(instance)
    config = instance / "config" / "rigtune"
    by_mod = {}
    options, sodium, iris = {}, {}, {}
    history = config / HISTORY
    for entry in (json.loads(history.read_text(encoding="utf-8")).get("entries") or []) if history.is_file() else []:
        for change in entry.get("changes") or []:
            if change.get("status") != "APPLIED":
                continue
            key = change.get("key") or ""
            if change.get("type") == "setting" and key.startswith("vanilla."):
                options[key[len("vanilla."):]] = change.get("after")
            elif change.get("type") == "setting" and key.startswith("sodium."):
                *path, leaf = key[len("sodium."):].split(".")
                node = sodium
                for part in path:
                    node = node.setdefault(part, {})
                node[leaf] = _json_value(change.get("after"))
            elif change.get("type") == "setting" and key.startswith("iris."):
                iris[key[len("iris."):]] = change.get("after")
            elif change.get("type") == "file" and change.get("file"):
                name = change["file"] if change.get("action") == "enable" else change.get("resultFile") or change["file"] + ".disabled"
                by_mod[change.get("modId") or change["file"]] = ("mods/" + name, change.get("modId"))
    jars = dict(by_mod.values())
    pending = config / "pending.json"
    for op in (json.loads(pending.read_text(encoding="utf-8")).get("ops") or []) if pending.is_file() else []:
        if op.get("type") == "ENABLE_FILE" and op.get("from"):
            jars[Path(op["from"]).resolve().relative_to(instance.resolve()).as_posix()] = op.get("modId")
    return {"jars": jars, "options": options, "sodium": sodium, "iris": iris}


def _json_value(text):
    """A setting value as Sodium's JSON has it: booleans and whole numbers unquoted, anything else a string."""
    if text in ("true", "false"):
        return text == "true"
    if text is not None and text.lstrip("-").isdigit():
        return int(text)
    return text
