"""The Stutter Doctor's dev script in a production client (docs/v0.5/SPEC.md 3f, AC3f.1; v0.4 AC5.8's qualitative checks).

A fresh instance (the jar under test, fabric-api, Sodium; so 200000,200,200000 is never-generated terrain), the product's
own -Drigtune.dev.stutterScript=teleport (monitor on, benchmark world, 20 s still, tp, 30 s, save-all, 10 s, Stutter
Doctor, leave, quit) with -Drigtune.dev.forceGcEverySec=5 and -Xlog:gc, one start through Loom's e2eClient (the undo
driver, inert without a phase). Then evaluate() on latest.log, stutter.json and the GC log:
- the script finished;
- the spikes in the product's 10 s teleport window carry "after teleport", and from the first chunk load in it on,
  "chunks loading";
- no GC milliseconds claimed without an overlapping JVM pause;
- the unexplained remainder shown.
Millisecond numbers aren't judged: llvmpipe frames aren't a GPU's. It ports the v0.4 local runs' evaluator
(docs/v0.4/verification/p5c/tools/stut_eval.py) with its timing fit.

    python tools/e2e/stutter_run.py --mc 26.3 --new-jar <rigtune jar> --work <dir> --evidence <dir> [--gradle-arg=--offline]
"""

import argparse
import datetime
import gzip
import json
import os
import re
import shutil
import subprocess
import sys
import uuid
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
sys.path.insert(0, str(HERE))

import e2e_env  # noqa: E402

LF = chr(10)
TIMEOUT = 12 * 60
# StutterAnalyzer.TELEPORT_WINDOW: the seconds after a teleport whose spikes the product tags.
TELEPORT_WINDOW = 10
OPTIONS = ("onboardAccessibility:false", "pauseOnLostFocus:false", "tutorialStep:none", "skipMultiplayerWarning:true",
           "joinedFirstServer:true", "soundCategory_master:0.0", "renderDistance:12", "simulationDistance:8", "fullscreen:false")
GC_LINE = re.compile(r"\[(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d+)[+-]\d{4}\]\[([\d.]+)s\]\[\w+\s*\]\[gc\s*\] GC\(\d+\) (Pause [^0-9]*?) .*? ([\d.]+)ms$")


def wall(line):
    """latest.log's [HH:MM:SS] as seconds of the day, else None."""
    m = re.match(r"\[(\d\d):(\d\d):(\d\d)\]", line)
    return None if not m else int(m.group(1)) * 3600 + int(m.group(2)) * 60 + int(m.group(3))


def since(t, start):
    """Seconds from start to t, both seconds of the day: a run crossing local midnight wraps (review-11 CI-3)."""
    d = t - start
    return d + 86400 if d < -12 * 3600 else d


def client_log(logs):
    """The client log's lines: what log4j rolled over during the run (at local midnight), then latest.log. The instance
    is fresh, so every rotated file is this run's."""
    logs = Path(logs)
    rotated = sorted(logs.glob("*.log.gz"), key=lambda p: (p.stat().st_mtime, p.name)) if logs.is_dir() else []
    lines = []
    for path in rotated:
        with gzip.open(path, "rt", encoding="utf-8", errors="replace") as f:
            lines += f.read().splitlines()
    latest = logs / "latest.log"
    return lines + (latest.read_text(encoding="utf-8", errors="replace").splitlines() if latest.is_file() else [])


def first(lines, text):
    return next((wall(line) for line in lines if text in line), None)


def gc_pauses(gc_text):
    """(end as seconds of the day, ms, kind) of every GC pause in the -Xlog file (its time decorator marks the end)."""
    out = []
    for line in gc_text.splitlines():
        m = GC_LINE.match(line)
        if m:
            t = datetime.datetime.fromisoformat(m.group(1))
            out.append((t.hour * 3600 + t.minute * 60 + t.second + t.microsecond / 1e6, float(m.group(4)), m.group(3).strip()))
    return out


