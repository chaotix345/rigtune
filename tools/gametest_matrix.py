"""Prints the client-gametest matrix for build.yml: one leg per Minecraft node in versions/*/.

Every node gets an OpenGL leg (Mesa llvmpipe); nodes from 26.3 on (SDL3, the first version where the Vulkan
fallback can be forced under Xvfb) also get a Vulkan leg (lavapipe). A new node gets its legs without a workflow edit,
with or without a Sodium build (sodium_version is optional; the game tests don't need Sodium loaded).

    python tools/gametest_matrix.py [--root <repo>] [--parts N]   ->   {"include": [{"mc": "26.2", "backend": "OpenGL"}, ...]}

The dormant two-JVM split (docs/v0.5/SPEC.md 1g): with --parts N > 1 every leg becomes N legs, each running a contiguous
slice of the game-test classes in fabric.mod.json's order (so the first class stays in part 1): "part" ("1/2") for the job
name, "suffix" ("-part1") for artifact names, "classes" (simple names) for build.gradle's -PgametestClasses. The switch
is build.yml's GAMETEST_PARTS (a dispatch's gametest_parts input overrides it for one run); PARTS is only this script's
default. With one part the legs are exactly as before.
"""
import argparse
import json
import re
import sys
from pathlib import Path

VULKAN_FROM = (26, 3)
PARTS = 1
GAMETEST_MOD = Path("src") / "gametest" / "resources" / "fabric.mod.json"
# The id goes into the workflow's shell steps, so the suffix is limited to letters, digits, dots and dashes.
_VERSION = re.compile(r"^(\d+)\.(\d+)(?:\.(\d+))?(-[0-9A-Za-z.-]+)?$")
_STAGES = {"snapshot": 0, "pre": 1, "rc": 2}


def _core(mc):
    m = _VERSION.fullmatch(mc)
    if not m:
        sys.exit(f"versions/{mc}: not a Minecraft version id")
    return int(m.group(1)), int(m.group(2)), int(m.group(3) or 0), m.group(4) or ""


def _sort_key(mc):
    """26.4-snapshot-2 < 26.4-snapshot-10 < 26.4-pre-1 < 26.4-rc-1 < 26.4 < 26.4.1 (unknown suffixes first)."""
    major, minor, patch, suffix = _core(mc)
    if not suffix:
        return major, minor, patch, len(_STAGES), 0, ""
    stage, _, number = suffix[1:].partition("-")
    return major, minor, patch, _STAGES.get(stage, -1), int(number) if number.isdigit() else 0, suffix


def nodes(root):
    """Every non-hidden directory under versions/ (the same set as release.yml's versions/*/), in version order."""
    found = [p.name for p in (Path(root) / "versions").iterdir() if p.is_dir() and not p.name.startswith(".")]
    if not found:
        sys.exit("no Minecraft nodes under versions/")
    return sorted(found, key=_sort_key)


def legs(root):
    result = []
    for mc in nodes(root):
        result.append({"mc": mc, "backend": "OpenGL"})
        if _core(mc)[:2] >= VULKAN_FROM:
            result.append({"mc": mc, "backend": "Vulkan"})
    return result


def game_test_classes(root):
    """The client game-test classes' simple names, in the order fabric.mod.json runs them."""
    entries = json.loads((Path(root) / GAMETEST_MOD).read_text(encoding="utf-8"))["entrypoints"]["fabric-client-gametest"]
    return [entry.rsplit(".", 1)[-1] for entry in entries]


def split(classes, parts):
    """parts contiguous slices of near-equal size, in order."""
    if parts < 1 or parts > len(classes):
        sys.exit(f"--parts {parts}: between 1 and {len(classes)} (the game-test classes)")
    return [classes[len(classes) * k // parts:len(classes) * (k + 1) // parts] for k in range(parts)]


def matrix(root, parts=PARTS):
    if parts == 1:
        return legs(root)
    slices = split(game_test_classes(root), parts)
    return [dict(leg, part=f"{k + 1}/{parts}", suffix=f"-part{k + 1}", classes=",".join(names))
            for leg in legs(root) for k, names in enumerate(slices)]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", default=str(Path(__file__).resolve().parent.parent), help="repository root")
    parser.add_argument("--parts", type=int, default=PARTS, help="JVMs per leg (default %(default)s)")
    args = parser.parse_args(argv)
    print(json.dumps({"include": matrix(args.root, args.parts)}, separators=(",", ":")))


if __name__ == "__main__":
    main()
