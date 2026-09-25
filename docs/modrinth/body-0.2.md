<!--
DRAFT — v0.2.0 Modrinth listing body. NOT synced to the live project yet.
Per docs/v0.2/plan-review.md L7/M15: until the 0.2.0 jar is uploaded as a
Modrinth version, the live listing (docs/modrinth/body.md) must describe
only 0.1.0's behaviour. This file is the starting point for the v0.2.0
release; before running `sync-body --body-file docs/modrinth/body-0.2.md`
at release time, a maintainer must:
  - remove this comment block,
  - check every bullet against what v0.2.0 actually shipped (the
    benchmark-world scene, the DH LOD knob and the shader-cost report are
    all candidates the plan review flagged as likely to be cut — see
    docs/v0.2/plan-review.md §5 "Most likely to slip" / "Cut first" — so
    don't describe a knob or scene that didn't ship),
  - confirm 26.3 support actually shipped (or drop it here if 26.3 slipped
    to a later release), and
  - keep the disclosure paragraph in sync with whatever network switches
    v0.2.0 actually ships (ClientSettings: networkEnabled, remoteRules,
    modrinth, startupToast).
-->

RigTune reads your PC's hardware and recommends the performance mods, mod settings and video settings that suit it. An in-game benchmark then tunes your render distance to your monitor.

## What it does

- **Reads your hardware**: CPU, RAM, the RAM allocated to Minecraft, GPU (model, VRAM, driver, OpenGL or Vulkan), monitor resolution and refresh rate, and whether you're a laptop running on battery.
- **Scans your mods**: it knows which performance mods you already have, which ones are obsolete or conflicting, and which have updates on Modrinth, including Distant Horizons and Iris settings.
- **Recommends changes**, each with a plain-English reason and an impact rating:
  - performance mods that fit *your* hardware
  - mods to disable, and updates for mods you already have
  - vanilla, Sodium, Distant Horizons and Iris settings for your hardware tier and goal (Performance / Balanced / Quality)
  - advice on things outside the game, such as RAM allocation, running on battery and GPU driver workarounds
- **Benchmarks in game**: measures real frame times (average and 1% lows) and tunes render distance (and, where applicable, other rendering knobs) to your monitor's refresh rate; a "measure before/after" mode reports the gain from any change you made.
- **Undo**: every change RigTune makes — settings, mod installs, mod disables — can be undone individually or all at once, immediately or after the next restart.
- **Applies safely**:
  - video settings take effect immediately
  - mod installs, updates, disables and config changes are staged, then applied by a small helper after Minecraft closes
  - nothing is ever deleted: disabled mods become `.jar.disabled`
  - every download is checked against Modrinth's hash
- **Lets you control what leaves your PC**: a settings screen with separate switches for the remote rules file, Modrinth lookups/downloads and the startup toast; turn any of them off and RigTune falls back to on-device advice with no network request.
- **Share a report**: copy a short, plain-text summary of your hardware and recommendations to paste elsewhere — no paths or usernames included.
- **Stays up to date without mod updates**: the recommendations come from a rules file in the [GitHub repo](https://github.com/chaotix345/rigtune) that the mod fetches at startup, rebuilt from live Modrinth data by a scheduled GitHub Action.

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

Review the list, untick anything you don't want, and press **Apply**. If mods or config changed, restart Minecraft; the next launch tells you what was applied. Made a mistake? Open **Undo** from the RigTune screen to revert the last apply or everything RigTune has done.

## Privacy and network use

RigTune detects your hardware and recommends performance mods and settings. At your explicit request, it can download recommended mods directly from Modrinth's own servers (via the official Modrinth API and CDN) — every downloaded file's checksum is verified against Modrinth before use. RigTune can also update itself the same way. No files are downloaded, installed, or modified without you clicking to confirm first, and RigTune sends no telemetry or usage data anywhere. A small, static rules file (no executable code) is fetched from GitHub to drive RigTune's hardware-based recommendations.

RigTune's settings screen lets you turn network access off entirely, or turn off just Modrinth lookups/downloads or the remote rules file; with network off, recommendations become advice ("install it from your launcher") instead of one-click actions.

## Source and credits

- Source code: [github.com/chaotix345/rigtune](https://github.com/chaotix345/rigtune) (MIT license)
- Mod lists used as input data: [Fabulously Optimized](https://github.com/Fabulously-Optimized/fabulously-optimized) (BSD-3-Clause) and [Additive](https://github.com/skywardmc/additive) (MIT).
- Mod data from the [Modrinth API](https://docs.modrinth.com/api/).
- Driver workaround details come from [Sodium](https://github.com/CaffeineMC/sodium).
