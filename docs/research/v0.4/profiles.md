# v0.4 research: Performance Profiles, share codes, battery hook, shader-pack profiles

Owner: r-profiles. Date: 2026-09-26. Base: `feat/v0.4.0` = v0.3.0 (only `docs/PROGRESS.md` differs from tag `v0.3.0`).
Scope: P1 item 4 (Performance Profiles + share codes + laptop hook) and P2 item 12 (shader-pack profile switching).

Every claim below is marked with how it was checked: **[src]** read in this repo, **[jar]** read or run from a released or cached
jar, **[run]** measured or executed in a scratch harness, **[UNVERIFIED]** not checked. Scratch harnesses live in the session
scratchpad (`scratchpad/r-profiles/{compat,proto,iris,mc}`); they are not part of the repo.

---

## 0. Recommendation in brief

1. **A profile switch is an ordinary Apply.** Build a `List<Recommendation>` of `Action.SetSetting(key, current, target)` for every
   managed key whose current value differs, then run it through `RealController.apply` (vanilla keys set now, Sodium/DH/Iris keys
   staged for the post-exit helper) and `controller.preview` (Preview). Journal, History, Undo this/last/all need **no changes**:
   I ran the released 0.3.0 `UndoPlanner` over profile-switch entries and it undoes them correctly (section 3).
2. **Journal representation: reuse kind `apply`, add nothing to `history.json`.** Label the entry through a sidecar in the new
   `config/rigtune/profiles.json` (`switches: [{entryId, profileId, templateId, name}]`). 0.3.0 then shows the switch as a normal
   "Apply" and can undo it; 0.4 shows "Profile: Battery". An optional `profile` field on `JournalEntry` would also be ignored by
   0.3.0, but 0.3.0 silently drops it the first time it rewrites `history.json` (verified), so the sidecar is strictly more robust.
   Never bump `history.json`'s `formatVersion` (0.3.0 would go read-only and stop journaling) and never add a new change `type`
   (0.3.0 renders it as "Added null").
3. **Templates** (Max FPS, Balanced, Quality, Battery, Recording) are computed on demand as layers: the user's own baseline
   ("My settings", auto-saved before the first switch) → the rules' setting values evaluated with the template's goal and forced
   facts (e.g. `onBattery=true`) → the template's own overrides → every clamp (so the heap and DH safety caps still hold in
   Quality/Max FPS). Tunable parts live in a new optional top-level `profileTemplates` section of `rules-v2.json`, which 0.2/0.3
   ignore (verified with the 0.3.0 parser) and the updater strips from `rules-v1.json`. The algorithm, ids, and a fallback copy
   stay in code.
4. **Share code `RT1-…`: a binary table-indexed encoding, no compression, CRC32, base64url.** A typical profile (24 keys) is
   **102 characters**, vanilla only (15 keys) 78, every key (30) 119. No decompression means no zip-bomb class at all; values are
   varints mapped through a frozen, append-only allowlist table (enum index / bounded int / bool), so a code *cannot* carry text,
   a path, a mod id, NaN or a newline into any file.
5. **Battery hook**: `onBattery` is sampled **once per session** today (cached `HardwareProbe.slow` future). Add a `PowerWatcher`
   that runs only when a real battery was found, polls `PowerSource.updateAttributes()` every 30 s on its own daemon thread
   (≈0.4-0.5 ms per poll measured with OSHI 6.9.0 on this machine), debounces over two polls, and **offers** Battery with a toast
   plus a banner button in the RigTune screen. Never auto-switch.
6. **Shader packs (P2 12): verifiable, but recommend DEFER.** Iris 1.11.4/1.11.6 read `shaderpacks/<pack>.txt` at every pack load
   and rewrite it normalised; pack option string values are pasted into `#define NAME <value>` without an allowlist check (a
   shader-source injection vector, so pack options must never come from a share code). A safe version needs the helper's write
   scope widened to `shaderpacks/`, a new op type and a new journal namespace. Both Complementary Reimagined r5.9.3 and BSL 10.1.8
   use Iris's standard profile grammar and Iris logs `Profile: <name> (+N options changed by user)`, so it *can* be verified.

---

## 1. What RigTune manages today

### 1.1 The allowlist and the namespaces

- `core/model/SettingKeys.java:12-28` `VANILLA_ALLOWED`: 16 vanilla keys. `SettingKeys.changeable` (`:38-43`) also allows **any**
  `sodium.*`, `dh.*`, `iris.*` key whose remainder matches `[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)*` (`:33`). `safeValue` (`:50-52`)
  rejects ISO control characters. [src]
- `client/ConfigTargets.java:33-39` maps a prefix to a file, stager and reader: `sodium.` → `config/sodium-options.json`
  (`SodiumConfigPatcher`), `dh.` → `config/DistantHorizons.toml` (`TomlConfigPatcher`), `iris.` → `config/iris.properties`
  (`PropertiesConfigPatcher`). [src]
- Reading: `SettingsBridge.read` (`client/probe/SettingsBridge.java:54-63`) = `readVanilla` (`:121-138`, through the private
  `Options.processOptions(FieldAccess)` driven by a `Proxy`, `:358-393`) + `readTargets` (`:77-83`, mtime-cached per file,
  `:85-119`). Sodium is flattened by `SodiumConfigPatcher.flatten` (`readSodium`, `:160-171`). [src]
- The Recommender only proposes a key that is present in the snapshot, changeable and safe (`core/recommend/Recommender.java:369`),
  so **RigTune never creates a key**. Profiles must keep that rule. [src]

### 1.2 Per target: keys the rules set (rules-v2 r13, bundled), types and ranges

