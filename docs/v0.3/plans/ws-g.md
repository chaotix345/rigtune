# WS-G: localisation, implementation plan

> **For agentic workers:** executed inline by the WS-G agent (superpowers:executing-plans style), TDD per task, one commit per task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Every piece of display text RigTune's core builds reaches the screen through a translation key with an English fallback, a source-scan test keeps client code free of hard-coded words and en_us.json free of missing or dead keys, and translators have a guide; English players see exactly the same pixels as before.

**Architecture:** A core `Text` value (sealed: a translation key with its English template and arguments, a literal for data, or parts joined by a separator) is added **next to** the existing String fields (`Recommendation.titleText()`/`reasonText()`, `UndoPlan.Item.descriptionText()`/`reasonText()`, `DownloadPlanner.Result.errorTexts()`); every String keeps its exact English value (`text.english()`), so the share report, the e2e drivers, persisted files and the helper see no change. The client renders a `Text` with `Component.translatableWithFallback` (verified with javap on 26.2 and 26.3: `translatableWithFallback(String, String, Object...)` exists, and `TranslatableContents` uses `Language.getOrDefault(key, fallback)` as the template; the bytecode of both versions is identical). `LangCheckTest` scans sources; a pseudo-locale test renders fixture reports, undo plans and planner errors through an uppercasing translator.

**Tech Stack:** Java 25, JUnit 5 (unit tests have Minecraft on the classpath via fabric-loader-junit), Gson, Python 3 + Pillow for the screenshot diff (scratch only).

**Spec:** docs/v0.3/SPEC.md item 9 + amendments G-H1, G-M1, G-M2, B-L1 (AC6.4 moved here), X-L2 (AC9.4 = pixel diff of CI artifacts before/after); docs/v0.3/plan-review.md (WS-G); docs/research/v0.3/misc.md §D; docs/v0.3/design/ws-c.md (launcher keys live in core/launcher/LauncherInfo.java).

