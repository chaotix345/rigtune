RigTune reads your PC's hardware and recommends the performance mods, mod settings and video settings that suit it. An in-game benchmark then tunes your render distance to your monitor, and a set of tools keeps an eye on things afterwards: profiles, a stutter report, Java advice and your benchmark history.

## What it does

- **Reads your hardware**: CPU, RAM, the RAM allocated to Minecraft, GPU (model, VRAM, driver, OpenGL or Vulkan), monitor resolution and refresh rate, and whether you're a laptop running on battery.
- **Scans your mods**: it knows which performance mods you already have, which ones are obsolete or conflicting, and which have updates on Modrinth, including your Distant Horizons and Iris settings.
- **Recommends changes**, each with a plain-English reason and an impact rating:
  - performance mods that fit *your* hardware
  - mods to disable, and updates for mods you already have
  - vanilla, Sodium, Distant Horizons and Iris settings for your estimated hardware tier and goal (Performance / Balanced / Quality)
  - advice on things outside the game, such as RAM allocation, running on battery, GPU driver workarounds and known-bad drivers
- **Knows your launcher**: in the Modrinth App, Prism Launcher, MultiMC, ATLauncher, GDLauncher, the CurseForge app or the official Minecraft Launcher, the memory and Java-arguments advice comes with that launcher's click steps.
- **Benchmarks in game**: measures real frame times (average and 1% lows, FPS cap lifted) and tunes render distance, and simulation distance in singleplayer, to your monitor's refresh rate. It waits for each render distance's terrain to load before measuring it. A dedicated benchmark world gives repeatable results without needing your own save. Distant Horizons and shaders get a cost report, rather than being auto-tuned. A "measure before/after" mode reports the real gain from any change you made.
- **History and Undo**: see everything RigTune changed, newest first, with the status of each change (and why one failed, if it did). Undo one entry, the last apply or everything, immediately or after the next restart.
- **Applies safely**:
  - **Preview** shows exactly what Apply would change, file by file, before you press it
  - video settings take effect immediately
  - mod installs, updates, disables and config changes are staged, then applied by a small helper after Minecraft closes; if the helper is interrupted, the next run finishes the change or puts the old jar back
  - updates respect the version requirements of your other mods
  - nothing is ever deleted: disabled mods become `.jar.disabled`
  - every download is checked against Modrinth's hash

## New in 0.4

Everything below is on the new **Tools…** screen (or the notice line on the RigTune screen), works with the network off, and sends nothing anywhere.

