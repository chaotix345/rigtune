"""The released-0.4.0 compatibility harness (docs/v0.5/SPEC.md 3b, AC3b.1): the "written by" fixture sets
(src/test/resources/v040-written/ and v050-written/, composed by written.py as one 0.5 instance) and the bundled
rules-v2.json, read by the RELEASED 0.4.0 jar's own Journal, HistoryModel, UndoPlanner, BenchmarkHistory,
PendingActions, ClientSettings, StutterStore/StutterSummary, AwarenessStore, ProfileStore, ServerLimitsStore,
RestoreMarker and RulesLoader (tools/e2e/compat/Compat040.java, compiled at launch against that jar, Gson 2.14.0,
fabric-loader and slf4j from the Gradle cache; never against this repository's sources).

    python tools/e2e/compat040.py --old-jar <rigtune-0.4.0+mc26.2.jar> [--written <root>]... [--out <report.json>]

Exit 0: every check passed; 1: a check failed or is missing."""

import argparse
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
sys.path.insert(0, str(HERE))

import compat030  # noqa: E402
import e2e_checks  # noqa: E402
import self_update_e2e  # noqa: E402
import written  # noqa: E402

PROGRAM = HERE / "compat" / "Compat040.java"
OLD_VERSION = "0.4.0+mc26.2"
EXPECTED = (
    "Journal: history.json state OK with the same entries",
    "HistoryModel: every entry listed, none of an unknown kind",
    "UndoPlanner: Undo this on every entry with a change still applied or staged plans without a problem",
    "UndoPlanner: Undo last and Undo all plan without a problem",
    "BenchmarkHistory: benchmarks.json loads without a .bad",
    "PendingActions: pending.json loads with every op's type, id and mod id",
    "ClientSettings: settings.json read as written",
    "StutterStore: every session loads and StutterSummary renders it",
    "AwarenessStore: loads, and an update on a copy keeps the unknown fields",
    "ProfileStore: profiles.json loads with every switch's label",
    "ServerLimitsStore: server-limits.json loads writable",
    "RestoreMarker: benchmark-restore.json restores what 0.4.0 knows",
    "RulesLoader: rules-v2.json parses with the same counts as without the sections 0.4.0 doesn't know",
    "0.4.0 reading them changed no file",
)


def missing(lines):
    names = {name for _, name, _ in lines}
    return [name for name in EXPECTED if name not in names]


def check_old_jar(old):
    """The released 0.4.0 jar for 26.2 (RELEASED), or SystemExit: Compat040.java is written against its classes."""
    old = Path(old).resolve()
    version = self_update_e2e.e2e_env.mod_json(old)["version"]
    problem = self_update_e2e.released_problem(version, e2e_checks.digest(old, "sha256"))
    if version != OLD_VERSION or problem:
        raise SystemExit("{} isn't the released 0.4.0 jar Compat040.java is written for: {}".format(old, problem or version))
    return old


def set_instances(sets, work):
    """For every set with an expect.json: that set alone composed into work/<set>/instance and again into
    work/<set>/spare (the checks that write use the spare), each with the files its pending ops act on
    (written.materialize). Returns ([(set, instance config dir, expect.json, spare config dir)], [sets without one])."""
    expectations = written.expectations(sets)
    out, missing = [], []
    for fixture in sets:
        if fixture.name not in expectations:
            missing.append(fixture.name)
            continue
        folders_ = []
        for kind in ("instance", "spare"):
            instance = Path(work) / fixture.name / kind
            written.compose([fixture], instance)
            written.materialize(instance)
            folders_.append(instance / "config")
        out.append((fixture.name, folders_[0], expectations[fixture.name], folders_[1]))
    return out, missing


def folders(work):
    """The composed instance, and the program's own scratch folder beside it (its checks write only there)."""
    work = Path(work).resolve()
    return work / "instance", work / "scratch"


def parse_args(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--old-jar", required=True, help="the released rigtune-0.4.0+mc26.2.jar")
    parser.add_argument("--written", action="append",
                        help="a fixture root (repeatable; default: v040-written, plus v050-written when it exists)")
    parser.add_argument("--rules", default=str(REPO / "src" / "main" / "resources" / "rigtune" / "rules-v2.json"))
    parser.add_argument("--gradle-cache", default=str(Path.home() / ".gradle" / "caches" / "modules-2" / "files-2.1"))
    parser.add_argument("--java", default=str(Path(os.environ["JAVA_HOME"]) / "bin" / "java") if os.environ.get("JAVA_HOME") else "java")
    parser.add_argument("--work", help="folder for the composed instance and the program's scratch (default: a new temporary folder)")
    parser.add_argument("--out", help="write the checks here as JSON")
    args = parser.parse_args(argv)
    args.written = args.written or [str(p) for p in self_update_e2e.default_written(REPO)]
    return args


def main(argv=None):
    args = parse_args(argv)
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(errors="backslashreplace")  # a redirected stdout on Windows is cp1252

    old = check_old_jar(args.old_jar)
    instance, scratch = folders(args.work or tempfile.mkdtemp(prefix="compat040-"))
    scratch.mkdir(parents=True, exist_ok=True)
    sets = written.resolve_all(args.written)
    conflicts = []
    sources = written.compose(sets, instance, conflicts=conflicts)
    print("sets: " + ", ".join("{}/{}{}".format(s.generation, s.name, " (PLACEHOLDER)" if s.placeholder else "") for s in sets))
    print("files: " + ", ".join("{} <- {}".format(name, "+".join(owners)) for name, owners in sources.items()))
    for file, key, earlier, later in conflicts:
        print("merged: {} {}: {} overrides {}".format(file, key, later, earlier))

    # Every "written by 0.5" set carries its expect.json (the v050-written README); each is checked on its own instance.
    per_set, without = set_instances([s for s in sets if s.generation == written.V050.name], scratch.parent / "sets")
    set_args = [a for name, config, expect, spare in per_set for a in ("--set", "|".join((name, str(config), str(expect), str(spare))))]

    cp = os.pathsep.join(str(p) for p in compat030.classpath(old, args.gradle_cache, compat030.loader_version(REPO / "gradle.properties")))
    result = subprocess.run([args.java, "-Dstdout.encoding=UTF-8", "-Dfile.encoding=UTF-8", "-cp", cp, str(PROGRAM), "--config", str(instance / "config"),
                             "--rules", str(Path(args.rules).resolve()), "--scratch", str(scratch)] + set_args,
                            capture_output=True, text=True, encoding="utf-8", errors="replace")
    lines = compat030.parse(result.stdout) + [(False, name + " expect.json", "missing: every v050-written set has one") for name in without]
    for ok, name, detail in lines:
        print("{} {}: {}".format("PASS" if ok else "FAIL", name, detail))
    absent = missing(lines) + [name + " (no check reported)" for name, *_ in per_set if not any(n.startswith(name + " ") for _, n, _ in lines)]
    if absent or result.returncode not in (0, 1):
        print("the program didn't report {} (exit {}):\n{}{}".format(absent, result.returncode, result.stdout, result.stderr))
    if args.out:
        Path(args.out).write_text(json.dumps({"oldJar": old.name, "sets": [{"generation": s.generation, "name": s.name, "placeholder": s.placeholder}
                                                                           for s in sets],
                                              "merged": [list(c) for c in conflicts],
                                              "checks": [{"name": n, "ok": ok, "detail": d} for ok, n, d in lines],
                                              "missing": absent}, indent=1) + "\n", encoding="utf-8", newline="\n")
    ok = not absent and result.returncode == 0 and all(ok for ok, _, _ in lines)
    print("RESULT " + ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
