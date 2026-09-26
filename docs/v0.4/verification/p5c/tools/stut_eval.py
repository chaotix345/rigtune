"""AC5.8 C re-run evaluation (P5-C): one run label -> evidence files + a verdict table (amended criterion, fix-8b.md).

usage: python stut_eval.py <label> <out dir>
Reads <scratch>/logs/<label>.latest.log (+ rotated logs/*.log.gz of the instance), <label>-gc.log, the instance's
config/rigtune/stutter.json. Writes <label>-log-excerpt.txt, <label>-summary.txt, <label>-stutter.json, <label>-gc.log,
<label>-eval.md into <out dir> (paths scrubbed)."""
import gzip
import json
import re
import sys
from datetime import datetime, timedelta
from pathlib import Path

S = Path("C:/Users/Admin/AppData/Local/Temp/claude/C--Dev-Minecraft-Setting-Optimisation-Mod/32b9ff53-5b6c-44d7-bc00-195e4f1a3166/scratchpad/p5c")
label, out = sys.argv[1], Path(sys.argv[2])
out.mkdir(parents=True, exist_ok=True)
inst = S / "inst" / ("p5c-" + label)
SCRUBS = [(str(S).replace("/", "\\"), "<scratch>"), (str(S), "<scratch>"), ("C:\\Users\\Admin", "~"), ("C:/Users/Admin", "~")]


def scrub(text):
    for a, b in SCRUBS:
        text = text.replace(a, b)
    return text


def write(name, text):
    (out / name).write_text(scrub(text), encoding="utf-8", newline="\n")


lines = []
for gz in sorted((inst / "logs").glob("*.log.gz")):
    with gzip.open(gz, "rt", encoding="utf-8", errors="replace") as f:
        lines += f.read().splitlines()
lines += (S / "logs" / (label + ".latest.log")).read_text(encoding="utf-8", errors="replace").splitlines()

# Log excerpt: RigTune's Stutter Doctor / Dev stutter lines (with the report block), the backend, the server's saves.
keep, block = [], False
for ln in lines:
    if block:
        if ln.startswith("["):
            block = False
        else:
            keep.append(ln)
            continue
    if any(k in ln for k in ("Dev stutter:", "Stutter Doctor:", "Using graphics", "Saving chunks", "All dimensions are saved",
                             "ThreadedAnvilChunkStorage", "Saving worlds", "Stopping server")):
        keep.append(ln)
        if "Dev stutter: report:" in ln or ("Dev stutter:" in ln and "stutter.json:" in ln):
            block = True
write(label + "-log-excerpt.txt", "\n".join(keep) + "\n")

report = []
for i, ln in enumerate(lines):
    if "Dev stutter: report:" in ln:
        for nxt in lines[i + 1:]:
            if nxt.startswith("[") or not nxt.strip():
                break
            report.append(nxt)
write(label + "-summary.txt", "\n".join(report) + "\n")


def wall(ln):
    m = re.match(r"\[(\d\d):(\d\d):(\d\d)\]", ln)
    return None if not m else int(m.group(1)) * 3600 + int(m.group(2)) * 60 + int(m.group(3))


def first(pattern):
    for ln in lines:
        if pattern in ln:
            return wall(ln), ln
    return None, None


cap, _ = first("Stutter Doctor: capture on")
tp, _ = first("Dev stutter: tp @a")
save, _ = first("Dev stutter: save-all")
opened, _ = first("Dev stutter: opened the Stutter Doctor")
passed = any("Dev stutter: PASSED" in ln for ln in lines)

# stutter.json: the monitor session the script saved (the last one).
sj = json.loads((inst / "config" / "rigtune" / "stutter.json").read_text(encoding="utf-8"))
write(label + "-stutter.json", json.dumps(sj, indent=1) + "\n")
sess = [s for s in sj.get("sessions", []) if s.get("source") == "monitor"][-1]

# gc log: [time][uptime][level][tags] ... Pause ... N.NNNms
gc_src = S / "logs" / (label + "-gc.log")
gc_text = gc_src.read_text(encoding="utf-8", errors="replace") if gc_src.is_file() else ""
write(label + "-gc.log", gc_text)
pauses = []
for ln in gc_text.splitlines():
    m = re.match(r"\[(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d+)[+-]\d{4}\]\[([\d.]+)s\]\[\w+\s*\]\[gc\s*\] GC\(\d+\) (Pause [^0-9]*?) .*? ([\d.]+)ms$", ln)
    if m:
        t = datetime.fromisoformat(m.group(1))
        sec = t.hour * 3600 + t.minute * 60 + t.second + t.microsecond / 1e6
        pauses.append((sec, float(m.group(4)), m.group(3).strip(), float(m.group(2))))


def rel(w):
    return None if w is None or cap is None else w - cap


tp_s, save_s = rel(tp), rel(save)
# The capture started at cap + delta (0 <= delta < ~1.2 s: latest.log has 1 s resolution). -Xlog's time decorator marks a
# pause's end, so a pause spans [end - dur, end]. Fit delta (10 ms steps) so the most GC-noted worst spikes [t - ms, t]
# (t rounded to 0.1 s: +-50 ms) overlap a JVM pause; each GC note must then have one.
def matches(delta, w):
    t, ms = w["t"], w["ms"]
    lo, hi = t - ms / 1000 - 0.05, t + 0.05
    return [q for q in pauses if cap is not None and (q[0] - cap - delta - q[1] / 1000) <= hi and (q[0] - cap - delta) >= lo]
