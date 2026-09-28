RigTune reads your PC's hardware and recommends the performance mods, mod settings and video settings that suit it. An in-game benchmark then tunes your render distance to your monitor, and a set of tools keeps an eye on things afterwards: profiles, a stutter report with one-click fixes, measured tries of a single setting, Java advice, your benchmark history and your launch time.

## What it does

- **Reads your hardware**: CPU, RAM, the RAM allocated to Minecraft, GPU (model, VRAM, driver, OpenGL or Vulkan), monitor resolution and refresh rate, and whether you're a laptop running on battery.
- **Scans your mods**: it knows which performance mods you already have, which ones are obsolete or conflicting, and which have updates on Modrinth, including your Distant Horizons and Iris settings.
- **Recommends changes**, each with a plain-English reason and an impact rating:
  - performance mods that fit *your* hardware
  - mods to disable, and updates for mods you already have
  - vanilla, Sodium, Distant Horizons and Iris settings for your estimated hardware tier and goal (Performance / Balanced / Quality)
  - advice on things outside the game, such as RAM allocation, running on battery, GPU driver workarounds and known-bad drivers
- **Knows your launcher**: in the Modrinth App, Prism Launcher, MultiMC, ATLauncher, GDLauncher, the CurseForge app or the official Minecraft Launcher, the memory and Java-arguments advice comes with that launcher's click steps. A launcher that keeps its own list of your mods keeps control of your mod files (see below).
- **Benchmarks in game**: measures real frame times (average and 1% lows, FPS cap lifted) and tunes render distance, and simulation distance in singleplayer, to your monitor's refresh rate. It waits for each render distance's terrain to load before measuring it. A dedicated benchmark world gives repeatable results without needing your own save. Distant Horizons and shaders get a cost report, rather than being auto-tuned. A "measure before/after" mode reports the real gain from any change you made.
- **History and Undo**: see everything RigTune changed, newest first, with the status of each change (and why one failed, if it did). Undo one entry, the last apply or everything, immediately or after the next restart.
- **Applies safely**:
  - **Preview** shows exactly what Apply would change, file by file, before you press it
  - video settings take effect immediately
  - mod installs, updates, disables and config changes are staged, then applied by a small helper after Minecraft closes; if the helper is interrupted, the next run finishes the change or puts the old jar back
  - updates respect the version requirements of your other mods
  - nothing is ever deleted: disabled mods become `.jar.disabled`
  - every download is checked against Modrinth's hash

## New in 0.5