def evaluate(lines, stutter, gc_text):
    """(checks [(name, ok, detail)], facts) for one run."""
    sessions = [s for s in (stutter or {}).get("sessions", []) if s.get("source") == "monitor"]
    finished = any("Dev stutter: PASSED" in line for line in lines)
    checks = [("the dev script finished (monitor session saved)", finished and bool(sessions),
               "PASSED line: {}; monitor sessions in stutter.json: {}".format(finished, len(sessions)))]
    if not sessions:
        return checks, {}
    session = sessions[-1]
    cap, tp = first(lines, "Stutter Doctor: capture on"), first(lines, "Dev stutter: tp @a")
    tp_s = None if cap is None or tp is None else since(tp, cap)
    # Entering the world is a teleport to the product too (its first spikes carry the tag, v0.4's C1r as well).
    entered = first(lines, "Dev stutter: in the benchmark world")
    entry_s = None if cap is None or entered is None else since(entered, cap)
    pauses = gc_pauses(gc_text)
    worst = session.get("worst", [])

    # The capture started within [cap, cap + 1) s (latest.log has 1 s resolution): fit the offset (10 ms steps) so the
    # most GC-noted spikes [t - ms, t] (t rounded to 0.1 s: +-50 ms) overlap a pause [end - dur, end].
    def overlapping(delta, w):
        lo, hi = w["t"] - w["ms"] / 1000 - 0.05, w["t"] + 0.05
        return [p for p in pauses if cap is not None and since(p[0], cap) - delta - p[1] / 1000 <= hi and since(p[0], cap) - delta >= lo]
    gc_noted = [w for w in worst if any(n.startswith("gc:") for n in w.get("causes", []))]
    delta = max((d / 100 for d in range(0, 121)), key=lambda d: (sum(1 for w in gc_noted if overlapping(d, w)), -abs(d - 0.5))) \
        if gc_noted else 0.0
    unmatched = [w["t"] for w in gc_noted if not overlapping(delta, w)]

    tags, causes = session.get("tags", {}), session.get("causes", {})
    # The product's own teleport window (StutterAnalyzer.TELEPORT_WINDOW, 10 s from the position jump). The log stamps
    # whole seconds (+-1 s), so: every listed spike surely inside it (tp + 1 .. tp + 9) carries "after teleport"; at least one
    # tagged spike lies in its widest reading (tp - 1 .. tp + 11); no tagged spike lies outside that and the world entry's
    # own window. "Chunks loading": in the widest reading, every spike from the first one with chunk loads on (P5C-F1:
    # nothing arrives in the first moments to tag).
    def within(w, start, low, high):
        return start is not None and start + low <= w["t"] <= start + high

    def tagged(w):
        return "afterTeleport:context" in w.get("causes", [])
    sure = [w for w in worst if within(w, tp_s, 1, TELEPORT_WINDOW - 1)]
    wide = sorted((w for w in worst if within(w, tp_s, -1, TELEPORT_WINDOW + 1)), key=lambda w: w["t"])
    stray = [w["t"] for w in worst if tagged(w) and not within(w, tp_s, -1, TELEPORT_WINDOW + 1)
             and not within(w, entry_s, -1, TELEPORT_WINDOW + 1)]
    untagged = [w["t"] for w in sure if not tagged(w)]
    loading = [w for w in wide if any(n.startswith(("chunksLoading:", "chunkLoad:")) for n in w.get("causes", []))]
    first_loading = loading[0]["t"] if loading else None
    late_untagged = [w["t"] for w in wide if first_loading is not None and w["t"] >= first_loading and w not in loading]
    checks += [
        ("the spikes after the teleport carry \"after teleport\"", tags.get("afterTeleport", 0) > 0 and any(tagged(w) for w in wide)
         and not untagged and not stray,
         "tp at session {} s (world entry {} s); tagged near the tp: {}; untagged inside its window: {}; tagged outside both windows: {}"
         .format(tp_s, entry_s, [w["t"] for w in wide if tagged(w)], untagged, stray)),
        ("\"chunks loading\" from the first chunk load after the teleport on", tags.get("chunksLoading", 0) > 0 and first_loading is not None
         and not late_untagged, "tagged: {}; first at {} s; untagged after it: {}".format(tags.get("chunksLoading", 0), first_loading, late_untagged)),
        ("no GC milliseconds claimed without an overlapping pause", bool(pauses) and not unmatched,
         "{} GC pauses in the JVM log; GC-noted spikes {}; without a pause: {} (capture start = its log line + {:.2f} s)".format(
             len(pauses), [w["t"] for w in gc_noted], unmatched, delta)),
        ("the unexplained remainder is shown", "unknown" in causes, "causes {}".format(causes)),
    ]
    facts = {"tpSession": tp_s, "spikes": session.get("spikes"), "tags": tags, "causes": causes, "gcPauses": len(pauses),
             "gcOffsetSeconds": delta, "collector": session.get("collector"), "avgFps": session.get("avgFps")}
    return checks, facts


def markdown(name, mc, checks, facts):
    lines = ["# Stutter Doctor dev script: " + name, "",
             "- MC {}; verdict **{}**".format(mc, "PASS" if all(ok for _, ok, _ in checks) else "FAIL"),
             "- Facts: `{}`".format(json.dumps(facts)), "", "| check | result | detail |", "|---|---|---|"]
    lines += ["| {} | {} | {} |".format(n, "PASS" if ok else "**FAIL**", d.replace("|", "\\|")) for n, ok, d in checks]
    return LF.join(lines) + LF


def excerpt(lines):
    """RigTune's Stutter Doctor / Dev stutter lines (with the report and stutter.json blocks), the backend, the saves."""
    keep, block = [], False
    for line in lines:
        if block and not line.startswith("["):
            keep.append(line)
            continue
        block = False
        if any(k in line for k in ("Dev stutter:", "Stutter Doctor:", "Using graphics", "Saving chunks", "All dimensions are saved")):
            keep.append(line)
            block = line.rstrip().endswith(":")
    return keep


