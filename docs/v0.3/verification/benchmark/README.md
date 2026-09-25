# Benchmark verification (WS-E: SPEC 3f and 8)

## AC3.7: a real Tune records chunk counts that grow with the render distance

`./gradlew :<mc>:runBenchmarkAutorun -PrigtuneAutorun=benchmark-world-tune`: a plain client (no game-test harness) runs the full default Tune (6 render distance steps, 8 s sweeps, 20 s settle timeout) in the benchmark world, starting at render distance 8 (`renderDistance:8` in the run dir's options.txt, fresh benchmark world). Run on 2026-09-26 at commit 3b2b2d4, on the development PC (Ryzen 7 7800X3D, RX 7800 XT, 2560×1440 at 180 Hz, so the target is 170 FPS), under the machine-wide game lock. 26.3 started first time (no OpenAL crash). The logs are the RigTune lines of each run's latest.log: [autorun-26.2.log](autorun-26.2.log), [autorun-26.3.log](autorun-26.3.log).

Per render distance step: the chunks within RD − 1 of the camera present on the client when the step settled (the settle circle), the chunks the client held then, and the settle time.

| RD | circle | 26.2 present | 26.2 client holds | 26.2 settle | 26.3 present | 26.3 client holds | 26.3 settle |
|---|---|---|---|---|---|---|---|
| 8 | 149 | 149 | 267 | 4.4 s | 149 | 271 | 3.1 s |
| 10 | 253 | 253 | 454 | 2.0 s | 253 | 473 | 2.0 s |
| 14 | 529 | 529 | 716 | 3.5 s | 529 | 726 | 2.0 s |
| 22 | 1,373 | 1,373 | 1,694 | 10.6 s | 1,373 | 1,708 | 6.3 s |
| 32 | 3,001 | 2,999 | 3,468 | 20.0 s (timeout) | 3,001 | 3,550 | 12.3 s |

- The chunk counts grow with every step on both versions. Before the fix, the research probe saw the client stuck at 162 (26.2) and 167 (26.3) chunks after RD 5 → 12, for 120 s (docs/research/v0.3/benchmark.md §1.2): the up-steps measured the starting chunk set.
- 26.2's RD 32 step hit the 20 s timeout with 2 of 3,001 chunks missing (0.07%). That's within the 2% allowed (E-M1), so the step still counted. (Its log line in this run reads "timed out waiting for the sections"; since 4813ac1 it says "timed out with 2 missing (within the 2% allowed)".) Everything was present by the repeats (3,001 of 3,001, client 3,725).
- Both chose RD 32 (the maximum): every step met 170 FPS in 1% lows on this machine (26.2: 594, 592, 392, 426, 250; 26.3: 602, 646, 559, 525, 290). The 1% lows now fall overall as the terrain grows (594 at RD 8 to 250 at RD 32 on 26.2), which is the cost the up-steps are meant to measure.

## Game tests (CI, every leg)

Run 36175800883 (all jobs green; legs 26.2 OpenGL, 26.3 OpenGL, 26.3 Vulkan on 4-vCPU ubuntu-24.04 runners). From each leg's latest.log (`Benchmark game test:` lines):

| | 26.2 OpenGL | 26.3 OpenGL | 26.3 Vulkan |
|---|---|---|---|
| Tune from the harness's RD 5, steps | 5, 7, 11, 12 | 5, 7, 11, 12 | 5, 7, 11, 12 |
| RD 12 settle (60 s timeout) | 1.2 s | 0.9 s | 0.7 s |
| RD 11 settle | 14.0 s | 10.4 s | 9.3 s |
| missing within 11 at the RD 12 step's first frame | 0 of 377 | 0 of 377 | 0 of 377 |
| server's requested view distance after the finished / cancelled / current-world Tune | 5 / 5 / 5 | 5 / 5 / 5 | 5 / 5 / 5 |
| scene fingerprint | floor 117 minecraft:forest; within 128: water 0.0%, height sd 18.5, range 57; within 192: 4 biomes >= 5%, water 14.7% | same | same |
| camera | y 133 = floor 117 + 16, both blocks air | same | same |

Screenshots reviewed: `bench-menu-world` (the CURRENT-scene note under the scene hint), `bench-world-tune-result` (the 5/7/11/12 table), `bench-world-running` (the new camera spot above the canopy).
