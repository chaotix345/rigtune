"""release.yml's Modrinth publish (docs/v0.5/SPEC.md 3c; review-11 CI-2): for every Minecraft node, the staged
rigtune-<version>+mc<node>.jar through tools/modrinth_project.py's upload-version, wrapped in tools/ci/retry.sh.

No Gradle: the publish job's runner is fresh, and an online Gradle configuration (the plugins, Loom's Minecraft files) is an
outage surface after the GitHub release is already public, and would see the token. upload-version is idempotent (a
version_number that exists is left alone), so a retry is safe, and it checks the file's sha256 against the staged
SHA256SUMS. The payload is what build.gradle's `modrinth {}` block sent: the jar's version as version_number,
"RigTune <mod_version> (MC <node>)", fabric, release for a plain release id (26.2, 26.2.1) and alpha otherwise, the
CHANGELOG section of the version, fabric-api required. A failed node is reported and the others still go; the exit status
is 1 if any failed.

    python3 tools/e2e/modrinth_publish.py --staged <release-files dir> --tag v0.5.0 [--dry-run]
"""

import argparse
import re
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent
RELEASE_ID = re.compile(r"\d+\.\d+(\.\d+)?")


class PublishError(Exception):
    pass


def changelog(path, mod_version):
    """build.gradle's changelogForVersion: the section under the first "## " heading naming [mod_version] (or the version
    without a "-" suffix), up to the next "## " heading, trimmed; "" when there is none."""
    lines = Path(path).read_text(encoding="utf-8").splitlines() if Path(path).is_file() else []
    candidates = list(dict.fromkeys([mod_version, re.sub(r"-.*", "", mod_version)]))
    start = next((i for i, line in enumerate(lines) if line.startswith("## ") and any("[{}]".format(c) in line for c in candidates)), None)
    if start is None:
        return ""
    end = next((i for i in range(start + 1, len(lines)) if lines[i].startswith("## ")), len(lines))
    return "\n".join(lines[start + 1:end]).strip()


def upload_args(staged, tag, mc, changelog_file):
    """tools/modrinth_project.py's arguments for the node's staged jar."""
    staged = Path(staged)
    found = sorted(staged.glob("rigtune-*+mc{}.jar".format(mc)))
    if len(found) != 1:
        raise PublishError("expected exactly one rigtune-*+mc{}.jar to publish, found: {}".format(mc, [p.name for p in found] or "none"))
    jar = found[0]
    sums = {}
    for line in (staged / "SHA256SUMS").read_text(encoding="utf-8").splitlines():
        # sha256sum's "<digest>  <name>", or "<digest> *<name>" in binary mode.
        parts = line.split(None, 1)
        if len(parts) == 2:
            sums[parts[1].strip().lstrip("*")] = parts[0]
    if jar.name not in sums:
        raise PublishError("{} isn't in SHA256SUMS".format(jar.name))
    mod_version = tag[1:] if tag.startswith("v") else tag
    return ["upload-version", "--file", str(jar), "--version-number", jar.name[len("rigtune-"):-len(".jar")],
            "--name", "RigTune {} (MC {})".format(mod_version, mc), "--game-versions", mc, "--loaders", "fabric",
            "--version-type", "release" if RELEASE_ID.fullmatch(mc) else "alpha", "--changelog-file", str(changelog_file),
            "--sha256", sums[jar.name]]


def publish(staged, tag, nodes, changelog_path, work, dry_run, run=lambda command: subprocess.run(command, cwd=REPO).returncode):
    work = Path(work)
    work.mkdir(parents=True, exist_ok=True)
    notes = work / "modrinth-changelog.md"
    notes.write_text(changelog(changelog_path, tag[1:] if tag.startswith("v") else tag), encoding="utf-8")
    status = 0
    for mc in nodes:
        try:
            args = upload_args(staged, tag, mc, notes)
        except PublishError as e:
            print("::error::{}".format(e), flush=True)
            status = 1
            continue
        code = run(["tools/ci/retry.sh", "python3", "tools/modrinth_project.py"] + args + (["--dry-run"] if dry_run else []))
        if code == 0:
            print("published: {} ({}){}".format(mc, args[2], " (dry run: payload only)" if dry_run else ""), flush=True)
        else:
            print("::error::Modrinth publish failed for {}".format(mc), flush=True)
            status = 1
    return status


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--staged", required=True, help="the release-files artifact (the jars and SHA256SUMS)")
    parser.add_argument("--tag", required=True)
    parser.add_argument("--work", default=None, help="where the changelog file goes (default: the staged folder's parent)")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args(argv)
    nodes = sorted(p.name for p in (REPO / "versions").iterdir() if p.is_dir())
    return publish(args.staged, args.tag, nodes, REPO / "CHANGELOG.md", args.work or Path(args.staged).resolve().parent, args.dry_run)


if __name__ == "__main__":
    sys.exit(main())
