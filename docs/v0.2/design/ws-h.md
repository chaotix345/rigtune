# WS-H: Knowledge

SPEC items 9 (triage), 7 (DH/Iris knowledge) and AC2.3 (Nvidium, RenderScale). Content only: no schema or
recommender code changed. The source is `rules/source/knowledge.json`; `rules/rules-v2.json` (+ bundled copy),
`rules/rules-v1.json` and `rules/REVIEW.md` are regenerated from it (revision 7).

## Files
- `rules/source/knowledge.json`: all content below.
- Regenerated: `rules/rules-v2.json`, `src/main/resources/rigtune/rules-v2.json`, `rules/rules-v1.json`,
  `rules/REVIEW.md` (section (a) empty; (d) lists 24 v1 overrides and omissions).
- `src/test/java/.../core/recommend/KnowledgeV2ScenarioTest.java` (new, 19 tests over the bundled rules).
- `src/test/java/.../core/rules/RulesLoaderTest.java`: `bundledRulesAreComplete` checked bundled setting keys with a
  pre-v2 `vanilla.`/`sodium.` prefix test; it now uses `SettingKeys.changeable`, like `RepositoryRulesTest`.
- The v0.1.0 baseline (`src/test/resources/v010/rules-v1-baseline.json`) is **unchanged**: RulesV1DifferentialTest
  passes against it, so no v1 change needed accepting.

## Facts checked for this content (2026-09-25)
- Distant Horizons 3.3.2 (javap on the Modrinth jar; the research doc couldn't reach DH's source):
  - `lodChunkRenderDistanceRadius`: min 32, **default 256**, max 4096.
  - `verticalQuality` and `horizontalQuality` default MEDIUM; `maxHorizontalResolution` default BLOCK.
  - `numberOfThreads` default `ceil(logical cores × 0.5)`, max = logical cores.
  - Quality presets (`RenderQualityPresetConfigEventHandler`): Minimum = vertical HEIGHT_MAP, horizontal LOWEST,
    max resolution TWO_BLOCKS; Low = LOW, LOW, BLOCK; Medium and up keep BLOCK.
  - UI names come from its lang file.
- Iris 1.11.4: the shadow distance is an IntRange 0..32, default 32. Its tooltip says lowering it "can
  significantly increase performance" and that the actual shadow distance "is capped by the View Distance setting".
- The user's live `DistantHorizons.toml` (read-only):
  - `lodChunkRenderDistanceRadius`/`numberOfThreads` are bare ints; the three quality keys are quoted strings.
  - Every proposed value fits: integers for bare keys, enum names without quotes/backslashes for quoted ones. So
    WS-D's patcher accepts them.
- MC 26.2 and Sodium 0.9.2 option names and value encodings, from the lang files (enum ids via javap), for the labels.

## Decisions and deviations
Review: a code-reviewer subagent's findings, decided by the coordinator, are all applied (below).

### Nvidium
- `recommendWhen`: `gpuVendor nvidia`, not integrated, **`gpuTierAtLeast 3`** (a beta mod pinned to an exact
  Sodium build), Sodium present, and `gpuModelMatches` with the exact triage §5 regex (AC2.3).
- `avoidWhen`: **deviation from triage.md.** It doesn't use `not gpuModelMatches(<that list>)`. A card missing from
  the list would then get a *ticked* suggestion to disable a working Nvidium (RTX 2050, GTX 1630, TITAN RTX,
  Quadro T-series, any future "RTX PRO" card). A missing recommendation costs nothing, because Nvidium switches
  itself off on unsupported GPUs.
  - So `avoidWhen` matches known pre-Turing NVIDIA names, plus non-NVIDIA and shaders on, as before. The names
    covered: GTX 4xx–10xx incl. M parts, GeForce GT/GTS and bare 3–4-digit M/MX parts, MX1xx–3xx,
    TITAN X/Xp/V/Black/Z, Quadro K/M/P and GP/GV100.
  - The reviewer's regex was checked in Java against 28 Turing-or-newer and 24 pre-Turing renderer strings.
  - MX450 is in neither list (triage §7: unverified), so it's neither offered nor flagged.
- Unknown GPU (blank renderer and vendor): both conditions are UNKNOWN, so no add and no disable. An NVIDIA vendor
  string without a renderer names no model: no add, no disable.