def stutter_json(lines):
    """The stutter.json the script logs before it quits (the last `Dev stutter: <path>:` block)."""
    text = LF.join(lines)
    blocks = re.findall(r"Dev stutter: \S*stutter\.json:\n(\{.*?\n\})\n", text + LF, re.S)
    return json.loads(blocks[-1]) if blocks else None


def parse_args(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--mc", required=True)
    parser.add_argument("--new-jar", required=True, help="the RigTune jar under test")
    parser.add_argument("--work", required=True)
    parser.add_argument("--evidence", required=True)
    parser.add_argument("--java-home", default=os.environ.get("JAVA_HOME"))
    parser.add_argument("--gradle-arg", action="append", help="extra argument for the Gradle call, e.g. --gradle-arg=--offline")
    args = parser.parse_args(argv)
    if not args.java_home:
        parser.error("set JAVA_HOME or pass --java-home")
    return args


def mod_coordinates(props):
    """(group, artifact, version) of the mods next to RigTune: fabric-api, and Sodium when the node has a build of it
    (add_mc_version.py leaves sodium_version out otherwise; never-generated terrain doesn't need it: review-11 CI-4)."""
    found = lambda key: (re.search(r"^{}=(.+)$".format(key), props, re.M) or [None, None])[1]
    out = [("net.fabricmc.fabric-api", "fabric-api", found("fabric_api_version").strip())]
    if found("sodium_version"):
        out.append(("maven.modrinth", "sodium", found("sodium_version").strip()))
    return out


def main(argv=None):
    args = parse_args(argv)
    name = "stutter-script-{}-{}".format(args.mc, uuid.uuid4().hex[:6])
    run = Path(args.work).resolve() / name
    instance, out = run / "instance", Path(args.evidence).resolve()
    (instance / "mods").mkdir(parents=True)
    out.mkdir(parents=True, exist_ok=True)
    props = (REPO / "versions" / args.mc / "gradle.properties").read_text(encoding="utf-8")
    jars = [Path(args.new_jar).resolve()] + [e2e_env.gradle_jar(Path.home() / ".gradle" / "caches" / "modules-2" / "files-2.1", *m)
                                             for m in mod_coordinates(props)]
    for jar in jars:
        shutil.copyfile(jar, instance / "mods" / jar.name)
    (instance / "options.txt").write_text(LF.join(OPTIONS) + LF, encoding="utf-8")
    gc_log = run / "gc.log"
    jvm = run / "jvm.txt"
    jvm.write_text(LF.join(["-Xmx4G", "-Drigtune.dev.stutterScript=teleport", "-Drigtune.dev.forceGcEverySec=5",
                            "-Xlog:gc,safepoint:file={}:time,uptime,level,tags".format(gc_log.as_posix())]) + LF, encoding="utf-8")
    command = (["cmd", "/c", str(REPO / "gradlew.bat")] if os.name == "nt" else [str(REPO / "gradlew")]) + list(args.gradle_arg or []) + [
        ":{}:e2eClient".format(args.mc), "-Pe2e.driver=undo", "-Pe2e.instance=" + str(instance), "-Pe2e.jvmArgsFile=" + str(jvm)]
    with open(out / "gradle.log", "w", encoding="utf-8") as log:
        try:
            code = subprocess.run(command, cwd=REPO, env=dict(os.environ, JAVA_HOME=args.java_home), stdout=log, stderr=subprocess.STDOUT,
                                  timeout=TIMEOUT).returncode
        except subprocess.TimeoutExpired:
            code = -1
    lines = client_log(instance / "logs")
    stutter = stutter_json(lines)
    gc_text = gc_log.read_text(encoding="utf-8", errors="replace") if gc_log.is_file() else ""
    checks, facts = evaluate(lines, stutter, gc_text)
    checks.insert(0, ("the client exited normally", code == 0, "gradle exit {}".format(code)))
    scrub = lambda text: text.replace(str(run), "<run>").replace(run.as_posix(), "<run>").replace(str(Path.home()), "~")
    (out / "log-excerpt.txt").write_text(scrub(LF.join(excerpt(lines))) + LF, encoding="utf-8", newline=LF)
    (out / "stutter.json").write_text(json.dumps(stutter, indent=1) + LF, encoding="utf-8", newline=LF)
    (out / "gc.log").write_text(gc_text, encoding="utf-8", newline=LF)
    (out / "RESULT.md").write_text(scrub(markdown(name, args.mc, checks, facts)), encoding="utf-8", newline=LF)
    for check_name, ok, detail in checks:
        print("[{}] {}: {}".format("PASS" if ok else "FAIL", check_name, detail))
    return 0 if all(ok for _, ok, _ in checks) else 1


if __name__ == "__main__":
    sys.exit(main())
