# Changelog

All notable changes to RigTune are documented here. Dates are UTC. The section
for each release becomes that release's Modrinth changelog (`build.gradle`'s
`changelogForVersion`, matched on the `## [<mod_version>]` heading).

## [Unreleased]

## [0.4.0] - 2026-09-27

### Added
- **Tools…** on the RigTune screen (in the footer, where Benchmark… was) opens a hub: **Benchmark…**, **Profiles…**, **Stutter Doctor…**, **JVM & memory…**, **Benchmark history…** and your last launch time. Everything in it works with the network off and sends nothing anywhere.
- **Performance Profiles** (Tools… → Profiles…): switch your video, Sodium, Distant Horizons and Iris settings between whole setups in one click.
  - Templates, worked out for your PC from the same rules as the main list: **Max FPS**, **Balanced**, **Quality**, **Battery** (60 FPS with VSync, shorter distances, no clouds; shaders and Distant Horizons rendering off when you have them) and **Recording** (a steady frame cap, 60 on most screens, no idle throttle). Your memory and Distant Horizons limits still apply.
  - **My settings**, saved the first time you open Profiles: the way back. It can't be deleted.
  - Your own saved profiles (**Save current…**) and imported ones.
  - A switch is an ordinary Apply: options change at once, Sodium, Distant Horizons and Iris settings at the next restart, History shows it as "Profile: Battery", and Undo this / last / all work as usual.
  - On a laptop, RigTune *offers* Battery when you unplug, and your previous profile when you plug back in. It never switches by itself, and **Don't offer again** turns the offer off.
- **Share codes**: **Copy code** puts a code like `RT1-AQdCYXR0…` (about 100 characters) on your clipboard; **Import code…** always opens a Preview first, with **Apply**, **Save only** and **Cancel**, and nothing is written before you click. A code holds only setting values from a fixed list, as numbers, plus a name shown as plain text: no file names, no mods, no downloads. Values beyond your PC's memory and Distant Horizons limits are lowered, and Preview says so. Thread counts stay on your PC. RigTune reads the clipboard only when you press **Paste**.
- **Stutter Doctor** (Tools… → Stutter Doctor) shows where the time went when the game hitches, and what may help.
  - The session monitor is **off by default**. Turn it on with **Start** (or in RigTune's settings). While a world is loaded it records frame times, Java's garbage-collection pauses, world saves, chunk loading and how busy the game's threads are; **Pause** and **Stop** do what they say. Leaving the world saves a short summary: numbers only, the last 5 sessions, in `config/rigtune/stutter.json`.
  - The report: spikes by severity, a frame-time histogram, the likely causes as shares of the lost time (garbage collection, chunk loading, chunk building, game ticks) with **Not explained** always shown, correlations such as world saves, chunks loading nearby or Distant Horizons' background work marked "(not measured)", the 10 worst spikes, and advice that fits (for example more memory, with your launcher's steps, when garbage collection dominates and the heap is nearly full). It gives no verdict or advice before 3 spikes and 2 minutes of gameplay.
  - The benchmark always records its own sweeps, and its result screen gets one Stutter Doctor line.
  - **Copy summary** puts a text summary on your clipboard, only when you press it.
- **JVM & memory** (Tools… → JVM & memory): the Java that runs your game (version, vendor, garbage collector and whether your Java arguments chose it, heap), and notes on Java arguments that Java ignores, that hurt a game client, or that only cost memory. Each note says where your launcher keeps its Java arguments (the Modrinth App, Prism Launcher, MultiMC, ATLauncher, GDLauncher, the CurseForge app and the Minecraft Launcher).
  - RigTune never tells you to add garbage-collector flags or to switch collector for more frame rate; its notes only ever suggest removing arguments or changing the memory setting. On the one PC they were measured on, Java's defaults, the launchers' default sets, Aikar's flags and ZGC gave the same frame rates within run-to-run noise; what differed was memory (the README has the table).
  - Your Java arguments are never shown, logged or shared: they can contain folder paths with your Windows user name. **Copy report** adds one line with the Java version, vendor, collector and the number of notes.
  - It needs a HotSpot-based Java (Temurin, Zulu, Oracle, Microsoft); on OpenJ9 the check turns itself off.
  - The README has the errors Java prints for arguments that stop the game from starting, and how to get back.
