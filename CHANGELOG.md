# Changelog

All notable changes to RigTune are documented here. Dates are UTC. The section
for each release becomes that release's Modrinth changelog (`build.gradle`'s
`changelogForVersion`, matched on the `## [<mod_version>]` heading).

## [Unreleased]

## [0.3.0] - 2026-09-26

### Added
- **History** ("What RigTune changed", from **History…** on the RigTune screen): every Apply, benchmark result, Undo and 0.1 import, newest first, with its local date and time and the RigTune and Minecraft versions. Select an entry to see each change (a setting's before → after, a mod file added, disabled, re-enabled or updated) and its status: Applied, Waiting for restart, Not applied, Cancelled or Undone. A change the post-exit helper couldn't apply shows the helper's reason ("Last attempt failed: … (try 2 of 3 at restart)"), and a dropped one "Not applied: …". The same failures are written to `latest.log` once per helper run.
- **Undo this**: undo a single History entry, not only the last apply or everything. You see the usual confirmation list first. A change that a later apply changed again is skipped ("Changed again by a later apply"), together with the rest of its group. **Undo last** and **Undo all** moved from the main screen into History.
- **Memory steps for your launcher**: RigTune recognises the Modrinth App, Prism Launcher, ATLauncher, the CurseForge app and the official Minecraft Launcher. It names the launcher in the header ("Memory 2.0 GB of 16 GB, set in the Modrinth App") and adds that launcher's click steps under every memory advice. It detects the launcher on your PC only: from a few named system properties and environment variables the launchers set, and, for Prism and CurseForge, their instance files in the game folder or the folder above it. The Modrinth App, Prism and ATLauncher steps are taken from the launchers' own source code; CurseForge's and the official launcher's come from CurseForge's blog and third-party guides. An unrecognised launcher gets the generic advice, as before. **Copy report** adds the launcher's name and nothing else about it.
- **Report a problem**: copies the full report to your clipboard and shows Minecraft's own "open this link?" screen with a link to a new GitHub issue, with the title and report field filled in from the link (your versions and as much of the report as fits). RigTune sends nothing itself; nothing is posted until you submit the issue on GitHub.
- **Preview** (next to Apply): lists exactly what Apply would do for the ticked items, file by file: settings written now (old → new), Sodium, Distant Horizons and Iris keys changed at the next restart, mod files downloaded or renamed to `.disabled`, and anything Apply would skip, with the reason. It writes and downloads nothing; with Modrinth on, it only looks up versions and dependencies, as Apply would.
- **Shader advice** on the benchmark result: when the shader cost was measured, you reach your target only with shaders off, and the pack takes at least 10% of your 1% lows, it suggests a lighter profile in the pack's own settings. It changes nothing in Iris or the pack.
- **spark hint**: with the spark profiler mod installed, an info advice explains how to capture a profile (`/sparkc profiler start`, then `stop`) and that stopping uploads it, with your player name and UUID, mod list, system details and Java launch arguments, to spark.lucko.me behind a link anyone with it can open.
- New benchmark runs record their context in `benchmarks.json` (Distant Horizons and shaders on or off, the shader pack, the window size, fullscreen), so later versions can compare like with like.
- **Translations**: a translator guide in the README ("Translating RigTune"); text RigTune builds itself (recommendation titles, undo, download and preview messages) now goes through translation keys; a test checks every language file's keys and arguments. RigTune still ships in English only. The recommendation text from the rules file, mod names, **Copy report** and **Report a problem** stay English.
- For contributors and maintainers:
  - client game tests run in CI on Linux for every supported version (26.2 on OpenGL; 26.3 on OpenGL and on Vulkan; Mesa software rendering under Xvfb), on the jar that ships (`runProductionClientGameTest`), with screenshots and logs kept for each leg;
  - `tools/add_mc_version.py` adds a Minecraft version in one step after checking Mojang's version manifest, Fabric, Fabric API and Mod Menu; `tools/mc_apidiff.py` checks every Minecraft, Fabric and Mod Menu member RigTune uses against another version (see `tools/MC_VERSIONS.md`);
  - the release workflow publishes every version in `versions/`, uploads to Modrinth the same files it attached to the GitHub release, and refuses a tag that doesn't match `mod_version`.

### Changed
- The RigTune screen's buttons: **History…** replaces Undo last and Undo all, **Preview** sits after Apply, and **Report a problem** after Copy report.
- Benchmark in your own world: Tune tests at most 8 render distances above the one you start at, because every distance it tests makes the game load, generate and save more of your world; the benchmark menu says so. The benchmark world keeps the full range.
- The benchmark world's camera is 16 blocks above the terrain (y 133 on 26.2 and 26.3); it was 10 blocks above the highest block at that spot, which depended on a tree. A game test checks on every supported version that the scene isn't mostly ocean, flat, or a single biome.
- Hardware tiers: the RX 9070 GRE is tier 4 (was 5, through the RX 9070 row); the Arc Pro B50 is tier 3 and the Arc Pro B60/B65/B70 tier 4 (were Intel's fallback, tier 2), both marked as discrete GPUs; the RTX 5050 has its own tier-3 row (same tier as before). RigTune 0.1.x keeps its old classification for all of them. These cards' exact driver strings weren't seen in a real log; a card that reports a different name is classified as before.
- The "Experimental Vulkan renderer" advice is shown only before Minecraft 26.4, which makes Vulkan the default.
- "Give Minecraft more memory" now says "at least 4 GB", so it no longer contradicts the Distant Horizons and shader memory advice shown with it.
- Modrinth lookups ask for the exact Minecraft version string the game reports (it differs from Fabric's normalised one only on snapshots and pre-releases); the rules still use the normalised version.
- The rules updater targets the supported Minecraft versions (26.2 and 26.3) and their hotfixes, instead of the three newest releases. Rules revision 13.

### Fixed
- **Benchmark render distances above your starting one were measured on the starting terrain.** RigTune changed render distance in memory only, so the game's server never sent the extra chunks, and Tune could choose a render distance your PC can't hold. Every step, and the restore, now tells the server the new distance, then waits until the chunks around you (out to one less than that distance) have arrived and been built, for 20 s at most. A step that timed out with more than 2% of them missing doesn't count as reaching your target and is marked "✘*".
- A stale jar in `mods/update/` no longer cancels a staged addition, or an undo, of a mod that isn't loaded. RigTune now cancels its staged change only for a loaded mod whose own update is waiting there (the notice says "change"), and the ticks on the RigTune screen follow what is really still staged.
- Modrinth "incompatible" checks judge the mods as they'll be after the updates you ticked: updates are planned before additions; an addition that clashes only with a mod's old version goes in with that mod's update (both apply or neither); an update's own "incompatible" entries, and those of mods you already have, are checked too. An update whose Modrinth data changed since the list was made is refused until you try again.
- Those messages name the other mod (its title, else its slug, else "another mod") instead of a Modrinth version id.
- A failed change no longer shows two different counts in one line: the helper's own retries stay in its message, and RigTune's restarts read "try 2 of 3 at restart" (History) or "restart attempt 2 of 3" (`latest.log`).
- After applying staged changes, the toast says "RigTune's staged changes were applied." (it said "Mod files and Sodium settings were updated." for Distant Horizons and Iris changes too).
- The benchmark chart's dates are in local time (they were UTC).
- The first 0.3 start after 0.1.x no longer logs a stack trace for each jar named in 0.1.x's last apply that is gone now.
- CI: a malformed version folder name can no longer split a game-test command.

### Compatibility
- 0.3.0 changes no file format. Every file 0.1.0 and 0.2.0 wrote keeps working, and a downgrade to 0.2.0 keeps working too: 0.2.0 ignores the two new optional fields (`context` on new benchmark runs, `lastWarnedApply` in `rigtune.json`) and drops them when it rewrites the file, so after a downgrade and re-upgrade the failed-change lines may be logged once more.
- A 0.1.x client never gets an unsafe or less conservative recommendation from `rules-v1.json`: its hardware tables are still 0.1.0's, and the only recommendation change it sees is the "at least 4 GB" wording. 0.2.x gets the other rules changes (hardware rows, spark hint, Vulkan advice) through `rules-v2.json`.

### Known issues
- On some Windows machines vanilla Minecraft 26.3 crashes natively during startup (around the time its sound system starts), with or without RigTune. It can take a few relaunches.
- An installed 0.1.0 or 0.2.0 is offered the 0.3.0 update only once the Modrinth listing is approved: RigTune finds its own update through Modrinth, which doesn't list versions of a project that is still in review.
- Quilt isn't supported (see the [README's FAQ](https://github.com/chaotix345/rigtune#does-rigtune-work-with-quilt)).
- MultiMC and other Prism-family launchers are named Prism Launcher and get Prism's steps. Other launchers (GDLauncher, for example) aren't recognised and get the generic advice.
- The incompatibility checks don't see changes an earlier Apply already staged: an addition that clashes only with the old version of a mod whose update is already waiting for a restart is refused until after that restart, and the opposite case isn't caught. Of two updates Modrinth marks incompatible with each other, one is refused.
- Preview can't show what Apply only learns once a file is downloaded (a file that isn't a Fabric mod, or a mod you already have, is left out; the screen says so), and Modrinth's answers can change between Preview and Apply.
- History names mod files, not mod names: the history file records only the file and the mod id.
- Measuring the shader cost turns shaders off and on through Iris, which re-saves `config/iris.properties` and the active pack's settings file with a new date line; every value stays the same. Iris also re-saves `iris.properties` at every game start.

## [0.2.0] - 2026-09-25

### Added
- Multi-version support: Minecraft Java **26.3**, alongside 26.2, built from one source tree with Stonecutter. Each version ships its own jar (`rigtune-<version>+mc26.2.jar` / `+mc26.3.jar`).
- Rules schema v2 (`rules-v2.json`): new condition fields (`gpuModelMatches`, `displayPixelsAtLeast`/`AtMost`, `modVersion`, `mcVersionRange`), `settingLabels`, and fail-closed evaluation (an unknown condition field never makes a rule fire). A safe v1 projection (`rules-v1.json`) keeps existing 0.1.x installs on a conservative subset, never less safe than before.
- **Undo RigTune**: every change RigTune makes (settings, mod installs/updates/disables, config patches) is journaled to `config/rigtune/history.json`. "Undo last apply" and "Undo everything" show a confirmation screen of exactly what will be reverted (and what will be skipped, with a reason), then revert it — immediately for settings, after a restart for mod/file changes, through the same safe post-exit helper as Apply.
- **Benchmark v2**: a dedicated benchmark world (no save required, reachable from the title screen) for repeatable results; simulation distance tuning alongside render distance (singleplayer); a **Measure before / Measure after** mode that reports the real 1% low/average gain from a change; Distant Horizons and shader cost reports (measured, not auto-tuned); a small chart of recent runs on the result screen.
- **Distant Horizons and Iris settings**: RigTune reads and patches `config/DistantHorizons.toml` and `config/iris.properties` (new `PATCH_TOML`/`PATCH_PROPERTIES` staged operations, type-preserving), and recommends DH/Iris values by hardware tier alongside Sodium and vanilla settings.
- **Settings screen** (`config/rigtune/settings.json`, opened from the RigTune screen or Mod Menu's config button): a master network switch, separate switches for remote rules and Modrinth (lookups/downloads), a startup-toast switch, default goal, and benchmark scene. Turn network off and RigTune falls back to on-device advice with zero requests.
- **Copy report**: a button that puts a Markdown summary of your hardware, recommendations and latest benchmark on the clipboard, with no file paths or user names.
- The RigTune project was created on [Modrinth](https://modrinth.com/mod/rigtune) and submitted for review; a v0.1.0 version was uploaded there too so existing 0.1.0 installs can find the 0.2.0 update.

### Changed
- Nvidium's recommendation now checks the GPU model directly (a Turing-or-newer allowlist) instead of a raw hardware-tier floor, so laptop RTX 20-series, RTX 3050 and GTX 16-series GPUs are correctly offered Nvidium; being a beta renderer pinned to an exact Sodium build, it's now opt-in (unticked by default) rather than ticked automatically.
- 32 upstream mods were triaged and marked reviewed; LambDynamicLights is now tracked (an unticked, never force-recommended "disable" suggestion on weak GPUs) instead of unseen.
- Ixeris keeps its "always recommend" rule on both versions; its reason notes that its raw-input batching gain isn't ported to 26.3 yet.

### Fixed
- A mod update whose downloaded jar isn't a readable Fabric mod is refused (the installed jar stays), and at apply time every jar RigTune enables is re-checked to be the mod it claims to be.
- A malformed or oversized `fabric.mod.json` in any jar in `mods/` can no longer crash the post-exit helper (it only fails that jar's own check), and a crafted jar can no longer exhaust the game's memory when the Undo screen reads it.
- RigTune no longer offers its own update for a mod that already has an update of its own waiting in `mods/update/` (e.g. Distant Horizons' self-updater); it shows a note instead, and cancels an update of that mod it had already staged.
- Two mods that conflict with each other are never offered or installed together, and Modrinth's "incompatible" dependencies are honoured (one naming a specific version only for that version).
- RigTune no longer offers to update Distant Horizons while DH's own auto-updater is on (the two updaters raced to replace the same jar at exit, which made a real update fail). It shows an info note instead.
- Distant Horizons render-distance caps now apply only while DH rendering is on, and DH thread caps only when Chunky isn't installed.
- A benchmark measured in a throttled or unfocused window (e.g. with Dynamic FPS) is stopped and not saved.
- **The post-exit helper retries a locked file for much longer.** Real-world feedback: a large mod jar (e.g. Distant Horizons) still open a few seconds after Minecraft exits (the Modrinth App re-scanning the instance, or an antivirus scan) made the helper give up after its old ~3 s of retries and leave the change pending. It now backs off exponentially (300 ms, doubling, capped at 5 s) over a ~30 s budget for this kind of sharing violation, and waits 2 s (was 1 s) after the game process exits before it even tries.
- **A startup race that could leave RigTune "Offline".** If the hardware/mod scan finished before the local rules were loaded (or a remote rules fetch tied the local revision), RigTune could skip its Modrinth lookups entirely until the player pressed Rescan. Lookups are now triggered by whichever of the scan and the rules load finishes last.
- A null/unreadable mod id on a staged mod-enable operation no longer bypasses the duplicate-mod-id safety check; such a file is rejected instead of being installed unvalidated.
- `sodium.*` setting keys from the rules file are now validated against the same safe character set as `dh.*`/`iris.*`, closing a defense-in-depth gap (not reachable through the shipped recommendation flow, but hardened anyway).
- `-Drigtune.rules.baseUrl` (test-only) now requires https, except on localhost, matching the existing Modrinth test property.
- Restored a 0.1.x safety check that had been narrowed during v2 rules development: `rules-v1.json`'s Nvidium "disable" suggestion for weak NVIDIA GPUs is exactly as conservative as it was in 0.1.0.

### Compatibility
- A 0.1.x client never gets an unsafe recommendation from `rules-v1.json`.
- Every file 0.1.0 wrote (`pending.json`, `last-apply.json`, `rules-cache.json`, `rigtune.json`, `helper/`) keeps working after an upgrade to 0.2.0; a one-time "legacy import" records 0.1.0's last apply into the new undo history.
- `config/rigtune/settings.json` is a new, separate file, so a downgrade back to 0.1.x (which only knows `rigtune.json`) can't reset the new network switches on a later re-upgrade.

### Known issues
- On some Windows machines vanilla Minecraft 26.3 crashes natively while starting its sound system (OpenAL), with or without RigTune. Relaunching usually works.
- A GitHub-installed 0.1.0 is offered the 0.2.0 self-update only once the Modrinth listing is approved (RigTune finds its own update through Modrinth).
- If you downgrade to 0.1.x with Distant Horizons or Iris setting changes still staged, the 0.1.x helper can't apply them and drops them after 3 exits; 0.1.x also ignores the new network switches.
## [0.1.0] - 2026-09-24

### Added
- Initial release, for Minecraft Java 26.2.
- Hardware detection: CPU, RAM, RAM allocated to Minecraft, GPU (model, VRAM, driver, OpenGL or Vulkan), monitor resolution and refresh rate, and whether the PC is a laptop running on battery.
- Mod scanning: detects installed performance mods, mods that are obsolete or conflicting, and available Modrinth updates.
- Recommendations, each with a plain-English reason and an impact rating: performance mods suited to the detected hardware, mods to disable, mod updates, and vanilla/Sodium video settings for the detected hardware tier and chosen goal (Performance / Balanced / Quality).
- In-game benchmark: sweeps render distance, measures real frame times (average and 1% lows, FPS cap lifted), and finds the highest render distance the PC can hold at the monitor's refresh rate.
- Safe apply: video settings take effect immediately; mod installs/updates/disables and Sodium config changes are staged and applied by a helper process after Minecraft closes; disabled mods become `.jar.disabled` rather than being deleted; every download is SHA-512 verified against Modrinth.
- A rules file fetched from GitHub at startup (with a bundled offline fallback) drives recommendations without requiring a mod update; a weekly GitHub Action rebuilds it from live Modrinth data.
