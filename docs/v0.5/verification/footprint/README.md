# Footprint gate verification (v0.5 SPEC 1d, ws-ci)

Budgets, calibration and arithmetic: docs/v0.5/design/ws-ci.md "Footprint budgets" and tools/footprint-budgets.json.

## AC1d.3: a 2x slowdown of the monitor-on tick fails the gate

Scratch branch `scratch/ws-ci-proof-slowdown` (fb2177b8, on b9f5c002): `StutterHooks.tick` runs its body twice while the
stutter monitor is on; only RigTuneClientGameTest and FootprintGameTest run.
Run [36297418835](https://github.com/chaotix345/rigtune/actions/runs/36297418835): every leg red on
`tickHookOnVsReference`, the ns backstop green.

| leg | CPU | tickHookOnVsReference (limit 2.05) | the work twice | tickHookNsPerCallOn (limit 653) |
|---|---|---|---|---|
| 26.2 OpenGL | AMD EPYC 7763 | **2.839** | 5.633 | 79.07 |
| 26.3 OpenGL | AMD EPYC 7763 | **2.859** | 5.748 | 79.94 |
| 26.3 Vulkan | Intel Xeon 6973P-C | **2.704** | 5.407 | 64.08 |

`java.lang.AssertionError: footprint budget tickHookOnVsReference: 2.84 > 2.05 (…)` (26.2 OpenGL; the others alike).

The same code without the slowdown, run [36297288375](https://github.com/chaotix345/rigtune/actions/runs/36297288375) (b9f5c002), all green:

| leg | CPU | tickHookOnVsReference | the work twice (self-check, must exceed 2.05) | tickHookNsPerCallOn |
|---|---|---|---|---|
| 26.2 OpenGL | AMD EPYC 9V45 | 1.311 | 2.643 | 30.26 |
| 26.3 OpenGL | AMD EPYC 9V74 | 1.498 | 2.967 | 46.40 |
| 26.3 Vulkan | AMD EPYC 7763 | 1.550 | 3.075 | 43.11 |

FrameHookBudgetTest in that run's java job: `frameHookOnVsReference` 1.371 / 1.375 (26.2 / 26.3; twice the work 2.744 /
2.748; limit 1.65), `frameHookOnPhasesVsReference` 0.945 / 0.946 (1.892 / 1.894; limit 1.32).

## The first attempt, and the calibration

- Run [36295129832](https://github.com/chaotix345/rigtune/actions/runs/36295129832) (4e0acd3e, the same slowdown on the
  first version of the timing, reverted by 406714be): red on 26.2 OpenGL (2.115) and 26.3 Vulkan (2.146), green on
  26.3 OpenGL (EPYC 9V45, 1.829): the reference loop ran 1.3-1.8x slow under 250-380 ms of JIT compilation during the
  blocks. Fixed with compiled loop methods and a JIT-quiet wait (ws-ci.md), then recalibrated.
- Tick calibration (branch `research/v05-ci`, `ci-experiment.yml`, TimingProbe2GameTest): runs
  [36295704245](https://github.com/chaotix345/rigtune/actions/runs/36295704245) and
  [36296194458](https://github.com/chaotix345/rigtune/actions/runs/36296194458), 192 measurements on 6 CPU models: 1x at
  most 1.745, the monitor's tick doubled at least 2.462.
- Frame calibration: run [36296732786](https://github.com/chaotix345/rigtune/actions/runs/36296732786), 16 runners x 3,
  5 CPU models: monitor on 1x ≤ 1.419, 2x ≥ 1.920; with the phase timers 1x ≤ 0.957, 2x ≥ 1.833.
