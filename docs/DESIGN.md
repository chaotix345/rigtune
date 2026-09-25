# RigTune: design

A client-side Fabric mod for Minecraft Java 26.2 and 26.3. It works out which mods and settings will get the most out of the player's hardware, and it keeps that advice current without needing a new mod release.

## Goals
1. **Detect the hardware**: CPU, RAM, JVM heap, GPU (vendor, model, VRAM, driver, graphics backend), display (resolution, refresh rate), and battery/laptop state.
2. **Scan installed mods**: use the Fabric mod ids (offline) plus jar hashes checked against the Modrinth API (for updates).
3. **Recommend** changes, each with a reason, an impact level, and a checkbox:
   - add missing performance mods that suit this hardware (e.g. Nvidium only on qualifying NVIDIA GPUs)
   - update outdated mods
   - remove obsolete or conflicting mods (Indium, Starlight, OptiFabric, both C2ME and Moonrise, etc.)
   - Sodium, Distant Horizons and Iris settings and vanilla video settings, by hardware tier and the player's goal (performance / balanced / quality)
   - advice outside the game: RAM allocation (which the launcher controls) and GPU driver workarounds that Sodium applied
4. **Benchmark**: an in-game test that measures real frame times and tunes render distance (and, in singleplayer, simulation distance) to a target FPS (the recommended FPS cap: the multiple of 10 below the refresh rate, e.g. 180 Hz → 170, at most 240; measured uncapped on 1% lows). A dedicated benchmark world gives repeatable results without the player's own save, and a "measure before/after" mode reports the real gain from any change. Specs only give the starting point; the measurement decides.
5. **Apply**: vanilla settings take effect immediately. Mod file changes and config changes (Sodium, Distant Horizons, Iris) are staged, then applied safely after the game exits (see "Apply pipeline"), and every change is journaled so it can be undone.
6. **Undo**: any RigTune change — a setting, a mod install/update/disable, a config patch — can be reverted individually ("Undo last apply") or all at once ("Undo everything"), immediately or through the same post-exit helper as Apply.
7. **Stay current**:
   - **Live**: the Modrinth API reports mod availability and updates for the running MC version at runtime.
   - **Rules**: a JSON rules file hosted on GitHub is fetched at startup, with a cached copy and a bundled copy as fallbacks.
   - **Updater**: a scheduled GitHub Action regenerates the data parts of the rules from upstream curated packs (Fabulously Optimized, Additive) and the Modrinth API, then opens a PR for review.
8. **Let the player control the network use**: a settings screen with a master switch and finer switches for remote rules and Modrinth, so RigTune can be run fully offline.

## Non-goals
- Changing the launcher's RAM allocation (we advise instead). Shader-pack tuning beyond advice, and beyond a shader-cost benchmark report. Server-side optimisation. Loaders other than Fabric. Redo after an undo.

## Architecture

```
io.github.chaotix345.rigtune
├── core/                  pure Java, no Minecraft imports, unit-tested
│   ├── model/             records shared across the mod (HardwareProfile, InstalledMod, SettingsSnapshot, Recommendation, Report...), SettingKeys (the vanilla./sodium./dh./iris. key allowlist)
│   ├── hardware/          GpuClassifier (renderer string → vendor/integrated/tier, and the string gpuModelMatches searches), TierCalculator
│   ├── rules/             RulesDocument (Gson), three-valued (TRUE/FALSE/UNKNOWN) Condition evaluation, RulesSources (bundled/cache/remote candidates, pickNewest), RulesLoader, RemoteRulesFetcher
│   ├── recommend/         Recommender: (profile, mods, settings, rules, availability, goal) → Report
│   ├── modrinth/          ModrinthClient interface + HttpModrinthClient (java.net.http), DownloadPlanner, DependencyResolver, OnlineDataFetcher, DTOs
│   ├── net/               BoundedHttp: byte caps, stall timeouts and deadlines for HTTP bodies
│   ├── benchmark/         FrameStats, RenderDistancePlanner, BenchmarkSession (the v2 step planner), Timing/Protocol, KnobGuard, BenchmarkMath, RestoreMarker/ModToggles, BenchmarkHistory
│   ├── apply/             PendingActions (JSON, op groups, merge), ApplyExecutor, ApplyLock (reentrant), SafeFileNames, AtomicFiles, SodiumConfigPatcher, TomlConfigPatcher, PropertiesConfigPatcher,
│   │                      ApplyHelper (post-exit file operations, runnable main), HelperLauncher
│   ├── history/           Journal (the undo log, `config/rigtune/history.json`), UndoPlanner, LegacyImport (0.1.0 migration)
│   └── report/            ShareReport (the Copy report formatter), ModrinthOffAdvice
└── client/                Minecraft/Fabric integration
    ├── RigTuneClient      ClientModInitializer: keybind, title/options-screen button, startup scan, notifications
    ├── RigTunePreLaunch   PreLaunchEntrypoint: finishes any leftover staged operations, journal startup/legacy import, all before mods init
    ├── RealController     wires the above to the UI: rescan/rebuild, reloadRules/settingsChanged, apply, undo, startBenchmark, shareReport
    ├── ClientSettings     `config/rigtune/settings.json`: network switches, startup toast, default goal, benchmark scene
    ├── OnlineLookupGate   decides when a Modrinth lookup is due (once per scan + rules pair, whichever settles last)
    ├── probe/             HardwareProbe (OSHI + Blaze3D device + window), ModScanner (FabricLoader), SettingsBridge (reads/writes Options, plus every `ConfigTargets` namespace: Sodium, Distant Horizons, Iris)
    ├── ui/                RigTuneScreen, RigTuneSettingsScreen, UndoScreen, BenchmarkMenuScreen, BenchmarkResultScreen
    ├── undo/              Staging (owns stage(): lock → relocate → merge → save → retire → record), VanillaChanges, HistoryStartup
    ├── benchmark/         BenchmarkController (tick-driven state machine, frame sampling, camera sweep), ClientKnobs, BenchmarkWorld/WorldFlow (the dedicated benchmark save), BenchmarkStore, MarkerRestore
    └── compat/            DhCompat, IrisCompat — the only classes linked against the Distant Horizons/Iris APIs (compileOnly), reached only once FabricLoader says the mod is present
```

