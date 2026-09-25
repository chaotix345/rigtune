# v0.3 research: benchmark follow-ups (P1 item 8)

Research and evidence only. No product code was changed: the probe below ran in a throwaway worktree (`research/bench`), which has since been deleted. Web sources were accessed 2026-09-26. Anything not independently confirmed is marked **UNVERIFIED** with the reason. Screenshots and raw outputs are in the session scratchpad, `C:/Users/Admin/AppData/Local/Temp/claude/C--Dev-Minecraft-Setting-Optimisation-Mod/4bf644a6-501a-4093-a81e-9e29a6010059/scratchpad/r-bench/` (called `r-bench/` below). They are not in the repo.

## TL;DR

- **(c) The benchmark world is fine on both versions, and it is the same terrain.** The camera sits on a forested hilltop (forest 100% within 80 blocks; ground 66 to 133), with an ocean and beach 130 to 200 blocks to the east and north-east. Nothing is flat and nothing is ocean. Worldgen noise (terrain height and biome) matches exactly on 26.2 and 26.3 at 9,409 of 9,409 samples over ±768 blocks. Only tree placement differs, which is why the camera is at y 127 on 26.2 and y 133 on 26.3: there is a tree canopy on the camera column on 26.3. v0.2's note that "26.2 and 26.3 generate different terrain" (`docs/v0.2/design/ws-c.md`, "Camera spot") is only half right: features differ, terrain doesn't. No seed or location change is needed. An automated check with calibrated thresholds is proposed in §2.6.
- **New finding, and the most important result here (§1): benchmark render-distance steps above the join-time RD add no terrain.** The benchmark changes RD in memory only, so the server is never told the new requested view distance. The Tune up-steps (+2/+4/+8, up to 32) are therefore measured with the start-RD chunk set, and Tune can recommend a render distance the machine can't sustain. This was A/B-verified on both versions. The fix is one call (`Options.broadcastOptions()`), plus a settle check that waits for the chunks to arrive.
- **(b) History-median comparison:** low risk, about 1.5 to 2 days. Today it has no data to work with: the user has no `benchmarks.json`, and records don't store enough context (Measure runs don't record DH or shader state, and nothing records the resolution) to know which runs are comparable. Recommendation: record an optional `context` object now (0.25 days) and defer the comparison line.
- **(a) Shader profile suggestion:** a generic suggestion that *applies* a profile isn't safe. Iris' public API has no profile or option access. Profiles are pack-defined, and their names and order aren't standardised. The "current profile" isn't stored anywhere; Iris infers it by matching option values. A read-only **advice line** driven by the measured shader cost is safe and small (0.5 to 1 day).
- **Build:** §1 (RD fix, must), (c) check, (a) as advice only, and the (b) `context` field. Defer the (b) UI. Acceptance criteria are in §5.

---

## 1. New finding: RD steps above the join-time value measure the old chunk set
The coordinator has taken this into `docs/v0.3/SPEC.md` as a P0 defect (3f).

### 1.1 Mechanism (verified in bytecode, 26.2 and 26.3)
- The benchmark applies render and simulation distance with `SettingsBridge.applyVanilla(minecraft.options, vanilla, false)` (`ClientKnobs.java:58`). `save=false` skips `options.save()` (`SettingsBridge.java:202`).
- In `net.minecraft.client.Options`, **only `save()` calls `broadcastOptions()`**. `javap -c` shows `save()` at offset 121 → `broadcastOptions()`, and no other class in the client jar references it. `broadcastOptions()` just sends `ClientPacketListener.broadcastClientInformation(buildPlayerInformation())`, which carries render distance, language, chat and skin settings. It writes no file.
- The server's per-player chunk radius is `ChunkMap.getPlayerViewDistance(player) = Mth.clamp(player.requestedViewDistance(), 2, serverViewDistance)` (javap, both versions). The integrated server does raise `serverViewDistance` to the client's RD (logged: `serverView 12`). But `requestedViewDistance` stays at the value last broadcast, which is the RD at join or at the last options save.

