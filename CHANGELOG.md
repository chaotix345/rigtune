# Changelog

All notable changes to RigTune are documented here. Dates are UTC. The section
for each release becomes that release's Modrinth changelog (`build.gradle`'s
`changelogForVersion`, matched on the `## [<mod_version>]` heading).

## [Unreleased]

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
