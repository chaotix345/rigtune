# WS-E: settings screen, network switches, share report, RigTune screen buttons — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans (inline, this worktree) with superpowers:test-driven-development per task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** SPEC items 8 (settings screen + network switches, P1) and 10 (share report, P1) for RigTune 0.2.0 on MC 26.2 and 26.3, plus the new RigTune screen buttons (Settings, Copy report, Undo last, Undo all, Benchmark menu).

**Architecture:** Pure logic in new `core/` classes (ShareReport, ModrinthOffAdvice, GatedModrinthClient), unit-tested. Client code: a new `RigTuneSettingsScreen`, a new `StartupNotices` helper, and surgical, delegating edits in the hotspots (RealController, RigTuneClient). All switches live in `ClientSettings` (`config/rigtune/settings.json`), never in ClientState.

**Tech Stack:** Java 25, Fabric Loom 1.17 (Mojang names), Stonecutter 0.9.8 (26.2 committed), JUnit 5 + fabric-loader-junit, Fabric client game tests.

**Spec:** docs/v0.2/SPEC.md items 8 and 10 + "Amendments from the plan review"; docs/v0.2/PLAN.md "WS-E" + "Plan-review fixes by workstream → WS-E".

## Global Constraints
- Work only in `C:/Dev/Worktrees/rigtune-ui`, branch `feat/settings-ui`. Rebase onto `origin/feat/v0.2.0` at the end. Never commit while Stonecutter is switched away from 26.2.
- `export JAVA_HOME="C:/Dev/Tools/jdk/jdk-25.0.4.1+1"` before every `./gradlew`. `./gradlew build` must pass for both MC versions.
- `core/` has no Minecraft imports. Hotspot files (RealController, RigTuneClient, en_us.json, gametest fabric.mod.json) get surgical, delegating edits only.
- Switches (ClientSettings): `networkEnabled` (master: off = no request of any kind), `remoteRules`, `modrinth` (lookups, update checks AND downloads), `startupToast`, `benchmarkScene` (BenchmarkRequest.Scene names `CURRENT` / `BENCHMARK_WORLD`), `privacyNoticeShown`. The settings screen calls `controller.settingsChanged()` after a network change.
- Network off or Modrinth off: no Modrinth request at all; Add/Update recommendations become advice ("install it from your launcher"); the header says "Offline (network off in settings)".
- Share report: Markdown, ≤ 2000 characters by default with "(N more)", no file paths, user names or world names.
- Language keys: alphabetical position, prefixes `rigtune.settings.*`, `rigtune.share.*`, `rigtune.screen.*`.
- Game tests only while holding `C:/Dev/Worktrees/.gametest-lock` (atomic mkdir + owner.txt; never wait in a loop). New buttons must fit, without overlap, at 854×480 (GUI scale 2) and 1280×720 (GUI scales 2 and 3).
- Commit messages end with the two attribution lines (Co-Authored-By Claude Opus 5.5 (1M context), Claude-Session URL).
- Remote-rules gating itself lives in `RealController.loadRules`, owned by WS-A (`remoteRulesAllowed()`); WS-E does not edit loadRules.

## Verified API facts (javap, both versions)
- Clipboard: `Minecraft.keyboardHandler` (public final `KeyboardHandler`) with `setClipboard(String)` / `getClipboard()`; identical descriptors on 26.2 and 26.3 (checked with javap against `minecraft-clientonly-deobf-26.{2,3}.jar`). No Stonecutter conditional needed.
- `CycleButton.onOffBuilder(boolean)`, `CycleButton.builder(Function<T,Component>, T)`, `Builder.withValues/withTooltip/create(x,y,w,h,Component,OnValueChange)` identical on both.

## File map
| file | status | responsibility |
|---|---|---|
| `src/main/java/.../core/report/ShareReport.java` | new | Markdown share report, limit + "(N more)", path scrubbing |
| `src/main/java/.../core/report/ModrinthOffAdvice.java` | new | Report → Add/Update become informational advice |
| `src/main/java/.../core/modrinth/GatedModrinthClient.java` | new | ModrinthClient wrapper: no delegate call while the switch is off |
| `src/client/java/.../client/ClientSettings.java` | modify | `benchmarkSceneOrDefault()` |
| `src/client/java/.../client/StartupNotices.java` | new | one-time privacy notice decision; startup-toast gate |
| `src/client/java/.../client/RealController.java` | modify (hotspot) | gated client, fetchOnline short-circuit, advice transform, `shareReport()` |
| `src/client/java/.../client/RigTuneClient.java` | modify (hotspot) | startup-toast gate, privacy toast |
| `src/client/java/.../client/ui/RigTuneSettingsScreen.java` | new | the settings screen |
| `src/client/java/.../client/ui/RigTuneScreen.java` | modify (owned) | buttons, network header line, `headerLines()` |
| `src/client/java/.../client/compat/ModMenuIntegration.java` | modify (owned) | Mod Menu opens settings |
| `src/main/resources/assets/rigtune/lang/en_us.json` | modify (hotspot) | new keys |
| `src/gametest/java/.../gametest/UiGameTest.java` | new | game test |
| `src/gametest/resources/fabric.mod.json` | modify (hotspot) | register UiGameTest |
| `README.md` | modify | Privacy section |
| `docs/v0.2/design/ws-e.md` | new | design + deviations |

