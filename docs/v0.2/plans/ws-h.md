# WS-H: Knowledge (SPEC items 9, 7-knowledge, AC2.3) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:test-driven-development for every task. Steps use checkbox (`- [ ]`) syntax. Executed inline by the WS-H agent, one commit per task.

**Goal:** Put the v0.2 knowledge into `rules/source/knowledge.json` (triage, Nvidium/RenderScale/Ixeris v2 conditions, DH/Iris settings and advice, setting labels), regenerate both rules files, and prove the behaviour with `KnowledgeV2ScenarioTest`, without making `rules-v1.json` less safe for 0.1.x.

**Architecture:** Content only. The schema code (WS-A) and the DH/Iris readers/patchers (WS-D) are merged; nothing under `src/main` changes. Every rule that uses a v2 feature carries an explicit `v1` decision (`false` or a conservative override). The scenario tests run the real Recommender over the bundled `rules-v2.json`, so they fail until the knowledge is written and regenerated.

**Tech Stack:** JSON rules, Python 3.11 updater (`tools/update_rules.py`, network), JUnit 5, Gradle (Stonecutter 26.2 + 26.3).

**Spec:** docs/v0.2/SPEC.md items 2 (AC2.3), 7 (knowledge), 9 and the amendments; docs/v0.2/PLAN.md WS-H; docs/RULES_SCHEMA.md; tools/README.md; docs/v0.2/design/ws-a.md ("what WS-H must do"); docs/research/v0.2/triage.md, dh-iris.md; docs/v0.2/design/ws-d.md.

## Global Constraints
- `export JAVA_HOME="C:/Dev/Tools/jdk/jdk-25.0.4.1+1"` before every `./gradlew`; `./gradlew build` for BOTH versions, always rerun after regenerating (scenario tests read the bundled rules).
- rules-v1.json must stay safe for 0.1.x: `check_rules_v1.py` passes, `RulesV1DifferentialTest` passes; update `src/test/resources/v010/rules-v1-baseline.json` only after reading the report and only for a deliberate, safe change (say why in design/ws-h.md).
- Every `dh.`/`iris.` setting entry: `"v1": false`. LambDynamicLights: `"v1": false`. Nvidium: v1 `recommendWhen` + `avoidWhen` overrides. A warning/critical advice with a v2 `when` needs an explicit `v1`.
- dh. values must fit the existing TOML token kind: bare int keys (`lodChunkRenderDistanceRadius`, `numberOfThreads`) get integers; quoted keys (`verticalQuality`, `horizontalQuality`, `maxHorizontalResolution`) get enum names without quotes or backslashes.
- Reasons: plain, specific, loosely cite the source ("per Distant Horizons' FAQ"), never promise FPS numbers.
- Don't edit SPEC.md/PLAN.md/PROGRESS.md or schema code. Never write under %APPDATA%\ModrinthApp. Scratch: `.../scratchpad/ws-h/`.
- Commit messages end with the two Co-Authored-By / Claude-Session lines from PLAN.md.

