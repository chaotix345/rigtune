# WS-K: shared contracts (SPEC C1-C7) — task plan

Branch `feat/v04-contracts`. One contracts change, merged before the P1 fan-out. No feature logic; no user-visible change
except the "Tools…" footer button (hub of skeleton screens) and an empty-until-filled notice line.

- [ ] K1 C1 optional fields (TDD): `JournalChange.modName`, `PendingActions.Op.projectId` + `withProjectId`,
      `BenchmarkRecord.Context.modSetHash`/`journalCursor`, `ClientSettings.stutterMonitor`, `Report.tierBasis` +
      `core/model/TierBasis`. Old constructors kept. Tests: `C1FieldsTest` (Gson round trip, absent → null/default, old ctors).
- [ ] K2 `core/store/JsonStateFile` (TDD, `JsonStateFileTest`): formatVersion (missing = 1, newer = read-only), atomic
      writes, byte cap, corrupt → `.bad` + empty, read errors never throw, unknown top-level fields kept.
- [ ] K3 C2 Java side: `RulesDocument.profileTemplates`/`stutterAdvice`, `Condition.driverVersion` + stutter keys,
      `EvalContext.stutter` (`core/stutter/StutterFacts`), evaluator stubs → UNKNOWN; update_rules.py key sets only
      (generated rules byte-identical). Tests: `RulesContractsTest`.
- [ ] K4 C3 core notice API (TDD, `NoticeBoardTest`); client `NoticeCenter`, `NoticeSource`, six skeleton sources;
      `ToolsScreen` + four skeleton screens; RigTuneScreen Tools… button + notice line.
- [ ] K5 C4 controller defaults, view records, skeleton services, RealController one-line delegations,
      `apply(selected, entryId)`; StubController.
- [ ] K6 C5 en_us.json blocks (tools, notice, profile/stutter/jvm/benchmark.trend titles); LangCheckTest green.
- [ ] K7 C6 eight game-test classes registered; UiGameTest Tools screenshots at 3 sizes (+ footer at 640x480@2).
- [ ] K8 Pinned v0.3.0 copies (`src/test/java/.../v030/`) + one smoke test per group; v0.1.0 PendingActions pin.
- [ ] K9 `docs/v0.4/design/ws-k.md` (every contract as landed, deviations). Self-review, build both versions, push, CI.
