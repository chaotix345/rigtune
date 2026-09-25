<p align="center"><img src="src/main/resources/assets/rigtune/icon.png" width="128" alt="RigTune icon"></p>

<h1 align="center">RigTune</h1>

<p align="center">A Fabric mod that reads your PC's hardware and recommends the performance mods, mod settings and video settings that suit it. An in-game benchmark then tunes your render distance to your monitor.</p>

<p align="center">Minecraft Java 26.2 / 26.3 · Fabric · client-side</p>

---

## What it does

- **Reads your hardware**: CPU, RAM, the RAM allocated to Minecraft, GPU (model, VRAM, driver, OpenGL or Vulkan), monitor resolution and refresh rate, and whether you're a laptop running on battery.
- **Scans your mods**: it knows which performance mods you already have, which ones are obsolete or conflicting, and which have updates on Modrinth, including your Distant Horizons and Iris settings.
- **Recommends changes**, each with a plain-English reason and an impact rating:
  - performance mods that fit *your* hardware. Nvidium is only suggested on NVIDIA GPUs, and RenderScale only on weak or integrated GPUs.
  - mods to disable: Indium or Starlight on modern Sodium, or C2ME and Moonrise installed together
  - updates for mods you already have
  - vanilla, Sodium, Distant Horizons and Iris settings for your hardware tier and goal (Performance / Balanced / Quality)
  - advice on things outside the game, such as RAM allocation, running on battery, GPU driver workarounds and heavy shaders
- **Benchmarks in game**: it measures real frame times (average and 1% lows, with the FPS cap lifted) and tunes render distance, and simulation distance in singleplayer, to your monitor's refresh rate. A dedicated benchmark world gives repeatable results without needing your own save. Distant Horizons and shaders get a cost report (what they're costing you), rather than being auto-tuned. A **Measure before / after** mode reports the real gain from any change you make, with a small chart of your recent runs.
- **Undo RigTune**: every change RigTune makes — settings, mod installs, mod disables — is logged, and you can undo the last apply or everything RigTune has done, one confirmation screen at a time. Reverts that need a restart are applied the same safe way as Apply.
- **Applies safely**:
  - video settings take effect immediately
  - mod installs, updates, disables and config changes (Sodium, Distant Horizons, Iris) are staged, then applied by a small helper after Minecraft closes (Windows keeps loaded mods locked)
  - nothing is ever deleted: disabled mods become `.jar.disabled`, so you can re-enable them in your launcher
  - every download is checked against Modrinth's SHA-512 hash
