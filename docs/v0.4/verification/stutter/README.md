# AC5.8: Stutter Doctor induced-stutter runs (P5-A, 2026-09-26)

Release candidate: `origin/feat/v0.4.0` @ a3f5c14 (code = 9cf84f6), `rigtune-0.4.0-dev+mc26.2.jar` sha256 `149f20f9…dd11`.
Machine: Ryzen 7 7800X3D, RX 7800 XT (Adrenalin 26.8.1), 32 GB, Windows 11, Temurin 25.0.4.1+1. MC 26.2, Fabric
API 0.161.0+26.2, Sodium 0.9.2 (default options: Chunk Updates = Deferred), RigTune's benchmark world (seed 8675309,
camera at 0,192), RD 12, SD 8, 1280×720 window, `-Xmx4G`.

**How the runs were made.** A production client per run (Loom `e2eClient` on a scratch instance: the RC jar,
fabric-api, Sodium, and the test-only driver mod `../p5a-tools/driver`), one client at a time under the lock
(`../p5a-tools/run.sh`, `queue.sh`). The driver turns the session monitor on through RigTune's controller
(`setStutterMonitor(true)`), opens RigTune's benchmark world (`BenchmarkWorld.open`), waits, opens StutterScreen, waits
for the report, logs `stutterSummary()` (the Copy summary text) and the StutterReport, screenshots the screen at four
scroll positions, leaves the world (the session goes to stutter.json) and quits. Runs C1/C3 use the product's own dev
script `-Drigtune.dev.stutterScript=teleport` (driver inert). Dev switches: `-Drigtune.dev.forceGcEverySec=5`.
Per run: `<run>-summary.txt` (the report text), `<run>-log-excerpt.txt` (RigTune's "Stutter Doctor:" lines incl. the
thread census, phase-timer bits and GC offset, plus driver lines), `p5a-<run>.json` (driver output with the full
StutterReport), `<run>-stutter.json` (stutter.json as logged at the end), screenshots, and `-Xlog:gc` files where noted.
`table.md` is the overview produced by `../p5a-tools/stutter_table.py`.

