"""Prints the self-update E2E matrix for e2e.yml, per tier (docs/v0.5/SPEC.md 3a, AC3a.2), from one table.

Old jars, their GitHub release tags and sha256 come from self_update_e2e.RELEASED (a version without "+mc<node>" is
26.2's: 0.1.0 had no other node); the nodes from versions/*/ (tools/gametest_matrix.py), so a new node gets its rows
without an edit here. The push tier is each node's newest release updating to the new jar; the release tier is every
row (a superset of push). `args` is shell-quoted for the harness (tools/e2e/self_update_e2e.py); the workflow adds
--name, --mc, --new-jar, --work, --evidence, --lock none and, for a row with an old jar, --old-jar.

    python tools/e2e/e2e_matrix.py --tier push|release [--root <repo>]
    python tools/e2e/e2e_matrix.py --nodes   ->  ["26.2","26.3"]
    ->  {"include": [{"id": "upgrade-from-0.4.0", "mc": "26.2", "old": "0.4.0+mc26.2", "tag": "v0.4.0",
                      "asset": "rigtune-0.4.0+mc26.2.jar", "sha256": "…", "args": "--expect-history auto"}, ...]}
"""

import argparse
import json
import re
import shlex
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))

import gametest_matrix  # noqa: E402
import self_update_e2e  # noqa: E402

TIERS = ("push", "release")
# 0.1.0's fabric.mod.json names no node; it was built for 26.2 only.
DEFAULT_NODE = "26.2"
# Seeds (tools/e2e/seeds/<name>): the released version whose state they are, and its node.
SEEDS = {"v010-dh": "0.1.0"}
# AC2H.6: seeds the new version starts on directly (--scenario stale-seed), on the node their state comes from.
STALE_SEEDS = {"v010-dh-app-reinstalled": DEFAULT_NODE, "v010-dh-app-reinstalled-disabled": DEFAULT_NODE}
# The release tier's rows with no old jar, and the downgrade targets, per node.
UNDO = (("undo-profiles", ["--scenario", "undo", "--profile-switch", "profile", "--profile-names", "Battery,Max FPS"]),
        ("undo-settings", ["--scenario", "undo", "--profile-switch", "settings"]),
        ("helper-kill", ["--scenario", "helper-kill"]))
DOWNGRADE_TO = ("0.4.0", "0.3.0")


def node_of(version):
    return version.split("+mc", 1)[1] if "+mc" in version else DEFAULT_NODE


def core(version):
    return version.split("+", 1)[0]


def _key(version):
    return tuple(int(part) for part in re.findall(r"\d+", core(version)))


def _row(row_id, mc, args, old=None):
    asset, sha256 = self_update_e2e.RELEASED[old] if old else ("", "")
    return {"id": row_id, "mc": mc, "old": old or "", "tag": "v" + core(old) if old else "", "asset": asset, "sha256": sha256,
            "args": shlex.join(args)}


def rows(root, tier):
    """The tier's rows, node by node in version order."""
    if tier not in TIERS:
        raise ValueError("unknown tier " + tier)
    out = []
    for mc in gametest_matrix.nodes(root):
        releases = sorted((v for v in self_update_e2e.RELEASED if node_of(v) == mc), key=_key, reverse=True)
        for version in releases:
            args = (["--legacy-disable"] if _key(version) < (0, 2) else []) + ["--expect-history", "auto"]
            row = _row("upgrade-from-" + core(version), mc, args, version)
            if tier == "release" or version == releases[0]:
                out.append(row)
        if tier == "push":
            continue
        for seed, version in SEEDS.items():
            if version in releases:
                out.append(_row("seeded-" + seed, mc, ["--seed", "tools/e2e/seeds/" + seed, "--expect-history", "auto"], version))
        out += [_row("seeded-" + seed, mc, ["--scenario", "stale-seed", "--seed", "tools/e2e/seeds/" + seed])
                for seed, node in STALE_SEEDS.items() if node == mc]
        out += [_row(row_id, mc, args) for row_id, args in UNDO]
        if mc == DEFAULT_NODE:
            # AC4j.3: the launcher-brand leg, 26.2 only (SPEC 3a's release tier).
            out.append(_row("brand-theseus", mc, ["--scenario", "brand"]))
        for target in DOWNGRADE_TO:
            old = next((v for v in releases if core(v) == target), None)
            if old:
                out.append(_row("downgrade-to-" + target, mc, ["--scenario", "downgrade"], old))
    return out


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    what = parser.add_mutually_exclusive_group(required=True)
    what.add_argument("--tier", choices=TIERS)
    # The nodes alone, for the release tier's one-job-per-node legs (e2e.yml's stutter script).
    what.add_argument("--nodes", action="store_true")
    parser.add_argument("--root", default=str(HERE.parent.parent), help="repository root")
    args = parser.parse_args(argv)
    if args.nodes:
        print(json.dumps(gametest_matrix.nodes(args.root), separators=(",", ":")))
        return
    print(json.dumps({"include": rows(args.root, args.tier)}, separators=(",", ":")))


if __name__ == "__main__":
    main()