### 1.2 A/B evidence (benchmark world, harness, Sodium 0.9.2 loaded)
The harness joins at RD 5. The probe then set RD 12 in memory, the same way `ClientKnobs` does:

| | 26.2 | 26.3 |
|---|---|---|
| after 10 s, RD set in memory only | 162 client chunks loaded; 215 of the 377 chunks within 11 of the camera missing | 167 loaded; 210 of 377 missing |
| same, left for 120 s (26.2, separate run) | stuck at 167; 210 missing for the whole 120 s | — |
| ~1 s after `mc.options.broadcastOptions()` | 637 loaded; 0 missing | 597 → 637 loaded; 0 missing |
| `sectionsReady()` (Sodium `isTerrainRenderComplete`) during the stall | **true** | **true** |

Logs: `r-bench/run-26.2c.log` (the 120 s stall), `r-bench/run-26.2d.log` and `r-bench/run-26.3.log` (the A/B).

### 1.3 Impact
- `RenderDistancePlanner.next()` starts at the player's RD and, while every step passes, steps up +2, +4, +8… to `MAX_RD` 32 (`RenderDistancePlanner.java:47`, `BenchmarkController.java:54`). Every up-step renders the start-RD chunks plus an empty far plane. So it tends to pass, and Tune overstates the affordable RD on capable machines. The chosen RD is applied through `KeepSettings`, which saves and so broadcasts. Only then does the real cost appear.
- The same applies to both scenes, and to multiplayer (same `ChunkMap` code; the server cap still applies). Down-steps are fine, because the client culls locally.
- The v0.2 autoruns weren't affected: they started at RD 16 and 12 and only stepped down (`docs/v0.2/verification/README.md` §(e)). The user's instance is at RD 32 (= `MAX_RD`), so it would only be hit after lowering RD.
- Second, related gap: the settle check trusts `sectionsReady()` alone (`BenchmarkController.java:375`, `:602`). Sodium's `isTerrainRenderComplete()` was **true while 210 in-range chunks hadn't arrived**. On a real world, where new chunks must also be generated, the same could happen even after the broadcast fix. Settle should also require the in-range chunks to be present on the client (`ClientChunkCache.hasChunk`), as the probe did.

### 1.4 Fix (proposal)
Call `minecraft.options.broadcastOptions()` after `applyVanilla(..., false)` in `ClientKnobs.apply` whenever render distance changed, and on every restore path, so the server's requested distance returns to the original. Add a chunk-presence condition to SETTLE (all chunks within RD−1 of the camera, same 20 s timeout, and log the missing count on a timeout). Effort: **0.5 to 1 day**, including a game-test assertion. Risk: low. It's the same packet vanilla sends on every options save, and `ClientInformation` is already sent at join.

---

## 2. (c) Benchmark-world terrain on 26.2 and 26.3

### 2.1 The code
- Seed `8675309`, camera `(0, surface + 10, 192)`: `BenchmarkWorld.java:44-47`. The world is `WorldOptions(SEED, generateStructures=true, bonusChest=false)` with the NORMAL preset and `WorldDataConfiguration.DEFAULT` (`:173`).
- The 3×3 chunks around the camera are generated first, then `surface = getHeight(MOTION_BLOCKING, 0, 192)` (includes leaves), then `tp @a x y z 0 0` (`:291-296`).
- The sweep is a full 360° yaw from the start yaw, at pitch 0 and then pitch 25° down, 8 s each (`BenchmarkController.java:166`, `:401`; `Timing.java:10-11`). The start yaw is 0 (south).