- **Performance Profiles**: switch all your settings in one click between Max FPS, Balanced, Quality, Battery and Recording (worked out for your PC from the same rules as the main list), your own settings, and profiles you saved or imported. A switch is an ordinary Apply, so History shows it and Undo works. On a laptop RigTune offers the Battery profile when you unplug; it never switches by itself.
- **Share codes**: share a profile as a short code (about 100 characters). A code holds only setting values from a fixed list, plus a name: no file names, mods or downloads. Importing one always shows a Preview first.
- **Stutter Doctor**: an opt-in monitor (off by default) that shows where the time went when the game hitches: garbage collection, chunk loading and building, game ticks, or "not explained", always shown. Other things that were happening at the same time (world saves, Distant Horizons' background work) are listed as "not measured", and it suggests what may help. It keeps short summaries of your last 5 sessions (numbers only) on your PC.
- **JVM & memory**: shows the Java your game runs on and notes Java arguments that Java ignores or that only cost memory. It never tells you to add garbage-collector flags: on the PC they were measured on, Java's defaults, the launchers' default sets, Aikar's flags and ZGC gave the same frame rates within run-to-run noise. Your Java arguments are never shown, logged or shared.
- **Benchmark history**: each run is compared with your earlier runs under the same conditions. A real drop in your 1% lows is flagged, with the changes since then listed as "may be related"; if the conditions differ, the cause is "unknown". An old result is marked "needs a rerun" when your game no longer matches it.
- **Notices**: one line tells you when a server limits your view distance (and stops RigTune suggesting more than the server sends), when your GPU or driver changed, when a rules update has new recommendations for you, and when a benchmark shows a drop. Two known-bad driver ranges (NVIDIA and old Intel HD Graphics) get a warning.
- **Honest wording**: the header shows an *estimated* tier and its lowest estimated component instead of saying what "limits" your PC; VSync off is now optional, and its text says what it does.
- **Keyboard and Narrator**: every list row can be reached with Tab and the arrow keys and is read aloud, and RigTune's colours follow Minecraft's High Contrast options.
- **Fixes for 0.3 users**, among them: clicking a row in History works on Minecraft 26.3; a second "Undo last" no longer skips a staged setting; "Undo all" still reaches your original settings after a long history; undo keeps a mod and its library together. The full list is in the changelog.

## Requirements

- Minecraft Java **26.2** or **26.3**
- [Fabric Loader](https://fabricmc.net/use/) 0.19.5 or newer
- [Fabric API](https://modrinth.com/mod/fabric-api)

## Use

Open RigTune from any of these:
- the **RigTune** button on the title screen
- the button in **Options → Video Settings**
- **F8** while in a world
- [Mod Menu](https://modrinth.com/mod/modmenu), if installed

Review the list, untick anything you don't want, and press **Apply** (or **Preview** first). If mods or config changed, restart Minecraft; the next launch tells you what was applied. Made a mistake? Open **History…** from the RigTune screen to undo one apply, the last one, or everything RigTune has done. **Tools…** opens the benchmark, Profiles, the Stutter Doctor, JVM & memory and your benchmark history.

## Privacy and network use

RigTune detects your hardware and recommends performance mods and settings. At your explicit request, it can download recommended mods directly from Modrinth's own servers (via the official Modrinth API and CDN) — every downloaded file's SHA-512 checksum is verified against Modrinth before use. RigTune can also update itself the same way. No files are downloaded, installed, or modified without you clicking to confirm first, and RigTune sends no telemetry or usage data anywhere. A small, static rules file (no executable code) is fetched from GitHub to drive RigTune's hardware-based recommendations.

RigTune's settings screen lets you turn network access off entirely, or turn off just Modrinth lookups/downloads, the remote rules file, or the startup suggestions toast; with network off, recommendations become advice ("install it from your launcher") instead of one-click actions.

The 0.4 tools add no network request. What they keep stays in your `config/rigtune` folder: your profiles, the Stutter Doctor's session summaries, the last view distance each server allowed (server addresses aren't stored in readable form), what RigTune last saw of your hardware, and your recent launch times. Share codes and the Stutter Doctor's summary go to your clipboard only when you press their button, and RigTune reads your clipboard only when you press Paste.

Launcher detection happens on your PC only, from a few named launcher properties and files; only the launcher's name goes into the report you copy. **Report a problem** sends nothing itself: it opens a GitHub link in your browser only after you confirm it, and nothing is posted until you submit the issue.

## What's been tested

Recommendations are estimates. Everything measured was measured on one PC (Ryzen 7 7800X3D, Radeon RX 7800 XT, 32 GB); for other hardware RigTune picks settings from its hardware tables, and automated tests check that the rules give the intended recommendations on hardware it wasn't run on, not that they make the game faster there. A benchmark on your own PC is the stronger evidence. Details are in the [README](https://github.com/chaotix345/rigtune#what-has-been-verified).

## Source and credits

- Source code: [github.com/chaotix345/rigtune](https://github.com/chaotix345/rigtune) (MIT license)
- Mod lists used as input data: [Fabulously Optimized](https://github.com/Fabulously-Optimized/fabulously-optimized) (BSD-3-Clause) and [Additive](https://github.com/skywardmc/additive) (MIT).
- Mod data from the [Modrinth API](https://docs.modrinth.com/api/).
- Driver workaround details come from [Sodium](https://github.com/CaffeineMC/sodium).
