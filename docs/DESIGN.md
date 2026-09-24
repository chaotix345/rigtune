# RigTune: design

A client-side Fabric mod for Minecraft Java 26.2. It works out which mods and settings will get the most out of the player's hardware, and it keeps that advice current without needing a new mod release.

## Goals
1. **Detect the hardware**: CPU, RAM, JVM heap, GPU (vendor, model, VRAM, driver, graphics backend), display (resolution, refresh rate), and battery/laptop state.
2. **Scan installed mods**: use the Fabric mod ids (offline) plus jar hashes checked against the Modrinth API (for updates).
3. **Recommend** changes, each with a reason, an impact level, and a checkbox:
   - add missing performance mods that suit this hardware (e.g. Nvidium only on NVIDIA)
   - update outdated mods
   - remove obsolete or conflicting mods (Indium, Starlight, OptiFabric, both C2ME and Moonrise, etc.)
   - Sodium settings and vanilla video settings, by hardware tier and the player's goal (performance / balanced / quality)
   - advice outside the game: RAM allocation (which the launcher controls) and GPU driver workarounds that Sodium applied
4. **Benchmark**: an in-game test that measures real frame times and searches render distance to hit a target FPS (default: the monitor's refresh rate, measured on 1% lows). Specs only give the starting point; the measurement decides.
5. **Apply**: vanilla settings take effect immediately. Mod file changes and Sodium config changes are staged, then applied safely after the game exits (see "Apply pipeline").
6. **Stay current**:
   - **Live**: the Modrinth API reports mod availability and updates for the running MC version at runtime.
   - **Rules**: a JSON rules file hosted on GitHub is fetched at startup, with a cached copy and a bundled copy as fallbacks.
   - **Updater**: a scheduled GitHub Action regenerates the data parts of the rules from upstream curated packs (Fabulously Optimized, Additive) and the Modrinth API, then opens a PR for review.

## Non-goals (v1)
- Changing the launcher's RAM allocation (we advise instead). Shader-pack tuning beyond advice. Server-side optimisation. Loaders other than Fabric.

## Architecture

```
io.github.chaotix345.rigtune
├── core/                  pure Java, no Minecraft imports, unit-tested
│   ├── model/             records shared across the mod (HardwareProfile, InstalledMod, SettingsSnapshot, Recommendation, Report...)
│   ├── hardware/          GpuClassifier (renderer string → vendor/integrated/tier), TierCalculator
│   ├── rules/             RulesDocument (Gson), Condition evaluation, RulesLoader (bundled/cache/remote pick-newest)
│   ├── recommend/         Recommender: (profile, mods, settings, rules, availability, goal) → Report
│   ├── modrinth/          ModrinthClient interface + HttpModrinthClient (java.net.http), DTOs
│   ├── benchmark/         FrameStats (avg, 1% low, p99), RenderDistancePlanner (search state machine)
│   └── apply/             PendingActions (JSON), SodiumConfigPatcher, ApplyHelper (post-exit file operations, runnable main)
└── client/                Minecraft/Fabric integration
    ├── RigTuneClient      ClientModInitializer: keybind, title/options-screen button, startup scan, notifications
    ├── RigTunePreLaunch   PreLaunchEntrypoint: finishes any leftover staged operations that are safe before mods init
    ├── probe/             HardwareProbe (OSHI + Blaze3D device + GLFW window), ModScanner (FabricLoader), SettingsReader/Writer (Options)
    ├── ui/                RigTuneScreen (overview + recommendation list + actions), BenchmarkResultScreen
    └── benchmark/         BenchmarkController (tick-driven state machine, frame sampling, camera sweep)
```

Keeping the logic in `core/` means most behaviour is unit-tested without launching Minecraft. It also keeps the code that must be re-ported for each MC release small (just `client/`).

## Data flow
1. Client init: load the rules (bundled → cached → remote in the background), probe the hardware once a GPU device exists, and scan the mods.
2. Build a report in the background, including the Modrinth lookups: one bulk `POST /version_files` for installed hashes, one `POST /version_files/update` for updates, and one bulk `GET /projects?ids=` for candidate availability. If Modrinth is unreachable, fall back to offline rules.
3. On the title screen, a small toast shows "RigTune: N suggestions". The button or keybind opens `RigTuneScreen`.
4. The player ticks items and presses Apply. Vanilla settings are applied now and saved. Mod and Sodium operations go into `config/rigtune/pending.json`, and the screen tells the player to restart.
5. On JVM exit, if there are pending operations, spawn `ApplyHelper` in a separate JVM (same Java executable; our jar on the classpath). It waits for the game process to exit, then applies the file operations and writes a result log.
6. On the next launch, preLaunch reads the result log and shows it. Operations that are still pending (e.g. the helper was killed) are reported so the player can finish them manually.

## Apply pipeline (why a post-exit helper)
- On Windows, the loaded mod jars stay open, so they can't be renamed or deleted while the game runs.
- Loading two versions of the same mod id crashes Fabric Loader at startup. So an update must disable the old jar *and* enable the new one, both together, only after the game has exited.
- New jars are downloaded while the game runs to `mods/<name>.jar.rigtune-pending`, which Fabric doesn't load. Each download is checked against Modrinth's SHA-512.
- The helper then:
  - renames pending files → `.jar`
  - renames removed or old jars → `.jar.disabled` (the Modrinth App's convention for disabled content, so it can be re-enabled from the launcher)
  - patches `config/sodium-options.json`
- Nothing is ever deleted, so every change can be undone.
- If the helper never runs, the pending files stay inert, which is safe.

## Rules format (rules/rules-v1.json)
```jsonc
{
  "schemaVersion": 1,
  "revision": 12,                  // monotonically increasing; newest wins
  "generatedAt": "2026-09-24T00:00:00Z",
  "gpuTiers": [ { "pattern": "(?i)rtx\\s*40[6-9]0", "vendor": "nvidia", "tier": 5, "integrated": false }, ... ],
  "mods": [
    {
      "slug": "sodium", "projectId": "AANobbMI", "modIds": ["sodium"],
      "category": "rendering", "impact": "high",
      "reason": "Rewrites the chunk renderer; the single biggest FPS gain.",
      "recommendWhen": { "always": true },
      "conflictsWith": ["optifabric"],
      "upstream": { "fabulouslyOptimized": true, "additive": true }   // generated
    }
  ],
  "obsolete": [ { "modIds": ["indium"], "reason": "Merged into Sodium 0.6+" } ],
  "settings": [
    { "key": "vanilla.renderDistance", "value": 12, "when": { "tierAtLeast": 4 }, "reason": "..." }
  ],
  "availability": { "26.2": ["sodium", "lithium", ...] }   // generated fallback for offline use
}
```
A condition is a declarative object. All fields are optional and they are ANDed together: `always`, `tierAtLeast`, `tierAtMost`, `gpuVendor[]`, `gpuIntegrated`, `onBattery`, `hasBattery`, `heapMbAtLeast`, `heapMbAtMost`, `ramMbAtLeast`, `modPresent[]`, `modAbsent[]`, `goal[]`, `mcVersion[]`, `not{}`, and `anyOf[]`. There is no scripting language.

## Hardware tier
- Tiers run from 1 to 5, computed as `min(gpuTier, cpuTier, memTier)`. The report also names the limiting factor.
  - gpuTier comes from the regex table in the rules. Software renderers get tier 0, which triggers a critical warning.
  - cpuTier comes from logical cores and the max clock, plus a bonus for known cache-heavy parts (X3D).
  - memTier comes from the JVM max heap.
- The player's goal (performance / balanced / quality) shifts the effective tier by −1, 0 or +1 when picking settings.

## Benchmark
Requires the player to be in a world, ideally singleplayer.
1. Save the current settings and hide the GUI.
2. For each candidate render distance: set it, wait until the chunk sections have compiled (or a 20 s timeout), then do a 360° camera sweep at two pitches for about 6 s while recording frame times.
3. `RenderDistancePlanner` searches between the minimum and the cap:
   - it raises the render distance while the 1% low stays at or above the target FPS
   - otherwise it lowers it
   - it stops after 6 steps or when the search converges
4. Restore the camera, GUI and original settings, then show the result. Keep the result, or revert?

Esc cancels at any point and restores everything.

## Staying current: updater (tools/update_rules.py + .github/workflows/update-rules.yml)
- A Python script using only the standard library:
  - reads the hand-written knowledge in `rules/source/*.json`
  - fetches the Fabulously Optimized and Additive packwiz mod lists for the newest MC versions
  - queries Modrinth for each rule mod's project status and the MC versions it supports
- It writes `rules/rules-v1.json`: bumps the revision, sets `upstream` flags and `availability`, and lists mods that are new upstream in `rules/REVIEW.md` for a maintainer to triage.
- The GitHub Action runs it weekly and on demand, and opens a PR when something changed.
- CI (`.github/workflows/build.yml`) runs the Gradle build and unit tests and the Python updater tests on every push and PR.

## Porting to new MC versions
All MC-touching code lives in `client/`, and it uses Fabric API events rather than mixins wherever possible. The recommendations themselves don't need a port; they arrive through the rules file.

## Safety
- The mod never touches files without an explicit Apply. Everything is reversible (`.disabled`, never deleted). Downloads are hash-verified. Modrinth requests carry a descriptive User-Agent and a timeout, and results are cached.
- Network failures fall back to offline behaviour and never block startup.

## Deviations (apply/modrinth/benchmark)
- **Modrinth API (verified live 2026-09-24)**: `GET /v2/projects?ids=` accepts ids and slugs mixed and silently drops unknown ones, so an unknown candidate slug is left out of `availableBySlug` (unknown) rather than marked unavailable. `/version_files` and `/version_files/update` return maps keyed by hash and omit unknown hashes. `GET /project/{id}/version` returns 404 for an unknown project, and `latestVersion` maps that to `Optional.empty()`. Response ordering isn't documented, so versions are sorted by `date_published`.
- **Errors**: `ModrinthClient` methods throw `IOException`. `ModrinthException extends IOException` and carries `statusCode()` and `rateLimited()` (429).
- **OnlineDataFetcher**: `fetchAll` returns `Result(OnlineData, projectIdsByModId)`, and `fetch` returns just the `OnlineData`. It skips an "update" whose version was published before the installed one, which guards against a downgrade when a build is mis-tagged. `UpdateInfo.currentVersion` is the installed Modrinth `version_number`. It sends no `version_types`, so a newer beta or alpha can be offered as an update.
- **DependencyResolver**: breadth-first, root first, default depth 5. It throws `IOException` when the root or a `required` dependency has no compatible version. It ignores dependency version pins and uses the latest compatible version instead. Dependencies that have only a `version_id` are skipped.
- **PendingActions**: each op is one flat record (`type` plus nullable `from`/`to`/`path`/`patches`). Paths are stored as strings and `createdAt` as an ISO-8601 string. The executor runs ops in plan order and never overwrites an existing `ENABLE_FILE` target (that op fails instead), so plan builders must put `DISABLE_FILE` before `ENABLE_FILE` when filenames can collide.
- **ApplyExecutor**: only `IOException`s are retried. Malformed JSON or bad op fields fail immediately. A `PATCH_JSON` that changes nothing is `SKIPPED_ALREADY_DONE`, and a missing target file is created from `{}`. If the plan has no `configDir`, `last-apply.json` is written next to `pending.json`.
- **ApplyHelper**: exit codes are 0 (all done), 1 (some ops failed), 2 (bad args) and 3 (game still running after 15 min, or interrupted). On a timeout it applies nothing and leaves `pending.json` for the next launch.
- **HelperLauncher**: `launch(configDir, pendingJson)` uses the current pid and truncates `config/rigtune/helper.log` on each run.
- **RenderDistancePlanner**: upward steps are +2, +4, +8… from the last pass. A pass recorded above the lowest failing RD is treated as noise, so the result stays conservative.

## Deviations (client)
- **ModScanner** keeps nested jar-in-jar mods in the list with a null file and hash, as `InstalledMod` documents, so rules see every real mod id (never `provides` aliases). Only top-level jars are hashed and sent to Modrinth.
- **SettingsBridge** drives the private `Options.processOptions(FieldAccess)` through a `java.lang.reflect.Proxy`, because `FieldAccess` is package-private. Writes validate against the option's `ValueSet` before `set()` (an invalid value would otherwise reset the option to its default), and `graphicsPreset` is applied before any other key so explicit values win.
- **Entry buttons**: on the title screen the button sits left of Options; on vanilla video settings right of Done. Sodium replaces the video screen and its page list swallows clicks, so there the button goes bottom-left and claims its clicks through `ScreenMouseEvents.allowMouseClick`.
- **Keybind** (F8, unused by vanilla) works in game only, like every vanilla key mapping; menus use the buttons.
- **Benchmark HUD** is attached after `VanillaHudElements.SLEEP`, the one layer vanilla still draws while the GUI is hidden.
- **AddMod** downloads are checked for a `fabric.mod.json` id that is already loaded and dropped if so, since a second top-level jar with the same id stops Fabric from starting.
- **Client game tests** need no extra build config: the `fabric-api` POM pulls `fabric-client-gametest-api-v1` in transitively even though the fat jar doesn't nest it. `runClientGameTest` starts from a fresh run directory each time.
