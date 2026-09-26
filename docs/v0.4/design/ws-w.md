# WS-W: server-aware advice + change awareness (SPEC 8, 9; amendments W-H1, W-L1, W-L2, W-L3, X-M1, X-M3)

Branch `feat/awareness`. Plan: docs/v0.4/plans/ws-w.md.

## Server-aware advice (item 8)
- **Capture**: `client/mixin/ClientPacketListenerMixin` injects at TAIL of `handleLogin`, `handleSetChunkCacheRadius` and
  `handleSetSimulationDistance` (`require = 1`, no `//? if`: javap shows the same names on 26.2 and 26.3);
  `ClientPacketListenerAccessor` reads `serverChunkRadius`/`serverSimulationDistance`. Both in rigtune.client.mixins.json.
- **`client/server/ServerLimitsTracker`**: the live `ServerLimits` (an `AtomicReference<Live>`; `Live` adds the address and
  "was N"), kind from `hasSingleplayerServer()` (SINGLEPLAYER, including an Open-to-LAN host) / `ServerData.isRealm()` /
  `isLan()` / else REMOTE. `ClientPlayConnectionEvents.DISCONNECT` clears it; the report rebuilds on JOIN, DISCONNECT
  and a live change (`RealController.rebuild()` became public, coordinator-approved). Registered once from
  `RigTuneClient.registerAwareness`.
- **`core/server/ServerLimitsStore`** (server-limits.json, a `StateStore`): `{"formatVersion":1, "salt":"<32 hex>",
  "servers":{"<HMAC-SHA256 hex>":{viewDistance, simulationDistance, kind, lastSeen}}}` (W-L1: random 16-byte salt created
  once and kept in the file; addresses are "not stored in readable form"). Keys: `host:port` lower-case with the default
  port written out; Realms `realm:<world name>`; **LAN guests `lan:<host>`** (an Open-to-LAN game picks a new port every
  time; keying by port would churn the 32-entry cap). A bad salt is replaced and the entries it keyed are dropped. The
  store only feeds "(was N)"; the Recommender never reads it; SINGLEPLAYER is never stored. The file never goes into the
  share report.
- **`core/recommend/ServerCap`** (X-M3): a pure step on the finished main-list report, called where RealController
  builds it (`ServerCap.apply(Recommender.recommend(...), live, rules)`), never inside `settingTargets`/`applyClamps`, so
  templates never see server limits. W-H1: target T, current C, limit L: T > C becomes min(T, max(C, L)) with "The
  server sends at most N chunks." appended when capped; capped down to C the recommendation is dropped (the notice
  explains); T <= C untouched. It lives in core/recommend for package access to `SettingValues.describe/number/format`
  (no visibility change there). `ServerCapTest.goldenReportUnchangedWithoutAServer` runs the golden matrix (no server,
  SINGLEPLAYER, a remote limit above every target: the same report object).
- **Notice** (`ServerLimitNoticeSource`, SERVER_LIMIT): "The server limits view distance to N chunks" / "… (you set M)";
  detail: "You set M; the server sends N, so N is what you see.", the simulation distance ("the server decides it"),
  "This server's limit changed since last time (was N).", and with DH loaded the "may" note (research §6 UNVERIFIED).
  Key `server-limit:<N>[:above]`: it names only the limit, never the server (a dismissal stores nothing about where the
  player plays; one dismissal hides that limit on every server: a product choice).
- **Benchmark**: `BenchmarkController.maxRenderDistance` keeps its clamp but reads the accessor on
  `minecraft.getConnection()` (no reflection on Options; `BenchmarkControllerServerLimitTest` checks the class file);
  `Outcome` gains a trailing `serverLimit` (old constructor kept) set when the server lowered a Tune's highest step, and
  BenchmarkResultScreen shows the notice's sentence then (one helper call).

## Change awareness (item 9)
- **`core/model/DriverVersion` + `core/hardware/DriverVersionParser`**: never throws, UNKNOWN when unrecognised. GL:
  Adrenalin "Context YY.M.rev.build" (the five-segment legacy form is UNKNOWN), "(Core Profile) Mesa x.y.z" (any
  vendor), "NVIDIA ddd.dd[.dd]", Intel "- Build a.b.c.d". Vulkan (26.3): the leading API version is dropped and only
  NVIDIA's number or a Mesa version is read from the rest; an unknown backend tries GL then Vulkan. Inputs over 1024
  characters are UNKNOWN; digit runs are bounded. `display()` pads NVIDIA's minor parts ("566.03").
- **`driverVersion` condition** (`ConditionEvaluator.driverVersion`, the existing dispatch line): keys vendor/atLeast/atMost
  only; TRUE/FALSE only when the detected vendor (GpuClass) is the rule's AND the string parses as **that vendor's own
  driver family** (NVIDIA -> geforce, AMD -> adrenalin, Intel -> intel-igpu). Deviation (stricter, fail closed; review
  finding M1): a Mesa version (nouveau, RADV, radeonsi, zink, ANV) is never compared with a proprietary range, so
  `{vendor nvidia, atMost 470}` can't fire on Mesa 24. A Linux/Mesa rule would need a new key (a SPEC amendment).
