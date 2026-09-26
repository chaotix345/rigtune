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
  - vanilla, Sodium, Distant Horizons and Iris settings for your estimated hardware tier and goal (Performance / Balanced / Quality)
  - advice on things outside the game, such as RAM allocation, running on battery, GPU driver workarounds and known-bad drivers, heavy shaders and Java arguments
- **Knows your launcher**: it recognises the Modrinth App, Prism Launcher, MultiMC, ATLauncher, GDLauncher, the CurseForge app and the official Minecraft Launcher, and gives that launcher's click steps for changing Minecraft's memory, or its Java arguments, under the advice. Other launchers get the general advice.
- **Benchmarks in game**: it measures real frame times (average and 1% lows, with the FPS cap lifted) and tunes render distance, and simulation distance in singleplayer, to your monitor's refresh rate. Each render distance is measured on its own terrain: the benchmark waits for the chunks to load, and a step whose chunks didn't arrive in time doesn't count. A dedicated benchmark world gives repeatable results without needing your own save. Distant Horizons and shaders get a cost report (what they're costing you), rather than being auto-tuned, and if your shader pack is what keeps you below your target, it suggests a lighter profile. A **Measure before / after** mode reports the real gain from any change you make.
- **Keeps a benchmark history**: each run is compared with your earlier runs under the same conditions. When your 1% lows drop below your usual by more than the run-to-run noise, it says so and lists what changed in between as "may be related"; when the conditions differ, it says the cause is unknown.
- **Profiles and share codes**: switch all your settings between Max FPS, Balanced, Quality, Battery, Recording, your own settings and profiles you saved or imported, in one click, and share a profile as a short code (see [below](#profiles-and-share-codes)).
- **Stutter Doctor**: an opt-in monitor that shows where the time went when the game hitches (garbage collection, chunk loading and building, game ticks, or "not explained") and what may help (see [below](#stutter-doctor)).
- **JVM & memory**: shows the Java that runs your game and notes Java arguments that do nothing or cost you; it never suggests adding garbage-collector flags (see [below](#jvm--memory-advice)).
- **Notices what changed**: one line on the RigTune screen tells you when a server limits your view distance, your GPU or driver changed, a rules update has new recommendations for you, a benchmark shows a drop, or your last benchmark needs a rerun.
- **History and Undo**: every change RigTune makes (settings, mod installs, updates and disables, config changes) is logged. The History screen lists them newest first, with the status of each change and, if one failed at the restart, why. Undo one entry, the last apply or everything, one confirmation screen at a time. Reverts that need a restart are applied the same safe way as Apply.
- **Applies safely**:
  - **Preview** shows exactly what Apply would change, file by file, before you press it
  - video settings take effect immediately
  - mod installs, updates, disables and config changes (Sodium, Distant Horizons, Iris) are staged, then applied by a small helper after Minecraft closes (Windows keeps loaded mods locked); if the helper is interrupted in the middle of an update, its next run finishes the update or puts the old jar back
  - an update or addition that another installed mod's version requirements don't allow is refused, naming that mod, and a mod and the library it needs are added, updated or undone together
  - nothing is ever deleted: disabled mods become `.jar.disabled`, so you can re-enable them in your launcher
  - every download is checked against Modrinth's SHA-512 hash
- **Lets you control what leaves your PC**: a settings screen (also reachable from Mod Menu) with separate switches for remote rules, Modrinth lookups/downloads and the startup toast. Turn any of them off and RigTune falls back to on-device advice with no network request.
- **Copy report**: puts a short Markdown summary of your hardware, recommendations and latest benchmark on your clipboard, ready to paste into Discord or an issue. No file paths or user names. **Report a problem** opens a new GitHub issue with your versions and as much of that report as fits filled in (the full report goes on your clipboard), after you confirm the link; nothing is posted until you submit it.
- **Works with the keyboard and the Narrator**: every list row, the notice line and Benchmark history's lines can be reached with Tab and the arrow keys and are read aloud, and RigTune's colours follow Minecraft's High Contrast options.
- **Stays up to date without mod updates**: the recommendations come from a rules file in this repo that the mod fetches at startup. A weekly GitHub Action rebuilds it from live Modrinth data and the mod lists of [Fabulously Optimized](https://github.com/Fabulously-Optimized/fabulously-optimized) and [Additive](https://github.com/skywardmc/additive), and opens a PR for a maintainer to review.

## Screenshots

![RigTune report](docs/images/report.png)

![Benchmark results](docs/images/benchmark.png)

![History: what RigTune changed](docs/images/history.jpg)

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

Review the list, untick anything you don't want, and press **Apply** (or **Preview** first, to see exactly what it would change). If mods or config changed, restart Minecraft; the next launch tells you what was applied. Made a mistake? Open **History…** on the RigTune screen: **Undo this** reverts the selected entry, **Undo last** the last apply and **Undo all** everything RigTune has done, immediately or after a restart. Something wrong? **Report a problem** starts a GitHub issue with your report (see the [FAQ](#what-does-report-a-problem-send)).

**Tools…** on the RigTune screen opens the rest: **Benchmark…**, **Profiles…**, **Stutter Doctor…**, **JVM & memory…**, **Benchmark history…**, and your last launch time.

To run the benchmark, press **Tools…** on the RigTune screen, then **Benchmark…**. Pick a scene — your current world, or the dedicated benchmark world (no save needed, reachable from the title screen) — and **Tune** or **Measure**. It takes about a minute. Press Esc to cancel; your settings are always restored. In your own world, Tune tests at most 8 render distances above your current one: every distance it tests makes the game load, generate and save that much more of the world.

## Profiles and share codes

**Tools… → Profiles…** switches your settings between whole setups in one click:
- **Templates**, worked out for your PC from the same rules as the main list: **Max FPS** (no frame cap, VSync off), **Balanced** (the same values as applying every setting suggestion), **Quality** (one tier higher), **Battery** (60 FPS with VSync, shorter distances, no clouds; shaders and Distant Horizons rendering off when you have them) and **Recording** (a steady frame cap, 60 on most screens, no idle throttle). The memory and Distant Horizons limits still apply to all of them.
- **My settings**: your own settings, saved the first time you open Profiles (and before your first switch). It's the way back. You can re-save it by saving under its name, but you can't delete it.
- Your own saved profiles (**Save current…**) and imported ones.

A switch is an ordinary Apply: options change right away, Sodium, Distant Horizons and Iris settings change at the next restart, and History shows it as "Profile: Battery", with Undo this / last / all as usual. RigTune never switches by itself. On a laptop it notices when you unplug and *offers* Battery (and offers your previous profile when you plug back in); **Don't offer again** turns the offer off.

**Copy code** puts a share code like `RT1-ARJDaGFy…` (about 100 characters) on your clipboard. **Import code…** takes one from a friend and always shows **Preview** first, with **Apply**, **Save only** or **Cancel**. Nothing is written before you click, and RigTune reads the clipboard only when you press **Paste**. A code carries only setting values from a fixed list, as numbers: no text besides a name (cleaned up and shown as plain text), no file names, mods or downloads. Values beyond your PC's memory and Distant Horizons limits are lowered, and Preview says so. Settings your game doesn't have are left out. Thread counts are machine-specific, so they stay in your own profiles and are never shared.

Shader-pack settings (the options inside a pack like Complementary or BSL) aren't part of profiles or share codes. Profiles only turn shaders on or off. Changing a pack's options safely would mean writing files outside `config/` and a new kind of staged change that older RigTune versions can't run. Iris also puts pack option values straight into the shader source, so they must never come from someone else's code. It may come in a later release (docs/research/v0.4/profiles.md §7).

## Stutter Doctor

**Tools… → Stutter Doctor** shows where the time went when the game hitches, and what may help.

- **The session monitor** is off by default. Turn it on with **Start** (or **Stutter Doctor monitor** in RigTune's settings). While a world is loaded it records every frame's time, the Java garbage collector's pauses, world saves, chunk loading and how busy the game's threads are. **Pause** stops recording for a while; **Stop** turns it off. Leaving the world ends the session and saves a short summary; the next world starts a new one while the monitor is on. While it's on it uses about 2.5 MB of memory and well under a microsecond per frame; when it's off it costs nothing (no listeners, no background thread).
- **The benchmark** always records its own sweeps the same way, and its result screen gets one line (for example "2 spikes during the sweeps; likely causes: Garbage collection 64 %").
- **The report**: session length, gameplay time, frames, average FPS and 1 % low; a frame-time histogram weighted by play time; the **spikes** (a frame over twice the usual frame time, at least 8 ms more and at least 20 ms; minor under 50 ms, major to 100 ms, severe to 500 ms, freezes beyond); the likely causes as shares of the lost time, measured where possible: garbage-collection pauses, chunk loading, chunk building, game ticks; with **"Not explained"** always shown for what nothing accounts for. World saves, chunks loading nearby, Distant Horizons, a busy CPU, the seconds after a teleport and fast movement are counted as correlations only ("7 of 12 spikes happened during world saves (not measured)", "9 of 12 spikes happened while chunks were loading (not measured)"). Then the 10 worst spikes and any advice that fits (for example more memory when garbage collection dominates and the heap is nearly full, with your launcher's steps; or Sodium's Chunk Updates set to Deferred). Menus, an unfocused window and the first 10 s in a world don't count. Everything is "likely": correlation, not proof.
- It needs at least 3 spikes and 2 minutes of gameplay for a verdict. On some Minecraft versions the per-phase timing may not be available; the report then says "Phase timing unavailable" and chunk loading, ticks and rendering aren't separated.

**Privacy:** all of it happens on your PC; nothing is sent anywhere. The summaries of the last 5 sessions (numbers only, no world or player names) are kept in `config/rigtune/stutter.json` (at most 64 KB); the raw frame times are never saved. **Copy summary** puts a text summary on your clipboard, only when you press it. **Clear** deletes the saved summaries.

## JVM & memory advice

**Tools… → JVM & memory** shows the Java that runs your game: its version and vendor, which garbage collector it uses and whether your Java arguments chose it, and how much memory (heap) it may use. RigTune checks your Java arguments once per session, on your PC, and lists the ones Java ignores, the ones that hurt a game client and the ones that only cost memory. Under each note it tells you where your launcher keeps its Java arguments (the Modrinth App, Prism Launcher, MultiMC, ATLauncher, GDLauncher, the CurseForge app and the Minecraft Launcher). How much memory to give Minecraft stays with the memory advice, which points to your launcher's memory setting; in the Modrinth App and GDLauncher an `-Xmx` typed into the Java arguments overrides the memory slider, and the memory advice says so when RigTune sees one.

**RigTune never tells you to add GC flags or to switch collector for more FPS: its notes only ever suggest removing arguments or changing the memory setting.** On the one PC they were measured on (Ryzen 7 7800X3D, RX 7800 XT, 32 GB, Minecraft 26.2 with Sodium at render distance 16, a 4 GB heap, 33 clean runs of 80 s each), every configuration gave the same frame rates within run-to-run noise:

| Java arguments | Average FPS | 1% low | GC pauses per 80 s | Memory committed |
|---|---|---|---|---|
| Java's defaults (G1) | 1931 | 610 | 27, 84 ms in total | 1.3 GB |
| The Minecraft Launcher's and ATLauncher's G1 set | 1937 | 616 | 45, 119 ms | 1.1 GB |
| Aikar's flags (Paper's server flags) | 1926 | 622 | 6, 10 ms | 4.1 GB |
| ZGC | 1922 | 627 | 23, under 1 ms | 3.9 GB |

Capped at 140 FPS, G1 and ZGC were also the same (1% low 126 against 127). What changed was memory: ZGC and Aikar's `-Xms` keep close to the whole heap in RAM. So RigTune leaves Java's defaults, the launchers' default sets and a working ZGC alone, and only names flags worth removing. One PC and a light mod set are not every PC: a slow CPU that uses its whole frame time could behave differently (not measured). The runs and their method are in [docs/research/v0.4/jvm-gc.md](docs/research/v0.4/jvm-gc.md).

**Privacy:** the Java arguments can contain folder paths with your Windows user name. RigTune never shows, logs or shares them: the screen and the log name only the flags it has a note about, and **Copy report** adds one line such as `Java: 25.0.3 (Azul Systems, Inc.), G1, 2 argument notes`. The check needs a HotSpot-based Java (Temurin, Zulu, Oracle, Microsoft); on other Javas (OpenJ9) it turns itself off and gives no JVM advice.

### The game won't start after I pasted Java arguments

When Java refuses an argument, Minecraft never starts, so RigTune can't see it; your launcher shows the error. Checked with Java 25, the version Minecraft 26.2 and 26.3 use:

| The error says | Why | Fix |
|---|---|---|
| `Unrecognized VM option 'UseConcMarkSweepGC'` (or `UseParNewGC`, `CMS…`, `AggressiveOpts`, `UseBiasedLocking`, `PermSize`, `MaxPermSize`, `UseStringCache`, …) | The option was removed from Java years ago (CMS in Java 14). | Delete it. |
| `VM option 'G1NewSizePercent' is experimental and must be enabled via -XX:+UnlockExperimentalVMOptions` | An experimental option without the unlock, or with the unlock after it. | Delete it, or put `-XX:+UnlockExperimentalVMOptions` before it. |
| `Multiple garbage collectors selected` | Two `-XX:+Use…GC` options, for example `-XX:+UseG1GC -XX:+UseZGC`. | Keep at most one (with none, Java picks G1). |
| `Initial heap size set to a larger value than the maximum heap size` | `-Xms` is larger than `-Xmx`. | Delete `-Xms`. |
| `Unexpected +/- setting in VM option 'ParallelGCThreads=8'` | A number option written with `+` or `-`. | Write it as `-XX:ParallelGCThreads=8`, or delete it. |
| `Unrecognized VM option 'UseTransparentHugePages'` | A Linux-only option, on Windows. | Delete it. |
| `Unknown -XX:ShenandoahGCMode option` | The `iu` mode no longer exists. | Delete it. |

The quickest way back is to stop the instance using its own Java arguments, so it uses your launcher-wide ones again: in the Modrinth App turn off the instance's **Custom Java arguments**, in Prism Launcher untick **Java Arguments**, and in ATLauncher press **Reset** next to **Java Parameters**. In the Minecraft Launcher, create a new installation: its JVM arguments start at the defaults.

## RigTune's own footprint

RigTune's heavy work (the hardware scan, the mod scan, loading the rules and the Modrinth lookups) runs on its own background threads, not on the way to the title screen. Each frame it checks one flag; each tick it reads a few fields.

**Launch time.** On one Windows PC (Ryzen 7 7800X3D, RX 7800 XT, Minecraft 26.2), launch to title screen took 14.5 s with RigTune and 14.3 s without it: medians of 10 launches each, with RigTune switched off through Fabric Loader's `-Dfabric.debug.disableModIds=rigtune` and everything else the same. That difference is well inside the roughly 2 s spread between launches.

**Budgets.** Every build runs a footprint check (`FootprintGameTest` in each game-test run, `FrameHookBudgetTest` with the unit tests) that fails when RigTune goes over these budgets (`tools/footprint-budgets.json`). The budgets were set at twice the largest value seen in calibration runs on GitHub's runners. The numbers below are the largest of the three game-test legs (26.2 OpenGL, 26.3 OpenGL and 26.3 Vulkan; Linux, 4 vCPUs, software rendering) in the release candidate's CI run ([36236205018](https://github.com/chaotix345/rigtune/actions/runs/36236205018)); the per-frame numbers come from the unit test on the same run:

| What | Release candidate | Budget |
|---|---|---|
| RigTune's startup work on the game's main thread, CPU time | 100 ms | 150 ms |
| The same, wall time (includes waiting while the shared runner is busy) | 153 ms | 368 ms |
| RigTune's call when the game has started, wall time | 48 ms | 141 ms |
| RigTune's background threads in the first 5 s, CPU time | 208 ms | 300 ms |
| Per frame | under 1 ns, nothing allocated | 13 ns, nothing allocated |
| Per tick, at the title screen | 59 ns, nothing allocated | 111 ns, nothing allocated |
| Per tick, in a world (session monitor off) | 26 ns, nothing allocated | 87 ns, nothing allocated |
| RigTune's own objects in memory, after a full garbage collection | 74 KB | 107 KB |
| RigTune objects left behind by opening and closing its screens and Tools 20 times | none | none |

Most of the startup time is Java loading classes the first time they're used, among them Gson's, which Minecraft loads soon after anyway. On the Windows PC above, the same startup work took 65 ms (62 ms of CPU time) when the budgets were calibrated.

**The Stutter Doctor's session monitor** is off by default. Off, it costs next to nothing: no garbage-collection listener, no thread, one flag check per frame and a few field reads per tick. While it's on (in a world), it holds about 2.5 MB of memory for its frame and event buffers and gives all of it back when it stops. It adds about 35 ns per frame, or about 240 ns with its per-phase timing; that's well under a thousandth of a frame even at 240 FPS. Its sampler thread uses up to about 55 ms of CPU per minute, under 0.1 % of one core. The same checks measure it, in the same release-candidate run:

| With the session monitor on | Release candidate | Budget |
|---|---|---|
| Per frame | 35 ns, nothing allocated | 75 ns, nothing allocated |
| Per frame, with the per-phase timing | 239 ns, nothing allocated | 400 ns, nothing allocated |
| Per tick | 49 ns, nothing allocated | 101 ns, nothing allocated |
| Its buffers in memory (their exact size in 0.4.0) | 2.51 MB | 2.5 MiB (2.62 MB) |
| Left in memory after it's turned off | nothing | nothing |
| Its sampler thread, CPU time per minute | 54 ms | 102 ms |

On the dev PC, three interleaved pairs of benchmark runs with the monitor on and off gave the same average FPS and 1 % lows within run-to-run noise (average −0.4 %, 1 % low −3 %, against a spread of 11-12 % inside each set). The benchmark records its own sweeps either way, so these pairs show what the monitor adds around a benchmark rather than the cost of recording itself, which the table above covers.

**Your own launch time.** Tools… shows your last launch time and the median of your last 10, and notes when your mod set changed since the previous launch. The times are kept in `config/rigtune/startup-times.json`, on your PC only. Fabric Loader doesn't time individual mods, so RigTune can't tell you which mod is slow: fewer mods and an SSD help most, and if the launch time jumped after you added a mod, check that mod first.

## Privacy

RigTune has no telemetry. Your hardware details never leave your PC unless you choose to share a report (**Copy report**, **Report a problem**). It makes two kinds of network request, and you can switch each one off in **RigTune → Settings** (or with Mod Menu's config button):

| Switch | What it sends when on | When it's off |
|---|---|---|
| **Network access** (master) | Everything below | No network request of any kind. The RigTune screen says "Offline (network off in settings)". |
| **Rules updates (GitHub)** | A plain download of the rules file from this repository (`rules-v2.json`, or `rules-v1.json` if that fails, from `raw.githubusercontent.com`). | RigTune uses the newer of the rules bundled in the jar and the last downloaded copy. |
| **Modrinth** | To the Modrinth API (`api.modrinth.com`): the SHA-1 hashes of your installed mod jars (to find updates, including RigTune's own), the ids of the mods RigTune might suggest (to check they exist for your version), and your Minecraft version with the loader name `fabric`. When you press Apply or Preview, it also looks up the versions and dependencies of the mods you ticked. From Modrinth's CDN it downloads only the files you choose to install or update, and checks their SHA-512 hashes. | No Modrinth request at all: no lookups, no update checks, no downloads. Mod installs and updates are still listed, as advice to do in your launcher. |
| **Startup suggestions toast** | Nothing (it isn't a network switch) | The "RigTune: N suggestions" toast on the title screen isn't shown. Apply results and warnings still are. |

Every request identifies itself as RigTune and its version (the User-Agent). The switches are stored in `config/rigtune/settings.json`; the first time RigTune 0.2 or newer starts, a one-time toast points at them. With the network off, or when a request fails, RigTune uses the newer of the bundled rules and the last downloaded copy. **Preview** sends nothing beyond the Modrinth lookups above and downloads nothing.

**What the tools keep on your PC.** Profiles, the Stutter Doctor, JVM & memory, benchmark history, server-aware advice, the notices and the launch-time line work entirely on your PC and add no network request. They keep their data in `config/rigtune/`, in files only RigTune reads:
- `profiles.json`: your saved and imported profiles, the History labels of profile switches and the battery offer's state;
- `stutter.json`: the summaries of the last 5 Stutter Doctor sessions (numbers only, no world or player names; at most 64 KB). Raw frame times are never saved;
- `server-limits.json`: the last view and simulation distance seen per server, for the "(was N)" note. Server addresses aren't stored in readable form: each entry is keyed by an HMAC-SHA256 of the address, with a random key created once and kept in the same file, so someone who has the file could still check whether it holds a server they already know. It never goes into a report;
- `awareness.json`: your GPU, driver, CPU and RAM as last seen (to notice a change), the recommendations you've already seen, and the notices you dismissed;
- `startup-times.json`: your last 30 launch times, with the Minecraft and RigTune versions, the number of mods and a hash of the mod list.

Share codes and **Copy summary** go to your clipboard only when you press their button, and RigTune reads the clipboard only when you press **Paste** in Import code. The JVM & memory check reads your Java arguments but never shows, logs or shares them (see [JVM & memory advice](#jvm--memory-advice)).

**Copy report** on the RigTune screen puts a Markdown summary on your clipboard for you to paste into Discord or an issue: the versions, your hardware, estimated tier and goal, your launcher's name (if RigTune recognised it), your Java version, vendor and garbage collector with the number of argument notes, the rules revision, the suggestions' titles, and your latest benchmark with its conditions (and whether it needs a rerun). It leaves out file paths, user names, world names and your Java arguments, and RigTune never sends it anywhere itself. **Report a problem** opens a pre-filled GitHub issue in your browser, only after you confirm the link, and nothing is posted until you submit it (see the [FAQ](#faq)).

**Launcher detection** happens on your PC only. To name your launcher in the memory and Java-arguments advice, RigTune reads these system properties and environment variables and no others: `org.prismlauncher.instance.name`, `multimc.instance.title`, `minecraft.launcher.brand`, `INST_ID` and `INST_NAME`. It also looks in the game folder and the folder above it for two small launcher files, reading Prism's `instance.cfg` (only to check it's a Prism instance, next to an `mmc-pack.json`) and CurseForge's `minecraftinstance.json` (only its `isMemoryOverride` value). Nothing about your launcher is sent anywhere; only its name appears in the report, when you copy or share it.

**spark**: if you have the spark profiler installed, RigTune shows how to capture a profile with it. RigTune doesn't run spark or send it anything, but note what spark itself does: `/sparkc profiler stop` uploads the profile, with your player name and UUID, mod list, system details and Java launch arguments, to spark.lucko.me behind a link that anyone who has it can open.

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

## What has been verified

RigTune's recommendations are estimates, and it's worth knowing what stands behind them:

- **One real machine.** Everything measured (the benchmarks, the Java and garbage-collector comparison behind the JVM advice, local game runs) was measured on one PC: a Ryzen 7 7800X3D with a Radeon RX 7800 XT and 32 GB of RAM on Windows 11. When advice says "in RigTune's tests", that's the PC it means.
- **Everything else is table-driven.** For other hardware, RigTune estimates a tier from its hardware tables (a "table match") or, for a CPU or GPU the tables don't know, from core counts and the GPU vendor (a "fallback estimate"), and picks settings from the rules for that tier.
- **Scenario tests check the rules, not performance.** Automated tests run the bundled rules on hardware RigTune hasn't been run on, including an integrated-graphics laptop on battery (Intel Iris Xe, 8 GB), an old 4-core desktop (Core i5-4590 with a GTX 960) and a CPU and GPU no table knows. They check that the rules give the intended recommendations (tiers, battery settings, memory caps, memory advice, nothing ticked that should start unticked), not that those recommendations make the game faster there.
- **What 0.4's features were run on, for real.** On the PC above, with a release candidate (Minecraft 26.2 unless noted; the evidence is in [docs/v0.4/verification/](docs/v0.4/verification/README.md)):
  - **Profiles**, with Sodium, Iris and Distant Horizons installed: Max FPS → Battery → Recording → My settings with a restart after each, and every file held the template's values; Undo this on the Battery switch put every setting back; the Battery share code, imported into a second instance, opened Preview with the same values and changed nothing.
  - **Stutter Doctor**, with stutter caused on purpose: forced full garbage collections were attributed to garbage collection (28 of 28 hitches) and brought the matching advice; standing still gave under one spike a minute; ZGC's collections claimed nothing; with the per-phase timing blocked, the game still started and the report said "Phase timing unavailable". After a teleport into new terrain the hitches stay "Not explained", because Sodium's chunk builders kept up on that PC and nothing measured pointed at chunk building; a re-run with 0.4.0's "chunks loading" tag marked 9 of the 12 post-teleport spikes "while chunks were loading (not measured)" (the other 3 came in the first 0.3 s, before any chunk had arrived) and claimed none of them as garbage collection (see "Known limits").
  - **JVM & memory**: the notes came from the running Java's own arguments; three interleaved pairs of Java's defaults against Aikar's flags, capped at 140 FPS, gave 1 % lows of 125.9 and 126.5 FPS (the same within noise), with Aikar's set keeping all 4 GB committed against about 1 GB.
  - **Benchmark history**: repeated Measure runs read "in line with your usual"; a slower run gave the regression line with "No change recorded; possibly a driver, OS or other change"; a run with shaders on gave "Performance changed under different conditions (shaders); cause unknown."
  - **Server-aware advice**, against a local vanilla 26.2 dedicated server with view distance 6, then 10: the notice, the capped suggestion, "(was 6)", and no address in `server-limits.json`.
  - **Driver strings**: the PC's real OpenGL and 26.3 Vulkan strings (AMD Adrenalin 26.8.1) parse to the right version, and a changed driver in the stored fingerprint raised the driver notice once.
  - A production client with a copy of a real 50-mod instance on 26.2 (Sodium, Iris, Distant Horizons and more), and one with a Modrinth mod set on 26.3; opening every tool wrote nothing.
  - Every client game test passed on both versions on this PC with the release candidate; CI runs them on every push on Linux (26.2 OpenGL, 26.3 OpenGL, 26.3 Vulkan, with software rendering).
- **Compatibility is tested against the released versions.** Tests run pinned copies of the released 0.1.0, 0.2.0 and 0.3.0 code on the rules files and the shared state files this version writes, and a CI job runs the released 0.3.0 jar's own classes on them; 0.1.x only ever gets a subset of the rules that is at least as cautious as what it shipped with.
- **Your own measurements are the stronger evidence.** A benchmark on your PC, and a Measure run before and after a change, show what actually happened there; changes RigTune lists between two runs are ones that "may be related", never proven causes.

## Known limits

- **History keeps 50 entries.** Older ones are folded into one baseline entry, so Undo all still reaches your original settings. The baseline shows as an ordinary Apply at the oldest date, and a profile switch folded into it loses its "Profile:" label.
- **Shader-pack settings aren't in profiles** (see [Profiles and share codes](#profiles-and-share-codes)); this may come in a later release.
- **No per-mod startup times.** Fabric Loader doesn't time individual mods, so RigTune can show your launch time but not which mod is slow.
- **Joining a LAN world from another PC, and Realms, weren't tested** end to end; server-aware advice was tested against a local dedicated server.
- **One real test machine** (see above). The battery offer was tested with simulated batteries only, and real Vulkan driver strings only for AMD and Mesa.
- **Stutter Doctor** claims time for chunk loading or building only with evidence of it; on a fast PC, hitches right after entering new terrain are tagged "while chunks were loading (not measured)" and their time stays "Not explained".
- **Keyboard and Narrator**: the benchmark result's table and the charts can't be reached yet, and the narration was checked in tests, not with a real screen reader.

## FAQ

### Does RigTune work with Quilt?

Not officially, and it isn't tested there. Quilt Loader lists Minecraft 26.2 and 26.3, but Quilt retired the Quilt Standard Libraries and Quilted Fabric API at Minecraft 26.1 ([QuiltMC blog, February 2026](https://quiltmc.org/en/blog/2026-02-03-non-obfuscated-updates/)), and RigTune needs Fabric API. Whether Quilt Loader can load Fabric API itself on 26.x hasn't been confirmed, so RigTune doesn't claim Quilt support. Use [Fabric Loader](https://fabricmc.net/use/).

### What does "Report a problem" send?

RigTune itself sends nothing and makes no network request for it. The button on the RigTune screen:
1. copies the full report (the same text as **Copy report**) to your clipboard;
2. shows Minecraft's own "open this link?" screen with a link to a new issue on this repository. The link fills in the title (your RigTune and Minecraft versions) and as much of the report as fits (always the versions line, usually your hardware, the whole report when it's short), and it's short enough to read in full on that screen. **Cancel** closes it and nothing is opened.

If you choose **Open in Browser**, your browser opens that link, so GitHub receives what's in it (the title and the report text in the link), and shows the issue form with those fields filled in. Paste the full report from your clipboard, say what happened, and review everything: nothing is posted until you submit the issue, which needs a GitHub account. (Minecraft's **Copy to Clipboard** button on the link screen puts the link on your clipboard instead of the report: press **Copy report** again to get the report back.)

## Build from source

You need JDK 25. One source tree builds every supported Minecraft version with [Stonecutter](https://stonecutter.kikugie.dev/); each version has a Gradle project named after it (`:26.2`, `:26.3`).

```sh
./gradlew build                     # every version: jars in versions/<mc>/build/libs, unit tests, game tests compiled
./gradlew :26.3:build               # one version only
./gradlew :26.2:runClientGameTest   # in-game tests for one version (opens a game window); run versions one at a time
./gradlew :26.3:runClientGameTest
python -m unittest discover -s tools/tests
```

CI (`.github/workflows/build.yml`) runs the build and unit tests for every version, the Python tests and the rules checks on every push and pull request. It also runs the client game tests on Linux, one job per version and graphics backend: OpenGL for every folder in `versions/`, plus Vulkan from 26.3 on (so 26.2 OpenGL, 26.3 OpenGL and 26.3 Vulkan today), on Mesa's software renderers under Xvfb. Those jobs use `./gradlew :<mc>:runProductionClientGameTest`, which runs the same tests against the built jar in a production client rather than the development classpath, and keep each job's screenshots, logs and crash reports as artifacts, plus the footprint numbers (see "RigTune's own footprint"). The E2E driver step also runs the released 0.3.0 jar's classes on the files this version writes. A separate workflow, `snapshot-canary.yml`, builds RigTune against the newest Minecraft snapshot every week (and on demand) and keeps one issue open while that build fails (see [tools/MC_VERSIONS.md](tools/MC_VERSIONS.md)).

The few lines that differ between versions are marked with `//? if >=26.3 {` comments. `src/` is always in the state of one active version, which is what your IDE compiles; the others are generated under `versions/<mc>/build/generated/stonecutter/`. To work on another version, switch the active one, and switch back before committing (CI fails if the sources are committed in a switched state):

```sh
./gradlew "Set active project to 26.3"   # rewrites the version comments in src/ for 26.3
./gradlew "Reset active project"          # back to 26.2, the committed version; git diff should then show only your own edits
```

See [docs/DESIGN.md](docs/DESIGN.md) "Porting to new MC versions" for how to add a future version, and [tools/MC_VERSIONS.md](tools/MC_VERSIONS.md) for the two tools that do most of it (`tools/add_mc_version.py` adds the version, `tools/mc_apidiff.py` checks RigTune's code against its API).

## Translating RigTune

RigTune's screens are English only for now, and translations are welcome.

- **Where the text lives:** [`src/main/resources/assets/rigtune/lang/en_us.json`](src/main/resources/assets/rigtune/lang/en_us.json) holds every piece of text RigTune shows. A translation is a file next to it named after the language code Minecraft uses (the Java Edition column of the [Minecraft Wiki's language list](https://minecraft.wiki/w/Language)), e.g. `de_de.json` or `pt_br.json`, with the same keys and your text as the values. You don't need every key: a missing one shows the English.
- **Keys** are `rigtune.<area>.<thing>`, for example `screen`, `header`, `goal`, `category`, `impact` and `limit` for the main screen, `rec` for the recommendation text RigTune writes itself ("Install %s", "Version %s is available (you have %s)."), `undo` and `history` for the Undo and History screens, `preview`, `benchmark`, `launcher` (the memory steps for each launcher), `download` (why a download was refused), `report` and `share` for the report buttons, `status` and `toast` for messages, `settings` for the settings screen, `tools` for the Tools hub and `notice` for the notice line, `profile` and `battery` for Profiles, `stutter` for the Stutter Doctor, `jvm` for JVM & memory, `benchmark.trend` for benchmark history, `server` and `awareness` for the notices about a server's limit and about changes, `startup` for the launch time, `a11y` for what the Narrator adds, and `key.rigtune.open` / `key.category.rigtune.rigtune` for the Controls screen. Only the values are translated, never the keys.
- **Arguments:** `%s` is replaced by a value (a mod name, a number, another piece of text) in order. If your language needs them in a different order, number them: `%2$s ... %1$s`. Use exactly the arguments the English uses, no more and no fewer, and write them as `%s` (Minecraft also accepts `%d`, but the English doesn't use it). `%%` is a percent sign (`"1%% low"` shows as "1% low"); a lone `%` makes Minecraft show the text unformatted.
- **Formatting codes:** RigTune's text has no `§` codes; colours and bold come from the code, so leave them out.
- **Try it in game:** put your file in `src/main/resources/assets/rigtune/lang/`, run `./gradlew build` (see "Build from source"), put `versions/<mc>/build/libs/rigtune-<version>+mc<mc>.jar` in a test instance's `mods` folder in place of RigTune and choose your language in Options → Language. Minecraft can also load a lang file from a resource pack (`assets/rigtune/lang/<code>.json` inside the pack), which is quicker for trying out wording; F3 + T reloads resource packs.
- **Check it:** `./gradlew :26.2:test --tests '*LangCheckTest'` fails if your file has a key en_us.json doesn't, different arguments from the English, a `%` Minecraft can't format, or isn't a flat JSON object of strings.
- **Submit** a pull request that adds your one `<code>.json` file. Say whether a native speaker has read it through.

Not translatable yet:
- the recommendation text that comes from the rules: most reasons, and the advice (title and text). It's written in English in [`rules/`](rules/) and downloaded from GitHub, so it stays English on a translated screen, next to RigTune's own translated parts ("Install %s", the notes after a reason);
- mod names, versions, file names, and setting names and values taken from the rules or from the mods themselves;
- error details from outside RigTune's planning: after "Download failed:", a download's own errors (network, redirects, a file whose SHA-512 doesn't match, an unsafe file name); the reason RigTune's apply helper gives for a change that failed at exit (History screen); a setting value the game or a mod's config refuses (Preview); a failed benchmark check's error;
- **Copy report** and **Report a problem**: the report stays English on purpose, so anyone can read it in a bug report.

## Credits

- Mod lists used as input data: [Fabulously Optimized](https://github.com/Fabulously-Optimized/fabulously-optimized) (BSD-3-Clause) and [Additive](https://github.com/skywardmc/additive) (MIT).
- Mod data from the [Modrinth API](https://docs.modrinth.com/api/).
- Driver workaround details come from [Sodium](https://github.com/CaffeineMC/sodium).

## License

[MIT](LICENSE)
