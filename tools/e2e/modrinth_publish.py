"""release.yml's Modrinth publish (docs/v0.5/SPEC.md 3c; review-11 CI-2, review-12): for every Minecraft node, the staged
rigtune-<version>+mc<node>.jar through tools/modrinth_project.py, wrapped in tools/ci/retry.sh.

No Gradle: the publish job's runner is fresh, and an online Gradle configuration (the plugins, Loom's Minecraft files) is an
outage surface after the GitHub release is already public, and would see the token. The payload is what 0.2.0-0.4.0's
Minotaur upload sent: the jar's version as version_number, "RigTune <mod_version> (MC <node>)", fabric, release for a
plain release id (26.2, 26.2.1) and alpha otherwise, the CHANGELOG section of the version, fabric-api required, not
featured. A failed node is reported and the others still go; the exit status is 1 if any failed.

Two steps, both per node:
- --preflight (read-only, before the GitHub release is public; review-12 R12REL-1/3/7): the CHANGELOG section is there
  and fits; the token reaches the project; the version_number is free or already holds exactly the staged file. With
  --dry-run, a changelog problem is a warning, so a dry run still shows everything else.
- the upload (after it): upload-version checks the staged sha256, leaves a version that already holds this file alone
  (a retry after a slow or lost response), and refuses one that holds other bytes. --dry-run prints the payload only.

    python3 tools/e2e/modrinth_publish.py --staged <release-files dir> --tag v0.5.0 [--preflight] [--dry-run]
"""

import argparse
import re
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent.parent
RELEASE_ID = re.compile(r"\d+\.\d+(\.\d+)?")
# Modrinth's version changelog limit isn't in the API docs we could check offline (labrinth's is believed to be 65536
# characters, UNVERIFIED); stay well under it (review-12 R12REL-3, the coordinator's conservative cap).
MAX_CHANGELOG = 60000


class PublishError(Exception):
    pass


def changelog(path, mod_version):
    """build.gradle's former changelogForVersion: the section under the first "## " heading naming [mod_version] (or the
    version without a "-" suffix), up to the next "## " heading, trimmed; "" when there is none."""
    lines = Path(path).read_text(encoding="utf-8").splitlines() if Path(path).is_file() else []
    candidates = list(dict.fromkeys([mod_version, re.sub(r"-.*", "", mod_version)]))
    start = next((i for i, line in enumerate(lines) if line.startswith("## ") and any("[{}]".format(c) in line for c in candidates)), None)
    if start is None:
        return ""
    end = next((i for i in range(start + 1, len(lines)) if lines[i].startswith("## ")), len(lines))
    return "\n".join(lines[start + 1:end]).strip()


def changelog_problems(text, mod_version):
    if not text:
        return ["CHANGELOG.md has no \"## [{}]\" section (or it is empty): the Modrinth versions would have no changelog".format(mod_version)]
    if len(text) > MAX_CHANGELOG:
        return ["CHANGELOG.md's [{}] section is {} characters, over the {} this release allows".format(mod_version, len(text), MAX_CHANGELOG)]
    return []


def staged_jar(staged, mc):
    """The node's staged jar and its SHA256SUMS digest."""
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
    return jar, sums[jar.name]


def mod_version_of(tag):
    return tag[1:] if tag.startswith("v") else tag


def upload_args(staged, tag, mc, changelog_file):
    """tools/modrinth_project.py's upload-version arguments for the node's staged jar."""
    jar, sha256 = staged_jar(staged, mc)
    return ["upload-version", "--file", str(jar), "--version-number", jar.name[len("rigtune-"):-len(".jar")],
            "--name", "RigTune {} (MC {})".format(mod_version_of(tag), mc), "--game-versions", mc, "--loaders", "fabric",
            "--version-type", "release" if RELEASE_ID.fullmatch(mc) else "alpha", "--changelog-file", str(changelog_file),
            "--sha256", sha256]


def preflight_args(staged, mc):
    """tools/modrinth_project.py's preflight arguments for the node's staged jar."""
    jar, sha256 = staged_jar(staged, mc)
    return ["preflight", "--file", str(jar), "--version-number", jar.name[len("rigtune-"):-len(".jar")], "--sha256", sha256]


def publish(staged, tag, nodes, changelog_path, work, dry_run, preflight=False,
            run=lambda command: subprocess.run(command, cwd=REPO).returncode):
    work = Path(work)
    work.mkdir(parents=True, exist_ok=True)
    notes = work / "modrinth-changelog.md"
    text = changelog(changelog_path, mod_version_of(tag))
    notes.write_text(text, encoding="utf-8")
    status = 0
    if preflight:
        for problem in changelog_problems(text, mod_version_of(tag)):
            print("::{}::{}".format("warning" if dry_run else "error", problem), flush=True)
            if not dry_run:
                status = 1
    for mc in nodes:
        try:
            args = preflight_args(staged, mc) if preflight else upload_args(staged, tag, mc, notes)
        except PublishError as e:
            print("::error::{}".format(e), flush=True)
            status = 1
            continue
        # The preflight only reads, with the token, in a dry run too; the upload's dry run sends nothing.
        code = run(["tools/ci/retry.sh", "python3", "tools/modrinth_project.py"] + args + (["--dry-run"] if dry_run and not preflight else []))
        if code == 0:
            print("{}: {} ({}){}".format("preflight OK" if preflight else "done (created, or already on Modrinth with the same file)",
                                         mc, args[2], " (dry run: payload only)" if dry_run and not preflight else ""), flush=True)
        else:
            print("::error::Modrinth {} failed for {}".format("preflight" if preflight else "publish", mc), flush=True)
            status = 1
    return status


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--staged", required=True, help="the release-files artifact (the jars and SHA256SUMS)")
    parser.add_argument("--tag", required=True)
    parser.add_argument("--work", default=None, help="where the changelog file goes (default: the staged folder's parent)")
    parser.add_argument("--preflight", action="store_true", help="the read-only checks before the GitHub release is public")
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args(argv)
    nodes = sorted(p.name for p in (REPO / "versions").iterdir() if p.is_dir())
    return publish(args.staged, args.tag, nodes, REPO / "CHANGELOG.md", args.work or Path(args.staged).resolve().parent, args.dry_run,
                   preflight=args.preflight)


if __name__ == "__main__":
    sys.exit(main())
