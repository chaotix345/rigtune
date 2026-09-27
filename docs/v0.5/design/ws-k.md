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
