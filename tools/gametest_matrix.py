"""Prints the client-gametest matrix for build.yml: one leg per Minecraft node in versions/*/.

Every node gets an OpenGL leg (Mesa llvmpipe); nodes from 26.3 on (SDL3, the first version where the Vulkan
fallback can be forced under Xvfb) also get a Vulkan leg (lavapipe). A new node gets its legs without a workflow edit.

    python tools/gametest_matrix.py [--root <repo>]   ->   {"include": [{"mc": "26.2", "backend": "OpenGL"}, ...]}
"""
import argparse
import json
import re
import sys
from pathlib import Path

VULKAN_FROM = (26, 3)
_VERSION = re.compile(r"^(\d+)\.(\d+)(?:\.(\d+))?(-.+)?$")


def _core(mc):
    m = _VERSION.match(mc)
    if not m:
        sys.exit(f"versions/{mc}: not a Minecraft version id")
    return int(m.group(1)), int(m.group(2)), int(m.group(3) or 0), m.group(4) or ""


def _sort_key(mc):
    major, minor, patch, suffix = _core(mc)
    return major, minor, patch, 0 if suffix else 1, suffix


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
