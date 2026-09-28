"""Checks that what Modrinth serves for a release is the GitHub release's bytes (docs/v0.5/SPEC.md 3c, AC3c.2; v0.4's M11).

For every node in versions/*/: the tag's GitHub asset rigtune-<version>+mc<node>.jar is downloaded (gh), its sha1 is
looked up in the project's authenticated version list (the public lookup hides versions of a project in moderation),
and then:
- that file's sha512 in the list equals the asset's;
- the file its CDN url serves hashes to the same sha512 (the bytes a player downloads);
- the version's number is <version>+mc<node>, its game versions exactly [<node>], its loaders exactly ["fabric"], and its
  type build.gradle's rule (a plain release id publishes as a release, anything else as an alpha).

    python tools/e2e/release_verify.py --tag v0.5.0 [--repo owner/name] [--nodes 26.2 26.3]
    (env: MODRINTH_TOKEN, GH_TOKEN or GITHUB_TOKEN)

Prints "verified: …" per check, "::error::…" per failure; exit 1 on any failure. Reads only.
"""

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

import gametest_matrix  # noqa: E402

PROJECT = "rigtune"
USER_AGENT = "chaotix345/rigtune/release (github.com/chaotix345/rigtune)"
RELEASE_ID = re.compile(r"\d+\.\d+(\.\d+)?")


def version_type(mc):
    """build.gradle's `versionType`: only a plain release id (26.3, 26.3.1) publishes as a release."""
    return "release" if RELEASE_ID.fullmatch(mc) else "alpha"


def expected(tag, mcs):
    """(node, asset, version number, version type) per node."""
    version = tag[1:] if tag.startswith("v") else tag
    return [(mc, "rigtune-{}+mc{}.jar".format(version, mc), "{}+mc{}".format(version, mc), version_type(mc)) for mc in mcs]


def nodes(root):
    return gametest_matrix.nodes(root)


def check_node(mc, asset, number, kind, data, versions, fetch):
    """(ok, message) per check for one node. data: the GitHub asset's bytes; versions: the authenticated version list;
    fetch(url) -> bytes."""
    sha1, sha512 = hashlib.sha1(data).hexdigest(), hashlib.sha512(data).hexdigest()
    found = [(v, f) for v in versions for f in v.get("files") or [] if (f.get("hashes") or {}).get("sha1") == sha1]
    if not found:
        return [(False, "{}: no Modrinth version file has {}'s sha1 ({}): upload missing or corrupted".format(mc, asset, sha1))]
    v, f = found[0]
    results = [(f["hashes"].get("sha512") == sha512,
                "{}: {} sha512 {} Modrinth's metadata".format(mc, asset, "matches" if f["hashes"].get("sha512") == sha512 else "differs from"))]
    served = hashlib.sha512(fetch(f["url"])).hexdigest()
    results.append((served == sha512, "{}: the CDN's {} {} the GitHub asset (sha512)".format(mc, f.get("url"), "is" if served == sha512 else "is NOT")))
    for field, want in (("version_number", number), ("game_versions", [mc]), ("loaders", ["fabric"]), ("version_type", kind)):
        results.append((v.get(field) == want, "{}: {} {} (want {})".format(mc, field, v.get(field), want)))
    return results


def _get(url, token=None):
    headers = {"User-Agent": USER_AGENT}
    if token:
        headers["Authorization"] = token
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=60) as response:
        return response.read()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--tag", required=True)
    parser.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY", "chaotix345/rigtune"))
    parser.add_argument("--nodes", nargs="*", help="default: versions/*/")
    args = parser.parse_args(argv)
    token = os.environ.get("MODRINTH_TOKEN")
    if not token:
        raise SystemExit("MODRINTH_TOKEN isn't set: the version list of a project in moderation needs it")
    mcs = args.nodes or nodes(HERE.parent.parent)
    versions = json.loads(_get("https://api.modrinth.com/v2/project/{}/version".format(PROJECT), token))
    scratch = Path(tempfile.mkdtemp(prefix="release-verify-"))
    failed = False
    for mc, asset, number, kind in expected(args.tag, mcs):
        download = subprocess.run(["gh", "release", "download", args.tag, "--repo", args.repo, "-p", asset, "-D", str(scratch), "--clobber"],
                                  capture_output=True, text=True)
        if download.returncode != 0:
            print("::error::could not download GitHub release asset {}: {}".format(asset, download.stderr.strip()))
            failed = True
            continue
        for ok, message in check_node(mc, asset, number, kind, (scratch / asset).read_bytes(), versions, _get):
            print(("verified: " if ok else "::error::") + message)
            failed |= not ok
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