**Two option sets.** Vanilla's defaults in a fresh instance are VSync on, maxFps 120 and inactivity limit "afk" (after
a minute without input the game drops to ~30 FPS). Runs A, B, C1, C2, D used them (realistic, but standing still for a
minute throttles the game to 30 FPS, which hides short hitches under the 33 ms frames: C2 recorded 0 spikes for that
reason, so it isn't used). A2, A3, C3, C4, D2 were repeated with VSync off, maxFps 260 (unlimited) and inactivity
"minimized" (~2,700-3,000 FPS).

## Results

| run | criterion | measured | result |
|---|---|---|---|
| **A** control (G1, -Xmx4G, still, default options, 6 min) | ≤ 3 spikes/min; no GC-claimed ms without an overlapping pause | 4 spikes in 5:52 of gameplay = **0.7/min** (3 of them at 0:10-0:11 right after the world-entry teleport, 1 severe 195 ms at 2:39 unexplained); GC claimed 3 % of the lost time, only on a spike tagged with a GC pause (Attributor claims GC ms only for overlapping pause intervals) | **PASS** |
| **A2** control, uncapped (2,719 FPS, 150 s) | same | **0 spikes** in 140 s of gameplay; 136 G1 pauses in the JVM (longest 17.2 ms, `stutA2-gc.log`), none produced a spike | **PASS** |
| **A3** control, uncapped, 6.5 min (autosave window) | same | **1 spike** in 6:23 of gameplay (0.16/min; the world-entry teleport at 0:12), 2,655 FPS; the 5-minute autosave ran (below) without a spike | **PASS** |
| **B** forceGcEverySec=5 (G1, -Xmx4G, 150 s) | ≥ 80 % of induced GC hitches (pauses ≥ 30 ms) attributed to GC with EXPLICIT; `stutter-gc-explicit` fires | 29 forced `System.gc()` in the capture (`explicitGcs 29`), **28 spikes = 28 hitches, each 84-89 ms** (full GCs ≥ 30 ms), causes **GC 100 %**, every listed spike `gc:high:FULL:EXPLICIT`; the 29th GC fell in the excluded world-loading window. Advice: **`stutter-gc-explicit`** ("Something asks Java for full garbage collections"), `enoughData` true (140 s, 28 spikes) | **PASS** (28/28 = 100 %) |
| **C** teleport into ungenerated terrain | hitches in the 30 s after the teleport attributed to chunk loading/building (tag "after teleport", chunk loads > 0, a Sodium backlog > 0); none claimed as GC without an overlapping pause; save window recorded; remainder shown | C1 (product script, default options): 14 spikes, 13 after the teleport, **not explained 100 %**, every spike "rendering (low)". C3 (product script, fresh world, uncapped): 12 spikes, all after a teleport, **GC 6 %, not explained 94 %**. C4 (driver, `tp -300000 200 300000`, uncapped): 18 spikes (worst 86 ms), 16 after the teleport, **not explained 100 %**. Probe in C4 (RigTune's own BuildBacklog read every 5 ticks for 30 s): **637 chunk loads** in 12 s but Sodium **scheduled 0 / busy 0 / total 10 in all 120 samples** (no backlog ever). GC claims only with a real pause nearby (C4's one `gc:low` spike at 104.9 s: G1 pauses of 4.2 and 3.8 ms at 104.68/105.23 s in `stutC4-gc.log`). Save window: the save-all ran 104 ms (BEFORE_SAVE → AFTER_SAVE, driver log) with no spike in it. The remainder is shown ("not explained"). | **FAIL (attribution) → P5A-F2**; the rest PASS |
| **D** ZGC + forceGcEverySec=5 (-Xmx4G, 150 s) | no spike with more than 1 ms claimed by GC; Cycles events present | D (default options): 0 spikes; D2 (uncapped, `-Xlog:gc*`): **0 spikes** in 140 s at 2,722 FPS, GC claimed nothing; the GC log has **30 `Major Collection (System.gc())` cycles** (~0.22 s concurrent each) and pauses of ~0.01 ms (Pause Mark Start/End, Relocate Start); RigTune counted 28 explicit collections in its window (`explicitGcs 28`) and calibrated (GC offset 23.1 ms), so the cycle notifications arrived | **PASS** |
| **F** overhead: 3 interleaved Measure pairs, monitor on/off | average FPS and 1 % low within run-to-run noise | on: avg 2,640 (2,633-2,646), 1 % low 622 (578-648); off: avg 2,651 (2,621-2,701), 1 % low 641 (620-675). Δ avg −0.4 %, Δ 1 % low −3.0 %; the spread inside each arm (1 % low 11-12 %) and item 7's floor (2 × max(cv, 5 %) ≥ 10 %) are larger (`F-overhead/table.md`) | **PASS** (within noise) |
| **S-M1** phase-timer target missing (a test mixin config) | the game starts and shows "phase timing unavailable" | SM1b: a test-only mixin config (`../p5a-tools/sm1b`: an empty `@Mixin(Minecraft.class)` whose config plugin rewrites `runTick`'s `INVOKE Minecraft.tick()` into a static hook in `preApply`, before any injector resolves targets) → log `[P5A-SM1] rewrote 1 call(s) to Minecraft.tick() in runTick`; RigTune's two tick injectors (require = 0) found no target: `timers seen 1110011`, **"phase timing unavailable"** in the session line, and StutterScreen shows **"Phase timing unavailable: chunk loading, ticks and rendering can't be measured separately."** (`SM1-missing-target/p5a-stutSM1b-stutter-SM1b-top.png`); the game started, the world ran at 119 FPS, the report and GC attribution still worked. (SM1, a first attempt with a `@Redirect` on the same call, didn't hide the target: Mixin resolves every injector's targets before injecting, so RigTune's injector still bound; `timers seen 1111111`.) | **PASS** |

Phase timers: every monitor run logged `timers seen 1111111` (all 7, the limiter pair while capped) or `11111`
(uncapped benchmark, no limiter) on 26.2, with plausible per-frame baselines (packets 0.2-8 µs, ticks 0.2-511 µs, render
0.27-1.2 ms). 26.3 was not run for AC5.8 (vanilla 26.3's native startup crash; CI's three legs log `timers seen 11111`,
ws-s.md).

**F caveat.** The benchmark always captures during its sweeps (SPEC 5), and a running session pauses while a benchmark
runs, so both arms record the sweeps; the pair measures what the session monitor adds around a benchmark, not the
capture's total cost. The capture's per-frame cost is WS-F's FrameHookBudgetTest (≈24 ns/frame on, 124 ns with the
phase timers). A2 (monitor on, 2,719 FPS, 0 spikes) shows no visible cost at high frame rates either. Side observation (P5A-F3): with
the monitor on, each Measure run also saved a near-empty session from outside the sweeps: `33 spikes in 2 s of gameplay`,
`0 spikes in 0 s`, `0 spikes in 0 s` (`F-overhead/ovh-log-excerpt.txt`).

## Also recorded
- **Thread-name census** (sampler, INFO): e.g. A2 `66 threads {… Chunk Render Task Executor #*=10, … IO-Worker-*=7,
  Render thread=1, RigTune Modrinth check=4 (a transient pool), RigTune rules=1, RigTune stutter sampler=1, RigTune
  worker=2, Server thread=1, Sodium Async Cull Thread=1, … Worker-Main-*=15 …}` (`A-control/stutA2-log-excerpt.txt`).
- **Calibrated GC offset:** 16.8-19.8 ms on G1, 23.1 and 30.0 ms on ZGC (each run's "session saved" line).
- **Save durations:** the teleport runs' explicit save-all took 104 ms (C4); the pause-screen saves 13-930 ms (driver's
  BEFORE_SAVE/AFTER_SAVE lines). No spike overlapped a save in any run.
- **DH- thread prefix at runtime (DH 3.3.2):** confirmed. With DH loaded (prof2 instance, benchmark world, 45 s), the
  census lists 126 threads including `DH-LOD Builder Thread[0..7]`, `DH-IO Thread[0..7]`, `DH-Render Loader Thread[0..7]`,
  `DH-World Gen Queue Thread[0]`, `DH-Chunk Update Queue [minecraft:overworld] Thread[0]`, … (every DH thread starts with
  `DH-`), and the DH tag fired: "6 of 14 spikes during Distant Horizons background work (not measured)"
  (`DH-census/`). Note: the census prints DH's numbered names one by one (`Thread[0]`, `Thread[1]`, …) instead of
  collapsing them like `Worker-Main-*`; cosmetic (the log line only).
- **Autosave and client spikes:** A3 (uncapped, 6:23 of gameplay): the integrated server's autosave fired once, 5:02
  after the world was created (`BEFORE_SAVE flush false, force false` at JVM uptime 321.43 s, `AFTER_SAVE` 22 ms later)
  and produced **no client spike**; nor did any pause-screen save in the other runs. (Small benchmark world; a large
  explored world may differ.)
- **Full-GC durations on a 4 GB heap:** 84-89 ms per forced `System.gc()` on G1 (B), 0.22 s concurrent (no pause) on ZGC.