- **`core/awareness/Fingerprint` + `ChangeDetector`** (on `AwarenessStore`, X-M1): the fingerprint keeps raw strings;
  gpuVendor is the detected `GpuVendor` name (GL_VENDOR "ATI Technologies Inc." vs Vulkan "AMD"). Renderers compare
  normalised (lower case, parenthesised parts, "/PCIe/SSE2" and a leading "Mesa " dropped) so a Mesa update or a
  backend switch isn't a GPU change. Order: GPU (vendor or renderer) > driver (parsed versions when both parse in the
  same family, else raw strings on the same backend) > CPU or RAM (>= 512 MB). First run / corrupt (.bad) / unusable
  fingerprint: silent seed; newer/unreadable file: no notice (it could never be acknowledged). A difference that isn't a
  change (backend switch, RAM noise, a GPU the earlier probe couldn't read) moves the stored fingerprint on silently when
  the probe read everything (review M2), so a later driver update on the other backend is still seen.
- **`core/awareness/WhatsNew`** (W-L3): the baseline is a revision plus the ids that revision COULD produce
  (`potentialIds`: add/disable/conflict per mod rule, disable per obsolete rule, set per setting key, advice per advice
  id; rules with unknown `requires` included), so goal changes and newly installed mods don't count. New = the report's
  appliable or WARNING recommendations whose ids the current rules can produce and the baseline's can't (so `update:*`
  never counts). No baseline: seeded silently; newer revision with nothing new: baseline moved on silently; an OLDER
  revision (downgrade, cache gone) is not news and keeps the baseline (deviation from "differs"; review L4). At most
  1000 ids (the bundled rules have far fewer; `WhatsNewTest` checks).
- **`client/awareness/AwarenessService`**: `afterProbe` (one call after the probe in `RealController.rescan`) compares and
  keeps a per-session pending change; `hardwareNotice()` (Re-scan -> `rescan()`, Re-benchmark -> BenchmarkMenuScreen with
  the current screen as parent); `whatsNewNotice()` runs `WhatsNew.check` once per report on the render thread (a
  <= 64 KiB file read, a small write when seeding/moving on; same footprint class as the dismissal reads WS-K added).
- **W-L3 "shown"**: Fabric's AFTER_INIT doesn't fire on `rebuildWidgets` ("+N more" cycling), so `AwarenessService.register`
  adds a per-screen `ScreenEvents.afterTick` on each RigTuneScreen that compares `shownNotice().key()` with the pending
  key (no allocation per tick; it goes away with the screen; coordinator-approved). Being listed on NoticeScreen (the
  narrow-layout "…" screen renders every notice) also counts as shown. Once shown, the fingerprint is committed
  (async); the notice stays for the session.
- **Dismissals**: hardware and what's-new dismissals commit the fingerprint / move the baseline on and are kept for the
  session only, NOT stored in `dismissed` (review M3: a stored key would hide a recurring A -> B change for good and it
  would never be committed). Other notices' keys are stored as WS-K built it.

## Hotspot edits (all small)
RealController: `rebuild()` public, `ServerLimits live = serverLimitsTracker.live()` + the `ServerCap.apply` wrap, the
`awarenessService.afterProbe(hardware)` call. RigTuneClient: `registerAwareness(real)` helper (one call). RigTuneScreen
`extractNotice` and NoticeScreen: the notice tooltip wraps (`font.split`, coordinator-approved; it was one unwrapped
line). BenchmarkController: accessor + `serverLimit`. BenchmarkResultScreen: `serverLimitLine`. en_us.json:
`rigtune.server.*` then `rigtune.awareness.*` at WS-K's anchor after `rigtune.share.unavailable`.

## Tests and evidence
- Unit: DriverVersionParserTest (AC9.1, incl. a real 26.3 Vulkan lavapipe string from CI), ConditionEvaluatorDriverVersionTest
  (AC9.2 + the pinned-0.3.0 fail-closed check over fixture seeds), LegacyConditionFailClosedTest
  `everyDriverVersionRuleDecidesOnTheCurrentEvaluator` (AC9.3's current-code side over the bundled r14 seeds),
  KnowledgeV2ScenarioTest `driverSeedsFireOnTheAffectedDriversOnly` (AC9.4 over the bundled rules; it replaces the
  driver part of WS-R's `theV04ContentFiresNothingUntilItsEvaluatorsLand` tripwire, which keeps the jvm/stutter parts), ChangeDetectorTest (AC9.5), WhatsNewTest
  (AC9.6), ServerLimitsStoreTest (AC8.2), ServerCapTest (AC8.1 as amended + golden), BenchmarkControllerServerLimitTest
  (AC8.3), ServerLimitNoticeSourceTest, V040WrittenWsWTest (writes build/v040-written/ws-w and checks
  src/test/resources/v040-written/ws-w/: awareness.json + server-limits.json, H-M1).
- Released-jar harness: `tools/e2e/compat030.py` with the real ws-w set and the released 0.3.0 jar: RESULT PASS (9/9;
  "0.3.0 reading them changed no file").
- Game tests (CI, all 3 legs): ServerLimitsGameTest (dedicated server via `createServer(Properties)`, view-distance=6,
  simulation-distance=5; the test writes `eula.txt` in the wiped run directory, where the harness also writes
  server.properties; a live `PlayerList.setViewDistance(4)` exercises the packet hook, "(was 6)" and the cap 2 -> 4),
  AwarenessGameTest (seeded older driver + older baseline; shown -> committed; Re-benchmark; dismissals; reopen).
  Both run with the network off and put their state back.

## UNVERIFIED / open
- Exact Vulkan driver strings for NVIDIA/AMD/Intel hardware (only lavapipe captured in CI): Phase 5 (AC9.8).
- The DH note (research §6): "may" wording kept; Phase 5 re-checks against DH's docs.
- Realms end to end (AC8.5 records it); the LAN-guest path is covered by kind logic only until AC8.5's manual run.
