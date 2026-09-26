# Rules file schema (v2)

## Files
| file | read by | contents |
|---|---|---|
| `rules/rules-v2.json` (+ an identical bundled copy, `src/main/resources/rigtune/rules-v2.json`) | RigTune 0.2+ | the full rules, `schemaVersion: 2` |
| `rules/rules-v1.json` | RigTune 0.1.x (remote only; 0.2 doesn't bundle it) | the **v1 projection**: only what 0.1.x understands, never less safe (below) |
| `rules/source/knowledge.json` | the updater | the hand-maintained source; may use v2 features and per-rule `v1` overrides |

They're served from `https://raw.githubusercontent.com/chaotix345/rigtune/main/rules/rules-v2.json` and `.../rules-v1.json`.

**Where they come from.** `tools/update_rules.py` generates both from:
- the hand-maintained source `rules/source/knowledge.json`, which has the same shape minus the generated fields, plus two maintainer-only fields (`reviewIgnore` and `v1` on rules and on `gpuTiers`/`cpuTiers` rows, below)
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
- `-Drigtune.rules.baseUrl=<folder URL>` replaces `https://raw.githubusercontent.com/chaotix345/rigtune/main/rules/` (for tests). It must be https; plain http is accepted only for localhost, 127.x.x.x and [::1].

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
| profileTemplates | object | **v2 only, 0.4+**, optional: the Profiles templates ([profileTemplates](#profiletemplates-v2-04)) |
| stutterAdvice | AdviceRule[] | **v2 only, 0.4+**, optional: the Stutter Doctor's advice ([stutterAdvice](#stutteradvice-v2-04)) |
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

## v1 on tier rows (source-only)
`{ "pattern": "(?i)RX\\s*9070\\s*GRE\\b", "vendor": "amd", "integrated": false, "tier": 4, "v1": false }`

In `rules/source/knowledge.json` only, a `gpuTiers` or `cpuTiers` row may carry `"v1": false`. The updater leaves the row out of rules-v1.json and lists it in REVIEW.md section (d); the key itself is never written to either output, so no client ever sees it (it isn't a schema change). Any other `v1` value, and `v1` on a `heapTiers` row, is a knowledge error.

0.1.x then classifies that hardware exactly as before: the rows that follow still apply in order, and a string no row matches falls back to `gpuVendorFallback` or the CPU formula. **Every new tier row gets `"v1": false`.** A different tier for 0.1.x isn't "more conservative" in either direction: a lower tier changes setting values and can newly trigger `tierAtMost` rules, which RulesV1DifferentialTest counts as new recommendations. Leave a row out of v1 only if it's new; leaving out an existing row changes what 0.1.x already does for that hardware. RulesV1DifferentialTest enforces this: it fails when rules-v1.json's `gpuTiers`, `gpuVendorFallback`, `cpuTiers` or `heapTiers` differ from the rules 0.1.0 shipped.

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
- `skipUpdateWhen` (**v2 only**, a Condition): when it is TRUE for an installed mod, RigTune doesn't offer that mod's update (neither the Update action nor the "update it in your launcher" note). It shows an info advice instead, `<title> updates itself`: "Its own auto-updater is on, so RigTune leaves its updates to it." FALSE or UNKNOWN offers the update as usual. Distant Horizons uses it with `settingIs` on its own auto-updater switch, because both updaters replacing the same jar at exit collide. Independently of the rules, a mod whose own updater already left a jar in `mods/update/` (directly or one folder down; matched by that jar's fabric.mod.json id) isn't offered its update either: the info advice `<name> has an update of its own waiting` says "It's in mods/update, so RigTune leaves it alone."
- `conflictsWith` lists slugs or mod ids. Don't recommend this mod if any of them is installed. Two mods that conflict (either rule declaring it, resolved through every rule) are never offered together either: the one earlier in `mods` stays, and its reason gets "RigTune doesn't also offer <titles>, which conflict with it." A download batch that still holds both sides stages the earlier one only, and a Modrinth `incompatible` dependency against an installed mod or another mod of the batch fails the addition too.
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

**The `ram-` id prefix** marks advice whose fix is changing the memory (heap) allocation: RigTune 0.3+ shows the detected launcher's steps for that ("In the Modrinth App: …") under every advice whose id starts with `ram-`. Use the prefix only for such advice, and give every such advice the prefix (in `stutterAdvice` too). Older clients ignore it (it's only an id).

**The `jvm-` id prefix** (0.4+) marks advice about the Java arguments. RigTune 0.4 adds "Found in your Java arguments: <flag names>" (from its own flag table, never the raw arguments) and the detected launcher's Java-arguments steps under every advice whose id starts with `jvm-`. Such advice tests the [jvm- facts](#jvm--facts-v2-04) and carries `"requires": ["jvm-flags"]` and `"v1": false`. Heap advice stays `ram-` (it points at the launcher's memory setting, which for Prism overrides an `-Xmx` typed in the Java arguments).

## SettingLabel (v2)
`"settingLabels": { "sodium.performance.chunk_builder_threads": { "name": "Chunk builder threads", "values": { "0": "Auto" } } }`

Human-readable names for recommendation titles (and the share report). `name` replaces the caption made from the key (the mod prefix, e.g. `Sodium: `, stays); `values` maps a setting value (compared after normalisation, so `"0"` also matches `0.0`) to a label. A missing label falls back to the key's caption and the raw value. Labels never change what is applied.

## requires (v2)
`"requires": ["some-client-feature"]` on a ModRule, ObsoleteRule, SettingRule or AdviceRule (and on a profile template or its settings entries). A rule whose `requires` names any feature this client doesn't know is **skipped entirely** (no addition, disable, conflict warning, setting or advice). It is the escape hatch for future rule-level fields that must not fail open: a rule that depends on such a field lists the feature that implements it.

| feature | known by | needed by |
|---|---|---|
| `jvm-flags` | the main list from 0.4 (`Recommender.SUPPORTED_FEATURES`) | every rule that tests a [jvm- fact](#jvm--facts-v2-04) (the updater enforces it) |
| `stutter-doctor` | only the Stutter Doctor (0.4+), never the main list | every [stutterAdvice](#stutteradvice-v2-04) entry (the updater enforces it) |

RigTune 0.2.0 and 0.3.0 know no features, so they skip every rule with a non-empty `requires`.

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
| flags | string[] | all present in HardwareProfile.flags: `shaders-enabled`, `backend-vulkan`, `sodium-workaround:<NAME>`; from 0.4 also the [jvm- facts](#jvm--facts-v2-04) (v2, only in rules with `requires: ["jvm-flags"]`) | a flag outside that list that isn't present; `backend-vulkan` absent while the backend is unknown; a jvm- fact while RigTune's JVM check hasn't run |
| gpuModelMatches (v2) | string | Java regex *found* in the GPU subject string (the same one `gpuTiers` see), at most 200 characters, with the same read budget | no GPU info; invalid or overlong regex; budget exhausted |
| displayPixelsAtLeast / displayPixelsAtMost (v2) | int | display width × height | width or height unknown (≤ 0) |
| modVersion (v2) | object: mod id → Fabric version predicate | every listed mod is loaded and its version satisfies the predicate (Fabric Loader's `VersionPredicate`, as in fabric.mod.json: `">=0.6.0 <0.8.0"`, `"~0.9"`, `"*"`). A listed mod that isn't loaded is FALSE | unparseable predicate (any term that isn't a semantic version, e.g. `">=>="` or `\|\|`); the installed version is missing or not a semantic version |
| mcVersionRange (v2) | string: Fabric version predicate | the running MC version satisfies it | unparseable predicate or MC version |
| driverVersion (v2, 0.4+) | object: `{"vendor": <gpuVendor>, "atLeast": "526.47", "atMost": "536.22"}` | the detected GPU vendor is `vendor` and the parsed driver version is within the bounds ([driverVersion](#driverversion-v2-04)) | a vendor mismatch or unknown vendor; a driver string RigTune can't parse; an unknown field or a missing `vendor` |
| settingIs (v2) | object: settings key → string, number or boolean | every listed key is in the current settings (the same `SettingsSnapshot` setting rules see) and its value equals the expected one after normalisation (case-insensitive, `1.0` equals `1`, `true` equals `"true"`). An empty object is TRUE | a listed key isn't in the snapshot (the mod or its config file is missing, or the config predates the key). So `not {"settingIs": …}` on a missing key is UNKNOWN, never TRUE. A value that isn't an object of strings, numbers and booleans poisons the condition like an unknown key |
| anyOf | Condition[] | at least one holds (an empty list is FALSE) | see below |
| not | Condition | negation | see below |

`mcVersionRange` is matched against Loader's normalized MC version, which for a snapshot or pre-release is a semantic pre-release: 26.4-snapshot-1 is `26.4-alpha.1`, 26.4-rc-1 is `26.4-rc.1`. So `<26.4` is still TRUE on 26.4's snapshots and pre-releases; end the bound with `-` to exclude them too: `<26.4-` is TRUE on 26.3.x and FALSE from the first 26.4 snapshot on (`vulkan-backend` uses it, since 26.4 makes Vulkan the default renderer). Use `mcVersion` only for exact versions. It's a v2 key: an `info` advice that uses it is never shown to 0.1.x unless a `v1` override gives it a v1 `when`.

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

The Stutter Doctor's keys (0.4+) are Condition fields too, but the updater allows them only inside [stutterAdvice](#stutteradvice-v2-04); in the main list they are always UNKNOWN.

### driverVersion (v2, 0.4+)
`{"driverVersion": {"vendor": "nvidia", "atLeast": "526.47", "atMost": "536.22"}}`
- `vendor` (required) is a lower-case `gpuVendor` value; `atLeast`/`atMost` are dotted version strings (digits and dots, each part below 2^31), compared left to right on the numbers RigTune parses from the driver string, with missing trailing parts as 0. The updater requires at least one bound and `atLeast` ≤ `atMost`.
- TRUE or FALSE only when the detected vendor matches and the driver string parses (AMD Adrenalin "… Context 26.8.1.…", Mesa "(Core Profile) Mesa 24.2.3", "NVIDIA 560.94", Intel Windows "- Build 31.0.101.5595"; on 26.3's Vulkan backend only the driver part of MC's string). Anything else is UNKNOWN: a vendor mismatch, an unknown vendor, an unparseable or pre-2024 five-part AMD string, an unknown field, a missing `vendor`.
- 0.2.0/0.3.0 don't know the key, so a condition using it is UNKNOWN there (fail closed). That is safe for advice and value entries; in a clamp, an `avoidWhen` or a `skipUpdateWhen` the rule needs `requires` ([Rules for maintainers](#rules-for-maintainers); the updater enforces it).
- It's a v2 key, so a warning using it needs a `v1` decision (the seeds use `"v1": false`).

### jvm- facts (v2, 0.4+)
RigTune 0.4 reads the running JVM once per session (on-device; raw arguments are never shown, logged or shared) and adds facts to `HardwareProfile.flags`, which `flags` tests:

| fact | set when |
|---|---|
| `jvm-gc-g1`, `jvm-gc-zgc`, `jvm-gc-shenandoah`, `jvm-gc-parallel`, `jvm-gc-serial`, `jvm-gc-epsilon`, `jvm-gc-other` | the collector that runs |
| `jvm-gc-typed` | the collector was chosen in the arguments (not Java's own default) |
| `jvm-ignored-flags` | a `-XX` flag Java ignores (no such option any more) or overrides |
| `jvm-young-gen-fixed` | `-Xmn`, `-XX:NewSize` or `-XX:MaxNewSize` |
| `jvm-server-flags` | Aikar's markers, or at least 4 of its distinctive flags |
| `jvm-explicit-gc-disabled` | `-XX:+DisableExplicitGC` |
| `jvm-xmx-duplicate` | two or more `-Xmx` values (Java uses the last one) |

- The list is `core/jvm/JvmFacts.RULE_FLAGS`, mirrored in the updater (`JVM_FLAGS`). Any other `jvm-` value is refused, and so is `jvm-probed`: RigTune sets it when the check ran, and every jvm- fact is UNKNOWN while it's absent (OpenJ9, or the check not finished yet), so a `not {"flags": ["jvm-…"]}` can't fire by accident.
- A rule that tests a jvm- fact needs `"requires": ["jvm-flags"]` (0.2.0/0.3.0 skip it) and `"v1": false`.

## profileTemplates (v2, 0.4+)
```json
"profileTemplates": { "templates": [
  { "id": "battery", "goal": "performance", "facts": { "onBattery": true, "hasBattery": true },
    "settings": [ { "key": "vanilla.renderDistance", "max": 8, "reason": "…" },
                  { "key": "dh.client.advanced.debugging.rendererMode", "value": "DISABLED", "when": { "modPresent": ["distanthorizons"] } } ] },
  { "id": "recording", "goal": "balanced", "settings": [ { "key": "vanilla.maxFps", "value": "$recordingFps" } ] }
] }
```
The Profiles templates (docs/v0.4/SPEC.md item 4). RigTune 0.4 computes a template on demand, in layers: the player's saved baseline, then the rules' value entries evaluated with the template's `goal` and forced `facts`, then the template's `settings`, then every clamp (the rules' and the template's), then only keys present in the instance. 0.2.0/0.3.0 ignore the section; a document without it (rules-v1.json, an old cache) makes 0.4 use the bundled rules' section.

| field | notes |
|---|---|
| id | one of `max_fps`, `balanced`, `quality`, `battery`, `recording`, each at most once |
| goal | `performance`, `balanced` or `quality` |
| facts | optional; forces `onBattery`/`hasBattery` (booleans) before the rules are evaluated |
| settings | optional SettingRules (value **or** min/max, `when`, `reason`, `impact`, `defaultSelected`, `requires`) over the keys profiles manage: the share-code table (vanilla renderDistance, simulationDistance, entityDistanceScaling, maxFps, enableVsync, inactivityFpsLimit, particles, biomeBlendRadius, weatherRadius, textureFiltering, renderClouds, prioritizeChunkUpdates, improvedTransparency, entityShadows, cutoutLeaves; Sodium's culling switches, chunk_build_defer_mode, quad_splitting_mode; `iris.enableShaders`, `iris.maxShadowRenderDistance`; DH's quality keys and `rendererMode`) plus the local-only `sodium.performance.chunk_builder_threads` and `dh.common.multiThreading.numberOfThreads`. Never `vanilla.graphicsPreset` or `iris.shaderPack` |
| requires | optional; a client that lacks a feature skips the template |

`"$recordingFps"` is resolved only here (60 when the display is 60 Hz or more or unknown, else its rate rounded down to a multiple of 10, at least 30); the main settings may not use it. The updater validates the section (ids, goal, facts, keys, value xor min/max, tokens, conditions) and never writes it to rules-v1.json. Templates take no `v1`.

## stutterAdvice (v2, 0.4+)
```json
"stutterAdvice": [
  { "id": "ram-stutter-gc-heap", "requires": ["stutter-doctor"], "kind": "warning", "impact": "high",
    "when": { "stutterShareAtLeast": { "gc": 30 }, "anyOf": [ { "gcFullPausesAtLeast": 1 }, { "liveSetPercentAtLeast": 75 } ], "heapRaiseRoomMbAtLeast": 2048 },
    "title": "…", "text": "…" }
]
```
AdviceRules the Stutter Doctor evaluates against a session's measured facts (docs/v0.4/SPEC.md item 5); the main list never reads the section, and 0.2.0/0.3.0 ignore it. Every entry needs `"requires": ["stutter-doctor"]`, a unique `id`, a `kind` (info, warning, critical), an `impact` (high, medium, low), a `title` and a `text`. Memory advice ids start with `ram-` (the launcher's memory steps); advice never suggests `-XX:+DisableExplicitGC`, and any wording about the garbage collector follows the JVM advice's measured conclusion (keep Java's defaults; size the heap). Conditions may use every v2 key plus the Stutter Doctor's keys, which are UNKNOWN anywhere else:

| field | type | meaning |
|---|---|---|
| stutterShareAtLeast | object: cause → whole percent | the share of the lost time each cause claimed: `gc`, `chunkLoad`, `chunkBuild`, `tick`, `render`, `unknown` |
| stutterTaggedShareAtLeast | object: tag → whole percent | the share of spikes carrying each tag (claims no time): `worldSave`, `dh`, `cpuContention`, `afterTeleport`, `movingFast` |
| gcFullPausesAtLeast / gcStallsAtLeast / gcExplicitPausesAtLeast | int ≥ 0 | full collections (not System.gc()), allocation stalls, System.gc() pauses in the session |
| liveSetPercentAtLeast | int 0-100 | live data after an old/full collection, as a share of the maximum heap |
| heapRaiseRoomMbAtLeast | int ≥ 0 | room to raise the heap: min(RAM/2, RAM − 4096) − heap, in MB |
| cpuContentionShareAtLeast | int 0-100 | the share of spikes with the CPU contended |
| spikesPerMinuteAtLeast | int ≥ 0 | spikes per minute **× 10** (30 = 3 a minute) |
| gcCollector | string[] | `g1`, `zgc`, `shenandoah`, `parallel`, `serial` |

The percentages are whole numbers, as JSON integers or digit strings (the map values are strings in the client; plan review K-M1). A malformed value poisons only its own condition. The whole section is left out of rules-v1.json, and its entries take no `v1`.

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
  - a rule field outside the v1 whitelist (`requires`, `avoidSelected`, `skipUpdateWhen`) without an explicit `v1`. With an override the field is left out only if that is exactly as safe: `requires` only when empty, `avoidSelected` only when true or when the v1 rule has no `avoidWhen`, `skipUpdateWhen` always (0.1.x offers every available update whatever its rules say, and the field only ever takes one away). Otherwise use `"v1": false`;
  - unknown fields (including unknown top-level fields), unknown condition keys, nulls, values outside the vocabularies, out-of-range integers, regexes over 200 characters and malformed `settingLabels` anywhere in knowledge.json.
- When a ModRule is left out, the other rules' `conflictsWith` references to its slug become its `modIds` (0.1.x resolves a slug only through a rule it has, but matches a mod id directly), so 0.1.x still sees the conflict. REVIEW.md (d) lists each rewrite.
- `settingLabels`, `profileTemplates` and `stutterAdvice` are left out of rules-v1.json (0.1.x rejects nothing, but none of them is for it). Tier rules are copied as they are, except `gpuTiers`/`cpuTiers` rows with `"v1": false`, which are left out ([v1 on tier rows](#v1-on-tier-rows-source-only)).
- 0.4's new advice (`jvm-*`, the driver seeds) carries `"v1": false`: its keys and facts are v2-only, and 0.1.x must not get a warning it can't evaluate.
- Every omission and field change is listed in `rules/REVIEW.md` section (d).
- `tools/check_rules_v1.py` (CI job `rules-v1-compat`) checks the result. The pinned-v0.1.0 differential test (`RulesV1DifferentialTest`) checks that, compared with the baseline `src/test/resources/v010/rules-v1-baseline.json` (the rules 0.1.0 shipped), rules-v1.json gives 0.1.x no new appliable recommendation (ticked or not), ticks none that was unticked, and loses no conflict or advice. `SchemaConsistencyTest` checks the updater's field lists and vocabularies against the Java code and the pinned v0.1.0 copy.

## Rules for maintainers
- **UNKNOWN switches restrictions off.** A condition that is UNKNOWN doesn't fire, so a clamp, a lower value, an `avoidWhen` or a warning gated on something a client may not know (RAM, VRAM, refresh rate, display size, GPU model, mod versions, the backend, a mod's config value) silently doesn't apply where it's unknown. For example `{"key": "vanilla.renderDistance", "max": 8, "when": {"not": {"vramMbAtLeast": 4096}}}` does nothing on a machine that doesn't report VRAM. Gate restrictions on always-known facts (heap, tiers, goal, installed mods), or add a second rule that covers the unknown case with one of those.
- **Never add a v2-only or future field to an existing restrictive rule** (a clamp, a lower value, an `avoidWhen`, a warning). Clients that don't know the field poison the whole rule, so they'd *lose* the restriction they have today. Add a new rule next to the old one instead (or gate the new one with `requires`).
- **A key or value newer than 0.2.0/0.3.0 in a clamp, an `avoidWhen` or a `skipUpdateWhen` needs `requires`** (plan review R-L1; the updater refuses it otherwise). There, failing closed means *not* restricting (no clamp, no disable, an update offered), so `requires` makes older clients skip the rule knowingly; keep a rule they understand next to it. The keys and values 0.2.0/0.3.0 know are the updater's `LEGACY_V2_CONDITION_KEYS`/`LEGACY_V2_VOCABULARIES`, checked against the pinned copy by SchemaConsistencyTest.
- **Tier-rule schema changes (`gpuTiers`, `cpuTiers`, `heapTiers`) need a new schemaVersion**: tier rules have no fail-closed handling.
- **A new `gpuTiers`/`cpuTiers` row gets `"v1": false`**, so 0.1.x keeps classifying that hardware as 0.1.0 did. Insert it before the broader row that matches the same strings today (the first match wins), and add the strings to GpuClassifierTest/CpuClassifierTest, including one the new row must not catch.
- A setting rule that *restricts* for a v2-only condition needs a deliberate `v1` decision (override or `false`), and REVIEW.md (d) shows it.
