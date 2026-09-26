"""The released-jar compatibility harness (docs/v0.4/SPEC.md item 3, AC3.3; plan review H-M1): the "written by 0.4"
fixture sets (src/test/resources/v040-written/, placeholders until the features land) and the bundled rules-v2.json,
read by the RELEASED 0.3.0 jar's own Journal, HistoryModel, UndoPlanner, BenchmarkHistory, PendingActions,
ClientSettings and RulesLoader (tools/e2e/compat/Compat030.java, compiled at launch against that jar, Gson 2.14.0,
fabric-loader and slf4j from the Gradle cache; never against this repository's sources).

    python tools/e2e/compat030.py --old-jar <rigtune-0.3.0+mc26.2.jar> [--out <report.json>]

Exit 0: every check passed; 1: a check failed or is missing. CI runs it in the "Compile the E2E drivers" step."""

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
sys.path.insert(0, str(HERE))

import e2e_checks  # noqa: E402
import self_update_e2e  # noqa: E402
import written  # noqa: E402

PROGRAM = HERE / "compat" / "Compat030.java"
GSON_VERSION = "2.14.0"  # what Minecraft 26.2 and 26.3 ship
EXPECTED = (
    "Journal: history.json state OK with the same entries",
    "HistoryModel: every entry listed, none of an unknown kind",
    "UndoPlanner: Undo this on the profile-switch entry reverts each change",
    "UndoPlanner: Undo last and Undo all plan without a problem",
    "BenchmarkHistory: benchmarks.json loads without a .bad",
    "PendingActions: pending.json loads with every op's type, id and mod id",
    "ClientSettings: settings.json read as written",
    "RulesLoader: rules-v2.json parses with unchanged counts",
    "0.3.0 reading them changed no file",
)


def _version_key(version):
    return [int(p) if p.isdigit() else p for p in re.split(r"[.\-+]", version)]


def find_jar(cache, group, artifact, version=None):
    """The jar of group:artifact:version in a Gradle modules cache (files-2.1); the newest version if none is given."""
    base = Path(cache) / group / artifact
    versions = [version] if version else sorted((p.name for p in base.iterdir() if p.is_dir()), key=_version_key, reverse=True) \
        if base.is_dir() else []
    for v in versions:
        found = sorted((base / v).glob("*/{}-{}.jar".format(artifact, v)))
        if found:
            return found[0]
    raise SystemExit("{}:{}:{} isn't in the Gradle cache {}; run ./gradlew build first".format(group, artifact, version or "*", cache))


def classpath(old_jar, cache, loader):
    return [Path(old_jar), find_jar(cache, "com.google.code.gson", "gson", GSON_VERSION),
            find_jar(cache, "net.fabricmc", "fabric-loader", loader), find_jar(cache, "org.slf4j", "slf4j-api")]


def loader_version(properties):
    return re.search(r"^loader_version=(.+)$", Path(properties).read_text(encoding="utf-8"), re.MULTILINE).group(1).strip()


def parse(output):
    """(ok, name, detail) per "PASS name | detail" / "FAIL name | detail" line."""
    out = []
    for line in output.splitlines():
        match = re.match(r"^(PASS|FAIL) (.+?) \| (.*)$", line)
        if match:
            out.append((match.group(1) == "PASS", match.group(2), match.group(3)))
    return out


def missing(lines):
    names = {name for _, name, _ in lines}
    return [name for name in EXPECTED if name not in names]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--old-jar", required=True, help="the released rigtune-0.3.0+mc26.2.jar")
    parser.add_argument("--written", default=str(REPO / "src" / "test" / "resources" / "v040-written"))
    parser.add_argument("--rules", default=str(REPO / "src" / "main" / "resources" / "rigtune" / "rules-v2.json"))
    parser.add_argument("--gradle-cache", default=str(Path.home() / ".gradle" / "caches" / "modules-2" / "files-2.1"))
    parser.add_argument("--java", default=str(Path(os.environ["JAVA_HOME"]) / "bin" / "java") if os.environ.get("JAVA_HOME") else "java")
    parser.add_argument("--work", help="folder for the composed instance (default: a new temporary folder)")
    parser.add_argument("--out", help="write the checks here as JSON")
    args = parser.parse_args(argv)
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(errors="backslashreplace")  # a redirected stdout on Windows is cp1252

    old = Path(args.old_jar).resolve()
    version = self_update_e2e.e2e_env.mod_json(old)["version"]
    problem = self_update_e2e.released_problem(version, e2e_checks.digest(old, "sha256"))
    if version not in self_update_e2e.RELEASED or problem:
        raise SystemExit("{} isn't a released jar: {}".format(old, problem or version))

    instance = Path(args.work or tempfile.mkdtemp(prefix="compat030-")).resolve() / "instance"
    sets = written.resolve(args.written)
    sources = written.compose(sets, instance)
    print("sets: " + ", ".join("{}{}".format(s.name, " (PLACEHOLDER)" if s.placeholder else "") for s in sets))
    print("files: " + ", ".join("{} <- {}".format(name, "+".join(owners)) for name, owners in sources.items()))

    cp = os.pathsep.join(str(p) for p in classpath(old, args.gradle_cache, loader_version(REPO / "gradle.properties")))
    result = subprocess.run([args.java, "-Dstdout.encoding=UTF-8", "-Dfile.encoding=UTF-8", "-cp", cp, str(PROGRAM), "--config", str(instance / "config"), "--rules", str(Path(args.rules).resolve())],
                            capture_output=True, text=True, encoding="utf-8", errors="replace")
    lines = parse(result.stdout)
    for ok, name, detail in lines:
        print("{} {}: {}".format("PASS" if ok else "FAIL", name, detail))
    absent = missing(lines)
    if absent or result.returncode not in (0, 1):
        print("the program didn't report {} (exit {}):\n{}{}".format(absent, result.returncode, result.stdout, result.stderr))
    if args.out:
        Path(args.out).write_text(json.dumps({"oldJar": old.name, "sets": [{"name": s.name, "placeholder": s.placeholder} for s in sets],
                                              "checks": [{"name": n, "ok": ok, "detail": d} for ok, n, d in lines],
                                              "missing": absent}, indent=1) + "\n", encoding="utf-8", newline="\n")
    ok = not absent and result.returncode == 0 and all(ok for ok, _, _ in lines)
    print("RESULT " + ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
