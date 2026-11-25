# Why `tickHookOnVsReference` sits near its limit

2026-11-26, main at fac3bd2f. Follow-up to [footprint-headroom.md](footprint-headroom.md), which found the ratio at 86% of its 2.05 limit on CI (max 1.768), and to [worker-cpu-startup.md](worker-cpu-startup.md), whose local runs failed it twice (2.26 and 2.06). The goal was a reference that represents the hook's real work, as PR #110 did for `frameHookOnVsReference`, with the limit and the doubled-work self-check unchanged. I found no honest change to make. No code and no budget changed. This note is the report.

## What the hook does per call, against the reference

The gated work is `RigTuneClient.onTick` plus `StutterHooks.tick` with the session monitor on, in a singleplayer world with Sodium loaded (the production game test, as on CI). Per call it:

- reads `System.nanoTime()` once (`StutterHooks.movement`, with a player present);
- reads and writes a few dozen fields across Minecraft objects (`gui`, the screen, the window, the player's position, the key mapping) and RigTune statics, with a few volatile reads;
- calls through a non-constant method handle (`StutterHooks.tick`), and on every fifth call `BuildBacklog.refresh`, which chains six Sodium method handles and writes three volatile ints.

It writes no ring per call. `StutterMonitor.event` runs only on a teleport or a speed change. So #110's cause, a 1.5 MB ring that other processes evict from L3, doesn't apply here.

The reference is 6 xorshift rounds per call with loads and stores into a 2 KB table. It has no clock read and no pointer chasing.

## Local measurements

26.2 production game test (`:26.2:runProductionClientGameTest -PgametestClasses=FootprintGameTest`) on this PC (Ryzen 7 7800X3D, 8 cores and 16 threads, Windows 11, Java 25). A temporary probe, not committed, ran right after the gate's own monitor-on timing: 12 measures per JVM, each 48 interleaved blocks of 20,000 calls as the gate does, timing the hook, the hook twice, the reference, a loop of bare `System.nanoTime()` calls and candidate references. The load came from idle-priority node processes started just before the client and stopped after it: a memory streamer writes one byte per cache line through its own 64 MB array, and an ALU helper spins on integer math. No helper outlived its run.

| JVM | Load | Gate's own measure (hook / reference ns) | Probe: hook / reference / clock ns | Probe ratio, median (range) |
|---|---|---|---|---|
| idle1 | none | 1.515 (26.4 / 17.4) | 27.3 / 17.4 / 19.0 | 1.568 (1.567-1.572) |
| idle2 | none | 1.529 (27.3 / 17.8) | 28.3 / 17.7 / 19.4 | 1.593 (1.589-1.601) |
| mem8 | 8 streamers | **2.001** (35.1 / 17.5) | 30.0 / 17.5 / 19.0 | 1.707 (1.702-1.715) |
| mem8b | 8 streamers | 1.503 (26.2 / 17.4) | 26.9 / 17.4 / 19.0 | 1.543 (1.541-1.553) |
| mem8alu8 | 8 streamers and 8 ALU helpers, every logical CPU busy | 1.489 (26.3 / 17.6) | 27.0 / 17.5 / 19.0 | 1.535 (1.527-1.537) |

Measured alone in idle1, the parts were `RigTuneClient.onTick` 5.2 ns, `StutterHooks.tick` 25.8 ns and `BuildBacklog.refresh` 16.0 ns (so about 3 ns per tick, at one call in five).

What this shows:

- **The clock read is most of the hook.** A bare `System.nanoTime()` costs 19.0 ns here, against 27.3 ns for the whole hook. On CI it costs 22-24 ns: FrameHookBudgetTest's phase reference (its reference plus 8 clock reads) ran 187.7 and 177.1 ns above its plain reference in the `java` jobs of runs 45694830419 and 45696601264. That is about half of the 42 ns hook on a Xeon 6975P-C.
- **Load didn't move the ratio reproducibly.** The reference and the clock didn't move under any load. In one JVM of three under 8 streamers, the hook did: the gate's measure reached 2.001, and the 12 probe measures right after it were 1.70-1.72. The other two loaded JVMs, one with every logical CPU busy, measured like idle. So streamers aren't the cause on their own. Whatever slowed that JVM came and went, and I couldn't make it happen again. The earlier failures at 2.26 and 2.06 were also on this PC while other projects' gates ran.

## CI by runner CPU

The six `build.yml` runs on main from 25 Nov (45681609497 to 45699911651, 18 fresh legs), medians:

| CPU | Legs | Hook, monitor on (ns) | Same work, monitor off (ns) | Reference (ns) | Ratio |
|---|---|---|---|---|---|
| Xeon 6975P-C | 8 | 42.2 | 7.2 | 25.4 | 1.670 |
| EPYC 7763 | 4 | 44.1 | 13.7 | 28.0 | 1.581 |
| EPYC 9V74 | 4 | 37.6 | 11.9 | 24.1 | 1.567 |
| EPYC 9V45 | 2 | 32.5 | 6.8 | 24.7 | 1.316 |

CI isn't noisy on this ratio. On Xeon 6975P-C, footprint-headroom.md's 46 legs give a median of 1.67, p90 1.70 and max 1.77. The ratio sits high there on every run. On that CPU, turning the monitor on adds 35 ns, against 26 ns on the EPYCs, while the reference is the same speed. The clock read is the largest part of those 35 ns. Whether it is also what costs more on the Xeon can't be told from the artifacts, which don't record the clock's cost.

## What I tried and why I didn't ship it

**A clock read in the reference**, as FrameHookBudgetTest's phase case does. It can't keep the ratio's scale. Locally, one clock read (19.0 ns) costs more than the whole reference (17.4 ns). A reference of one clock read plus 6 xorshift rounds measured 28.9 ns: the hook came to 0.95 and its doubled work to 1.84, under the 2.05 limit, so the self-check would fail. With 2 rounds (20.2 ns), the idle ratio fell from 1.568 to 1.350 and the doubled work to 2.617. That lowers the ratio by about 14% on every runner. Its sensitivity drops just as it would if the limit were raised. Under load it rose by the same share as the current reference (9.6% against 8.9% in mem8), so it doesn't make the ratio steadier either. Without CI numbers showing that the Xeon's clock is what pushes its ratio up, that amounts to loosening the limit.

**A dependent load chain across pages**, to mimic the hook's scattered field reads. The probe's chains gave impossible timings (128 dependent loads in 16 ns), and since no load reliably slowed the hook, there was nothing to match it against. I dropped it.

## Options for HQ

1. **Record the clock's cost on CI.** Add a block of bare `System.nanoTime()` calls to `timeTick` as a diagnostic in the JSON detail, not gated. A week of artifacts would show whether the Xeon 6975P-C's high ratio comes from its clock. If it does, a reference with a clock read has evidence behind it, and the self-check margin can be checked per runner before it ships (locally the doubled work stays at 2.57 or more with the 2-round version).
2. **Re-measure once, as FrameHookBudgetTest does.** FootprintGameTest has no re-measure for this ratio. With one, only two measures over 2.05 would fail. That covers the one-off slow JVM seen here: mem8's 2.001 was followed by 12 measures of 1.70-1.72. The limit and the self-check would stay the same. But it changes how the gate decides, and it doesn't help Xeon's steady 1.67, so I left it to HQ. My brief kept the ratio checks as they are.
3. **Leave it.** CI didn't fail this gate from 19 to 25 Nov (footprint-headroom.md): the max was 1.768, and the doubled work's min was 2.563.