- **Lets you control what leaves your PC**: a settings screen (also reachable from Mod Menu) with separate switches for remote rules, Modrinth lookups/downloads and the startup toast. Turn any of them off and RigTune falls back to on-device advice with no network request.
- **Copy report**: puts a short Markdown summary of your hardware, recommendations and latest benchmark on your clipboard, ready to paste into Discord or an issue. No file paths or user names.
- **Stays up to date without mod updates**: the recommendations come from a rules file in this repo that the mod fetches at startup. A weekly GitHub Action rebuilds it from live Modrinth data and the mod lists of [Fabulously Optimized](https://github.com/Fabulously-Optimized/fabulously-optimized) and [Additive](https://github.com/skywardmc/additive), and opens a PR for a maintainer to review.

## Screenshots

![RigTune report](docs/images/report.png)

![Benchmark results](docs/images/benchmark.png)

![Undo RigTune](docs/images/undo.jpg)

![Settings screen, network switches](docs/images/settings.jpg)

## Install

1. You need Minecraft Java **26.2** or **26.3**, [Fabric Loader](https://fabricmc.net/use/) 0.19.5 or newer, and [Fabric API](https://modrinth.com/mod/fabric-api).
2. Download the jar matching your Minecraft version — `rigtune-<version>+mc26.2.jar` or `rigtune-<version>+mc26.3.jar` — from [Modrinth](https://modrinth.com/mod/rigtune) or [GitHub Releases](https://github.com/chaotix345/rigtune/releases), and put it in your instance's `mods` folder.
3. [Mod Menu](https://modrinth.com/mod/modmenu) is optional; if you have it, RigTune is also listed there.

## Use

Open RigTune from any of these:
- the **RigTune** button on the title screen
- the button in **Options → Video Settings** (it works with Sodium's video screen too)
- **F8** while in a world
- Mod Menu

Review the list, untick anything you don't want, and press **Apply**. If mods or config changed, restart Minecraft; the next launch tells you what was applied. Made a mistake? Open **Undo last apply** or **Undo everything** from the RigTune screen to revert it, immediately or after a restart.

To run the benchmark, press **Benchmark…** on the RigTune screen. Pick a scene — your current world, or the dedicated benchmark world (no save needed, reachable from the title screen) — and **Tune** or **Measure**. It takes about a minute. Press Esc to cancel; your settings are always restored. In your own world, Tune tests at most 8 render distances above your current one: every distance it tests makes the game load, generate and save that much more of the world.

## Privacy

RigTune has no telemetry. Your hardware details never leave your PC. It makes two kinds of network request, and you can switch each one off in **RigTune → Settings** (or with Mod Menu's config button):

| Switch | What it sends when on | When it's off |
|---|---|---|
| **Network access** (master) | Everything below | No network request of any kind. The RigTune screen says "Offline (network off in settings)". |
| **Rules updates (GitHub)** | A plain download of the rules file from this repository (`rules-v2.json`, or `rules-v1.json` if that fails, from `raw.githubusercontent.com`). | RigTune uses the newer of the rules bundled in the jar and the last downloaded copy. |
| **Modrinth** | To the Modrinth API (`api.modrinth.com`): the SHA-1 hashes of your installed mod jars (to find updates, including RigTune's own), the ids of the mods RigTune might suggest (to check they exist for your version), and your Minecraft version with the loader name `fabric`. From Modrinth's CDN it downloads only the files you choose to install or update, and checks their SHA-512 hashes. | No Modrinth request at all: no lookups, no update checks, no downloads. Mod installs and updates are still listed, as advice to do in your launcher. |
| **Startup suggestions toast** | Nothing (it isn't a network switch) | The "RigTune: N suggestions" toast on the title screen isn't shown. Apply results and warnings still are. |

Every request identifies itself as RigTune and its version (the User-Agent). The switches are stored in `config/rigtune/settings.json`; the first time RigTune 0.2 starts, a one-time toast points at them. With the network off, or when a request fails, RigTune uses the newer of the bundled rules and the last downloaded copy.

**Copy report** on the RigTune screen puts a Markdown summary on your clipboard for you to paste into Discord or an issue: the versions, your hardware, tier and goal, the rules revision, the suggestions' titles and your latest benchmark. It leaves out file paths, user names and world names, and RigTune never sends it anywhere itself.

## How the recommendations stay current

```
rules/source/knowledge.json   hand-written knowledge (conditions, reasons, settings per tier)
        │  tools/update_rules.py  (weekly GitHub Action, or run by hand)
        ▼  + Modrinth: project status and which MC versions each mod supports
        ▼  + Fabulously Optimized and Additive: current mod lists
rules/rules-v2.json           what RigTune 0.2+ downloads (bundled copy in src/main/resources)
rules/rules-v1.json           the same rules as RigTune 0.1.x understands them (a conservative subset)
rules/REVIEW.md               new upstream mods and problems for a maintainer to triage
```

The v2 format (and how it stays safe for 0.1.x readers) is documented in [docs/RULES_SCHEMA.md](docs/RULES_SCHEMA.md). See [tools/README.md](tools/README.md) for the maintainer workflow. Pull requests that improve the knowledge are very welcome.

## Build from source

You need JDK 25. One source tree builds every supported Minecraft version with [Stonecutter](https://stonecutter.kikugie.dev/); each version has a Gradle project named after it (`:26.2`, `:26.3`).

```sh
./gradlew build                     # every version: jars in versions/<mc>/build/libs, unit tests, game tests compiled
./gradlew :26.3:build               # one version only
./gradlew :26.2:runClientGameTest   # in-game tests for one version (opens a game window); run versions one at a time
./gradlew :26.3:runClientGameTest
python -m unittest discover -s tools/tests
```

The few lines that differ between versions are marked with `//? if >=26.3 {` comments. `src/` is always in the state of one active version, which is what your IDE compiles; the others are generated under `versions/<mc>/build/generated/stonecutter/`. To work on another version, switch the active one, and switch back before committing (CI fails if the sources are committed in a switched state):

```sh
./gradlew "Set active project to 26.3"   # rewrites the version comments in src/ for 26.3
./gradlew "Reset active project"          # back to 26.2, the committed version; git diff should then show only your own edits
```

See [docs/DESIGN.md](docs/DESIGN.md) "Porting to new MC versions" for how to add a future version.

## Credits

- Mod lists used as input data: [Fabulously Optimized](https://github.com/Fabulously-Optimized/fabulously-optimized) (BSD-3-Clause) and [Additive](https://github.com/skywardmc/additive) (MIT).
- Mod data from the [Modrinth API](https://docs.modrinth.com/api/).
- Driver workaround details come from [Sodium](https://github.com/CaffeineMC/sodium).

## License

[MIT](LICENSE)