## Global Constraints
- `export JAVA_HOME="C:/Dev/Tools/jdk/jdk-25.0.4.1+1"` before every `./gradlew`; never `./gradlew --stop`; `./gradlew build` green for 26.2 and 26.3; committed Stonecutter version 26.2.
- `core/` has no Minecraft imports. Nothing reachable from ApplyHelper changes (core/apply, Journal, JournalChange, JournalEntry, PendingActions untouched); HelperLauncherTest passes.
- G-M1: `Text` is added next to existing String accessors; no existing signature or constructor disappears (`Recommendation`'s 7-arg constructor, `title()`, `reason()`, `UndoPlan.Item`'s 4- and 6-arg constructors, `description()`, `reason()`, `DownloadPlanner.Result`'s 3- and 4-arg constructors, `UndoScreen(Screen, RigTuneController, boolean)`); every String keeps its current English value. `./gradlew compileE2eJava compileE2eUndoJava` compiles.
- No persisted string changes: pending.json, last-apply.json, history.json, benchmarks.json (the benchmark's `"failed: <message>"` stays as it is).
- The share report stays English (it reads the String fields). Rule-provided titles/reasons, mod names, versions, file names, exception details stay literal data.
- New keys in alphabetical position under their prefix, never appended at the end: `rigtune.download.*` (planner/resolver errors), `rigtune.rec.*` (Recommender, ModrinthOffAdvice), `rigtune.undo.item.*`, `rigtune.undo.reason.*` (UndoPlanner), `rigtune.header.display_size*`, `rigtune.unit.*`. Every key's English equals the fallback in core.
- Hotspot edits stay small (WS-P adds a Preview button to RigTuneScreen and a RealController method in parallel): RigTuneScreen only where recommendation text/units are rendered; RealController only `finishDownloads`.
- Commit trailers on every commit (merges too): `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01BzjDikL2wqunaSiMjefimU`. No force push.

## File map
| file | responsibility |
|---|---|
| `src/main/java/.../core/model/Text.java` (new) | sealed `Text`: `Literal`, `Translatable(key, fallback, args)`, `Joined(separator, parts)`; `english()`, `render(Function<String,String>)`; MC's `%s`/`%n$s`/`%%` semantics |
| `src/main/java/.../core/model/TextException.java` (new) | `IOException` carrying a `Text` (message = English) |
| `src/client/java/.../client/ui/Texts.java` (new) | `Component component(Text)` |
| `src/main/java/.../core/model/Recommendation.java` | + components `titleText`, `reasonText` (7-arg constructor kept), `of(...)` factory |
| `src/main/java/.../core/recommend/Recommender.java` | builds titles/reasons as `Text` |
| `src/main/java/.../core/report/ModrinthOffAdvice.java` | notes as `Text`, keeps the texts |
| `src/main/java/.../core/history/UndoPlan.java` | `Item` + `descriptionText`, `reasonText` (4-/6-arg constructors kept) |
| `src/main/java/.../core/history/UndoPlanner.java` | descriptions/reasons as `Text` (String constants kept) |
| `src/main/java/.../core/modrinth/DependencyResolver.java`, `DownloadPlanner.java` | `TextException`s; `Result.errorTexts` |
| `src/main/java/.../core/benchmark/SessionResult.java`, `BenchmarkRun.java` | `NOT_MEASURED_FAILED = "failed: "` constant shared with the screen (value unchanged) |
| `src/client/java/.../client/ui/RigTuneScreen.java`, `UndoScreen.java`, `BenchmarkResultScreen.java`, `client/RealController.java` | render through `Texts`; units and display size through keys |
| `src/main/resources/assets/rigtune/lang/en_us.json` | new keys; dead keys removed |
| `src/test/java/.../core/model/TextTest.java` (new) | formatting semantics |
| `src/test/java/.../client/ui/TextsTest.java` (new) | client rendering = `english()` without a translation; the translation when present |
| `src/test/java/.../L10nFixtures.java` + `core/model/PseudoLocaleTest.java` (new) | AC9.3 |
| `src/test/java/.../LangCheckTest.java` (new) | AC9.1 checks (a)-(d) + self-test |
| `README.md` | "Translating RigTune" |
| `docs/v0.3/design/ws-g.md` (new) | design, evidence, deviations |

---

### Task 1: core `Text` and the client renderer
**Files:** create `core/model/Text.java`, `core/model/TextException.java`, `client/ui/Texts.java`, `src/test/.../core/model/TextTest.java`, `src/test/.../client/ui/TextsTest.java`.

**Produces:**
```java
public sealed interface Text {
  record Literal(String value) implements Text {}                       // data, never formatted
  record Translatable(String key, String fallback, List<Object> args) implements Text {} // args: String, Number, Boolean or Text
  record Joined(String separator, List<Text> parts) implements Text {}  // blank parts dropped at construction
  static Text literal(String value);            // null -> ""
  static Text of(String key, String fallback, Object... args); // null arg -> "null" (as MC and string concatenation show it)
  static Text join(String separator, Text... parts); static Text join(String separator, List<Text> parts);
  static Text sentences(Text... parts);         // join(" ", ...)
  String english();                             // render(key -> null)
  String render(Function<String, String> templates); // templates: key -> the language's template, or null for the fallback
  boolean isBlank();
}
public class TextException extends IOException { public TextException(Text text); public Text text(); } // getMessage() = text.english()
public final class Texts { public static Component component(Text text); } // Literal -> Component.literal; Translatable -> Component.translatableWithFallback(key, fallback, args mapped: Text -> component); Joined -> empty().append(part).append(separator)...
```
Formatting (mirrors `TranslatableContents.decomposeTemplate`, javap-verified): pattern `%(?:(\d+)\$)?([A-Za-z%]|$)`; `%%` → `%`; `%s` takes the next unnumbered argument (a counter that `%n$s` doesn't advance); `%n$s` takes argument n; any other conversion, a stray `%`, or an argument index out of range → the raw template. `Joined.english()` is `String.join(separator, parts)` stripped (the Recommender's `(a + " " + b).trim()`).

- [ ] Step 1: TextTest: `of("k", "Install %s", "Sodium").english()` = `"Install Sodium"`; positional `"%2$s then %1$s"`; mixed `"%s %1$s %s"` (unnumbered counter independent of positional); `"1%% low"` → `"1% low"`; `"%d"` and `"50%"` and `"%s %s"` with one arg → raw template; null arg → `"null"`; nested Text args; `render(k -> "X %s")` uses the template; a literal containing `%s` is never formatted; `join(" ", literal(""), of(...))` drops the blank part; `join` of all-blank parts is blank; `sentences(literal(" a "), literal("b")).english()` = `"a  b"`.
- [ ] Step 2: run `./gradlew :26.2:test --tests '*TextTest'` → fails to compile.
- [ ] Step 3: implement `Text`, `TextException`.
- [ ] Step 4: TextsTest (client unit test, real `Component`): for a list of Texts (literal, key with args, nested, joined, a malformed template), `Texts.component(t).getString()` equals `t.english()` (no RigTune translation loaded → fallback path, proving the core formatter agrees with Minecraft's); with `Language.inject(stub)` whose `getOrDefault(key, fallback)` returns `"<" + key + ">%s"` for one key, the component shows the stub's template (restore the previous language in `finally`).
- [ ] Step 5: implement `Texts`; run both tests green on 26.2; commit `feat(l10n): core Text value and the client renderer`.

### Task 2: Recommender and ModrinthOffAdvice build `Text`
**Files:** `core/model/Recommendation.java`, `core/recommend/Recommender.java`, `core/report/ModrinthOffAdvice.java`, en_us.json (`rigtune.rec.*`), test `core/recommend/RecommenderTextTest.java`.

**Produces:** `record Recommendation(String id, Category category, Impact impact, String title, String reason, Action action, boolean selectedByDefault, Text titleText, Text reasonText)`; the old 7-arg constructor delegates with `Text.literal(title)`, `Text.literal(reason)`; the compact constructor replaces null texts with literals of the Strings; `static Recommendation of(String id, Category c, Impact i, Text title, Text reason, Action a, boolean selected)` sets `title = title.english()`, `reason = reason.english()`.

Keys (English = today's text exactly):
```
rigtune.rec.alpha                      (alpha build)
rigtune.rec.availability_unknown       (availability not confirmed)
rigtune.rec.bundled                    It is bundled inside another mod, so it has to be removed together with that mod.
rigtune.rec.conflict.reason            %s and %s change the same parts of the game and shouldn't be installed together. Keep one and disable the other.
rigtune.rec.conflict.title             %s conflicts with %s
rigtune.rec.disable.title              Disable %s
rigtune.rec.install.keeps_out          RigTune doesn't also offer %s, which conflicts with it.
rigtune.rec.install.keeps_out.plural   RigTune doesn't also offer %s, which conflict with it.
rigtune.rec.install.title              Install %s
rigtune.rec.list.or                    %s or %s
rigtune.rec.modrinth_off.install       Modrinth is off in RigTune's settings: install it from your launcher.
rigtune.rec.modrinth_off.update        Modrinth is off in RigTune's settings: update it in your launcher.
rigtune.rec.network_off.install        Network access is off in RigTune's settings: install it from your launcher.
rigtune.rec.network_off.update         Network access is off in RigTune's settings: update it in your launcher.
rigtune.rec.obsolete.reason            %s is obsolete on this Minecraft version.
rigtune.rec.outside_mods_folder.remove It isn't in this instance's mods folder, so remove it in your launcher.
rigtune.rec.outside_mods_folder.update It isn't in this instance's mods folder, so update it in your launcher.
rigtune.rec.setting.title              %s: %s → %s
rigtune.rec.unsuitable.reason          %s doesn't suit this hardware.
rigtune.rec.update.reason              Version %s is available (you have %s).
rigtune.rec.update.title               Update %s
rigtune.rec.update_queued.reason       It's in mods/update, so RigTune leaves it alone.
rigtune.rec.update_queued.title        %s has an update of its own waiting
rigtune.rec.update_rigtune.reason      These recommendations are written for RigTune %s or newer and you have %s. Update RigTune so every suggestion is understood correctly.
rigtune.rec.update_rigtune.title       Update RigTune
rigtune.rec.updates_itself.reason      Its own auto-updater is on, so RigTune leaves its updates to it.
rigtune.rec.updates_itself.title       %s updates itself
```
The public/package String constants (`ALPHA_NOTE`, `AVAILABILITY_UNKNOWN_NOTE`, `OUTSIDE_MODS_FOLDER`, `ModrinthOffAdvice.*_NOTE`) stay with their values (tests and the game test use them).

- [ ] Step 1: RecommenderTextTest: the existing Recommender fixtures (RecommenderTest/ScenarioTest style: bundled rules, `Fixtures`) for each branch (obsolete, avoided, conflict, addition with 1 and 2 kept-out conflicts + alpha + unknown availability, update, update outside mods folder, queued update, self-updating, disable outside folder, bundled, setting, advice, update RigTune) and ModrinthOffAdvice (both switches): `titleText().english()` equals `title()`, `reasonText().english()` equals `reason()`, the title of each core-built branch is a `Translatable` with the key above; the setting title is `rigtune.rec.setting.title`; an advice title is a `Literal` of the rule's title.
- [ ] Step 2: run → fails (no `titleText()`).
- [ ] Step 3: implement (the Strings come from `english()`, so every existing Recommender/ModrinthOffAdvice test must stay green unchanged); add the keys.
- [ ] Step 4: `./gradlew :26.2:test` green; commit `feat(l10n): Recommender titles and reasons as Text`.

### Task 3: UndoPlanner descriptions and reasons
**Files:** `core/history/UndoPlan.java`, `core/history/UndoPlanner.java`, en_us.json (`rigtune.undo.item.*`, `rigtune.undo.reason.*`), test `core/history/UndoPlannerTextTest.java`.

**Produces:** `record Item(String description, Action action, String reason, boolean needsRestart, List<String> changeIds, List<String> opIds, Text descriptionText, Text reasonText)`; the 6-arg and 4-arg constructors delegate with literals (`reasonText` null when `reason` is null); `static Item of(Text description, Action a, Text reason, boolean needsRestart, List<String> changeIds, List<String> opIds)`.

Keys: `rigtune.undo.item.disable` "Disable %s", `.enable` "Enable %s", `.patch` "%s: %s", `.reenable` "Re-enable %s", `.setting` "%s: %s → %s", `.unknown` "Unknown change", `.unknown_file` "Unknown change to %s", `.none` "(none)"; `rigtune.undo.reason.<name>` for every reason constant (`absent_before`, `already_original` (%s), `breaks` (%s), `breaks.missing` "%s would be missing %s", `breaks.twice` "mod %s would be loaded twice (%s)", `changed_between`, `changed_since` (%s), `file_exists` (%s), `file_gone` (%s), `gone`, `group_changed`, `not_a_mod` (%s), `not_changeable`, `not_staged`, `preset_may_change`, `rigtune_jar`, `rigtune_staged`, `staged_together`, `superseded`, `superseded_group`, `update_staged` (%s)). `violations()` returns a sorted map English → Text so the "added problems" comparison is unchanged; BREAKS's argument is `Text.join("; ", ...)`.

- [ ] Step 1: UndoPlannerTextTest: (i) reflection over UndoPlanner's `static final String` reason constants: each equals the en_us value of exactly one `rigtune.undo.reason.*` key; (ii) fixture plans (a setting revert, a changed-since skip, a file revert, a staged discard with a staged-together mate, a superseded skip, a "would be missing" break): every item's `descriptionText().english()` equals `description()`, `reasonText` likewise, and every description/reason is a `Translatable` (or null reason).
- [ ] Step 2: run → fails. Step 3: implement. Step 4: `./gradlew :26.2:test` (all UndoPlanner tests unchanged and green); commit `feat(l10n): UndoPlanner descriptions and reasons as Text`.

### Task 4: planner and resolver errors
**Files:** `core/modrinth/DependencyResolver.java`, `core/modrinth/DownloadPlanner.java`, en_us.json (`rigtune.download.*`), test `core/modrinth/DownloadErrorTextTest.java`.

**Produces:** `record Result(List<Op> ops, List<String> ids, List<String> errors, Map<String, List<String>> opIds, List<Text> errorTexts)` (3-/4-arg constructors kept; errorTexts default to literals of errors); each error is `Text.of("rigtune.download.error", "%s: %s", rec.titleText(), cause)` where cause is the `TextException`'s text, else `Text.literal(e.getMessage())` (network, hash and file-name errors stay English data).

Keys: `rigtune.download.another_mod` "another mod", `.conflicts` "it conflicts with %s, which is being installed too", `.error` "%s: %s", `.incompatible` "Modrinth marks %s as incompatible with %s, which is installed", `.incompatible_both` "Modrinth marks %s and %s as incompatible, and both would be installed", `.incompatible_installed` "Modrinth marks %s, which is installed, as incompatible with %s", `.no_file` "No file for %s", `.no_version` "No %s version of %s for Minecraft %s", `.not_a_mod` "%s is not a Fabric mod jar (no readable fabric.mod.json id)", `.outside_mods_folder` "it isn't in this instance's mods folder; update it in your launcher", `.stale` "its Modrinth data changed since the list was made; try again", `.target_exists` "%s is already in the mods folder".

- [ ] Step 1: DownloadErrorTextTest with the existing planner/resolver fakes: each refusal (conflict in batch, no version, incompatible ×3 incl. "another mod", no file, not a mod jar, outside mods folder, stale data, target exists) → `errorTexts` entry is `rigtune.download.error` whose second argument is the expected key; `errors` unchanged English; an unrelated `IOException("boom")` → literal "boom".
- [ ] Step 2: run → fails. Step 3: implement. Step 4: `./gradlew :26.2:test` green (existing messages unchanged); commit `feat(l10n): download planner and resolver errors as Text`.

### Task 5: client rendering
**Files:** `client/ui/RigTuneScreen.java` (reason/title lines, `displayTitle` → Component via `rigtune.rec.setting.title`, `gb()` → `rigtune.unit.gb` "%s GB", display → `rigtune.header.display_size` "%s×%s" / `rigtune.header.display_size_hz` "%s×%s @ %s Hz"), `client/ui/UndoScreen.java` (item texts), `client/RealController.java` (`finishDownloads`: errorTexts joined with "; "), `client/ui/BenchmarkResultScreen.java` + `core/benchmark/SessionResult.java` + `BenchmarkRun.java` (`NOT_MEASURED_FAILED` constant, value "failed: "), en_us.json.

- [ ] Step 1: extend TextsTest: `RigTuneScreen.displaySize(w, h, hz)` and `gb(mb)` produce the same `getString()` as the old code for (1920, 1080, 144), (1920, 1080, 0), 2048 MB, 16384 MB, 0 MB.
- [ ] Step 2: implement; `./gradlew build` green on both versions; commit `feat(l10n): screens render core text through Texts`.

### Task 6: LangCheckTest (AC9.1, AC6.4) and dead keys
**Files:** create `src/test/java/io/github/chaotix345/rigtune/LangCheckTest.java`; en_us.json (remove `rigtune.screen.benchmark`, `rigtune.screen.benchmark.needs_world` and whatever else (c) finds).

Checks, each a static function over `(Map<String path, String source>, Map<String key, String template> lang, ...)` returning violation strings, so the self-tests feed planted inputs:
- (a) client code (`src/client/java`): string literals (comments stripped) with a letter inside `literal(...)`; in `client/ui` also literals with 2+ letters inside `text(...)`/`centeredText(...)` and literals with 2+ letters next to `+`/`+=`. Key-shaped literals (`^\.?[a-z0-9_]+(\.[a-z0-9_]+)*\.?$` with a dot) are exempt. Explicit allowlist `ALLOWED` (file + literal + reason), empty unless the scan finds a legitimate case.
- (b) every key-shaped literal starting `rigtune.`/`key.rigtune.` (not ending in `.`) in `src/client/java` and `src/main/java` (core, LauncherInfo's tables, HistoryModel, UndoPlan problems, Text.of keys) is in en_us.json; `NOT_KEYS` lists the non-key literals (`rigtune.json`, `rigtune.dev.autorun`) with reasons.
- (c) every en_us key is referenced literally, or belongs to a documented dynamic family whose suffixes come from the code: `rigtune.goal.<Goal>` (+ `.tooltip`), `rigtune.category.<Category>`, `rigtune.impact.<Impact>`, `rigtune.limit.<TierCalculator's limiting factors, from calculate() over tiers>`, `rigtune.benchmark.scene.<Scene>`, `rigtune.benchmark.menu.scene.<Scene>.hint`, `rigtune.history.kind.*`/`.status.*` (HistoryModel.kindKey/statusKey over the journal constants), `rigtune.benchmark.{cancelled,throttled}` + `.title`/`.body` (BenchmarkController), `key.category.rigtune.rigtune` (KeyMapping.Category from MOD_ID + "rigtune"); each family's prefix literal must itself appear in the code. Also every en_us template parses under Minecraft's rules and every `Text.of("key", "literal fallback"` in core has fallback = en_us value.
- (d) every other `<locale>.json` in the lang folder: keys ⊆ en_us, same argument-index set per key, valid template; and every file is a flat JSON object of strings.
- Self-test: planted `Component.literal("Hello")`, `graphics.text(font, "Hello", ...)`, `"Hz" + x` in a fake `client/ui` file → (a) violations; `Component.translatable("rigtune.nope")` → (b); an unused key → (c); `de_de` with an extra key, a missing `%s`, `%2$s` beyond range → (d); and the real tree → none.
- [ ] Steps: write the self-tests and the repo test → fail; implement; remove the dead keys; `./gradlew :26.2:test --tests '*LangCheckTest'` green; commit `test(l10n): LangCheckTest source scan; remove dead keys`.

### Task 7: pseudo-locale check (AC9.3)
**Files:** create `src/test/java/io/github/chaotix345/rigtune/core/model/PseudoLocaleTest.java` (+ fixtures shared with Tasks 2-4 via a small `L10nFixtures` helper if needed).
- Walk each fixture Text (Reports from Task 2's fixtures incl. ModrinthOffAdvice; UndoPlans from Task 3; planner/resolver errors from Task 4): a `Translatable` must have its key in en_us with fallback = en_us value; a `Literal` outside argument position must be rule data (a title/reason/text of the rules document used) or blank; arguments may be literals (names, versions, files).
- Render with `templates = key -> upper(en_us[key])` (uppercase outside `%` conversions): the output with every argument literal removed has no lowercase letter; the same through `Texts.component` with an injected uppercasing `Language` (client unit test, real Components).
- [ ] Steps: test → implement nothing new (the conversion is done) or fix any gap it finds; commit `test(l10n): pseudo-locale check of core-built text (AC9.3)`.

### Task 8: README "Translating RigTune" (AC9.2)
- Where files live (`src/main/resources/assets/rigtune/lang/<locale>.json`, locale codes from the Minecraft Wiki Language page), key naming (`rigtune.<area>.<thing>`), `%s` and `%1$s` arguments (same count; `%%` for a percent sign), formatting codes (`§` codes aren't used; colours come from the code), testing in game (build, drop the jar in, pick the language; F3+T reloads resources), LangCheckTest's locale check, what isn't translatable yet (rule text fetched from GitHub, mod names and versions, setting names from the rules, exception details and network errors, the share report), and how to submit (a PR adding one `<locale>.json`, a subset of en_us's keys is fine).
- [ ] Commit `docs: Translating RigTune`.

### Task 9: verification and hand-off
- [ ] `./gradlew build` (both versions), `./gradlew :26.2:test --tests '*HelperLauncherTest'`, `./gradlew compileE2eJava compileE2eUndoJava` (as CI runs them).
- [ ] Push; CI green on every job; `gh run download <run> -p 'gametest-screenshots-*'`; pixel diff (Pillow) against run 36182135247 and against the latest green feat/v0.3.0 run; list every differing frame with its reason (dates, FPS, rules r12 text are expected; text changes are not).
- [ ] Code review subagent on `git diff feat/v0.3.0...HEAD`; fix high/medium findings.
- [ ] docs/v0.3/design/ws-g.md; merge `origin/feat/v0.3.0` (keep both sides), `./gradlew build`, push, CI green.