### 2.2 Method
There's a research-only client game test `TerrainProbeGameTest` in the throwaway worktree, with the harness limited to that class. It:
1. opens the world through the product path, `BenchmarkWorld.open(...)`, and waits for `READY`;
2. sets RD 12 and calls `broadcastOptions()` (see §1), then waits until every chunk within 11 of the camera is on the client and sections are ready for 5 s;
3. hides the HUD and takes 8 screenshots at yaw 0/90/180/270 (S/W/N/E), each at pitch 0 and 25° (the sweep's two pitches);
4. surveys on the server thread via `server.execute`:
   - a noise map on a 16-block grid over ±768 blocks: `ChunkGenerator.getBaseHeight(x, z, OCEAN_FLOOR_WG, level, randomState)` and `ServerLevel.getUncachedNoiseBiome`. This is pure worldgen, with no chunk generation and no dependence on render distance;
   - the generated surface of the loaded chunks every 2 blocks within r = 80/128/192: `MOTION_BLOCKING` top block, `OCEAN_FLOOR` ground height, and `getBiome`;
   - the nearest structures: `findNearestMapStructure(StructureTags.X, camera, 16, false)`.

Runs: 26.2 `BUILD SUCCESSFUL` (1m39s), 26.3 `BUILD SUCCESSFUL` (1m00s, first try, no OpenAL crash). A first 26.2 attempt without the broadcast took its screenshots before the RD 12 chunks arrived (sky below the horizon to the E/N). That is how §1 was found; those screenshots were discarded.

### 2.3 What the camera sees
The screenshots I relied on are all at 854×480, RD 12:

| view | 26.2 (camera y 127) | 26.3 (camera y 133) |
|---|---|---|
| S, level | `r-bench/262-0000_probe-rd12-S.png`: rolling oak/birch forest on hills to the horizon | `r-bench/263-0000_probe-rd12-S.png`: same hills, different trees |
| W, level | `262-0001_…-W.png`: forest, a terraced grass slope | `263-0001_…-W.png`: forest |
| N, level | `262-0002_…-N.png`: forest; sea at the edge to the NE | `263-0002_…-N.png`: forest; sea and a sand beach across the view (6 blocks higher) |
| E, level | `262-0003_…-E.png`: the forest falls away to the sea at the lower left | `263-0003_…-E.png`: same, sea and beach at the lower left |
| S/W/N/E, 25° down | `262-0004…0007_…-down.png`: canopy, grass, terraces | `263-0004…0007_…-down.png`: canopy, grass, terraces, sea and beach (N, E) |

Judgement:
- **Varied enough, and not a degenerate scene.** It's hilly terrain (ground 66 to 133 within 80 blocks), and dense foliage covers about half the columns. Leaves are cutout geometry with many faces, so this is a realistic, moderately heavy load. Water and beach come into view only at RD ≥ ~9 (none within 128 blocks).
- **What's missing:** mountains or snow, visible caves or ravines, villages (the nearest is 596 blocks away), and lava at the surface. Sky fills about half of each level-pitch frame, because the camera is on a hilltop; the 25° sweep offsets that.
- The existing v0.2 26.3 screenshot at the harness's RD 5 (`docs/v0.2/verification/img/a263-0019_bench-world-running.jpg`) shows the same wooded hilltop.

### 2.4 Numbers

Generated surface (server heightmaps, loaded chunks; "missing 0" at every radius):

| radius | version | water | leaves | ground min..max (sd) | top blocks (distinct) | biomes |
|---|---|---|---|---|---|---|
| 80 (≈RD 5) | 26.2 | 0.0% | 48.2% | 66..133 (13.4) | grass 51.5, oak leaves 38.9, birch leaves 9.3, stone, dirt (5) | forest 100% |
| 80 | 26.3 | 0.0% | 49.4% | 66..134 (13.5) | grass 50.2, oak leaves 41.1, birch leaves 8.3, stone, log (5) | forest 100% |
| 128 (RD 8) | 26.2 | 0.3% | 46.5% | 62..133 (18.7) | 8 distinct | forest 90.6, old-growth birch 9.4 |
| 128 | 26.3 | 0.4% | 46.7% | 60..134 (18.7) | 9 distinct | forest 90.7, old-growth birch 9.3 |
| 192 (RD 12) | 26.2 | 14.8% | 37.2% | 38..133 (20.8) | + water, sand, gravel, andesite, lava, seagrass (18) | forest 56.3, old-growth birch 22.9, river 7.4, plains 6.5, ocean 2.9, birch forest 2.2, beach 1.8 |
| 192 | 26.3 | 14.8% | 36.6% | 34..134 (20.8) | (19) | identical to 26.2 |

Nearest structures (identical on both): mineshaft 125 blocks (underground, not visible), ruined portal 244, shipwreck 305, ocean ruin 519, village 596. None is visible within RD 12.

Biome map from the noise, 64-block cells over x −768..768 (W→E) and z −576..960 (N→S). `@` is the camera; F forest, B old-growth birch, b birch, p plains, m meadow, r river, `.` beach, `:` stony shore, o ocean, O deep ocean, s/S savanna/plateau, d dark forest, c cherry grove, j jungle, t taiga.
```
sssssssssssroooooo:ddddBB
rsssssssssssoooooo:ddddBB
ssssssssssssoooooooodddBB
sssssSsssssp:ooooooooooBB
sssSSsppppprFooooOOOooodt
sSSsssppFFrrFFooOOOOodddt
ssssSssFFFFFFFrooOOoodo::
rsssSSssSFmFFbrooOOor:d:o
FsssSsssmmFrFboooOOo::r:o
FsssssrpmmprFFooooooo:doo
FFsssppppprFFFooooooodb:o
FrssSmprpppFFFBooOOOoob.o
ppsspmprppFF@FBBooOOOOoOO
ppsrppprpFFFFFB.ooOOOOOOO
FFSppppppFFBBBBrooOOOOOOO
FFSmpcccFFbBbbbr.ooOOOOOO
FFSmppppFFBBbbbrFooOOOOOO
FrrppprFFBBbbbFFFooOOOoOO
pFFFFFFrFBrbbbFFooooooooO
ppFFFFFFrrmbmFFFr:ooOooOO
jFFFFrmmFbmmmFprppooOOOOO
jFFFpFmmmmmmrppppppoOOOOO
jpFpFFFFFmmFppp:ooooOOOOO
jpFFsSFFFFFrpproooOOOOOOO
FpFSsssSSFSspprooOOOOOOOO
```

### 2.5 26.2 compared with 26.3
- **Noise terrain is bit-identical.** All 9,409 samples of the ±768 map have the same floor height and biome (`r-bench/terrain-probe-26.2.csv` and `-26.3.csv`; max |Δh| = 0). The camera column's noise floor is 117 on both.
- **Decoration differs.** Trees are placed differently: the screenshots differ tree by tree, and leaf shares differ by 1 to 2 points. On 26.3 a canopy covers (0, 192), so `MOTION_BLOCKING` = 123 and the camera goes to y 133 instead of 127. Each version is deterministic across fresh creations: y 127 on 26.2 and y 133 on 26.3 in WS-C, in the v0.2 autoruns, in all four 26.2 probe runs here (each a fresh run dir, so a fresh save) and in the 26.3 probe run.
- Cross-version FPS isn't comparable anyway. The save is recreated per MC version, and history and pairs are already keyed by `mcVersion` (`BenchmarkHistory.java:157`, `:172`).
- Side note: sampling the noise is about 10× faster on 26.3 (9,409 samples in 1.4 s, against 15.2 s on 26.2). 26.3 refactored the density-function code (`RandomState.samplersWithContext`, `MaterialSystem`; javap).
- The user's instance (`docs/v0.2/verification/b-26.2/rigtune-smoke-report.txt`) has no worldgen-changing mods (no Terralith or Tectonic). C2ME and Structure Layout Optimizer are present and claim vanilla-identical output. **UNVERIFIED** for this seed: I didn't run the probe with the user's mods. A worldgen mod would change the scene silently, and the check in §2.6 would catch it if it ran in production too.

### 2.6 Proposed automated check
Keep it deterministic and independent of render distance: sample the **noise** (as the probe did), not the loaded chunks.
- A pure core function `SceneVariety.check(samples)`, unit-testable, takes (dx, dz, floorHeight, biomeId) samples on a 16-block grid, plus sea level. It fails when any of these holds:
  1. **ocean:** more than 50% of the samples within r ≤ 128 are below sea level;
  2. **flat:** within r ≤ 128, the floor-height standard deviation is below 8 or the range is below 24 blocks;
  3. **monotone:** fewer than 2 biomes each cover ≥ 5% of the samples within r ≤ 192.
- Calibration, 361 candidate centres on a 64-block grid within ±576 of the camera, same seed:

  | rule | rejects |
  |---|---|
  | ocean | 129 (e.g. (64, −384): 79% water, lukewarm ocean) |
  | flat | 9 (savanna and plains, e.g. (−192, −384): sd 5.8) |
  | monotone | 0 |
  | pass | 223 |

  Distribution at r ≤ 128: water p10/p50/p90 = 5/22/99%, sd 6.2/12.6/23.7, range 34/62/93.
- The current camera is well inside every limit: r ≤ 128 water 0%, sd 18.5, range 57, 2 biomes; r ≤ 192 4 biomes ≥ 5%, water 14.7%.
- Where it runs: in `BenchmarkGameTest` after the benchmark world reaches `READY`, on the server thread through `server.execute` and a volatile result, polled with `waitFor`. This worked in the harness. The cost is 197 + 441 samples: about 1 s on 26.2 and under 0.1 s on 26.3.
- It also logs a **scene fingerprint**: camera noise floor, biome, and the three metrics. Expected today: `floor 117, minecraft:forest` on both versions. The value is the prompt to look again when item 1 adds a new MC version.
- Optional, in production: run the same check once when the world is created, and log a warning (not a refusal) when it fails, e.g. because a worldgen mod or datapack changed the scene.

### 2.7 Fix, if the view were poor, and one small improvement
No seed or location change is needed. Optional: derive the camera height from the noise floor rather than from a heightmap that includes leaves: `y = getBaseHeight(0, 192, OCEAN_FLOOR_WG) + 16`, which is **133 on both versions**. The camera spot then no longer depends on tree placement, which is what differs between versions. This moves the 26.2 camera from 127 to 133, so earlier 26.2 benchmark-world runs aren't comparable to later ones. The user has none.

Effort for (c): the check, unit tests and the game-test hook, **0.5 to 1 day**; the camera-height change 0.1 day. Risk: low. Test code only, apart from the optional production warning.

---

## 3. (b) Latest run compared with the median of the history

### 3.1 What exists
- `config/rigtune/benchmarks.json`, `schemaVersion` 1, holds the last 50 finished runs, oldest first (`BenchmarkHistory`). Its files survive corruption and downgrades (`.bad`, `.newer`).
- A record (`BenchmarkRecord`) has `id`, `createdAt`, `rigtuneVersion`, `mcVersion`, `mode`, `scene`, `phase`/`pairId`, `targetFps`, `targetMet`, `knobs` (RD and SD value/original with stats), `result` (avg, 1% low, p99, repeats, CV), `costs`, `notMeasured`, `world`, `deadlineHit`.
- `result` is 2 full-protocol repeats at one knob set in both modes. Tune measures the chosen settings, Measure the current ones (`BenchmarkSession.java:171-173`). The two are comparable when the knobs and context match.
- Reusable pieces:
  - `BenchmarkHistory.chart(scene, mcVersion, max)` already filters by scene and version (`:172`);
  - `BenchmarkMath.cv/aggregate/gainPercent/percent`, and the 2× noise-floor rule `gain()` (`BenchmarkMath.java:77`; floor = 2 × the larger CV, default CV 5%);
  - the result screen's line list and the 10-run bar chart (`BenchmarkResultScreen.java:35`, `:59`, `:135`).

### 3.2 What blocks a meaningful median today
- **The comparable set can't be identified from the records.**
  - **Measure runs never record DH or shader state.** Cost reports run only in TUNE (`BenchmarkSession.java:271`), so a Measure run with shaders on looks the same as one with shaders off. The v0.2 evidence shows a 3.5× difference in average FPS between the two (676 against 2,372; `docs/v0.2/verification/README.md` §(f)).
  - The shader pack name, framebuffer size and fullscreen state aren't recorded either.
- **The CURRENT scene is position-dependent.** Only the benchmark world is a fixed scene, so the median should use `scene == BENCHMARK_WORLD` only.
- **0.2.x Tune results with chosen RD > original RD are biased** (§1). Requiring the new context field excludes every 0.2.x run, which fixes this.
- **The user has no history** (no `benchmarks.json`). The feature shows nothing until at least 3 comparable earlier runs exist.

### 3.3 Design (if built)
- **Record** (0.3.0, optional field, no schema bump): `context: { dhRendering, shaders, shaderPack, width, height, fullscreen, protocol: 1 }`.
  - Gson ignores unknown fields, so the pinned 0.2.0 reader loads it.
  - 0.2.0 rewriting the file drops `context` from older runs. That is a lossy downgrade that doesn't break anything: those runs just stop counting as comparable.
  - Mods, drivers and other video settings are deliberately left out. Detecting their effect is the point of the comparison ("did that driver or mod make it slower?").
- **Comparable to latest `L`:** an earlier run `R` with
  - the same `scene` = BENCHMARK_WORLD and the same `mcVersion`;
  - `result != null` and `result.repeats >= 2`;
  - the same RD and SD `value`;
  - `context` present and equal on all fields.

  Include both phases, and exclude `L` itself.
- **Statistic:** needs n ≥ 3. m = median of the comparable 1% lows (also the avgs); Δ = gainPercent(m, L.low). Noise floor = 2 × max(L.cv or 5%, 1.4826 × MAD(lows)/m) × 100, which extends the existing 2× rule. MAD is a robust spread, so one outlier run doesn't inflate the floor.
- **UI:** one line under the gain line. "Compared with your usual (median of N runs, same settings): 1% lows −14%, avg −9%" in red or green when |Δ| ≥ the floor. Otherwise "In line with your usual (median of N runs)". Nothing when n < 3. Don't draw the median on the chart: the chart also shows runs that aren't comparable.
- **Effort:** core, `HistoryComparison` and unit tests 0.75 day; the `context` field plus compatibility fixtures 0.5 day (0.25 day on its own); UI line, lang strings and game-test screenshot 0.5 day. **Total about 1.5 to 2 days.** Risk: low, since it's read-only and changes no settings.

---

## 4. (a) Shader-pack profile suggestion from the measured cost

### 4.1 What the cost report measures today
At the chosen knobs, a Tune run measures a quick-protocol baseline (a 6 s level sweep) and then the same with shaders off, via `IrisApi.getConfig().setShadersEnabledAndApply(false)`, which reloads the pipeline (`BenchmarkSession.java:191`, `IrisCompat.java:23`). It reports % gains in 1% low and average and stores them in `costs.shaders`. The whole pack is either on or off: nothing about options or profiles is measured. v0.2 evidence with MakeUp-UltraFast on 26.2: 1% low 502 → 601 (+20%), avg 676 → 2,372 (+251%).

### 4.2 How profiles work (verified)
- **Pack side:** `shaders.properties` defines `profile.NAME=<options>`. Tokens are `OPTION=value`, `OPTION` or `!OPTION` (booleans), `profile.OTHER` (inherit), and `!program.X` (disable a program). "The current profile is detected based on the selected option values. If no profile matches the current option values, the profile 'Custom' is selected." Source: [OptiFine shaders.properties doc](https://raw.githubusercontent.com/sp614x/optifine/master/OptiFineDoc/doc/shaders.properties) (accessed 2026-09-26).
- **Names and order aren't standardised:**
  - Complementary Reimagined (the user's pack in v0.2 research) defines `POTATO, VERYLOW, LOW, MEDIUM, HIGH, VERYHIGH, ULTRA, COMPLEMENTARY`, 14 options each ([shaders.properties](https://raw.githubusercontent.com/ComplementaryDevelopment/ComplementaryReimagined/main/shaders/shaders.properties));
  - MakeUp-UltraFast uses `no_effects, shadowless_low, shadowless_medium, shadowless_high, low, medium, high, extremeplus` ([shaders.properties](https://raw.githubusercontent.com/javiergcim/MakeUpUltraFast/master/shaders/shaders.properties)).
- **Iris side** (javap of Iris 1.11.4+mc26.2 and 1.11.6+mc26.3, identical):
  - `ProfileSet.scan(OptionSet, OptionValues)` returns the `current` profile (Optional) plus `next`/`previous`. The current profile is **inferred by matching** (`Profile.matches`), never stored.
  - `ProfileSet` sorts profiles by `Profile.precedence`, which is `optionValues.size()` (the `Profile` constructor). "Previous" is therefore *fewer options set*, not *lower quality*: fine for Complementary, where each profile sets 14 and the declared order holds, but not a general rule.
- **Where the values are stored:** `shaderpacks/<pack file name>.txt` is a `java.util.Properties` file (`Iris.loadExternalShaderpack` concatenates `"\u0001.txt"`; `tryReadConfigProperties` uses `Properties.load`). Applying a profile means writing that pack's option values into this file.
- **Public API:** `IrisApi` v0 exposes only `isShaderPackInUse`, `getConfig().areShadersEnabled()` / `setShadersEnabledAndApply(boolean)` and rendering hooks. It has **no option or profile access**. `Iris.queueShaderPackOptionsFromProfile(Profile)` and the reload are internal classes.

### 4.3 Can a generic suggestion be safe?
- **Applying or measuring a lower profile: not safely.**
  - It needs Iris internals (fragile across Iris versions) or writing `shaderpacks/<pack>.txt`. That changes the user's pack settings, and needs a restore marker, undo and a journal.
  - Which profile is "lower" is a per-pack guess (§4.2).
  - Effort 3 to 5 days, high risk. **Not recommended.**
- **A read-only advice line: safe, and generic enough.**
  - Rule: when a Tune run measured the shader cost, the target was **missed with shaders on** (baseline 1% low < target) and **met with shaders off** (off 1% low ≥ target), show "Your shader pack is what keeps you below N FPS. In Iris' shader pack settings, choose a lower Profile or lower shadow quality."
  - This is falsifiable from stored numbers alone and changes nothing.
  - Optional: read the active pack's `shaders/shaders.properties` (zip or folder, from `iris.properties` `shaderPack`) and list its profile names in declared order, or name shadow settings when it has none.
  - The quick protocol has a single sample per side, but the rule compares against the target, not a small %, so the noise floor matters less. Still, require the off − on 1% low gap to be ≥ 10% (= 2 × `NOISY_CV`).
  - **UNVERIFIED:** the exact Iris menu path to the Profile button. I didn't open it in-game, and the button's position is set by each pack's `screen=` line (Complementary: `screen=… SHADER_STYLE <profile> RP_MODE …`). Also UNVERIFIED: that a naive `profile.` line scan is correct for packs that wrap lines in `#ifdef` (shaders.properties is preprocessed).
- **Effort:** advice line 0.5 day; with profile names, 1 day. Risk: low.
- **Value:** only users with a shader pack on who run Tune. The user has Complementary installed but had shaders off in v0.2.

---

## 5. Recommendation and draft acceptance criteria

| candidate | value | effort | risk | verdict |
|---|---|---|---|---|
| §1 RD broadcast + chunk-presence settle | fixes a Tune correctness bug on every up-step | 0.5–1 d | low | **build (must)** |
| (c) scene-variety check + fingerprint (+ camera height) | guards the scene for new MC versions (item 1); cheap | 0.5–1 d (+0.1) | low | **build** |
| (a) shader advice line (read-only) | useful for shader users who miss their target | 0.5–1 d | low | **build** (advice only; no profile switching) |
| (b) `context` field only | makes future history comparable | 0.25 d | low | **build** |
| (b) median comparison UI | zero value until ≥3 comparable runs exist | 1.25–1.75 d more | low | **defer to v0.4** (written reason: no history; needs `context` data first) |

Draft ACs (numbering for the coordinator to adjust):
- **AC8.1** After every benchmark render-distance change and on every restore path, RigTune calls `Options.broadcastOptions()` without writing options.txt (unit test on `ClientKnobs` with a counting fake).
- **AC8.2** A game test joins the benchmark world at RD 5, runs a benchmark step at RD 12, and asserts that every chunk within 11 chunks of the camera is loaded on the client before that step records its first frame (26.2 and 26.3).
- **AC8.3** A step's settle phase ends only when every chunk within RD−1 of the camera is present on the client and the sections are built, or at the 20 s timeout, which logs the number of missing chunks.
- **AC8.4** After a finished or cancelled Tune, the integrated server's `ServerPlayer.requestedViewDistance()` equals the player's restored render distance (game test).
- **AC8.5** `SceneVariety.check` rejects an all-ocean grid ("ocean"), a flat grid ("flat") and a single-biome grid ("monotone"), and accepts the grid recorded around the benchmark camera (unit tests; thresholds: water > 50% within 128, sd < 8 or range < 24 within 128, fewer than 2 biomes ≥ 5% within 192).
- **AC8.6** `BenchmarkGameTest` samples the benchmark world's noise around the camera on the server thread and fails if `SceneVariety.check` rejects it, on every supported MC version.
- **AC8.7** The game test logs the scene fingerprint, and it reads `floor 117, minecraft:forest` with identical metrics on 26.2 and 26.3.
- **AC8.8** (optional) `BenchmarkWorld.cameraPosition().y` is 133.0 on both 26.2 and 26.3 (noise floor + 16).
- **AC8.9** The result screen shows the shader advice line exactly when the shader cost was measured, the baseline 1% low is below the target, the shaders-off 1% low is at or above the target, and the gap is at least 10% (truth-table unit test); otherwise it shows none.
- **AC8.10** A run that shows the shader advice leaves `config/iris.properties` and every `shaderpacks/*.txt` byte-identical (production smoke with a shader pack).
- **AC8.11** Every new benchmarks.json run carries an optional `context` object (DH rendering, shaders, shader pack, framebuffer width and height, fullscreen, protocol 1). `schemaVersion` stays 1, a 0.2.0-written fixture loads unchanged, and the pinned 0.2.0 reader loads a 0.3.0-written file.
- If (b)'s UI is built:
  - **AC8.12** `BenchmarkHistory.comparable(latest)` returns only earlier BENCHMARK_WORLD runs with the same MC version, RD, SD and `context`, and a result with ≥ 2 repeats (unit test on a mixed fixture).
  - **AC8.13** With ≥ 3 comparable runs, the result screen shows the signed 1% low and avg % against their medians when |Δ| ≥ 2 × max(latest CV or 5%, 1.4826·MAD/median), shows "in line with your usual" otherwise, and shows nothing with fewer than 3 runs (unit tests + one game-test screenshot).

## 6. Open or unverified
- Whether C2ME or Structure Layout Optimizer change this seed's terrain in the user's instance: not run with the user's mods.
- The Iris UI path to the Profile button, and preprocessor handling of `profile.` lines: not checked in-game.
- §1's impact on real FPS numbers: the mechanism and the chunk counts are verified, but I didn't run a full Tune before and after the fix to quantify how far the chosen RD was overstated. That takes a non-harness run (`runBenchmarkAutorun`, starting at a low RD such as 8).
- Correction for the record: `docs/v0.2/design/ws-c.md` ("26.2 and 26.3 generate different terrain for the same seed (surface 117 vs 123)"). The terrain noise is identical; the 6-block difference is a tree on the camera column.