- **Benchmark history and regression alerts** (Tools… → Benchmark history):
  - Runs are compared only with runs under the same conditions: Minecraft version, scene, render and simulation distance, window size, fullscreen, shaders and pack, Distant Horizons.
  - Once there are 3 earlier comparable runs, a run whose 1% low is below your usual by more than the run-to-run noise says so ("1% lows 31% below your usual 630 FPS since 2026-09-26"), then lists the History entries since then as "Changes since then (may be related)", or "No change recorded; possibly a driver, OS or other change". A run under different conditions says "Performance changed under different conditions (shaders); cause unknown." An improvement is never an alert.
  - The result screen's chart shows comparable runs only, with your usual level as a line.
  - The tier badge's tooltip and **Copy report** show your last benchmark with its conditions, and "Needs a rerun" when they no longer match your game.
- **Server-aware advice**: on a server that limits view distance, the RigTune screen says so ("The server limits view distance to 10 chunks (you set 16)"), and a suggested render-distance increase stops at the server's limit ("The server sends at most 10 chunks."). RigTune never suggests lowering your render distance because of a server. Singleplayer, including a world you opened to LAN, is unchanged. The last limit seen per server is kept in `config/rigtune/server-limits.json` for a "(was 6)" note; server addresses aren't stored in readable form there (a keyed hash with a random key kept in the file), and the file never goes into a report.
- **Change awareness**:
  - A notice when your GPU, GPU driver or other hardware changed since the last start ("Your GPU driver changed since last time (26.5.1 → 26.8.1)"), with **Re-scan** and **Re-benchmark**. Switching between OpenGL and Vulkan alone isn't a change.
  - A notice when a rules update brings recommendations you haven't seen yet ("3 new recommendations for you since you last looked: …").
  - The first start after an upgrade stays quiet and only records where you are.
  - Two driver warnings for known-bad ranges: NVIDIA drivers 526.47 to 536.22 on Windows (they force Threaded Optimization, which Sodium reports causes crashes and stutter, Sodium issue #1486), and Intel HD Graphics 2500/4000 drivers older than 10.18.10.5161 (can freeze at startup with Sodium, Sodium issue #899).
- **One notice line** under the RigTune screen's header shows the most important of these (battery offer, server limit, benchmark regression, hardware change, new recommendations, a benchmark that needs a rerun), with "+N more". In a narrow window a "…" button opens them all.
- **Launch time** in Tools…: your last launch-to-title time, the median of your last 10, and a note when your mod set changed since the previous launch ("may be related"). Fabric Loader doesn't time individual mods, so RigTune can't tell you which mod is slow.
- **Keyboard and Narrator**: every list row on RigTune's screens can be reached with Tab and the arrow keys and is read by the Narrator, and so are the notice line, Benchmark history's lines and the launch time in Tools; a recommendation's checkbox now names its recommendation. Enter or Space selects a History entry or a profile, and a frame shows the focused row. With Minecraft's High Contrast options on, RigTune's own colours switch to high-contrast ones. The benchmark result's table and the charts aren't reachable yet.
- **RigTune's own footprint** is checked on every build: a game test on every CI leg and a unit test fail when RigTune's startup work, its per-frame or per-tick cost, its memory, or the Stutter Doctor monitor's cost go over fixed budgets (numbers in the README).
- For contributors and maintainers:
  - a snapshot canary workflow (weekly, and on demand) builds RigTune against the newest Minecraft snapshot and keeps one issue open while that fails; it was proven on a branch, and its schedule starts once the file is on main;
  - a harness in CI runs the released 0.3.0 jar's own classes on the files this version writes, and pinned copies of 0.1.0 and 0.3.0 code read them in the unit tests;
  - scenario tests run the bundled rules on hardware RigTune wasn't run on (an integrated-graphics laptop on battery, an old 4-core desktop, a CPU and GPU no table knows).

### Changed
- The header reads "**Estimated tier** 4/5 · lowest estimated component: CPU" instead of "limited by …", and names every tied component ("GPU, CPU"). The tier badge's tooltip gives each component's tier and where it came from: RigTune's hardware table ("table match") or core counts and the GPU vendor ("fallback estimate"). **Copy report** uses the same wording.
- **VSync off** is an optional suggestion now (unticked), and says what it does: "Optional: turning VSync off lowers input lag but can cause tearing; leave it on if you see tearing." The frame-rate cap no longer claims to keep FreeSync or G-Sync active: "Avoids rendering frames your monitor can't show; with FreeSync or G-Sync it also keeps the frame rate inside the variable-refresh range." RigTune 0.1.x gets the same change.
- **Launchers**:
  - MultiMC and GDLauncher are recognised, named, and get their own memory steps. PolyMC is named MultiMC (its steps are the same).
  - The CurseForge app's memory steps say "choose Custom RAM Allocation" (it's a radio button, not a switch).
  - The Minecraft Launcher's steps follow Mojang's own help articles: Installations → select the installation → More Options → JVM Arguments → Save.
  - In the Modrinth App and GDLauncher an `-Xmx` typed into the Java arguments overrides the memory slider; when RigTune sees one, the memory advice says to change it there.
