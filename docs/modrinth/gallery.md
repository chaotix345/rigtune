# Modrinth gallery

Images to upload to the Modrinth gallery, in display order, via `python tools/modrinth_project.py gallery`. `featured: true` marks the one used as the project's spotlight image. `description` is used as the image's alt text (Modrinth requires alt text for gallery images).

Updated for the v0.3.0 release: one new image, `history.jpg` (the History screen, new in 0.3.0; a copy of the Phase 5 game-test screenshot docs/v0.3/verification/p5/img/a263-history-after-undo.jpg, whose entries are the test's seeded history). The other six images went live with the 0.2.0 release; re-running `gallery` at release time adds only the new one (uploads are by title, additive). `report.png` is still a 0.1-era capture: its footer predates 0.3's History…, Preview and Report a problem buttons, and replacing the live image takes a new title or an edit on Modrinth, since `gallery` skips a title that's already there.

## docs/images/report.png
- title: RigTune report
- description: The RigTune report screen, showing detected hardware (CPU, GPU, RAM, display), recommended mods and settings each with a plain-English reason and an impact rating, and the Apply button.
- featured: true

## docs/images/benchmark.png
- title: Benchmark results
- description: The benchmark results screen, comparing measured average FPS, 1% lows and frame time at two render distances against the player's monitor refresh rate target.
- featured: false

## docs/images/history.jpg
- title: What RigTune changed
- description: The History screen, listing every Apply, benchmark result and Undo newest first with its date, versions and a count of changes, and the selected Undo entry opened to show its change and status, above the Undo this, Undo last, Undo all and Done buttons.
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
