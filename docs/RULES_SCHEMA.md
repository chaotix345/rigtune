# Rules file schema (v1)

The rules file is `rules/rules-v1.json`, served from `https://raw.githubusercontent.com/chaotix345/rigtune/main/rules/rules-v1.json`, and a copy is bundled in the mod at `src/main/resources/rigtune/rules-v1.json`.

**Where it comes from.** `tools/update_rules.py` generates it from two inputs:
- the hand-maintained source `rules/source/knowledge.json`, which has the same shape minus the generated fields
- upstream data (Modrinth, Fabulously Optimized, Additive)

**How the mod picks a copy.** It loads the bundled copy, the cached copy (`config/rigtune/rules-cache.json`) and the remote copy. It uses the valid one with the highest `revision`. A document is valid if its `schemaVersion` is 1 and it parses.

## Top level
| field | type | notes |
|---|---|---|
| schemaVersion | int | must be 1 |
| revision | int | increases monotonically; the updater bumps it whenever the content changes |
| generatedAt | string (ISO-8601) | |
| minModVersion | string | optional; older mod versions show a "please update RigTune" note but still use the rules |
| gpuTiers | GpuTierRule[] | the first match wins |
| gpuVendorFallback | map vendor→tier | used when no pattern matches; keys are lowercase GpuVendor names |
| cpuTiers | CpuTierRule[] | the first match wins; if none match, the formula (below) applies |
| heapTiers | HeapTierRule[] | sorted by atLeastMb descending; the first rule where heapMb >= atLeastMb wins |
| mods | ModRule[] | |
| obsolete | ObsoleteRule[] | |
| settings | SettingRule[] | |
| advice | AdviceRule[] | |
| availability | map mcVersion → slug[] | **generated**: slugs with a Fabric release for that MC version. The offline fallback |
| upstream | object | **generated**: `{ "fabulouslyOptimized": {"mcVersion": "26.2", "slugs": [...]}, "additive": {...} }` |

## GpuTierRule
`{ "pattern": "(?i)rtx\\s*40[6-9]0", "vendor": "nvidia", "integrated": false, "tier": 5 }`
- `pattern` is a Java regex that is *found* (not fully matched) in the GPU renderer/description string.
- The GPU vendor is detected from the renderer first, then the vendor string. A rule whose `vendor` differs from a detected vendor is skipped. `integrated` is optional: when omitted (or no rule matches) a heuristic decides (Intel non-Arc, generic AMD "Radeon Graphics"/"Vega N Graphics", Apple M-series and Qualcomm are integrated). A vendor missing from `gpuVendorFallback` gets tier 2. Invalid regexes are skipped with a warning.
- tier 0 means software rendering (a critical warning). Tiers 1–5 run from weak to strong.

## CpuTierRule
`{ "pattern": "(?i)ryzen\\s*\\d\\s*\\d{4}X3D", "tier": 5 }`

When no rule matches, the formula is: logical cores <= 2 → 1; <= 4 → 2; <= 8 → 3; <= 12 → 4; else 5. Then −1 if the known max frequency is under 2500 MHz, clamped to 1..5. An unknown core count gives tier 3.

## HeapTierRule
`{ "atLeastMb": 6144, "tier": 5 }`

## Tiers
- `tier = min(gpuTier, cpuTier, memTier)`. The limiting factor is whichever of the three is lowest (ties go to gpu, then cpu, then mem).
- `effectiveTier = clamp(tier + goalOffset, 1, 5)`, where the goal offset is PERFORMANCE −1, BALANCED 0, QUALITY +1. Tier 0 (software rendering) stays at 0.
- Conditions test the **effective** tier.

## ModRule
```json
{
  "slug": "sodium",
  "projectId": "AANobbMI",
  "title": "Sodium",
  "modIds": ["sodium"],
  "category": "rendering",
  "impact": "high",
  "stability": "stable",
  "reason": "Replaces the chunk renderer; usually the single biggest FPS gain.",
  "recommendWhen": { "always": true },
  "avoidWhen": { "gpuVendor": ["intel"], "gpuIntegrated": true },
  "avoidReason": "…",
  "conflictsWith": ["optifabric"],
  "defaultSelected": true,
  "upstream": { "fabulouslyOptimized": true, "additive": true }
}
```
- `category` is one of rendering, logic, memory, lighting, worldgen, network, startup, utility, ui.
- `impact` is high, medium or low.
- `stability` is stable, beta or alpha. For alpha, the reason gets a "(alpha build)" note and `defaultSelected` is false.
- `avoidWhen` and `avoidReason` are optional. When the mod is installed and `avoidWhen` matches, recommend disabling it.
- `conflictsWith` lists slugs or mod ids. Don't recommend this mod if any of them is installed.
- `defaultSelected` defaults to true.
- `upstream` is **generated**.

