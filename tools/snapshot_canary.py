"""Resolves the Minecraft version snapshot-canary.yml tests (docs/v0.5/SPEC.md 3f, AC3f.8): the dispatch's `mc` input
when given (whitespace removed), else Mojang's newest snapshot; when that snapshot is the release itself there is nothing
newer to test and the run is a skip. Writes `mc` and `skip` to $GITHUB_OUTPUT, the skip's notice to the log and its line
to $GITHUB_STEP_SUMMARY. A network error or an unreadable manifest exits non-zero (the run fails; the issue is left
alone). --manifest reads a saved manifest instead of fetching it (tools/tests/test_snapshot_canary.py).

    python tools/snapshot_canary.py [--mc <id>] [--manifest <version_manifest_v2.json>]
"""

import argparse
import json
import os
import sys
import time
import urllib.request

MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
USER_AGENT = "chaotix345/rigtune-snapshot-canary/1.0 (github.com/chaotix345/rigtune)"
ATTEMPTS = 4
RETRY_DELAY = 5


def fetch_manifest(opener=urllib.request.urlopen, sleeper=time.sleep):
    """The manifest, retried as the workflow's curl did (--retry 3 --retry-delay 5)."""
    request = urllib.request.Request(MANIFEST_URL, headers={"User-Agent": USER_AGENT})
    for attempt in range(1, ATTEMPTS + 1):
        try:
            with opener(request, timeout=30) as response:
                return json.loads(response.read())
        except OSError as e:
            if attempt == ATTEMPTS:
                raise SystemExit(f"snapshot_canary: no manifest from {MANIFEST_URL}: {e}")
            sleeper(RETRY_DELAY)


def resolve(input_mc, manifest_loader):
    """(mc, skip): skip when the newest snapshot is the release (then mc is that release). The manifest is loaded only
    when no version was given."""
    mc = "".join((input_mc or "").split())
    if mc:
        return mc, False
    manifest = manifest_loader()
    latest = manifest.get("latest") if isinstance(manifest, dict) else None
    snapshot = latest.get("snapshot") if isinstance(latest, dict) else None
    release = latest.get("release") if isinstance(latest, dict) else None
    if not isinstance(snapshot, str) or not isinstance(release, str) or not snapshot or not release:
        raise SystemExit(f"snapshot_canary: the manifest has no latest.snapshot/latest.release: {latest!r}")
    return snapshot, snapshot == release


def _append(env, text):
    path = os.environ.get(env)
    if path:
        with open(path, "a", encoding="utf-8") as out:
            out.write(text + "\n")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--mc", default="", help="the version id to test (blank: the newest snapshot)")
    parser.add_argument("--manifest", help="a saved version_manifest_v2.json instead of Mojang's")
    args = parser.parse_args(argv)

    def load():
        if args.manifest:
            with open(args.manifest, encoding="utf-8") as f:
                return json.load(f)
        return fetch_manifest()

    mc, skip = resolve(args.mc, load)
    if skip:
        print(f"::notice title=Snapshot canary skipped::The newest snapshot is the release ({mc}): nothing newer to test.")
        _append("GITHUB_STEP_SUMMARY", f"Skipped: the newest snapshot is the release ({mc}).")
        _append("GITHUB_OUTPUT", "skip=true")
        return
    print(f"Testing Minecraft {mc}")
    _append("GITHUB_OUTPUT", f"mc={mc}")
    _append("GITHUB_OUTPUT", "skip=false")


if __name__ == "__main__":
    main(sys.argv[1:])
