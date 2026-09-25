# WS-C: launcher-aware RAM advice (SPEC item 5)

Branch `feat/launcher-ram`. Plan: docs/v0.3/plans/ws-c.md. Sources were read on 2026-09-26 unless noted.

## What it does
- `core/launcher/` (pure Java, Gson only) detects the launcher from injected signals; `client/probe/LauncherProbe` fills the signals with exactly the names below and runs the detector on `Probes.EXECUTOR` once per session (the signals can't change while the game runs; memoized like `HardwareProbe.slowPart()`). Each caller waits at most 3 s (a `copy()` of the shared detection with `completeOnTimeout` → Unknown), and a detection that finishes later is used by the next rescan; any Throwable → Unknown. `LauncherProbe.reset()` exists for the game test only.
- `RealController.rescan()` probes the launcher next to the hardware and mod scans, before the report is built, and keeps the result beside the report (`RigTuneController.launcher()`, default `LauncherInfo.UNKNOWN`). It logs the launcher's name only ("RigTune: launcher Modrinth App").
- `RigTuneScreen` (the logic is in `client/ui/LauncherLines`): with a known launcher the header's CPU line drops RAM/heap and a new line reads "Memory 2.0 GB of 16 GB, set in the Modrinth App"; every advice whose recommendation id starts with `advice:ram-` (rule id `ram-*`, the convention WS-D documents in RULES_SCHEMA.md) gets one more wrapped line "In <launcher>: <steps>" in its own colour. With Unknown the screen is exactly as in 0.2. The screen rebuilds when the detected launcher changes.
- `ShareReport.format(..., String launcher)` overloads (C-L1: no signature change): a known launcher adds `- Launcher: <name>` after the RAM line; Unknown leaves the text byte-identical to 0.2's.

## Detection
Signals read (nothing else): system properties `org.prismlauncher.instance.name`, `multimc.instance.title`, `minecraft.launcher.brand`; environment variables `INST_ID`, `INST_NAME`; the files `instance.cfg`, `mmc-pack.json`, `minecraftinstance.json` in the game dir and its parent (absolute, normalized).

Order:
1. A non-blank Prism/MultiMC property or `INST_ID`/`INST_NAME` → Prism Launcher.
2. `minecraft.launcher.brand` = `theseus` → Modrinth App; = `ATLauncher` → ATLauncher (exact, case-sensitive).
3. For the game dir, then its parent: `instance.cfg` (regular file, ≤ 64 KiB, with an `InstanceType=` key) next to a regular `mmc-pack.json` → Prism; a regular `minecraftinstance.json` → CurseForge, with its top-level `isMemoryOverride` when readable.
4. `minecraft.launcher.brand` = `minecraft-launcher` → the official Minecraft Launcher.
5. Otherwise Unknown (today's generic wording, no launcher named).

**Deviation from SPEC item 5's order:** the official brand is checked after the instance files, not with the other brands. CurseForge starts the game through the official launcher and passes the same `minecraft-launcher` brand (public crash report below, game dir under `curseforge/minecraft/Instances`), so checking the brand first would name the wrong launcher for every CurseForge user.

C-M1 file rules:
- A file is opened only if `Files.isRegularFile` (follows symlinks: a symlink to a regular file works; a FIFO or a directory with that name is never opened).
- `instance.cfg`: `Files.size` ≤ 64 KiB checked before opening, then `readNBytes(64 KiB + 1)` (a file that grew in between is dropped). Malformed (no `InstanceType` key), oversized or unreadable → not a Prism instance by files; the properties/env still decide.
- `minecraftinstance.json`: streamed through Gson's `JsonReader` over a stream that fails after 32 MiB; a leading BOM is skipped; only the top-level `isMemoryOverride` is read (other values skipped, nested keys ignored); it stops as soon as the key is found. Missing, non-boolean, malformed, over the cap → CurseForge with no value. Real files are 7-345 KB and the key comes early (see below), so the cap never matters in practice.
- `MaxMemAlloc`/`allocatedMemory` are never read (C-M2): `Runtime.maxMemory()` already is the effective value.

C-M2 steps:
- Modrinth App, Prism and ATLauncher: the instance's own memory steps (turn the instance's override on, then set the maximum). They work whether or not the instance already overrides the global value.
- CurseForge: `isMemoryOverride` false → the app's global Java settings; true or unknown → the pack's Profile Options (works either way).
- Official launcher: each installation has its own JVM arguments (no global setting).

## Sources

### Brand literals (C-M3)
| launcher | literal | evidence |
|---|---|---|
| Official Minecraft Launcher | `minecraft-launcher` | Minecraft 26.2 client (`net.minecraft.client.Minecraft`, Loom's deobf jar `minecraft-clientonly-deobf-26.2.jar`, `javap -c`): `getLauncherBrand()` returns `System.getProperty("minecraft.launcher.brand")`, and `fillSystemReport` adds it as the "Launcher name" detail when set. Public crash reports of official-launcher users show `Launcher name: minecraft-launcher`: github.com/IrisShaders/Iris/issues/3330 (2026-09-12, `Launched Version: fabric-loader-0.19.5-26.1.2`), /issues/3237, /issues/2803 (`fabric-loader-0.16.14-1.21.7`). Re-checked by hand 2026-09-26. |
| CurseForge app | `minecraft-launcher` (same) | github.com/CaffeineMC/sodium/issues/3001: JVM arguments list `-Dminecraft.launcher.brand=minecraft-launcher` and `-Dminecraft.applet.TargetDirectory=C:\Users\User\curseforge\minecraft\Instances\...` (re-checked by hand 2026-09-26). |
| Modrinth App | `theseus` | modrinth/code `packages/app-lib/src/launcher/args.rs` (`${launcher_name}` → `"theseus"`); crash reports showing `Launcher name: theseus`: flathub/com.modrinth.ModrinthApp#100, IrisShaders/Iris#3098, #2817, #3077. |
| ATLauncher | `ATLauncher` | ATLauncher `src/main/java/com/atlauncher/constants/Constants.java` (`LAUNCHER_NAME`), substituted for `${launcher_name}` in `MCLauncher.java` (docs/research/v0.3/launcher-ram.md). No public crash report with it was found (not needed: source). |
| Prism Launcher | `PrismLauncher` (not used) | Crash reports: Admicos/minecraft-wayland#64, TreyRuffy/BetterF3#131, wired-tomato/WayGL#39, FrozenBlock/FrozenLib#79, IrisShaders/Iris#3272. RigTune doesn't need it: Prism's own properties and `INST_*` env are stronger signals. |
| Loom (the CI/dev runs) | none | FabricMC/fabric-loom `dev/1.17`: no `minecraft.launcher.brand` anywhere; `AbstractProductionRunTask.configureJvmArgs` (:167-170) and `ClientProductionRunTask` (:146-152) add only `-Dfabric.addMods` (and `-XstartOnFirstThread` on macOS); `GenerateDLIConfigTask` (:145-186) sets no brand; dev-launch-injector and fabric-loader have none. So `runClientGameTest` and `runProductionClientGameTest` start the game without a brand: the "without" case of AC5.3. |

### Instance files
- Prism-written `instance.cfg` (`[General]`, `ConfigVersion=1.2`, `InstanceType=OneSix`, `[UI]` section): github.com/Deepacat/Ae6r/blob/d49fd214b70a9576012fc8b7c797f1d402776aca/instance.cfg → fixture `src/test/resources/launcher/prism/instance.cfg`.
- MultiMC-style `instance.cfg` (no section, `InstanceType=OneSix` first) and `mmc-pack.json` (MC 26.3, Fabric Loader 0.19.5): github.com/Fabulously-Optimized/fabulously-optimized/tree/52482ada6fda025adbdb796c07f454f4cca9e396/MultiMC/Fabulously%20Optimized%20x.y.z → fixtures `launcher/multimc/*` and `launcher/prism/mmc-pack.json`.
- CurseForge `minecraftinstance.json`: github.com/EnigmaticaModpacks/CreateTogether/blob/fb56c63f0bbb5856cb865afd507017093fc857b2/minecraftinstance.json (218,082 bytes; `isMemoryOverride` is top-level key 13 of 42, `installedAddons` key 38; `installPath` is the game dir itself) → fixture `launcher/curseforge/minecraftinstance.json` (every top-level key kept, `installedAddons` cut to 2 entries, `installPath` user name replaced). Other real files (7,326 to 344,275 bytes; the key always before `installedAddons`; `allocatedMemory` an int): Mattabase/HauntedWatelands, GTModpackTeam/gregtech-for-magic-custom-edition, yves-chevallier/minecraft, GreatOrator/FallCraft, namick/all-the-mods-2.

### Click steps (the lang values)
The line reads "In <name>: <steps>". Open-source launchers: labels copied from their UI strings at the latest release and checked identical on the main branch. Closed-source: marked.

| key | text | source |
|---|---|---|
| `steps.modrinth_app` (instance) | this instance → Instance settings (gear) → Sync overrides → turn on Custom memory allocation → set the slider. | modrinth/code v0.21.5 (22ccdc1, 2026-09-23; identical at main 722cc35): `apps/app-frontend/src/pages/instance/components/page-header/index.vue:219-222` (`instance.action.settings` = "Instance settings", gear button :147-153); `.../settings-modal/index.vue:99-106` (tab "Sync overrides"; the others are General, Installation, Sharing); `.../settings-modal/java-settings.vue:166-169` ("Custom memory allocation", a Toggle :217, then a memory Slider :220-232 with no caption of its own). Spot-checked by hand. There is no instance-level "Java and memory" tab (that's the global "Synced settings" section, `AppSettingsModal.vue:147-154`, `launch-options.vue:112-115`, field "Memory allocation" :152-155). |
| `steps.prism` (instance) | right-click this instance → Edit... → Settings → Java → tick Memory → Maximum Memory Usage. | PrismLauncher 11.1.0 (ea87ffc, 2026-09-03; identical on develop cf054d3): `launcher/ui/MainWindow.ui:391-404` ("&Edit..."), `launcher/ui/pages/instance/InstanceSettingsPage.h:53-55` ("Settings"), `launcher/ui/widgets/MinecraftSettingsWidget.ui:616-639` (tab "Java"), `launcher/ui/widgets/JavaSettingsWidget.ui:185` ("Memor&y" group box, made checkable at instance level in `JavaSettingsWidget.cpp:81`: the tick is the override), `:248` ("Ma&ximum Memory Usage:"). Spot-checked by hand. |
| `steps.atlauncher` (instance) | this instance's Settings button → Java/Minecraft → Maximum Memory/Ram. | ATLauncher v3.4.41.3 (1dac5d8, 2026-09-19; identical at master 8e9a713): `gui/card/InstanceCard.java:78,718` (button "Settings"), `gui/dialogs/InstanceSettingsDialog.java:79-81` (tabs General, Java/Minecraft, Commands), `gui/dialogs/instancesettings/JavaInstanceSettingsTab.java:106` ("Maximum Memory/Ram:"). Spot-checked by hand. Global is Settings → Java/Minecraft → Maximum Memory/Ram (`JavaSettingsTab.java:89,637-639`), not used. |
| `steps.curseforge.pack` | My Modpacks → this pack's three-dot menu → Profile Options → turn on Custom RAM Allocation → set the slider. | **Not source-verified** (closed source): https://blog.curseforge.com/how-to-allocate-more-ram-to-minecraft/ (CurseForge's own blog), corroborated by gamehostbros.com, ghostcap.com and help.meloncube.net guides; support.curseforge.com has no article on it. |
| `steps.curseforge.global` | Settings (gear icon) → Minecraft → Java Settings → Allocated Memory. | **Not source-verified**: same sources. |
| `steps.official` | Installations → this installation's three-dot menu → Edit → More Options → JVM Arguments: change the number in -Xmx (-Xmx4G is 4 GB). | **Not source-verified**: Mojang's article (help.minecraft.net/hc/en-us/articles/39083573916941) renders client-side and couldn't be read headless (WebFetch, curl, Wayback); the path is the same in about six independent hosting guides. Detection itself is verified (above). |

"three-dot menu" is used for both "⋮" (CurseForge) and "..." (official) so no glyph depends on the font.

## Tests
- `core/launcher/LauncherDetectorTest` (AC5.1, 32 tests): every launcher from its real signals (properties, env, the real Prism/MultiMC/CurseForge files), precedence (Prism property beats `theseus`; the brand beats the files; the game dir beats its parent; Prism files beat a CurseForge file in the same folder; CurseForge and Prism beat the official brand), nothing above the parent read, normalized paths, malformed/binary/oversized (64 KiB + 1; exactly 64 KiB still read) `instance.cfg`, directories named like the files, symlinks, FIFOs (Linux/macOS, 5 s preemptive timeout; skipped on Windows), malformed/non-object/non-boolean/nested/truncated `minecraftinstance.json`, a BOM in either file, a file ending exactly at the cap, a key after a 20,000-entry array, the cap (unit cap and a real 33 MiB file), throwing signal maps, null maps.
- `core/launcher/LauncherScenarioTest` (AC5.2): the bundled rules for (8 GB, 2 GB), (16 GB, 2 GB, DH), (32 GB, 6 GB, DH + shaders), (32 GB, 16 GB, a tier-3 Xeon E5) yield `ram-low`, `ram-low` + `ram-distant-horizons`, `ram-distant-horizons-shaders`, `ram-huge`; each known launcher (CurseForge with override true/false/unknown) gets its steps key on every `ram-*` advice and on nothing else; Unknown gets none; every key exists in en_us.json with the right `%s` count and no `rigtune.launcher.*` key is unused.
- `core/report/ShareReportLauncherTest` (AC5.4): a Prism detection from signals full of an instance name and a user path yields exactly `- Launcher: Prism Launcher` and none of the name, path, env names or brand; Unknown → identical text; the name is escaped like any field.
- `client/probe/LauncherProbeTest`: a stalled detector times out as Unknown, a throwing one (also an Error) or a rejecting executor is Unknown, a late detection is kept for the next probe, the game probe detects once per session until `reset()`, and only the listed property/env names are asked for.
- `gametest/LauncherGameTest` (AC5.3): see "Evidence".

## Notes for other workstreams
- WS-G (LangCheckTest): the `rigtune.launcher.name.*` and `rigtune.launcher.steps.*` keys appear as string literals in `core/launcher/LauncherInfo.java` (a switch), and the client passes them to `Component.translatable(variable)`. A scan of client code alone won't see them used; include core or treat `LauncherInfo` as their source.
- WS-B/WS-F (RigTuneScreen): WS-C's changes are the `cpuAndMemory`/`launcherLine` helpers, two fields, one line in `init()`, the `tick()` condition and the entry's extra lines; nothing in the button row.

## Evidence
- Unit tests: `./gradlew build` green on 26.2 and 26.3, 894 tests each (1 skipped: the FIFO test on Windows; it runs on the Linux CI).
- CI run https://github.com/chaotix345/rigtune/actions/runs/36176467280 (head 96f09df, after merging WS-0): every job green, including client game tests on 26.2 OpenGL, 26.3 OpenGL and 26.3 Vulkan.
- AC5.3: each leg's artifacts have `launcher-none-*` and `launcher-modrinth-*` at 854x480@2, 1280x720@3 and 1280x720@2 (reviewed: the header reads "Memory 2.0 GB of 16 GB, set in the Modrinth App" and the ram-low advice ends with "In the Modrinth App: this instance → Instance settings (gear) → Sync overrides → turn on Custom memory allocation → set the slider."; without a brand the screen is as in 0.2). The "without" case is confirmed in each leg's latest.log: `LauncherGameTest: at start minecraft.launcher.brand=null; ... INST_ID set: false; INST_NAME set: false; detected: LauncherInfo[launcher=UNKNOWN, ...]`, and Loom's production run task sets no brand (source above). The job log prints no JVM arguments, so that line is the evidence.
- Local `runClientGameTest` on 26.2 under the lock (2026-09-26): BUILD SUCCESSFUL, same log lines and screenshots.

## Review (code-reviewer subagent on the branch diff)
Verdict "approve after fixing 1". Fixed: (1, medium) a queued or timed-out probe could replace a known launcher with Unknown: detection is now once per session with per-caller timeouts on a copy; (2) the raw U+FEFF in source: removed, Gson's JsonReader skips a BOM itself (test kept); (3) instance.cfg with a BOM: stripped; (4) the stream cap now ends normally at exactly the cap; (5) the game test's cleanup no longer hides the first failure and waits for the rescan to settle; (6) shareReport read on the client thread; (7) the start-up "no launcher" check fails only in CI; (8) the screen logic moved to `LauncherLines`, `launcherLines()` computed on demand; (9) the list keeps ≥ 60 px at every size (asserted); (10) official steps no longer assume -Xmx2G, "three-dot menu" for both launchers; (11) `@Nullable` on the new ShareReport parameter; plus an end-to-end test of CurseForge `isMemoryOverride: false` → global steps.

## Deviations and open items
- Precedence: the official launcher's brand is checked after the instance files (see Detection), because CurseForge sends the same brand.
- C-M3: the official launcher's literal is verified, so it's detected (not deferred). Its click steps, and CurseForge's, are from third-party/blog sources (not the apps' own UI strings): marked above.
- Prism's `instance.cfg` must contain `InstanceType`: Prism accepts a file without it but writes `InstanceType=OneSix` whenever it loads an instance (`launcher/minecraft/MinecraftInstance.cpp:241-242` at 11.1.0), so a launched instance always has it; without the key only the properties/env detect Prism (they're always set by Prism's launch wrapper).
- GDLauncher and other launchers stay Unknown (no verified literal).