A mod is **installed** when any of its `modIds` is loaded. It is **recommended to add** when all of these hold:
- it isn't installed
- `recommendWhen` matches
- `avoidWhen` doesn't match
- no conflicting mod is installed
- it's available for the running MC version: online per Modrinth, or offline per `availability`. Unknown counts as available, with the note "availability not confirmed".

## ObsoleteRule
`{ "modIds": ["indium"], "title": "Indium", "reason": "Merged into Sodium since 0.6; the standalone mod conflicts with current Sodium.", "replacement": "sodium" }`

If it's installed, recommend disabling it (impact high).

## SettingRule
```json
{ "key": "vanilla.renderDistance", "value": 12, "when": { "tierAtLeast": 4 }, "reason": "…", "impact": "medium", "defaultSelected": true }
{ "key": "vanilla.renderDistance", "max": 8, "when": { "heapMbAtMost": 3072 }, "reason": "…" }
```
- `key` uses namespaced settings keys (below).
- An entry has **either** `value` **or** `min`/`max`.
- **Value entries** are evaluated in file order, and the last matching entry for a key wins.
- **Clamp entries** (`min`/`max`) are applied after that, in file order. If a clamp changes the value, its reason is appended. When no value entry matched, a clamp applies to the current value, so a clamp alone means "at most" / "at least" and never moves a setting the other way.
- **Computed values**:
  - `"$refreshRate"` means the display refresh rate, or 60 if it's unknown.
  - `"$refreshRateCap"` means the refresh rate minus 3 (a VRR-friendly cap), with a minimum of 30.
- A recommendation is emitted only when the resolved value differs from the current value, after normalisation (case-insensitive; `1.0` equals `1`). It is also emitted only when the key is present in the current `SettingsSnapshot`: unknown keys are skipped, so rules for Sodium keys do nothing when Sodium is absent.

## AdviceRule
`{ "id": "ram-low", "when": { "heapMbAtMost": 2048 }, "impact": "high", "title": "Allocate more RAM", "text": "…", "kind": "warning" }`

`kind` is one of info, warning or critical. Advice is informational only: it has no action.

## Recommender output
- Ids: `add:<slug>`, `update:<modId>`, `disable:<modId>`, `set:<key>`, `advice:<id>`, `conflict:<a>+<b>` (slugs, sorted).
- Categories: critical and warning advice and conflicts → WARNING (critical is always impact high); info advice → ADVICE; obsolete and `avoidWhen` → REMOVE_MOD; updates → UPDATE_MOD (impact low). Sorted by category, then impact, then title.
- Computed values (`$refreshRate`, `$refreshRateCap`) are resolved before clamps.
- Unknown availability appends "(availability not confirmed)"; alpha appends "(alpha build)".
- If `minModVersion` is newer than the running RigTune, an `advice:update-rigtune` note is added.

## Condition
This is a JSON object. Every field is optional, all present fields must hold (AND), and an empty object `{}` is true.

| field | type | meaning |
|---|---|---|
| always | bool | `true` matches; `false` never matches |
| tierAtLeast / tierAtMost | int | effective tier |
| rawTierAtLeast / rawTierAtMost | int | tier before the goal offset |
| gpuVendor | string[] | lowercase GpuVendor names |
| gpuIntegrated | bool | |
| gpuTierAtLeast / gpuTierAtMost | int | |
| cpuTierAtLeast / cpuTierAtMost | int | |
| hasBattery / onBattery | bool | |
| heapMbAtLeast / heapMbAtMost | int | JVM max heap |
| ramMbAtLeast / ramMbAtMost | int | total system RAM; false if unknown |
| vramMbAtLeast / vramMbAtMost | int | false if VRAM is unknown |
| refreshRateAtLeast | int | false if unknown |
| backend | string[] | "opengl" or "vulkan" |
| os | string[] | "windows", "macos" or "linux" (lowercase prefix match on the OS family) |
| goal | string[] | "performance", "balanced" or "quality" |
| mcVersion | string[] | exact match on the running MC version |
| modPresent / modAbsent | string[] | mod ids. modPresent: all loaded; modAbsent: none loaded |
| flags | string[] | all present in HardwareProfile.flags (e.g. `"shaders-enabled"`, `"sodium-workaround:AMD_GAME_OPTIMIZATION_BROKEN"`) |
| anyOf | Condition[] | at least one holds |
| not | Condition | negation |

## Settings keys
- `vanilla.<options.txt key>`, e.g. `vanilla.renderDistance`, `vanilla.simulationDistance`, `vanilla.maxFps`, `vanilla.enableVsync`, `vanilla.particles`, `vanilla.biomeBlendRadius`. Values are strings as they appear in options.txt, **without surrounding quotes**.
- `sodium.<section>.<field>` is a path inside `config/sodium-options.json`, e.g. `sodium.performance.chunk_builder_threads`.
