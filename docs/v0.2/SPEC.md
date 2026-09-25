# RigTune v0.2.0: specification

Status: DRAFT (Phase 2). Each item has acceptance criteria (AC). An item ships only when every AC is verified, and the evidence is recorded in docs/PROGRESS.md. Priorities: P0 must ship; P1 should ship (defer with a written reason); P2 stretch. Cut P2 before compromising P0/P1 quality.

Research inputs: docs/research/v0.2/{multi-version,api-diff,modrinth,dh-iris,benchmark,triage}.md.

Compatibility promise: a 0.1.x client must never get an unsafe recommendation from `rules-v1.json`, and every file 0.1.0 wrote (`pending.json`, `last-apply.json`, `rules-cache.json`, `rigtune.json`, `helper/`) must keep working after an upgrade to 0.2.0.

---

## 1. Multi-version support (P0)
Sources: docs/research/v0.2/multi-version.md (Stonecutter prototype that builds both), api-diff.md (javap diff).
- Versions: MC 26.2 and 26.3 (26.4 is snapshot-only on 2026-09-25). Loader 0.19.5; fabric-api 0.161.0+26.2 / 0.161.0+26.3; Mod Menu 20.0.2 / 21.0.0; Sodium mc26.2-0.9.2 / mc26.3-0.9.2 (dev runtime only).
- Tooling: Stonecutter 0.9.8 on the existing Groovy build, Loom 1.17-SNAPSHOT, Gradle 9.5.1. One shared `src/`; `versions/<mc>/gradle.properties` per version; `//? if >=26.3` comments at the few differing sites. **The committed ("VCS") active version is 26.2**; a CI check fails if sources are committed while switched. The Loom 1.18/Gradle 9.8 upgrade is out of scope.
- A single jar can't serve both: `InputConstants.KEY_F8` is inlined (297 vs 65) and `GpuDevice`/`DeviceInfo` moved to `com.mojang.renderpearl.api.device`. So one jar per version: `rigtune-<ver>+mc26.2.jar`, `rigtune-<ver>+mc26.3.jar` (+ sources), each with `depends.minecraft` `~26.2` / `~26.3`.
- Code sites (api-diff.md): RigTuneClient keybind (KEYSYM → KEYBOARD, F8), HardwareProbe (device import move, `Window.getRefreshRate()` removed → active video mode, float refresh rate, `Window.isFullscreen()` removed → a verified replacement such as `options.fullscreen()`), BenchmarkController refresh rate, game test Escape key.
- CI: one job builds and unit-tests every version and compiles the gametest sources; the release job collects exactly one jar + sources jar per version and fails if any is missing. Game tests in CI are P2 (item 11).
- Dev workflow documented (README "Build from source" + DESIGN "Porting"): `./gradlew build` builds all; `./gradlew :26.3:runClientGameTest`; how to switch the active version and switch back before committing; how to add 26.4 later (a new `versions/26.4`, the version list, fixing conditional sites).
- AC1.1 `./gradlew build` produces both jars; all unit tests pass on both versions. AC1.2 `:26.2:runClientGameTest` and `:26.3:runClientGameTest` pass, run serially; the coordinator reviews the 26.3 screenshots (F8 opens the screen, the report shows a real refresh rate). AC1.3 CI is green with both versions. AC1.4 The 26.3 production smoke run with a representative Modrinth mod set works (Phase 5).

## 2. Rules schema v2 with safe evolution (P0)

### Files
| file | who reads it | contents |
|---|---|---|
| `rules/rules-v2.json` (+ identical bundled `src/main/resources/rigtune/rules-v2.json`) | 0.2+ clients | full knowledge, v2 features |
| `rules/rules-v1.json` | 0.1.x clients (remote only) | the **v1 projection**: only what 0.1.x understands, never less safe |
| `rules/source/knowledge.json` | the updater | hand-written source; may use v2 features and per-rule `v1` overrides |

URLs: `https://raw.githubusercontent.com/chaotix345/rigtune/main/rules/rules-v2.json` and `.../rules-v1.json`.

### Client loading (0.2)
- Candidates: bundled v2; cache `config/rigtune/rules-v2-cache.json` (v2); the 0.1.0 cache `config/rigtune/rules-cache.json` (v1, read-only for 0.2; 0.2 never writes it); remote v2; if remote v2 fails (network error, non-200, too large, invalid), remote v1.
- Remote v1 is parsed by the v2 parser (v1 is a subset). A successful remote v1 is cached in `rules-v2-cache.json`? **No**: it's only used in memory, so the cache always holds a v2 document.
- `pickNewest`: highest `revision`; on a tie prefer schemaVersion 2, then the order remote > cache > bundled.
- `schemaVersion` must be 1 or 2; anything else is invalid (a future v3 lives in its own file).
- Network off (item 8) → no remote fetch at all.

