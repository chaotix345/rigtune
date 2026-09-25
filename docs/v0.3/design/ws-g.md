# WS-G: localisation (SPEC item 9)

Branch `feat/l10n`. Plan: docs/v0.3/plans/ws-g.md. Amendments applied: G-H1 (no Wave A contract; converted here), G-M1 (Text next to the Strings, no signature change, nothing persisted or on the helper path), G-M2 (AC9.3 as fixture checks; LangCheckTest (a) widened), B-L1 (AC6.4 verified here), X-L2 (AC9.4 as a CI screenshot pixel diff).

## Text
- `core/model/Text` (sealed, no Minecraft imports): `Translatable(key, fallback, args)` (the English template and its arguments: String, Number, Boolean or a nested Text), `Literal(value)` (data, never formatted or translated) and `Joined(separator, parts)` (sentences of one paragraph, `"; "` lists; blank parts dropped when made, the English trimmed, as the `(a + " " + b).trim()` it replaces).
- `english()` formats the fallback exactly as Minecraft's `TranslatableContents.decomposeTemplate` does. Checked with `javap -c` on 26.2's and 26.3's `minecraft-common.jar` (the bytecode is identical): pattern `%(?:(\d+)\$)?([A-Za-z%]|$)`, `%%` is `%`, `%s` takes the next unnumbered argument (a counter `%n$s` doesn't move), any other conversion, a stray `%` or a missing argument makes the whole template show raw; a null argument reads "null". `TextsTest` renders the same Texts through real `Component`s and gets the same strings.
- `client/ui/Texts.component(Text)`: `Component.translatableWithFallback(key, fallback, args)` (exists on 26.2 and 26.3, `javap`), so the language's template is used when it has the key and the English otherwise, resolved at render time like every other translatable.
- `TextException extends IOException` carries a Text for refusals the UI shows; its message is the English.

## What changed where (every String keeps its old English, G-M1)
| type | added | old API kept |
|---|---|---|
| `Recommendation` | components `titleText`, `reasonText`; `of(id, category, impact, Text, Text, action, selected)` sets `title`/`reason` to their English | 7-arg constructor (texts become literals of the Strings), `title()`, `reason()` |
| `UndoPlan.Item` | components `descriptionText`, `reasonText` (null when `reason` is); `Item.of(...)` | 6- and 4-arg constructors, `description()`, `reason()` |
| `DownloadPlanner.Result` | component `errorTexts` | 3- and 4-arg constructors, `errors()` |

`javap` on the built 0.3.0 jar lists every old constructor and accessor with its old descriptor, and `compileE2eUndoJava` plus `:26.2:compileE2eJava` against the released 0.1.0 and 0.2.0 jars (sha256 as in build.yml) compile.