## Verified facts this plan relies on (2026-09-25)
- DH 3.3.2 (`Config$Client$Advanced$Graphics$Quality`, javap): `lodChunkRenderDistanceRadius` min 32 / **default 256** / max 4096; `verticalQuality` default MEDIUM; `horizontalQuality` default MEDIUM; `maxHorizontalResolution` default BLOCK. `numberOfThreads` default = `ceil(availableProcessors × 0.5)` (ThreadPresetConfigEventHandler), max = availableProcessors.
- Iris 1.11.4 `IrisVideoSettings`: shadow distance IntRange 0..32, default 32. Its tooltip: "Lowering the shadow distance can significantly increase performance… The actual shadow render distance is capped by the View Distance setting." So a shadow clamp only matters below the vanilla render distance (RigTune's tier RDs are 6/8/10/12/16).
- UI labels: Sodium 0.9.2 lang (Chunk Updates: Deferred/Soon/Immediate = ALWAYS/ONE_FRAME/ZERO_FRAMES; Block Transparency Limits: Basic/Safe/Unlimited = OFF/SAFE/UNLIMITED; Chunk Update Threads, 0 = Default); MC 26.2 lang + enum ids (particles 0 All/1 Decreased/2 Minimal; textureFiltering 0 None/1 RGSS/2 Anisotropic; prioritizeChunkUpdates 0 Threaded/1 Semi Blocking/2 Fully Blocking; renderClouds "false"/"fast"/"true" = Off/Fast/Fancy; inactivityFpsLimit afk/minimized; biomeBlendRadius 0..7 = OFF/3x3/…/15x15; framerateLimit 10..260, 260 = Unlimited); DH 3.3.2 lang (LOD Chunk Render Distance Radius, Vertical Quality, LOD Dropoff Distance, Max Horizontal Resolution, enum names).
- The user's live DistantHorizons.toml: `lodChunkRenderDistanceRadius`/`numberOfThreads` bare ints; the three quality keys quoted strings.
- Recommender: `add:<slug>` is put-if-absent, OnlineLookupGate de-duplicates slugs, so two mutually exclusive ModRules with one slug (Ixeris per MC version) work.

## Content decisions
| item | v2 | v1 |
|---|---|---|
| 32 reviewIgnore (triage §2b) | reasons as written | n/a (source-only) |
| LambDynamicLights | never recommended; avoidWhen tier ≤ 2; avoidSelected false; impact low | `false` |
| Nvidium | recommendWhen/avoidWhen via `gpuModelMatches` (triage §5 regex) | override: old tier-gated recommendWhen + avoidWhen |
| RenderScale | + anyOf branch `gpuTier 3 && displayPixelsAtLeast 3686400` (2560×1440) | override: old recommendWhen and old reason |
| Ixeris | two rules: `mcVersionRange "<26.3"` (26.2 reason, keeps v1 via override `always: true`) and `">=26.3"` (SDL reason, `v1: false`); both ticked | unchanged behaviour |
| DH LOD distance | tier 1/2/3: `max` 48/64/96 (ticked, never raises what the player lowered); tier 4: value 160 unticked; tier 5: value 256 (DH default) unticked | `false` |
| DH vertical / horizontal quality | tier ≤ 2 only: LOW / LOWEST(t1), LOW(t2), ticked; nothing above DH's default MEDIUM | `false` |
| DH max horizontal resolution | CHUNK / HALF_CHUNK / FOUR_BLOCKS ticked (t1–t3); TWO_BLOCKS unticked (t4); t5 keeps BLOCK (default) | `false` |
| DH threads | `max` by CPU tier bands 1/2/3/4 → 1/2/4/6 (≈ half the logical cores, DH's own default; only lowers a raised value) | `false` |
| vanilla RD with DH, tier ≤ 2 | `max: 8` after the existing `max: 12` (currently redundant with the per-tier values; a guard) | included automatically (vanilla key, v1 conditions; restrictive) |
| Iris shadow distance (shaders on) | `max` 4 / 6 ticked (t1/t2), 8 unticked (t3): each 2 below RigTune's tier RD, since Iris caps it at RD anyway | `false` |
| RAM advice | de-duplicate: DH-only and shaders-only warnings exclude the combined case; new `ram-distant-horizons-shaders-limited` (DH + shaders, 12–16 GB RAM); new info `ram-distant-horizons-low-system` (DH, < 12 GB RAM); texts cite DH's FAQ | old `when` via overrides; new warning `false`; new info auto |
| Shaders at tier ≤ 2 | new warning `shaders-entry-level` (turn off or MakeUp - Ultra Fast); `heavy-shaders` gets `tierAtLeast: 3` so the two never show together | heavy-shaders: override with old `when`; new warning `false` |
| DH shader packs | new info `shaders-distant-horizons`: packs both compatibility lists agree on (Complementary Reimagined/Unbound, BSL 8.2.0+, Bliss, Photon) | auto (plain info) |
| settingLabels | every settings key the rules use (vanilla, sodium, dh, iris) + value labels for cryptic values | stripped |

## File Structure
- Modify: `rules/source/knowledge.json` (all content).
- Regenerated: `rules/rules-v2.json`, `src/main/resources/rigtune/rules-v2.json`, `rules/rules-v1.json`, `rules/REVIEW.md`.
- Create: `src/test/java/io/github/chaotix345/rigtune/core/recommend/KnowledgeV2ScenarioTest.java`.
- Create: `docs/v0.2/design/ws-h.md`.
- Maybe: `src/test/resources/v010/rules-v1-baseline.json` (only if the differential test flags a deliberate, safe change).

---

### Task 1: Scenario tests first (red)

**Files:** Create `src/test/java/io/github/chaotix345/rigtune/core/recommend/KnowledgeV2ScenarioTest.java`.

Tests (all over `RulesLoader.loadBundled()`, `Fixtures`, `OnlineData.offline()`, `Goal.BALANCED`):
- `nvidiumOnTuringAndNewer`: RTX 2060 and GTX 1660 SUPER with sodium → `add:nvidium` present and ticked; GTX 1080 and the AMD user rig → absent. Every triage §5 "must match" string → present; every "must not match" string → absent.
- `nvidiumInstalledOnPascalIsDisabled`: GTX 1080 with nvidium installed → `disable:nvidium`; RTX 2060 → none.
- `lambDynamicLightsOnTier2IsAnUntickedDisable`: low-end laptop (tier 2) with `lambdynlights` → `disable:lambdynlights`, REMOVE_MOD, impact LOW, `selectedByDefault()` false, DisableMod action; tier-5 rig → no `disable:lambdynlights`; never `add:lambdynamiclights`.
- `dhTier2Settings`: laptop + DH, DH keys at DH defaults (256/MEDIUM/MEDIUM/BLOCK/threads 4) → LOD 64, vertical LOW, horizontal LOW, max resolution HALF_CHUNK, all ticked; threads unchanged (CPU tier 3 → max 4); titles use labels ("Distant Horizons: LOD Chunk Render Distance Radius: 256 → 64", "Distant Horizons: Max Horizontal Resolution: Block → Half a chunk").
- `dhTier2NeverRaisesWhatThePlayerLowered`: LOD 32 → no LOD recommendation.
- `dhTier5KeepsDefaults`: user rig + DH at defaults → no dh. recommendation; LOD 512 → unticked 256.
- `dhThreadsClampedByCpuTier`: 4-thread CPU (CPU tier 2), threads 8 → 2.
- `irisShadowDistanceWithShadersOnly`: tier 2 + iris + `shaders-enabled`, shadow 32 → 6 ticked; without the flag → none.
- `renderScaleForMidTierGpuAt1440p`: RX 5700 XT (GPU tier 3) at 2560×1440 → `add:renderscale`; at 1920×1080 → absent; user rig (tier 5) at 1440p → absent.
- `ramAdviceWithDhAndShaders`: DH + shaders, heap 4096, RAM 32 GB → only `ram-distant-horizons-shaders`; RAM 12 GB → only `ram-distant-horizons-shaders-limited`; DH only → only `ram-distant-horizons`; shaders only → only `ram-shaders`; DH on 8 GB RAM → `ram-distant-horizons-low-system`.
- `shadersOnEntryLevelHardware`: tier-2 laptop + shaders → `shaders-entry-level`, not `heavy-shaders`; GPU tier 3 at effective tier 3 → `heavy-shaders` only.
- `shaderPacksWithDhSupport`: DH + shaders → `advice:shaders-distant-horizons` (info).
- `ixerisReasonDependsOnMcVersion`: 26.2 → `add:ixeris` ticked, reason without "26.3"; 26.3 → ticked, reason mentions "26.3" and "SDL".
- `everySettingKeyHasALabel`: every `rules.settings` key has a `settingLabels` entry with a name.

- [ ] Write the class. Run `./gradlew :26.2:test --tests "*KnowledgeV2ScenarioTest"`; expect failures (no content yet).
- [ ] The plan is committed on its own first (protocol step 2). The red tests are committed together with the content that turns them green (Task 6), so every commit builds.

### Task 2: Triage (reviewIgnore, LambDynamicLights)
- [ ] Add the 32 `reviewIgnore` entries (triage §2b text, lightly edited to plain wording).
- [ ] Add the LambDynamicLights ModRule (after `particle-core`), with `avoidSelected: false`, `"v1": false`.

### Task 3: Nvidium, RenderScale, Ixeris (AC2.3, item 9)
- [ ] Nvidium v2 conditions with the triage regex; `v1` override with the old `recommendWhen` and `avoidWhen`.
- [ ] RenderScale third anyOf branch; `v1` override with the old `recommendWhen` and reason.
- [ ] Split Ixeris into the two version rules.

### Task 4: DH/Iris settings, clamps and advice (item 7 knowledge)
- [ ] Settings per the table above, all `dh.`/`iris.` entries `"v1": false`, reasons citing DH's/Iris's own descriptions or the community guides.
- [ ] Advice per the table (overrides on `ram-distant-horizons`, `ram-shaders`, `heavy-shaders`).

### Task 5: settingLabels
- [ ] Labels for every settings key used by the rules (names from the games' UIs, value labels where values are ids/enums).

### Task 6: Regenerate and verify
- [ ] `python tools/update_rules.py`; REVIEW.md (a) must be empty (triage anything new upstream the same way).
- [ ] `python tools/check_rules_v1.py`; `python -m unittest discover -s tools/tests`.
- [ ] `./gradlew build` (both versions). Read any RulesV1DifferentialTest failure; don't touch the baseline unless the change is deliberate and safe.
- [ ] Commit tasks 1–6 (knowledge + regenerated files + tests).

### Task 7: Review, merge, push, CI, design notes
- [ ] code-reviewer subagent (rules accuracy, 0.1.x safety); fix high/medium findings; rerun the checks.
- [ ] Merge `origin/feat/v0.2.0` if it moved; build both; push; `gh run watch <id> --exit-status` (incl. rules-v1-compat).
- [ ] Write `docs/v0.2/design/ws-h.md`; verification-before-completion.