Tests: `src/test/java/.../core/report/ShareReportTest.java`, `.../core/report/ModrinthOffAdviceTest.java`, `.../core/modrinth/GatedModrinthClientTest.java`, `.../client/ClientSettingsTest.java`, `.../client/StartupNoticesTest.java`.

---

### Task 1: ShareReport (core, pure) — AC10.1

**Files:** Create `core/report/ShareReport.java`; Test `core/report/ShareReportTest.java`.

**Interfaces — Produces:**
```java
public final class ShareReport {
	public static final int DISCORD_LIMIT = 2000;
	public record Versions(String rigtune, String minecraft, String loader) {}
	public static String format(Report report, Versions versions, BenchmarkSummary benchmark);            // benchmark may be null
	public static String format(Report report, Versions versions, BenchmarkSummary benchmark, int maxChars);
	static String scrub(String text);   // path-like tokens → "<path>"
}
```

Output shape (English; Discord Markdown):
```
**RigTune 0.2.0-dev+mc26.2** · Minecraft 26.2 · Fabric Loader 0.19.5
**Hardware**
- CPU: AMD Ryzen 7 7800X3D 8-Core Processor (8 cores, 16 threads)
- GPU: AMD Radeon RX 7800 XT · driver 25.9.1 · OpenGL · 16 GB VRAM
- RAM 32 GB · heap 6.0 GB · display 2560×1440 @ 180 Hz
- Tier 4/5 · limited by CPU · goal Balanced
- Rules r2 (bundled) · offline
**Latest benchmark** 2026-09-25 · tune · current
- render distance 12 · avg 180 FPS · 1% low 170 FPS · target 165 FPS met
**Recommendations** (10; [x] = ticked)
__Warnings__
- Only 2 GB of RAM allocated (high)
__Remove mods__
- [x] Disable Indium (high)
(3 more)
```
Rules: categories in `Category` enum order, recommendations in report order within a category; `[x]` appliable and selectedByDefault, `[ ]` appliable and not, no box for informational ones; only titles (no reasons, no Action fields such as file paths); every free-text field goes through `scrub` and is clipped to 120 chars; recommendation lines are dropped from the end so the total incl. the "(N more)" line fits; a category header is never left without an item; if even the fixed part exceeds `maxChars`, the result is hard-cut to `maxChars`. No benchmark → the single line `**Latest benchmark** not run yet`.