Converted (keys, English unchanged):
- Recommender (`rigtune.rec.*`): Disable/Install/Update titles, the obsolete and "doesn't suit this hardware" reasons, the conflict title and reason, "RigTune doesn't also offer X (or Y), which conflict(s) with it", the alpha and availability notes, the update reason, the outside-the-mods-folder and bundled notes, queued update, "updates itself", "Update RigTune", and the setting title (`%s: %s → %s`, the label and values are arguments). ModrinthOffAdvice's four notes (`rigtune.rec.modrinth_off.*`, `rigtune.rec.network_off.*`). Rule-provided titles, reasons and advice text stay `Literal`.
- UndoPlanner (`rigtune.undo.item.*`, `rigtune.undo.reason.*`): every description (setting, enable, disable, re-enable, patch, unknown change, "(none)") and every reason constant (the constants stay: the game tests compare with them); "would stop the game from starting" lists its causes (`breaks.twice`, `breaks.missing`) as a `"; "`-joined Text, the causes sorted by their English as before.
- DependencyResolver and DownloadPlanner (`rigtune.download.*`): every refusal of their own is a `TextException`; `errorTexts` entries are `rigtune.download.error` ("%s: %s", the recommendation's title Text and the cause). "another mod" is a key.
- Client: RigTuneScreen shows titles/reasons through `Texts` (a vanilla setting's caption title through `rigtune.rec.setting.title`), the header's GB values and display size through `rigtune.unit.gb`, `rigtune.header.display_size(_hz)`; UndoScreen shows the item Texts; RealController joins `errorTexts` for "Download failed: %s".

Not changed on purpose:
- Persisted text and the helper: `last-apply.json` reasons (the History screen shows the helper's English reason as an argument), `history.json`, `benchmarks.json` (a benchmark check's `"failed: <message>"` keeps its format; the prefix is now the constant `SessionResult.NOT_MEASURED_FAILED`), `pending.json`. No class under core/apply, nor Journal/JournalChange/JournalEntry, changed; HelperLauncherTest passes.
- The share report (ShareReport reads the English Strings), the logs, DevAutorun's `PlannerResult.reason()` (log only).
- Error details from outside RigTune's checks stay `Literal`: network/HTTP and hash errors from the Modrinth client, SafeFileNames' unsafe-name error, a benchmark check's exception message.
- Sorting: recommendations are still ordered by their English title (the comparator reads `title()`), so a translated list keeps the English order.

## LangCheckTest (AC9.1, AC6.4)
`src/test/java/io/github/chaotix345/rigtune/LangCheckTest.java`, a source scan over `src/client/java` and `src/main/java` with a small Java lexer (comments and string contents masked, so parentheses and `+` inside strings or comments don't count):
- (a) client code: a string literal with a letter inside `literal(...)`; in `client/ui` also two or more letters inside `text(...)` / `centeredText(...)` or next to `+` / `+=`. Key-shaped literals (`rigtune.goal.`, `.tooltip`, full keys) are exempt. `ALLOWED` (file + literal + reason) is empty: the only hits on the Wave A tree were the header's `" Hz"` / `" GB"` (now keys) and BenchmarkResultScreen's `"failed: "` protocol prefix (now a constant).
- (b) every key-shaped `rigtune.*` / `key.rigtune.*` literal in client and core (Component.translatable, Text.of, LauncherInfo's name/steps tables, HistoryModel, UndoPlan problems, BenchmarkController refusals) is in en_us.json; `NOT_KEYS`: `rigtune.json` (file), `rigtune.dev.autorun` (system property).
- (c) every en_us.json key is written in the code or belongs to a family whose values come from the code: `rigtune.goal.<Goal>` (+ `.tooltip`), `rigtune.category.<Category>`, `rigtune.impact.<Impact>`, `rigtune.limit.<every limitingFactor TierCalculator returns over all tiers and goals>`, `rigtune.benchmark.scene.<Scene>`, `rigtune.benchmark.menu.scene.<Scene>.hint`, `rigtune.history.kind/status.*` (HistoryModel.kindKey/statusKey over the journal constants), BenchmarkController's `rigtune.benchmark.{cancelled,throttled}` + `.title`/`.body`, and `key.category.rigtune.rigtune` (Minecraft's name for the key-mapping category RigTuneClient registers; the test checks the registration line). Each family's prefix and suffix literals must appear in the code and each family key must be in en_us.json. Also: every `Text.of("key", "English"` in core has en_us.json's value as its English.
- (d) every other `<ll>_<cc>.json`: a flat object of strings, keys a subset of en_us.json's, the same argument set (`%s` counted in order, `%n$s` by position) and a template Minecraft can format; en_us.json's own templates must be formattable too.
- Self-tests plant a literal (in `literal`, `text`, `centeredText`, `+`, `+=`, and one in a comment that must not count), a missing key, an unused key, a family with a missing key and an unwritten prefix, a wrong English, and locale files with an extra key, a wrong argument set, a stray `%`, a non-string value and a bad file name.
- Dead keys removed: `rigtune.screen.benchmark`, `rigtune.screen.benchmark.needs_world` (the only ones (c) found).

## AC9.3 (pseudo-locale)
`TextChecks` (test helper): every `Translatable` key is in en_us.json and its fallback is the en_us.json value; a `Literal` outside an argument must be blank or rule data; rendered with a translator that uppercases every en_us.json template (keeping `%s`/`%n$s`/`%%`), nothing but argument values and rule data is lower case.
- Reports: `PseudoLocaleTest` over `L10nFixtures` (rules reaching every Recommender branch with text of its own, plus ModrinthOffAdvice with Modrinth off and with the network off), and the same through a Minecraft `Language` injected with the pseudo-locale (real Components). Example: `Faster net. RIGTUNE DOESN'T ALSO OFFER Noise OR Surface, WHICH CONFLICT WITH IT. (AVAILABILITY NOT CONFIRMED)`.
- Undo plans: an `@AfterEach` in UndoPlannerTest checks every plan its 70 tests make (description and reason Texts, their English equal to the Strings); `UndoPlannerTextTest` maps each reason constant to exactly one `rigtune.undo.reason.*` key.
- Planner/resolver errors: DependencyResolverTest's refusals go through a `refused(...)` helper that requires a `TextException` passing the check; an `@AfterEach` in DownloadPlannerTest checks every error (a literal cause is allowed only for the fake's download failure); `DownloadErrorTextTest` covers the refusals the other tests don't reach (no version, no file for an add and an update, outside the mods folder) and a download's own failure.
- The check has its own planted-problem test (a core-built literal, a missing key, a wrong English, a literal sentence joined to a key).

## AC9.4 / AC6.4 (screenshots unchanged in English)
Filled in with the final CI run below.

## Notes for other workstreams
- WS-P (feat/preview): PreviewScreen shows `Recommendation.title()` and planner detail Strings. After both merge, `Texts.component(r.titleText())` and `DownloadPlanner.Result.errorTexts()` would show them translated; LangCheckTest will scan PreviewScreen and the `rigtune.preview.*` keys like any other.
- New display text built in core should be a `Text.of("rigtune.<area>.<thing>", "English %s", args)` with the key added to en_us.json; LangCheckTest checks the English matches.
