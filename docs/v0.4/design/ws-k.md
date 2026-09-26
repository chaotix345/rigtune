# WS-K: shared contracts as landed (SPEC "Shared contracts" C1-C7 + plan-review K-M1, K-L1, X-M1, X-M2, X-L1)

Branch `feat/v04-contracts`. Every name below is exactly as in the code; workstreams fill their own files and add only
what this list leaves to them. Deviations from SPEC C1-C7 are at the end.

## C1. State files and optional fields

**Optional additive fields** (old constructors kept as overloads; absent in a file = null/default; a null is not written;
0.3.0 and older ignore them and drop them on rewrite):
- `core/history/JournalChange`: trailing component `String modName`; old 13-arg constructor kept; `withModName(String)`;
  every wither (`withStatus`, `reverting`, `withGroup`, `withResultFile`, `withOpId`) keeps it.
- `core/apply/PendingActions.Op`: trailing components `String projectId, String versionId` (versionId per amendment A-M1);
  the old 9-arg and 5-arg constructors kept; `withProjectId(String)`, `withVersionId(String)`; `inGroup`, `withModId`,
  `withAttempts` keep both. `sameChange` ignores them. Note: `ApplyResult.OpResult` embeds the Op, so last-apply.json's
  op copies carry them too when set (additive; old readers ignore them).
- `core/benchmark/BenchmarkRecord.Context`: trailing `@Nullable String modSetHash, @Nullable String journalCursor`; old
  7-arg constructor kept; `withModSet(String modSetHash, String journalCursor)`; `sameConditions(Context other)` compares
  the 7 condition fields only (B-H1: use it, not `equals()`, which now includes the hash and cursor).
- `client/ClientSettings`: `public volatile boolean stutterMonitor = false`.
- `core/model/Report`: trailing `@Nullable TierBasis tierBasis`; old 9-arg constructor kept (null). RealController's
  `withoutStaged` and `ModrinthOffAdvice.apply` copy it through.
- `core/model/TierBasis(Gpu gpu, Cpu cpu, Memory memory)`, `enum Basis { TABLE_MATCH, FALLBACK_ESTIMATE }`,
  `Gpu(int tier, Basis basis, @Nullable String matchedPattern, @Nullable GpuVendor vendor, boolean integrated)`,
  `Cpu(int tier, Basis basis, @Nullable String matchedPattern, int logicalCores, long maxFreqMhz)`,
  `Memory(int tier, Basis basis, long heapMb)`. Plain data; WS-A fills it (Recommender) and renders it.
- Tests: JournalChangeModNameTest, PendingOpModrinthIdsTest, BenchmarkContextFieldsTest, TierBasisTest,
  ClientSettingsTest.stutterMonitorDefaultsOffAndRoundTrips.

**`core/store/JsonStateFile`** (the common rules for the five new files):
- `new JsonStateFile(Path file, long maxBytes)` / `(Path, long, Gson)`; `JsonStateFile.GSON` (pretty, no HTML escaping);
  `FORMAT_VERSION = 1`, `FORMAT_VERSION_KEY = "formatVersion"`.
- `<T> Loaded<T> load(Class<T> type)`; `record Loaded<T>(State state, @Nullable T value, JsonObject root)` with
  `writable()`; `enum State { MISSING, OK, MOVED_ASIDE, NEWER, UNREADABLE }`.
  Missing formatVersion = 1; newer = read-only (value parsed if it fits, never written); corrupt (not UTF-8, not a JSON
  object, formatVersion not a whole number >= 1, a shape T can't take) = moved to `<name>.bad` (`.bad.1`, ... never
  replaced) and empty; an IOException, a corrupt file that can't be moved, or a file over 4 x maxBytes (maybe a newer
  RigTune's) = UNREADABLE (left alone, not written). Nothing throws.