- The benchmark result screen wraps its status lines instead of cutting them off; at the smallest window size a line that still doesn't fit shows its full text as a tooltip.
- **Preview** shows settings the way the main list does ("Particles: All → Decreased", not `particles: 0 → 1`).
- **History** names mods by their own name ("Sodium") instead of the jar file, for changes made by 0.4 or later.
- Two ticked items that can't go in together (two updates or two additions that Modrinth or the rules mark as incompatible) are both refused, each naming the other, instead of the one planned later failing. Untick one of them.
- The helper's failure messages read "Gave up after N tries" and "Gave up after 3 restarts".
- **Tools…** replaces **Benchmark…** in the RigTune screen's footer; Benchmark… is the first entry in Tools.
- Modrinth lookups and downloads run on their own threads, so a slow or failing Modrinth no longer holds up History, Undo plans or report rebuilds. Settings changes are saved on their own thread and written before the game quits.
- "Disable LambDynamicLights" no longer says "entry-level hardware" on a PC whose tier only its memory lowers. Rules revision 16.

### Fixed
- **On Minecraft 26.3, clicking a row in History did nothing**, and clicking a recommendation's text didn't tick it: 26.3 numbers the left mouse button differently from 26.2. (The RigTune button on Sodium's video settings screen used the same check.)
- **A second "Undo last" in one session could skip a staged setting.** After two applies that changed the same Sodium, Distant Horizons or Iris setting, the second Undo last said "You changed it since" and left the setting at the in-between value. It now undoes it.
- A second "Undo last" in one session no longer skips an apply that is only waiting for the first undo's restart and undoes an older, unrelated one instead; it asks you to restart first.
- Preview, History and the Undo screen could add every widget twice when their list had already loaded.
- History's **Undo last** and **Undo all** were active with nothing left to undo.
- **Updates respect other mods' version requirements.** An update that an installed mod doesn't allow (for example Iris requiring Sodium 0.9.x) is refused, naming that mod; ticked together with an update of that mod that does allow it, both go in together. A mod's own requirements on the other mods are checked the same way.
- **A library bundled inside another mod no longer counts as installed**, so a mod you add that needs the standalone library gets it instead of having it dropped after the download.
- An update whose new version needs a mod you don't have (and aren't adding in the same Apply) is refused, naming it, instead of staged without it. An update's downloaded jar must be the same mod as the one it replaces.
- **The incompatibility checks see what an earlier Apply staged**: an addition or update that Modrinth marks incompatible with a mod still waiting for a restart is refused, naming that mod. The other direction, the staged mod's own "incompatible" list, is checked while Modrinth is on.
- Pressing Apply before RigTune's Modrinth lookup finished (or after it failed) skipped the incompatibility checks against your installed mods. It now asks you to wait a moment or press Rescan.
- **A helper killed mid-update is finished or rolled back at the next run.** The post-exit helper applies an update's renames as a group; if it is killed between them, or a rollback fails, the next run finishes the group or puts the old jar back instead of leaving a mod or its library missing. A group left half done is never given up on. Undo and Discard pending leave such a group alone until the next exit has finished it.
- The helper writes its result files in a crash-safe order: a helper that died in between no longer makes History call applied changes "Not applied".
- **Undo keeps a mod and its library together**: all file changes of one undo are applied together or not at all, so a failed rename can't leave a mod active without its library.
- "Disable X" is refused when another installed mod needs X (the game wouldn't start without it), or when another change of X is already staged (cancel that first). The rest of the Apply goes ahead.
- Staged setting changes A → B → A for one setting before a restart ended at B; they now end at A.
- **"Undo all" still reaches your original settings after a long history.** History keeps at most 50 entries; the oldest used to be dropped, so after many applies or profile switches Undo all could no longer restore your own settings or remove mods RigTune added. Old entries are now folded into one baseline entry that keeps what Undo all needs.
- The "Imported from 0.1" History entry is only created when upgrading from 0.1.x (it also appeared when `history.json` was deleted).
- Text from outside RigTune (advice from the rules file, setting labels, mod names) is drawn as plain text without formatting codes or invisible characters, and **Copy summary** escapes advice titles the way Copy report does, so a hostile title can't format a Discord message or ping anyone.
- A Modrinth file name with text-direction or invisible characters is refused. Log lines that name something from a download escape control characters, and the log lines of 0.4's new features name config files relative to `config/rigtune` instead of by their full path, which can contain your Windows user name.

### Compatibility
- 0.4.0 changes no file format (no `formatVersion` or `schemaVersion` bump).
  - New files, which older versions never read: `profiles.json`, `stutter.json`, `server-limits.json`, `awareness.json`, `startup-times.json` and the helper's `unfinished-groups.json`, all in `config/rigtune/`.
  - New optional fields: a Modrinth project and version id on staged mod additions in `pending.json` (and in `last-apply.json`'s copies of them), `modName` in `history.json`, the mod-set hash and history position of new benchmark runs in `benchmarks.json`, and `stutterMonitor` in `settings.json`. 0.1.0 to 0.3.0 ignore them, and 0.3.0 drops them when it rewrites a file (History then shows file names again, and the monitor is off).
- A profile switch is an ordinary Apply entry: 0.3.0 lists it and can undo it, without the "Profile:" label.
- Checked against the released versions: a CI harness runs the released 0.3.0 jar's own classes on the history, pending, benchmark, settings and rules files 0.4 writes; unit tests run pinned copies of the 0.1.0 and 0.3.0 code on them, including 0.3.0's helper on a plan the new helper left. On the release candidate (Minecraft 26.2), the released 0.1.0, 0.2.0 and 0.3.0 updated themselves to 0.4.0 in a real client against a local stand-in for Modrinth, undo after a restart worked with real profile switches, and a downgrade to the released 0.3.0 and back kept History, Undo and every 0.4 file working.
- After a downgrade to 0.3.0: its own history cap drops the baseline entry once it adds to a full history, and its helper can't read the new helper's record of a half-done update (it finishes the update if it can, as 0.3.0 always did).
- A 0.1.x client never gets an unsafe or less conservative recommendation from `rules-v1.json`: its only change is the unticked VSync suggestion and the two reworded reasons. Everything else new in the rules is for 0.4 only: 0.2.0 and 0.3.0 ignore the new sections and never fire a rule with the new driver condition.

### Known issues
- Shader-pack settings (the options inside a pack such as Complementary or BSL) aren't part of profiles or share codes; profiles only turn shaders on or off (reasons in the README).
- History's oldest entries are folded into one baseline entry, shown as an ordinary Apply at the oldest date; a profile switch folded into it loses its "Profile:" label.
- Stutter Doctor claims time for chunk loading or building only with evidence of it. On a fast PC, whose chunk builders keep up, the hitches after entering new terrain are tagged "while chunks were loading (not measured)" but their time stays "Not explained".
- Server-aware advice was tested against a local vanilla dedicated server. Joining a world opened to LAN from another PC, and Realms, weren't tested end to end. The Distant Horizons note on servers ("may still show terrain you've already explored") isn't verified.
- If the helper's rollback itself fails again, or the helper is killed during one, and the jar left missing is one another mod needs, Fabric won't start until the files are put back by hand (the helper's log and `unfinished-groups.json` name them). This is much less likely than in 0.3.0, but possible.
- The battery offer was tested with simulated batteries only (no laptop was available).
- Real Vulkan driver strings were captured only for AMD and Mesa. An NVIDIA or Intel Vulkan driver that RigTune can't parse is compared as text, so the driver notice then shows the raw strings.
- The CurseForge app gets no note about an `-Xmx` in its Java arguments (it isn't known whether one overrides Custom RAM Allocation). The Minecraft Launcher's labels come from Mojang's help articles, not from the launcher itself.
- The benchmark result's table and the benchmark charts can't be reached with the keyboard or read by the Narrator yet. The Narrator text was checked in tests, not with a real screen reader.
- On some Windows machines vanilla Minecraft 26.3 crashes natively during startup (around the time its sound system starts), with or without RigTune. It can take a few relaunches.
- An installed 0.1.0, 0.2.0 or 0.3.0 is offered the update only once the Modrinth listing is approved: RigTune finds its own update through Modrinth, which doesn't list versions of a project that is still in review.
- Quilt isn't supported (see the [README's FAQ](https://github.com/chaotix345/rigtune#does-rigtune-work-with-quilt)).
- Preview can't show what Apply only learns once a file is downloaded: a file that isn't a Fabric mod, a mod you already have, or a version another mod's requirements don't allow. Modrinth's answers can also change between Preview and Apply.
- The incompatibility checks don't use disables an earlier Apply staged, and the check of a staged mod's own "incompatible" list needs Modrinth on.
- Measuring the shader cost turns shaders off and on through Iris, which re-saves `config/iris.properties` and the active pack's settings file with a new date line; every value stays the same.

## [0.3.0] - 2026-09-26

### Added
- **History** ("What RigTune changed", from **History…** on the RigTune screen): every Apply, benchmark result, Undo and 0.1 import, newest first, with its local date and time and the RigTune and Minecraft versions. Select an entry to see each change (a setting's before → after, a mod file added, disabled, re-enabled or updated) and its status: Applied, Waiting for restart, Not applied, Cancelled or Undone. A change the post-exit helper couldn't apply shows the helper's reason ("Last attempt failed: … (try 2 of 3 at restart)"), and a dropped one "Not applied: …". The same failures are written to `latest.log` once per helper run.
- **Undo this**: undo a single History entry, not only the last apply or everything. You see the usual confirmation list first. A change that a later apply changed again is skipped ("Changed again by a later apply"), together with the rest of its group. **Undo last** and **Undo all** moved from the main screen into History.
- **Memory steps for your launcher**: RigTune recognises the Modrinth App, Prism Launcher, ATLauncher, the CurseForge app and the official Minecraft Launcher. It names the launcher in the header ("Memory 2.0 GB of 16 GB, set in the Modrinth App") and adds that launcher's click steps under every memory advice. It detects the launcher on your PC only: from a few named system properties and environment variables the launchers set, and, for Prism and CurseForge, their instance files in the game folder or the folder above it. The Modrinth App, Prism and ATLauncher steps are taken from the launchers' own source code; CurseForge's and the official launcher's come from CurseForge's blog and third-party guides. An unrecognised launcher gets the generic advice, as before. **Copy report** adds the launcher's name and nothing else about it.
- **Report a problem**: copies the full report to your clipboard and shows Minecraft's own "open this link?" screen with a link to a new GitHub issue, with the title and report field filled in from the link (your versions and as much of the report as fits). RigTune sends nothing itself; nothing is posted until you submit the issue on GitHub.
- **Preview** (next to Apply): lists exactly what Apply would do for the ticked items, file by file: settings written now (old → new), Sodium, Distant Horizons and Iris keys changed at the next restart, mod files downloaded or renamed to `.disabled`, and anything Apply would skip, with the reason. It writes and downloads nothing; with Modrinth on, it only looks up versions and dependencies, as Apply would.
- **Shader advice** on the benchmark result: when the shader cost was measured, you reach your target only with shaders off, and the pack takes at least 10% of your 1% lows, it suggests a lighter profile in the pack's own settings. It changes nothing in Iris or the pack.
- **spark hint**: with the spark profiler mod installed, an info advice explains how to capture a profile (`/sparkc profiler start`, then `stop`) and that stopping uploads it, with your player name and UUID, mod list, system details and Java launch arguments, to spark.lucko.me behind a link anyone with it can open.
- New benchmark runs record their context in `benchmarks.json` (Distant Horizons and shaders on or off, the shader pack, the window size, fullscreen), so later versions can compare like with like.
- **Translations**: a translator guide in the README ("Translating RigTune"); text RigTune builds itself (recommendation titles, undo, download and preview messages) now goes through translation keys; a test checks every language file's keys and arguments. RigTune still ships in English only. The recommendation text from the rules file, mod names, **Copy report** and **Report a problem** stay English.
- For contributors and maintainers:
  - client game tests run in CI on Linux for every supported version (26.2 on OpenGL; 26.3 on OpenGL and on Vulkan; Mesa software rendering under Xvfb), on the jar that ships (`runProductionClientGameTest`), with screenshots and logs kept for each leg;
  - `tools/add_mc_version.py` adds a Minecraft version in one step after checking Mojang's version manifest, Fabric, Fabric API and Mod Menu; `tools/mc_apidiff.py` checks every Minecraft, Fabric and Mod Menu member RigTune uses against another version (see `tools/MC_VERSIONS.md`);
  - the release workflow publishes every version in `versions/`, uploads to Modrinth the same files it attached to the GitHub release, and refuses a tag that doesn't match `mod_version`.

### Changed
- The RigTune screen's buttons: **History…** replaces Undo last and Undo all, **Preview** sits after Apply, and **Report a problem** after Copy report.
- Benchmark in your own world: Tune tests at most 8 render distances above the one you start at, because every distance it tests makes the game load, generate and save more of your world; the benchmark menu says so. The benchmark world keeps the full range.
- The benchmark world's camera is 16 blocks above the terrain (y 133 on 26.2 and 26.3); it was 10 blocks above the highest block at that spot, which depended on a tree. A game test checks on every supported version that the scene isn't mostly ocean, flat, or a single biome.
- Hardware tiers: the RX 9070 GRE is tier 4 (was 5, through the RX 9070 row); the Arc Pro B50 is tier 3 and the Arc Pro B60/B65/B70 tier 4 (were Intel's fallback, tier 2), both marked as discrete GPUs; the RTX 5050 has its own tier-3 row (same tier as before). RigTune 0.1.x keeps its old classification for all of them. These cards' exact driver strings weren't seen in a real log; a card that reports a different name is classified as before.
- The "Experimental Vulkan renderer" advice is shown only before Minecraft 26.4, which makes Vulkan the default.
- "Give Minecraft more memory" now says "at least 4 GB", so it no longer contradicts the Distant Horizons and shader memory advice shown with it.
- Modrinth lookups ask for the exact Minecraft version string the game reports (it differs from Fabric's normalised one only on snapshots and pre-releases); the rules still use the normalised version.
- The rules updater targets the supported Minecraft versions (26.2 and 26.3) and their hotfixes, instead of the three newest releases. Rules revision 13.

### Fixed
- **Benchmark render distances above your starting one were measured on the starting terrain.** RigTune changed render distance in memory only, so the game's server never sent the extra chunks, and Tune could choose a render distance your PC can't hold. Every step, and the restore, now tells the server the new distance, then waits until the chunks around you (out to one less than that distance) have arrived and been built, for 20 s at most. A step that timed out with more than 2% of them missing doesn't count as reaching your target and is marked "✘*".
- A stale jar in `mods/update/` no longer cancels a staged addition, or an undo, of a mod that isn't loaded. RigTune now cancels its staged change only for a loaded mod whose own update is waiting there (the notice says "change"), and the ticks on the RigTune screen follow what is really still staged.
- Modrinth "incompatible" checks judge the mods as they'll be after the updates you ticked: updates are planned before additions; an addition that clashes only with a mod's old version goes in with that mod's update (both apply or neither); an update's own "incompatible" entries, and those of mods you already have, are checked too. An update whose Modrinth data changed since the list was made is refused until you try again.
- Those messages name the other mod (its title, else its slug, else "another mod") instead of a Modrinth version id.
- A failed change no longer shows two different counts in one line: the helper's own retries stay in its message, and RigTune's restarts read "try 2 of 3 at restart" (History) or "restart attempt 2 of 3" (`latest.log`).
- After applying staged changes, the toast says "RigTune's staged changes were applied." (it said "Mod files and Sodium settings were updated." for Distant Horizons and Iris changes too).
- The benchmark chart's dates are in local time (they were UTC).
- The first 0.3 start after 0.1.x no longer logs a stack trace for each jar named in 0.1.x's last apply that is gone now.
- CI: a malformed version folder name can no longer split a game-test command.

### Compatibility
- 0.3.0 changes no file format. Every file 0.1.0 and 0.2.0 wrote keeps working, and a downgrade to 0.2.0 keeps working too: 0.2.0 ignores the two new optional fields (`context` on new benchmark runs, `lastWarnedApply` in `rigtune.json`) and drops them when it rewrites the file, so after a downgrade and re-upgrade the failed-change lines may be logged once more.
- A 0.1.x client never gets an unsafe or less conservative recommendation from `rules-v1.json`: its hardware tables are still 0.1.0's, and the only recommendation change it sees is the "at least 4 GB" wording. 0.2.x gets the other rules changes (hardware rows, spark hint, Vulkan advice) through `rules-v2.json`.

### Known issues
- On some Windows machines vanilla Minecraft 26.3 crashes natively during startup (around the time its sound system starts), with or without RigTune. It can take a few relaunches.
- An installed 0.1.0 or 0.2.0 is offered the 0.3.0 update only once the Modrinth listing is approved: RigTune finds its own update through Modrinth, which doesn't list versions of a project that is still in review.
- Quilt isn't supported (see the [README's FAQ](https://github.com/chaotix345/rigtune#does-rigtune-work-with-quilt)).
- MultiMC and other Prism-family launchers are named Prism Launcher and get Prism's steps. Other launchers (GDLauncher, for example) aren't recognised and get the generic advice.
- The incompatibility checks don't see changes an earlier Apply already staged: an addition that clashes only with the old version of a mod whose update is already waiting for a restart is refused until after that restart, and the opposite case isn't caught. Of two updates Modrinth marks incompatible with each other, one is refused.
- Preview can't show what Apply only learns once a file is downloaded (a file that isn't a Fabric mod, or a mod you already have, is left out; the screen says so), and Modrinth's answers can change between Preview and Apply.
- History names mod files, not mod names: the history file records only the file and the mod id.
- Measuring the shader cost turns shaders off and on through Iris, which re-saves `config/iris.properties` and the active pack's settings file with a new date line; every value stays the same. Iris also re-saves `iris.properties` at every game start.

## [0.2.0] - 2026-09-25

### Added
- Multi-version support: Minecraft Java **26.3**, alongside 26.2, built from one source tree with Stonecutter. Each version ships its own jar (`rigtune-<version>+mc26.2.jar` / `+mc26.3.jar`).
- Rules schema v2 (`rules-v2.json`): new condition fields (`gpuModelMatches`, `displayPixelsAtLeast`/`AtMost`, `modVersion`, `mcVersionRange`), `settingLabels`, and fail-closed evaluation (an unknown condition field never makes a rule fire). A safe v1 projection (`rules-v1.json`) keeps existing 0.1.x installs on a conservative subset, never less safe than before.
- **Undo RigTune**: every change RigTune makes (settings, mod installs/updates/disables, config patches) is journaled to `config/rigtune/history.json`. "Undo last apply" and "Undo everything" show a confirmation screen of exactly what will be reverted (and what will be skipped, with a reason), then revert it — immediately for settings, after a restart for mod/file changes, through the same safe post-exit helper as Apply.
- **Benchmark v2**: a dedicated benchmark world (no save required, reachable from the title screen) for repeatable results; simulation distance tuning alongside render distance (singleplayer); a **Measure before / Measure after** mode that reports the real 1% low/average gain from a change; Distant Horizons and shader cost reports (measured, not auto-tuned); a small chart of recent runs on the result screen.
- **Distant Horizons and Iris settings**: RigTune reads and patches `config/DistantHorizons.toml` and `config/iris.properties` (new `PATCH_TOML`/`PATCH_PROPERTIES` staged operations, type-preserving), and recommends DH/Iris values by hardware tier alongside Sodium and vanilla settings.
- **Settings screen** (`config/rigtune/settings.json`, opened from the RigTune screen or Mod Menu's config button): a master network switch, separate switches for remote rules and Modrinth (lookups/downloads), a startup-toast switch, default goal, and benchmark scene. Turn network off and RigTune falls back to on-device advice with zero requests.
- **Copy report**: a button that puts a Markdown summary of your hardware, recommendations and latest benchmark on the clipboard, with no file paths or user names.
- The RigTune project was created on [Modrinth](https://modrinth.com/mod/rigtune) and submitted for review; a v0.1.0 version was uploaded there too so existing 0.1.0 installs can find the 0.2.0 update.

### Changed
- Nvidium's recommendation now checks the GPU model directly (a Turing-or-newer allowlist) instead of a raw hardware-tier floor, so laptop RTX 20-series, RTX 3050 and GTX 16-series GPUs are correctly offered Nvidium; being a beta renderer pinned to an exact Sodium build, it's now opt-in (unticked by default) rather than ticked automatically.
- 32 upstream mods were triaged and marked reviewed; LambDynamicLights is now tracked (an unticked, never force-recommended "disable" suggestion on weak GPUs) instead of unseen.
- Ixeris keeps its "always recommend" rule on both versions; its reason notes that its raw-input batching gain isn't ported to 26.3 yet.

### Fixed
- A mod update whose downloaded jar isn't a readable Fabric mod is refused (the installed jar stays), and at apply time every jar RigTune enables is re-checked to be the mod it claims to be.
- A malformed or oversized `fabric.mod.json` in any jar in `mods/` can no longer crash the post-exit helper (it only fails that jar's own check), and a crafted jar can no longer exhaust the game's memory when the Undo screen reads it.
- RigTune no longer offers its own update for a mod that already has an update of its own waiting in `mods/update/` (e.g. Distant Horizons' self-updater); it shows a note instead, and cancels an update of that mod it had already staged.
- Two mods that conflict with each other are never offered or installed together, and Modrinth's "incompatible" dependencies are honoured (one naming a specific version only for that version).
- RigTune no longer offers to update Distant Horizons while DH's own auto-updater is on (the two updaters raced to replace the same jar at exit, which made a real update fail). It shows an info note instead.
- Distant Horizons render-distance caps now apply only while DH rendering is on, and DH thread caps only when Chunky isn't installed.
- A benchmark measured in a throttled or unfocused window (e.g. with Dynamic FPS) is stopped and not saved.
- **The post-exit helper retries a locked file for much longer.** Real-world feedback: a large mod jar (e.g. Distant Horizons) still open a few seconds after Minecraft exits (the Modrinth App re-scanning the instance, or an antivirus scan) made the helper give up after its old ~3 s of retries and leave the change pending. It now backs off exponentially (300 ms, doubling, capped at 5 s) over a ~30 s budget for this kind of sharing violation, and waits 2 s (was 1 s) after the game process exits before it even tries.
- **A startup race that could leave RigTune "Offline".** If the hardware/mod scan finished before the local rules were loaded (or a remote rules fetch tied the local revision), RigTune could skip its Modrinth lookups entirely until the player pressed Rescan. Lookups are now triggered by whichever of the scan and the rules load finishes last.
- A null/unreadable mod id on a staged mod-enable operation no longer bypasses the duplicate-mod-id safety check; such a file is rejected instead of being installed unvalidated.
- `sodium.*` setting keys from the rules file are now validated against the same safe character set as `dh.*`/`iris.*`, closing a defense-in-depth gap (not reachable through the shipped recommendation flow, but hardened anyway).
- `-Drigtune.rules.baseUrl` (test-only) now requires https, except on localhost, matching the existing Modrinth test property.
- Restored a 0.1.x safety check that had been narrowed during v2 rules development: `rules-v1.json`'s Nvidium "disable" suggestion for weak NVIDIA GPUs is exactly as conservative as it was in 0.1.0.

### Compatibility
- A 0.1.x client never gets an unsafe recommendation from `rules-v1.json`.
- Every file 0.1.0 wrote (`pending.json`, `last-apply.json`, `rules-cache.json`, `rigtune.json`, `helper/`) keeps working after an upgrade to 0.2.0; a one-time "legacy import" records 0.1.0's last apply into the new undo history.
- `config/rigtune/settings.json` is a new, separate file, so a downgrade back to 0.1.x (which only knows `rigtune.json`) can't reset the new network switches on a later re-upgrade.

### Known issues
- On some Windows machines vanilla Minecraft 26.3 crashes natively while starting its sound system (OpenAL), with or without RigTune. Relaunching usually works.
- A GitHub-installed 0.1.0 is offered the 0.2.0 self-update only once the Modrinth listing is approved (RigTune finds its own update through Modrinth).
- If you downgrade to 0.1.x with Distant Horizons or Iris setting changes still staged, the 0.1.x helper can't apply them and drops them after 3 exits; 0.1.x also ignores the new network switches.
## [0.1.0] - 2026-09-24

### Added
- Initial release, for Minecraft Java 26.2.
- Hardware detection: CPU, RAM, RAM allocated to Minecraft, GPU (model, VRAM, driver, OpenGL or Vulkan), monitor resolution and refresh rate, and whether the PC is a laptop running on battery.
- Mod scanning: detects installed performance mods, mods that are obsolete or conflicting, and available Modrinth updates.
- Recommendations, each with a plain-English reason and an impact rating: performance mods suited to the detected hardware, mods to disable, mod updates, and vanilla/Sodium video settings for the detected hardware tier and chosen goal (Performance / Balanced / Quality).
- In-game benchmark: sweeps render distance, measures real frame times (average and 1% lows, FPS cap lifted), and finds the highest render distance the PC can hold at the monitor's refresh rate.
- Safe apply: video settings take effect immediately; mod installs/updates/disables and Sodium config changes are staged and applied by a helper process after Minecraft closes; disabled mods become `.jar.disabled` rather than being deleted; every download is SHA-512 verified against Modrinth.
- A rules file fetched from GitHub at startup (with a bundled offline fallback) drives recommendations without requiring a mod update; a weekly GitHub Action rebuilds it from live Modrinth data.
