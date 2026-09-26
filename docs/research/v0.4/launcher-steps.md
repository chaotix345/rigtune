# Launcher steps: where to change memory and Java arguments (for v0.4 items 2g and 6)

Research only, read 2026-09-26. Nothing in the repo or any worktree was changed; no git, no Java process. Open-source launchers: UI strings read from the launchers' own GitHub source with `gh api` (tag + commit below; "same on default branch" means the text and logic were checked there too). Closed-source launchers: only their own help or support pages. Background (jvm-gc.md §1.1): HotSpot uses the **last** `-Xmx` on the command line.

Already verified in v0.3 (docs/v0.3/design/ws-c.md) and re-confirmed here: the memory steps for the Modrinth App, Prism and ATLauncher. New here: every Java-arguments field; MultiMC, PolyMC and GDLauncher; primary sources for CurseForge and the official launcher; and whether the memory field beats an `-Xmx` typed into the Java arguments.

## Summary

| Launcher | Memory (the `ram-*` steps) | Java arguments (the `jvm-*` steps) | Memory field beats a typed `-Xmx`? | Evidence |
|---|---|---|---|---|
| Modrinth App | instance → Instance settings (gear) → Sync overrides → **Custom memory allocation** (switch) → slider | same tab → **Custom Java arguments** (switch; the box starts with the global arguments). Global: Settings → Synced settings → Java and memory → **Java arguments** | **No**: typed `-Xmx` wins | source, v0.21.5 |
| Prism Launcher | right-click → **Edit...** → Settings → Java → tick **Memory** → **Maximum Memory Usage** | same tab → tick **Java Arguments** → box. Global: **Settings...** → Java → General → Java Arguments | **Yes** | source, 11.1.0 |
| MultiMC | right-click → **Edit Instance** → Settings → Java → tick **Memory** → **Maximum memory allocation** | same tab → tick **Java arguments** → box. Global: **Settings** → Java → **JVM arguments** | **Yes** | source, 0.6.16 + develop |
| PolyMC | **Edit Instance...** → Settings → Java → tick **Memory** → **Maximum memory allocation** | same tab → tick **Java arguments**. Global: **Settings...** → Java → **JVM arguments** | **Yes** | source, 7.1 |
| ATLauncher | instance **Settings** button → **Java/Minecraft** → **Maximum Memory/Ram** | same tab → **Java Parameters** (no switch; the box starts with the instance's or global value). Global: main **Settings** tab → Java/Minecraft → Java Parameters | **Yes**: the UI refuses to save `-Xmx` in Java Parameters | source, v3.4.41.3 |
| GDLauncher (Carbon) | instance → **Settings** tab → **Instance Java Memory** (switch) → slider. Global: Settings (gear) → Java → **Java Memory** | instance → Settings tab → **Instance Java Arguments** (switch; starts **empty**, plus a "Prepend global Java arguments" switch). Global: Settings (gear) → Java → **Java Arguments** | **No**: typed `-Xmx` wins | source, develop 35533d7 (no tags exist) |
| GDLauncher (legacy) | right-click → **Manage** → Overview → **Override Java Memory** | Overview → **Override Java Arguments**. Global: Settings (cog) → Java → **Java Custom Arguments** | **No** | source, last commit 2022-07-30; discontinued |
| CurseForge app | pack: My Modpacks → ⋮ → **Profile Options** → Memory Settings → **Custom RAM Allocation** (radio button) → slider. Global: cog → Minecraft → Java Settings → **Allocated Memory** | pack: Profile Options → Advanced Settings → **Additional Arguments**. Global: cog → Minecraft → **Default Additional Arguments** | **UNVERIFIED** (closed source; the support pages don't say) | official support articles + their screenshots |
| Official Minecraft Launcher | Installations → select the installation → More Options → **JVM arguments**: the `-Xmx` value | the same field | n/a: `-Xmx` in that field *is* the memory setting | Mojang help articles |

## Findings that affect existing work

1. **Existing strings.** `steps.prism`, `steps.modrinth_app`, `steps.atlauncher` and `steps.curseforge.global` are correct (the last one is now confirmed by CurseForge's own support page). Two need changes:
   - `steps.curseforge.pack` says "turn on Custom RAM Allocation". It is a **radio button** under "Memory Settings" (the other choice is "CurseForge Settings - 4096MB"), so it should say "choose".
   - `steps.official` says "this installation's three-dot menu → Edit". Mojang's own articles say "Select the installation you want to change → Select More Options on the Edit installation screen", then "Select the Save button". The three-dot step isn't proven wrong, but no primary source has it. Reword to Mojang's steps (below).
2. **Two wrong line numbers in ws-c.md's Prism citation.** The text of the string is unaffected. The Java tab is `MinecraftSettingsWidget.ui:576-578`, not `:616-639` (that range is the Tweaks tab). Memory is made checkable at `JavaSettingsWidget.cpp:80`, not `:81` (line 81 is the Java Arguments box).
3. **Whether the memory field wins depends on the launcher.** jvm-gc.md §1.1's rule, "heap advice points at the memory control, never the Java arguments box", is only safe for Prism, MultiMC, PolyMC and ATLauncher. In the **Modrinth App and GDLauncher**, an `-Xmx` typed in Java arguments overrides the slider, so a `ram-*` step that says "set the slider" does nothing for those users. The probe can see this case: `getInputArguments()` then holds two `-Xmx` tokens, and in these two launchers the later one is the typed one. A proposed extra string is in the last section.
4. **ATLauncher won't save `-Xmx` in Java Parameters.** The same goes for `-XX:PermSize` and `-XX:MetaspaceSize`. The error is "The entered Java Parameters were incorrect. Please remove any references to Xmx or XX:PermSize." A `jvm-*` or `ram-*` text must never tell ATLauncher users to type `-Xmx` there. `-Xms` isn't checked.
5. **The official launcher's defaults changed in 26.1, per Mojang.** Article 41950300066573: "Beginning with Minecraft: Java Edition version 26.1-snapshot-1, the default garbage collector has changed". Its fix is to replace `-XX:+UseZGC` in the JVM arguments field with the old G1 set. Article 39083573916941: "after version 26.1-snapshot-2, the default memory allocation ... changed from 2 GB to 4 GB". It tells players with under 4 GB of RAM to go back to 2 GB. Three consequences:
   - jvm-gc.md §1.1's official-launcher row (`-Xmx2G` + the G1 set, logged by launcher 3.22.20 on a 2025 version) is probably stale for 26.x.
   - The AC6.2 fixture "the official launcher's logged default line" may not be what 26.x players have.
   - A player following Mojang's own 2 GB advice ends up on ZGC with a 2 GB heap, which is exactly `jvm-zgc-small-heap`'s case.

   I haven't seen a real 26.x official-launcher command line, so this rests on Mojang's articles only.
6. **PolyMC will be detected as "MultiMC" under the v0.4 plan.** PolyMC 7.1 sets `INST_NAME`/`INST_ID` (`launcher/minecraft/MinecraftInstance.cpp:453-454`). It sets no `multimc.instance.title`, no `org.prismlauncher.*` and no `minecraft.launcher.brand`: the only `System.setProperty` in its launcher wrapper is `minecraft.applet.TargetDirectory`, at `libraries/launcher/org/polymc/impl/OneSixLauncher.java:121`. Its brand is `program_info/CMakeLists.txt:11` `"PolyMC"`, but nothing passes that to the game. Under SPEC 2g, `INST_*` alone maps to MULTIMC, so PolyMC users see the name "MultiMC". The MultiMC click steps still work for them word for word (PolyMC's labels differ only by "Edit Instance..." and the mnemonics).

   If WS-A wants to tell them apart: v03-deferred §g says real MultiMC sets `multimc.instance.title` (`OneSixLauncher.java:81-82`, not re-read here). Then `INST_*` without that property would mean PolyMC or another fork.
7. **How to read Mojang's help pages headless.** This fixes ws-c's "couldn't be read": the page is a React app, but `https://help.minecraft.net/help_center/en-us/articles/<id>` returns the article as JSON. The URL pattern comes from `/hc/static/js/Title.nBC09pEN.js` (`getArticle` → `help_center/<locale>/articles/<id>`).

## Per launcher

### Modrinth App: modrinth/code v0.21.5, commit 22ccdc1 (2026-09-23), every cited file the same on `main`
- **Instance:** the instance page's gear button, "Instance settings" (`apps/app-frontend/src/pages/instance/components/page-header/index.vue:147-154`, message `instance.action.settings` at `:219-221`) → tab "Sync overrides" (`.../settings-modal/index.vue:99-106`). That tab's content, `synced-options-settings.vue`, renders `<JavaSettings/>` after the sync toggles (`:682`, import `:41`).
  - In `.../settings-modal/java-settings.vue`: "Custom memory allocation" (`:166-169`), its switch (`:217`) and slider (`:220-233`).
  - "Custom Java arguments" (`:174-177`, id `instance.settings.tabs.java.java-arguments`), description "Set Java arguments separately for this instance." (`:178-181`), switch `override-java-arguments` (`:349`), box (`:352-359`), placeholder "Enter Java arguments..." (`:182-185`).
  - The box starts with the global arguments when the instance has none (`:90-92`, and `:104-107` resets it to them when switched off). So "turn on, then edit" works whichever place the flag came from.
  - The same text is in the compiled catalog `apps/app-frontend/src/locales/en-US/index.json`.
- **Global:** `apps/app-frontend/src/components/ui/modal/AppSettingsModal.vue:147-154` tab "Synced settings" → `.../instances-synced-settings/launch-options.vue`: section "Java and memory" (`:112-115`), "Memory allocation" (`:152-155`), "Java arguments" (`:160-163`, placeholder `:164-167`, description "Arguments passed to Java when launching an instance." `:168-171`). How the settings window itself is opened: not checked.
- **Precedence:** `packages/app-lib/src/launcher/args.rs:162` pushes `-Xmx{max}M`, then `:205-209` appends the custom arguments with no filtering. `packages/app-lib/src/api/instance/run.rs:114-118` uses the instance's arguments, falling back to the global ones. **A typed `-Xmx` wins.**

### Prism Launcher: PrismLauncher/PrismLauncher 11.1.0, commit ea87ffc (committed 2026-08-29, released 2026-09-03); `develop` cf054d3 has the same text and logic, only line shifts
- **Instance:** `launcher/ui/MainWindow.ui:396` "&Edit..." (action `:391-404`) → page "Settings" (`launcher/ui/pages/instance/InstanceSettingsPage.h:53-55`) → tab "Java" (`launcher/ui/widgets/MinecraftSettingsWidget.ui:576-578`).
  - In `launcher/ui/widgets/JavaSettingsWidget.ui`: "Memor&y" (`:185`), "Ma&ximum Memory Usage:" (`:248`), "Java Argumen&ts" (`:368`, box `:378`).
  - At instance level both group boxes get a tick box (`JavaSettingsWidget.cpp:80` and `:81`). The Java Arguments tick is `OverrideJavaArgs` (`:132`, `:157`), and the box shows the effective value, global when not overridden (`:133`).
- **Global:** `MainWindow.ui:316` "Setti&ngs..." (toolbar and File menu) → `launcher/Application.cpp:899` "Settings" dialog → Java page (`:904`; `launcher/ui/pages/global/JavaPage.h:58` "Java") → tab "General" (`JavaPage.ui:37-38`), which holds the same JavaSettingsWidget with no tick boxes (`:70`).
- **Precedence:** `launcher/minecraft/MinecraftInstance.cpp:578-579` puts the custom arguments first ("custom args go first. we want to override them if we have our own here"), then `-Xms`/`-Xmx` at `:615-619`. `launcher/BaseInstance.cpp:485-487` splits the arguments without filtering. **The memory field wins.**

### MultiMC: MultiMC/Launcher tag 0.6.16, commit c6b6096 (2022-06-09); `develop` a305dab (2026-07-28) has the same labels and logic, only line shifts
- **Instance:** `launcher/ui/MainWindow.cpp:523` "Edit Instance". The action is on the instance toolbar (`:526`), and the right-click menu reuses those actions (`:903-916`). → page "Settings" (`launcher/ui/pages/instance/InstanceSettingsPage.h:42`) → tab "Java" (`InstanceSettingsPage.ui:46`).
  - "Memor&y" (`:97`, checkable in the .ui), "Maximum memory allocation:" (`:189`), "Java argumen&ts" (`:209`, checkable).
  - The page header reads "The settings here are overrides for global settings." ("Open Global Settings", `:29-32`). The box loads the effective value (`InstanceSettingsPage.cpp:254`).
- **Global:** `MainWindow.cpp:330` "Settings" → Java page: "Memory" (`JavaPage.ui:45`) and "JVM arguments:" (`:195`, a one-line field).
- **Precedence:** `launcher/minecraft/MinecraftInstance.cpp:313-314` (custom first, same comment as Prism), then `-Xms`/`-Xmx` at `:341-347`. `BaseInstance.cpp:268-270` doesn't filter. **The memory field wins.**

### PolyMC: PolyMC/PolyMC tag 7.1, commit 9b01b80 (2026-07-30); not compared with `develop`
- **Instance:** `launcher/ui/MainWindow.cpp:705` "Edit Inst&ance..." → "Settings" (`InstanceSettingsPage.h:62`) → "Java" (`InstanceSettingsPage.ui:46`): "Memor&y" (`:107`), "Maximum memory allocation:" (`:199`), "Java argumen&ts" (`:219`), both checkable.
- **Global:** `MainWindow.cpp:367` "Setti&ngs..." → Java: "Memory" (`JavaPage.ui:45`), "JVM arguments:" (`:175`).
- **Precedence:** `MinecraftInstance.cpp:397-398` custom first, then `:425-431` `-Xms`/`-Xmx`. **The memory field wins.**
- **Detection:** see finding 6.

### ATLauncher: ATLauncher/ATLauncher v3.4.41.3, commit 1dac5d8 (2026-09-19), every cited file the same on `master`
- **Instance:** "Settings" button (`src/main/java/com/atlauncher/gui/card/InstanceCard.java:78`) → tabs General, Java/Minecraft, Commands (`gui/dialogs/InstanceSettingsDialog.java:79-81`).
  - In `gui/dialogs/instancesettings/JavaInstanceSettingsTab.java`: "Maximum Memory/Ram:" (`:106`); "Java Parameters:" (`:294-295`, tooltip "Extra Java command line paramaters can be added here." with the typo in the source). There's no switch: the box starts with the instance's or the global value (`:323`), and a "Reset" button (`:326-327`) restores the global value.
- **Global:** main-window tab "Settings" (`gui/tabs/SettingsTab.java:172`) → "Java/Minecraft" (`gui/tabs/settings/JavaSettingsTab.java:637-639`): "Maximum Memory/Ram:" (`:89`), "Java Parameters:" (`:328-329`).
- **Precedence:** `mclauncher/MCLauncher.java:292-300` adds `-Xmx`, then `:342-349` appends Java Parameters. But the UI refuses `-Xmx`:
  - Instance: `JavaInstanceSettingsTab.java:579-588` (`isValidJavaParamaters`); Save only runs when that passes (`InstanceSettingsDialog.java:92-94`).
  - Global: `viewmodel/impl/settings/JavaSettingsViewModel.java:181-186` marks the field "Invalid!" and doesn't save it; the dialog is at `JavaSettingsTab.java:616`.
  - **The memory field always decides** (unless someone edits the instance file by hand).
- **i18n:** the `GetText.tr("...")` literals are the English source. `.pot` is generated at build time and only translated `.po` files are committed (`.github/workflows/download-translations.yml`).

### GDLauncher Carbon: gorilla-devs/GDLauncher-Carbon `develop`, commit 35533d7 (2026-08-27); the repo has no tags or releases
- **Global:** the top bar's gear (no text; `apps/desktop/packages/mainWindow/src/components/Navbar.tsx:128-133`) → tab "Java" (`pages/Settings/index.tsx:53-56`; key `settings:_trn_java` = "Java", `packages/i18n/locale/english/settings.json:69`) → "Java Memory" (`pages/Settings/Java.tsx:164`; `java.json:14`) and "Java Arguments" (`Java.tsx:224`; `java.json:15`).
- **Instance:** the instance page's "Settings" tab (`pages/Library/Instance/index.tsx:174-183`; `ui.json:20`), in `pages/Library/Instance/Tabs/Settings/index.tsx`:
  - "Instance Java Memory" (`:457`; `java.json:40`), its switch (`:460`; turning it on starts at half of total RAM, `:467`) and slider (`:480`).
  - "Instance Java Arguments" (`:540`; `java.json:41`), its switch (`:543-551`; turning it on sets the box to **empty**, `:551`), and "Prepend global Java arguments before instance extra Java arguments" (`:565`; `java.json:42`).
  - So a flag can sit in either box, which is why the proposed Java-arguments steps name both.
- **Precedence:** `crates/carbon_app/src/managers/minecraft/minecraft.rs:593-594` pushes `-Xmx`/`-Xms`, then `:665` appends the user's arguments "verbatim" (comment `:657-664`: no launch-time filter). **A typed `-Xmx` wins.**

### GDLauncher legacy: gorilla-devs/GDLauncher_LEGACY-Full-History `master` 2eb0989 (2022-07-30); last tag v1.1.25 (2022-06-16)
Discontinued: `gorilla-devs/GDLauncher` is archived, and its description points to Carbon. Both apps send brand `GDLauncher`, so RigTune can't tell them apart; the proposed strings target Carbon.
- **Global:** cog (`src/app/desktop/components/SystemNavbar.js:46-69`) → "Java" (`src/common/modals/Settings/index.js:95-97`) → "Java Memory" (`src/common/modals/Settings/components/Java.js:379`), "Java Custom Arguments" (`:430`).
- **Instance:** right-click → "Manage" (`src/app/desktop/components/Instances/Instance.js:340`) → Overview → "Override Java Memory" (`src/common/modals/InstanceManager/Overview.js:479`), "Override Java Arguments" (`:525`).
- **Precedence:** `src/app/desktop/utils/index.js:503-505` and `:608-609,616` put `-Xmx`/`-Xms` first and the custom arguments after, so **a typed `-Xmx` wins**. The default arguments (`utils/constants.js:9-14`) include `-Xms256m`.

### CurseForge app: closed source; support.curseforge.com (Freshdesk)
- **"Minecraft - Getting Started"** (article 9000218572, "Modified on: Thu, 4 Dec, 2025"), section "Additional Game Settings":
  - "You can set the amount of memory ... in two locations: The game's general settings ... The settings of a specific modpack/profile". "By default ... 4GB/4096MB."
  - "Additional Arguments ... can also be set in two places ... in the Minecraft settings screen ... and in the Profile Options window of a modpack."
  - Labels, read from the article's screenshots (app v1.286.0.27968):
    - Global page: "Java Settings", "Allocated Memory" ("Set the default memory allocated to Profiles", slider), and "Default Additional Arguments" (placeholder "Example: -server"). Attachment 9236211061 (also 9236354244).
    - Pack: "My Modpacks" → ⋮ → "Profile Options" (9236211286, 9236352498). Then "Memory Settings" with radio buttons "CurseForge Settings - 4096MB" / "Custom RAM Allocation", a slider and a MB box (9236211214), and "Advanced Settings" → "Additional Arguments" (9236352588).
- **"Minecraft Modpacks - Installation and Launch Issues"** (article 9000196081, modified 13 Jul 2026): "In the app's main menu, to your left, click on the cog to open the app's settings", then "Under 'Game Specific', select Minecraft".
- **UNVERIFIED:** whether "Allocated Memory" or "Custom RAM Allocation" beats an `-Xmx` in Additional Arguments, and whether a pack's Additional Arguments replace or add to the Default Additional Arguments. `minecraftinstance.json` has a top-level `javaArgsOverride` (null in every collected file; jvm-gc.md §1.1), but what it means is unverified.

### Official Minecraft Launcher: closed source; help.minecraft.net (read through the JSON endpoint in finding 7)
- **"Fix Minecraft: Java Edition Game Crashes by Checking Memory Allocation"** (39083573916941, updated 2026-09-25): "Select the Installations tab at the top. Select the installation you want to change. Select More Options on the Edit installation screen. Copy all the text in the JVM arguments field ... Locate the memory value in the JVM Arguments field in the Launcher (-Xmx2G) and change the number ... For example, changing the value to -Xmx4G will allocate 4GB ... Select the Save button."
- **"Change the Garbage Collector for Minecraft: Java Edition"** (41950300066573, updated 2026-09-25): the same path, and finding 5.
- There's no separate memory field and no global setting: each installation has its own JVM arguments, so the "overrides?" question doesn't apply.

## Proposed en_us.json strings

Same style as the existing `rigtune.launcher.*` values: lower-case start, " → ", ends with a full stop, read after "In <launcher>: ". "three-dot menu" is kept for CurseForge's ⋮ (seen in its screenshots).

```json
"rigtune.launcher.jvm_steps.atlauncher": "this instance's Settings button → Java/Minecraft → Java Parameters.",
"rigtune.launcher.jvm_steps.curseforge": "My Modpacks → this pack's three-dot menu → Profile Options → Additional Arguments, and Settings (gear icon) → Minecraft → Default Additional Arguments.",
"rigtune.launcher.jvm_steps.gdlauncher": "this instance → Settings tab → Instance Java Arguments, and Settings (gear icon) → Java → Java Arguments.",
"rigtune.launcher.jvm_steps.modrinth_app": "this instance → Instance settings (gear) → Sync overrides → turn on Custom Java arguments → edit the box.",
"rigtune.launcher.jvm_steps.multimc": "right-click this instance → Edit Instance → Settings → Java → tick Java arguments → edit the box.",
"rigtune.launcher.jvm_steps.official": "Installations → select this installation → More Options → JVM Arguments, then Save.",
"rigtune.launcher.jvm_steps.prism": "right-click this instance → Edit... → Settings → Java → tick Java Arguments → edit the box.",
"rigtune.launcher.name.gdlauncher": "GDLauncher",
"rigtune.launcher.name.multimc": "MultiMC",
"rigtune.launcher.steps.curseforge.pack": "My Modpacks → this pack's three-dot menu → Profile Options → Memory Settings → choose Custom RAM Allocation → set the slider.",
"rigtune.launcher.steps.gdlauncher": "this instance → Settings tab → turn on Instance Java Memory → set the slider.",
"rigtune.launcher.steps.multimc": "right-click this instance → Edit Instance → Settings → Java → tick Memory → Maximum memory allocation.",
"rigtune.launcher.steps.official": "Installations → select this installation → More Options → JVM Arguments: change the number in -Xmx (-Xmx4G is 4 GB), then Save.",
```

- **Changed:** `steps.curseforge.pack` ("turn on" → "choose"; adds "Memory Settings") and `steps.official` (Mojang's path plus "then Save"). **Unchanged:** `steps.prism`, `steps.modrinth_app`, `steps.atlauncher`, `steps.curseforge.global`.
- **Why these Java-arguments paths work either way:**
  - Prism, MultiMC and the Modrinth App: ticking or switching on the instance's own box shows the current arguments, so it works whether or not the instance already overrides the global value.
  - ATLauncher: the instance box always starts with the effective value.
  - CurseForge and GDLauncher Carbon: both places are named, because the flag can be in either (for CurseForge, which one applies is UNVERIFIED; Carbon's switch starts empty).
- **CurseForge Java arguments:** SPEC item 6 planned generic text here, because the field "can't be source-verified". It now has an official source for its location, so `jvm_steps.curseforge` is proposed. If the coordinator keeps it generic, drop that line.
- **PolyMC** is detected as MultiMC (finding 6). The MultiMC strings work for it unchanged.
- **Optional (finding 3), for the Modrinth App and GDLauncher only**, when `getInputArguments()` holds two `-Xmx` tokens: `"rigtune.launcher.xmx_in_java_args": "Your Java arguments also set -Xmx, and %s uses that instead of the memory slider: change or remove it there."` (`%s` = the launcher name key). Proposed wording, not in the SPEC.