- v1 override: the old tier-gated `recommendWhen`, and the old `avoidWhen` **without its `gpuTierAtMost 2`
  branch**. That's only fewer disable suggestions; additions are unaffected, since v1 recommends only at GPU tier ≥ 4.

### Mod rules
- **RenderScale.** A third `anyOf` branch: GPU tier 3 and `displayPixelsAtLeast` 3686400 (2560×1440; covers
  ultrawide 3440×1440, not 2560×1080). v1 override: the old `recommendWhen` and reason.
- **Ixeris.** One rule, `always: true`, ticked, with a reason that's true on both versions.
  - The reason: the threaded event handling helps everywhere, and the Windows raw-input batching (per its changelog,
    and open issue #129) isn't ported to 26.3's SDL window system yet, so the gain there is smaller.
  - **Deviation from SPEC item 9's "via `mcVersionRange`"** (coordinator's decision). Two same-slug rules worked,
    but they gave ambiguous REVIEW rows, a duplicate `availability` entry and a second Modrinth request. With an
    unparseable or snapshot MC version, neither rule fired.
- **LambDynamicLights** (mod id `lambdynlights`): never recommended, `avoidWhen tierAtMost 2`,
  `avoidSelected: false`, impact low, `"v1": false` (H3). It conflicts with sodiumdynamiclights, ryoamiclights and
  optifabric (its fabric.mod.json `breaks`).

### DH settings (all `"v1": false`)
With DH's default of 256 chunks, almost every per-tier value in dh-iris.md §1.2 is a *lowering*. For enums below
the default, a value entry can also raise what a player chose.
- **LOD distance:**
  - Tiers 1/2/3: `max` 48/64/96, ticked. A cap never raises what the player lowered.
  - Tier 4: `value` 160; tier 5: `value` 256 (DH's default). Both unticked, like vanilla render distance 12/16.
  - Reasons cite the one community guide by name (distanthorizonsguide.com: 64–96 entry-level, 96–128 mid-range);
    tier 1's 48 is RigTune's own, below that band.
- **Quality (tiers ≤ 2 only), citing DH's own presets:**
  - Vertical quality LOW: the Low preset.
  - Horizontal quality LOWEST (tier 1): the Minimum preset. LOW (tier 2): the Low preset.
  - **Deviation:** no raises above DH's default MEDIUM on tiers 3–5; §1.2's HIGH/VERY_HIGH were the research's own
    extrapolation.
- **Max horizontal resolution:** TWO_BLOCKS on tiers ≤ 2 only, the value DH's own Minimum preset uses (every other
  preset keeps BLOCK).
  - **Deviation:** §1.2's CHUNK/HALF_CHUNK/FOUR_BLOCKS were more aggressive than any DH preset, so they're dropped,
    and so are the tier-3/4 entries.
  - Enum values can still raise a lower choice (e.g. CHUNK → TWO_BLOCKS, HEIGHT_MAP → LOW) on tiers ≤ 2. It's
    accepted, and a test documents it; the reasons state the preset fact rather than claiming a saving.
