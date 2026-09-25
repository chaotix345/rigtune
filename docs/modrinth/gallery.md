# Modrinth gallery

Images to upload to the Modrinth gallery, in display order, via `python tools/modrinth_project.py gallery`. `featured: true` marks the one used as the project's spotlight image. `description` is used as the image's alt text (Modrinth requires alt text for gallery images).

Updated for the v0.2.0 release (not yet synced to the live gallery, which still holds the four 0.1.0-era images below the `---`): `report.png`/`benchmark.png` were replaced by real v0.2.0 screenshots from Phase 5 verification (same subjects, current content), and two new images show the v0.2.0-only Undo and Settings screens. A maintainer runs `python tools/modrinth_project.py gallery` at release time to sync this list; Modrinth gallery uploads are additive/by-title, so re-running it after 0.2.0 ships will add the two new images and, for `report`/`benchmark`, needs the old ones removed by hand first (matching titles don't overwrite the underlying file).

## docs/images/report.jpg
- title: RigTune report
- description: The RigTune report screen, showing detected hardware (CPU, GPU, RAM, display), recommended mods and settings each with a plain-English reason and an impact rating, and the Apply button.
- featured: true

## docs/images/benchmark.jpg
- title: Benchmark v2 result
- description: The benchmark result screen after a Tune run, showing the chosen render distance, the measured average FPS and 1% lows against the monitor refresh rate target, and a chart of recent runs.
- featured: false

## docs/images/undo.jpg
- title: Undo RigTune
- description: The Undo everything confirmation screen, listing each change RigTune made that will be reverted.
- featured: false

## docs/images/settings.jpg
- title: RigTune settings, network off
- description: The RigTune screen with the network switches off in Settings, showing the "Offline (network off in settings)" header and mod recommendations shown as advice instead of one-click actions.
- featured: false

## docs/smoke/title.png
- title: RigTune on the title screen
- description: The Minecraft title screen with the RigTune button next to Options and Quit Game.
- featured: false

## docs/smoke/rigtune-in-world.png
- title: RigTune opened in a world
- description: The RigTune screen opened with F8 while in a singleplayer world, showing the Add mods section with per-mod reasons and impact ratings.
- featured: false

---

Live on the Modrinth gallery as of the 0.1.0 submission (2026-09-25), for reference until the above is synced: `docs/images/report.png`, `docs/images/benchmark.png`, `docs/smoke/title.png`, `docs/smoke/rigtune-in-world.png` — the first two were the 0.1.0-era files at those paths, since replaced above.