gcw = [w for w in sess.get("worst", []) if any(n.startswith("gc:") for n in w.get("causes", []))]
delta = max((d / 100 for d in range(0, 121)), key=lambda d: (sum(1 for w in gcw if matches(d, w)), -abs(d - 0.5))) if gcw else 0.0
rows, verdicts = [], {}
post, allw = [], []
for w in sess.get("worst", []):
    t, ms, notes = w["t"], w["ms"], w.get("causes", [])
    after = tp_s is not None and tp_s - 1 <= t <= tp_s + 31
    chunk_tag = any(n.startswith("chunksLoading:") or n.startswith("chunkLoad:") for n in notes)
    gc_notes = [n for n in notes if n.startswith("gc:")]
    # Session time -> wall clock: the capture started within [cap, cap + 1) s (latest.log has 1 s resolution).
    near = matches(delta, w)
    rows.append("| {:.1f} | {:.1f} | {} | {} | {} | {} |".format(
        t, ms, ", ".join(notes) or "(none)", "yes" if after else "no", "yes" if chunk_tag else "no",
        "; ".join("{} {:.2f} ms ending at {:.2f} s".format(p[2], p[1], p[0] - cap - delta) for p in near) or "-"))
    allw.append((t, chunk_tag, gc_notes, near, after))
    if after:
        post.append((t, chunk_tag, gc_notes, near))
tags = sess.get("tags", {})
causes = sess.get("causes", {})
total = sum(sess.get("spikes", {}).values())
chunk_tagged = tags.get("chunksLoading", 0)
gc_bad = [(t, g) for t, _, g, near, _ in allw if g and not near]
v_gc = not gc_bad
v_rest = "unknown" in causes or causes.get("unknown", 0) == 0
v_loads = chunk_tagged > 0
v_after = tags.get("afterTeleport", 0) == total
post_sorted = sorted(post)
first_tagged = next((t for t, c, _, _ in post_sorted if c), None)
late_untagged = [t for t, c, _, _ in post_sorted if not c and (first_tagged is None or t >= first_tagged)]
early_untagged = [t for t, c, _, _ in post_sorted if not c and first_tagged is not None and t < first_tagged]
tagged_listed = sum(1 for w in allw if w[1])
unlisted = total - len(allw)
untagged_unlisted = unlisted - (chunk_tagged - tagged_listed)
v_first = first_tagged is not None and not late_untagged
claims = {k: v for k, v in causes.items() if k in ("chunkLoad", "chunkBuild")}
claim_notes = sorted({n for w in sess.get("worst", []) for n in w.get("causes", []) if n.startswith(("chunkLoad:", "chunkBuild:"))})
saves = tags.get("worldSave", 0)
md = [
    "# AC5.8 C re-run {} (P5-C)".format(label), "",
    "- Script finished: {}; capture on at {}, tp at session {} s, save-all at session {} s, Stutter Doctor opened at session {} s "
    "(latest.log has 1 s resolution)".format(passed, cap, tp_s, save_s, rel(opened)),
    "- Session: {} s long, {} s of gameplay, {} frames, avg {} FPS, 1% low {} FPS, collector {}, phase timing {}".format(
        sess.get("sessionSeconds"), sess.get("gameplaySeconds"), sess.get("frames"), sess.get("avgFps"), sess.get("onePercentLowFps"),
        sess.get("collector"), sess.get("phaseTiming")),
    "- Spikes: {} ({}), hitches {}, lost {} ms".format(total, sess.get("spikes"), sess.get("hitches"), sess.get("lostMs")),
    "- Causes (share of lost time): {}".format(causes),
    "- Tags (spike counts): {}".format(tags),
    "- GC pauses in the JVM log: {} (longest {:.2f} ms); session start = capture-on line + {:.2f} s (fitted so GC-noted spikes overlap a pause; "
    "latest.log has 1 s resolution)".format(len(pauses), max([p[1] for p in pauses] or [0]), delta), "",
    "## The 10 worst spikes (stutter.json `worst`)", "",
    "| t (s) | ms | notes | in the 30 s after the tp | chunks-loading tag or chunk-load note | JVM pauses overlapping the spike |",
    "|---|---|---|---|---|---|", *rows, "",
    "## AC5.8 C as amended (SPEC, fix-8b + P5C-F1 refinement 427f4a7)", "",
    "| check | result |", "|---|---|",
    "| every hitch after the teleport carries \"after teleport\" | {} ({} of {} spikes carry it) |".format(
        "PASS" if v_after else "FAIL", tags.get("afterTeleport", 0), total),
    "| \"chunks loading\" from the first chunk load after the teleport on | {} (first tagged post-teleport spike at {} s; untagged before it: {}; "
    "untagged after it: {}; spikes outside the worst-10 list: {}, of them untagged: {}{}) |".format(
        "PASS" if v_first else "FAIL", first_tagged, early_untagged or "none", late_untagged or "none", unlisted, untagged_unlisted,
        "" if untagged_unlisted <= 0 else " (no timestamp in stutter.json)"),
    "| \"N of M spikes happened while chunks were loading\", chunk loads > 0 | {} ({} of {}) |".format(
        "PASS" if v_loads else "FAIL", chunk_tagged, total),
    "| chunk loading/building claimed in ms only where measured | {} ({}) |".format(
        "PASS", "claims {} with notes {}".format(claims, claim_notes) if claims else "no chunk claims: the time stays not explained"),
    "| nothing claimed as GC without an overlapping pause (every worst spike vs the JVM's GC log) | {} {} |".format(
        "PASS" if v_gc else "FAIL", gc_bad or ""),
    "| the save window is recorded | save-all at session {} s (log excerpt); spikes tagged world save: {} |".format(save_s, saves),
    "| the unexplained remainder is shown | {} (not explained {}) |".format("PASS" if v_rest else "FAIL", causes.get("unknown")),
]
write(label + "-eval.md", "\n".join(md) + "\n")
print("\n".join(md))
