"""Prints the client-gametest matrix for build.yml: one leg per Minecraft node in versions/*/.

Every node gets an OpenGL leg (Mesa llvmpipe); nodes from 26.3 on (SDL3, the first version where the Vulkan
fallback can be forced under Xvfb) also get a Vulkan leg (lavapipe). A new node gets its legs without a workflow edit,
with or without a Sodium build (sodium_version is optional; the game tests don't need Sodium loaded).

    python tools/gametest_matrix.py [--root <repo>]   ->   {"include": [{"mc": "26.2", "backend": "OpenGL"}, ...]}
"""
import argparse
import json
import re
import sys
from pathlib import Path

VULKAN_FROM = (26, 3)
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


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", default=str(Path(__file__).resolve().parent.parent), help="repository root")
    args = parser.parse_args(argv)
    print(json.dumps({"include": legs(args.root)}, separators=(",", ":")))


if __name__ == "__main__":
    main()
