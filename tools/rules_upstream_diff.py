#!/usr/bin/env python3
"""Compares the upstream data two generated rules files carry: `availability`, the top-level `upstream` lists and each
mod's `upstream` flags. `revision`, `generatedAt` and everything that comes from knowledge.json are ignored, and so are
mods only one file has (their availability too), since those come from a knowledge.json edit, not from upstream.

Used for the weekly rules PR during a release (docs/v0.5/SPEC.md 2T, AC2T.2): the integration branch's regenerated
rules-v2.json must carry every upstream change the bot's PR found:

    python tools/rules_upstream_diff.py <the bot PR's rules-v2.json> rules/rules-v2.json

Exit 0 when the upstream data is the same, 1 when it differs (each difference printed), 2 when a file can't be read.
"""

import argparse
import json
import sys
from pathlib import Path


def differences(first, second):
    out = []
    first_mods = {m.get("slug"): m for m in first.get("mods", [])}
    second_mods = {m.get("slug"): m for m in second.get("mods", [])}
    common = set(first_mods) & set(second_mods)
    first_availability, second_availability = first.get("availability", {}), second.get("availability", {})
    for version in sorted(set(first_availability) | set(second_availability)):
        if version not in second_availability:
            out.append(f"availability {version}: only in the first file")
        elif version not in first_availability:
            out.append(f"availability {version}: only in the second file")
        else:
            a, b = set(first_availability[version]) & common, set(second_availability[version]) & common
            out += [f"availability {version}: {slug} only in the first file" for slug in sorted(a - b)]
            out += [f"availability {version}: {slug} only in the second file" for slug in sorted(b - a)]
    first_upstream, second_upstream = first.get("upstream", {}), second.get("upstream", {})
    for pack in sorted(set(first_upstream) | set(second_upstream)):
        a, b = first_upstream.get(pack) or {}, second_upstream.get(pack) or {}
        if a.get("mcVersion") != b.get("mcVersion"):
            out.append(f"upstream {pack}: mcVersion {a.get('mcVersion')} vs {b.get('mcVersion')}")
        out += [f"upstream {pack}: {slug} only in the first file" for slug in sorted(set(a.get("slugs", [])) - set(b.get("slugs", [])))]
        out += [f"upstream {pack}: {slug} only in the second file" for slug in sorted(set(b.get("slugs", [])) - set(a.get("slugs", [])))]
    for slug in sorted(common):
        a, b = first_mods[slug].get("upstream"), second_mods[slug].get("upstream")
        if a != b:
            out.append(f"mods[{slug}].upstream: {dict(sorted((a or {}).items()))} vs {dict(sorted((b or {}).items()))}")
    return out


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("first", help="a generated rules file (e.g. the bot PR's rules-v2.json)")
    parser.add_argument("second", help="another generated rules file (e.g. the integration branch's rules/rules-v2.json)")
    args = parser.parse_args(argv)
    try:
        docs = [json.loads(Path(name).read_text(encoding="utf-8")) for name in (args.first, args.second)]
    except (OSError, json.JSONDecodeError) as e:
        print(f"error: {e}", file=sys.stderr)
        return 2
    found = differences(*docs)
    for line in found:
        print(line)
    if not found:
        print("the upstream data is the same")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main())
