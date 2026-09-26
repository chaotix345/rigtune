"""The "written by 0.4" fixture sets (docs/v0.4/SPEC.md amendment H-M1): files 0.4 writes, committed by each feature
workstream under src/test/resources/v040-written/<set>/ (placeholders in placeholder/<set>/ until then; see the README
there). The released-jar compatibility harness (compat030.py) and the downgrade-040-to-030 run compose them into one
instance's config/rigtune/."""

import json
import shutil
from dataclasses import dataclass
from pathlib import Path

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
class FixtureSet:
    name: str
    folder: Path
    placeholder: bool


def resolve(root):
    """Each owner's set: the real one (root/<set>) when it exists, else the placeholder (root/placeholder/<set>)."""
    root = Path(root)
    out = []
    for name in SETS:
        if (root / name).is_dir():
            out.append(FixtureSet(name, root / name, False))
        elif (root / "placeholder" / name).is_dir():
            out.append(FixtureSet(name, root / "placeholder" / name, True))
    return out


def compose(sets, instance):
    """Writes every set's files into instance/config/rigtune: history.json's entries merged from every set by `at`,
    every other file from exactly one set, ${INSTANCE} paths filled in (other files keep their bytes). Returns file
    name -> the sets it came from."""
    config = Path(instance) / "config" / "rigtune"
    config.mkdir(parents=True, exist_ok=True)
    sources = {}
    for fixture in sets:
        for path in sorted(p for p in fixture.folder.iterdir() if p.is_file() and p.suffix == ".json"):
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
        elif len(provided) > 1:
            raise ValueError("{} comes from more than one set: {}".format(name, [s for s, _ in provided]))
        else:
            path = provided[0][1]
            text = path.read_text(encoding="utf-8")
            if fixtures.TOKEN in text:
                (config / name).write_text(fixtures.instantiate_json(text, instance), encoding="utf-8", newline="\n")
            else:
                shutil.copyfile(path, config / name)
    return {name: [s for s, _ in provided] for name, provided in sorted(sources.items())}


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
