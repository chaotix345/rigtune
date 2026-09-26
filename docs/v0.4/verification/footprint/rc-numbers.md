# RC footprint numbers (AC10.5)

Source: the latest green `build` run on `feat/v0.4.0`, [36236205018](https://github.com/chaotix345/rigtune/actions/runs/36236205018) (head a3f5c14 = the RC code 9cf84f6 plus one PROGRESS line; every job green, footprint budgets in `fail` mode, `violations: []` on all three legs). Artifacts `footprint-26.2-OpenGL`, `footprint-26.3-OpenGL` and `footprint-26.3-Vulkan` (FootprintGameTest, production client, 7 mods, Linux, 4 vCPUs, software rendering) and `test-reports` (FrameHookBudgetTest, JUnit). Downloaded with `gh run download 36236205018 -p "footprint-*"` and `-n test-reports`. For the coordinator to fold into README "RigTune's own footprint". README.md itself isn't edited here.

## FootprintGameTest (`measured`), per leg

| What | 26.2 OpenGL | 26.3 OpenGL | 26.3 Vulkan | Budget (tools/footprint-budgets.json) |
|---|---|---|---|---|
| Render-thread startup work (preLaunch + onInitializeClient), CPU | 100.2 ms | 98.9 ms | 98.5 ms | 150 ms |
| The same, wall | 109.9 ms | 136.6 ms | 152.8 ms | 368 ms |
| CLIENT_STARTED handler, wall | 48.0 ms | 29.2 ms | 35.9 ms | 141 ms |
| RigTune threads, CPU in the 5 s after CLIENT_STARTED | 208.0 ms | 187.0 ms | 179.5 ms | 300 ms |
| Tick hook at the title screen, ns per call / bytes | 51.1 / 0 | 58.9 / 0 | 44.4 / 0 | 111 / 0 |
| Tick hook in a world, monitor off (play path), ns / bytes | 12.8 / 0 | 25.7 / 0 | 21.3 / 0 | 87 / 0 |
| Tick hook in a world, monitor on, ns / bytes | 40.8 / 0 | 43.9 / 0 | 48.9 / 0 | 101 / 0 |
| RigTune objects after a full GC at idle (class histogram, shallow) | 73.7 KB | 73.2 KB | 73.3 KB | 109,296 B |
| Heap growth after 20 RigTune screen + Tools open/close cycles | -389 KB | -372 KB | -511 KB | 13.5 MB |
| Leak suspects (classes with more instances per cycle) | 0 | 0 | 0 | 0 |
| Session monitor on: retained (StutterMonitor.retainedBytes) | 1,982,608 B | 1,982,608 B | 1,982,608 B | 2,621,440 B |
| Session monitor off again: retained / leftover instances | 0 / 0 | 0 / 0 | 0 / 0 | 0 / 0 |
| Stutter sampler CPU per 60 s, steady state | 54.2 ms | 39.7 ms | 43.8 ms | 102 ms |

Diagnostics from the same files (not budgeted): notice evaluation 115.8 / 168.5 / 99.6 µs per screen init; the monitor-on heap delta after GC 2.83 / 2.92 / 2.92 MB (≈ the rings' 1.98 MB plus capture bookkeeping), back to -131 / -46 / -47 KB after it's off; phase timers seen on every leg; launch to title 20.1 / 17.6 / 24.7 s (a CI runner with software rendering; not comparable with README's Windows A/B of 14.5 s vs 14.3 s); RigTune threads at idle: `RigTune preview`, `RigTune rules`, 2 × `RigTune worker` (plus `RigTune stutter sampler` only while the monitor runs; no `RigTune power`: no battery on the runner, AC4.9).

## FrameHookBudgetTest (unit tests, both versions)

Best of 5 × 10 M hot calls. The allocation is over the hot calls; the cold 10 M calls allocate at most 880 B, within the 64 KiB JIT allowance.

| Per-frame hook | 26.2 | 26.3 | Budget |
|---|---|---|---|
| Monitor off | 0.175 ns, 0 B | 0.178 ns, 0 B | 13 ns, 0 B |
| Monitor on (FrameTimes + StutterMonitor.onFrame) | 34.7 ns, 0 B | 34.7 ns, 0 B | 75 ns, 0 B |
| Monitor on + phase timers (capped frame, one tick, a chunk load) | 238.2 ns, 0 B | 238.5 ns, 0 B | 400 ns, 0 B |
| (diagnostic) the same, uncapped | 180.4 ns | 180.5 ns | none |

## Compared with README's current table (the calibration maxima)

Every RC value is under its budget. Three RC values are above README's "Largest measured" column:
- Render-thread startup CPU: 100.2 ms (README 96 ms).
- RigTune's background threads in the first 5 s: 208 ms (README 187 ms).
- RigTune's objects after a full GC: 73.7 KB (README 55 KB). The v0.4 features merged after the calibration (profiles' ShareKeys, benchmark records and more) add instances.

The monitor-off frame hook now reads 0.18 ns (README 6.4 ns), and the phase-timed frame 238 ns (README 256 ns). If README shows the RC's numbers, these are the values to use.

## Local Windows run (not the gate)

The local dev `runClientGameTest` on the dev machine (Ryzen 7 7800X3D, Windows 11) wrote `../gametests/26.2-footprint.json` (0 violations) and `../gametests/26.3-attempt1-footprint.json`. The second has a render-thread CPU of 156.25 ms against a wall time of 140.0 ms, which failed the 150 ms budget. It's an artefact of Windows' 15.625 ms thread-CPU resolution, not RigTune's cost: [P5B-FINDINGS.md](../P5B-FINDINGS.md) F1.
