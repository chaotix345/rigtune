# Changelog

All notable changes to RigTune are documented here. Dates are UTC. The section
for each release becomes that release's Modrinth changelog (`build.gradle`'s
`changelogForVersion`, matched on the `## [<mod_version>]` heading).

## [Unreleased]

v0.2.0 work is in progress on `feat/v0.2.0`; this section is filled in from
`docs/v0.2/SPEC.md` and renamed to `## [0.2.0] - <date>` when it ships.

## [0.1.0] - 2026-09-24

### Added
- Initial release, for Minecraft Java 26.2.
- Hardware detection: CPU, RAM, RAM allocated to Minecraft, GPU (model, VRAM, driver, OpenGL or Vulkan), monitor resolution and refresh rate, and whether the PC is a laptop running on battery.
- Mod scanning: detects installed performance mods, mods that are obsolete or conflicting, and available Modrinth updates.
- Recommendations, each with a plain-English reason and an impact rating: performance mods suited to the detected hardware, mods to disable, mod updates, and vanilla/Sodium video settings for the detected hardware tier and chosen goal (Performance / Balanced / Quality).
- In-game benchmark: sweeps render distance, measures real frame times (average and 1% lows, FPS cap lifted), and finds the highest render distance the PC can hold at the monitor's refresh rate.
- Safe apply: video settings take effect immediately; mod installs/updates/disables and Sodium config changes are staged and applied by a helper process after Minecraft closes; disabled mods become `.jar.disabled` rather than being deleted; every download is SHA-512 verified against Modrinth.
- A rules file fetched from GitHub at startup (with a bundled offline fallback) drives recommendations without requiring a mod update; a weekly GitHub Action rebuilds it from live Modrinth data.
