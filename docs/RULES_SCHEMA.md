# Rules file schema (v2)

## Files
| file | read by | contents |
|---|---|---|
| `rules/rules-v2.json` (+ an identical bundled copy, `src/main/resources/rigtune/rules-v2.json`) | RigTune 0.2+ | the full rules, `schemaVersion: 2` |
| `rules/rules-v1.json` | RigTune 0.1.x (remote only; 0.2 doesn't bundle it) | the **v1 projection**: only what 0.1.x understands, never less safe (below) |
| `rules/source/knowledge.json` | the updater | the hand-maintained source; may use v2 features and per-rule `v1` overrides |

They're served from `https://raw.githubusercontent.com/chaotix345/rigtune/main/rules/rules-v2.json` and `.../rules-v1.json`.

**Where they come from.** `tools/update_rules.py` generates both from:
- the hand-maintained source `rules/source/knowledge.json`, which has the same shape minus the generated fields, plus two maintainer-only fields (`reviewIgnore` and `v1`, below)
- upstream data (Modrinth, Fabulously Optimized, Additive)

Both files come from one run and share `revision` and `generatedAt`. See tools/README.md for the pipeline.

**How the mod picks a copy (0.2).**
- Candidates:
  - the bundled `rules-v2.json`
  - the v2 cache `config/rigtune/rules-v2-cache.json`
  - 0.1.0's cache `config/rigtune/rules-cache.json` (a v1 document; 0.2 reads it and never writes it)
  - remote `rules-v2.json`; if that fails (network error, non-200, larger than 2 MiB, invalid), remote `rules-v1.json`
- A remote v2 document is saved to the v2 cache. A remote v1 document is only used in memory, so the v2 cache always holds a v2 document.
- The mod uses the valid candidate with the highest `revision`. On a tie it prefers `schemaVersion` 2, then remote over cache over bundled.
- The local candidates are used right away; the remote document replaces them only if it is strictly newer (a higher `revision`, or the same revision with `schemaVersion` 2 over 1). The same rules again aren't re-applied.
- A document is valid if it parses and its `schemaVersion` is 1 or 2. A future v3 will live in its own file.
- No request is made when the network or remote rules are switched off in RigTune's settings. The switch is checked again before the v1 fallback request, and a load that a newer one (after a settings change) has replaced makes no further requests.
- `-Drigtune.rules.baseUrl=<folder URL>` replaces `https://raw.githubusercontent.com/chaotix345/rigtune/main/rules/` (for tests).

0.1.x reads only `rules-v1.json` (schemaVersion 1) and its own `rules-cache.json`.

## Top level
| field | type | notes |
|---|---|---|
| schemaVersion | int | 2 in rules-v2.json, 1 in rules-v1.json |
| revision | int | increases monotonically; the updater bumps it whenever either file's content changes |
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
| settingLabels | map settings key → SettingLabel | **v2 only**, optional (below) |
| availability | map mcVersion → slug[] | **generated**: slugs with a Fabric release for that MC version. The offline fallback |
| upstream | object | **generated**: `{ "fabulouslyOptimized": {"mcVersion": "26.2", "slugs": [...]}, "additive": {...} }` |

## GpuTierRule
`{ "pattern": "(?i)rtx\\s*40[6-9]0", "vendor": "nvidia", "integrated": false, "tier": 5 }`
- `pattern` is a Java regex that is *found* (not fully matched) in the GPU subject string: the renderer, or the vendor string when the renderer is blank.
- The GPU vendor is detected from the renderer first, then the vendor string. A rule whose `vendor` differs from a detected vendor is skipped. `integrated` is optional: when omitted (or no rule matches) a heuristic decides (Intel non-Arc, generic AMD "Radeon Graphics"/"Vega N Graphics", Apple M-series and Qualcomm are integrated). A vendor missing from `gpuVendorFallback` gets tier 2. Invalid regexes and regexes longer than 200 characters are skipped with a warning; a match that runs out of its read budget counts as no match.
- tier 0 means software rendering (a critical warning). Tiers 1–5 run from weak to strong.

## CpuTierRule
`{ "pattern": "(?i)ryzen\\s*\\d\\s*\\d{4}X3D", "tier": 5 }`

When no rule matches, the formula is: logical cores <= 2 → 1; <= 4 → 2; <= 8 → 3; <= 12 → 4; else 5. Then −1 if the known max frequency is under 2500 MHz, clamped to 1..5. An unknown core count gives tier 3.

## HeapTierRule
`{ "atLeastMb": 6144, "tier": 5 }`

Tier rules have no `requires` and no fail-closed handling: a client ignores fields it doesn't know in them. So **any schema change to a tier rule needs a new schemaVersion** (and its own file). The updater rejects unknown fields in tier rules.

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
  "avoidSelected": true,
  "conflictsWith": ["optifabric"],
  "defaultSelected": true,
  "requires": [],
  "upstream": { "fabulouslyOptimized": true, "additive": true }
}
```
- `category` is one of rendering, logic, memory, lighting, worldgen, network, startup, utility, ui.
- `impact` is high, medium or low.
- `stability` is stable, beta or alpha. For alpha, the reason gets a "(alpha build)" note and `defaultSelected` is false.
- `avoidWhen` and `avoidReason` are optional. When the mod is installed and `avoidWhen` is TRUE, recommend disabling it.
- `avoidSelected` (**v2 only**, default true): whether that "disable" suggestion starts ticked. A mod that is a visual preference rather than a problem (LambDynamicLights) uses `false`.
- `conflictsWith` lists slugs or mod ids. Don't recommend this mod if any of them is installed.
- `defaultSelected` defaults to true.
- `requires`: see [requires](#requires-v2).
- `upstream` is **generated**.

A mod is **installed** when any of its `modIds` is loaded. It is **recommended to add** when all of these hold:
- it isn't installed
- `recommendWhen` is TRUE
- `avoidWhen` is absent or FALSE (UNKNOWN blocks the addition too)
- no conflicting mod is installed
- it's available for the running MC version: online per Modrinth, or offline per `availability`. Unknown counts as available, with the note "availability not confirmed".

## ObsoleteRule
`{ "modIds": ["indium"], "title": "Indium", "reason": "Merged into Sodium since 0.6; the standalone mod conflicts with current Sodium.", "replacement": "sodium" }`

If it's installed, recommend disabling it (impact high). May carry `requires`.

## SettingRule
```json
{ "key": "vanilla.renderDistance", "value": 12, "when": { "tierAtLeast": 4 }, "reason": "…", "impact": "medium", "defaultSelected": true }
{ "key": "vanilla.renderDistance", "max": 8, "when": { "heapMbAtMost": 3072 }, "reason": "…" }
```
- `key` uses namespaced settings keys (below).
- An entry has **either** `value` **or** `min`/`max`.
- **Value entries** are evaluated in file order, and the last matching entry for a key wins.
- **Clamp entries** (`min`/`max`) are applied after that, in file order. If a clamp changes the value, its reason is appended. When no value entry matched, a clamp applies to the current value, so a clamp alone means "at most" / "at least" and never moves a setting the other way.
- **Computed values** (the only `$` tokens; 0.2 skips an entry with any other `$…` value, and the updater rejects one unless the rule has `requires`, since a new token needs a new client):
  - `"$refreshRate"` means the display refresh rate, or 60 if it's unknown.
  - `"$refreshRateCap"` is a VRR-friendly cap just under the refresh rate. Vanilla only accepts multiples of 10, so it is floor(hz/10)*10, minus 10 more when that equals hz and hz >= 100, clamped to 30..250 (180 Hz → 170, 144 Hz → 140, 60 Hz → 60). `"$refreshRate"` rounds to the nearest multiple of 10 (30..260).
- A recommendation is emitted only when the resolved value differs from the current value, after normalisation (case-insensitive; `1.0` equals `1`). It is also emitted only when the key is present in the current `SettingsSnapshot`: unknown keys are skipped, so rules for Sodium keys do nothing when Sodium is absent.
- The recommendation title is `<name>: <current> → <target>`, using `settingLabels` where they exist. For a mod's key the name starts with the mod: `Sodium: …`, `Distant Horizons: …`, `Iris: …`.
- May carry `requires`.

## AdviceRule
`{ "id": "ram-low", "when": { "heapMbAtMost": 2048 }, "impact": "high", "title": "Allocate more RAM", "text": "…", "kind": "warning" }`

`kind` is one of info, warning or critical. Advice is informational only: it has no action. May carry `requires`.

## SettingLabel (v2)
`"settingLabels": { "sodium.performance.chunk_builder_threads": { "name": "Chunk builder threads", "values": { "0": "Auto" } } }`

Human-readable names for recommendation titles (and the share report). `name` replaces the caption made from the key (the mod prefix, e.g. `Sodium: `, stays); `values` maps a setting value (compared after normalisation, so `"0"` also matches `0.0`) to a label. A missing label falls back to the key's caption and the raw value. Labels never change what is applied.

## requires (v2)
`"requires": ["some-client-feature"]` on a ModRule, ObsoleteRule, SettingRule or AdviceRule. A rule whose `requires` names any feature this client doesn't know is **skipped entirely** (no addition, disable, conflict warning, setting or advice). RigTune 0.2.0 knows no features, so today any non-empty `requires` hides the rule from every client. It is the escape hatch for future rule-level fields that must not fail open: a rule that depends on such a field lists the feature that implements it.

## reviewIgnore (source-only)
`{ "slug": "servercore", "reason": "server-only" }`

A top-level array in `rules/source/knowledge.json` only. Each entry is an upstream mod a maintainer has already triaged in `REVIEW.md` section (a) and decided not to add, with a one-line `reason`. `tools/update_rules.py` excludes these slugs from section (a) of the next `REVIEW.md` so they don't keep coming back up for review, but it never copies `reviewIgnore` into either output — it has no effect on what the mod recommends.

## Condition
This is a JSON object. Every field is optional, all present fields must hold (AND), and an empty object `{}` is true. A missing condition (e.g. no `recommendWhen`) is also true, but a condition written as `null` (e.g. `"when": null`) is UNKNOWN in 0.2 (0.1.x reads it as true; the updater rejects nulls). Fields marked v2 exist only in rules-v2.json. Integer fields must be JSON integers in range (32-bit for the tier and refresh-rate fields, 64-bit for the MB and pixel fields) and boolean fields JSON booleans; anything else counts as an unknown key.

| field | type | meaning | UNKNOWN when |
|---|---|---|---|
| always | bool | `true` matches; `false` never matches | |
| tierAtLeast / tierAtMost | int | effective tier | |
| rawTierAtLeast / rawTierAtMost | int | tier before the goal offset | |
| gpuVendor | string[] | lowercase GpuVendor names: nvidia, amd, intel, apple, qualcomm, software, other, unknown | no GPU info (unless `unknown` is listed); a value outside the list |
| gpuIntegrated | bool | | no GPU info |
| gpuTierAtLeast / gpuTierAtMost | int | | |
| cpuTierAtLeast / cpuTierAtMost | int | | |
| hasBattery / onBattery | bool | | |
| heapMbAtLeast / heapMbAtMost | int | JVM max heap | heap unknown |
| ramMbAtLeast / ramMbAtMost | int | total system RAM | RAM unknown |
| vramMbAtLeast / vramMbAtMost | int | | VRAM unknown |
| refreshRateAtLeast | int | display refresh rate | refresh rate unknown |
| backend | string[] | opengl, vulkan | backend unknown; a value outside the list |
| os | string[] | windows, macos, linux (lowercase prefix match on the OS family, so `win` works) | blank OS name; a value that isn't a prefix of those |
| goal | string[] | performance, balanced, quality | a value outside the list |
| mcVersion | string[] | exact match on the running MC version | MC version unknown |
| modPresent / modAbsent | string[] | mod ids. modPresent: all loaded; modAbsent: none loaded | a `null` entry |
| flags | string[] | all present in HardwareProfile.flags: `shaders-enabled`, `backend-vulkan`, `sodium-workaround:<NAME>` | a flag outside that list that isn't present; `backend-vulkan` absent while the backend is unknown |
| gpuModelMatches (v2) | string | Java regex *found* in the GPU subject string (the same one `gpuTiers` see), at most 200 characters, with the same read budget | no GPU info; invalid or overlong regex; budget exhausted |
| displayPixelsAtLeast / displayPixelsAtMost (v2) | int | display width × height | width or height unknown (≤ 0) |
| modVersion (v2) | object: mod id → Fabric version predicate | every listed mod is loaded and its version satisfies the predicate (Fabric Loader's `VersionPredicate`, as in fabric.mod.json: `">=0.6.0 <0.8.0"`, `"~0.9"`, `"*"`). A listed mod that isn't loaded is FALSE | unparseable predicate (any term that isn't a semantic version, e.g. `">=>="` or `\|\|`); the installed version is missing or not a semantic version |
| mcVersionRange (v2) | string: Fabric version predicate | the running MC version satisfies it | unparseable predicate or MC version |
| anyOf | Condition[] | at least one holds (an empty list is FALSE) | see below |
| not | Condition | negation | see below |

Flags come from detection: `backend-vulkan` from the graphics backend, `shaders-enabled` from Iris's API and `sodium-workaround:<NAME>` from Sodium's workaround list. If Iris's or Sodium's API can't be read, their flags read as absent (FALSE), so don't rely on the absence of those flags for anything restrictive.

### Evaluation: TRUE, FALSE or UNKNOWN (fail closed)
A condition evaluates to TRUE, FALSE or UNKNOWN. Only a top-level TRUE fires; UNKNOWN counts as false for every rule kind: no addition, no disable, no setting, no advice. In an addition's `avoidWhen`, only FALSE lets the addition through.

- **Unknown keys poison the whole condition.** While parsing, every condition object records the keys this client doesn't know, keys whose value is `null`, and values it can't read exactly (a non-integer or out-of-range number, a non-boolean for a boolean field). If any node of the tree (including inside `not` and `anyOf`) has one, the whole top-level condition is UNKNOWN. A newer field's meaning can't be guessed, and treating it as false inside `not` would flip it to true.
- **Undecidable values are UNKNOWN where they occur** (the last column above) and combine with Kleene logic:
  - `not UNKNOWN` = UNKNOWN;
  - `anyOf` is TRUE if any branch is TRUE, else UNKNOWN if any branch is UNKNOWN, else FALSE;
  - the fields of one object (AND) are FALSE if any is FALSE, else UNKNOWN if any is UNKNOWN, else TRUE.

  So `{"tierAtLeast": 5, "ramMbAtLeast": 8000}` is FALSE on a tier-3 machine even when RAM is unknown, and `not {"gpuModelMatches": "…"}` on a machine without GPU info is UNKNOWN, never TRUE.
- For a list of enumerated values (gpuVendor, backend, os, goal), a known value that matches makes the field TRUE; otherwise a value outside the vocabulary (or a `null` entry) makes it UNKNOWN (a newer client might match it); otherwise it's FALSE.
- The vocabularies above are also 0.1.0's. They may grow in a later 0.2.x, but 0.1.0's never do: the updater keeps the v1 set frozen, and a value only a newer client knows makes a condition v2-only.

v1 (0.1.x) evaluates the same fields two-valued: unknown RAM/VRAM/refresh are false, and unknown keys are ignored. That's why rules-v1.json may only contain v1 keys and v1 values (below).

## Settings keys
- `vanilla.<options.txt key>`, e.g. `vanilla.renderDistance`, `vanilla.simulationDistance`, `vanilla.maxFps`, `vanilla.enableVsync`, `vanilla.particles`, `vanilla.biomeBlendRadius`. Values are strings as they appear in options.txt, **without surrounding quotes**.
- `sodium.<section>.<field>` is a path inside `config/sodium-options.json`, e.g. `sodium.performance.chunk_builder_threads`.
- After `sodium.` (and the v2 `dh.`/`iris.` prefixes) a key is dot-separated segments of letters, digits and `_`; RigTune never changes a key with anything else in it.
- Other namespaces (e.g. Distant Horizons, Iris) are v2 only; rules-v1.json contains only `vanilla.` and `sodium.` keys.

## The v1 projection (rules-v1.json)
0.1.x evaluates only rules whose every field it understands, with unchanged semantics. The updater builds rules-v1.json from the same knowledge **field by field**:

- **Overrides.** A rule may carry `"v1"`, which is never written to either output:
  - `"v1": false` omits the rule from rules-v1.json;
  - `"v1": { … }` is shallow-merged over the rule for v1 only (e.g. a conservative v1 `recommendWhen`). It may only set fields 0.1.x knows, its conditions must be v1-only, and a `null` anywhere in it is an error (0.1.x reads a null condition as "always").
- **Automatic, per field** (after the override):
  - a `recommendWhen` that uses a v2 condition key, or a value outside the v0.1.0 vocabularies, becomes `{"always": false}`; `modIds`, `conflictsWith` and the other v1 fields stay, so 0.1.x keeps the conflict warning;
  - an `info` advice `when` like that becomes `{"always": false}` (a `warning` or `critical` one is an error instead, so a warning never disappears for 0.1.x without a decision);
  - an `avoidWhen` like that is dropped (with `avoidReason`), but only when the v1 `recommendWhen` is `{"always": false}`. Otherwise it's an error: dropping it would let 0.1.x offer the mod where 0.2 avoids it. Give a v1 `avoidWhen` override.
- **Errors** (the updater stops):
  - a setting entry whose `when` uses v2 features, or whose key is outside `vanilla.`/`sodium.`, without an explicit `v1`. Omitting a setting entry can change which entry wins for 0.1.x, so it's always a maintainer decision;
  - a rule field outside the v1 whitelist (`requires`, `avoidSelected`) without an explicit `v1`. With an override the field is left out only if that is exactly as safe: `requires` only when empty, `avoidSelected` only when true or when the v1 rule has no `avoidWhen`. Otherwise use `"v1": false`;
  - unknown fields (including unknown top-level fields), unknown condition keys, nulls, values outside the vocabularies, out-of-range integers, regexes over 200 characters and malformed `settingLabels` anywhere in knowledge.json.
- When a ModRule is left out, the other rules' `conflictsWith` references to its slug become its `modIds` (0.1.x resolves a slug only through a rule it has, but matches a mod id directly), so 0.1.x still sees the conflict. REVIEW.md (d) lists each rewrite.
- `settingLabels` is left out of rules-v1.json. Tier rules are copied as they are.
- Every omission and field change is listed in `rules/REVIEW.md` section (d).
- `tools/check_rules_v1.py` (CI job `rules-v1-compat`) checks the result. The pinned-v0.1.0 differential test (`RulesV1DifferentialTest`) checks that, compared with the baseline `src/test/resources/v010/rules-v1-baseline.json` (the rules 0.1.0 shipped), rules-v1.json gives 0.1.x no new appliable recommendation (ticked or not), ticks none that was unticked, and loses no conflict or advice. `SchemaConsistencyTest` checks the updater's field lists and vocabularies against the Java code and the pinned v0.1.0 copy.

## Rules for maintainers
- **UNKNOWN switches restrictions off.** A condition that is UNKNOWN doesn't fire, so a clamp, a lower value, an `avoidWhen` or a warning gated on something a client may not know (RAM, VRAM, refresh rate, display size, GPU model, mod versions, the backend) silently doesn't apply where it's unknown. For example `{"key": "vanilla.renderDistance", "max": 8, "when": {"not": {"vramMbAtLeast": 4096}}}` does nothing on a machine that doesn't report VRAM. Gate restrictions on always-known facts (heap, tiers, goal, installed mods), or add a second rule that covers the unknown case with one of those.
- **Never add a v2-only or future field to an existing restrictive rule** (a clamp, a lower value, an `avoidWhen`, a warning). Clients that don't know the field poison the whole rule, so they'd *lose* the restriction they have today. Add a new rule next to the old one instead (or gate the new one with `requires`).
- **Tier-rule schema changes (`gpuTiers`, `cpuTiers`, `heapTiers`) need a new schemaVersion**: tier rules have no fail-closed handling.
- A setting rule that *restricts* for a v2-only condition needs a deliberate `v1` decision (override or `false`), and REVIEW.md (d) shows it.
