#!/usr/bin/env python3
"""Checks that rules/rules-v1.json is safe for 0.1.x clients: schemaVersion 1, only fields, condition keys and
values 0.1.0 understands, only vanilla./sodium. settings keys, and exactly the v1 projection of
rules/source/knowledge.json with rules/rules-v2.json's generated data (so both files come from one updater run).
Runs offline. See tools/README.md.
"""

import argparse
import json
import sys
from pathlib import Path

import update_rules as ur

V1_TOP_LEVEL = frozenset({
    "schemaVersion", "revision", "generatedAt", "minModVersion", "gpuTiers", "gpuVendorFallback", "cpuTiers",
    "heapTiers", "mods", "obsolete", "settings", "advice", "availability", "upstream",
})


def shape_problems(v1):
    problems = []
    if v1.get("schemaVersion") != 1:
        problems.append(f"rules-v1.json: schemaVersion must be 1, not {v1.get('schemaVersion')!r}")
    unknown = sorted(set(v1) - V1_TOP_LEVEL)
    if unknown:
        problems.append(f"rules-v1.json: top-level field(s) 0.1.x doesn't know: {', '.join(unknown)}")
    for kind in ur.RULE_KINDS + ur.TIER_KINDS:
        for i, rule in enumerate(v1.get(kind, [])):
            label = f"rules-v1.json {ur.rule_label(kind, rule, i)}"
            extra = sorted(set(rule) - ur.V1_RULE_FIELDS[kind])
            if extra:
                problems.append(f"{label}: field(s) 0.1.x doesn't know: {', '.join(extra)}")
            for field in ur.CONDITION_FIELDS.get(kind, ()):
                if field in rule:
                    problems += [f"{label}: {p}" for p in ur.condition_problems(rule[field], ur.V1_CONDITION_KEYS, field, ur.V1_VOCABULARIES)]
            if kind == "settings" and not str(rule.get("key")).startswith(ur.V1_SETTING_PREFIXES):
                problems.append(f"{label}: settings key outside vanilla./sodium.")
    return problems


def projection_problems(knowledge, v2, v1):
    problems = []
    upstream_by_slug = {m.get("slug"): m.get("upstream") for m in v2.get("mods", [])}
    mods = []
    for mod in knowledge.get("mods", []):
        if mod["slug"] not in upstream_by_slug:
            return [f"rules-v2.json has no mod {mod['slug']!r} from knowledge.json; regenerate with tools/update_rules.py"]
        merged = dict(mod)
        merged["upstream"] = upstream_by_slug[mod["slug"]]
        mods.append(merged)
    content = ur.assemble_content(knowledge, mods, v2.get("availability", {}), v2.get("upstream", {}))
    if not ur.deep_equal(ur.strip_meta(v2), ur.v2_content(content)):
        problems.append("rules-v2.json doesn't match knowledge.json; regenerate with tools/update_rules.py")
    expected_v1, _ = ur.v1_projection(content)
    if not ur.deep_equal(ur.strip_meta(v1), expected_v1):
        problems.append("rules-v1.json isn't the v1 projection of knowledge.json + rules-v2.json; regenerate with tools/update_rules.py")
    if v1.get("revision") != v2.get("revision") or v1.get("generatedAt") != v2.get("generatedAt"):
        problems.append("rules-v1.json and rules-v2.json must share revision and generatedAt (one updater run)")
    return problems


def check(repo_root):
    repo_root = Path(repo_root)
    paths = {
        "knowledge.json": repo_root / "rules" / "source" / "knowledge.json",
        "rules-v2.json": repo_root / "rules" / "rules-v2.json",
        "rules-v1.json": repo_root / "rules" / "rules-v1.json",
    }
    docs = {}
    problems = []
    for name, path in paths.items():
        try:
            docs[name] = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as e:
            problems.append(f"{name}: can't read {path}: {e}")
    if problems:
        return problems
    v1 = docs["rules-v1.json"]
    problems += shape_problems(v1)
    try:
        ur.validate_knowledge(docs["knowledge.json"])
    except ur.KnowledgeError as e:
        return problems + [f"knowledge.json: {e}"]
    return problems + projection_problems(docs["knowledge.json"], docs["rules-v2.json"], v1)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", default=str(Path(__file__).resolve().parent.parent),
                        help="Repository root (default: the parent of tools/)")
    args = parser.parse_args(argv)
    problems = check(args.repo_root)
    for problem in problems:
        print(f"error: {problem}", file=sys.stderr)
    if problems:
        return 1
    print("rules-v1.json is v1-compatible and matches the projection of rules-v2.json")
    return 0


if __name__ == "__main__":
    sys.exit(main())
