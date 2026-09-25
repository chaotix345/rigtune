# WS-C: launcher-aware RAM advice, implementation plan

> **For agentic workers:** executed inline by the WS-C agent (superpowers:executing-plans style), TDD per task, one commit per task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** RigTune names the launcher that started the game and, under every `ram-*` advice, tells the player where that launcher's memory setting is ("In the Modrinth App: ..."), without reading anything beyond a few named signals.

**Architecture:** Pure detection in `core/launcher/` from injected signals (a map of selected system properties, a map of selected env vars, the game dir). The client's `LauncherProbe` fills those maps from exactly the named properties/env vars and runs the detector on `Probes.EXECUTOR` with a timeout. `RealController` keeps the result next to the report (it's not part of `Report`); `RigTuneScreen` renders the header memory line and the launcher line; `ShareReport` gets an overload that adds the launcher's name.

**Tech Stack:** Java 25, Gson streaming `JsonReader` (core may use Gson), JUnit 5, Fabric client game tests (Loom `ClientProductionRunTask` in CI).

**Spec:** docs/v0.3/SPEC.md item 5 + amendments C-M1, C-M2, C-M3, C-L1 (they override item 5's text); docs/v0.3/plan-review.md (WS-C); docs/research/v0.3/launcher-ram.md.

## Global Constraints
- `export JAVA_HOME="C:/Dev/Tools/jdk/jdk-25.0.4.1+1"` before every `./gradlew`; never `./gradlew --stop`; `./gradlew build` green for 26.2 and 26.3; committed Stonecutter version 26.2.
- `core/` has no Minecraft/Fabric imports; core code here is not on the helper path (no ApplyHelper reachability).
- Every UI string from `en_us.json` via `Component.translatable`; WS-C keys are `rigtune.launcher.*`, inserted in alphabetical position (between `rigtune.header.*` and `rigtune.limit.*`), never appended at the end.
- Don't edit build.gradle, stonecutter.gradle, settings.gradle, gradle.properties, .github/workflows/*, rules/*, RULES_SCHEMA.md, KnowledgeV2ScenarioTest.
- Hotspots: RealController / RigTuneController (new `default` method under `// v0.3 (WS-C)`) / RigTuneScreen (small helpers) / ShareReport (overload only; no signature change) / gametest fabric.mod.json (one line).
- Detection (C-M1): game dir and its parent only (absolute, normalized); open a file only if `Files.isRegularFile`; `instance.cfg` size checked (≤ 64 KiB) before opening and read bounded; `minecraftinstance.json` streamed with `JsonReader`, only the top-level `isMemoryOverride`, the stream capped at 32 MiB; the probe runs on `Probes.EXECUTOR` with a timeout; any Throwable → Unknown.
- C-M2: Modrinth App and Prism always get instance-level steps; CurseForge picks per-pack (`isMemoryOverride` true or unreadable) vs global (false). `MaxMemAlloc`/`allocatedMemory` are never read.
- C-M3: the official launcher gets a literal only if verified from public crash reports; otherwise it stays Unknown and the deviation is recorded.
- Privacy: nothing about the launcher leaves the machine except its name in the share report; no instance name, path or env value is stored in `LauncherInfo` or logged.
- Commit trailers: `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01BzjDikL2wqunaSiMjefimU`. No force push.

## File map
| file | responsibility |
|---|---|
| `src/main/java/.../core/launcher/Launcher.java` (new) | enum of launchers: id, English display name, name/steps lang keys |
| `src/main/java/.../core/launcher/LauncherInfo.java` (new) | record (launcher, memoryOverride); `known()`, `nameKey()`, `stepsKey()` |
| `src/main/java/.../core/launcher/LauncherSignals.java` (new) | record of the injected signals + the exact property/env names read |
| `src/main/java/.../core/launcher/LauncherDetector.java` (new) | `detect(LauncherSignals)`: precedence, never throws |
| `src/main/java/.../core/launcher/InstanceFiles.java` (new) | bounded readers for `instance.cfg` and `minecraftinstance.json` |
| `src/main/java/.../core/launcher/LauncherAdvice.java` (new) | `stepsKey(Recommendation, LauncherInfo)`: the key for `advice:ram-*`, else null |
| `src/client/java/.../client/probe/LauncherProbe.java` (new) | reads the named properties/env; runs the detector on `Probes.EXECUTOR` with a timeout |
| `src/client/java/.../client/ui/RigTuneController.java` | `default LauncherInfo launcher()` |
| `src/client/java/.../client/RealController.java` | probe in `rescan()`, `launcher()`, share report name |
| `src/client/java/.../client/ui/RigTuneScreen.java` | header memory line; launcher line under `ram-*` advice; rebuild on launcher change; `launcherLines()` accessor |
| `src/main/java/.../core/report/ShareReport.java` | `format(..., String launcher)` overloads; `- Launcher: <name>` line |
| `src/main/resources/assets/rigtune/lang/en_us.json` | `rigtune.launcher.*` keys |
| `src/test/java/.../core/launcher/LauncherDetectorTest.java` (new) | AC5.1 |
| `src/test/java/.../core/launcher/LauncherScenarioTest.java` (new) | AC5.2 (bundled rules × launchers) + lang keys exist |
| `src/test/java/.../core/report/ShareReportLauncherTest.java` (new) | AC5.4 |
| `src/test/java/.../client/probe/LauncherProbeTest.java` (new) | timeout / failure → Unknown; only named signals read |
| `src/test/resources/launcher/**` (new) | real-format fixtures (Prism instance.cfg + mmc-pack.json, CurseForge minecraftinstance.json) |
| `src/gametest/java/.../gametest/LauncherGameTest.java` (new) + `src/gametest/resources/fabric.mod.json` | AC5.3 |
| `docs/v0.3/design/ws-c.md` (new) | sources (URL + date), decisions, deviations |

---

### Task 1: Verify sources (research, no code)
**Files:** Create `docs/v0.3/design/ws-c.md`.
- [ ] Two research subagents (read-only): (a) click-step wording from Modrinth App (modrinth/code app frontend), Prism (source), ATLauncher (source) UI strings, CurseForge best available (marked); Prism `InstanceType` in instance.cfg; (b) the official launcher's `minecraft.launcher.brand` from public crash reports + the MC jar's system report field; Loom's production run brand; real `minecraftinstance.json` key order and sizes.
- [ ] Record every source (URL, file:line, SHA/tag, date 2026-09-26/27) in ws-c.md; decide the official launcher (verified literal or Unknown + P1 deviation).
- [ ] Commit with Task 2 or on its own.

### Task 2: core launcher model + detector (AC5.1)
**Files:** Create `core/launcher/{Launcher,LauncherInfo,LauncherSignals,LauncherDetector,InstanceFiles}.java`; test `LauncherDetectorTest.java`; fixtures under `src/test/resources/launcher/`.

**Interfaces (produces):**
```java
public enum Launcher { PRISM, MODRINTH_APP, ATLAUNCHER, CURSEFORGE, UNKNOWN; // + OFFICIAL only if C-M3 verified
	public String displayName();   // English, for the share report; "" for UNKNOWN
}
public record LauncherInfo(Launcher launcher, @Nullable Boolean memoryOverride) {
	public static final LauncherInfo UNKNOWN;
	public boolean known();
	public @Nullable String nameKey();   // "rigtune.launcher.name.<id>" (literal keys in a switch)
	public @Nullable String stepsKey();  // "rigtune.launcher.steps.<id>", CurseForge ".curseforge.global" only when memoryOverride == FALSE
}
public record LauncherSignals(Map<String, String> properties, Map<String, String> env, @Nullable Path gameDir) {
	public static final List<String> PROPERTIES = List.of("org.prismlauncher.instance.name", "multimc.instance.title", "minecraft.launcher.brand");
	public static final List<String> ENV = List.of("INST_ID", "INST_NAME");
}
public final class LauncherDetector { public static LauncherInfo detect(LauncherSignals signals); } // never throws
final class InstanceFiles {
	static final int INSTANCE_CFG_MAX = 64 * 1024;
	static final long CURSEFORGE_MAX = 32L * 1024 * 1024;
	static boolean prismInstance(Path dir) throws IOException;          // instance.cfg (regular, <= 64 KiB, has InstanceType=) + mmc-pack.json (regular)
	static Optional<CurseForge> curseForge(Path dir);                     // present iff minecraftinstance.json is a regular file
	record CurseForge(@Nullable Boolean memoryOverride) {}
}
```
Precedence: (1) Prism property/env (non-blank) → PRISM; (2) brand `theseus` → MODRINTH_APP, `ATLauncher` → ATLAUNCHER; (3) for dir in [gameDir, parent]: Prism files → PRISM, `minecraftinstance.json` → CURSEFORGE(override); (4) verified official literal → OFFICIAL (after the files: CurseForge launches through the official launcher); (5) UNKNOWN.

- [ ] Write `LauncherDetectorTest`: no signal → Unknown; each launcher from its real signal (Prism: property; multimc property; INST_ID; INST_NAME; files from fixture at level 1 with game dir `minecraft/`; Modrinth: brand theseus; ATLauncher: brand ATLauncher; CurseForge: fixture file at level 0, override true/false/missing); precedence (Prism property beats theseus; theseus beats CurseForge file; Prism files beat CurseForge file at the same level; level 0 beats level 1; level 2 is never read); malformed instance.cfg (no InstanceType / binary) → not Prism; oversized instance.cfg (64 KiB + 1) → not Prism, no crash; directory named `instance.cfg` / `minecraftinstance.json` → ignored; symlink to a regular file → works (assumption-skipped where symlinks can't be created); FIFO named `instance.cfg` (Linux/macOS, `mkfifo`) → returns within 5 s, not Prism; malformed/truncated/non-object minecraftinstance.json → CURSEFORGE with null override; > 32 MiB file whose key comes after the cap → null override, no crash; key after a large `installedAddons` array within the cap → read; nested `isMemoryOverride` (not top-level) ignored; non-boolean value → null; blank brand/properties → ignored; gameDir null → Unknown; a signals map that throws → Unknown.
- [ ] Run `./gradlew :26.2:test --tests '*LauncherDetectorTest'` → FAIL (classes missing).
- [ ] Implement the classes.
- [ ] Run → PASS; commit `feat(launcher): detect the launcher from properties, env and instance files (AC5.1)`.

### Task 3: RAM advice mapping + scenario tests (AC5.2)
**Files:** Create `core/launcher/LauncherAdvice.java`; test `LauncherScenarioTest.java`; modify `en_us.json`.
```java
public final class LauncherAdvice {
	public static final String RAM_ADVICE_PREFIX = "advice:ram-";
	public static boolean isRamAdvice(Recommendation r);
	public static @Nullable String stepsKey(Recommendation r, LauncherInfo launcher); // null unless ram advice and launcher known
}
```
- [ ] Test over `RulesLoader.loadBundled()` + `Recommender.recommend`: scenarios (8 GB, 2 GB heap), (16 GB, 2 GB, DH), (32 GB, 6 GB, DH + shaders), (32 GB, 16 GB, CPU tier ≤ 3): each yields its expected `ram-*` advice (non-vacuous); for every known launcher each `ram-*` advice gets that launcher's steps key and no other recommendation gets one; Unknown gets none; CurseForge override true/null → `.curseforge.pack`, false → `.curseforge.global`. Plus: every key `LauncherInfo`/the UI can produce exists in en_us.json (classpath resource).
- [ ] Run → FAIL; implement `LauncherAdvice` + lang keys (`rigtune.launcher.advice`, `.header.cpu`, `.header.memory`, `.name.*`, `.steps.*`) in alphabetical position; run → PASS; commit.

### Task 4: share report launcher line (AC5.4)
**Files:** Modify `core/report/ShareReport.java`; test `ShareReportLauncherTest.java`.
- [ ] Overloads `format(Report, Versions, BenchmarkSummary, @Nullable String launcher)` and `format(Report, Versions, BenchmarkSummary, int maxChars, @Nullable String launcher)`; the existing two delegate with null (output unchanged). A non-blank name adds `- Launcher: <field(name)>\n` after the RAM line.
- [ ] Test: detected Prism with instance name "Secret Pack", INST_NAME, a game dir under `C:\Users\alice\...` → report has `- Launcher: Prism Launcher`, and none of the instance name, `alice`, `INST_`, `theseus`; Unknown / null → no Launcher line and the text equals the old overload's; existing ShareReportTest unchanged and green.
- [ ] FAIL → implement → PASS → commit.

### Task 5: LauncherProbe + controller wiring
**Files:** Create `client/probe/LauncherProbe.java`; modify `RigTuneController.java`, `RealController.java`; test `client/probe/LauncherProbeTest.java`.
```java
public final class LauncherProbe {
	public static final long TIMEOUT_MS = 3000;
	public static CompletableFuture<LauncherInfo> probeAsync(Path gameDir);                       // never completes exceptionally
	static CompletableFuture<LauncherInfo> probeAsync(Supplier<LauncherInfo> detect, Executor executor, long timeoutMs);
	static LauncherSignals signals(Function<String, String> property, Function<String, String> env, Path gameDir); // only the named keys
}
// RigTuneController, under // v0.3 (WS-C):
default LauncherInfo launcher() { return LauncherInfo.UNKNOWN; }
```
- [ ] Tests: a blocking detector → UNKNOWN after the timeout; a throwing detector → UNKNOWN; a rejecting executor → UNKNOWN; `signals()` asks only for `LauncherSignals.PROPERTIES`/`ENV` (recording lookups) and drops nulls.
- [ ] Implement; RealController: `volatile LauncherInfo launcher`, `rescan()` combines `LauncherProbe.probeAsync(gameDir)` before the report is built, logs the detected launcher name once per change, `launcher()` override, `shareReport()` passes `launcher.launcher().displayName()` (null when Unknown).
- [ ] `./gradlew :26.2:test` → PASS; commit.

### Task 6: UI (header memory line + launcher line)
**Files:** Modify `RigTuneScreen.java`.
- [ ] Header: when `controller.launcher().known()`, line 1 is `rigtune.launcher.header.cpu` (CPU, threads) and a new line `rigtune.launcher.header.memory` ("Memory %s of %s, set in %s") follows; otherwise the existing `rigtune.header.cpu` line is unchanged.
- [ ] `RecommendationEntry`: when `LauncherAdvice.stepsKey(r, launcher)` is non-null, extra wrapped lines of `rigtune.launcher.advice` ("In %s: %s") below the reason, in their own colour; `preferredHeight()` includes them. `launcherLines()` returns the built components (for the game test). `tick()` rebuilds when `controller.launcher()` changes.
- [ ] `./gradlew build` (both versions, gametest compile) → green; commit.

### Task 7: LauncherGameTest (AC5.3)
**Files:** Create `src/gametest/java/.../gametest/LauncherGameTest.java`; modify `src/gametest/resources/fabric.mod.json` (one entrypoint line).
- [ ] Log `minecraft.launcher.brand` and the Prism property/env presence at the start (the "without" case confirmed from latest.log in CI). Wait for the real report; assert the real controller's launcher is UNKNOWN; open `RigTuneScreen` on a stub controller whose report is the bundled rules' recommendations for (16 GB, 2 GB heap, DH) and whose `launcher()` delegates to the real controller; screenshots `launcher-unknown-*` at the 3 sizes; assert no launcher line and no memory header line.
- [ ] Test hook: `System.setProperty("minecraft.launcher.brand", "theseus")`, `real.rescan()`, wait for MODRINTH_APP; screenshots `launcher-modrinth-*` at 3 sizes; assert the header memory line and one launcher line per `ram-*` advice. `finally`: restore the property, rescan, wait for the report.
- [ ] Register in fabric.mod.json; `./gradlew build`; push; CI legs green; download artifacts and look at both screenshot sets; check latest.log for the logged brand; commit.

### Task 8: self-review, merge, finish
- [ ] Dispatch a code-reviewer subagent on `git diff origin/feat/v0.3.0...HEAD`; keep working; fix high/medium findings.
- [ ] When WS-0 has merged: `git merge origin/feat/v0.3.0`, `./gradlew build`, push, `gh run watch <id> --exit-status`, download the game-test artifacts.
- [ ] Finish docs/v0.3/design/ws-c.md (sources, decisions, deviations, AC evidence).
