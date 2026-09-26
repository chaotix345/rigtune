# WS-W plan: server-aware advice + change awareness (SPEC 8, 9 as amended by W-H1, W-L1, W-L2, W-L3, X-M1, X-M3)

Branch `feat/awareness`, worktree `rigtune-aware`. TDD per task, commit after each, push often (CI runs unit + game tests).

## Decisions (coordinator-approved where marked)
- The server cap is a pure post-processing step `core/recommend/ServerCap.apply(report, live, rules)` called where
  RealController builds the report (X-M3). It lives in core/recommend for package access to SettingValues' describe/number
  (no visibility change there). Only a proposed RD increase is lowered: T > C -> min(T, max(C, L)); equal to C -> the
  recommendation is dropped; T <= C unchanged (W-H1). Only live, non-SINGLEPLAYER limits; stored entries never.
- `RealController.rebuild()` becomes public (approved); ServerLimitsTracker rebuilds on JOIN, DISCONNECT and a live change.
- The notice tooltips wrap (approved): `font.split` in RigTuneScreen.extractNotice and NoticeScreen (those lines only).
- W-L3 "shown": AwarenessService registers a per-screen `ScreenEvents.afterTick` on RigTuneScreen (approved) comparing
  `shownNotice().key()` with the pending hardware key; NoticeScreen counts on init (`shown()`). The fingerprint is
  committed when shown or dismissed; the notice stays for the session.
- WHAT'S NEW baseline = the ids the rules revision *could* produce (`WhatsNew.potentialIds(rules)`: add/disable/conflict per
  mod rule, disable per obsolete rule, set per setting key, advice per advice id); new = current eligible ids (appliable or
  WARNING) that the current rules can produce minus the baseline. Revision bumped with nothing new -> baseline advanced
  silently. Evaluated when RigTuneScreen asks for notices.
- Typed accessors for awareness.json live in my own classes (Fingerprint, WhatsNew.Baseline), not in AwarenessStore
  (WS-B also adds there). All writes through `AwarenessStore.update` (X-M1).
- server-limits.json: `{"formatVersion":1, "salt":"<32 hex>", "servers":{"<hmac-sha256 hex>":{viewDistance,
  simulationDistance, kind, lastSeen}}}` through a StateStore (unknown fields kept); HMAC-SHA256 keyed by a random 16-byte
  salt created once (W-L1); a bad salt is replaced and the entries it keyed dropped; 32 entries, oldest lastSeen pruned.
- The mixin: `ClientPacketListenerMixin` (TAIL of handleLogin, handleSetChunkCacheRadius, handleSetSimulationDistance,
  `require = 1`) + `ClientPacketListenerAccessor` (serverChunkRadius, serverSimulationDistance); no `//? if`.
- BenchmarkController reads the accessor on `minecraft.getConnection()`; Outcome gains a trailing `serverLimit` (old
  constructor kept); BenchmarkResultScreen adds the limit line in one helper call.

## Tasks
1. `core/model/DriverVersion`, `core/hardware/DriverVersionParser` + DriverVersionParserTest (AC9.1: §2.3 vectors, the two
   Vulkan vectors, garbage -> UNKNOWN, 5-segment AMD -> UNKNOWN, never throws, fuzz).
2. `ConditionEvaluator.driverVersion` (body of the existing stub) + ConditionEvaluatorDriverVersionTest (AC9.2) and a
   fail-closed test with the pinned v030 evaluator on fixture rules (AC9.3 part; WS-R owns LegacyConditionFailClosedTest).
3. `core/awareness/Fingerprint`, `ChangeDetector` + ChangeDetectorTest/AwarenessFingerprintTest (AC9.5).
4. `core/awareness/WhatsNew` + WhatsNewTest (AC9.6).
5. `core/server/ServerLimitsStore` + ServerLimitsStoreTest (AC8.2).
6. `core/recommend/ServerCap` + ServerCapTest (AC8.1 as amended).
7. Client server side: mixin + accessor + json line, ServerLimitsTracker (kind, store, rebuild), RigTuneClient hook helper,
   RealController (public rebuild, cap call), BenchmarkController accessor + Outcome.serverLimit + result line,
   BenchmarkControllerServerLimitTest (AC8.3: cap + no Options reflection in the class file), ServerLimitNoticeSource,
   `rigtune.server.*` keys.
8. Client awareness side: AwarenessService (afterProbe, hardware + what's-new notices, shown hook, dismiss), the two
   sources, the one RealController call, `rigtune.awareness.*` keys, tooltip wrap.
9. Game tests: ServerLimitsGameTest (AC8.4, W-L2 citation), AwarenessGameTest (AC9.7), both network off, 3 sizes.
10. v040-written/ws-w (awareness.json + server-limits.json) written by a unit test; run compat030 if present.
11. After WS-R's driver seeds land: merge origin/feat/v0.4.0, AC9.4 scenario tests over the bundled rules.
12. Self-review (code-reviewer subagent), merge origin/feat/v0.4.0, build both versions, push, CI green, screenshots,
    docs/v0.4/design/ws-w.md.