Vanilla (`options.txt`), written by `SettingsBridge.applyVanilla` (`SettingsBridge.java:182-212`: `graphicsPreset` first, each value
validated by the option's own codec and `validateValue` in `setOption` `:313-327`, then one `Options.save()` `:201-203`), journaled
by `VanillaChanges.apply` (`client/undo/VanillaChanges.java:25-34`, kind `apply`, status APPLIED, whole-snapshot diff). Ranges from
the MC 26.2 `Options` class (decompiled from Loom's `minecraft-client-only.jar`) [jar]:

| key | options.txt value | range (26.2) | rules r13 set it? |
|---|---|---|---|
| renderDistance | int | 2..32 (2..16 if max heap < 1 GB) | values 6/8/10/12/16 by tier; clamps by heap and DH |
| simulationDistance | int | 5..32 (..16 small heap) | clamps 5/6/8/10 |
| entityDistanceScaling | double | 0.5..5.0, quarter steps | clamps 0.5/0.75 |
| maxFps | int | 10..260 step 10 (260 = unlimited) | `$refreshRateCap`; 60 on battery |
| enableVsync | bool | | false; true on battery |
| inactivityFpsLimit | enum | `minimized`, `afk` | `afk` with a battery |
| particles | int enum | 0..2 (ALL, DECREASED, MINIMAL) | min 1/2 |
| biomeBlendRadius | int | 0..7 | clamps 0/1 |
| weatherRadius | int | 3..10 | 5 |
| textureFiltering | int enum | 0..2 (NONE, RGSS, ANISOTROPIC) | 0 |
| renderClouds | enum | `false`, `fast`, `true` (codec also accepts bools) | "false" |
| prioritizeChunkUpdates | int enum | 0..2 (NONE, PLAYER_AFFECTED, NEARBY) | 0 without Sodium |
| improvedTransparency | bool | | false tier ≤ 3 |
| entityShadows | bool | | false tier ≤ 2 |
| cutoutLeaves | bool | | false tier ≤ 2 |
| graphicsPreset | enum | rewrites a dozen options | never set by rules; **exclude from profiles** |

The benchmark also writes `renderDistance`/`simulationDistance` through `KeepSettings.apply` (`client/benchmark/KeepSettings.java:21-41`,
kind `benchmark`; values from `BenchmarkResultScreen.java:95-97`) and changes `maxFps`/`enableVsync`/`inactivityFpsLimit` in memory
only (`BenchmarkController.java:66-68`), restoring them. [src]

Sodium (`config/sodium-options.json`), staged by `SodiumConfigPatcher.stage` (`core/apply/SodiumConfigPatcher.java:134`), applied by
the helper as `PATCH_JSON` (`ApplyExecutor.java:478`). Type rule: an existing bool accepts only true/false, a number only a number
(whole when it was whole), **a missing field gets an inferred type and a missing file is created from `{}`** (DESIGN "Deviations").
So for imported codes, Sodium keys must additionally require Sodium to be loaded and the key to exist. Enum values from the
`sodium-mc26.2-0.9.2-fabric` jar [jar]:

| key | type | values |
|---|---|---|
| performance.use_fog_occlusion / use_block_face_culling / use_entity_culling / animate_only_visible_textures | bool | |
| performance.chunk_builder_threads | int | 0 = auto; upper bound not checked by RigTune or verified in Sodium [UNVERIFIED] |
| performance.chunk_build_defer_mode | enum | ALWAYS, ONE_FRAME, ZERO_FRAMES (`DeferMode`) |
| performance.quad_splitting_mode | enum | OFF, SAFE, UNLIMITED (`QuadSplittingMode`) |

Distant Horizons (`config/DistantHorizons.toml`), staged by `TomlConfigPatcher.stage` (`core/apply/TomlConfigPatcher.java:88-112`),
`PATCH_TOML`. Only existing keys; the value must keep the file's quoting kind; no quote/backslash/newline (`unsafe`, `:121-141`).
Enum lists from docs/research/v0.2/dh-iris.md §2 (bytecode-checked there):

| key | type | values |
|---|---|---|
| client.advanced.graphics.quality.lodChunkRenderDistanceRadius | int | DH API min/max at runtime (not hard-coded) |
| client.advanced.graphics.quality.verticalQuality | quoted enum | HEIGHT_MAP, LOW, MEDIUM, HIGH, VERY_HIGH, EXTREME, PIXEL_ART |
| client.advanced.graphics.quality.horizontalQuality | quoted enum | LOWEST, LOW, MEDIUM, HIGH, EXTREME |
| client.advanced.graphics.quality.maxHorizontalResolution | quoted enum | CHUNK, HALF_CHUNK, FOUR_BLOCKS, TWO_BLOCKS, BLOCK |
| common.multiThreading.numberOfThreads | int | sanity-clamp to logical cores |
| client.advanced.debugging.rendererMode | quoted enum | DEFAULT, DEBUG_TRIANGLE, DISABLED; **DH's on/off** (`DhCompat.java:10`: `renderingEnabled` persists to it) — read by rules (`settingIs`), not set |

Iris (`config/iris.properties`), staged by `PropertiesConfigPatcher.stage` (`core/apply/PropertiesConfigPatcher.java:56-76`),
`PATCH_PROPERTIES`, existing keys only. Iris's own keys (`IrisConfig.save`, Iris 1.11.4, decompiled) [jar]: `shaderPack`,
`enableShaders` ("false" disables; anything else enables), `allowUnknownShaders`, `enableDebugOptions`, `disableUpdateMessage`,
`maxShadowRenderDistance` (int, UI slider 0..32 in `IrisVideoSettings`; a parse failure resets to 32 and rewrites the file),
`colorSpace`. Rules set only `iris.maxShadowRenderDistance` (clamps 4/6/8).

Mod enable/disable: `Action.DisableMod` → `Op.disableFile` (`RealController.java:391-395`), renamed to `.jar.disabled` by the
helper. There is **no enable action**; re-enabling exists only inside Undo (`UndoPlanner` builds `ENABLE_FILE` from `.disabled`).
"Optional mods" in RigTune's own vocabulary (`client/compat/OptionalMods.java`) are Distant Horizons and Iris, whose on/off
states are the config keys `dh.client.advanced.debugging.rendererMode` (DEFAULT/DISABLED) and `iris.enableShaders`.

---

## 2. The apply pipeline and the journal

`RealController.apply` (`client/RealController.java:370-448`):

1. One journal entry id per Apply (`:375`, `ChangeRecorder.newEntryId()`), local to the method.
2. Partition by action (`:383-399`): vanilla `SetSetting` → map; config `SetSetting` → per-target patches; `DisableMod` of a direct
   child of `mods/` → immediate op; `AddMod`/`UpdateMod` → downloads (Modrinth, async).
3. **Immediate**: vanilla values via `VanillaChanges.apply` (options set and saved now, journaled APPLIED).
4. **Deferred**: config patches are validated against the file now (`target.stager()`), refused ones counted as failures; the
   ops (one per key) plus mod disables are merged into `config/rigtune/pending.json` by `Staging.stage`
   (`client/undo/Staging.java:78-97`: apply lock → merge → save → record). The journal gets STAGED changes with the op ids they have
   after the merge (`Staging.recorded`, `:99-103`, kind hard-coded `apply`).
5. At client stop, `HelperLauncher` starts `ApplyHelper` in a separate JVM; after the game exits (+2 s settle) it takes the apply
   lock, runs `ApplyExecutor` (groups all-or-nothing, config patches last), rewrites `pending.json` minus done/abandoned ops,
   writes `last-apply.json` and updates the journal (`ApplyExecutor.updateJournal`, `:159-161`): STAGED → APPLIED/ABANDONED.
6. Next launch: `RigTunePreLaunch`/`HistoryStartup` reconcile (a STAGED change whose op is gone → ABANDONED), WARN lines.

Journal (`core/history/Journal.java`): `config/rigtune/history.json`, `{formatVersion: 1, entries: [...]}` (`:30`, `:159`), last 50
entries (`cap`, `:252-268`), every write under the apply lock, atomic. `JournalEntry(id, at, kind, rigtuneVersion, mcVersion,
undoOf, changes)` with kinds `apply`, `benchmark`, `undo`, `legacy-import` (`JournalEntry.java:7-12`); `JournalChange(id, type
setting|file, key, before, after, action, modId, file, resultFile, status, opId, group, reverts)` (`JournalChange.java:9`).
`last-apply.json` is the helper's per-op result (`ApplyResult`), used by History for failure reasons (`ApplyFailures`).

Undo (`core/history/UndoPlanner.java`): Undo last = newest non-undo entry with something left (`plan`, `:122-143`); Undo this =
`planEntry` (`:147-161`); candidates are STAGED changes and APPLIED ones not being reverted (`:256-263`); a change a later entry
touched again is skipped as superseded (`:316-...`); settings revert when the current value is still the latest `after`; staged
changes are cancelled by dropping their op groups from `pending.json`. Kinds other than `undo` are all treated alike.

### Can a profile switch be an ordinary Apply?

Yes. A switch = `List<Recommendation>` with `Action.SetSetting(key, current, target)` for every managed key that (a) is present in
`SettingsBridge.read` (never create a key), (b) passes `SettingKeys.changeable`/`safeValue`, (c) differs by `SettingValues.same`.
Then:

- Apply: the existing switch in `RealController.apply` handles it; vanilla now, config at restart. Preview: `controller.preview`
  + `PreviewScreen` take the same list. Journal: one entry of kind `apply`. Undo: unchanged (section 3 shows the released 0.3.0
  planner undoing such entries, including the "Changed again by a later apply" skip when an older switch is undone).
- Works with the network off: no Modrinth call for `SetSetting`.

What's missing (all small):

1. **The entry id isn't exposed.** Split `apply` into `apply(selected)` → `apply(selected, entryId)` (private body unchanged) so
   the profile code can record `{entryId → profile}` in the sidecar after the call.
2. **Same-key staged patches don't supersede each other.** `PendingActions.merge` (`core/apply/PendingActions.java:143-200`) only
   dedups identical changes and replaces enables by mod id. Two switches before a restart leave two `PATCH_JSON` ops for the same
   key (the later wins at exit), and the first switch's journal change stays STAGED and then APPLIED although it never was the
   final value. Fix: a `PATCH_*` op on the same path and the same single key replaces the older one (into `Merged.replaced`), so
   `Staging.recorded` marks the old change DISCARDED through `HistoryUpdates.discard(droppedIds)` (`Staging.java:101-112`), exactly
   like a replaced enable. Client-side only (the helper never merges).
3. **A confirm button on `PreviewScreen`** (today it only has Done, `PreviewScreen.java:114`): an overload taking a label and a
   callback, used by "Import code" and "Preview switch".
4. **Guards**: refuse while `downloading` (already in `apply`), while `BenchmarkController.running()` (`BenchmarkController.java:350`;
   the benchmark restores options on exit and would overwrite a switch), and never include `vanilla.graphicsPreset`.
5. **Mod jar on/off** would need a new `Action.EnableMod` (plus the `RealController`/`PreviewPlanner` switch cases and
   `PreviewDifferentialTest`). Not needed if "optional-mod states" means DH/Iris on/off (config keys). Recommended: DH/Iris toggles
   only in 0.4; no jar toggles, and never in share codes (see open questions).

---

## 3. Downgrade safety (0.4.0 files read by 0.3.0)

Evidence: a harness compiled against the **released** `rigtune-0.3.0+mc26.2.jar` (downloaded with `gh release download v0.3.0`) and
Gson 2.14.0 (the version MC 26.2 and 26.3 ship, from Loom's `mojang_minecraft_info.json`) fed 0.4-style files to 0.3.0's own
`Journal`, `HistoryModel`, `UndoPlanner` and `RulesLoader` [run]:

| 0.4 writes | 0.3.0 does | verdict |
|---|---|---|
| extra field on an entry (`"profile": {...}` or `"profile": "Max FPS"`) and on a change (`"newField": 123`) | `state=OK`, entries parsed, fields ignored | safe |
| unknown kind `"profile"` | History label key `rigtune.history.kind.unknown` ("Change"), `undoable=true`, rows render | sane |
| kind `apply` + settings changes from a switch | "Apply"; Undo this on the older of two switches reverts `renderDistance 8→12` and skips `maxFps` "Changed again by a later apply"; Undo last reverts `maxFps 170→60` | fully undoable |
| a new change `type` (e.g. `"profile-marker"`) | rendered as `ADDED null` ("Added null"), counted as a mod, entry marked undoable | **don't** |
| any 0.3.0 write after reading the above (e.g. an Apply) | file rewritten; unknown fields **dropped** (`profile` gone), unknown kind string kept, ids kept | label loss only |
| `formatVersion: 2` | `state=NEWER`, `readOnly=true`, `record()` refuses ("written by a newer RigTune"), file untouched | **don't** (journaling dies after downgrade) |
| an existing field with a new JSON type (`kind` as an object) | `state=CORRUPT`; the next 0.3.0 `update()` moves it to `history.json.bad` and starts fresh | **don't** |
| `rules-v2.json` with a new top-level `profileTemplates` section | `RulesLoader.parse` OK (revision 99, 56 settings) | safe |
| a new `pending.json` op type | Gson maps an unknown enum constant to null; `ApplyExecutor.problem` returns "unknown operation" (`ApplyExecutor.java:436-439`), the op fails and is abandoned after 3 runs | safe (only matters for P2 12) |

0.2.0's `Journal`/`JournalEntry` are the same format (`formatVersion` 1, same record, same NEWER check; `git show v0.2.0:`) [src].
0.1.x has no journal. None of them reads `profiles.json`.

**Recommendation**: P1 writes nothing new into `history.json`, `pending.json`, `last-apply.json`, `settings.json` or `rigtune.json`.
Switch entries are kind `apply` with ordinary `setting` changes; the profile label lives in `profiles.json` (`switches`, keyed by
journal entry id, which 0.3.0 rewrites preserve; prune ids no longer in the journal). After a downgrade 0.3.0 shows "Apply" and can
undo; after the re-upgrade 0.4 shows "Profile: …" again. `profiles.json` has its own `formatVersion` (newer → read-only, like the
journal), keeps unknown fields on rewrite (read into a `JsonObject`, change known members, write back) so a later 0.4.x/0.5 field
survives a 0.4.0 save, and moves a corrupt file to `.bad`.

---

## 4. Templates from the rules

### 4.1 How recommendations are produced today

`Recommender.recommend` (`Recommender.java:80-113`): tier = `min(gpuTier, cpuTier, memTier)` (`TierCalculator.calculate`,
`core/hardware/TierCalculator.java:165-178`) with the goal's offset (PERFORMANCE −1, BALANCED 0, QUALITY +1; `Goal.java`), clamped
to 1..5. `Session.settings()` (`Recommender.java:326-380`): value entries whose `when` matches resolve per key (last match wins),
`$refreshRate`/`$refreshRateCap` tokens resolved (`SettingValues.java:32-58`); then clamp entries (`min`/`max`) apply to the resolved
value or the current one; then only keys whose current value differs become `set:` recommendations. Conditions can use `goal`,
`onBattery`, `hasBattery`, tiers, `modPresent`, `flags`, `settingIs` … (`ConditionEvaluator.java:72-105`); a value outside a
vocabulary (e.g. `goal: ["battery"]`) is UNKNOWN and fails closed in 0.2/0.3.

### 4.2 Proposal: layered targets

`ProfileTemplates.compute(rules, hardware, mods, snapshot, baseline, template)` (pure core):

1. **Baseline**: the "My settings" profile, auto-saved from the current values of the managed keys the first time the player opens
   Profiles (and re-savable). Without it, switching Battery → Quality would never turn shadows or clouds back on, because the rules
   only speak about low tiers (e.g. `entityShadows` only has a `tierAtMost 2` entry).
2. **Rule values** for the template's `goal`, with the template's forced **facts** applied to the `HardwareProfile` before
   evaluation (Battery: `onBattery=true`, `hasBattery=true`). Extract the resolution half of `Session.settings()` into
   `Recommender.settingTargets(rules, ctx, snapshot) → Map<key, Resolved>` (value entries only) and reuse it; `settings()` keeps its
   output (guarded by the scenario tests).
3. **Template overrides**: `SettingRule` entries from `profileTemplates.templates[id].settings` (value entries).
4. **All clamps last**: the rules' clamp entries and the template's, evaluated in the same context, so `heapMbAtMost` caps and the
   DH render-distance caps still apply to Quality and Max FPS.
5. Keep only keys present in the snapshot, changeable, safe, in the managed keyset (the share table below), not `graphicsPreset`.

Rules vs code:

- **Rules** (`rules-v2.json` only, new optional top-level section, bundled copy always present):
  ```json
  "profileTemplates": {
    "templates": [
      {"id": "max_fps",   "goal": "performance", "settings": [{"key": "vanilla.maxFps", "value": 260}, {"key": "vanilla.enableVsync", "value": false}]},
      {"id": "balanced",  "goal": "balanced"},
      {"id": "quality",   "goal": "quality"},
      {"id": "battery",   "goal": "performance", "facts": {"onBattery": true, "hasBattery": true},
       "settings": [{"key": "iris.enableShaders", "value": false, "when": {"modPresent": ["iris"]}},
                    {"key": "vanilla.renderClouds", "value": "false"},
                    {"key": "vanilla.renderDistance", "max": 8}, {"key": "vanilla.simulationDistance", "max": 6},
                    {"key": "vanilla.particles", "min": 1}]},
      {"id": "recording", "goal": "balanced",
       "settings": [{"key": "vanilla.maxFps", "value": "$recordingFps"},
                    {"key": "vanilla.enableVsync", "value": true, "when": {"not": {"refreshRateAtLeast": 61}}},
                    {"key": "vanilla.enableVsync", "value": false, "when": {"refreshRateAtLeast": 61}},
                    {"key": "vanilla.inactivityFpsLimit", "value": "minimized"},
                    {"key": "sodium.performance.chunk_build_defer_mode", "value": "ALWAYS", "when": {"modPresent": ["sodium"]}},
                    {"key": "vanilla.prioritizeChunkUpdates", "value": 0, "when": {"modAbsent": ["sodium"]}}]}
    ]
  }
  ```
  Older clients ignore it (verified). `tools/update_rules.py` today rejects unknown top-level fields in knowledge.json, so it needs
  to learn the section (validate: known template ids, `goal` in the vocabulary, `facts` only `onBattery`/`hasBattery`, settings
  entries as for `settings`), pass it to v2 and strip it from v1; document it in RULES_SCHEMA.md.
- **Code**: template ids and their lang keys; the layering algorithm; the facts mechanism; the `$recordingFps` token (resolved
  only in the template layer, so the main Recommender still skips unknown `$` tokens); the managed keyset; the safety predicates;
  and a fallback: when the active rules lack the section (a v1 file or an older cache), use the bundled rules' section.

### 4.3 Definitions (proposed, with reasons)

| template | goal | frame settings | other | why |
|---|---|---|---|---|
| Max FPS | performance (−1 tier) | maxFps 260 (unlimited), vsync off | rest from the rules at tier −1 | what "max FPS" means; RigTune's normal cap below refresh is a latency choice, not max FPS |
| Balanced | balanced | rules (cap at refresh −10, vsync off) | rules | = applying every current recommendation |
| Quality | quality (+1 tier) | rules | rules at +1; clamps still apply | |
| Battery | performance + facts `onBattery/hasBattery` | rules → maxFps 60, vsync on, inactivity `afk` (rules r13 already say so) | shaders off (if Iris), clouds off, RD ≤ 8, sim ≤ 6, particles ≥ decreased | cap + vsync cut GPU work most; shaders multiply GPU load; RD/sim cut CPU and GPU |
| Recording | balanced | maxFps `$recordingFps` = 60 on displays ≥ 60 Hz (the display rate, rounded down to 10, min 30, below that); vsync on only when the display is ≤ 60 Hz (the cap equals the refresh, so no extra cost and no tearing), else off | inactivity `minimized`; Sodium defer `ALWAYS` (Sodium) / vanilla chunk updates `0` (no Sodium) | 60 is the usual capture rate and leaves encoder headroom; the AFK throttle (30 FPS after 60 s without input, DESIGN "Benchmark v2") would ruin idle or cinematic shots, the benchmark disables it for the same reason; deferring chunk builds trades pop-in for even frame times |

UNVERIFIED: that OBS game capture records the game's frames without monitor tearing (so vsync off is fine above 60 Hz); Sodium's
own description of `ALWAYS`. DH is not switched off by any template (only the tier −1 LOD clamps); switching DH off on battery is an
open question.

---

## 5. Share code

### 5.1 Prototype numbers [run]

`scratchpad/r-profiles/proto/sharecode.py`, name "Charlie's Balanced", realistic values:

| key set | A: JSON+deflate | B: key=value lines+deflate | C: lines+deflate+preset dict | **D: binary index+varint** |
|---|---|---|---|---|
| vanilla only (15 keys) | 342 | 307 | 134 | **78** |
| vanilla + Sodium (22) | 503 | 463 | 192 | **96** |
| typical: vanilla + Sodium + Iris (24) | 531 | 495 | 204 | **102** |
| full: vanilla + Sodium + DH + Iris (30) | 712 | 672 | 262 | **119** |
| rules-managed subset, Balanced tier 3 (12) | 360 | 327 | 124 | **70** |

Example (D, typical): `RT1-ARJDaGFybGllJ3MgQmFsYW5jZWQYAAoBBQICAxAEAAUBBgAHAggHCQEKAgsADAANAQ4BDwEQAREBEgETABQBFQEcEB0B9kvlDA`

Also measured: 200,000 newline bytes deflate to 211 bytes (948:1), i.e. any compressed format needs an inflate cap. D needs none.

### 5.2 Grammar (recommended: D)

```
code  := "RT" VERSION "-" b64url(body || crc32_be(body))      ; VERSION = "1"; b64url = RFC 4648 §5, no padding
body  := fmt:u8(=1) nameLen:u8(0..64) name:utf8[nameLen] count:u8(1..64) pair{count}
pair  := key:uvarint(index into the v1 table, 1-2 bytes) value:uvarint(1-5 bytes, < 2^31, canonical)
```

Value mapping per key type (the v1 table, append-only, frozen indices; prototype order):

| type | wire value | keys |
|---|---|---|
| bool | 0/1 | enableVsync, improvedTransparency, entityShadows, cutoutLeaves, the 4 Sodium culling bools, iris.enableShaders |
| enum | index into the listed values | inactivityFpsLimit {minimized, afk}; particles/textureFiltering/prioritizeChunkUpdates {0,1,2}; renderClouds {false, fast, true}; Sodium defer {ALWAYS, ONE_FRAME, ZERO_FRAMES}; quad splitting {OFF, SAFE, UNLIMITED}; DH vertical/horizontal quality, max horizontal resolution; DH rendererMode {DEFAULT, DISABLED} (never DEBUG_TRIANGLE) |
| int (min..max) | value − min | renderDistance 2..32, simulationDistance 5..32, biomeBlendRadius 0..7, weatherRadius 3..10, iris.maxShadowRenderDistance 0..32, DH lodChunkRenderDistanceRadius (static 32..512, then DH's own API min/max) |
| int ×10 | value/10 − 1 | maxFps 10..260 |
| quarter | value×4 − 2 | entityDistanceScaling 0.5..5.0 |

Not shareable (machine-specific, stay in local profiles only): `sodium.performance.chunk_builder_threads`,
`dh.common.multiThreading.numberOfThreads`. Never in any profile: `vanilla.graphicsPreset`, `iris.shaderPack`, any other key.

### 5.3 Decoding rules (strict, in this order)

1. Raw input > 4096 chars → reject without further work. Remove ASCII whitespace (chat wrapping) only; then require
   `^RT[0-9]+-[A-Za-z0-9_-]{8,}$` and length ≤ 700 (the maximum a valid v1 code can have: 1+1+64+1+64×7+4 = 519 bytes → 692
   chars + prefix).
2. Prefix version ≠ 1 → "made by a newer RigTune" (or "not a RigTune profile code" if not `RT…-`).
3. Base64url decode (reject padding, invalid characters, a final quantum with non-zero spare bits); check CRC32 → "damaged or
   incomplete" (CRC is for copy/paste corruption, not authenticity; validation is the defence).
4. Parse exactly: `fmt == 1`; name decoded as strict UTF-8 (`CodingErrorAction.REPORT`); `count` pairs, then **no trailing
   bytes**; varints canonical (no overlong forms), ≤ 5 bytes, < 2^31.
5. Duplicate key index → reject the whole code.
6. Unknown key index (a newer table) → skipped and counted ("3 settings need a newer RigTune"); every future key must be a
   varint type, so skipping stays possible. Known key with an out-of-range value → reject the whole code.
7. Name: NFC; drop code points in categories Cc, Cf (bidi overrides U+202A-202E/2066-2069, zero-widths, U+FEFF), Co, Cs, Cn, Zl,
   Zp; collapse whitespace; ≤ 32 code points; empty → the lang key's "Imported profile". Rendered only via `Component.literal`;
   never used in a path (profile ids are generated `p-<uuid>`).
8. Build the switch list like any profile (only keys present in this instance, changeable, different; Sodium keys only with Sodium
   loaded and the key in the file; DH/Iris keys only through their patchers, which refuse missing keys), run `SettingsBridge.problems`
   (the option's own codec + range, e.g. RD 32 on a < 1 GB heap) and the stagers, then show **Preview** with Apply / Save only /
   Cancel. Nothing is written before a click; the clipboard is read only on the Paste button.

### 5.4 Threat model

| threat | why it can't happen |
|---|---|
| newline/control-char injection into options.txt, TOML, properties | no strings on the wire; values are rebuilt from our enum tables and our integer formatting; existing `safeValue`, TOML `unsafe`, Properties escaping stay as second lines |
| huge numbers, overflow | ≤ 5-byte canonical varint, < 2^31, then the per-key range; then the option's own range check |
| NaN / Infinity / exponent tricks | no floats on the wire (decimals are scaled integers) |
| duplicate keys | whole code rejected |
| path-like values, path traversal | no string values; the name is never a path |
| Unicode tricks in the name (RTL override, zero-width, homoglyph) | category filter + NFC + length cap; shown labelled as "name from the code"; homoglyphs remain possible but harmless (display only) |
| zip bomb / CPU exhaustion | no compression; input capped at 4096 raw / 700 stripped chars; O(n) parse |
| disabling RigTune, Fabric API, a library, a mod | v1 carries no mod ids and no file actions; the only on/off states are DH `rendererMode` DEFAULT/DISABLED and `iris.enableShaders`, which only change rendering and only if the key already exists |
| downloads | none: `SetSetting` never reaches Modrinth; works with the network off |
| hostile but valid values (RD 2, 10 FPS) | Preview shows every change; the switch is one journal entry, undoable |
| a newer format | `RT2-` refused with a message; unknown keys in `RT1-` skipped and counted |

---

## 6. Battery hook

Today [src]: `HardwareProbe.probeSlow` (`client/probe/HardwareProbe.java:145-199`) builds a new OSHI `SystemInfo`, reads CPU, memory,
**power sources** (`:180-188`: a source counts only if `realBattery` (`:202-205`) — OSHI's fake desktop "System Battery" has unknown
name/chemistry and capacity 1; `onBattery |= !isPowerOnLine() && isDischarging()`), and graphics cards. It runs once on
`Probes.EXECUTOR` (2 daemon threads "RigTune worker") and the future is cached in a static field that is never reset (`:37`,
`:56-61`), so **`onBattery` reflects the state at the first scan of the session**. Rules use it for `maxFps`/`enableVsync` and
`hasBattery` for `inactivityFpsLimit`.

Cost [run] (OSHI 6.9.0, JNA 5.17.0 as MC 26.2 ships them; this desktop; the fake System Battery reports `online=false,
discharging=false`): `new SystemInfo().getHardware()` 18 ms; first `getPowerSources()` 171 ms (JNA/class init, already paid by the
startup probe in game); later calls median 0.53 ms, max 1.37 ms; `PowerSource.updateAttributes()` 0.41 ms avg. On Windows OSHI uses
`PowrProf.CallNtPowerInformation(SystemBatteryState)` and SetupAPI device enumeration (javap of `WindowsPowerSource`). Laptop cost
UNVERIFIED (no laptop here).

Proposal:
- `client/probe/PowerWatcher`: started after the first probe **only if `hasBattery`**; one daemon `ScheduledExecutorService` thread
  ("RigTune power"), not `Probes.EXECUTOR` (keeps report builds unblocked); every 30 s calls `updateAttributes()` on the cached
  real-battery sources; a state change must hold for 2 consecutive polls (≥ 30 s) before it counts, so a wiggled plug doesn't
  flap. Stops at client stop. Updates the cached `SlowPart.onBattery` and triggers `rebuild()` so recommendations follow.
- `core/profile/BatteryPrompt` (pure, unit-tested): on a debounced AC → battery edge, prompt if prompts are on, no prompt in the
  last 10 min, no benchmark running (`BenchmarkController.running()`), the Battery profile isn't already active, and the player
  hasn't snoozed it. On battery → AC, offer the profile that was active before.
- UX: a `SystemToast` ("On battery power. Open RigTune (F8) to switch to Battery") and a banner in `RigTuneScreen`'s header with a
  "Switch to Battery" button; the switch is the normal Apply (mostly immediate vanilla keys: FPS cap, vsync, RD). A
  "Don't offer again" toggle. **Never auto-switch** in 0.4 (an opt-in auto-switch could come later). Prompt state lives in
  `profiles.json` (`battery: {prompt, profileId, previousProfileId, lastPromptAt}`), not `settings.json` (a 0.3.0 save would drop it).

---

## 7. Shader packs (P2 12)

Iris versions from `versions/*/gradle.properties`: 1.11.4+mc26.2 (Modrinth `gxZWWnKH`) and 1.11.6+mc26.3 (`bAdKrpw8`), decompiled
with Loom's Vineflower 1.12.0 from the Gradle cache; the relevant methods are identical in both [jar].

- `iris.properties` (`IrisConfig.load/save`): `shaderPack=<file or folder name in shaderpacks/>` (empty or `(internal)` → none),
  `enableShaders` (only "false" disables). `IrisConfig.initialize()` runs at startup **and on every `Iris.reload()`**, which
  re-reads the file. `IrisApiV0ConfigImpl.setShadersEnabledAndApply` first `save()`s the in-memory config (overwriting external
  edits) and then reloads.
- Per-pack options (`Iris.loadExternalShaderpack`): the options file is `shaderpacks/<shaderPack>.txt` (e.g.
  `ComplementaryReimagined_r5.9.3.zip.txt`), resolved with `getShaderpacksDirectory().resolve(name + ".txt")` (no containment
  check). It is read with `java.util.Properties` **at every pack load** (startup and reload: the R key, the shader screen's Apply,
  the API), merged with the GUI's queued options, and then **rewritten** with only the values that differ from the pack's defaults
  (`MutableOptionValues.addAll` drops defaults and unknown keys; `tryUpdateConfigPropertiesFile` deletes the file when nothing is
  left). So byte-identical restores are impossible; compare parsed values.
- String option values are **not checked against the pack's allowed values**: `OptionAnnotatedSource.edit` emits
  `"#define " + name + " " + value`. A value with an escaped newline in `<pack>.txt` would inject preprocessor lines into the
  shader source. Hence: pack options must never come from a share code, and a restore must validate keys and values.
- Iris logs `Profile: <name> (+N options changed by user)` after each load (`ShaderPack` constructor): a verification hook.

The two packs [jar / downloaded]:
- **Complementary Reimagined r5.9.3** (the user's instance, read-only copy): `shaders.properties` defines `profile.POTATO`,
  `VERYLOW`, `LOW`, `MEDIUM`, `HIGH`, `VERYHIGH`, `ULTRA`, `COMPLEMENTARY` as `KEY=VALUE` lists (`SHADOW_QUALITY=-1
  shadowDistance=64.0 …`). The user's `.txt` holds 2 non-default values (`HELD_LIGHTING_MODE=0`, `SHOW_LIGHT_LEVEL=2`) under a
  `#<date>` line, as Iris writes it.
- **BSL v10.1.8** (Modrinth, 2026-09-21, loaders iris/optifine, 26.2 and 26.3): `profile.MINIMUM`, `LOW`, `MEDIUM`, `HIGH`, `ULTRA`
  with inheritance (`profile.LOW=profile.MINIMUM SHADOW shadowMapResolution=1024 …`) and `!BOOL` negation.

Minimal safe design, if built: opt-in per pack; "Save pack settings to this profile" copies the parsed `<pack>.txt` into the profile
(keys `[A-Za-z_][A-Za-z0-9_]{0,63}`, values `[-+]?[A-Za-z0-9_.]{1,32}`, ≤ 512 entries, file ≤ 64 KiB); the pack name comes only from
the local `iris.properties`, validated as a bare file name whose resolved parent is `<gameDir>/shaderpacks`; the restore is written
post-exit by the helper (no race with Iris's own rewrite), after backing the current file up to `config/rigtune/shaderpacks/`;
journaled as one `setting` change `shaderpack.<sha256(pack)[:8]>` with before/after = the canonical option list (0.3.0 would show it
and skip its undo as "RigTune doesn't change this option itself": `GameState.changeable` finds no target for the prefix); Preview
lists the exact keys. Verification: production smoke per pack (seed a `<pack>.txt` equal to a pack profile, switch, restart, assert
the Iris log `Profile: LOW (+0 …)` and the parsed values; undo, restart, assert the original) on 26.2 and 26.3, locally (llvmpipe
CI can't run shader packs usefully).

Risks: widens the helper's write scope beyond `config/` (today `ApplyExecutor.containmentProblem` allows patches only inside the
config folder, `:448-466`) for a new op type (a downgraded 0.3.0 helper fails it safely, section 3); pack updates rename the zip and
orphan the saved settings; Iris's GUI queue or a concurrent Iris save could race an in-game write (hence post-exit only);
`setShadersEnabledAndApply` overwrites external `iris.properties` edits.

**Verdict: can be verified safely against two popular packs, but DEFER to a later release.** The value (a few pack options per
profile) is small next to the cost and the security-sensitive helper change, and P1 is large. If the coordinator wants it in 0.4,
build exactly the minimal design above after P1 lands, as its own workstream.

---

## 8. Files and ownership

New files only (no overlap):

- **WS-P1 core model + codec** (pure core): `core/profile/Profile` (record: id, name, templateId, source, createdAt, rigtuneVersion,
  mcVersion, settings), `ProfileStore` (profiles.json: formatVersion 1, unknown fields kept, newer → read-only, corrupt → `.bad`,
  ≤ 1 MiB, ≤ 50 profiles, switches ≤ 50, atomic writes), `ShareKeys` (the v1 table: key, type, bounds, shareable), `ShareCode`
  (encode/decode, bounds, CRC, name sanitising), `ProfileSwitch` (profile + snapshot → `List<Recommendation>`; titles through
  `SettingValues.describe`, reason `Text.of("rigtune.profile.reason", …)`). Tests: `ShareCodeTest`, `ShareCodeFuzzTest`,
  `ShareKeysTest`, `ProfileStoreTest`, `ProfileSwitchTest`.
- **WS-P2 templates + rules**: `core/profile/ProfileTemplates` + `Template` enum; `RulesDocument.profileTemplates` (+ its classes);
  `Recommender.settingTargets` extraction; `rules/source/knowledge.json`, `tools/update_rules.py` (+ Python tests),
  `docs/RULES_SCHEMA.md`, regenerated `rules/rules-v2.json` + bundled copy (and `rules-v1.json` unchanged). Tests:
  `ProfileTemplatesTest`, updates to `RepositoryRulesTest`/`SchemaConsistencyTest`/`RulesV1DifferentialTest` expectations.
- **WS-P3 apply/journal integration (client hotspots)**: `RealController` (split `apply` to take an entry id; `profiles()`,
  `switchProfile`, `previewProfile`, `saveCurrent`, `importCode`, `exportCode`, labels into `ProfileStore`); `RigTuneController`
  (default methods, `StubController` in gametest); `PendingActions.merge` same-key patch replacement (+ `StagingMergeTest`,
  `PendingActionsTest`, `PreviewDifferentialTest` if affected); `HistoryModel.build` takes an entry-id → label map and `Entry`
  gets it; `HistoryScreen` shows "Profile: …". `UndoPlanner`: **no change**.
- **WS-P4 UI**: `ui/ProfilesScreen` (templates + saved profiles: Switch, Preview, Save current, Rename, Delete, Copy code),
  `ui/ProfileImportScreen` (EditBox, Paste, errors) → `PreviewScreen` with a confirm button; `RigTuneScreen` one footer button
  "Profiles…"; **en_us.json owner** (other workstreams hand their keys to this owner, or add them in one agreed block);
  `LangCheckTest` dynamic family for the `Template` enum keys.
- **WS-P5 battery**: `client/probe/PowerWatcher`, `core/profile/BatteryPrompt` (+ test); `HardwareProbe` (keep the real
  `PowerSource`s; update `onBattery`), `RigTuneClient` (start/stop watcher, toast), the `RigTuneScreen` banner (coordinate with P4).
- **WS-P6 compatibility**: `src/test/java/.../v030/core/history/*` pinned from `git show v0.3.0:` (Journal, JournalEntry,
  JournalChange, HistoryModel, UndoPlanner, UndoPlan, JarInfo, ApplyFailures, ApplyResult as needed; the repo already pins 0.1.0 and
  0.2.0 code in `v010`/`v020`), `V030CompatTest`; the e2e downgrade run in `tools/e2e`.

Hotspots and the minimal change each needs:

| file | change |
|---|---|
| `RealController` | `apply(selected)` delegates to `apply(selected, entryId)`; ~6 profile methods; refuse during a benchmark |
| `RigTuneController` | default no-op profile methods |
| `RigTuneScreen` | one footer button (check the 640×480 GUI 2 footer layout in `UiGameTest`) + the battery banner line |
| `en_us.json` | ~50-70 `rigtune.profile.*` / `rigtune.battery.*` keys, one owner |
| `Recommender` | extract `settingTargets`; `recommend` output unchanged (scenario tests) |
| `UndoPlanner` | none |
| `PendingActions` | merge: same path + same single key `PATCH_*` replaces the older op |
| `HistoryModel`/`HistoryScreen` | optional label per entry |
| `PreviewScreen` | optional confirm button |

---

## 9. Test plan

Unit (core, per MC version):
- `ShareCode`: round trip of every table key at min/max/every enum value; the prototype's example codes as golden strings (lengths
  78/102/119); name with bidi/zero-width/control characters sanitised; 32-code-point cap; empty name.
- Negative/fuzz: every single-byte flip and truncation of a valid code fails the CRC or the parser; random bytes with a valid CRC
  never throw anything but the codec's own error; overlong varints; 6-byte varints; value ≥ 2^31; out-of-range per key;
  duplicate key; trailing bytes; `count` larger/smaller than the pairs; padding `=`; non-base64url characters; `RT2-`; > 700
  stripped / > 4096 raw chars (fast reject, timed); unknown key index skipped and counted.
- `ShareKeys` guard: the v1 table equals a pinned list (append-only: existing indices never change); every table key is in
  `SettingKeys.changeable`; `graphicsPreset`, `iris.shaderPack` and thread counts are not shareable.
- `ProfileSwitch`: only present, changeable, different keys; `graphicsPreset` never; Sodium keys skipped without Sodium.
- `ProfileTemplates` over a hardware × mods matrix: every value in the table's bounds; Battery maxFps 60 + vsync; Recording
  inactivity `minimized` and `$recordingFps` (60 at 144 Hz, 50 at 50 Hz, 60 unknown); heap ≤ 2100 MB caps RD at 8 in Quality and
  Max FPS; missing section → bundled fallback; `recommend()` output identical before/after the extraction.
- `ProfileStore`: missing/corrupt (`.bad`)/newer (read-only) file; unknown top-level and per-profile fields survive a save; caps;
  orphan switch labels pruned.
- `PendingActions.merge`: same-key patch replaced → `replaced`; journal change DISCARDED; different keys untouched.
- `BatteryPrompt`: edge + debounce, cooldown, snooze, benchmark running, already active, battery → AC offers the previous profile.
- `LangCheckTest`: new keys used, no literals in the new UI.

Game tests (client, both versions): switch to a template → options changed now, config ops in `pending.json`, one journal entry with
the profile label in History; Undo this → values back; a second switch before restart → the first switch's staged changes DISCARDED;
import a code → Preview lists the exact keys and writes nothing (hash `options.txt`, `config/`) until Apply; a malformed code shows
the error; network off → everything works (no HTTP, gate unit tests).

Compatibility:
- `V030CompatTest` (pinned 0.3.0 sources): 0.3.0 reads a `history.json` written by 0.4 after profile switches (state OK, "Apply",
  undoable, Undo this plans like the harness in section 3); 0.3.0's rewrite keeps entry ids so 0.4's labels come back; 0.3.0's
  `RulesLoader` parses the 0.4 bundled `rules-v2.json`.
- e2e downgrade (released 0.3.0 jar pinned by sha256, as the v0.3 harness does): 0.4 switch profiles + restart → 0.3.0: History
  shows the entries, Undo last works, `profiles.json` untouched → 0.4 again: labels and profiles intact.
- `RulesV1DifferentialTest`/`rules-v1-compat` unchanged: `rules-v1.json` must not change.

---

## 10. Open questions for the coordinator

1. "Optional-mod on/off": is DH on/off (`rendererMode`) + Iris shaders on/off enough, or are jar-level enable/disable toggles
   wanted? Jar toggles need a new `Action.EnableMod`, Preview and differential-test work, and I'd keep them out of share codes.
2. Should Battery also switch DH off (`rendererMode=DISABLED`), or only lower its LOD radius?
3. Shareable `maxFps`: keep the sender's number, or encode "match my display" (a reserved wire value) so a 144 Hz sender's cap
   doesn't land on a 60 Hz friend?
4. Should switching to one of the player's own profiles skip Preview (one click) and only imports force it? (Assumed yes.)
5. P2 12: accept the DEFER, or schedule the minimal design as a late 0.4 workstream?
