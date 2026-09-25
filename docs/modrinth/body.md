RigTune reads your PC's hardware and recommends the performance mods, mod settings and video settings that suit it. An in-game benchmark then tunes your render distance to your monitor.

*This listing describes RigTune 0.1.0, currently the only released version (Minecraft Java 26.2 only).*

## What it does

- **Reads your hardware**: CPU, RAM, the RAM allocated to Minecraft, GPU (model, VRAM, driver, OpenGL or Vulkan), monitor resolution and refresh rate, and whether you're a laptop running on battery.
- **Scans your mods**: it knows which performance mods you already have, which ones are obsolete or conflicting, and which have updates on Modrinth.
- **Recommends changes**, each with a plain-English reason and an impact rating:
  - performance mods that fit *your* hardware. Nvidium is only suggested on NVIDIA GPUs, and RenderScale only on weak or integrated GPUs.
  - mods to disable: Indium or Starlight on modern Sodium, or C2ME and Moonrise installed together
  - updates for mods you already have
  - vanilla video settings and Sodium settings for your hardware tier and goal (Performance / Balanced / Quality)
  - advice on things outside the game, such as RAM allocation, running on battery, GPU driver workarounds and heavy shaders
- **Benchmarks in game**: it sweeps the camera at several render distances, measures real frame times (average and 1% lows, with the FPS cap lifted), and finds the highest render distance your PC can hold at your monitor's refresh rate.
- **Applies safely**:
  - video settings take effect immediately
  - mod installs, updates, disables and Sodium config changes are staged, then applied by a small helper after Minecraft closes (Windows keeps loaded mods locked)
  - nothing is ever deleted: disabled mods become `.jar.disabled`, so you can re-enable them in your launcher
  - every download is checked against Modrinth's SHA-512 hash
- **Stays up to date without mod updates**: the recommendations come from a rules file in the [GitHub repo](https://github.com/chaotix345/rigtune) that the mod fetches at startup. A weekly GitHub Action rebuilds it from live Modrinth data and the mod lists of Fabulously Optimized and Additive, and opens a PR for a maintainer to review.

## Requirements

- Minecraft Java **26.2**
- [Fabric Loader](https://fabricmc.net/use/) 0.19.5 or newer
- [Fabric API](https://modrinth.com/mod/fabric-api)

## Use

Open RigTune from any of these:
- the **RigTune** button on the title screen
- the button in **Options → Video Settings** (it works with Sodium's video screen too)
- **F8** while in a world
- [Mod Menu](https://modrinth.com/mod/modmenu), if installed

Review the list, untick anything you don't want, and press **Apply**. If mods or Sodium settings changed, restart Minecraft; the next launch tells you what was applied.

To run the benchmark, stand somewhere typical in your world (singleplayer works best) and press **Run benchmark**. It takes about a minute. Press Esc to cancel; your settings are always restored.

## Privacy and network use

RigTune detects your hardware and recommends performance mods and settings. At your explicit request, it can download recommended mods directly from Modrinth's own servers (via the official Modrinth API and CDN) — every downloaded file's checksum is verified against Modrinth before use. RigTune can also update itself the same way. No files are downloaded, installed, or modified without you clicking to confirm first, and RigTune sends no telemetry or usage data anywhere. A small, static rules file (no executable code) is fetched from GitHub to drive RigTune's hardware-based recommendations.

Concretely, RigTune makes two kinds of network request, and no others:
- **GitHub**: downloads the static rules file from this project's repository.
- **Modrinth API**: sends the SHA-1 hashes of your installed mod jars (to find updates) and the project ids it might suggest, and downloads the mod files you choose to install.

If you're offline, RigTune falls back to the rules bundled in the jar.

## Source and credits

- Source code: [github.com/chaotix345/rigtune](https://github.com/chaotix345/rigtune) (MIT license)
- Mod lists used as input data: [Fabulously Optimized](https://github.com/Fabulously-Optimized/fabulously-optimized) (BSD-3-Clause) and [Additive](https://github.com/skywardmc/additive) (MIT).
- Mod data from the [Modrinth API](https://docs.modrinth.com/api/).
- Driver workaround details come from [Sodium](https://github.com/CaffeineMC/sodium).
