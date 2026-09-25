# Modrinth gallery

Images to upload to the Modrinth gallery, in display order, via `python tools/modrinth_project.py gallery`. `featured: true` marks the one used as the project's spotlight image. `description` is used as the image's alt text (Modrinth requires alt text for gallery images).

Updated for the v0.2.0 release: two new images (Undo, Settings) show v0.2.0-only screens. `report.png`/`benchmark.png` are unchanged file paths and still current (the report and benchmark screens' overall shape didn't change enough to need a reshoot); a maintainer who wants fresher captures can swap their content for a Phase 5 verification screenshot (docs/v0.2/verification/img/) of the same name before running `gallery` at release time. This list isn't yet synced to the live gallery (four 0.1.0-era images, uploaded at the 0.1.0 submission on 2026-09-25); re-running `gallery` after 0.2.0 ships adds the two new images (uploads are by title, additive).

## docs/images/report.png
- title: RigTune report
- description: The RigTune report screen, showing detected hardware (CPU, GPU, RAM, display), recommended mods and settings each with a plain-English reason and an impact rating, and the Apply button.
- featured: true

## docs/images/benchmark.png
- title: Benchmark results
- description: The benchmark results screen, comparing measured average FPS, 1% lows and frame time at two render distances against the player's monitor refresh rate target.
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
