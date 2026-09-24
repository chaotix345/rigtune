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
│   ├── net/               BoundedHttp: byte caps, stall timeouts and deadlines for HTTP bodies
│   ├── benchmark/         FrameStats (avg, 1% low, p99), RenderDistancePlanner (search state machine)
│   └── apply/             PendingActions (JSON, op groups, merge), ApplyExecutor, ApplyLock, SafeFileNames, SodiumConfigPatcher,
│                          ApplyHelper (post-exit file operations, runnable main), HelperLauncher
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
2. Build a report in the background, including the Modrinth lookups: one bulk `POST /version_files` for installed hashes, one `POST /version_files/update` for updates, one bulk `GET /projects?ids=` to rule candidates out, and a `GET /project/{id}/version` check for each candidate that the project lists don't rule out. If Modrinth is unreachable, fall back to offline rules.
3. On the title screen, a small toast shows "RigTune: N suggestions". The button or keybind opens `RigTuneScreen`.
4. The player ticks items and presses Apply. Vanilla settings are applied now and saved. Mod and Sodium operations go into `config/rigtune/pending.json`, and the screen tells the player to restart.
5. When the client stops, if there are pending operations, spawn `ApplyHelper` in a separate JVM (same Java executable, with copies of our jar and Gson on the classpath). It takes the apply lock, waits for the game process to exit, then applies the file operations and writes a result log.
6. On the next launch, preLaunch waits briefly for a helper that still holds the apply lock, then reads the result log and shows it. Operations that are still pending (e.g. the helper was killed) are reported so the player can finish them manually.

## Apply pipeline (why a post-exit helper)
- On Windows, the loaded mod jars stay open, so they can't be renamed or deleted while the game runs.
- Loading two versions of the same mod id crashes Fabric Loader at startup. So an update must disable the old jar *and* enable the new one, both together, only after the game has exited.
- New jars are downloaded while the game runs to `mods/<name>.jar.rigtune-pending`, which Fabric doesn't load. Each download is checked against Modrinth's SHA-512.
- **File names**: a Modrinth file name must be a bare `.jar` name (no `/ \ :` or other characters Windows forbids, no control characters, no leading dot, no trailing dot or space, no reserved DOS device name such as `NUL.jar`), and the resolved path's parent must be the mods folder. Otherwise the download is refused.
- **Which jars**: only jars directly in the instance's `mods/` get file actions. A jar loaded from elsewhere (`-Dfabric.addMods`, a launcher-shared folder) still gets update checks, but the recommendation is advice only ("update it in your launcher").
- **Staging** (`config/rigtune/pending.json`): each op has an id; each recommendation's ops form one **group** (an update is {disable old, enable new}; an added mod is its jar plus its dependencies); every enable records the jar's `fabric.mod.json` id. Staging holds the apply lock and merges into the existing plan:
  - a repeated change is dropped, and its group is joined with the new one
  - a newer enable for a mod id that already has a staged enable replaces it and takes over the rest of its group; the replaced pending jar is renamed to `<name>.jar.rigtune-superseded` and left inert
- **Helper**: runs from copies of our jar and Gson in `config/rigtune/helper/`, because a JVM keeps its classpath jars open and Windows can't rename an open jar; running from `mods/` would block RigTune's own update. It holds `config/rigtune/apply.lock` (an OS file lock, so a killed helper releases it) from before the game exits until `pending.json` is rewritten. Then it:
  - refuses any op whose files aren't directly in the plan's mods folder (or, for JSON patches, inside its config folder)
  - runs each group all-or-nothing: disables first; an enable only once the group's disables are OK or already done; if a later op fails, the earlier renames are undone, and the group stays pending (minus ops that were already done)
  - renames pending files → `.jar`
  - renames removed or old jars → `.jar.disabled` (the Modrinth App's convention for disabled content, so it can be re-enabled from the launcher)
  - patches `config/sodium-options.json`, keeping each existing field's JSON type (a value that doesn't fit fails the patch)
  - re-reads `pending.json` and removes only the ops it ran successfully (by op id), so ops staged by another instance survive
- **Next launch**: preLaunch tries the apply lock. If the helper still holds it, preLaunch waits up to 5 s and then shows a warning that changes take effect after another restart (Fabric has already picked the mod jars by then), rather than racing the helper.
- Nothing is ever deleted (the only exception is a just-downloaded duplicate that no staged op uses), so every change can be undone.
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
- The mod never touches files without an explicit Apply. Everything is reversible (`.disabled`, never deleted). Downloads are hash-verified. Modrinth requests carry a descriptive User-Agent, a byte cap, a stall timeout and an overall deadline, and results are cached.
- Network failures fall back to offline behaviour and never block startup.