### v2 document
Same as v1 (docs/RULES_SCHEMA.md) plus:
- `schemaVersion: 2`.
- `settingLabels` (optional, top level): `{ "<settings key>": { "name": "Chunk builder threads", "values": { "0": "Auto" } } }`. Used for recommendation titles and the share report. Missing label → the key's existing caption logic.
- `requires` (optional, on any ModRule, ObsoleteRule, SettingRule or AdviceRule): string[] of client features. A rule whose `requires` contains a feature this client doesn't know is **skipped**. 0.2.0 knows no features yet (the set is empty); this is the escape hatch for future rule-level fields that must not fail open.
- New condition fields (all optional, ANDed like the rest):

| field | type | meaning | unknown/invalid |
|---|---|---|---|
| `gpuModelMatches` | string | Java regex *found* in the GPU subject string (the renderer, or the vendor string when the renderer is blank; the same string `gpuTiers` match). ≤200 chars, budgeted like `gpuTiers`. | invalid regex, budget exhausted, or no GPU info → false |
| `displayPixelsAtLeast` / `displayPixelsAtMost` | long | width × height of the display (`DisplayInfo`) | width or height ≤ 0 → false |
| `modVersion` | object: modId → Fabric version predicate (e.g. `">=0.6.0 <0.8.0"`, `"~0.9"`) | every listed mod is loaded and its version satisfies the predicate (Fabric Loader's `VersionPredicate`/`Version` semantics, as in fabric.mod.json) | mod not loaded, unparseable predicate or version → false |
| `mcVersionRange` | string: Fabric version predicate | the running MC version satisfies it | unparseable → false |

- **Fail closed**: while parsing, every condition object records keys it doesn't know. If any node of a condition tree (including inside `not` and `anyOf`) has an unknown key, the whole top-level condition evaluates to **false**. So a rule gated by an unknown field never fires: `recommendWhen` → not recommended, `avoidWhen` → nothing is disabled, setting `when` → the entry doesn't match, advice `when` → not shown. (Evaluating the unknown node as false *inside* `not` would flip it to true; that's why the whole tree is poisoned.)
- `mcVersion` (exact list) stays for v1 compatibility.

### v1 projection (updater)
- The updater writes both files from one run with the **same `revision`** (bumped when either output changes).
- For each rule in knowledge.json:
  1. If it has `"v1": false`, omit it from v1.
  2. If it has `"v1": { ... }`, shallow-merge those fields over the rule for the v1 output only (e.g. a conservative v1 `recommendWhen`).
  3. If the (merged) rule still uses anything v1 doesn't know — a condition key outside the v1 set (anywhere in the tree), `requires`, or a settings key outside `vanilla.`/`sodium.` — omit it from v1.
  4. `v1` is never written to either output.
- Top-level `settingLabels` is omitted from v1.
- REVIEW.md gets a section "(d) Omitted from rules-v1.json" listing every omitted rule and why, so a maintainer sees when an omitted setting entry changes which entry wins for 0.1.x.
- Safety argument: 0.1.x evaluates only rules whose every field it understands, with unchanged semantics. Omitting a mod/advice/avoid rule only removes a recommendation. Omitting a setting entry can change which earlier entry wins, so a setting rule that *restricts* (a lower value, a clamp) for a v2-only condition must carry a `v1` override or be reviewed in section (d).

### CI and tests
- `rules-consistency` job: `rules/rules-v2.json` == bundled copy; the bundled v1 copy is gone (0.2 doesn't bundle v1).
- New `rules-v1-compat` job: `python tools/check_rules_v1.py` asserts rules-v1.json has schemaVersion 1, contains only v1 condition keys (recursive), only `vanilla.`/`sodium.` settings keys, no `requires`/`settingLabels`/`v1`, and equals the projection of rules-v2.json + knowledge (regenerated offline).
- Java: unknown-field fail-closed tests (top level, inside `not`, inside `anyOf`, on avoidWhen and settings), each new condition (including invalid inputs), `requires` skipping, labels, v1-remote fallback, cache migration, pickNewest tie-breaks.
- A Java test loads `rules/rules-v1.json` and `rules/rules-v2.json` from the repo and checks both parse and every setting key is allow-listed.
- Python: projection tests (override, omit, recursive detection, labels stripped, same revision, REVIEW (d)).

### AC
- AC2.1 `rules-v2.json` exists in the repo and the jar; a 0.2 client uses it; remote v2 404 → remote v1 is used; both fail → cache/bundled.
- AC2.2 An unknown condition field anywhere makes the rule not fire (tests above).
- AC2.3 Nvidium is gated in v2 by `gpuModelMatches` to mesh-shader-capable NVIDIA GPUs (Turing and newer; exact regex from triage.md); RenderScale is suggested for a mid-tier GPU at ≥1440p via `displayPixelsAtLeast`.
- AC2.4 `rules-v1.json` passes `rules-v1-compat`, and the 0.1.0 parser (schemaVersion 1) accepts it.
- AC2.5 RULES_SCHEMA.md documents v2, the projection and the fail-closed rule; tools/README.md documents `v1` overrides and REVIEW (d).

## 3. "Undo RigTune" rollback (P0)

### Journal
`config/rigtune/history.json` (new; `formatVersion: 1`). Every read-modify-write holds `config/rigtune/apply.lock` (the helper and a relaunched game can overlap). Written temp-then-atomic-move (AtomicFiles). Bounded to the last 50 entries.

```jsonc
{
  "formatVersion": 1,
  "entries": [{
    "id": "uuid",
    "at": "2026-09-25T10:00:00Z",
    "kind": "apply",            // apply | benchmark | undo | legacy-import
    "rigtuneVersion": "0.2.0+mc26.2",
    "mcVersion": "26.2",
    "undoOf": null,             // for kind=undo: an entry id, or "all"
    "changes": [
      { "id": "uuid", "type": "setting", "key": "vanilla.renderDistance", "before": "12", "after": "16",
        "status": "APPLIED", "opId": null, "reverts": null },
      { "id": "uuid", "type": "setting", "key": "sodium.performance.chunk_builder_threads", "before": "0", "after": "4",
        "status": "STAGED", "opId": "<pending op id>", "reverts": null },
      { "id": "uuid", "type": "file", "action": "enable", "modId": "lithium", "file": "lithium-0.25.4.jar",
        "status": "STAGED", "opId": "<op id>", "group": "<op group>", "reverts": null },
      { "id": "uuid", "type": "file", "action": "disable", "modId": "indium", "file": "indium-1.0.jar",
        "status": "APPLIED", "opId": "<op id>", "group": null, "reverts": null }
    ]
  }]
}
```
- `file` is a bare file name in the instance's mods folder (never a path; the folder is derived as today). `enable` means `<file>` became active (from a `.rigtune-pending` download); `disable` means `<file>` became `<file>.disabled`.
- Change status: `STAGED` (in pending.json) → `APPLIED` | `ABANDONED` | `DISCARDED`; `APPLIED` → `REVERTED` once an undo change that `reverts` it is APPLIED. `FAILED` ops stay `STAGED` (they retry) until the executor abandons them.

### Recording
- Apply (RealController): vanilla settings → `APPLIED` with `before` read from Options immediately before the write (not the report's value) and `after` as written; failed writes aren't recorded. Sodium (and any other config-file namespace from item 7) → `STAGED` with the op id, `before` from the file at staging. File ops (disable, update, add, dependencies) → `STAGED` with op id and group, recorded when they are staged (after downloads).
- Benchmark "Keep" → an entry of kind `benchmark` with the settings it kept.
- The helper (`ApplyExecutor.run`, under the lock) updates entries by op id: OK / SKIPPED_ALREADY_DONE → APPLIED; ABANDONED → ABANDONED; FAILED → unchanged.
- Discard pending → every STAGED change whose op was dropped → DISCARDED.
- RigTune's own jar (mod id `rigtune`) is recorded but never offered for undo (no self-downgrade).

### Undo
Two actions in the RigTune screen: **Undo last apply** (the newest non-undo entry with any APPLIED or STAGED change) and **Undo everything** (all non-undo entries). Both open a confirmation screen listing exactly what will be reverted and what will be skipped (with the reason), then:
- STAGED changes: removed from pending.json by op id, under the lock. Removing any op removes its whole group (a staged dependency can't be dropped while a dependent stays); other entries' changes in that group become DISCARDED too, and the confirmation screen says so. Removed downloads are retired to `.rigtune-superseded`.
- APPLIED changes (processed newest entry first; `UndoPlanner` simulates the mods folder across the sequence so each step's preconditions are checked against the state the earlier steps leave):
  - vanilla setting: if the current value == the latest `after`, set it back to the earliest `before` (immediately); otherwise skip ("you changed it since").
  - config-file setting (Sodium etc.): the same check against the file; staged as PATCH_JSON (post-exit).
  - file `enable` (added or updated-to jar): stage DISABLE_FILE of `<file>` if it exists; else skip.
  - file `disable`: stage ENABLE_FILE `<file>.disabled` → `<file>` with its modId, if the `.disabled` file exists and `<file>` doesn't; else skip.
  - The reversal ops of one original group form one new group (an update's undo = {disable new, enable old}), so the executor's duplicate-mod-id check and all-or-nothing rollback apply.
- The undo is itself journaled (`kind: undo`, each change has `reverts`), and it can't be undone (no redo in 0.2).
- Vanilla changes take effect now; staged ones after a restart, as with Apply.

### 0.1.0 migration
- 0.1.0 files are read unchanged: `pending.json` (with or without id/group/modId/attempts), `last-apply.json`, `rules-cache.json`, `rigtune.json` (goal, lastShownApply), `helper/`.
- Legacy import: on the first 0.2 run with no history.json, if last-apply.json exists, add one `legacy-import` entry with the OK/SKIPPED file ops of that run as APPLIED file changes (excluding RigTune's own jars). PATCH_JSON results are skipped (their before-values were never recorded). The import runs once (history.json then exists).
- New ClientState fields have defaults when absent (Gson + field initializers; tested).

### AC
- AC3.1 Every change made by Apply, the benchmark and the helper is in history.json with before/after and a correct status (unit tests for recording and helper updates).
- AC3.2 Undo last apply and Undo everything revert settings immediately / after restart and mods after restart, via the post-exit pipeline, skipping user-modified items with a reason (unit tests incl. the update chain a→b→c undone to a, staged-group removal, missing files, a user-changed setting).
- AC3.3 Migration tests: real 0.1.0 `pending.json`, `last-apply.json`, `rigtune.json` fixtures (captured from the v0.1.0 jar in the item 5 run, plus hand-written pre-group ops) are read by 0.2: preLaunch reports the unseen result and leftover ops, goal and lastShownApply are kept, the 0.2 helper executes a 0.1.0 plan, legacy import produces the expected entry.
- AC3.4 A client game test (per MC version) applies a vanilla + Sodium change, undoes it, and checks the values and pending.json.

## 4. Modrinth publishing (P0)
Source: docs/research/v0.2/modrinth.md.
- Tool: Minotaur `com.modrinth.minotaur` 2.10.0 (`uploadFile = jar`; there is no remapJar on 26.x). One Modrinth version per MC version: `version_number` `0.2.0+mc26.2` / `0.2.0+mc26.3`, `game_versions` `["26.2"]` / `["26.3"]`, `loaders` `["fabric"]`, `version_type` release, required dependency `fabric-api`, changelog from CHANGELOG.md's section for the tag.
- release.yml: GitHub release first (one jar per MC version plus sources jars), then one Modrinth publish per MC version as independent steps/jobs, so a retry after a partial failure still publishes the missing one (Minotaur fails, rather than duplicating, when a version number exists). Skipped with a notice when the `MODRINTH_TOKEN` secret is absent.
- Project: created once through the API with the user's setup token (PROJECT_CREATE/READ/WRITE, VERSION_*): slug `rigtune`, title RigTune, categories `optimization` + additional `utility`, client_side required, server_side unsupported, license MIT, source/issues links, icon (≤256 KiB), gallery from the in-game screenshots (with titles/descriptions as alt text), body adapted from the README including the disclosure paragraph (downloads only on explicit click, from Modrinth's CDN, SHA-512 verified; self-update the same way; static rules JSON from GitHub; no telemetry). Then submitted for review (API if it works, otherwise one click by the user).
- CI token: a separate "Create versions"-only token as the `MODRINTH_TOKEN` repo secret.
- **Self-update reach (finding):** RigTune finds its own update via `POST /v2/version_files/update` with its jar's hash. A 0.1.0 installed from GitHub is only matched if Modrinth hosts that exact file, so uploading the v0.1.0 jar as an older Modrinth version is what lets existing 0.1.0 users get the update offer. That is a public action beyond "publish v0.2.0": ask the user first. Also, that endpoint reportedly returns nothing for projects still in review (modrinth/code#7434), so real-world update offers start after approval.
- AC4.1 The release run publishes both jars to GitHub and (with the token) both Modrinth versions; `GET /v2/project/rigtune/version` lists them with the right game_versions and loaders. AC4.2 The listing has the description, icon, gallery, license and links, and is submitted for review. AC4.3 The state (live / pending review) is reported honestly.

## 5. Self-update verified end-to-end (P0)
- A scripted test (tools or a Gradle task; not part of `build`) that:
  1. makes a fresh instance COPY in the scratchpad (never the user's instance) with fabric-api for 26.2 and the **released** `rigtune-0.1.0.jar` (from the GitHub release, checked against its published SHA-256 `8294d04a6b67e76dcff298366be38f85048ebf19a120baa9e8ed5b08b2e4b950`);
  2. starts a local fake Modrinth server that answers `/v2/version_files`, `/v2/version_files/update`, `/v2/projects`, `/v2/project/{id}/version`, and serves the built `rigtune-0.2.0+mc26.2.jar` with its real SHA-512/SHA-1;
  3. points v0.1.0 at it. v0.1.0 has no base-URL setting, so use JVM properties only: `-Djdk.net.hosts.file=<file mapping api.modrinth.com and cdn.modrinth.com to 127.0.0.1>` and a truststore with the fake server's self-signed certificate (`-Djavax.net.ssl.trustStore`). 0.2.0 adds `-Drigtune.modrinth.baseUrl` (and `-Drigtune.rules.baseUrl`) for future tests;
  4. drives v0.1.0 in a production client (like runProductionSmoke): the report offers "Update RigTune 0.1.0 → 0.2.0+mc26.2"; apply it; quit; the post-exit helper runs;
  5. asserts: exactly one `rigtune*.jar` in mods (the 0.2.0 one), `rigtune-0.1.0.jar.disabled` exists, no pending.json, last-apply.json all OK;
  6. launches 0.2.0 on the same copy and asserts it reads the 0.1.0 state (goal preserved, the apply toast, history legacy import excludes RigTune's own jar), and captures the 0.1.0-written files as test fixtures for AC3.3.
- AC5.1 The run passes, with a log and screenshots under docs/smoke/self-update/.
- AC5.2 Update ordering is correct for a jar whose file name changes (`rigtune-0.1.0.jar` → `rigtune-0.2.0+mc26.2.jar`) and the helper ran from `config/rigtune/helper/` copies.

## 6. Benchmark v2 (P1)
Source: docs/research/v0.2/benchmark.md (protocol, stats, algorithm, schema, chart), dh-iris.md §3/§5 (DH/Iris knobs).

### Scenes
- `current` (default, as in 0.1): the player's current world and position.
- `benchmark-world`: a dedicated singleplayer save `rigtune-benchmark` (NORMAL preset, fixed seed, fixed position and camera path, time frozen at noon, weather clear, mob spawning off), created with the production API `Minecraft.createWorldOpenFlows()` → `WorldOpenFlows.createFreshLevel(...)` (the Fabric client-gametest world builder is test-only and not on the mod's classpath). Reused across runs; recreated if missing or if its recorded MC version differs. After the run, the player returns to the title screen. Available from the title-screen RigTune screen (no world loaded). The seed and coordinates must be checked in a real run (a screenshot showing varied terrain) before shipping; a flat world is the fallback if NORMAL proves nondeterministic.
- RISK: nothing in the repo has used WorldOpenFlows yet. Spike it first in a game test; if it can't be made reliable, ship `current` only and defer the dedicated world (documented).

### Measurement (per candidate)
Settle (sections compiled, ≤20 s) → 1.5 s discarded warm-up → 8 s per camera phase × 2 phases (level, then 25° down). The final chosen configuration is measured twice more, and the coefficient of variation of the two 1% lows is reported; CV > 5% shows "results were noisy (close background apps and retry)".

### Knobs (coordinate descent under a hard deadline, total ≤ ~4 min)
1. Render distance: RenderDistancePlanner, unchanged semantics.
2. Simulation distance: 3-point local search (current, −2, −4, ≥5) in singleplayer only; on a remote server it isn't a client-FPS knob and is skipped.
3. Distant Horizons LOD distance (if DH is loaded and its API is ready): 3-point local search via `DhApi.Delayed.configs.graphics().chunkRenderDistance().setValue(v, "RigTune")`, restored with `setValue(original)`.
4. Shaders on/off (if Iris is loaded and a pack is in use): one extra measurement with shaders off via `IrisApi.getInstance().getConfig().setShadersEnabledAndApply(false)` to report shader cost; always restored. This is a report, never auto-applied.
- Crash safety: before touching DH or Iris, write `config/rigtune/benchmark-restore.json` with the original values. It's deleted after a successful restore; if it exists at the next client start, the values are restored once DH/Iris are ready. Vanilla test values stay in memory only, as in 0.1.
- DH and Iris calls run on the render thread; every compat class is only loaded when `FabricLoader.isModLoaded` says so (compileOnly APIs).

### Before/after
- A second mode, **Measure** (no tuning): measures the current settings at the fixed scene (2 repeats).
- Flow in the RigTune screen: "Measure before" (saved with a pairId) → the player applies recommendations (restart if needed) → "Measure after" (same scene kind and MC version; for `current`, a warning that the location must match) → the result screen shows "1% lows: +X% (avg +Y%)", or "no significant change" when |gain| < 2 × max(CV before, CV after).

### History and chart
- `config/rigtune/benchmarks.json` (schemaVersion 1, last 50 runs; unknown newer schema → start fresh, don't overwrite until a run succeeds; written temp-then-move). Schema per benchmark.md §7 plus the RigTune version, mode (tune/measure) and scene.
- The result screen draws a small bar chart of the last ≤10 runs (1% low and avg per run, same scene kind) with `GuiGraphicsExtractor.fill`, labelled with dates.

### AC
- AC6.1 Unit tests: planner and coordinate descent (fake measurer, deadline honoured, knob order, restore), CV/noise-floor maths, gain formatting, history (cap, schema, corrupt file, newer schema).
- AC6.2 Game test per MC version: a Measure run and a Tune run complete in the harness (numbers aren't asserted because of harness tick sync; restore is asserted), a before/after pair is stored, the result screen with chart is screenshotted, benchmarks.json is valid.
- AC6.3 benchmark-world: created, entered, measured, exited to title, reused on the second run (game test), plus one screenshot checked by the coordinator.
- AC6.4 DH knob: in the production smoke run with DH loaded, the LOD value after the run equals the original, and benchmark-restore.json is gone.

## 7. Distant Horizons and Iris awareness (P1)
Source: docs/research/v0.2/dh-iris.md.
- New settings namespaces (v2 rules only; the projection keeps them out of v1):
  - `dh.<dotted TOML path>` read from `config/DistantHorizons.toml` by a minimal reader (sections + `key = value`; records whether each value token was quoted);
  - `iris.<key>` read from `config/iris.properties` (java.util.Properties).
- Applying: staged post-exit like Sodium, as new op types `PATCH_TOML` (replaces only the value token of an existing key in its section, preserving the original quoting; refuses a missing key, never adds or reorders) and `PATCH_PROPERTIES` (load, set, store, ISO-8859-1). Both are validated at staging against the current file (like SodiumConfigPatcher.stage), one op per key, and recorded in the journal (item 3). The paths must be inside the config dir (InstanceDirs checks).
- A 0.1.x helper never sees these ops (a 0.2 plan is only run by a 0.2 helper, except after a downgrade, where the unknown type fails and is abandoned after 3 runs; documented).
- Knowledge (rules/source/knowledge.json), from dh-iris.md §1.2/§4/§6/§7, conservative and cited in the reasons:
  - DH LOD distance, vertical/horizontal quality, max horizontal resolution and thread count per tier (`modPresent: [distanthorizons]`);
  - a stricter vanilla RD clamp with DH at tier ≤ 2 (max 8), next to the existing max 12;
  - `iris.maxShadowRenderDistance` per tier when shaders are on;
  - advice: RAM with DH and/or shaders (heap bands); shaders at tier ≤ 2 (suggest an ultra-fast pack or no shaders); shader packs with DH support, listed only where verified.
- AC7.1 Unit tests for the TOML reader/patcher (quoted floats stay quoted, bare ints stay bare, section scoping, missing key refused, CRLF, comments) and the properties patcher. AC7.2 Recommender scenario tests for DH/Iris profiles. AC7.3 The production smoke run with the user's DH (a copy) shows the DH recommendations, and a staged DH patch applied by the helper leaves a TOML that DH loads (checked in the next launch's log: no DH config error, and the value is in effect).

## 8. Settings screen (P1)
- Stored in `config/rigtune/settings.json` (`ClientSettings`, its own file, so a 0.1.x downgrade that rewrites rigtune.json can't drop them) with defaults:
  - `networkEnabled` (true): master switch. Off → no request of any kind: no remote rules, no Modrinth lookups, no downloads. Add/Update recommendations become advice ("install it from your launcher") and the header says "Offline (network off in settings)".
  - `remoteRules` (true), `modrinth` (true: lookups, update checks AND downloads): finer switches under the master.
  - `startupToast` (true): the title-screen "RigTune: N suggestions" toast. Apply results and warnings are still shown.
  - `goal` (existing): the default goal.
  - `benchmarkScene`: `"CURRENT"` (default) or `"BENCHMARK_WORLD"` (the BenchmarkRequest.Scene names, used everywhere).
- A `RigTuneSettingsScreen` opened from a Settings button on the RigTune screen and from Mod Menu's config button (Mod Menu opens the settings screen; the settings screen has a button to the main screen).
- Changes save immediately and trigger a rescan where relevant.
- Privacy-first: the README Privacy section lists exactly what each switch controls and what is sent (GitHub raw for rules; Modrinth: SHA-1 hashes of installed jars, candidate project ids, downloads of files the player chose). No telemetry. The first launch of 0.2 shows a one-time toast pointing at the network settings.
- AC8.1 Each switch demonstrably gates its requests (unit tests with a fake HTTP layer / fetcher; a game test toggles network off and checks the header).
- AC8.2 A missing settings.json gives the defaults; 0.1.0's rigtune.json still loads (goal, lastShownApply).

## 9. Knowledge triage (P1)
Source: docs/research/v0.2/triage.md.
- Apply the triage: 32 reviewIgnore entries (as written in triage.md §2) and LambDynamicLights (mod id `lambdynlights`) as a tracked, never-recommended rule whose avoidWhen (tier ≤ 2) suggests disabling it, impact low and **unticked by default**.
- Nvidium: v2 `recommendWhen` uses `gpuModelMatches` (triage.md §5 regex: GTX 1650/1660, RTX 20/30/40/50, Quadro RTX, RTX A-series, RTX Ada) instead of NVIDIA tier ≥ 4, which wrongly excluded Turing cards. v1 keeps its old condition via a `v1` override (tier-gated; Nvidium disables itself on unsupported GPUs, so the v1 rule is not unsafe, just narrower).
- Ixeris: keep default-ticked (SDL3 port works on 26.3; its buffered-raw-input gain isn't ported yet, so the reason text is updated to say so for 26.3 via `mcVersionRange`).
- Section (c): no rule changes (availability is regenerated live; debugify now has a 26.3 build).
- AC9.1 REVIEW.md (a) is empty after regenerating; every ignore reason is plain and specific. AC9.2 Scenario tests: Nvidium on RTX 2060 / GTX 1660 (yes), GTX 1080 / AMD (no); LambDynamicLights on tier 2 (disable suggestion, unticked). AC9.3 `./gradlew test` rerun after regenerating rules.

## 10. Share report (P1)
- A "Copy report" button on the RigTune screen puts a Markdown summary on the clipboard: RigTune/MC/loader versions, hardware (CPU, GPU + driver + backend + VRAM, RAM, heap, display resolution and refresh), tier and limiting factor, goal, rules revision and source, the recommendations (grouped by category, impact, and whether ticked), and the latest benchmark (target, result, avg / 1% low). No file paths, user names or world names.
- Formatting is pure core code (`core/report/ShareReport`), unit-tested, ≤ 2000 characters by default (Discord's message limit) with a "(N more)" truncation.
- AC10.1 Unit tests for the formatter (truncation, no paths). AC10.2 A game test presses the button and reads the clipboard.

## 11. Client game tests in CI (P2)
Linux + xvfb per supported MC version, using the production run task where it helps. Stretch.

## 12. Launcher-aware RAM advice (P2)
Detect the Modrinth App, Prism, or the official launcher from the game dir/launcher properties and give exact click-steps in the RAM advice. Stretch.

## 13. Localisation scaffolding (P2)
All UI strings are already translatable (`assets/rigtune/lang/en_us.json`). Scaffolding = no hard-coded UI strings in new screens, a translators' note in the README. Stretch.

---

## Amendments from the plan review (docs/v0.2/plan-review.md; these override the text above where they differ)
Contract changes already committed on feat/v0.2.0:
- ChangeRecorder.record(entryId, kind, changes) and newEntryId()
- JournalChange.resultFile
- UndoPlan.Item.action (REVERT / DISCARD_STAGED / SKIP)
- RigTuneController.undo(UndoPlan)
- ClientSettings (settings.json)
- ConfigTargets (generic config-namespace routing in apply(); readValues on the patchers)
- AtomicFiles.writeString is public

Item 2 (rules):
- H1: three-valued condition evaluation: TRUE / FALSE / UNKNOWN.
  - UNKNOWN comes from:
    - an unknown key
    - an invalid or undecidable input: no GPU info, a bad regex, an exhausted budget, an unknown display size, an unparseable version or predicate, or unknown RAM/VRAM/refresh
    - a value outside a field's known vocabulary (`flags`, `gpuVendor`, `backend`, `os`, `goal`)
  - Combining: `not UNKNOWN` = UNKNOWN. `anyOf` is TRUE if any branch is TRUE, else UNKNOWN if any is UNKNOWN. AND is FALSE if any part is FALSE, else UNKNOWN if any is UNKNOWN.
  - A top-level UNKNOWN is treated as false for every rule kind: no recommendation, no disable, no setting, no advice.
  - The known vocabularies go in RULES_SCHEMA.md; check_rules_v1.py rejects values outside the v0.1.0 vocabularies.
- H2: project per field, not per rule.
  - A v2-only `recommendWhen` or advice `when` becomes `{"always": false}`. A v2-only `avoidWhen` is dropped. `conflictsWith`, `modIds` and the other v1-safe fields stay.
  - A `null` anywhere in a `v1` override is an updater error.
  - Each rule type has a whitelist of v1 fields. Any other field needs an explicit `v1` override or `"v1": false`, otherwise the updater errors.
  - A setting entry that uses a v2 feature needs an explicit `v1` (error otherwise).
  - Differential test (WS-A builds it; WS-H runs it after content changes): a pinned copy of the v0.1.0 Recommender/ConditionEvaluator (from tag v0.1.0, under src/test, in a renamed package) evaluates the previous and the new rules-v1.json over a hardware × mods × goal matrix. It fails on any new ticked recommendation that isn't more conservative.
- H3: new ModRule field `avoidSelected` (default true, v2-only), honoured by `Recommender.avoided()`. LambDynamicLights uses `avoidSelected: false` and `"v1": false`.
- M1/L1: RULES_SCHEMA.md rules for maintainers:
  - Never add a v2-only or future field to an existing restrictive rule; it would lift the restriction for clients that don't know the field. Add a new rule instead.
  - Tier-rule (`gpuTiers`/`cpuTiers`/`heapTiers`) schema changes need a new schemaVersion.
- M14:
  - `EvalContext` gains installed mod versions (for `modVersion`).
  - WS-A exposes a re-runnable `reloadRules()` in RealController for the settings screen.

Item 3 (undo):
- H4: one entry per Apply. RealController creates the entry id in apply() and passes it to the downloads; the benchmark "Keep" and undo each use their own id.
- H5: record inside stage(), under the lock, after the merge.
  - `PendingActions.Merged` gains `Map<incomingId, survivingId>` and the replaced op ids (→ DISCARDED).
  - `discard` returns the dropped ids.
  - A config key's `before` is the value after any already-staged ops for that key.
  - Startup reconciliation (preLaunch, under the lock): a STAGED change whose op is neither in pending.json nor in last-apply.json becomes ABANDONED ("lost").
- M4: ApplyLock becomes reentrant within one JVM (a hold count), so the journal can take it while preLaunch or stage() holds it.
- M5: the helper's classpath is our jar + Gson only.
  - Journal code reachable from ApplyExecutor/ApplyHelper must not touch RigTune.LOGGER, Fabric or MC classes.
  - Journal updates in the helper are best-effort (catch Throwable, log to helper.log) and can never fail an apply.
  - A test runs the helper with only our classes + Gson on the classpath.
- M6: the executor reports the actual disabled name. OpResult gains `resultPath` (an additive field, so 0.1.0 readers ignore it); the helper stores it in JournalChange.resultFile, and undo re-enables that file.
- M7: graphicsPreset changes a dozen options, so vanilla recording diffs the whole vanilla snapshot before/after the write and records every changed key.
- M8: undo(plan) executes the plan that was shown, re-checking each item; an item whose state changed becomes a skip with a reason.
- M9: before staging reversals, UndoPlanner checks the simulated result. After it:
  - no two active jars may share a mod id;
  - no active jar may lose a `depends` mod id (excluding minecraft, java, fabricloader and anything a loaded jar provides).
  Items that would break this become skips.
- L2/L3: details in plan-review.md (legacy import; never discard a staged self-update of RigTune).

Item 4 (Modrinth):
- M15: the user explicitly approved (2026-09-25) three things: creating the project through the API, uploading the identical v0.1.0 jar, and submitting for review.
  - The v0.1.0 upload must come before any 0.2.0 upload, because update ordering uses date_published.
  - L7: until 0.2.0 is uploaded, the listing describes only 0.1.0's behaviour.
- M11: the release uploads byte-identical jars to GitHub and Modrinth. They are built once, in one job, and the workflow compares SHA-256s before publishing.

Item 5 (self-update E2E):
- M10: don't use Loom's production run task as is. It adds the dev jar via -Dfabric.addMods, which would load two copies of RigTune. Launch the client so that only the instance's mods/ is used.
- M12: also run the harness for 0.2.0 → a newer 0.2.0 build, to prove the 0.2 helper can apply its own successor.
- L5: 0.2 only downloads from `https://cdn.modrinth.com/`. The base-URL test property widens that only when it's set.
- M14: Phase 5 adds an end-to-end undo after a restart: 0.2.0 applies a mod change → quit → helper → Undo last → quit → helper → assert.

Item 6 (benchmark):
- M16: the tuned knobs are render distance and simulation distance only.
  - Simulation distance uses the research's cheaper secondary protocol: settle ≤2 s, then one 6 s phase per point.
  - DH and shaders become cost reports: one measurement with DH rendering off (`renderingEnabled`, API-only, not persisted) and one with shaders off, each compared with the tuned result and always restored.
  - Hard deadline 5 min. AC6.1 checks that RD + SD finish within it on the fake measurer.
- M-risk: with DH loaded, the harness deadlocks on world exit. So AC6.4 and AC7.3 are checked from the title screen (stage there and quit there), or with a non-harness production launch.
- Cut order if time runs short: benchmark-world, then the shader cost report, then the DH cost report, then the chart (keep the gain line).
