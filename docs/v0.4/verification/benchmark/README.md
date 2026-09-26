# AC7.6: benchmark history real run (P5-A, 2026-09-26/27)

Release candidate: `origin/feat/v0.4.0` @ a3f5c14 (code = 9cf84f6), `rigtune-0.4.0-dev+mc26.2.jar` sha256 `149f20f9…dd11`.
One scratch instance (`p5a-inst-bench262`): production 26.2 client, fabric-api 0.161.0+26.2, Sodium 0.9.2, 1280×720
window, SD 8. Each run is RigTune's own **Measure** in its benchmark world started the way the menu's Measure does
(`BenchmarkController.tryStart(MEASURE, BENCHMARK_WORLD, defaultConfig())`, through the test-only driver
`../p5a-tools/driver`), then the result screen and Benchmark history are screenshotted, and the notices logged.
One client at a time under the lock; the runs are hours apart only because other verification runs were interleaved.

| run | conditions | RigTune result (avg / 1 % low / cv) | wording on the result screen | screenshot |
|---|---|---|---|---|
| bh1 | RD 8 | 3,101 / 630 / 1.9 % | "Not enough comparable runs for a trend yet (0 of 3)" | `p5a-bh1-result.png` |
| bh2 | RD 8 | 3,132 / 634 / 4.0 % | "… (1 of 3)" | `p5a-bh2-result.png` |
| bh3 | RD 8 | 2,736 / 435 / **36 %** | "Results were noisy (36% spread): close background apps and retry." + "… (2 of 3)" | `p5a-bh3-result.png` |
| bh4 | **RD 16** (RD + 8) | 2,181 / 566 / 15 % | "Results were noisy (15% spread) …" + "Not enough comparable runs for a trend yet (0 of 3)"; Benchmark history: "1 comparable run; 3 with different conditions not shown" | `p5a-bh4-result.png`, `p5a-bh4-benchhist.png` |
| bh5 | RD 8 again | 2,561 / 435 / 1.7 % | **"1% lows 31% below your usual 630 FPS since 2026-09-26"** + **"No change recorded; possibly a driver, OS or other change"**; notice `benchmark.regression.…` with the same text, actions Details… / Got it | `p5a-bh5-result.png`, `p5a-bh5-benchhist.png` |
| bh6 | RD 8, **shaders on** (Iris 1.11.4 + Complementary Reimagined r5.9.3, `enableShaders=true`) | 591 / 384 / 4.6 % | **"Performance changed under different conditions (shaders); cause unknown."**; Benchmark history's selector now shows "shaders: ComplementaryReimagined_r5.9.3.zip" | `p5a-bh6-result.png`, `p5a-bh6-benchhist.png` |

Raw numbers: `table.md` (RigTune's result from `benchmarks.json` plus the driver's pooled frames), full records in
`benchmarks.json`, driver output `p5a-bh<n>.json`, log lines `bh-log-excerpt.txt`.

## Verdict
- **Not-comparable wording after a change that matters: PASS** (bh6, shaders on): "Performance changed under different
  conditions (shaders); cause unknown." Only the differing key is named; adding Iris also changed the mod set, which is
  (per B-H1) not part of comparability.
- **RD + 8 (bh4): no claim, honestly so.** WS-B only says "different conditions" when the previous run of the scene
  differs past 2 × the larger CV; the previous run (bh3) was noisy (36 % spread), so a 30 % 1 %-low difference sits
  inside the noise and the screen says "Not enough comparable runs for a trend yet (0 of 3)". That matches ws-b.md
  deviation 2 ("within the noise nothing is claimed"); a clean previous run would have been needed. Recorded, not a defect.
- **Regression wording: PASS, and seen for real** (bh5): the same conditions as bh1-bh3, 1 % low 435 vs their median
  630 (floor 2 × max(cv 1.7 %, 1.4826 × MAD / median) ≈ 3.4 %), anchored at the most recent comparable run (bh3,
  2026-09-26), nothing in History between them → "No change recorded; possibly a driver, OS or other change". The
  machine really was slower then (avg 2,561 vs 3,101-3,132 FPS at bh1/bh2 with the same settings; other agents' builds
  were running on this PC), so the alert and its hedged wording fit.
- The trend chart shows comparable runs only, with the "usual" median line (bh5), and the noisy-run warning appears
  where the spread was high (bh3, bh4).
- Earlier, in the overhead instance (`../stutter/F-overhead/`), the fourth and later comparable Measure runs showed
  "1% lows in line with your usual 629 FPS (4 comparable runs)", the in-line branch.
