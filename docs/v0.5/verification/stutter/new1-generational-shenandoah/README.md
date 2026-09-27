# NEW-1: what generational Shenandoah's GC notifications say (AC2S.11, the code-deciding run)

WS-S's first task (docs/v0.5/PLAN.md "WS-S", SPEC 2S NEW-1), run before any NEW-1 code, under the game-test lock,
2026-09-27. Question: do the GarbageCollectorMXBean notifications tell a young cycle from a global/old one, so that only
the latter count as live-set samples (`GcKind.MAJOR`)?

## Method

- Dev PC (Windows 11, JDK 25.0.4+1), Minecraft 26.2, this branch at a7613410 (0.4's GC code), a plain client:
  `./gradlew --no-daemon :26.2:runBenchmarkAutorun -PrigtuneAutorun=benchmark-world-tune` (DevAutorun: the benchmark
  world, the full default Tune from RD 12 up to 32, then quit). The Tune's sweeps are recorded by the benchmark's own
  Stutter Doctor capture, whose summary (with `liveSetPct`) went to stutter.json.
- JVM options through `JAVA_TOOL_OPTIONS`: `-XX:+UseShenandoahGC -XX:ShenandoahGCMode=generational
  -Xlog:gc:file=gc-%p.log:uptime -javaagent:gcprobe-agent.jar=probe-%p.txt -Drigtune.dev.forceGcEverySec=150`
  (run 2 adds `-Xmx2G`). `GcProbeAgent.java` (here) is measurement tooling only: it logs every notification's raw
  `gcName | gcAction | gcCause`, its GcInfo id/start/end and each heap pool's usage before → after.
- `join.py` (here) matches each `Shenandoah Cycles` notification to the GC log phases inside its [start, end] (plus the
  GC clock offset RigTune itself calibrated in that run: 46 ms, 29.4 ms), which name the cycle's generation
  (`(Young)`, `(Old)`, `(Global)`).

| run | heap | JVM uptime | capture (benchmark sweeps) | cycle notifications | log cycles |
|---|---|---|---|---|---|
| 1 | default, 7964 MB | ~3.3 min | 162.9 s | 37 | 4 global (Metadata GC Threshold), 1 global (System.gc()), 20 young, 12 young + an old marking |
| 2 | `-Xmx2G` | ~2.8 min | 135.0 s | 99 | 4 global (Metadata GC Threshold), 59 young, 35 young + an old marking, 1 old marking |

The runs are shorter than the SPEC's "10-minute session" (the full Tune finishes in ~3 minutes); between them every
cycle kind generational Shenandoah has (young, old marking, global from a heuristic and from `System.gc()`) occurred,
the old generation filled to 1.2 GB (run 1) and 0.9 GB (run 2), and the strings below were the same in every one.

## Result

The pools (`pools-and-beans.txt`): `Shenandoah Young Gen`, `Shenandoah Old Gen` (HEAP), Metaspace, Compressed Class
Space and three CodeHeaps; beans `Shenandoah Pauses` and `Shenandoah Cycles`, both over the two heap pools.

| cycle kind (from the GC log) | notification strings (gcName, gcAction, gcCause) |
|---|---|
| young | `Shenandoah Cycles`, `end of GC cycle`, `Concurrent GC` (run 1: 20, run 2: 59) |
| young followed by an old marking | `Shenandoah Cycles`, `end of GC cycle`, `Concurrent GC` (12, 36) |
| global (heuristic trigger "Metadata GC Threshold") | `Shenandoah Cycles`, `end of GC cycle`, `Concurrent GC` (4, 4) |
| global (`System.gc()`) | `Shenandoah Cycles`, `end of GC cycle`, `System.gc()` (1, 0) |
| pause phases of any of them | `Shenandoah Pauses`, `Init Mark` / `Final Mark` / `Init Update Refs` / `Final Update Refs`, `Concurrent GC` (or `System.gc()`) |

`run1-notifications.txt` and `run2-notifications.txt` list every cycle notification with the log's kind and the old and
young generation before → after; `run*-gc-cycles.log` are the GC log's trigger and cycle-start lines.

**The notifications don't tell a young cycle from a global or old one** (only the explicit `System.gc()` cause differs,
and that's the explicit case, not the generation). 0.4's code counts every `Shenandoah Cycles` notification as MAJOR and
reads `Shenandoah Old Gen` after it: the old generation after a young cycle includes floating garbage, and it only grew
during these runs (70 → 1194 MB in run 1). RigTune's `liveSetPct` read 13 % in run 1, where the log's old markings found
752-873 MB live (9-11 % of the heap), and 33 % in run 2, where the markings' median was ~456 MB (22 %). So under
generational Shenandoah 0.4's live set reads high, as NEW-1 feared.

## Decision (SPEC 2S NEW-1, Open question 5)

No GcKind classifier for generational Shenandoah: under it the live set is unmeasured (UNKNOWN). The listener recognises
the mode by its heap pool names (`Shenandoah Young Gen`/`Shenandoah Old Gen`, captured once when it starts) and then
marks no notification MAJOR, so `liveSetPercent` stays null and `ram-stutter-gc-heap`'s live-set branch can't fire
through it (the conservative direction). Full and degenerated collections still count as full pauses. GcKindTest has a
case for each string above (docs/v0.5/design/ws-s.md task S5).