Keeping the logic in `core/` means most behaviour is unit-tested without launching Minecraft. It also keeps the code that must be re-ported for each MC release small (just `client/`; see "Porting to new MC versions").

## Data flow
1. Client init: load the rules (bundled v2 → the v2 cache → 0.1.0's cache, read-only → remote v2, or remote v1 if that fails; the highest revision wins, ties broken toward v2, then remote over cache over bundled), probe the hardware once a GPU device exists, and scan the mods and every config namespace (Sodium, Distant Horizons, Iris). `OnlineLookupGate` triggers exactly one Modrinth lookup per scan-and-rules pair, from whichever of the two settles last, unless Modrinth is switched off.
2. Build a report in the background, including the Modrinth lookups: one bulk `POST /version_files` for installed hashes, one `POST /version_files/update` for updates, one bulk `GET /projects?ids=` to rule candidates out, and a `GET /project/{id}/version` check for each candidate that the project lists don't rule out. If Modrinth is unreachable or switched off, Add/Update recommendations become advice ("install/update it in your launcher") instead of a one-click action.
3. On the title screen, a small toast shows "RigTune: N suggestions" (unless the startup-toast switch is off). The button or keybind opens `RigTuneScreen`, which opens `RigTuneSettingsScreen` (Settings, also reachable from Mod Menu), `UndoScreen` (Undo last / Undo everything) and `BenchmarkMenuScreen` (Benchmark…).
4. The player ticks items and presses Apply. Vanilla settings are applied now, saved, and journaled with their before/after values. Mod, Sodium, Distant Horizons and Iris operations go into `config/rigtune/pending.json` as one group per recommendation, journaled as STAGED, and the screen tells the player to restart.
5. When the client stops, if there are pending operations, spawn `ApplyHelper` in a separate JVM (same Java executable, with copies of our jar and Gson on the classpath). It waits for the game process to exit, then takes the apply lock, applies the file operations, updates the journal (best-effort, and reachable only through core/Gson classes) and writes a result log.
6. On the next launch, preLaunch waits briefly for a helper that still holds the apply lock, then reads the result log and shows it, reconciles the journal (a STAGED change whose operation is no longer pending becomes ABANDONED), and — the very first time 0.2 runs against a 0.1.0 instance — imports 0.1.0's last apply as one `legacy-import` history entry. Operations that are still pending (e.g. the helper was killed) are reported so the player can finish them manually.
7. Undo: the player presses "Undo last apply" or "Undo everything"; `UndoScreen` shows exactly what `UndoPlanner` would revert (and what it would skip, with a reason). Confirming reverts settings immediately, stages file/config reversals the same way Apply does, and journals the whole thing as one new `undo` entry, which itself can't be undone.

## Apply pipeline (why a post-exit helper)
- On Windows, the loaded mod jars stay open, so they can't be renamed or deleted while the game runs.
- Loading two versions of the same mod id crashes Fabric Loader at startup. So an update must disable the old jar *and* enable the new one, both together, only after the game has exited.
- New jars are downloaded while the game runs to `mods/<name>.jar.rigtune-pending`, which Fabric doesn't load. Each download is checked against Modrinth's SHA-512.
- **File names**: a Modrinth file name must be a bare `.jar` name (no `/ \ :` or other characters Windows forbids, no control characters, no leading dot, no trailing dot or space, no reserved DOS device name such as `NUL.jar`, no 8.3 short-name alias such as `SODIUM~1.jar`, which NTFS could resolve to an existing jar), and the resolved path's parent must be the mods folder. Otherwise the download is refused.
- **Which folders** (`InstanceDirs`): the mods folder is found the way Fabric Loader 0.19.5 finds it: `-Dfabric.modsFolder` if set (taken as given, so a relative value is relative to the working directory, as in Fabric), else `<gameDir>/mods`, then its real path. ModScanner, RealController and the helper all use this; the helper gets the game's value as `-Dfabric.modsFolder`. Folders are compared by real path, so a symlinked `mods/` works. The config folder is `<gameDir>/config`.
- **Which jars**: only jars directly in the instance's mods folder get file actions. A jar loaded from elsewhere (`-Dfabric.addMods`, a launcher-shared folder) still gets update checks, but the recommendation is advice only ("update it in your launcher").
- **Planning downloads** (`DownloadPlanner`): each Add/Update recommendation becomes one **group** (an update is {disable old, enable new}; an added mod is its jar plus the dependencies it needs); every enable records the jar's `fabric.mod.json` id.
  - Each recommendation works on its own copies of the installed projects and mod ids, committed only when it succeeds, so a failed one can't make a later one skip a dependency it never delivered.
  - A recommendation that needs a dependency an earlier one in the same batch staged joins that one's group (several groups merge), so the dependency is never applied or rolled back without its dependents.
  - An update whose target file name is already taken by another file is refused before downloading, as an added mod is.
- **Sodium settings**: each value is checked against `sodium-options.json` as it is at staging, with the rules the patch uses, and a value that doesn't fit is reported as failed right away. The rest are staged one `PATCH_JSON` op per key, so one bad value can't block the others.
- **Staging** (`config/rigtune/pending.json`): each op has an id. Staging holds the apply lock and merges into the existing plan:
  - the plan's folders are derived from where `pending.json` is; the `modsDir`/`configDir` it records are informational only. Ops outside this instance's folders (a copied or moved instance) are dropped, and never touched.
  - a repeated change is dropped, and its group is joined with the new one
  - a newer enable for a mod id that already has a staged enable replaces it and takes over the rest of its group; the replaced pending jar is renamed to `<name>.jar.rigtune-superseded` and left inert
- **Helper**: runs from copies of our jar and Gson in `config/rigtune/helper/`, because a JVM keeps its classpath jars open and Windows can't rename an open jar; running from `mods/` would block RigTune's own update. It waits (up to 15 min) for the game process to exit *without* the lock, so a game JVM that lingers can't block staging in a relaunched game, then a short settle delay (2 s) before it even tries to apply, giving anything that briefly grabbed a mod jar right at exit (the Modrinth App re-scanning the instance, an AV scanner) a moment to let go. Only then does it take `config/rigtune/apply.lock` (an OS file lock, so a killed helper releases it), which it holds while it reads the plan, applies it and rewrites `pending.json`. It:
  - derives the mods and config folders from where `pending.json` is, and refuses any op whose files aren't directly in that mods folder (or, for JSON patches, inside that config folder)
  - runs each group all-or-nothing: disables first; an enable only once the group's disables are OK or already done; if a later op fails, the earlier renames are undone, and the group stays pending (minus ops that were already done)
  - before a group with an enable that carries a mod id, reads the `fabric.mod.json` id of every `*.jar` in the mods folder, ignoring jars the group disables. If the mod is already there (the launcher updated it meanwhile, or it was installed by hand), the group is **abandoned**: nothing is renamed and its downloads become `.rigtune-superseded`
  - renames pending files → `.jar`
  - renames removed or old jars → `.jar.disabled` (the Modrinth App's convention for disabled content, so it can be re-enabled from the launcher)
  - patches `config/sodium-options.json`, keeping each existing field's JSON type (a value that doesn't fit fails the patch)
  - re-reads `pending.json` and removes only the ops it ran successfully or abandoned (by op id), so ops staged meanwhile survive. Each failed op counts an attempt; when one reaches 3 failed runs, its whole group is abandoned
  - writes `config/rigtune/last-apply.json`; abandoned ops have status `ABANDONED`, and their downloads are renamed to `.rigtune-superseded`
  - a rename or patch write retries: a sharing violation (Windows denying it because something still has the file open) backs off exponentially (300 ms, doubling, capped at 5 s) up to a ~30 s total budget, since that kind of contention can outlast a few seconds; any other I/O error keeps the fast, fixed-delay retry (10 × 300 ms), same as a rollback undoing an earlier op in the group
  - writes `pending.json`/`last-apply.json` themselves temp-then-atomic-move, retrying the move (10 × 100 ms) while Windows denies it because an AV scanner, indexer or sync client has the file open
- **Next launch**: preLaunch tries the apply lock. If the helper still holds it, preLaunch waits up to 5 s and then shows a warning that changes take effect after another restart (Fabric has already picked the mod jars by then), rather than racing the helper. A toast reports the last result, including how many changes were dropped.
- **Discard pending**: while `pending.json` exists, the RigTune screen shows a "Discard pending" button. Under the apply lock it renames every staged download to `.rigtune-superseded` and deletes `pending.json`.
- Nothing is ever deleted (the only exception is a just-downloaded duplicate that no staged op uses), so every change can be undone (see "Undo").
- If the helper never runs, the pending files stay inert, which is safe.

### Journal, undo, and the new config patch ops
- **Journal** (`config/rigtune/history.json`, `core/history/Journal`): every Apply, benchmark "Keep" and Undo is one journal entry, holding the (now reentrant) apply lock and written atomically; the last 50 entries are kept. Each change records its `before`/`after` and moves `STAGED` → `APPLIED`/`ABANDONED`/`DISCARDED` as `pending.json` and the helper's result evolve. The helper's own journal update runs through core classes and Gson only (never `RigTune.LOGGER`, Fabric or MC classes), so the helper's classpath stays just our jar and Gson; a mistake here is caught by running the helper in a child JVM with only those two on the classpath.
- **Config patch ops**: alongside Sodium's `PATCH_JSON`, new `PATCH_TOML` and `PATCH_PROPERTIES` ops patch `config/DistantHorizons.toml` and `config/iris.properties` the same staged, validate-at-both-staging-and-apply-time way. The TOML patcher never infers a value's type from the *incoming* string — only from what's already in the file (a quoted key vs. a bare one) — and refuses a mismatch, because DH writes doubles and enums quoted but ints and booleans bare; guessing from the new value's shape could silently write a value DH's own parser can't read.
- **Retry policy**: a *sharing violation* (a file still briefly open right after the game exits — the Modrinth App re-scanning the instance, an antivirus scan) backs off exponentially (300 ms, doubling, capped at 5 s) over a ~30 s budget; every other `IOException` keeps the original fixed 10 × 300 ms retry. Rollback (undoing an earlier op in a group after a later one fails) uses whichever policy applies. The helper also waits 2 s (not 1 s) after the game process exits, before it even tries to take the apply lock, to give anything that briefly grabbed a jar right at exit a moment to let go.
- **Undo** (`core/history/UndoPlanner`, `client/undo/UndoService` + `UndoScreen`): "Undo last apply" is the newest entry with anything left to revert; "Undo everything" is every entry. A staged change is removed from `pending.json` by op id, taking its whole group with it (a dependency can't be dropped while a dependent stays); an applied change is reverted only if nothing has changed it since ("you changed it since" otherwise), checked against a simulated mods folder so a whole reversal sequence (e.g. an update chain a→b→c undone back to a) is checked as one. A reversal is skipped rather than applied if it would leave two active jars sharing a mod id, or drop a `depends` mod id nothing else provides. Settings revert immediately; mod/config reversals are staged and applied by the same post-exit helper as Apply. The undo itself is one journal entry and can't be undone.

## Rules (v2, with a safe v1 projection)
0.2+ clients read `rules/rules-v2.json` (`schemaVersion: 2`): more condition fields (`gpuModelMatches`, `displayPixelsAtLeast`/`displayPixelsAtMost`, `modVersion`, `mcVersionRange`), `settingLabels` for recommendation titles, and three-valued (TRUE/FALSE/UNKNOWN) evaluation that fails closed — an unknown condition field anywhere in a rule's condition tree makes the whole rule not fire, rather than silently matching or not matching.

0.1.x clients still read `rules/rules-v1.json` (`schemaVersion: 1`). The same updater run that writes `rules-v2.json` also writes a conservative *projection* of the same knowledge into v1 terms — a rule that uses a v2-only feature is either rewritten (an explicit, hand-checked `v1` override), or left out, never silently weakened — so a 0.1.x install can never get a less safe recommendation after the 0.2.0 rules go live. A differential test runs the pinned 0.1.0 recommender over the old and new `rules-v1.json` across a hardware × mods × goal matrix to catch a regression automatically.

The full field list, the client's candidate list and `pickNewest` tie-break, and the v1 projection rules are documented in [docs/RULES_SCHEMA.md](RULES_SCHEMA.md); the maintainer workflow (the hand-written source, the updater, `REVIEW.md`) is in [tools/README.md](../tools/README.md).

## Hardware tier
- Tiers run from 1 to 5, computed as `min(gpuTier, cpuTier, memTier)`. The report also names the limiting factor.
  - gpuTier comes from the regex table in the rules. Software renderers get tier 0, which triggers a critical warning.
  - cpuTier comes from logical cores and the max clock, plus a bonus for known cache-heavy parts (X3D).
  - memTier comes from the JVM max heap.
- The player's goal (performance / balanced / quality) shifts the effective tier by −1, 0 or +1 when picking settings.

## Benchmark v2
Two scenes: **current** (the player's world and position, as in 0.1) and the dedicated **benchmark world** — a singleplayer save named `rigtune-benchmark` with a fixed seed, camera spot, time (frozen at noon) and weather (clear), created on demand from the title-screen benchmark menu and reused across runs (recreated only if its recorded MC version or seed no longer matches). Two modes: **Tune** and **Measure** (no tuning, just measures the current settings — used for before/after).

1. Remember the current settings and hide the GUI. Test values are set in memory only; options.txt is written once, with the original values, when they're restored. Creative flight is toggled client-side only, so the server never stores it. Lift the frame-rate limit to unlimited (260), turn vsync off and set the inactivity limit to `minimized` so the AFK throttle (30 FPS after 60 s without input) can't kick in.
2. **Tune**, under a ~5 minute deadline (coordinate descent, one knob at a time):
   - render distance: `RenderDistancePlanner`, unchanged from 0.1 — for each candidate, set it, wait until the chunk sections have compiled (or a 20 s timeout), then a 360° camera sweep at two pitches (level, then 25° down) for about 6 s while recording frame times; it raises the render distance while the 1% low stays at or above the target FPS, otherwise lowers it, and stops after 6 steps or when the search converges;
   - simulation distance (singleplayer only): a cheaper 3-point local search (current, −2, −4) that only lowers it below the current value when doing so beats it by a clear margin, since it's also a gameplay setting;
   - the chosen settings are measured twice more, and the coefficient of variation of the two 1% lows is reported (a result "was noisy, close background apps and retry" warning above 5%);
   - **Distant Horizons and shader cost reports**: one extra measurement each with DH rendering off, then with shaders off (DH back on in between), each always restored right after — these are reports of what the feature is costing, not something the benchmark auto-disables. A report whose feature refuses to switch off, or that the deadline cuts, is shown as "not measured" rather than a number.
3. **Measure**: two repeats of the current settings at the fixed scene, no tuning — the "before" and "after" of a before/after comparison.
4. Restore the camera, GUI, Distant Horizons/Iris state and original settings on every exit path (Esc, a disconnect, a world or player change, an exception in the benchmark, or the client stopping). A crash-safety marker (`config/rigtune/benchmark-restore.json`), written before DH or Iris is ever changed, is retried at the next launch if a restore didn't finish; DH's `renderingEnabled` turned out to persist to `DistantHorizons.toml` itself (not purely an in-memory API override), so the restore writes the *original* value back explicitly rather than just clearing an override.
5. The result screen shows the target, the result and a noise warning if relevant, the DH/shader cost lines, and a small chart of the last ~10 runs of the same scene (`config/rigtune/benchmarks.json`, the last 50 kept). **Measure before** (saved with a pair id) → the player applies recommendations, restarting if needed → **Measure after** (same scene and MC version) reports "1% lows: +X% (avg +Y%)", or "no significant change" when the gain is within twice the larger of the two runs' noise.

Esc cancels at any point and restores everything, as do a disconnect, a world or player change, an exception in the benchmark, and the client stopping.

## Settings and network switches
`config/rigtune/settings.json` (`client/ClientSettings`) is deliberately its own file, separate from `rigtune.json`, so a 0.1.x downgrade (which only knows the old file) can't reset it on a later re-upgrade. It holds: `networkEnabled` (the master switch — off means no request of any kind, of anything below), `remoteRules`, `modrinth` (lookups, update checks *and* downloads), `startupToast` (the title-screen suggestions toast), the default `goal`, and `benchmarkScene`. A missing or partial file gives defaults for whatever's missing.

`RigTuneSettingsScreen` (opened from the RigTune screen, or from Mod Menu's config button) edits it directly: every change saves at once, and the three network switches also call `settingsChanged()` (reloads the rules, then rescans). Gating happens at two seams so every call path is covered by one check apiece: `core/modrinth/GatedModrinthClient` wraps every Modrinth call and refuses it (before touching the delegate) while `modrinth` is off — installed-jar lookups, update checks (including RigTune's own self-update), availability checks, dependency resolution and downloads all go through it — and `RulesSources` checks `remoteRulesAllowed()` before each rules request. With Modrinth off, Add/Update recommendations keep their title, category and impact but become advice ("install/update it in your launcher") instead of a one-click action; with the network master off, the RigTune screen's header says "Offline (network off in settings)".

## Staying current: updater (tools/update_rules.py + .github/workflows/update-rules.yml)
- A Python script using only the standard library:
  - reads the hand-written knowledge in `rules/source/knowledge.json` (which may use v2-only condition fields and per-rule `v1` overrides)
  - fetches the Fabulously Optimized and Additive packwiz mod lists for the newest MC versions
  - queries Modrinth for each rule mod's project status and the MC versions it supports
- It writes `rules/rules-v2.json` (+ the bundled copy in `src/main/resources`) and the safe v1 projection `rules/rules-v1.json` from one run, sharing one `revision` (bumped when either file's content changes) and `generatedAt`. It sets `upstream` flags and `availability`, and lists mods that are new upstream, plus every v1 omission/override, in `rules/REVIEW.md` for a maintainer to triage.
- The GitHub Action runs it weekly and on demand, and opens a PR when something changed.
- CI (`.github/workflows/build.yml`) runs the Gradle build and unit tests for every MC version, the `rules-consistency` and `rules-v1-compat` checks, and the Python updater tests on every push and PR.

## Porting to new MC versions
All MC-touching code lives in `client/`, and it uses Fabric API events rather than mixins wherever possible. The recommendations themselves don't need a port; they arrive through the rules file.

One jar can't serve two MC versions: `InputConstants` key codes are compile-time constants that javac inlines (F8 is 297 on 26.2, 65 on 26.3), and classes move between packages. So each version is compiled separately from one source tree with [Stonecutter](https://stonecutter.kikugie.dev/) 0.9.8:

```
settings.gradle                  Stonecutter plugin; versions '26.2', '26.3'; vcsVersion (the committed state, 26.2)
stonecutter.gradle               root script: the active version, Loom declared once, run tasks ordered by version
build.gradle                     per-version script, run once for each versions/<mc>/ (stonecutter.current.version is <mc>)
gradle.properties                shared properties (loader, Loom, mod_version)
versions/<mc>/gradle.properties  minecraft_dependency (the fabric.mod.json range), fabric_api/modmenu/iris versions, optional sodium_version
src/                             shared by every version, in the active version's state
versions/<mc>/build/             rigtune-<mod_version>+mc<mc>.jar, generated sources for the non-active versions, test reports
```

Version-specific code is a comment conditional at the call site: `//? if >=26.3 {` … `//?} else {` … `//?}`, with the inactive branch commented out (a braceless `else` covers one line). Predicates are semver, so `>=26.3` also matches 26.3.1. Prefer an API that exists on every version over a conditional; there are three today (the key type in `RigTuneClient`, the GPU device imports and the refresh rate in `HardwareProbe`).

A node's `minecraft_dependency` is `~<mc>` for a release (it accepts that version's hotfixes and rejects the next drop) and `~<base>-` for a pre-release node, which isn't shipped; never an open-ended `>=`. `sodium_version` is optional: without it the node builds and runs its tests without Sodium. CI's game-test legs (`tools/gametest_matrix.py`) and the release's Modrinth uploads loop over `versions/*/`, so a new node needs no workflow edit.

Adding a version (details, the tools' checks and the hotfix steps: `tools/MC_VERSIONS.md`):
1. When Fabric announces the version, read its blog post. Upgrade Loom and Gradle on their own first if it asks.
2. Run `python tools/add_mc_version.py <mc>` (it checks the Mojang manifest, Java 25, Fabric meta, Fabric API, Mod Menu, Sodium and Iris, adds the node to `settings.gradle` and writes `versions/<mc>/gradle.properties`), then `./gradlew :<mc>:build`.
3. Fix compile errors with `//? if >=<mc> {`. Run `python tools/mc_apidiff.py <prev> <mc>` and review every changed class, including reflection targets, the mixin target, `Options` keys and runtime defaults.
4. Run `:<mc>:runClientGameTest` and a production smoke test.
5. Review version-specific rules. Regenerate the rules for all supported versions.
6. Update docs and the changelog. Run `./gradlew "Reset active project"` and commit on a feature branch.
7. Tag a release. CI builds and publishes every node.
8. For each later hotfix: bytecode-compare (`mc_apidiff.py` with both nodes built), then `PATCH` `game_versions` on Modrinth.

To move the committed state to a newer version, change `vcsVersion`, `stonecutter.active` and the version checked in `.github/workflows/build.yml` together.

Dropping a version: remove it from `versions`, delete `versions/<mc>/`, and delete the conditional branches only it used (Stonecutter doesn't prune them).

## Safety
- The mod never touches files without an explicit Apply. Everything is reversible (`.disabled`, never deleted) and, since 0.2, explicitly undoable through the journal (Undo last apply / Undo everything). Downloads are hash-verified. Modrinth requests carry a descriptive User-Agent, a byte cap, a stall timeout and an overall deadline, and results are cached.
- Network failures fall back to offline behaviour and never block startup; the player can also switch any network request off entirely (see "Settings and network switches").

## Deviations (apply/modrinth/benchmark)
- **Modrinth API (verified live 2026-09-24)**: `GET /v2/projects?ids=` accepts ids and slugs mixed and silently drops unknown ones, so an unknown candidate slug is left out of `availableBySlug` (unknown) rather than marked unavailable. `/version_files` and `/version_files/update` return maps keyed by hash and omit unknown hashes. `GET /project/{id}/version` returns 404 for an unknown project, and `latestVersion` maps that to `Optional.empty()`. Response ordering isn't documented, so versions are sorted by `date_published`.
- **Errors**: `ModrinthClient` methods throw `IOException`. `ModrinthException extends IOException` and carries `statusCode()` and `rateLimited()` (429).
- **HTTP limits** (`core/net/BoundedHttp`): `HttpRequest.timeout` only covers the wait for headers, so every body is read through a capped subscriber with a stall timeout and an overall deadline, after which the exchange is cancelled. Downloads: file size + 1 KB when Modrinth gives a size, 256 MB at most, 30 s stall, 10 min deadline. JSON: 16 MB, 30 s stall, 60 s deadline. Error bodies are truncated at 64 KB. A 429 is retried once after `Retry-After` (seconds or an HTTP date; `X-Ratelimit-Reset` as a fallback; 1 s if neither), waiting 10 s at most.
- **OnlineDataFetcher**: `fetchAll` returns `Result(OnlineData, projectIdsByModId)`, and `fetch` returns just the `OnlineData`. It skips an "update" whose version was published before the installed one, which guards against a downgrade when a build is mis-tagged. `UpdateInfo.currentVersion` is the installed Modrinth `version_number`. It sends no `version_types`, so a newer beta or alpha can be offered as an update. Failures are logged before it falls back to offline data.
- **Availability**: project `loaders` and `game_versions` are unions over all versions (a mod with Fabric only for 26.1 and NeoForge for 26.2 lists both), so a project-level miss means unavailable but a hit only means "maybe". Each hit gets `GET /v2/project/{id}/version?loaders=["fabric"]&game_versions=[mc]` (via `latestVersion`), 4 at a time; a compatible version with a file means available, none means unavailable. A failed check leaves the candidate unknown, and after a 429 the remaining checks are skipped.
- **DependencyResolver**: breadth-first, root first, default depth 5. It throws `IOException` when the root or a `required` dependency has no compatible version. It ignores dependency version pins and uses the latest compatible version instead. Dependencies that have only a `version_id` are skipped.
- **PendingActions**: each op is one flat record (`type` plus nullable `from`/`to`/`path`/`patches`, and `id`, `group`, `modId`, `attempts`). Paths are stored as strings and `createdAt` as an ISO-8601 string. Ops without a group (including plans written before groups existed) run on their own; ops without an id are matched by value. The executor never overwrites an existing `ENABLE_FILE` target (that op fails instead), and within a group it runs `DISABLE_FILE` ops first, so an update whose new file name equals the old one works.
- **ApplyExecutor**: groups run in order of their first op, and results are reported in plan order. A sharing violation retries with the exponential backoff described above; every other `IOException` uses the fixed 10 × 300 ms retry, and so do rollbacks (a failed rollback is reported in the op's message). Malformed JSON/TOML/properties or bad op fields fail immediately. A `PATCH_JSON`/`PATCH_TOML`/`PATCH_PROPERTIES` op isn't rolled back by a later failure in its group, but it runs last (RealController never groups a config patch with anything that could still fail after it). A `PATCH_JSON` that changes nothing is `SKIPPED_ALREADY_DONE`, and a missing `sodium-options.json` is created from `{}` (the TOML/properties patchers refuse a missing file instead, since DH/Iris must have run once to create one). The mods and config folders come from where `pending.json` is (`InstanceDirs`), whatever the plan records, and `last-apply.json` goes next to `pending.json`.
- **ApplyHelper**: exit codes are 0 (all done), 1 (some ops failed or were abandoned), 2 (bad args) and 3 (game still running after 15 min, the apply lock still held by someone else after 60 s, or interrupted). On a timeout it applies nothing and leaves `pending.json` for the next launch.
- **HelperLauncher**: `launch(configDir, pendingJson)` uses the current pid and truncates `config/rigtune/helper.log` on each run. The classpath copies are named `<n>-<file name>`; an identical copy is reused, a copy still in use by an older helper gets a fresh name, and other files in the folder are deleted. Class directories (development runs) are used in place. If copying fails the helper isn't started, and the plan stays pending. RigTune offers updating or disabling its own jar only when it runs from a jar (`HelperLauncher.selfUpdateSupported`).
- **SodiumConfigPatcher**: an existing boolean accepts only `true`/`false` (any case), an existing number only a number (a whole number when the current value is one), an existing string any text; objects and lists are never replaced. A new field gets an inferred type.
- **Remote rules**: the body is capped at 2 MB (15 s stall, 30 s deadline), a larger cache file is ignored, and an `OutOfMemoryError` while parsing counts as an invalid document. `gpuTiers`/`cpuTiers` patterns over 200 characters are dropped, and matching runs on a `CharSequence` that throws after 1,000,000 character reads, which counts as no match.
- **RenderDistancePlanner**: upward steps are +2, +4, +8… from the last pass. A pass recorded above the lowest failing RD is treated as noise, so the result stays conservative.

## Deviations (client)
- **ModScanner** keeps nested jar-in-jar mods in the list with a null file and hash, as `InstalledMod` documents, so rules see every real mod id (never `provides` aliases). Only top-level jars are hashed and sent to Modrinth, and only jars directly in `mods/` get a `file`.
- **SettingsBridge** drives the private `Options.processOptions(FieldAccess)` through a `java.lang.reflect.Proxy`, because `FieldAccess` is package-private. Writes validate against the option's `ValueSet` before `set()` (an invalid value would otherwise reset the option to its default), and `graphicsPreset` is applied before any other key so explicit values win.
- **Entry buttons**: on the title screen the button sits left of Options; on vanilla video settings right of Done. Sodium replaces the video screen and its page list swallows clicks, so there the button goes bottom-left and claims its clicks through `ScreenMouseEvents.allowMouseClick`.
- **Keybind** (F8, unused by vanilla) works in game only, like every vanilla key mapping; menus use the buttons.
- **Benchmark HUD** is attached after `VanillaHudElements.SLEEP`, the one layer vanilla still draws while the GUI is hidden.
- **Benchmark render distance cap**: the server's view distance only caps the search on a remote server. In singleplayer `IntegratedServer` copies the client's render distance into the view distance every tick, so it would otherwise pin the search to the starting distance.
- **Benchmark in client game tests**: the harness syncs with the test thread on every frame that runs a tick, so those frames (about 1% of frames at ~2000 FPS) are slow and the 1% low reads far below the average (e.g. avg 1914, 1% low 234 on an RX 7800 XT). The numbers are uncapped but not representative of normal play.
- **AddMod** downloads are checked for a `fabric.mod.json` id that is already loaded and dropped if so, since a second top-level jar with the same id stops Fabric from starting. An id that is only staged isn't dropped: the newer jar replaces the staged one when merged.
- **Client game tests** need no extra build config: the `fabric-api` POM pulls `fabric-client-gametest-api-v1` in transitively even though the fat jar doesn't nest it. `runClientGameTest` starts from a fresh run directory each time.

## v0.2 deviations
Each workstream's full design notes and deviations from the plan (docs/v0.2/SPEC.md, docs/v0.2/PLAN.md) are kept in `docs/v0.2/design/`; this is a short summary of the ones worth knowing before touching the related code.
- [v0.2/design/ws-a.md](v0.2/design/ws-a.md) — rules v2: an unknown condition field or an explicit JSON `null` poisons the *whole* condition tree (not just the field that used it), because a TRUE sibling inside `anyOf` would otherwise override an unknown key. The v1 projection needs an explicit `v1` override for anything v2-only — never a silent drop — and a differential test replays the pinned 0.1.0 recommender over the old and new `rules-v1.json` to catch a regression.
- [v0.2/design/ws-b.md](v0.2/design/ws-b.md) — undo: `ApplyLock` became reentrant (one JVM-wide `ReentrantLock` plus one shared OS `FileLock` per real folder path) so the journal can be touched from inside Staging, preLaunch or the undo service without deadlocking. Reversal groups for an update chain are merged by which file they touch, not one-to-one with the original groups, so a→b→c undone back to a becomes one {disable c, enable a} group.
- [v0.2/design/ws-c.md](v0.2/design/ws-c.md) — benchmark v2: only render distance and simulation distance are auto-tuned; Distant Horizons and shaders are cost *reports*. `WorldFlow` only ever sets up, measures or leaves the `rigtune-benchmark` save — never the player's own world.
- [v0.2/design/ws-d.md](v0.2/design/ws-d.md) — Distant Horizons/Iris settings: the TOML reader/patcher's type-preservation rule (above) and its offset-based, not line-based, patching (so untouched formatting, comments and line endings survive a patch).
- [v0.2/design/ws-e.md](v0.2/design/ws-e.md) — settings screen, Copy report: `ShareReport` scrubs anything path-shaped, Markdown-escapes free text and zero-widths every `@`, so a hostile mod title on the clipboard can't format the message or ping anyone.
- [v0.2/design/ws-g.md](v0.2/design/ws-g.md) — self-update end-to-end harness: a local fake Modrinth server plus JVM-level host redirection and a self-signed truststore drive a real released 0.1.0 jar through an update to a 0.2.0 build on a scratch instance copy; this is what found the startup Modrinth-lookup race fixed in `OnlineLookupGate`.
- [v0.2/design/ws-h.md](v0.2/design/ws-h.md) — knowledge: Nvidium's `avoidWhen` deliberately does *not* mirror its `recommendWhen` model allowlist, so a GPU missing from the (necessarily incomplete) list gets neither an add nor a disable suggestion, rather than a false "disable a mod that's actually working" nag.
- [v0.2/design/F.md](v0.2/design/F.md) — Modrinth publishing: `tools/modrinth_project.py`, and what the live API needed beyond its documented behaviour (an empty `initial_versions` is still required on create; a version dependency needs the target project's base62 id, not its slug; submitting for review is three separate calls, not one).
- [v0.2/design/fix-lock-retry.md](v0.2/design/fix-lock-retry.md) — the helper's exponential-backoff retry for a locked file (above), written from the user's own 0.1.0 apply failure.