## Deviations (apply/modrinth/benchmark)
- **Modrinth API (verified live 2026-09-24)**: `GET /v2/projects?ids=` accepts ids and slugs mixed and silently drops unknown ones, so an unknown candidate slug is left out of `availableBySlug` (unknown) rather than marked unavailable. `/version_files` and `/version_files/update` return maps keyed by hash and omit unknown hashes. `GET /project/{id}/version` returns 404 for an unknown project, and `latestVersion` maps that to `Optional.empty()`. Response ordering isn't documented, so versions are sorted by `date_published`.
- **Errors**: `ModrinthClient` methods throw `IOException`. `ModrinthException extends IOException` and carries `statusCode()` and `rateLimited()` (429).
- **HTTP limits** (`core/net/BoundedHttp`): `HttpRequest.timeout` only covers the wait for headers, so every body is read through a capped subscriber with a stall timeout and an overall deadline, after which the exchange is cancelled. Downloads: file size + 1 KB when Modrinth gives a size, 256 MB at most, 30 s stall, 10 min deadline. JSON: 16 MB, 30 s stall, 60 s deadline. Error bodies are truncated at 64 KB. A 429 is retried once after `Retry-After` (seconds or an HTTP date; `X-Ratelimit-Reset` as a fallback; 1 s if neither), waiting 10 s at most.
- **OnlineDataFetcher**: `fetchAll` returns `Result(OnlineData, projectIdsByModId)`, and `fetch` returns just the `OnlineData`. It skips an "update" whose version was published before the installed one, which guards against a downgrade when a build is mis-tagged. `UpdateInfo.currentVersion` is the installed Modrinth `version_number`. It sends no `version_types`, so a newer beta or alpha can be offered as an update. Failures are logged before it falls back to offline data.
- **Availability**: project `loaders` and `game_versions` are unions over all versions (a mod with Fabric only for 26.1 and NeoForge for 26.2 lists both), so a project-level miss means unavailable but a hit only means "maybe". Each hit gets `GET /v2/project/{id}/version?loaders=["fabric"]&game_versions=[mc]` (via `latestVersion`), 4 at a time; a compatible version with a file means available, none means unavailable. A failed check leaves the candidate unknown, and after a 429 the remaining checks are skipped.
- **DependencyResolver**: breadth-first, root first, default depth 5. It throws `IOException` when the root or a `required` dependency has no compatible version. It ignores dependency version pins and uses the latest compatible version instead. Dependencies that have only a `version_id` are skipped.
- **PendingActions**: each op is one flat record (`type` plus nullable `from`/`to`/`path`/`patches`, and `id`, `group`, `modId`). Paths are stored as strings and `createdAt` as an ISO-8601 string. Ops without a group (including plans written before groups existed) run on their own; ops without an id are matched by value. The executor never overwrites an existing `ENABLE_FILE` target (that op fails instead), and within a group it runs `DISABLE_FILE` ops first, so an update whose new file name equals the old one works.
- **ApplyExecutor**: groups run in order of their first op, and results are reported in plan order. Only `IOException`s are retried (10 × 300 ms); rollbacks are retried the same way, and a failed rollback is reported in the op's message. Malformed JSON or bad op fields fail immediately. `PATCH_JSON` isn't undone, but it runs last in its group (RealController never groups it). A `PATCH_JSON` that changes nothing is `SKIPPED_ALREADY_DONE`, and a missing target file is created from `{}`. A plan without `modsDir`/`configDir` has all its file ops refused. If the plan has no `configDir`, `last-apply.json` is written next to `pending.json`.
- **ApplyHelper**: exit codes are 0 (all done), 1 (some ops failed), 2 (bad args) and 3 (game still running after 15 min, the apply lock still held by someone else after 60 s, or interrupted). On a timeout it applies nothing and leaves `pending.json` for the next launch.
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
- **AddMod** downloads are checked for a `fabric.mod.json` id that is already loaded and dropped if so, since a second top-level jar with the same id stops Fabric from starting. An id that is only staged isn't dropped: the newer jar replaces the staged one when merged.
- **Client game tests** need no extra build config: the `fabric-api` POM pulls `fabric-client-gametest-api-v1` in transitively even though the fat jar doesn't nest it. `runClientGameTest` starts from a fresh run directory each time.