- [ ] Step 1: Write `ShareReportTest` with: `headerHardwareAndGroups` (versions, CPU/threads, GPU+driver+backend+VRAM, RAM/heap/display/refresh, tier + limiting factor, goal, rules revision/source, online/offline, category order, markers); `benchmarkLineAndMissingBenchmark`; `unknownValuesPrintQuestionMarks` (vram -1, refresh -1, width 0); `truncatesWithMoreCount` (200 recs → length ≤ 2000, ends with `(N more)`, shown + N == 200); `customLimitAndNoDanglingHeader`; `hardCapWhenFixedPartTooLong` (maxChars 120); `noPathsOrUserNames` (DisableMod/UpdateMod paths under `C:\Users\alice\...`, a title containing `/home/bob/.minecraft/mods/x.jar` and `C:\Users\alice\x.jar`, reason with a path → output contains none of `alice`, `bob`, `.minecraft`, `\`; `https://modrinth.com/mod/x` survives scrub).
- [ ] Step 2: `./gradlew :26.2:test --tests '*ShareReportTest'` → FAIL (class missing).
- [ ] Step 3: Implement ShareReport (StringBuilder; `scrub` with patterns `(?i)(?<![\w])[a-z]:[\\/]\S*`, `\\\\\S+`, `~[\\/]\S*`, `(?<![\w:/.])/(?:[^\s/]+/)+[^\s/]*`).
- [ ] Step 4: Run → PASS.
- [ ] Step 5: Commit `feat(share): Markdown share report formatter (core)`.

### Task 2: Settings persistence, startup notices — AC8.2

**Files:** Modify `client/ClientSettings.java`; Create `client/StartupNotices.java`; Modify `client/RigTuneClient.java` (hotspot: 2 call sites); Tests `client/ClientSettingsTest.java`, `client/StartupNoticesTest.java`; lang keys `rigtune.settings.privacy_toast.title/body`.

**Interfaces — Produces:**
```java
// ClientSettings
public BenchmarkRequest.Scene benchmarkSceneOrDefault();          // unknown/null → CURRENT
// StartupNotices (package io.github.chaotix345.rigtune.client)
public static boolean takePrivacyNotice(ClientSettings settings, Path configDir); // true once, then persists privacyNoticeShown=true
public static boolean showSuggestionsToast(ClientSettings settings, long importantCount); // settings.startupToast && importantCount > 0
```
- [ ] Step 1: Tests. ClientSettingsTest: `missingFileGivesDefaults` (all six defaults), `partialFileKeepsOtherDefaults` (`{"networkEnabled":false}`), `corruptFileGivesDefaults`, `roundTrip`, `unknownSceneFallsBackToCurrent`, `switchesCombine` (remoteRulesAllowed/modrinthAllowed truth table), `v010RigtuneJsonStillLoadsAndDoesNotAffectSettings` (write 0.1.0's `{"goal":"PERFORMANCE","lastShownApply":"2026-09-24T10:00:00Z"}` to rigtune.json → ClientState has both, ClientSettings defaults, settings.json absent until saved). StartupNoticesTest: `privacyNoticeOnlyOnce` (true, then false, then false after reload), `suggestionsToastHonoursSwitch`.
- [ ] Step 2: Run → FAIL.
- [ ] Step 3: Implement. RigTuneClient.onTick: after `showNotices`, `if (StartupNotices.takePrivacyNotice(settings, configDir)) SystemToast.add(… rigtune.settings.privacy_toast.title/body with the key name …)`; toast block: `if (StartupNotices.showSuggestionsToast(settings, important))`.
- [ ] Step 4: Run → PASS; `./gradlew :26.2:compileClientJava`.
- [ ] Step 5: Commit `feat(settings): settings defaults, one-time privacy toast, startup toast switch`.

### Task 3: Modrinth gating + advice + shareReport in RealController — AC8.1 (Modrinth/network part)

**Files:** Create `core/modrinth/GatedModrinthClient.java`, `core/report/ModrinthOffAdvice.java`; Modify `client/RealController.java`; Tests `core/modrinth/GatedModrinthClientTest.java`, `core/report/ModrinthOffAdviceTest.java`.

**Interfaces — Produces:**
```java
public final class GatedModrinthClient implements ModrinthClient {
	public GatedModrinthClient(ModrinthClient delegate, BooleanSupplier allowed);
	// every method: if (!allowed.getAsBoolean()) throw new ModrinthException(0, "Modrinth is off in RigTune's settings"); else delegate
}
public final class ModrinthOffAdvice {
	public static final String ADD_NOTE = "Modrinth is off in RigTune's settings: install it from your launcher.";
	public static final String UPDATE_NOTE = "Modrinth is off in RigTune's settings: update it in your launcher.";
	public static Report apply(Report report); // AddMod/UpdateMod → Action.None, selectedByDefault false, reason + note; same id/category/impact/title; others untouched
}
```
RealController edits: field `settings = ClientSettings.shared(configDir)`; `modrinth = new GatedModrinthClient(new HttpModrinthClient(modVersion), settings::modrinthAllowed)`; `fetchOnline()` starts with `if (!settings.modrinthAllowed()) { online = OnlineDataFetcher.Result.offline(); rebuild(); return; }`; rebuild's `report = withoutStaged(built)` → `report = settings.modrinthAllowed() ? withoutStaged(built) : ModrinthOffAdvice.apply(withoutStaged(built))`; `shareReport()` override → `ShareReport.format(report, new Versions(modVersion, hw.mcVersion(), loader version), latestBenchmark())` ("" while report is null).
- [ ] Step 1: Tests. GatedModrinthClientTest: a fake `ModrinthClient` whose every method calls `fail(...)`; with allowed=false each of the 5 methods throws `ModrinthException` and the fake is never called; `OnlineDataFetcher(gated).fetchAll(...)` → `Result.offline()` data, no call; `DependencyResolver(gated…).resolve` throws IOException, no call; with allowed=true calls go through; switch read per call (toggle between calls). ModrinthOffAdviceTest: Add → informational ADD_MOD with note; Update → informational UPDATE_MOD with note; DisableMod / SetSetting / None untouched; report fields (hardware, tier, revision, online...) preserved; no appliable Add/Update remains.
- [ ] Step 2: Run → FAIL.
- [ ] Step 3: Implement both classes and the RealController edits.
- [ ] Step 4: Run all tests `./gradlew :26.2:test` → PASS.
- [ ] Step 5: Commit `feat(settings): gate every Modrinth request on the switches; Add/Update become advice`.

### Task 4: RigTuneSettingsScreen + Mod Menu

**Files:** Create `client/ui/RigTuneSettingsScreen.java`; Modify `client/compat/ModMenuIntegration.java`; lang keys `rigtune.settings.*`.

**Interfaces — Produces:**
```java
public class RigTuneSettingsScreen extends Screen {
	public RigTuneSettingsScreen(@Nullable Screen parent, RigTuneController controller);
	RigTuneSettingsScreen(@Nullable Screen parent, RigTuneController controller, ClientSettings settings, Path configDir);
}
```
Layout: title centred at y 15; one centred column, width `min(width - 32, 310)`, rows of 20 px with 4 px gaps from y 36: Network access (onOff), Rules updates from GitHub (onOff, inactive when network off), Modrinth (onOff, inactive when network off), Startup toast (onOff), Default goal (Goal cycle → `controller.setGoal`), Benchmark scene (Scene cycle: "Where you are" / "Benchmark world"). Each has a tooltip saying what is sent. Footer at `height - 28`: "Open RigTune" (only when the parent isn't a RigTuneScreen; opens `new RigTuneScreen(parent, controller)`) and Done. On change: set field, `settings.save(configDir)`; network/remoteRules/modrinth also `controller.settingsChanged()` and `rebuildWidgets()` (so the sub-switches' active state follows).
ModMenuIntegration: `parent -> new RigTuneSettingsScreen(parent, RigTuneClient.controller())`.
- [ ] Step 1: Implement (UI code: verified in the game test of Task 6, which is written against it; compile check here).
- [ ] Step 2: `./gradlew :26.2:compileClientJava :26.2:compileGametestJava` → OK.
- [ ] Step 3: Commit `feat(settings): RigTune settings screen; Mod Menu opens it`.

### Task 5: RigTuneScreen buttons + network header line

**Files:** Modify `client/ui/RigTuneScreen.java`; lang keys `rigtune.screen.*`.

Changes:
- Title row: a "Settings" button at the right edge (width `font.width(label) + 12`), opening `new RigTuneSettingsScreen(this, controller)`. The goal button and the tier badge room shrink by that width + GAP; the badge is drawn left of the Settings button (falls back into the header lines as today when it doesn't fit).
- Footer buttons, in order: Apply (N), Undo last (`new UndoScreen(this, controller, false)`), Undo all (`new UndoScreen(this, controller, true)`), [Discard pending], Benchmark… (`new BenchmarkMenuScreen(this, controller)`, always active), Rescan, Copy report (active when a report is shown; `minecraft.keyboardHandler.setClipboard(controller.shareReport())`, status `rigtune.share.copied` with the length, or `rigtune.share.unavailable` for an empty string), Done.
- Grid: `perRow = clamp((column + GAP) / (MIN_BUTTON + GAP), 1, n)`, `rows = ceil(n / perRow)`, then `perRow = ceil(n / rows)` (balanced), `buttonWidth = min(120, (column - GAP*(perRow-1)) / perRow)`, with `MIN_BUTTON = 88`. Rows centred as today.
- Header: when `!settings.networkEnabled` add a line `rigtune.screen.header.network_off` ("Offline (network off in settings): add and update mods in your launcher"), else when `!settings.modrinth` add `rigtune.screen.header.modrinth_off`; gold. Public `List<Component> headerLines()` accessor for the game test.
- [ ] Step 1: Implement; compile both source sets.
- [ ] Step 2: Commit `feat(ui): RigTune screen buttons (settings, copy report, undo, benchmark menu) and the network-off header`.

### Task 6: UiGameTest — AC8.1 (game part), AC10.2, layout at three sizes

**Files:** Create `src/gametest/java/.../gametest/UiGameTest.java`; Modify `src/gametest/resources/fabric.mod.json` (one entrypoint line).

Flow (real controller unless noted):
1. Title + report ready; `ClientSettings.load(configDir).privacyNoticeShown` is true (the privacy toast was taken by then).
2. Open RigTuneScreen; for 854×480@2, 1280×720@3, 1280×720@2: screenshot `ui-main-*` and `checkLayout(screen)` (every visible widget inside the screen, no two visible widgets overlapping; list excluded from the overlap test except vs buttons). With a pending-changes stub (delegates to StubController, `hasPendingChanges()` true) repeat the three sizes (8 footer buttons), screenshots `ui-stub-pending-*`.
3. Copy report: press `rigtune.screen.copy_report`; clipboard (`mc.keyboardHandler.getClipboard()`) equals `controller.shareReport()`, starts with `**RigTune `, ≤ 2000 chars, has no game-dir path, user home or `\`.
4. Undo last / Undo all / Benchmark… open `UndoScreen` / `UndoScreen` / `BenchmarkMenuScreen`; closing returns to a RigTuneScreen.
5. Settings: press `rigtune.screen.settings` → RigTuneSettingsScreen; three sizes screenshots `ui-settings-*` + checkLayout; toggle Network access off → settings.json has `networkEnabled:false`, Rules/Modrinth buttons inactive; screenshot; Done → RigTuneScreen, wait for a report: `headerLines()` has `rigtune.screen.header.network_off`, `report.online()` false, no appliable AddMod/UpdateMod; screenshot `ui-main-network-off`. Network on + Modrinth off → header `modrinth_off`. Restore all switches on (and saved).
6. Mod Menu: `new ModMenuIntegration().getModConfigScreenFactory().create(new TitleScreen())` is a RigTuneSettingsScreen; set it, press "Open RigTune" → RigTuneScreen.
- [ ] Step 1: Write the test + registration; compile.
- [ ] Step 2: Take the lock; `./gradlew :26.2:runClientGameTest`; release after the client exits; look at every screenshot; fix and repeat.
- [ ] Step 3: Switch to 26.3 is NOT needed (`:26.3:runClientGameTest` builds the generated sources); take the lock; run `:26.3:runClientGameTest` (retry up to 3 times on the known native sound-startup crash); look at the screenshots.
- [ ] Step 4: Copy 2–3 representative screenshots to `scratchpad/ws-e/screens/`; commit `test(ui): UiGameTest (settings, network-off header, copy report, layout at three sizes)`.

### Task 7: README Privacy + design notes
- [ ] README "Privacy": no telemetry; the three request kinds (GitHub raw rules; Modrinth: SHA-1 hashes of installed jars, candidate project ids/slugs, version lookups for updates and availability, downloads of files the player chose, RigTune's own update check); a table of the switches (Network access, Rules updates, Modrinth, Startup toast) with exactly what each stops; where they're stored (`config/rigtune/settings.json`); the one-time toast; offline behaviour. Mention the Copy report button contents (no paths/user names/world names; nothing is sent, it only goes to the clipboard).
- [ ] `docs/v0.2/design/ws-e.md` (after the rebase): decisions and deviations.
- [ ] Commit `docs: privacy switches in the README` and later `docs(v0.2): WS-E design notes`.

### Task 8: Review, rebase, CI
- [ ] superpowers:requesting-code-review on `git diff origin/feat/v0.2.0...HEAD`; fix high/medium.
- [ ] `git rebase origin/feat/v0.2.0` (hotspots: keep both sides); `./gradlew build` (both versions); rerun game tests if UI code changed in the rebase; push; `gh run watch <id> --exit-status`.
- [ ] superpowers:verification-before-completion; report ≤ 15 lines.

## Self-review
- SPEC 8: settings.json + defaults (T2), network master/remoteRules/modrinth/startupToast/goal/benchmarkScene (T2–T4), screen from RigTune + Mod Menu + link to main (T4, T5), save immediately + rescan (T4), privacy README + no telemetry + one-time toast (T2, T7), Add/Update → advice + header (T3, T5). AC8.1: T3 unit tests (Modrinth + master), T6 game test (header); remote rules → WS-A's gate (checked after the rebase). AC8.2: T2.
- SPEC 10: Copy report button (T5), content list (T1), pure core + ≤ 2000 + "(N more)" (T1), AC10.1 (T1), AC10.2 (T6).
- Names used across tasks: `ShareReport.format/Versions/DISCORD_LIMIT`, `ModrinthOffAdvice.apply`, `GatedModrinthClient(delegate, allowed)`, `StartupNotices.takePrivacyNotice/showSuggestionsToast`, `ClientSettings.benchmarkSceneOrDefault`, `RigTuneSettingsScreen(parent, controller)`, `RigTuneScreen.headerLines()`.
