RigTune reads your PC's hardware and your installed mods, and recommends the performance mods, mod settings and video settings that suit them, each with a plain-English reason. Nothing changes until you press **Apply**, and History can undo every change. An in-game benchmark then tunes your render distance to your monitor.

Client-side only, for **Fabric** on Minecraft Java **26.2** and **26.3**. It isn't needed on servers.

## What it does

- **Reads your hardware**: CPU, RAM, the memory given to Minecraft, GPU and driver, OpenGL or Vulkan, your monitor's resolution and refresh rate, and whether a laptop is on battery. From these it estimates a hardware tier (1 to 5) and shows what the estimate rests on.
- **Scans your mods**: which performance mods you already have, which are obsolete or conflict with each other, and which have updates on Modrinth.
- **Recommends changes, each with a reason and an impact rating**:
  - performance mods that fit your hardware (for example, Nvidium only on NVIDIA GPUs that support it);
  - mods to turn off, such as obsolete ones or two mods that conflict;
  - updates for mods you have;
  - vanilla, Sodium, Distant Horizons and Iris settings for your tier and goal (Performance, Balanced or Quality);
  - advice it can't apply for you, such as how much memory to give Minecraft (with your launcher's steps), running on battery, known driver problems and heavy shaders.
- **Applies only what you tick**: **Preview** shows exactly what Apply would change. Video settings change at once. Mod downloads, updates, disables and Sodium, Distant Horizons or Iris config changes are staged and applied by a small helper after Minecraft closes, because Windows keeps loaded mods locked. Nothing is deleted (a disabled mod becomes `.jar.disabled`), and every download is checked against Modrinth's SHA-512 hash.
- **History and Undo**: every Apply, benchmark result and profile switch is listed in History. You can undo one entry, the last one, or everything.
- **Benchmarks in game**: it measures real frame times (average and 1% lows, with the frame cap lifted) at several render distances, and finds the highest one your PC holds at your monitor's refresh rate. It runs where you stand or in its own benchmark world, which gives repeatable runs. **Measure before / after** shows what a change did, and the benchmark history says when your 1% lows drop below your usual.
- **Try it (measured)**: tick one setting and RigTune measures, applies it, measures again in the same place, and says whether it helped, against a noise floor of at least 5%. Then you keep it or revert it.
- **Profiles**: Max FPS, Balanced, Quality, Battery and Recording, plus your own. Switching is one click (and undoable), and a profile can be shared as a short code. It can also offer a profile when you join a server you chose it for; it never switches by itself.
- **Stutter Doctor** (opt-in): records frame times while you play, on your PC only, and shows where the hitch time went: garbage collection, chunk loading or building, or game ticks. When a session clearly points at one setting, it offers a fix to apply and compares your next sessions.
- **Java and memory advice**: notes Java arguments that Java ignores or that cost a game client, with where your launcher keeps them; how much memory to give Minecraft is in the main list's advice. It never asks you to add garbage-collector flags.
- **Stays current without updating the mod**: the recommendations come from a rules file in the GitHub repository, rebuilt weekly from Modrinth data and the Fabulously Optimized and Additive mod lists, and reviewed before it goes live.

## What it doesn't do

- It doesn't change anything until you press Apply, and it never changes anything on a server.
- It doesn't promise FPS. Its settings are estimates for your hardware tier, from tables. Your own benchmark, or Try it, shows what a change actually does on your PC.
- It doesn't edit your launcher's files or database, and it leaves mod files to launchers that keep their own list (see Compatibility), unless you turn on **Settings → Mod files → Let RigTune change them anyway**.
- It doesn't send telemetry.

## Requirements

- Minecraft Java 26.2 or 26.3. Download the file for your version: `rigtune-<version>+mc26.2.jar` or `rigtune-<version>+mc26.3.jar`.
- Fabric Loader 0.19.5 or newer, [Fabric API](https://modrinth.com/mod/fabric-api) and Java 25 or newer.
- Optional: [Mod Menu](https://modrinth.com/mod/modmenu), where RigTune is listed with a config button.

## Compatibility

- **Sodium**: RigTune reads and suggests Sodium's settings, and adds its button to Sodium's video settings screen. Sodium setting changes apply at the next restart.
- **Iris**: RigTune knows when shaders are on, suggests Iris's shadow distance on lower tiers, and the benchmark reports what your shaders cost you.
- **Distant Horizons**: RigTune suggests Distant Horizons' own quality and thread settings for your tier, keeps vanilla render distance moderate while Distant Horizons draws the far terrain, and reports its cost in the benchmark. While Distant Horizons is generating terrain, benchmark and Try it results are marked as such and left out of comparisons. In a launcher-managed instance it suggests turning off Distant Horizons' own auto-updater, so your launcher updates it instead.
- **Launchers that keep their own mod list**: the Modrinth App, the CurseForge app, ATLauncher, GDLauncher, and Prism or PolyMC when they keep packwiz metadata (`mods/.index/`). There, RigTune leaves mod files to the launcher: installs, updates and disables become advice with that launcher's own steps, and settings still apply in one click. With the official launcher, MultiMC, or Prism without that folder, RigTune makes the mod changes itself, as above.
- **Coming from RigTune 0.1-0.4 in the Modrinth App**: if the app's Update all fails because an older RigTune renamed your mods, RigTune lists the old copies and the steps that fix it.

## Network use

RigTune makes two kinds of request, each with its own switch in **RigTune → Settings**, under a master **Network access** switch:

- **Rules updates (GitHub)**: downloads the rules file from this project's GitHub repository.
- **Modrinth**: sends the SHA-1 hashes of your mod jars (to find updates) and the ids of mods it might suggest, through the official Modrinth API. It downloads only the mods you choose to install or update, from Modrinth's CDN.

With the network off (or offline), RigTune uses the rules bundled in the jar or the last downloaded copy, and mod installs and updates are shown as advice to do in your launcher. Your hardware details leave your PC only when you copy or submit a report yourself; **Report a problem** opens a pre-filled GitHub issue in your browser after you confirm, and nothing is posted until you submit it.

## Getting started

Open RigTune from the **RigTune** button on the title screen, the button in **Options → Video Settings**, **F8** in a world, or Mod Menu. Review the list, untick anything you don't want, and press **Apply** (or **Preview** first). If mods or config changed, restart Minecraft; the next launch tells you what was applied. **Tools…** opens the benchmark, Profiles, the Stutter Doctor and the Java and memory advice.

## Links

- Source code (MIT): [github.com/chaotix345/rigtune](https://github.com/chaotix345/rigtune)
- Issues and suggestions: [GitHub issues](https://github.com/chaotix345/rigtune/issues)
- Changelog: [CHANGELOG.md](https://github.com/chaotix345/rigtune/blob/main/CHANGELOG.md)
- Guides: [features](https://github.com/chaotix345/rigtune/blob/main/docs/guide/features.md), [launchers](https://github.com/chaotix345/rigtune/blob/main/docs/guide/launchers.md), [privacy](https://github.com/chaotix345/rigtune/blob/main/docs/guide/privacy.md), and [what has been verified, and known limits](https://github.com/chaotix345/rigtune/blob/main/docs/guide/verification.md)

## Credits

- Mod lists used as input data: [Fabulously Optimized](https://github.com/Fabulously-Optimized/fabulously-optimized) (BSD-3-Clause) and [Additive](https://github.com/skywardmc/additive) (MIT).
- Mod data from the [Modrinth API](https://docs.modrinth.com/api/).
- Driver workaround details come from [Sodium](https://github.com/CaffeineMC/sodium).
