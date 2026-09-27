# WS-K: shared contracts and extension points (v0.5)

Branch `feat/v05-contracts` (worktree `rigtune-contracts5`), from `origin/feat/v0.5.0` @ 054cc882. Scope: docs/v0.5/PLAN.md
"The contracts commit (WS-K): exact contents" items 1-19, SPEC "Shared contracts" C1-C8, X4, AC-X.1..AC-X.3. Every stub
defaults to 0.4's behaviour: the full unit suite, LangCheckTest, PaletteTest, WordingTest and every game test stay green on
both nodes and all three CI legs.

This file is first the TDD task plan (committed before any code), then, once landed, "the contracts as landed" (every
signature, stub, extension point, anchor, skeleton method and fixture folder), the per-leg footprint baseline and the
pinned v040 imports. Every workstream reads the "as landed" part.

## TDD task plan

Each task: a red test first (or, for a pure no-op seam, a test that pins today's behaviour and the new API together),
then the code, then a commit. Local runs: `./gradlew :26.2:test --tests '<classes>'` inside a build slot; `:26.3:` for
the version-specific parts (none expected; X9); the full build is CI's.

| # | task | files | tests (red first) | closes |
|---|---|---|---|---|
| K1 | NoticePriority: the 14 slots in C3's order | `core/notice/NoticePriority` | `NoticeBoardTest.theFourteenSlotsInC3Order` (red: 6 slots) | AC-X.3 (order), AC7.12 (pin) |
| K2 | Render-thread window + flag in FootprintStats (never references V05Services) | `client/FootprintStats` | `FootprintStatsTest.aWorkerResolvingInsideTheWindowLeavesTheFlagUnset`, `.theRenderThreadInsideTheWindowSetsIt`, `.theRenderThreadAfterTheWindowLeavesItUnset` (red: no API) | AC-X.2 (unit) |
| K3 | Type stubs: `ModFilesPolicy`, `InstanceEvidence`, `LauncherModText`, `FirstRun.Status`, `FixOffer`, `FixSpec`, `TryItView`, `ServerProfilesView` | new core files | `V05StubsTest` (policy `of` = RIGTUNE for every launcher/evidence/opt-in; `guideLine` null; the EMPTY views; FixSpec constants) | C4 types |
| K4 | New state-file shells on StateStore: `FixStore` (stutter-fixes.json, 32 KiB), `TryItStore` (tryit.json, 16 KiB), `ServerProfileStore` (server-profiles.json, 16 KiB) | new core files | `V05StoreShellsTest`: for each store missing → empty + writable; corrupt → `.bad` + empty; newer → read-only, never written; over 4 × cap → left alone; unknown fields at any depth kept; formatVersion 1 written | C1 shells, X7 |
| K5 | Optional fields, old constructors kept: `JournalEntry.foldedEntryIds`, `BenchmarkRecord.Context.worldFresh/dhGenerating/stagedAtStart`, `StutterReport.settingsAtStart/settingsAtEnd`, `ClientSettings.modFilesByRigTune`, AwarenessStore's `acknowledgedStartupRegressions`/`acknowledgeStartupRegression` and `optionsAtExit`/`setOptionsAtExit`/`takeOptionsAtExit` | the records, `ClientSettings`, `AwarenessStore` | `V05OptionalFieldsTest` (round trips; absent = null/false; null not written; pinned 0.2.0/0.3.0 readers read the 0.5 files), `ClientSettingsTest.modFilesByRigTuneDefaultsOffAndRoundTrips`, `AwarenessStoreV05Test` (caps 64, consuming read, unknown fields kept, newer never written) | C1 fields |
| K6 | Rules model: `RulesDocument.stutterFixes` (lenient, per entry), `Condition.causeSpikesAtLeast` in `hasStutterKey`, evaluated UNKNOWN; updater key sets + SchemaConsistencyTest ties; pinned `v040/core/rules` copies + `LegacyParserTest` | `RulesDocument`, `Condition`, `ConditionEvaluator`, new `LenientEntries`, `tools/update_rules.py` (constants only), `SchemaConsistencyTest`, `src/test/java/.../v040/**` | `StutterFixesModelTest` (absent = null; a malformed entry drops only itself; an unreadable section = null, advice unaffected), `CauseSpikesStubTest` (UNKNOWN with and without facts; a `not` over it never TRUE), `SchemaConsistencyTest.stutterFixKeysMatchTheUpdater`, `v040.LegacyParserTest` (0.4.0's parser reads r16 with the same counts, and a `stutterFixes` section in any shape changes nothing) | C2, AC5.2 prep |
| K7 | Busy (C8) + `ProfileService.refusal()` delegating + `rigtune.tryit.refused.running` | new `client/Busy`, `ProfileService`, en_us.json | `BusyTest` (each condition alone, in order; none → null; ProfileService's refusal delegates: grep test) | AC-X.1 |
| K8 | The lazy holder, skeleton services, controller defaults and RealController delegations; GameState's policy supplier; `UndoPlanner.State.modFiles()` | new `client/V05Services`, the six skeletons, `RigTuneController`, `RealController`, `GameState`, `UndoPlanner` | `V05ServicesTest` (getters create once, constructors only store; a render-thread resolution inside the window sets the flag, a worker's doesn't), `RigTuneControllerDefaultsTest` (every v0.5 default answers 0.4's behaviour; `apply(selected, entryId)` delegates to `apply(selected)`) | C4, X4.1/X4.3 |
| K9 | Lazy notice list + the 8 skeleton sources | `NoticeCenter`, 8 new sources, `RealController` | `NoticeCenterLazyTest` (the supplier is resolved on the first `notices()`, once; not at construction) | C3, X4 |
| K10 | Extension points: `V05Hooks.afterRecommend` (FixHold, then LauncherModAdvice), the stale-group step (`StaleGroups` + `Rebuilt.stale`), `beforeApply`/`afterApply` (`ApplyFacts`), start hook, `registerEvents`, `titleScreen`, PreviewScreen's per-owner methods, seams | new `client/V05Hooks`, `StepContext`, `ApplyFacts`, stub files of each owner, `RealController`, `RigTuneClient`, `PreviewScreen`, `StutterHooks`, `AwarenessService` | `V05HooksTest` (identity/no-op stubs leave report, selection and parts unchanged; order of the post-steps), `AwarenessDismissTest` (a `server-profile:` key is session-only, never stored), the 240-scenario golden report unchanged (existing) | PLAN 13a-g, 12 |
| K11 | LangCheckTest registry + WordingTest additions | `LangCheckTest`, new `V05LangFamilies`, `WordingTest` | `WordingTest.stutterFixWordsAreRefused` (a fixture value), existing LangCheckTest | X5, PLAN 13h, 15 |
| K12 | Game tests: 7 new classes (each returns at once under `rigtune.smoke`, one empty test), fabric.mod.json in C6 order, `ForwardingController`, `CannedViews`, the context record `V05TestContext`, LauncherManagedGameTest's skeleton methods | `src/gametest/**` | `ForwardingControllerTest` (unit, reflection: every `RigTuneController` method is overridden and forwards), `GameTestRegistrationTest` (fabric.mod.json order = C6) | C6, PLAN 13i, 16 |
| K13 | Fixture README (`v050-written`), `docs/v0.5/verification/README.md` | docs/resources | review | PLAN 17-18 |
| K14 | This file "as landed": anchors, extension points, the footprint baseline from CI | this file | CI footprint JSON per leg | PLAN 19 |
| H1 | (after ws-ci merges) A11yGameTest and AwarenessGameTest skeleton methods | ws-ci's files | CI game tests | C6, PLAN 16 |
| H2 | (after ws-ci merges) FootprintGameTest: the flag unset at `initEnd` and after the CLIENT_STARTED handler | `FootprintGameTest` | CI, 3 legs | AC-X.2 |
| H3 | (after ws-ci merges) the six controller wrappers → `ForwardingController`; A11yController reads `CannedViews` | 6 game-test classes | CI game tests | PLAN 13i |

No code-deciding run. No new `//? if` block expected (X9). No fixture set (WS-K writes no new file; its shells are empty
and each owner commits its own set).

Local red runs: each task's tests were run red first (compile failure: the API didn't exist yet, or the assertion for
the changed behaviour) and green after, in a build slot (`:26.2:test --tests ...`); the full `:26.2:test` (1889 tests, 1
skipped as before) and `:26.2:gametestClasses` passed locally before the first push.

---

# The contracts as landed

Every name below is exactly as in the code. Paths: `client/…` = `src/client/java/io/github/chaotix345/rigtune/client/…`,
`core/…` = `src/main/java/io/github/chaotix345/rigtune/core/…`, `gametest/…` = `src/gametest/java/io/github/chaotix345/
rigtune/gametest/…`. "Stub" = answers 0.4's behaviour (identity, no-op, empty) until its owner fills it in; an owner
changes only the body of its own stub (and adds to its own new files), never a dispatch line. The dispatch classes
(`V05Services`, `V05Hooks`, `ForwardingController`, `CannedViews`, `V05TestContext`, `V05LangFamilies.add`, `NoticeCenter`,
`NoticePriority`, `RigTuneController`, `LangCheckTest`, `WordingTest`, the game tests' `fabric.mod.json`) are frozen: a new
entry goes through the coordinator.

## 1. NoticePriority (C3)
`core/notice/NoticePriority`: `BATTERY_OFFER, SERVER_PROFILE, HELD_MOD_CHANGES, LAUNCHER_REPAIR, FIRST_RUN, SERVER_LIMIT,
TRY_IT, BENCHMARK_REGRESSION, STARTUP_REGRESSION, HARDWARE_CHANGED, SETTINGS_CHANGED_OUTSIDE, MOD_FILES_NEWS, WHATS_NEW,
BENCHMARK_STALE`. `NoticeBoardTest.declarationOrderIsTheSpecsPriorityOrder` pins it (AC-X.3, AC7.12).

## 2. The lazy holder and the X4 flag
- `client/RealController`: `public V05Services v05()`: double-checked, made on the first call (never in the constructor),
  on whatever thread calls first (normally the start hook's worker).
- `client/V05Services` (final class): package-private constructor `V05Services(RealController)`; `String createdOn()` (the
  thread that made it); one `synchronized` create-on-first-use getter per service: `ModFilesService modFiles()` (WS-L1),
  `LauncherRepairService launcherRepair()` (WS-L2), `FirstRunService firstRun()` (WS-F), `TryItService tryIt()` (WS-T),
  `ServerProfileService serverProfiles()` (WS-P2), `StutterFixService stutterFixes()` (WS-S2). The constructor and every
  getter call `FootprintStats.lazyResolved(<what>)`.
- `client/FootprintStats` (never references V05Services): the startup window is the render thread while it runs
  `preLaunchStart`..`preLaunchEnd`, `initStart`..`initEnd` (the launch's own calls only: a later call, like a game test's
  second onPreLaunch(), opens no window) or `clientStarted(Runnable)`'s runnable.
  `public static boolean inStartupWindow()` (true only on that thread, inside); `public static void lazyResolved(String
  what)` (sets the flag to "<what> during <window>" only on that thread inside the window; the first one is kept);
  `public static @Nullable String renderThreadResolve()` (null = never). A worker resolving meanwhile, or the render
  thread afterwards, never sets it (FootprintStatsTest, V05ServicesTest: AC-X.2's unit half). FootprintGameTest's
  assertion is the held part H2 (after ws-ci merges).
- The lazy notice list's supplier also calls `lazyResolved("the v0.5 notice sources")`.

## 3. Skeleton services (each constructor only stores the controller; reached only through `v05()`)

| class | owner | methods (skeleton answer) |
|---|---|---|
| `client/launcher/ModFilesService` | WS-L1 | `ModFilesPolicy policy()` (RIGTUNE) |
| `client/launcher/LauncherRepairService` | WS-L2 | (constructor only) |
| `client/FirstRunService` | WS-F | `FirstRun.Status status()` (UNKNOWN), `boolean firstApplyPending()` (false), `void load()` (start hook), `void applied(V05Hooks.ApplyFacts)` (after-apply hook, last) |
| `client/tryit/TryItService` | WS-T | `TryItView view()` (EMPTY), `@Nullable Text refusal(Recommendation)` (`TryItView.UNAVAILABLE`), `Component start(Recommendation, BenchmarkRequest.Scene)`, `void measureNow()`, `Component keep()`, `void cancel()`, `void derive()` (start hook), `void titleToast(Minecraft)` (title-screen hook) |
| `client/server/ServerProfileService` | WS-P2 | `ServerProfilesView view()` (EMPTY), `Component remember(@Nullable String profileId)`, `Component forget(String key)`, `Component forgetAll()` ("rigtune.status.nothing"), `void onJoin(ClientPacketListener, Minecraft)`, `void onDisconnect()` (event hooks) |
| `client/stutter/StutterFixService` | WS-S2 | `ApplyPreview preview(FixOffer.Offer)` (EMPTY), `Component apply(FixOffer.Offer)` ("rigtune.status.nothing"), `void dismiss(String entryId)`, `List<FixHold.Hold> holds()` (empty; the post-step reads it) |

## 4. The lazy notice list and the 8 skeleton sources (C3, X4)
- `client/notice/NoticeCenter`: new constructor `NoticeCenter(Supplier<List<NoticeSource>> sources, List<NoticeSource>
  fallback, Dismissals)`, resolved once (synchronized) on the first `notices()` or `act()`; a supplier that throws is
  logged once and the fallback (RealController passes the six 0.4 sources) is used from then on; the old
  `NoticeCenter(List<NoticeSource>, Dismissals)` is kept. `NoticeCenterLazyTest`.
- RealController builds the v0.4 sources in its constructor as before and passes a supplier that makes the v0.5 ones and
  returns all 14 in C3 order: battery, **ServerProfile**, **HeldModChanges**, **LauncherRepair**, **FirstRun**,
  serverLimit, **TryIt**, regression, **StartupRegression**, hardwareChange, **OutsideChanges**, **ModFilesNews**,
  whatsNew, stale.
- Skeletons (`current()` null, `act` no-op, constructor `(RealController)`): `client/notice/ServerProfileNoticeSource`
  (WS-P2), `HeldModChangesNoticeSource` (WS-L2), `LauncherRepairNoticeSource` (WS-L2), `FirstRunNoticeSource` (WS-F),
  `TryItNoticeSource` (WS-T), `StartupRegressionNoticeSource` (WS-W2), `OutsideChangesNoticeSource` (WS-W),
  `ModFilesNewsNoticeSource` (WS-L1). Each returns its notice with its own `NoticePriority`.

## 5. Busy (C8)
`client/Busy`: `public static volatile BooleanSupplier tryItRunning = () -> false` (WS-T points it at its state);
`public static @Nullable Text refusal(RealController)`: in order `rigtune.profile.status.benchmark` (0.4's "benchmark
running" text, while `BenchmarkController.running()`), `rigtune.status.busy` (downloading), `rigtune.tryit.refused.running`
(a Try it chain), `rigtune.profile.code.error.not_ready` (rules or hardware not ready), else null;
`public static void overrideBenchmarkCheck(@Nullable BooleanSupplier)`. `ProfileService.refusal()` delegates (and its
`overrideBenchmarkCheck` forwards to Busy's, so ProfilesGameTest is unchanged). `BusyTest` (AC-X.1): each condition
alone, the order, none → null, and a source check that the callers (`CALLERS` in the test: ProfileService now; C20's
`StutterFixService.apply` and C09's `Triable` add themselves when they land) delegate and keep no copy.

## 6. RigTuneController defaults (C4) and RealController's one-line delegations

| default (RigTuneController) | answer | RealController |
|---|---|---|
| `boolean firstApplyPending()` | false | `v05().firstRun().firstApplyPending()` |
| `Component apply(List<Recommendation> selected, String entryId)` | `apply(selected)` | the existing method, now `@Override` |
| `boolean downloading()` | false | the existing method, now `@Override` |
| `ModFilesPolicy modFiles()` | RIGTUNE | `v05().modFiles().policy()` |
| `ApplyPreview previewStutterFix(FixOffer.Offer)` | `ApplyPreview.EMPTY` | `v05().stutterFixes().preview(offer)` |
| `Component applyStutterFix(FixOffer.Offer)` | `rigtune.status.nothing` | `…stutterFixes().apply(offer)` |
| `void dismissStutterFix(String entryId)` | no-op | `…stutterFixes().dismiss(entryId)` |
| `TryItView tryIt()` | `TryItView.EMPTY` | `v05().tryIt().view()` |
| `@Nullable Text tryItRefusal(Recommendation)` | `TryItView.UNAVAILABLE` (`rigtune.tryit.refused.unavailable`) | `…tryIt().refusal(rec)` |
| `Component startTryIt(Recommendation, BenchmarkRequest.Scene)` | `rigtune.status.nothing` | `…tryIt().start(rec, scene)` |
| `void tryItMeasureNow()` / `Component tryItKeep()` / `void tryItCancel()` | no-op / nothing / no-op | `…tryIt().measureNow()/keep()/cancel()` |
| `ServerProfilesView serverProfiles()` | `ServerProfilesView.EMPTY` | `v05().serverProfiles().view()` |
| `Component rememberServerProfile(@Nullable String profileId)` / `forgetServerProfile(String key)` / `forgetAllServerProfiles()` | `rigtune.status.nothing` | `…serverProfiles().remember/forget/forgetAll` |

StubController compiles unchanged. One convention: a default that answers a status answers `rigtune.status.nothing`.
`RigTuneControllerDefaultsTest`.

## 7. Type stubs (core)
- `core/launcher/ModFilesPolicy` enum `RIGTUNE, LAUNCHER, PENDING`; `static ModFilesPolicy of(@Nullable LauncherInfo
  launcher, InstanceEvidence evidence, boolean optIn)` → RIGTUNE (launcher null = detection hasn't answered).
- `core/launcher/InstanceEvidence(boolean packwizIndex)`, `NONE`.
- `core/launcher/LauncherModText.guideLine(ModFilesPolicy policy, @Nullable LauncherInfo launcher, boolean optedIn)` →
  null. (Deviation: a third parameter, `optedIn`, since AC4b.5's opted-in sentence can't be told apart from plain
  RIGTUNE by the policy alone; WS-F passes `settings().modFilesByRigTune`.)
- `core/history/FirstRun` with `enum Status { UNKNOWN, NEW, RETURNING }` (WS-F adds `isNew`).
- `core/stutter/FixOffer` sealed: `record Offer(String adviceId, String key, String from, String to, boolean now)`,
  `record NotYet(String adviceId, Reason reason, List<String> args)`, `enum Reason { LENGTH, EVIDENCE, BENCHMARK, SERVER,
  BUSY, STORE }` (sf §2.7's `not_yet.*`).
- `core/stutter/FixSpec` (final class; WS-S2 may make it the record sf §2.3 describes): `FEATURE = "stutter-fix"`,
  `Set<String> KEYS` (`vanilla.renderDistance`, `sodium.performance.chunk_build_defer_mode`,
  `dh.common.multiThreading.numberOfThreads`), `FIELDS` (`requires, adviceId, evidence, set`), `SET_FIELDS` (`key,
  value, step, min, max`).
- `core/stutter/FixHold`: `record Hold(String key, String from, String to, String appliedOn)`; `static Report
  apply(Report, List<Hold>)` → the report (post-step stub).
- `core/tryit/TryItView(Stage stage)`, `EMPTY` (NONE), `UNAVAILABLE` (`rigtune.tryit.refused.unavailable`, "Try it
  (measured) isn't available here."), `enum Stage { NONE, MEASURING_BEFORE,
  STOPPED_BEFORE, APPLYING, AWAITING_RESTART, RETRYING, NOT_APPLIED, CANCELLED, MEASURING_AFTER, READY, INTERRUPTED,
  RESULT, REVERT_PENDING, REVERTED, NO_BEFORE, NO_ENTRY }` (ti §2.5). WS-T adds fields.
- `core/profile/ServerProfilesView(State state, ServerLimits.@Nullable Kind kind, @Nullable String currentKey, @Nullable
  String currentProfile, @Nullable Text currentProfileName, boolean heldOnBattery, @Nullable String activeProfile,
  @Nullable Text activeProfileName, List<Row> rows, boolean writable)`, `enum State { NOT_CONNECTED, OWN_WORLD,
  UNRECOGNISED, SERVER }`, `record Row(String key, ServerLimits.Kind kind, String profile, @Nullable Text profileName,
  @Nullable String lastJoined, boolean current)`, `EMPTY` (NOT_CONNECTED, no rows, writable) (sp §2.3).
- `core/report/LauncherModAdvice`: `static Report apply(Report, ModFilesPolicy, LauncherInfo)` (launcher `UNKNOWN` until
  detected) and `static List<Recommendation> guard(List<Recommendation>, ModFilesPolicy)` → identity.
- `core/history/UndoPlanner.State`: `default ModFilesPolicy modFiles()` → RIGTUNE. `client/undo/GameState`: new
  constructor `(Options, List<ConfigTargets.Target>, Path, Map<String, SettingLabel>, Supplier<ModFilesPolicy>)`
  overriding `modFiles()`; the old 4-arg constructor kept (RIGTUNE). RealController's three `new GameState(...)` pass
  `this::modFiles`.
- `V05StubsTest`.

## 8. State-file shells (C1, X7)
`core/stutter/FixStore` (`stutter-fixes.json`, `MAX_BYTES` 32 KiB), `core/tryit/TryItStore` (`tryit.json`, 16 KiB),
`core/server/ServerProfileStore` (`server-profiles.json`, 16 KiB; salt handling is WS-P2's). Each: `FILE_NAME`,
`MAX_BYTES`, `static Path file(Path configDir)`, `static synchronized X shared(Path configDir)` (one per file per
process), `Path file()`, `JsonObject read()` (a copy; empty without a usable file), `boolean update(UnaryOperator<JsonObject>)`,
`boolean writable()`, on `StateStore` with identity defaults (the owner adds its defaults and typed accessors).
`V05StoreShellsTest` (one contract for all three: missing → empty and writable, the first write has `formatVersion` 1;
corrupt → `.bad` and empty; newer → read-only, never written; over 4 × the cap → left alone; unknown fields at any depth
kept).

## 9. Optional fields (C1; absent = null/false, a null isn't written, old constructors kept)
- `core/history/JournalEntry`: trailing `@Nullable List<String> foldedEntryIds` (nulls in it dropped); the 7-arg
  constructor kept; `withFoldedEntryIds(List<String>)`. The two places that rebuild an entry with new changes keep it:
  `HistoryUpdates.map` (status updates) and `Journal.withChanges` (changes added to an Apply); `Journal`'s fold (the
  baseline) is L8's (WS-P) to fill. `FoldedEntryIdsKeptTest`.
- `core/benchmark/BenchmarkRecord.Context`: trailing `@Nullable Boolean worldFresh, @Nullable Boolean dhGenerating,
  @Nullable List<String> stagedAtStart`; the 7- and 9-arg constructors kept; `withWorldFresh`, `withDhGenerating`,
  `withStagedAtStart`; `withModSet` keeps them; `sameConditions` unchanged (they aren't conditions; WS-B decides).
- `core/stutter/StutterReport`: trailing `@Nullable Map<String, String> settingsAtStart, settingsAtEnd`; the 22-arg
  constructor kept; `withSettings(Map, Map)`; `withAdvice` and `StutterStore`'s trimming keep them.
- `client/ClientSettings`: `public volatile boolean modFilesByRigTune = false`. Every settings.json 0.5 writes now holds
  `"modFilesByRigTune": false` (a primitive, as `stutterMonitor` in 0.4). Checked: nothing compares settings.json bytes
  (compat030/compat040 compare 0.4's own fields, `StutterWrittenFixtureTest` compares stutter.json only, the E2E's
  `KEPT` has no settings.json). Caution for Phase 5: `RIGTUNE_REGENERATE_FIXTURES=1` also rewrites
  `v040-written/ws-s/settings.json` through `StutterWrittenFixtureTest`, which would then carry the 0.5 field; don't
  regenerate the v040 sets from 0.5 code.
- `core/awareness/AwarenessStore`: `ACKNOWLEDGED_STARTUP_REGRESSIONS` ("acknowledgedStartupRegressions", absent until the
  first one), `Set<String> acknowledgedStartupRegressions()`, `boolean acknowledgeStartupRegression(String key)` (newest
  `MAX_ACKNOWLEDGED` = 64 kept); `OPTIONS_AT_EXIT` ("optionsAtExit"), `MAX_OPTIONS_AT_EXIT` = 64, `Map<String, String>
  optionsAtExit()` (strings only), `boolean setOptionsAtExit(Map<String, String>)` (first 64 keys, nulls left out),
  `@Nullable Map<String, String> takeOptionsAtExit()` (the consuming read: null when there is none or it couldn't be
  removed, so a newer file's snapshot is never compared twice). Value sanitising and the 32-character cap are WS-W's.
- Tests: `V05OptionalFieldsTest` (round trips; the pinned 0.3.0 `Journal` and the 0.2.0/0.3.0 `BenchmarkHistory` read the
  0.5 files), `StutterReportSettingsTest`, `AwarenessStoreV05Test`, `ClientSettingsTest.modFilesByRigTuneDefaultsOffAndRoundTrips`.

## 10. Rules model (C2)
- `core/rules/RulesDocument`: `@JsonAdapter(LenientEntries.class) public List<StutterFix> stutterFixes` (null when
  absent); `StutterFix { List<String> requires; String adviceId; Condition evidence; FixSet set; }`, `FixSet { String key;
  JsonElement value, step, min, max; }`; `fillDefaults` drops null entries.
- `core/rules/LenientEntries` (new): an entry Gson can't read drops only itself; a section that isn't an array is null.
  (Deviation from the plan's `LenientSection`: that one drops the whole section on one bad entry, and SPEC 5 says "a
  malformed entry drops only itself". LenientSection is unchanged, so stutterAdvice and profileTemplates keep 0.4's rule.)
- `core/rules/Condition`: `public Map<String, String> causeSpikesAtLeast` (adapter-vetted like the share maps);
  `ConditionEvaluator.hasStutterKey` includes it; `stutter()` evaluates it through the stub
  `private static Truth causeSpikes(Map<String, String> wanted, StutterFacts facts)` → UNKNOWN (WS-S2 fills that one
  method; the dispatch line is in place).
- `tools/update_rules.py`: constants only: `STUTTER_FIX_FEATURE`, `STUTTER_FIX_FIELDS`, `STUTTER_FIX_SET_FIELDS`,
  `STUTTER_FIX_KEYS`, `STUTTER_FIX_CONDITION_KEYS`. No section or key is accepted yet: `causeSpikesAtLeast` is refused as
  an unknown key everywhere until WS-R's validator (safe). `SchemaConsistencyTest`: the dump carries them;
  `conditionKeysMatch` counts `stutterFixConditionKeys`; new `stutterFixKeysMatchTheUpdater`.
- `StutterFixesModelTest` (absent = null; an entry and its fields; a malformed entry drops only itself; a non-array section
  is null and costs no advice; causeSpikesAtLeast is a stutter key and UNKNOWN, a `not` over it never TRUE; FixSpec names
  the model's fields; the main list never supports `stutter-fix`).
- Pinned 0.4.0 parser: `src/test/java/io/github/chaotix345/rigtune/v040/core/rules/{RulesDocument, LenientSection,
  ConditionAdapterFactory, Condition, BudgetedChars}.java` and `v040/core/model/Impact.java` (`git show v0.4.0:`, package
  line and the Impact import changed only; `v040/package-info.java` says so). **They import these current classes**:
  `io.github.chaotix345.rigtune.RigTune` (logger), plus Gson and jspecify. `v040/core/rules/LegacyParserTest` parses as
  0.4.0's `RulesLoader.parse` does (GsonBuilder + the pinned ConditionAdapterFactory + fillDefaults): the bundled r16 gives
  the current parser's revision, advice, settings, mods, stutterAdvice and template counts with no unknown condition key,
  and a `stutterFixes` section as an array (with `causeSpikesAtLeast` inside), a string, null, a number or an object
  changes nothing. WS-R extends it for R.

## 11. Extension points (PLAN contracts 13a-13g)
Every step runs contained, so no feature can break what 0.4 did: `V05Hooks.postStep` (a post-step that throws or answers
null leaves the report it was given, so the rebuild finishes, `droppedQueuedUpdates`/`recountStaged` included),
`V05Hooks.guarded` (the apply guard fails closed: if it throws or answers null, only the `SetSetting` items go through,
never a mod file), `V05Hooks.applyStep` (an after-apply step that throws is logged; the status and the next steps are
unaffected), `V05Services.step` (the start hook's steps, the event lambdas and the title-screen hook: any Throwable is
logged, as in RigTunePreLaunch; the afterStart future also logs through `exceptionally`). The stale-group step catches
Throwable in both halves. Tests: `V05HooksTest`, `V05ServicesTest.aStepThatThrowsIsContained`.
- **a. Report post-steps**: in `RealController.rebuild()`'s worker task, `ServerCap.apply(...)`'s result goes through
  `V05Hooks.afterRecommend(Report, V05Hooks.StepContext)`: `FixHold.apply(report, controller.v05().stutterFixes().holds())`
  (WS-S2), then `LauncherModAdvice.apply(held, controller.modFiles(), controller.launcher())` (WS-L1).
  `ModrinthOffAdvice` stays last, on the render thread. `record StepContext(RealController controller, RulesDocument
  rules, @Nullable ServerLimits live)`, built on the worker.
- **b. Stale-group step**: in the same worker task, after `dropQueuedUpdates`, `RealController.dropStaleGroups(loaded)` →
  `client/undo/StaleGroups.drop(Staging staging, Set<String> loaded)` (WS-H; `List<Op>`, may throw IOException: logged,
  treated as nothing dropped); `Rebuilt.stale`; on the render thread, when non-empty, `recountStaged()` and
  `StaleGroups.status(List<Op> dropped, List<InstalledMod> scanned)` (null = no status) becomes the status line.
- **c. Apply hooks**: `RealController.apply(selected, entryId)`, after the downloading refusal:
  `selected = V05Hooks.beforeApply(this, selected)` → `LauncherModAdvice.guard(selected, modFiles())` (WS-L1). Before
  `return join(parts)`: `V05Hooks.afterApply(this, V05Hooks.ApplyFacts, parts)` runs, in order,
  `client/undo/RefusedDisables.afterApply(ApplyFacts, List<Component> parts)` (WS-H, 2V),
  `client/awareness/OutsideChanges.afterApply(RealController, ApplyFacts, List<Component>)` (WS-W, 4h), and
  `v05().firstRun().applied(ApplyFacts)` (WS-F), last. `record ApplyFacts(String entryId, List<Recommendation> selected,
  Set<String> disablesAllowed, int settingsOk, int settingsFailed, int staged, boolean stageFailed, int downloads)`.
  The downloading refusal returns before both hooks (nothing applied).
- **d. Start hook**: `V05Services.afterStart(this)` at the end of `RealController.start()`: one `Probes.EXECUTOR` task
  running, each in its own step (resolving the holder inside the step), `controller.v05().firstRun().load()` (WS-F),
  `controller.v05().tryIt().derive()` (WS-T), `OutsideChanges.compareAtStart(RealController)` (WS-W).
- **e. Event registration**: `V05Services.registerEvents(real)`, one line in `onInitializeClient` right after
  `registerAwareness(real)` (so JOIN runs after ServerLimitsTracker's): JOIN → `v05().serverProfiles().onJoin(listener,
  minecraft)`, DISCONNECT → `onDisconnect()` (WS-P2), and a CLIENT_STOPPING listener →
  `OutsideChanges.snapshotAtStop(RealController, Minecraft)` (WS-W) in the phase `rigtune:v05-before-exit`, ordered before
  Fabric's default phase, so it runs before RigTune's own exit work (the benchmark cancel, the helper launch) and sees
  whether a benchmark was running. Each lambda resolves its service only when its event fires.
- **f. Title-screen hook**: `V05Services.titleScreen(minecraft, controller)` at the end of `RigTuneClient.showNotices`
  (once per launch in play, but RigTuneClientGameTest calls showNotices again, so `titleToast` must be safe to call
  twice) → `tryIt().titleToast(minecraft)`
  (WS-T) when the controller is RealController.
- **g. PreviewScreen**: `populate()` calls, per owner (empty until filled): `settingsSyncLine(PreviewList, ApplyPreview,
  int width)` inside "Written now" (WS-W, 4h), `downloadChecks(...)` after the downloads' notes (WS-H, L5),
  `launcherLines(...)` after "Not changed", before the notes (WS-L1, 4b). The plain footer is its own method
  `plainFooter(int column, int top)` (Done, as before; WS-T's [Try it (measured)] [Done]); the Confirm footer is unchanged.
- **Seams (item 12)**: `client/stutter/StutterHooks.benchmarkStepExcluded(boolean excluded)` (no-op; WS-S implements, WS-B
  calls); `client/awareness/AwarenessService.SESSION_ONLY_PREFIXES = List.of("server-profile:")`, `dismiss` hides such a
  key for the session and never stores it (implemented; `AwarenessDismissTest`).
- `V05HooksTest`: the stubs are identity/no-ops; the dispatch order.

## 12. LangCheckTest registry and WordingTest (13h, item 15)
- `src/test/java/io/github/chaotix345/rigtune/V05LangFamilies` (package `io.github.chaotix345.rigtune`):
  `static void add(List<LangCheckTest.Family> out)`, called at the end of `LangCheckTest.families()`, calls one private
  method per owner, each separated by a comment line: `launcherPolicy` (WS-L1), `launcherRepair` (WS-L2), `awareness`
  (WS-W), `stutterFixes` (WS-S2), `tryIt` (WS-T), `serverProfiles` (WS-P2), `launchAlerts` (WS-W2), `firstRun` (WS-F).
  An owner adds `new LangCheckTest.Family(prefix, suffixes, keys)` from its own code's values, as `families()` does.
- `WordingTest`: `rigtune.tryit.` joined the correlation prefixes (no "caused"/"because of"); `rigtune.stutter.fix.` values
  may not contain "fixed the", "proves" or "guarantee"; `theV05WordsAreRefusedWhereTheyWouldClaimTooMuch` pins both.

## 13. en_us.json: the one key and every block's anchor (C5)
WS-K added `rigtune.tryit.refused.running` ("A Try it (measured) is still in progress. Finish or cancel it first.", used
by `Busy`) and `rigtune.tryit.refused.unavailable` ("Try it (measured) isn't available here.", used by
`TryItView.UNAVAILABLE`), right after `rigtune.tools.title`, their alphabetical neighbour: they open the `rigtune.tryit.*`
block. Each new block starts
right after its anchor and is alphabetical inside; no two owners share an anchor. Edits inside an existing block go next
to the related keys (that block's owner only). Never at the end of the file.

| block | owner | anchor (the block starts right after this key) |
|---|---|---|
| `rigtune.settings.battery_offer*` | WS-P | `rigtune.screen.undo_last.tooltip` (so the block sits before `rigtune.settings.goal`) |
| `rigtune.settings.mod_files*` | WS-L1 | `rigtune.settings.goal` (`goal` stays between the two blocks) |
| `rigtune.launcher.mod_files.*`, `rigtune.launcher.mod_steps.*` | WS-L1 | `rigtune.launcher.jvm_steps.prism` |
| `rigtune.undo.reason.launcher_managed` | WS-L1 | `rigtune.undo.reason.group_changed` |
| `rigtune.undo.reason.not_disabled_by_rigtune` | WS-L1 | `rigtune.undo.reason.not_changeable` |
| `rigtune.repair.*` (incl. the held-changes notice) | WS-L2 | `rigtune.toast.busy.body` |
| `rigtune.outside.*` | WS-W | `rigtune.awareness.whats_new.one` |
| `rigtune.startup.perf_counters.*` | WS-W | `rigtune.startup.advice` |
| `rigtune.startup.notice.*`, `rigtune.startup.regression*` | WS-W2 | `rigtune.startup.mod_set_changed` (`last`, `last_median` stay between WS-W's and WS-W2's blocks) |
| `rigtune.firstrun.*` | WS-F | `rigtune.header.offline` |
| `rigtune.tryit.*` | WS-T | `rigtune.tools.title` (already holds the two `rigtune.tryit.refused.*` keys; keep the block sorted) |
| `rigtune.profile.servers*`, `rigtune.profile.server.*` | WS-P2 | `rigtune.profile.unnamed` |
| `rigtune.stutter.fix.*` | WS-S2 | `rigtune.stutter.count.spikes.one` |
| `rigtune.stutter.window.*` | WS-S | `rigtune.stutter.title` |
| `rigtune.stutter.tag.settings_changed` | WS-S | `rigtune.stutter.tag.one` |
| `rigtune.status.discarded_with_kept` | WS-H | `rigtune.status.discarded` |
| L8's "Includes" key in `rigtune.history.*` | WS-P | `rigtune.history.versions.mc` (the history block's last key; WS-H's L6 edits sit near `rigtune.history.failed`/`not_applied`) |
| `rigtune.server.*` (the DH note, 3f) | WS-E | `rigtune.server.detail.dh` |
| edits in `rigtune.benchmark.*`, `rigtune.benchmark.trend.*` | WS-B | next to the related keys |
| edits in `rigtune.awareness.*` | WS-W | next to the related keys |
| edits in `rigtune.profile.*`, `rigtune.battery.*` | WS-P | next to the related keys |
| edits in `rigtune.toast.*` | WS-L2 | next to the related keys |
| `rigtune.preview.note.downloads` | WS-H | in place |

An en_us.json merge conflict is resolved by the merging agent, keeping both sides' keys.

## 14. Game tests (C6, 13i, item 16)
- `src/gametest/resources/fabric.mod.json` in C6's order: FirstApplyGameTest, RigTuneClientGameTest, BenchmarkGameTest,
  LauncherGameTest, UndoGameTest, UiGameTest, ReportGameTest, HistoryGameTest, PreviewGameTest, ProfilesGameTest,
  StutterGameTest, **StutterFixGameTest**, JvmGameTest, BenchmarkHistoryGameTest, **TryItGameTest**, ServerLimitsGameTest,
  **LanGuestGameTest**, **ServerProfilesGameTest**, AwarenessGameTest, **BatteryFlowGameTest**, **LauncherManagedGameTest**,
  FootprintGameTest, A11yGameTest.
- The 7 new classes (`gametest/<Name>.java`, owners: FirstApply WS-F, StutterFix WS-S2, TryIt WS-T, LanGuest WS-E,
  ServerProfiles WS-P2, BatteryFlow WS-E, LauncherManaged per method) open `runTest` with
  `if (Boolean.getBoolean("rigtune.smoke")) { return; }` and do nothing else yet. The Fabric runner starts the first
  entrypoint at the title screen and checks after each one that it ended there, so an empty class is harmless in any
  position.
- `LauncherManagedGameTest.runTest` builds `V05TestContext.of(context)` and calls, once each, `private static void
  policyAndAdvice(V05TestContext)` (WS-L1) and `private static void heldAndRepair(V05TestContext)` (WS-L2).
- `gametest/V05TestContext(ClientGameTestContext context, StubController stub, RigTuneController real, Path configDir)`
  (record): `static V05TestContext of(ClientGameTestContext)`, `RealController realController()` (throws when the game's
  controller isn't RealController), `void resize(int width, int height, int guiScale)` (A11yGameTest's resize),
  `static final int[][] SIZES = {{1280, 720, 2}, {640, 480, 2}, {854, 480, 2}}`, `static final int[] SCROLLING = {854, 480, 3}`.
- `gametest/ForwardingController` (abstract): `ForwardingController(RigTuneController delegate)`,
  `RigTuneController delegate()`, and an `@Override` forwarding every RigTuneController method (v0.5's included); its
  class initialiser fails (AssertionError) if one is missing, and the unit test
  `V05GameTestContractsTest.forwardingControllerForwardsEveryMethod` checks the source. **A wrapper that overrides one
  `apply` overload must override the other** (`apply(selected, entryId)` forwards to the delegate's own, so faking only
  `apply(selected)` would still apply through the delegate): the constructor throws otherwise, and
  `V05GameTestContractsTest.everyWrapperOverridesBothApplyOverloadsOrNeither` checks the sources.
- `gametest/CannedViews`: static `tryIt()`/`tryIt(TryItView)`, `serverProfiles()`/`serverProfiles(ServerProfilesView)`,
  `modFiles()`/`modFiles(ModFilesPolicy)`, `stutter()`/`stutter(StutterView)`, `firstApplyPending()`/
  `firstApplyPending(Boolean)` (null = not canned), `clear()`. An owner sets its views inside its own skeleton method and
  clears them in a `finally`.
- `V05GameTestContractsTest` (unit): the entrypoint order, the smoke return of every new class, ForwardingController.
- **H1-H3, landed after ws-ci merged (a38950ed):**
  - `A11yGameTest.runTest`, inside its try (network off through GameTestNet), after the 0.4 walks, builds
    `new V05TestContext(context, stub, real, configDir)` and calls once each, in this order, `private static void
    <walk>(V05TestContext v05)`: `walkStutterFix` (WS-S2), `walkTryIt` (WS-T), `walkServerProfiles` (WS-P2),
    `walkFirstApply` and `walkHowItWorks` (WS-F), `walkToolsStartup` (WS-W, then WS-W2), `walkBenchmarkScreens` (WS-B),
    `walkBatteryOfferRow` (WS-P), `walkModFilesRowAndNews` (WS-L1), `walkLauncherNotices` (WS-L2),
    `highContrastRunningGame` (WS-E). They sit in a "v0.5 walks" block before the helpers, one comment line per owner;
    an owner edits only its method's body and adds its own helpers right below it; a walk leaves the screen, the size
    and CannedViews as it found them.
  - `AwarenessGameTest.runTest`, inside its try, after the 0.4 checks: `V05TestContext.of(context)`, then
    `awarenessFixes` (WS-W), `startupRegression` (WS-W2), `settingsChangedOutside` (WS-W), same rules.
  - `FootprintGameTest`: `startup()` fails the leg when `FootprintStats.renderThreadResolve()` isn't null (a v0.5 service
    resolved on the render thread inside preLaunch, onInitializeClient or the CLIENT_STARTED handler; a worker's
    resolution never sets it) and writes it as `v05RenderThreadResolve`; the JSON also gets `v05HolderCreatedOn` (the
    thread that made the holder, normally "RigTune worker").
  - The six wrappers extend `ForwardingController`: `A11yController` (delegate: the StubController, the walks' canned
    world; the real-backed overrides stay explicit; `tryIt()`, `serverProfiles()`, `modFiles()`, `stutter()`,
    `firstApplyPending()` answer from `CannedViews` when set), `PreviewGameTest.CannedController` (the stub; canned
    preview, pending flag, the real labels), `UiGameTest.PendingStub` (the stub; changes pending),
    `ProfilesGameTest.NotReady` (a class now, delegate: the real controller; only the import is refused),
    `JvmGameTest.CannedController` and `LauncherGameTest.RamAdviceController` (delegate: the real controller, so the
    launcher, the JVM report and every v0.5 answer are real; a fixed report; both apply overloads apply nothing; no
    notice, status or pending-changes line on their screen, as before).

## 15. Fixtures and verification READMEs (items 17-18)
- `src/test/resources/v050-written/README.md`: the set folders (`ws-l1, ws-l2, ws-s, ws-s2, ws-p, ws-p2, ws-b, ws-t,
  ws-w, ws-w2, ws-f`, `ws-h` only if WS-H finds a new write) with each set's files; names as in `config/rigtune/`; the
  `${INSTANCE}` token; past timestamps; unique fixed ids; the composition and per-file merge rules (from WS-E's
  `tools/e2e/written.py` on `test/v05-e2e`); the `expect.json` schema compat040 interprets; the one regeneration switch
  `RIGTUNE_REGENERATE_FIXTURES=1`; placeholders in `placeholder/<set>/` (WS-E's). Note for WS-E: `written.py`'s `V050.sets`
  on `test/v05-e2e` lists `ws-h` and lacks `ws-p` and `ws-w`; the PLAN's list (this README's) is the one to follow.
- The realworld fixtures' README is WS-L1's (PLAN amendment PLAN-11), not landed here.
- `docs/v0.5/verification/README.md`: one section per area (ci, footprint, stutter, benchmark, e2e, server, battery,
  launcher, try-it, server-profiles, first-apply, startup, smoke, real-instance) with its owner.

## 16. Footprint baseline (per leg, from CI's footprint-<mc>-<backend>.json)
"Before" = the integration branch's last five green runs (36293289958, 36293968281, 36296295977, 36297472703,
36298895950: docs-only commits on 0.4's code), median and range; "after" = this branch's head. ms, render-thread CPU
unless noted. `tickHookOnVsReference` is ws-ci's new key: its baseline comes with the held part's runs.

| leg | key | before: median (range) | after: WS-K run 36302121033 |
|---|---|---|---|
| 26.2 OpenGL | preLaunchCpuMs + initCpuMs = renderThreadInitCpuMs | 98.7 (63.5-102.8) | 112.9 (pre 59.7, init 53.1) |
| 26.2 OpenGL | initCpuMs (onInitializeClient only) | 50.1 (33.9-52.6) | 53.1 |
| 26.2 OpenGL | clientStartedWallMs | 44.3 (25.5-69.9) | 50.2 |
| 26.2 OpenGL | workerCpuMs5s | 204.5 (132.8-214.1) | 191.5 |
| 26.3 OpenGL | renderThreadInitCpuMs | 108.7 (95.6-117.1) | 110.4 |
| 26.3 OpenGL | initCpuMs | 54.4 (49.2-57.3) | 53.0 |
| 26.3 OpenGL | clientStartedWallMs | 30.4 (20.4-36.0) | 24.5 |
| 26.3 OpenGL | workerCpuMs5s | 194.5 (173.1-200.1) | 180.2 |
| 26.3 Vulkan | renderThreadInitCpuMs | 96.6 (74.9-116.6) | 102.5 |
| 26.3 Vulkan | initCpuMs | 50.2 (38.5-55.0) | 57.6 |
| 26.3 Vulkan | clientStartedWallMs | 34.5 (24.7-49.4) | 46.6 |
| 26.3 Vulkan | workerCpuMs5s | 178.0 (144.6-217.0) | 208.0 |

Reading: every "after" value is inside or within a few ms of the "before" range, whose spread (one runner to the next)
is larger than any change WS-K could make; the budgets (150 / 141 / 300) keep their margin. 26.2's 112.9 comes from
preLaunch (59.7 vs a 48-50 median), code WS-K didn't change beyond two volatile writes. More "after" runs (the held
part, the first streak on the ws-ci + WS-K SHA) replace this single sample.

## 17. Deviations, residuals, UNVERIFIED
- `LauncherModText.guideLine` takes `optedIn` (a third parameter): see 7.
- `RulesDocument.stutterFixes` uses the new `LenientEntries`, not `LenientSection`: see 10.
- A second key, `rigtune.tryit.refused.unavailable`, for `TryItView.UNAVAILABLE` (the default Try it refusal; the
  coordinator's review #8: not the misleading "Nothing to apply."). It has a user (`TryItView`), so it isn't a placeholder.
- The coordinator's review of 054cc882..ed1a3d9c (0 blockers, 3 medium, 8 low), all fixed in the next commit: the
  containment of every hook step (#1), foldedEntryIds kept by HistoryUpdates.map and Journal.withChanges (#2), this
  section (#3), the tryit anchor (#4), Throwable in `step` and the afterStart future (#5), the startup window on the
  launch's own calls only (#6), NoticeCenter's fallback (#7), one default convention and the unavailable key (#8), stale
  comments (#9), the apply-overload rule (#10), the settings.json note (#11).
- Tests folded into others than the plan table names: `NoticeBoardTest.theFourteenSlotsInC3Order` is the existing
  `declarationOrderIsTheSpecsPriorityOrder`, extended; `CauseSpikesStubTest` is in `StutterFixesModelTest`; the stutter
  part of `V05OptionalFieldsTest` is `StutterReportSettingsTest` (it needs StutterStoreTest's package-private fixture);
  `ForwardingControllerTest` and `GameTestRegistrationTest` are `V05GameTestContractsTest` (source checks: the unit tests
  can't load src/gametest's classes); the render-thread flag is tested in `FootprintStatsTest` and `V05ServicesTest`.
- `StutterStore`'s trimming passes the two new stutter fields through (one line, so a trimmed session keeps them).
- The CLIENT_STOPPING listener runs in its own phase before Fabric's default (see 11e): an ordering decision the plan
  left open.
- UNVERIFIED: that a `-D` on the gradlew command line sets the daemon idle timeout (PLAN "Local runs"); not checked by
  WS-K.

## 18. Docs (for the docs workstream)
Nothing player-visible changes in WS-K. DESIGN.md "Shared contracts (v0.5)": the lazy holder (`V05Services`, made on the
first `RealController.v05()` call; FootprintStats' render-thread flag), the lazy notice list, the extension points of
section 11, and the shared busy refusal (`Busy`, C8).

## 19. AC table

| AC | status | evidence |
|---|---|---|
| AC-X.1 (BusyTest; callers delegate; no second copy) | verified for ProfileService (the other two callers land with C20/C09 and add themselves to BusyTest's list) | BusyTest (unit, CI run 36302121033 java job) |
| AC-X.2 (the flag unset at initEnd/after CLIENT_STARTED on 3 legs; unit for worker vs render thread) | see §16's run | FootprintStatsTest, V05ServicesTest; FootprintGameTest on 3 legs |
| AC-X.3 (NoticeBoardTest pins 14 slots; LangCheckTest, WordingTest, PseudoLocaleTest, PaletteTest pass on both nodes) | verified on this branch (release-candidate check is the RC's) | CI 36302121033: java job (both nodes) green |
| AC7.12 (SERVER_PROFILE pinned after BATTERY_OFFER, before SERVER_LIMIT) | verified | NoticeBoardTest |
| Behaviour unchanged (every unit test and game test green on both nodes and all three legs) | verified | CI 36302121033: all 8 jobs green |