- `Saved save(Object value)`, `Saved save(Object value, @Nullable JsonObject previousRoot)`;
  `enum Saved { OK, TOO_LARGE, READ_ONLY, FAILED }`. Writes `formatVersion` first, atomically (`AtomicFiles`), refuses
  over maxBytes, re-checks the file on disk first (never overwrites NEWER/UNREADABLE). With `previousRoot`, unknown
  top-level fields survive (fields the value's type declares are not resurrected).
- `static JsonObject preserveUnknown(JsonObject fresh, @Nullable JsonObject previous, Collection<String> knownKeys)`,
  usable per element for nested objects.
- Tests: JsonStateFileTest (15).

**`core/store/StateStore`** (plan review X-M1): `new StateStore(Path file, long maxBytes, UnaryOperator<JsonObject> defaults)`;
`synchronized JsonObject read()` (a copy, defaults filled; a NEWER file as it is); `synchronized boolean update(UnaryOperator<JsonObject>)`
(re-reads under the lock, applies, writes; false if read-only, too large, failed, or the change threw); `writable()`.
The root stays a JsonObject, so unknown fields at any depth survive. The content is untrusted (players edit it):
accessors type-check values (`instanceof JsonPrimitive/JsonArray/JsonObject`), never `getAs*` blindly.

**`core/awareness/AwarenessStore`** (awareness.json): `static AwarenessStore shared(Path configDir)` (one per file per
process), `static Path file(Path configDir)`, `read()`, `update(UnaryOperator<JsonObject>)`, `writable()`,
`Set<String> dismissed()`, `boolean dismiss(String key)` (newest `MAX_DISMISSED = 256` kept). `MAX_BYTES = 64 KiB`.
Keys: `FINGERPRINT` ("fingerprint") with `FINGERPRINT_GPU_VENDOR`, `FINGERPRINT_GPU_RENDERER`, `FINGERPRINT_GPU_DRIVER_RAW`,
`FINGERPRINT_BACKEND`, `FINGERPRINT_CPU_NAME`, `FINGERPRINT_TOTAL_RAM_MB`; `LAST_SEEN_RULES_REVISION`,
`LAST_SEEN_RECOMMENDATION_IDS`, `DISMISSED`, `ACKNOWLEDGED_REGRESSIONS`. Always present (defaults): `dismissed`,
`acknowledgedRegressions` (empty arrays). Absent on purpose until seeded: `fingerprint` (= first run),
`lastSeenRulesRevision`/`lastSeenRecommendationIds` (= no baseline). WS-W and WS-B add typed accessors here.

**`core/profile/ProfileStore`** (profiles.json): same API shape (`shared`, `file`, `read`, `update`, `writable`);
`MAX_BYTES = 1 MiB`, `MAX_PROFILES = 50`, `MAX_SWITCHES = 50`; keys `PROFILES`, `ACTIVE`, `SWITCHES`, `BATTERY`,
`BATTERY_PROMPT`, `BATTERY_PREVIOUS_PROFILE`, `BATTERY_LAST_PROMPT_AT`, `BATTERY_SNOOZED`. Always present: `profiles`
[], `switches` [], `battery` {prompt: true, snoozed: false}. `active` absent = none. WS-P adds typed accessors and
enforces the caps.
- Tests: StateStoreTest (two threads x 1,000 updates on different fields, both stores; defaults; unknown fields; newer
  never written; bounded dismissals).
- stutter.json, server-limits.json, startup-times.json: their workstreams wrap a `JsonStateFile` directly (single writer
  each), or a `StateStore` if they grow a second writer.

## C2. Rules (Java side only; WS-R owns the updater validation, content and regeneration)
- `RulesDocument.profileTemplates` (`ProfileTemplates { List<ProfileTemplate> templates }`,
  `ProfileTemplate { List<String> requires; String id; String goal; Map<String, Boolean> facts; List<SettingRule> settings; }`)
  and `RulesDocument.stutterAdvice` (`List<AdviceRule>`): null when absent; `fillDefaults` drops null/idless entries.
  Both carry `@JsonAdapter(LenientSection.class)` (`core/rules/LenientSection`): a section this version can't read
  (wrong shape or value types) is null, which disables only that section, never the whole document. Note Gson reads
  `facts: {"onBattery": "yes"}` as false; WS-R's updater validates the section.
- `Condition` keys (plan review K-M1: all adapter-vetted types, so a bad value poisons only its condition):
  `Map<String,String> driverVersion`; stutter keys `Map<String,String> stutterShareAtLeast`,
  `Map<String,String> stutterTaggedShareAtLeast` (cause/tag -> whole percent as a string-or-number, parsed by the
  evaluator; UNKNOWN on a bad number), `Integer gcFullPausesAtLeast`, `Integer gcStallsAtLeast`,
  `Integer gcExplicitPausesAtLeast`, `Integer liveSetPercentAtLeast` (whole percent), `Long heapRaiseRoomMbAtLeast`,
  `Integer cpuContentionShareAtLeast` (whole percent), `Integer spikesPerMinuteAtLeast` (**spikes per minute x 10**:
  30 = 3 a minute), `List<String> gcCollector` (g1, zgc, shenandoah, parallel, serial).
- `ConditionAdapterFactory` now also vets every `List<String>` key (`STRING_LISTS`): not an array of
  strings/numbers/booleans/nulls = unknown key (poisoned), instead of Gson rejecting the whole document. Map, Integer,
  Long and Boolean keys were already vetted. Still not vetted (pre-existing, unchanged): the String keys
  `gpuModelMatches`/`mcVersionRange` given an object or array, and `anyOf`/`not` of the wrong shape; those reject the
  document (fail safe: the client falls back to the cache or the bundled rules). Don't add a new String key.
- `ConditionEvaluator`: `driverVersion(Map<String,String>, EvalContext)` and `stutter(Condition, @Nullable StutterFacts)`
  are stubs returning UNKNOWN (`// filled by WS-R`); `public static boolean hasStutterKey(Condition)`. They sit after
  `settingIs` in `node()`.
- `EvalContext`: trailing `@Nullable StutterFacts stutter`; the 5/6/7-arg constructors kept (stutter null);
  `withStutter(StutterFacts)`.
- `core/stutter/StutterFacts(Map<String,Double> claimedShares, Map<String,Double> taggedShares, int gcFullPauses,
  int gcStalls, int gcExplicitPauses, @Nullable Double liveSetPercent, @Nullable Long heapRaiseRoomMb,
  @Nullable Double cpuContentionShare, double spikesPerMinute, @Nullable String gcCollector)` (measured values; shares
  in percent; the rules' thresholds are the whole numbers above).
- `Recommender.SUPPORTED_FEATURES` unchanged (empty).
- tools/update_rules.py: only the key sets: `"driverVersion"` in `V2_CONDITION_KEYS`; new `STUTTER_CONDITION_KEYS`;
  the stutter Integer keys in `INT32_CONDITION_KEYS`; `gcCollector` in `LIST_CONDITION_KEYS`. No map validator for
  driverVersion yet (WS-R). SchemaConsistencyTest dumps `stutterConditionKeys` and checks
  `KNOWN_KEYS == V2 ∪ STUTTER`, disjoint, driverVersion in V2 not V1. Generated rules unchanged: every one of
  knowledge.json's 120 conditions validates identically with the old and new updater (V2 and V1 sets,
  `is_v1_condition`), tools/tests 285 OK, nothing regenerated or committed.
- Tests: RulesContractsTest (7), ConditionAdapterFactoryTest (wrong-typed value per new key: that condition is
  poisoned, also inside anyOf and stutterAdvice; the document loads).

## C3. Hub and notice slot
- Core: `core/notice/Notice(String key, NoticePriority priority, Text message, @Nullable Text detail, List<NoticeAction> actions, boolean dismissible)`,
  `NoticeAction(String id, Text label)`, `enum NoticePriority { BATTERY_OFFER, SERVER_LIMIT, BENCHMARK_REGRESSION, HARDWARE_CHANGED, WHATS_NEW, BENCHMARK_STALE }`,
  `NoticeBoard.select(List<Notice>, Set<String> dismissed)` -> `Selection(List<Notice> visible)` with `top()`,
  `others()`, `at(int)` (cycles). Order: priority, then the sources' order; dismissed ones left out unless not
  dismissible; each key once. NoticeBoardTest (8).
- Client: `client/notice/NoticeSource { @Nullable Notice current(); void act(String actionId); }` (asked on screen
  init/rebuild only, render thread); `NoticeCenter(List<NoticeSource>, NoticeCenter.Dismissals)`,
  `interface Dismissals { Set<String> dismissed(); void dismiss(String key); }`, `NoticeCenter.inMemory()`,
  `List<Notice> notices()` (visible, best first; reads dismissals only when some source has a notice),
  `act(String key, String actionId)`, `dismiss(String key)`. A throwing source is logged and skipped.
- Sources (each `(RealController controller)`, `current()` returns null): `BatteryNoticeSource` (WS-P),
  `ServerLimitNoticeSource` (WS-W), `RegressionNoticeSource` + `BenchmarkStaleNoticeSource` (WS-B),
  `HardwareChangeNoticeSource` + `WhatsNewNoticeSource` (WS-W). Registered once in RealController in NoticePriority
  order; each reaches its service through the controller's accessor (nobody edits the registration).
- Dismissals: `AwarenessService implements NoticeCenter.Dismissals` via `AwarenessStore.shared(configDir)`, plus an
  in-memory set for the session, so a dismissal still hides the notice while awareness.json is newer or unreadable.
- `client/ui/ToolsScreen(Screen parent, RigTuneController controller)`: entries in order Benchmark…
  (`rigtune.screen.benchmark_menu`, opens the existing BenchmarkMenuScreen), Profiles…, Stutter Doctor…, JVM & memory…,
  Benchmark history…, then the startup line (`private @Nullable Component startupLine(StartupTimes.View)`, returns null:
  WS-F fills it); public `openBenchmark()`, `openProfiles()`, `openStutter()`, `openJvm()`, `openBenchmarkHistory()`,
  `startupLine()`.
- Skeleton screens (title + Done, `(Screen parent, RigTuneController controller)`, `protected final RigTuneController controller`):
  `ProfilesScreen` (`rigtune.profile.title`), `StutterScreen` (`rigtune.stutter.title`), `JvmScreen`
  (`rigtune.jvm.title`), `BenchmarkHistoryScreen` (`rigtune.benchmark.trend.title`).
- `client/ui/NoticeScreen(Screen parent, RigTuneController controller)`: every visible notice (message, detail as
  tooltip, up to 2 actions, dismiss), "+N more" if they don't fit, Done; `shown()` for tests.
- RigTuneScreen (all in helper methods in one block after `reportProblem()`): `toolsButton()` right after History…
  and **in place of Benchmark…** (footer stays 8 buttons, 9 with Discard); `noticeLine(int y)` called once from
  `init()` right after the header height is known (adds `NOTICE_ROW = 16` px only when a notice shows);
  `placeNoticeButtons`, `noticeButton`, `extractNotice` (one call in `extractRenderState` after the header lines);
  public `shownNotice()` and `otherNotices()` for tests. At `width >= NOTICE_INLINE_WIDTH` (400 scaled px): message +
  up to 2 action buttons + dismiss (`rigtune.notice.dismiss`) + "+N more" (`rigtune.notice.more`, cycles; the index
  stays in range, so after a dismissal the next notice moves into the slot). Narrower, or when the inline buttons would
  leave the message under `MIN_NOTICE_MESSAGE` (80 px): message + one "…" button (`rigtune.notice.open`) opening
  NoticeScreen (which wraps a button row that doesn't fit). Other workstreams' RigTuneScreen edits (2j
  tier badge/tooltip, 2m checkbox label, 7's tooltip helper call, 11 row focus/palette) are in existing methods, away
  from this block.

## C4. Controller contract
- RigTuneController defaults (no-op/empty): `List<Notice> notices()`, `noticeAction(String key, String actionId)`,
  `dismissNotice(String key)`; `List<ProfileView> profiles()`, `Component switchProfile(String id)`,
  `ApplyPreview previewProfile(String id)`, `Component saveCurrentProfile(String name)`,
  `ProfileImport importProfileCode(String code)`, `@Nullable String exportProfileCode(String id)`,
  `renameProfile(String id, String name)`, `deleteProfile(String id)`; `StutterView stutter()`,
  `setStutterMonitor(boolean on)`, `pauseStutterMonitor(boolean paused)`, `clearStutter()`, `String stutterSummary()`;
  `JvmReport jvmReport()`; `BenchmarkTrend.View benchmarkTrend(@Nullable String contextKey)`;
  `@Nullable ServerLimits serverLimits()`; `StartupTimes.View startupTimes()`.
- View types (skeleton records; the owner may reshape them, but StubController must keep compiling):
  `core/profile/ProfileView(String id, Text name, String source, boolean active)`;
  `core/profile/ProfileImport(@Nullable String name, ApplyPreview preview, int unknownKeys, @Nullable Text error)` +
  `failed(Text)`; `core/stutter/StutterView(boolean monitorOn, boolean paused, boolean enoughData)` + `EMPTY`;
  `core/jvm/JvmReport(boolean available, @Nullable String javaVersion, @Nullable String vendor, @Nullable String collector)`
  + `UNAVAILABLE`; `core/benchmark/BenchmarkTrend` (final class) with `View(@Nullable String contextKey, List<String> contextKeys, int comparableRuns, int otherRuns)`
  + `View.EMPTY`; `core/model/ServerLimits(int viewDistance, int simulationDistance, Kind kind, long lastSeenEpochMillis)`
  with `enum Kind { SINGLEPLAYER, LAN_GUEST, REALM, REMOTE }`; `client/footprint/StartupTimes.View(@Nullable Long lastMs, @Nullable Long medianMs, int runs, boolean modSetChanged)`
  + `View.EMPTY`.
- Services and notice sources get the controller before its constructor has finished: **never call a controller
  accessor (or do any work) in a service or source constructor**; fetch what you need lazily.
- Services, each `(RealController controller, Path configDir)`, no work in the constructor:
  `client/profile/ProfileService` (profiles, switchProfile, previewProfile, saveCurrentProfile, importProfileCode,
  exportProfileCode, renameProfile, deleteProfile), `client/stutter/StutterService` (view, setMonitor, pause, clear,
  summary), `client/jvm/JvmService` (report), `client/benchmark/TrendService` (trend), `client/server/ServerLimitsTracker`
  (live), `client/awareness/AwarenessService` (dismissed, dismiss), `client/footprint/StartupTimes` (view).
- RealController: the seven services + `NoticeCenter` as final fields built at the end of the constructor; one-line
  delegations at the end of the class; read accessors for services and notice sources: `minecraft()`, `configDir()`,
  `modsDir()`, `modVersion()`, `settings()`, `rules()`, `hardwareProfile()`, `mods()`, `downloading()`,
  `profileService()`, `stutterService()`, `jvmService()`, `trendService()`, `serverLimitsTracker()`,
  `awarenessService()`, `startupTimesService()`.
- `RealController.apply(List<Recommendation> selected, String entryId)`: the whole Apply journaled under `entryId`;
  `apply(selected)` = `apply(selected, ChangeRecorder.newEntryId())`. The threading is done (it was trivial); WS-P
  only calls it.
- StubController (gametest): `setNotices(List<Notice>)`, `notices()` (NoticeBoard over the canned list minus
  dismissed), `noticeAction` (recorded; `noticeActions()`), `dismissNotice`.

## C5. en_us.json blocks (each separated from the next block by existing lines)
- `rigtune.notice.*` after `rigtune.header.offline`: `dismiss` ("×"), `dismiss.tooltip`, `more` ("+%s more"),
  `more.tooltip`, `open` ("…"), `open.tooltip`, `title`.
- `rigtune.tools.*` after `rigtune.history.versions.mc`: `benchmark_history`, `jvm`, `open`, `open.tooltip`,
  `profiles`, `stutter`, `title`.
- `rigtune.jvm.title` after `rigtune.launcher.steps.prism` (WS-J's own block; its `launcher.jvm_steps.*` go inside the
  launcher block).
- `rigtune.profile.title` after `rigtune.preview.value.none`; `rigtune.stutter.title` after
  `rigtune.status.queued_update_dropped`; `rigtune.benchmark.trend.title` after `rigtune.benchmark.step.simulation_distance`.
- Not created (LangCheckTest fails on unused keys): start them at these anchors so parallel blocks never touch:
  `rigtune.battery.*` right after the `rigtune.profile.*` block (WS-P); `rigtune.server.*` then `rigtune.awareness.*`
  after `rigtune.share.unavailable` (WS-W); `rigtune.startup.*` after `rigtune.impact.low` (WS-F); `rigtune.a11y.*`
  after `rigtune.download.target_exists` (WS-X).
- LangCheckTest: no new dynamic family (the notice line never builds keys from NoticePriority; no template-id keys
  exist yet). The template-id family is WS-P's when it adds `rigtune.profile.template.*`.

## C6. Game tests
- Registered in src/gametest/resources/fabric.mod.json after PreviewGameTest: `ProfilesGameTest`, `StutterGameTest`,
  `JvmGameTest`, `BenchmarkHistoryGameTest` (each: RigTune -> ToolsScreen -> its screen via `open*()` -> Done),
  `ServerLimitsGameTest`, `AwarenessGameTest`, `FootprintGameTest`, `A11yGameTest` (title screen reachable). All
  return early under `-Drigtune.smoke=true` and log "<Class>: registered; the contracts skeleton case passed".
- UiGameTest: `checkTools` (Tools… right after History…, no footer Benchmark…; main screen at 640x480@2; ToolsScreen
  at 1280x720@2, 640x480@2, 854x480@2; Benchmark and the four entries open and close), the benchmark menu reached
  through Tools, `ui-stub-pending-640x480`-style checks with Discard + a notice at the 3 sizes asserting the list is
  >= 2 x 24 px high, and `checkNoticeLine`/`checkNoticeScreen` (inline at >= 400 px: 2 actions, dismiss, "+N more"
  cycling, dismiss hides; narrow: "…" -> NoticeScreen lists all three, an action there reaches the controller).
  Screenshots: `ui-tools-footer-640x480-scale2`, `ui-tools-<w>x<h>-scale<s>`, `ui-tools-{profiles,stutter,jvm,benchmark-history}`,
  `ui-stub-pending-notice-<w>x<h>-scale<s>`, `ui-notice-<w>x<h>-scale<s>`, `ui-notice-cycled`, `ui-notice-screen-640x480-scale2`.

## Pinned copies (plan review K-L1; test-only)
- `src/test/java/io/github/chaotix345/rigtune/v030/core/` = `git show v0.3.0:` with the package line changed and
  imports of other pinned classes pointed at v030 (like v010/v020); notes in `v030/package-info.java`. core/rules is
  byte-identical in v0.2.0 and v0.3.0, so it's pinned once here and stands for 0.2.0 too (use it for
  LegacyConditionFailClosedTest/LegacyRulesParseTest; there is no v020/core/rules).
  - rules: Condition, ConditionAdapterFactory, ConditionEvaluator, EvalContext, Truth, RulesDocument, RulesLoader,
    BudgetedChars; hardware: GpuClassifier; recommend: SettingValues (both used by the evaluator);
    recommend/Recommender = **stub** with `SUPPORTED_FEATURES` (empty) and public `supported(List<String>)` (AC6.3).
  - history: Journal, JournalEntry, JournalChange, HistoryModel, UndoPlanner, UndoPlan, ApplyFailures, ChangeRecorder,
    HistoryUpdates, JarInfo.
  - apply: PendingActions and the helper closure it needs: ApplyExecutor, ApplyHelper, ApplyLock, ApplyResult,
    AtomicFiles, InstanceDirs, ModJars, SafeFileNames, SodiumConfigPatcher, TomlConfigPatcher, PropertiesConfigPatcher,
    TomlDocument (not MC-dependent, so pinned rather than skipped; a test can run 0.3.0's helper on a 0.4 plan).
  - benchmark: BenchmarkHistory, BenchmarkRecord, BenchmarkRecords, BenchmarkMath, BenchmarkRequest, FrameStats,
    Knobs, PlannerResult, Protocol, SessionResult, Step.
  - They compile against these CURRENT classes: `io.github.chaotix345.rigtune.RigTune` (logger), and core.model
    `BenchmarkSummary`, `DisplayInfo`, `Goal`, `GpuClass`, `GpuInfo`, `GpuVendor`, `GraphicsBackend`, `HardwareProfile`,
    `Impact`, `SettingKeys`, `Text`, `TierResult`, `SettingsSnapshot` (+ Fabric Loader, Gson). Risk: a v0.4 change to
    one of them flows into the "0.3.0" behaviour. Records only break compilation if an accessor is removed; the ones
    with behaviour are `SettingKeys` (pinned SettingValues calls it) and `Text` (UndoPlanner/HistoryModel wording).
    Whoever changes those in v0.4 must pin the v0.3.0 version into v030/core/model first.
- `src/test/java/io/github/chaotix345/rigtune/v010/core/apply/` = `git show v0.1.0:` PendingActions and its closure
  (ApplyExecutor, ApplyHelper, ApplyLock, ApplyResult, AtomicFiles, InstanceDirs, ModJars, SafeFileNames,
  SodiumConfigPatcher), imports pointed at v010 (incl. v010.RigTune).
- Smoke tests, `core/PinnedCopiesTest`: 0.3.0's RulesLoader loads the bundled rules-v2.json with the same counts and
  fails closed on driverVersion while ignoring profileTemplates/stutterAdvice; the Recommender stub skips `jvm-flags`
  and `stutter-doctor`; 0.3.0's Journal reads a 0.4 history.json with modName (state OK, ids kept); 0.1.0's and
  0.3.0's PendingActions parse a 0.4 pending.json with projectId/versionId; 0.3.0's BenchmarkHistory loads a 0.4
  benchmarks.json with modSetHash/journalCursor (no .bad).

## README (plan review X-L1)
Empty sections, each with an HTML comment naming its owner: "Profiles and share codes", "Stutter Doctor", "JVM & memory
advice", "RigTune's own footprint" (between "Use" and "Privacy"), "What has been verified" (before "FAQ"). "Use" now
says Tools… → Benchmark….

## Deviations from SPEC C1-C7 (and why)
1. **`jvm-` flag prefix not in ConditionEvaluator** (C7 lists it under contracts). When an absent `jvm-` fact is FALSE
   and when UNKNOWN (JVM check off on OpenJ9, probe not finished) is item 6's semantics, not a stub; it's a two-line
   change in `knownFlag`/`flags()` away from the C2 stubs, and no rule uses it before WS-R's content lands.
2. **Stutter key types** (K-M1): shares/percentages whole-percent Integers, `spikesPerMinuteAtLeast` is x10, the share
   maps `Map<String,String>`; research §5.2's `{"gc": 30}` still parses (numbers are read as strings).
3. **`PendingActions.Op.versionId`** added with projectId (amendment A-M1), so WS-A doesn't reshape the record again.
4. **ConditionAdapterFactory vets List<String> keys** (all of them, not only gcCollector): the only way to make
   gcCollector K-M1-safe; for the existing keys it only changes malformed documents (poisoned condition instead of a
   rejected document). The pinned v030 copy keeps 0.2.0/0.3.0's behaviour.
5. **C5**: no placeholder keys for the battery/server/awareness/startup/a11y blocks (LangCheckTest fails on unused
   keys; anchors given above) and no NoticePriority/template-id dynamic family (nothing builds such keys yet).
6. **C3 layout** per X-M2 (Tools… replaces Benchmark…, Benchmark is the hub's first entry, NoticeScreen below 400 px).
7. **last-apply.json** gains projectId/versionId inside op copies when set (the Op record is embedded); SPEC's
   "unchanged" list is otherwise right.
8. `RigTuneController.notices()` returns the visible list (dismissed removed, sorted); screens still pass it through
   `NoticeBoard.select(list, Set.of())` for `top()`/`at()`/`others()`.
9. The new rules sections are lenient (`LenientSection`) rather than plain typed fields: same types, but a malformed
   section is dropped alone (self-review finding; K-M1's reasoning).

## Self-review
A code-reviewer subagent reviewed the diff (1 high, 2 medium, 6 low). Fixed: the notice index after a dismissal
(also caught by CI), StateStore.update catching a throwing change, inline notice buttons falling back to "…" when
they would squeeze the message and NoticeScreen wrapping buttons, oversize files left alone (UNREADABLE),
session-level dismissals, `Context.sameConditions`, lenient new rules sections, the constructor rule above, the Tools
tooltip.