- **RigTune never fights your launcher.** The Modrinth App, the CurseForge app, ATLauncher and GDLauncher keep their own list of an instance's mods, and so do Prism Launcher and PolyMC once they've installed mods (packwiz metadata in `mods/.index/`). There, RigTune's mod installs, updates and disables become advice with the launcher's own click steps, and Undo leaves mod files an older RigTune changed to the launcher too. Settings still apply in one click. **Settings → Mod files → Let RigTune change them anyway** brings the old behaviour back for that instance.
- **Try it (measured)**: tick one setting in Preview and press **Try it (measured)**. RigTune measures your game, applies the change, measures again in the same place, and shows how the 1% lows and the average moved against a noise floor; then you keep it or revert it. Sodium, Distant Horizons and Iris settings are measured across a restart. When anything else changed between the two runs, there's no verdict and both numbers are shown.
- **Stutter Doctor one-click fixes**: when its advice points clearly at Sodium's Chunk Updates, render distance or Distant Horizons' thread count, the Stutter Doctor can offer the fix: a preview, then an ordinary Apply you can undo. It then compares your next play sessions under the same conditions and says less stutter, no clear change or more stutter, always with both numbers: a measured comparison, not proof.
- **Profiles for servers**: RigTune can offer a profile you chose when you join a server, a LAN game or a Realm. It never switches by itself.
- **A guide for your first Apply**: new players see what Apply changes and how to undo it before they press it, and afterwards a list of that Apply's changes, in effect now or at the next restart, with **Undo this Apply**.
- **Launch-time alerts**: when a launch is noticeably slower than your usual, RigTune says so once, with the numbers and what changed before it (the mod count, the mod set or RigTune's version) as "may be related". On a Windows PC whose performance counters are switched off, Tools explains that setting and its cost at launch; RigTune never changes it.
- **Settings changed outside the game**: RigTune notices when settings it applied were changed outside the game since you last played (for example by the Modrinth App's game-settings sync) and offers to apply its values again. It never reverts anything by itself.
- **Keyboard and Narrator**: the benchmark result's table and charts can now be reached with Tab and are read aloud.
- **Fixes**, among them: the benchmark no longer suggests a lower render distance because the terrain was still generating, and a first run in a new benchmark world or with Distant Horizons generating terrain is left out of your usual; the Stutter Doctor no longer counts AFK idling as gameplay; RigTune no longer claims a mod your launcher installed as its own change; History pairs 0.1.0's updates. The full list is in the changelog.

## Using an older RigTune with the Modrinth App, CurseForge, ATLauncher or Prism?

RigTune 0.1-0.4 changed mod files itself. In the Modrinth App (and other launchers that keep their own mod list), when RigTune updated or added mods, the app's **Update** or **Update all** can fail with "The updated filename belongs to another content item". To fix it:

1. Do this **in the app**, not in File Explorer: the app keeps its own list.
2. Open the instance → **Content** → filter **Disabled**.
3. Select the old copy of each mod RigTune updated → **Delete**. RigTune's newer versions stay and keep working. If an old copy isn't listed, the app has already hidden it: nothing to do.

Or, to let the app own every file: delete RigTune's new copy, update the old one, then switch it on. Mods that update themselves (such as Distant Horizons' own updater) can cause the same problem.

RigTune 0.5 no longer changes mod files in these launchers; it tells you what to change there, and a notice lists the old copies from RigTune's own records with your launcher's steps (**Copy list** copies the file names). RigTune never deletes them itself. Older RigTune versions show a warning with these steps too.

If the Modrinth App's game-settings sync is on, the app copies `options.txt` between your synced instances, so RigTune's setting changes reach them too. To stop it: App settings → Synced settings → Sync game options, or the instance → Instance settings → Sync overrides → Unsync game settings.

The launchers' button names come from their source code and locale files (CurseForge's from its help pages), not from the running apps.

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

RigTune's settings screen lets you turn network access off entirely, or turn off just Modrinth lookups/downloads, the remote rules file, or the startup suggestions toast; with network off, recommendations become advice ("install it from your launcher") instead of one-click actions. With Modrinth on, Preview reads the start of each listed download's metadata from Modrinth's CDN (a few hundred KiB at most per file, nothing saved), to show what Apply would refuse; nothing is downloaded until you press Apply.

The tools add no other network request. What they keep stays in your `config/rigtune` folder: your profiles and the servers you asked for a profile offer (server addresses aren't stored in readable form), the Stutter Doctor's session summaries and the fixes you tried, your measured tries, what RigTune last saw of your hardware and your settings, and your recent launch times. Share codes and the Stutter Doctor's summary go to your clipboard only when you press their button, and RigTune reads your clipboard only when you press Paste.

Launcher detection happens on your PC only, from a few named launcher properties and files and the file names in the mods folder's `.index/` folder; RigTune never reads a launcher's database, and only the launcher's name goes into the report you copy. **Report a problem** sends nothing itself: it opens a GitHub link in your browser only after you confirm it, and nothing is posted until you submit the issue.

## What's been tested

Recommendations are estimates. Everything measured was measured on one PC (Ryzen 7 7800X3D, Radeon RX 7800 XT, 32 GB); for other hardware RigTune picks settings from its hardware tables, and automated tests check that the rules give the intended recommendations on hardware it wasn't run on, not that they make the game faster there. The thresholds behind the one-click fixes, Try it's noise floor and the launch-time alerts come from that PC and one player's play and launches. Server features were tested against a local server (not the real Realms service), and the battery offer with simulated batteries. A benchmark on your own PC is the stronger evidence. Details are in the [README](https://github.com/chaotix345/rigtune#what-has-been-verified).

## Source and credits

- Source code: [github.com/chaotix345/rigtune](https://github.com/chaotix345/rigtune) (MIT license)
- Mod lists used as input data: [Fabulously Optimized](https://github.com/Fabulously-Optimized/fabulously-optimized) (BSD-3-Clause) and [Additive](https://github.com/skywardmc/additive) (MIT).
- Mod data from the [Modrinth API](https://docs.modrinth.com/api/).
- Driver workaround details come from [Sodium](https://github.com/CaffeineMC/sodium).