- **Threads:** `max` 1/2/4/6 by **CPU** tier bands 1/2/3/4, about half the logical cores (DH's own default).
  - It only lowers a thread count the player raised.
  - The CPU tier is always known (formula or pattern), so the cap never goes UNKNOWN.
- **Vanilla render distance** `max 8` with DH at tier ≤ 2 (SPEC item 7), after the existing `max 12`.
  - Today it's redundant with the per-tier value entries (6/8 at tiers 1/2).
  - It's in v1 (vanilla key, v1 conditions, restrictive); the differential test confirms no change for 0.1.x.

### Iris shadow distance (`"v1": false`)
**Deviation from §1.2's 16/24/32.** Iris caps shadows at the vanilla render distance, so values at or above RigTune's
tier render distance (6/8/10) do nothing.
- The caps: 4 and 6 (ticked, tiers 1/2) and 8 (unticked, tier 3), each two chunks under that tier's render distance.
- Gated on `modPresent iris` + `flags shaders-enabled`.

### Advice
- **RAM** (DH's FAQ: 2–4 GB more than vanilla). v2 de-duplicates; before, up to three warnings with different
  numbers showed at once.
  - The DH-only and shaders-only warnings exclude the combined case.
  - The combined case gets `ram-distant-horizons-shaders` (≥ 16 GB RAM, unchanged) or the new
    `ram-distant-horizons-shaders-limited` (12–16 GB).
  - v1 overrides keep the old `when`s. The new warning is `"v1": false`, since 0.1.x already shows the old pair there.
  - New info `ram-distant-horizons-low-system` (DH on < 12 GB RAM) says "about 4 GB at most", in line with
    `ram-low`/`ram-low-system`. It also goes to v1.
- **Shaders:**
  - New warning `shaders-entry-level` (tier ≤ 2 **and GPU tier ≤ 3**; turn them off, or MakeUp - Ultra Fast).
  - `heavy-shaders` gets `tierAtLeast 3` in v2. Together the two cover exactly the old `heavy-shaders` condition
    (shaders on, GPU tier ≤ 3) and never show together.
  - The v1 override keeps `heavy-shaders`' old `when`; the new warning is `"v1": false`.
  - A heap- or CPU-limited tier ≤ 2 with a strong GPU gets neither warning (a test documents this).
- **`shaders-distant-horizons`** (info, DH + shaders): only packs both community compatibility lists agree on
  (dh-iris.md §6): Complementary Reimagined/Unbound, BSL 8.2.0+, Bliss, Photon.
  - Solas (one list) and MakeUp (secondary sites only) are left out; Sildur's Vibrant isn't named.

### settingLabels and triage
- **settingLabels** for every settings key the rules use, with the games' own UI names. A scenario test checks
  every settings key has a label.
  - Sodium: "Chunk Updates: Deferred/Soon/Immediate", "Block Transparency Limits: Basic/Safe/Unlimited".
  - DH: "Max Horizontal Resolution: Chunk … Block", "NO. of threads".
  - Vanilla ids map to the game's names for Particles, Texture Filtering, Chunk Builder and Clouds.
  - No label for Iris's 0: it isn't verified, and the caps never produce it.
- **Triage:** the 32 `reviewIgnore` entries from triage §2b, lightly reworded to plain, specific reasons. The live
  regeneration found no new upstream mods, so REVIEW (a) is empty (AC9.1).

## rules-v1.json changes (all checked safe)
- Text only: the Ixeris reason, Nvidium's avoidReason, the `ram-distant-horizons` text.
- Nvidium's v1 `avoidWhen` loses its `gpuTierAtMost 2` branch: fewer disables, no new adds.
- The redundant DH `max 8` render-distance clamp.
- Two new info advices: `ram-distant-horizons-low-system`, `shaders-distant-horizons`.
- `lambdynamiclights` in availability.

RulesV1DifferentialTest passes against the v0.1.0 baseline: no new or newly ticked appliable recommendation and no
lost warning. `check_rules_v1.py` passes.

## Verification
- `python tools/update_rules.py` (live) and `python tools/check_rules_v1.py`.
- `python -m unittest discover -s tools/tests`: 152 OK.
- `./gradlew build` for 26.2 and 26.3: 552 tests each, 0 skipped, 0 failures.
- AC9.1: REVIEW (a) is empty.
- AC9.2: `KnowledgeV2ScenarioTest`.
  - Nvidium: RTX 2060 / GTX 1660 yes, GTX 1080 / AMD no, plus every triage §5 string.
  - Pre-Turing incl. M/MX parts: a ticked disable. Cards outside both lists and unknown GPUs: no disable.
  - LambDynamicLights on tier 2: an unticked disable.
- AC9.3: the build was rerun after regenerating.
- AC2.3: tested for Nvidium's `gpuModelMatches` and for RenderScale at 2560×1440 on a GPU-tier-3 card.
- AC7.2 (knowledge side):
  - DH at tiers 1–5, the LOD cap not raising, the enum-raise case, the thread cap.
  - Iris shadows at tiers 1/2/3/5, and with shaders off.
  - RAM advice with DH and/or shaders; the shader warnings.

## UNVERIFIED
- The Iris shadow caps and some DH values are RigTune's own estimates, not measured; the unticked ones say so.
  - DH: tier 1's 48, tier 4's 160.
- DH shader-pack support comes from two third-party compatibility lists (dh-iris.md §6), not the packs themselves.
- Ixeris on 26.3: changelog and issue-tracker evidence, not source (triage §7).
- No game launch: AC7.3 (DH loads a patched TOML) is Phase 5.
