# WS-W: awareness, 4h, drivers, settings writes, launch-time advice (v0.5)

Branch `fix/v05-awareness` (worktree `rigtune-aware5`), from `feat/v0.5.0` @ a7613410 (ws-ci + WS-K merged). Scope:
docs/v0.5/PLAN.md "WS-W"; SPEC 2W (AW-1, AW-2, Latent 2), 2D, 2R's L6, 2L (RW-16 part a), 4h. WS-W2 (C18) takes
ToolsScreen over after this merges.

This file is first the TDD task plan (committed before any code), then, as each task lands, what landed, the
deviations, the evidence and the AC table.

## Inputs read
PLAN (Global Constraints, protocol, WS-W, contracts, Ownership, Hotspots, Cross-workstream ACs, Local runs,
Amendments); SPEC (top, compatibility, X1-X12, C3, C8, Shared contracts, 2W, 2D, 2R, 2L, 4h, Amendments);
docs/v0.5/design/ws-k.md (the contracts as landed: `OutsideChanges` stubs, `OutsideChangesNoticeSource` skeleton,
`AwarenessStore.optionsAtExit/setOptionsAtExit/takeOptionsAtExit`, `AwarenessService.SESSION_ONLY_PREFIXES`, the start
hook, the CLIENT_STOPPING phase `rigtune:v05-before-exit`, `PreviewScreen.settingsSyncLine`, the anchors
`rigtune.outside.*` after `rigtune.awareness.whats_new.one` and `rigtune.startup.perf_counters.*` after
`rigtune.startup.advice`, the skeleton methods `awarenessFixes`, `settingsChangedOutside`, `walkToolsStartup`, the
footprint baseline of run 36310249248); research: av (AW-1, AW-2, Latent 2), vg §4 (+ r-verify's `vectors.tsv` with
each row's vendor name and renderer), rw §12 (RW-16) and the saved Microsoft pages, lm §2 (the options sync); DESIGN
"Change awareness (0.4)"; the code named below.

## TDD task plan

Each task: the red test first (the audit-verify throwaway tests reused where the SPEC names them), then the code, then
a commit. Local: `./gradlew :26.2:test --tests '<classes>'` in a build slot (`:26.3:` too where a class touches a
version-specific API; none planned); the full build and every game test are CI's.

| # | task | files | tests (red first) | closes |
|---|---|---|---|---|
| W1 | Latent 2: `Fingerprint.of` maps the probe's `"unknown"` placeholders to `""` (GPU renderer, vendor string, driver; the CPU name too, the same bug class, see Deviations) | `core/awareness/Fingerprint` | `ChangeDetectorTest.latentUnknownGpuIsNoChange` (av's test), `.aFirstRunSeedFromAnUnknownProbeThenARealGpuRaisesNothing`, `.anUnknownCpuIsNoHardwareChange` | AC2W.3 |
| W2 | 2D: `ADRENALIN`'s month is 1-12; the 38 real strings as `src/test/resources/drivers/real-strings.tsv` (backend, vendor name, renderer, raw, family, comparable, confidence, source) | `core/hardware/DriverVersionParser`, the TSV | `DriverStringsTest.everyRealStringParsesAsRecorded` (parameterised; R1/R2 red), `.theTableHas38SourcedRows`; `DriverVersionParserTest.legacyAmdContextIsUnknown` (R1-R4); existing DriverVersionParserTest, ConditionEvaluatorDriverVersionTest, ChangeDetectorTest unchanged | AC2D.1, AC2D.2 |
| W3 | L6: `StartupNotices.takePrivacyNotice(settings)` is a pure state change; the save goes through `SettingsSaver.shared()` (`RigTuneClient.java:192`); `BenchmarkMenuScreen.java:71`'s raw save → `SettingsSaver` (one marked line) | `client/StartupNotices`, `client/RigTuneClient` (L6 line), `client/ui/BenchmarkMenuScreen` (one line) | `StartupNoticesTest.theFlagIsSavedThroughSettingsSaver` (observed by `SettingsSaver.shared().flush`), `SettingsWritesTest.noRawSettingsSaveOutsideSettingsSaver` (grep over src/client: red on StartupNotices.java:20 and BenchmarkMenuScreen.java:71) | AC2R.2 |
| W4 | AW-1: in `afterProbe` a NONE against the committed fingerprint keeps a committed notice (no equality test); a changed-back or new change replaces it; the notice's own Re-scan retires it (commits, clears, then rescans) | `client/awareness/AwarenessService` | `AwarenessServiceTest.aw1ShownNoticeSurvivesARescan` (av's test), `.itsOwnRescanRetiresIt`, `.aChangeBackReplacesTheCommittedNotice`, `.anUncommittedNoticeIsStillReplacedByNone` | AC2W.1 (unit) |
| W5 | AW-2: NoticeScreen reports its listed notices through a callback at the end of every `init()` (an instance listener AwarenessService sets in its AFTER_INIT branch, so nothing loads at mod init; the first init is still reported by AFTER_INIT); no tick work | `client/ui/NoticeScreen` (the callback only), `AwarenessService.register` | `AwarenessServiceTest.aListedHardwareNoticeIsCommitted`; the game test `awarenessFixes` (AC2W.2's red: before the fix the fingerprint isn't committed after a dismiss-rebuild) | AC2W.2 |
| W6 | 2L detection, read-only: `core/hardware/PerfCounters` (pure: a `Registry` reader interface; Perflib REG_DWORD ≠ 0 → off; REG_SZ → on + "unusual"; absent → on; the PerfOS/PerfProc/PerfDisk per-service values: DWORD ≠ 0 → that service off, other types "unusual"; not Windows → never read); `client/probe/WindowsRegistry` (JNA `Advapi32Util.registryValueExists`/`registryGetValue` only); `HardwareProbe.probeSlow` keeps the result in `SlowPart` (old constructor kept) and logs one INFO line | `core/hardware/PerfCounters`, `client/probe/WindowsRegistry`, `client/probe/HardwareProbe` | `PerfCountersTest` (1 → off; 0 → on; REG_SZ → on + unusual; absent → on; not Windows → the reader is never made; per-service cases), `NoRegistryWriteTest` (no `RegSetValue`, `registrySet`, `registryCreateKey`, `registryDelete` in src) | AC2L.1 |
| W7 | 2L timer: `client/mixin/CrashReportMixin` (HEAD/RETURN on `CrashReport.preload`, `require = 0`) → `client/probe/PreloadTimer` (two `System.nanoTime()` reads into static longs); one line in `rigtune.client.mixins.json` | new mixin + timer, the mixins json | `PreloadTimerTest` (not measured → null; start+end → ms; end without start → null), `MixinConfigTest.crashReportMixinIsOptional` (listed, every injector `require = 0`) | AC2L.4 (timer part) |
| W8 | 2L advice + ToolsScreen as a `RowList`: the five tool buttons stay screen widgets (UiGameTest and ProductionSmoke press them by key), in two columns when one column would leave the list under 40 px (854×480@3); below them a scrolling `ToolsList` with the startup line, its notes and, while the counters are off, the advice rows (each a `RowFocus` stop; the two Microsoft pages as rows that open vanilla's link confirmation); `startupLine()`, `startupDetail()` and `startupDetailClipped()` (false: the list scrolls) keep their signatures for FootprintGameTest; `core/hardware/PerfCounterAdvice.lines(PerfCounters, @Nullable Long preloadMs)` builds the text | `client/ui/ToolsScreen`, `core/hardware/PerfCounterAdvice`, en_us.json (`rigtune.startup.perf_counters.*`), `A11yGameTest.walkToolsStartup` | `PerfCounterAdviceTest` (lines exactly when off; the measured line only when measured, as "%s s"; no "lodctr"; both URLs; WordingTest/LangCheckTest pass); A11y walk with a seeded probe result at the three sizes + 854×480@3: every row and button reached by Tab and narrated, the layout check, screenshots | AC2L.2, AC2L.3, X6, X12 |
| W9 | 4h core: `core/awareness/OutsideOptions` (pure): the vanilla keys RigTune applied and its latest applied value (from the journal; changeable vanilla keys only; `fullscreen` never), the stop snapshot (≤ 64 keys, values through `SettingKeys.safeValue`-style cleaning and a 32-character cap), options.txt parsing (as Options.load: JSON-unquoted), the comparison | `core/awareness/OutsideOptions` | `OutsideChangesTest` (AC4h.1's eight cases: unchanged → nothing; an in-game change before a clean exit → nothing; an outside change → flagged; no snapshot → no compare; consumed after one compare; `fullscreen` ignored; a key RigTune never applied ignored; over-long/unsafe values capped/sanitised) | AC4h.1 |
| W10 | 4h client: `OutsideChanges.snapshotAtStop` (skips during a benchmark or a Try it; exactly one `AwarenessStore.setOptionsAtExit`, nothing when there is nothing to watch), `compareAtStart` (the worker: take the snapshot, read the journal and options.txt, compare), `afterApply` (the Modrinth App fan-out line in the Apply status, and this session's applied vanilla keys join the watched set); `OutsideChangesNoticeSource` (SETTINGS_CHANGED_OUTSIDE; the Modrinth App's steps under that launcher; actions Apply RigTune's values again = an ordinary Apply, one journal entry, and Keep); `PreviewScreen.settingsSyncLine` | `client/awareness/OutsideChanges`, `client/notice/OutsideChangesNoticeSource`, `PreviewScreen.settingsSyncLine`, en_us.json (`rigtune.outside.*`) | `OutsideChangesClientTest` (the fan-out line only for MODRINTH_APP, in the Apply status only when settings were written now; the stop handler's one update: its pure part plus a source check; the notice text with and without the app's steps; Keep and the re-apply selection) | AC4h.3 (unit part), AC4h.4 |
| W11 | Game tests: `AwarenessGameTest.awarenessFixes` (AW-2 at 854×480@3 with 3 notices: dismiss the top one → awareness.json holds the new fingerprint; AW-1: after that, `rescan()` → the notice is still the source's; its own Re-scan → gone) and `settingsChangedOutside` (AC4h.2: apply a vanilla key, the stop snapshot, an outside write to options.txt, the start compare; the notice with the app's steps under brand `theseus`; Apply again → one journal entry that Undo reverts; Keep → gone; everything put back) | `AwarenessGameTest` (my two methods) | CI, 3 legs | AC2W.1 (game), AC2W.2, AC4h.2 |
| W12 | Fixture set `ws-w`: awareness.json with `optionsAtExit`, written by the test through `AwarenessStore` and `OutsideOptions`; `expect.json` (0.4.0's AwarenessStore keeps `optionsAtExit`); compat030 against the set | `src/test/resources/v050-written/ws-w/`, `V050WrittenWsWTest` | the test compares (regenerates with `RIGTUNE_REGENERATE_FIXTURES=1`) | AC4h.3 (round trip; compat040 once WS-E's interpreter lands) |
| W13 | Code-deciding real run (under the game-test lock, 26.2, this PC, counters off): does `CrashReportMixin` apply before `Main.main`'s `preload()` (the measured time in the log and in Tools), does the detection read Perflib = 1; the Perflib key exported before and after (read-only) is byte-identical | none (evidence) | `runProductionClientGameTest -PgametestClasses=AwarenessGameTest,A11yGameTest` | the UNVERIFIED mixin timing; AC2L.5 stays Phase 5's |

No new `//? if` block expected (X9: `ConfirmLinkScreen.confirmLinkNow(Screen, URI)` and `CrashReport.preload()V` exist
on both nodes, javap-checked). No new thread, no network, no new per-tick or per-frame work (AW-2's callback runs at
`init()` only).

**Push plan.** Held while the coordinator's streak runs; the first push carries this doc and W1-W3 (+ whatever is done).

---

# As landed

Paths as in PLAN "Ownership" (`client/…`, `core/…`, `gametest/…`). One commit per task (W1 43b7878e, W2 25be33ee, W3
85a3d0c8, W4/W5 930a35b1 + d3576d79, W6 58d1c864, W7 47da8e55, W8 a678811c, W9 2f839034, W10 021929c2, W12 61757aca,
W13's record edfe8263). No new `//? if` block. No new thread, no network, no per-tick or per-frame work.

## What landed

| item | change | files | tests (red first) |
|---|---|---|---|
| Latent 2 | `Fingerprint.of` maps HardwareProbe's `"unknown"` placeholder to `""` for the GPU vendor string, renderer and driver and the CPU name. A failed probe is no GPU/CPU, so it can't raise "Your GPU changed" or seed a fingerprint the next good probe differs from (`ChangeDetector.check` then moves the stored one on silently). | `core/awareness/Fingerprint` | `ChangeDetectorTest.latentUnknownGpuIsNoChange` (av's), `.aFirstRunSeedFromAnUnknownProbeThenARealGpuRaisesNothing`, `.anUnknownCpuIsNoHardwareChange` (3 red before) |
| 2D | `ADRENALIN` = `\bContext\s+(\d{1,3})\.([1-9]` or `1[0-2])\.(\d{1,2})\.\d{1,9}(?![.\d])` (the SPEC's pattern); the 38 strings of vg §4.2 (rows 1-29, 31-34, R1-R5; 25-32 marked `composed`) in `src/test/resources/drivers/real-strings.tsv` with vendor name and renderer (r-verify's `vectors.tsv`), the detected vendor, family, comparable version and source URL (GPU model only; R5 is CI run 36284606444). | `core/hardware/DriverVersionParser`, the TSV | `DriverStringsTest.everyRealStringParsesAsRecorded` (one dynamic test per row: `detectVendor` then `parse`), `.theTableHas38SourcedRows`, `.theLegacyAmdBranchIsUnknown`; `DriverVersionParserTest.theAdrenalinMonthIsOneToTwelve` (4 red before: R1, R2 and the two month tests); every existing vector unchanged |
| L6 | `StartupNotices.takePrivacyNotice(settings)` is a pure state change; `takePrivacyNotice(settings, configDir)` saves through `SettingsSaver.shared()` (RigTuneClient's L6 line); BenchmarkMenuScreen's scene change saves through `SettingsSaver` (a marked edit: comment, the call, its import). | `client/StartupNotices`, `client/RigTuneClient` (L6 line), `client/ui/BenchmarkMenuScreen` | `StartupNoticesTest.takingTheNoticeIsAPureStateChange`, `.theFlagIsSavedThroughSettingsSaver` (observed through `SettingsSaver.shared().flush`); `SettingsWritesTest.noRawSettingsSaveOutsideSettingsSaver` (red on StartupNotices.java and BenchmarkMenuScreen.java:71 before), `.theCheckSeesARawSave` |
| AW-1 | `afterProbe`: a NONE against the committed fingerprint keeps a committed notice (no equality test); a changed-back or new change replaces it; an uncommitted one still goes with a NONE. The notice's own Re-scan `retire()`s it: commits the fingerprint synchronously (as `dismiss` does; `shown()`'s commit is asynchronous), clears it, then rescans. | `client/awareness/AwarenessService` | `AwarenessServiceTest.aw1ShownNoticeSurvivesARescan` (av's), `.itsOwnRescanRetiresIt`, `.itsOwnRescanCommitsANoticeNotYetCommitted`, `.aChangeBackReplacesTheCommittedNotice`, `.anUncommittedNoticeGoesWhenTheHardwareChangesBack` (4 of 6 red on 0.4's logic; the last two pin kept behaviour) |
| AW-2 | NoticeScreen calls an instance listener with what each `init()` listed (rebuilds included). AwarenessService sets it in its existing AFTER_INIT branch (so nothing is class-loaded at mod init) and still reports the first listing there. | `client/ui/NoticeScreen` (the callback only), `AwarenessService.register` | `AwarenessServiceTest.aListedHardwareNoticeIsCommitted`; `AwarenessGameTest.awarenessFixes` |
| 2L detection | `PerfCounters.detect(osName, Supplier<Registry>)` (core, pure): only on Windows; Perflib's "Disable Performance Counters" REG_DWORD ≠ 0 → off; another type → on + "unusual"; absent → on; PerfOS/PerfProc/PerfDisk: DWORD ≠ 0 → that service off, another type → unusual; a reader that can't be made (no JNA) → NOT_READ; a value that can't be read → absent. `WindowsRegistry` (client) reads HKLM with `Advapi32Util.registryValueExists`/`registryGetValue` only. `HardwareProbe.probeSlow` (the worker, never preLaunch) keeps it in `SlowPart.perfCounters` (the 5-arg constructor kept) and logs one INFO line when anything is off or unusual; `HardwareProbe.perfCounters()` never waits; `seedPerfCounters` is the A11y walk's seam. | `core/hardware/PerfCounters`, `client/probe/WindowsRegistry`, `client/probe/HardwareProbe` | `PerfCountersTest` (1 → off; 0 → on; REG_SZ → on + unusual; absent → on; not Windows → never read; the services; this PC's shape; QWORD/binary; a throwing read; no JNA), `NoRegistryWriteTest` |
| 2L timer | `CrashReportMixin`: HEAD/RETURN on `CrashReport.preload()V` (javap: same on 26.2 and 26.3), `require = 0` → `PreloadTimer.start()/end()` (two `nanoTime` reads into static fields); not measured → null. One line in `rigtune.client.mixins.json`. | `client/mixin/CrashReportMixin`, `client/probe/PreloadTimer`, the mixins json | `PreloadTimerTest` (null until start and end; the duration; the mixin registered, both injectors optional) |
| 2L advice + Tools | `PerfCounterAdvice.lines(PerfCounters, @Nullable Long preloadMs)` (core): nothing unless Perflib is off; else what the setting is, the crash-report setup's time at this launch when measured ("about 1 s is usual … part of that time may be related to this setting"), "0 is Windows' default; changing it needs an administrator and a Windows restart, and a tuning tool may have set it on purpose; RigTune doesn't change Windows settings", and Microsoft's two pages (cc737243 and KB 2554336) with their addresses. ToolsScreen: the five tool buttons stay screen widgets (UiGameTest and ProductionSmoke press them by key); below them a scrolling `ToolsList` (RowList) with the startup line, its notes and the advice, one `RowFocus` stop per line, the page rows opening vanilla's `ConfirmLinkScreen` on a click or Enter; `startupLine()`, `startupDetail()` keep their signatures and `startupDetailClipped()` is now always false (the list scrolls); `perfCounterLines()`, `rowText()`, `rowsFit()` for tests. | `core/hardware/PerfCounterAdvice`, `client/ui/ToolsScreen`, en_us.json (`rigtune.startup.perf_counters.*`, 5 keys after `rigtune.startup.advice`) | `PerfCounterAdviceTest` (lines only when off; the measured line only when measured; the wording, no "lodctr", no guess who set it; both pages linked); `A11yGameTest.walkToolsStartup` |
| 4h core | `OutsideOptions` (pure): `applied(journal)` (the latest applied value per changeable vanilla key; an undo's change stops the watch; never fullscreen; unsafe or > 32-character values left out), `snapshot(watched, vanillaNow)` (≤ 64 keys, cleaned and capped), `parseOptions` (as Options.load: `name:value`, a JSON string unquoted), `compare` (keys RigTune applied only; none without a snapshot), `clean`. | `core/awareness/OutsideOptions` | `OutsideChangesTest` (AC4h.1's eight cases + latest value wins, undo, 64 keys, options.txt parsing) |
| 4h client | `OutsideChanges` fills WS-K's stubs: `snapshotAtStop` (one `setOptionsAtExit`; nothing while `BenchmarkController.running()` or `Busy.tryItRunning`, before the journal was read, or with nothing watched); `compareAtStart` (the start hook's worker: `takeOptionsAtExit`, the journal, options.txt ≤ 1 MiB); `afterApply` (this session's changeable vanilla keys join the watched ones; the fan-out line when settings were written now in a Modrinth App instance). `OutsideChangesNoticeSource`: SETTINGS_CHANGED_OUTSIDE, not dismissible, key `settings-changed-outside:<ms>` (session state only), message "A setting was …(%s)" / "N settings were … (e.g. %s)" with the rules' labels, detail listing up to 8 changes plus, in a Modrinth App instance, the app's steps; actions Apply RigTune's values again (`Busy.refusal` first; then an ordinary `apply` of RigTune's values for the keys not already at them; the status in a toast) and Keep. `PreviewScreen.settingsSyncLine` shows the same fan-out line under "Written now". | `client/awareness/OutsideChanges`, `client/notice/OutsideChangesNoticeSource`, `client/ui/PreviewScreen.settingsSyncLine`, en_us.json (`rigtune.outside.*`, 9 keys after `rigtune.awareness.whats_new.one`); `V05HooksTest`'s stub line | `OutsideChangesClientTest` (the fan-out line only for MODRINTH_APP and only when written now; the watched keys from an Apply; the stop snapshot's cases; the stop handler's one update (source check); the notice text, steps, actions; the re-apply selection; retired once) |
| fixtures | `v050-written/ws-w/awareness.json` (`optionsAtExit`, written through AwarenessStore and OutsideOptions) + `expect.json` (`AwarenessStore` keeps `optionsAtExit`, no `.bad`). | the set, `V050WrittenWsWTest` | compares by default; `RIGTUNE_REGENERATE_FIXTURES=1` rewrites |

Game tests (my methods only): `AwarenessGameTest.awarenessFixes` (AC2W.1, AC2W.2), `.settingsChangedOutside` (AC4h.2,
the Apply status's fan-out line under theseus), `A11yGameTest.walkToolsStartup` (AC2L.2's A11y part, X6, X12). The
shared game-test files got only imports besides those bodies and their helpers.

## Evidence

- **CI** (every job and all three legs green): run 36321500954 (47da8e55: W1-W7), 36325022781 (021929c2: + W8-W10),
  36326382550 (edfe8263), 36332107491 (4423f6eb: origin/feat/v0.5.0 690b8f4c merged), and after the code review's fixes
  **36334845522 (c42d9126): 2254 unit tests per node, 2 skipped, 0 failed**; its screenshots `awareness-aw2-*-1280x720-scale3`
  and `a11y-tools-startup-1280x720-scale3` looked at (the driver notice listed under four canned ones after the
  dismissal; the Tools list scrolling under the buttons, Done clear). Unit tests 2006 per node, 2 skipped (ApplyLockTest's and LogSafeTest's Windows-only cases, as
  before). The first push's run 36319839409 failed on every leg in `awarenessFixes` only: at 854×480 the game caps the
  GUI scale at 2 (Window.calculateScale keeps the GUI at least 320×240), so all three notices fit; fixed in d3576d79 by
  counting the rows that fit first.
- **Screenshots looked at** (`gametest-screenshots-26.2-OpenGL`; 26.3-Vulkan spot-checked): run 36321500954
  `awareness-aw2-before/after-854x480-scale3` (five canned notices and "+1 more"; after the dismissal the driver notice
  "(23.0.0 → 25.2.8)" with Re-scan/Re-benchmark/× is listed), `awareness-aw1-retired-854x480-scale3` (gone); run
  36325022781 `a11y-tools-startup-{1280x720,640x480,854x480}-scale2` and `-854x480-scale3` (buttons, then the list: the
  startup line, the mod-set note, the advice, the five 2L rows; at 640×480 the list scrolls with its scrollbar; nothing
  overlaps Done), `a11y-tools-startup-link-focus` (the focus frame on the reference row), `…-link-confirm` (vanilla's
  "open or copy" screen with the cc737243 address), `awareness-outside-{1280x720,640x480,854x480}-scale2` (the notice
  line "A setting was changed outside the game sinc…" with Apply RigTune's values again / Keep / +1 more; at 640 the
  "…" button), `footprint-tools-*` and `ui-tools-*` (the 0.4 checks on the relaid screen: startup line and advice as
  rows, Done clear). CI's own measured line: "Minecraft's crash-report setup took 0.3 s at this launch" (Linux: the
  mixin applies in the production client there too). The 4h block's log in CI (36325022781, 26.2 OpenGL): both rounds
  flagged exactly `[vanilla.entityShadows]`, so no other game-test class's in-memory change was a false positive.
- **W13, the code-deciding real run** (2026-09-28 00:33, this PC, Windows 11, 26.2 production client,
  `runProductionClientGameTest -PgametestClasses=AwarenessGameTest,A11yGameTest`, under the game-test lock, released in
  the same script; evidence in `<scratch>/ws-w/realrun/`): the worker logged `Windows performance counters: off
  (Perflib's "Disable Performance Counters" isn't 0); unusual value type: PerfOS` (rw §12.2's shape: Perflib REG_DWORD 1,
  PerfOS "0" as REG_SZ); **the mixin applied before `Main.main`'s preload**: `crash-report setup 3135 ms`, and Tools
  showed the 5 advice rows with "took 3.1 s at this launch" (screenshot `a11y-tools-startup-this-machine`); vanilla's
  OSHI block ran 00:33:31 → 00:33:32 (the paging-file PDH/WMI failures as in rw §12.1). The Perflib key exported before
  and after the run (`reg export`, read-only) is byte-identical (sha256 5d38f626…ab308c). Both classes passed locally
  (AwarenessGameTest 7.7 s: AW-2, AW-1, 4h Apply again + Undo and Keep; A11yGameTest 27.1 s).
  **Again after the code review** (2026-09-28 02:50, the same setup, head 618c6eb2 + this doc): both classes passed
  (AwarenessGameTest 7.8 s incl. the reworked L3 path; A11yGameTest 31.1 s incl. the Tab order and the rebuild on the
  probe's result); `crash-report setup 1986 ms` (a warmer launch) and the 5 advice rows on this PC; the Perflib export
  byte-identical again (same sha256). A first attempt with the review's first L3 version failed in `awarenessFixes`
  (awareness.json kept the old driver), which led to the rework (618c6eb2).
- **Fixtures**: compat030 (`tools/e2e/compat030.py` with the released `rigtune-0.3.0+mc26.2.jar`, v040-written with this
  set's `optionsAtExit` merged into its awareness.json): all 9 checks PASS, "0.3.0 reading them changed no file". The
  released `rigtune-0.4.0+mc26.2.jar`'s own `AwarenessStore` rewriting this set's awareness.json (a dismissal) keeps
  `optionsAtExit` (a single-file program, `<scratch>/ws-w/compat1/Keep040.java`: KEPT). compat040's interpreter is
  WS-E's (not merged): `expect.json` is ready for it.

## Footprint deltas (against ws-k.md's per-leg baseline, run 36310249248)

Run 36326382550 (edfe8263), ms, with ws-k.md's baseline and its "before WK" / earlier-WS-K range for the runner spread.

| leg | renderThreadInitCpuMs | clientStartedWallMs | workerCpuMs5s | tickHookOnVsReference | tickHookAllocBytes |
|---|---|---|---|---|---|
| 26.2 OpenGL | 100.8 (base 82.2; range 63.5-112.9) | 58.5 (36.4; 25.5-69.9) | 182.6 (135.5; 132.8-214.1) | 1.539 (1.481) | 0 |
| 26.3 OpenGL | 116.9 (82.2; 77.8-117.1) | 35.0 (27.0; 20.4-36.0) | 207.8 (153.2; 141.4-200.1) | 1.527 (1.746) | 0 |
| 26.3 Vulkan | 114.2 (120.0; 74.9-116.6) | 35.6 (39.9; 24.7-49.4) | 190.6 (200.7; 144.6-217.0) | 1.572 (1.535) | 0 |

The final run 36334845522 (c42d9126): 26.2 OpenGL 87.3 / 23.2 / 170.5 / 1.476, 26.3 OpenGL 125.6 / 35.3 / 230.3 / 1.569,
26.3 Vulkan 101.7 / 38.4 / 194.5 / 1.512 (same columns), `tickHookAllocBytes` 0 on every leg; 26.3 OpenGL's worker 230.3
is that leg's highest so far and inside the 300 budget (its render-thread init is 125.6 of 150 on the same run, so the
runner looks slow overall). `v05RenderThreadResolve` null and `v05HolderCreatedOn` "RigTune worker" on every leg; `rigtuneClassBytesIdle`
76672/76728/76504 (limit 109296). Reading: WS-W adds no render-thread init work (AW-2's listener is set in AFTER_INIT,
not at init; the mixin runs in vanilla's own startup before RigTune's window; nothing else at init), so the init values
sit inside the runner spread (this branch's earlier run 36321500954: 118.6/104.7/110.4). The worker adds, on Linux,
OutsideChanges' journal read at start (no snapshot in CI's fresh run dir) and no registry read (not Windows); 26.3
OpenGL's 207.8 is above that leg's WS-K-era range and inside the 300 budget while 26.3 Vulkan's is below its baseline,
which reads as runner spread; the first streak after merging gives the medians. No new tick work:
`tickHookAllocBytes*` 0 on every leg (AC2W.2's footprint half).

## Deviations
- **Latent 2** also maps the CPU name's "unknown" (the same bug: a failed OSHI read raised "Your hardware changed").
- **AW-2**: the callback is an instance listener AwarenessService sets from its AFTER_INIT branch, not a static set at
  mod init, so NoticeScreen isn't class-loaded during `onInitializeClient`; the first listing is still reported there.
- **AW-1**: the notice's own Re-scan commits synchronously on the render thread (one small awareness.json update, as a
  dismissal already does) so the rescan it starts compares with the new fingerprint.
- **Tools**: each line is a list row with a `RowFocus` (X6's rule for rows), not `RowFocus.standalone` as 2L's text says;
  the five buttons stay screen widgets (a button in a list row isn't found by UiGameTest's and ProductionSmoke's
  by-key presses, which are frozen). The 0.4 look (centred text) is kept. 2L's advice is shown for Perflib's switch
  only; a service's own switch needs none (OSHI skips those counters itself) and is logged with any unusual type.
- **X12's 854×480 at GUI scale 3** renders at scale 2 (the game keeps the GUI at least 320×240). After the review the
  scrolling size is 1280×720 at GUI scale 3 (the coordinator's X12 amendment) in walkToolsStartup and the AW-2 block;
  the AW-2 block counts what fits rather than assuming a number of notices.
- **W8's two-column button fallback** (planned for a height where one column leaves the list under 40 px) was not built:
  the game keeps the GUI at least 240 px high, where one column leaves the list 60 px (about 5 lines), so it could never
  trigger.
- **4h**: the snapshot's keys are the journal's (`vanilla.renderClouds`), with the exit time under `$exitAt`; RigTune's
  value for a key is its latest APPLIED change (an undo's own changes skipped); this session's Applies add theirs;
  "Apply RigTune's values again" asks the shared busy check (C8) first. The notice isn't dismissible (Keep is its answer)
  and stores nothing. `V05HooksTest`'s line for the 4h stub (WS-K's test) now checks the filled step's launcher condition
  (its other lines unchanged).
- **BenchmarkMenuScreen**: the "one marked line" is the call plus its comment and import.
- New core files `core/hardware/PerfCounters`, `PerfCounterAdvice`, `core/awareness/OutsideOptions` hold the pure parts;
  `client/probe/WindowsRegistry`, `PreloadTimer` the client parts (all new, WS-W's).

## Code review (0 H, 2 M, 8 L; the coordinator's decisions: fix all)
| # | finding | fix | commit |
|---|---|---|---|
| M1 | A fingerprint 0.4 stored from a failed probe ("unknown") still compared as a real GPU | `Fingerprint.read` maps the placeholder too; `ChangeDetectorTest.aStoredUnknownFingerprintIsNoChangeAndIsReseededSilently` (red before) | 7145ed80 |
| M2 | The Modrinth App line on any Apply/Preview that wrote settings, though the app syncs options.txt only | `OutsideChanges.applyLine` (a vanilla SetSetting written now) and `previewLine` (an options.txt row under "Written now"); two tests | 892948f9 |
| L3 | The same change reported again before a seen notice's commit was written started it over as unseen | kept as seen, and the fingerprint written again (the first version kept it without writing, which the local AwarenessGameTest run showed leaves awareness.json on the old value: reworked in 618c6eb2); `AwarenessServiceTest.theSameChangeReportedAgainKeepsTheCommittedNotice` | 7145ed80, 618c6eb2 |
| L4 | `applied` updated by the worker and the render thread without atomicity | an `AtomicReference` with `updateAndGet` | 892948f9 |
| L5 | An undo's change removed the key, though an earlier Apply may still be in effect | the latest APPLIED change wins; an undo's own changes skipped; `undoingALaterApplyWatchesTheEarlierValue` | 892948f9 |
| L6 | 0.5 → 0.4.0 → 0.5: in-game changes under 0.4 looked changed outside | the snapshot is stamped (`$exitAt`); a launch of another RigTune version recorded in startup-times.json since then (0.4 writes it at every launch) skips the compare; `aSessionOfAnotherVersionSinceTheExitSkipsTheComparison` | 892948f9 |
| L7 | A key back at RigTune's value gave a notice whose one action does nothing | `compare` skips it; `aChangeBackToRigTunesValueIsNothing` | 892948f9 |
| L8 | No Tab-order check over the tool buttons and Done; an impossible 854×480@3 size | `checkToolsTabOrder` (five buttons in order, every row, Done last); 1280×720@3 | f9919dc8 |
| L9 | Tools opened before the probe finished never showed the advice | `ToolsScreen.tick()` rebuilds when `HardwareProbe.perfCounters()` changes; checked in the walk | f9919dc8 |
| L10 | This file | this section, the deviations, the evidence | this commit |

## Review-11 fixes (branch fix/v05-r11-ws-w)
| id | sev | finding | fix | test (red first) |
|---|---|---|---|---|
| FEAT-5 | L | RigTune applied 12, the player set 20 in game, the sync wrote 8: the notice said "Render distance: 20 → 8" while "Apply RigTune's values again" sets 12, shown nowhere; the reapply reason ("RigTune's value before it was changed outside the game") was untrue there | when the value before the outside change isn't RigTune's and Apply again would set RigTune's, the change reads "Render distance: 20 → 8; RigTune's value: 12" (new key `rigtune.outside.change_and_rigtune`) in the message's example and the detail; `rigtune.outside.reason` is now "The value RigTune last applied." | `OutsideChangesClientTest.theValueApplyAgainWillSetIsShownWhenItIsNotTheOneBefore` (failed on the old message) |
| COMPAT-6 | L | The CrashReport timer proven only on 26.2; 26.3 only logged `preloadMs()`, accepting null | not a 26.3 gap: javap shows `invokestatic CrashReport.preload:()V` in `net.minecraft.client.main.Main` on both 26.2 and 26.3 (offset 749 on each), and CI run 36383257100's 26.3 logs measured it ("crash-report setup 1211 ms" 26.3 OpenGL part 1, "547 ms" part 2, "806 ms" 26.3 Vulkan part 1). Found meanwhile: `net.minecraft.server.Main` calls `preload()` too, so the game tests' in-process dedicated server re-timed it (part 2 logged 547 ms at the launch and 281 ms later): `PreloadTimer` now keeps the first (the launch's) measurement. `A11yGameTest.walkToolsStartup` fails a leg whose `preloadMs()` is null (every leg is a production client) | `PreloadTimerTest.aLaterPreloadInTheSameJvmKeepsTheLaunchsMeasurement` (failed before: the later call's value) |
| COMPAT-3 | L | 0.5 → 0.3.0 → 0.5: 0.3.0 records no startup run, so an in-game change under it showed as "changed outside the game" (also a 0.4 launch that never reached the title screen) | `OutsideOptions.anotherLaunchSince`: vanilla's log config archives the previous session's latest.log as `logs/*.log.gz` when a game starts, so this launch made one archive newer than `$exitAt` and each launch in between one more; two or more skip the compare. `OutsideChanges.logArchives` lists that one folder (not recursive). No archive (another log config) or no stamp: can't tell, as before | `OutsideChangesTest.anotherLaunchSinceTheExitSkipsTheComparisonWhateverItsVersion` (the startup-run guard alone returns false for that case), `OutsideChangesClientTest.theLogArchivesAreTheGzippedLogs` |

## Residuals (not fixed, with why)
- A snapshot written by a 0.5 build before the exit stamp (a pre-release) has no `$exitAt`, so the 0.4-in-between check
  can't tell and compares as before.
- A key RigTune applied that the game changed in memory without saving options.txt before a clean exit would be flagged
  at the next start (vanilla saves when a settings screen closes; RigTune's own Apply saves; a benchmark or Try it skips
  the snapshot).
- Mixin's `@Inject` makes two `CallbackInfo` objects once per launch (before RigTune's init); nothing per tick.
- The KB page's address can wrap inside the word at some widths (cosmetic; the row opens the page).
- The notice line clips SETTINGS_CHANGED_OUTSIDE's message behind its long action label at 1280×720 (full text on
  NoticeScreen and in the tooltip, as for other notices).

## UNVERIFIED
- The Modrinth App's sync behaviour and labels in the running app (lm §2 is source-traced; the user's check is AC4j.5).
- That setting Perflib's value back to 0 removes the wait on Windows 11 (Microsoft's reference is Windows Server 2003's);
  the advice only states what Microsoft documents and never promises a faster launch.
- AC2L.5 (the RC jar, Phase 5); compat040 on this set (WS-E's interpreter).

## Docs (for the docs workstream)
- **CHANGELOG [0.5.0]**: "A hardware or driver notice you've seen stays for the session after a re-scan; its own
  Re-scan button retires it (AW-1). Notices listed on the notices screen after a dismissal now count as seen (AW-2). A
  hardware probe that fails no longer looks like a new GPU or CPU (Latent 2). AMD's legacy-branch driver strings
  ('Context 22.20.x') no longer read as Adrenalin 22.20 (2D). The first-run privacy flag and the benchmark scene are
  saved through RigTune's settings writer, so a quick quit keeps them (L6). On a Windows PC whose performance counters
  are switched off, Tools says what that setting is and how long Minecraft's crash-report setup took at this launch,
  with Microsoft's pages; RigTune reads the setting and never changes it (2L). RigTune notices when settings it applied
  were changed outside the game since you last played (e.g. by the Modrinth App's game-settings sync) and offers to
  apply its values again (4h)."
- **README known limits**: "Settings changed outside the game: the check compares options.txt at launch with the values
  RigTune saw at the last clean exit, for settings RigTune applied only; after a crash or a kill there is nothing to
  compare. In a Modrinth App instance with game-settings sync on, RigTune's changes are copied to your other synced
  instances; RigTune can't see that switch."
- **README Tools text**: "Launch time: the last launch, the median of the last 10 and, on Windows PCs with performance
  counters switched off, what that means and Microsoft's documentation."
- **DESIGN.md, Change awareness**: "0.5: a committed notice survives a NONE rescan and its own Re-scan retires it;
  NoticeScreen reports each init's listing (AW-2); the probe's 'unknown' placeholders are empty in the fingerprint.
  Settings changed outside the game (4h): OutsideOptions/OutsideChanges, a one-shot comparison of options.txt at start
  with the exit snapshot in awareness.json (optionsAtExit, at most 64 keys, values at most 32 characters), consumed
  once." **DESIGN.md, footprint/launch time**: "0.5 (RW-16a): HardwareProbe's worker reads Windows' Perflib 'Disable
  Performance Counters' and the PerfOS/PerfProc/PerfDisk entries (read-only, JNA Advapi32Util); CrashReportMixin times
  CrashReport.preload() (two clock reads, require = 0); Tools' launch-time section is a scrolling RowList."
- **For WS-W2 (C18)**: add the regression rows to `ToolsScreen.populate` in their own method (the list is
  `ToolsList.row(text, color, action, top)`); the notice detail's 2L line can come from `PerfCounterAdvice.lines(
  HardwareProbe.perfCounters(), PreloadTimer.preloadMs())`.

## AC table

| AC | status | evidence |
|---|---|---|
| AC2W.1 | verified | `AwarenessServiceTest` (unit); `AwarenessGameTest.awarenessFixes`: a rescan keeps the committed notice, its own Re-scan removes it (CI 3 legs, runs 36321500954 → 36326382550; local W13) |
| AC2W.2 | verified | `awarenessFixes` with as many canned notices as fit above the driver notice (first at 854×480, which the game renders at scale 2; after the review at 1280×720@3): dismissing the top one lists the driver notice and awareness.json holds the new driver (3 legs; screenshots); `tickHookAllocBytes` 0 on every leg |
| AC2W.3 | verified | `ChangeDetectorTest.latentUnknownGpuIsNoChange`, `.aFirstRunSeedFromAnUnknownProbeThenARealGpuRaisesNothing` |
| AC2D.1 | verified | R1, R2 UNKNOWN, R4 23.1.1; DriverVersionParserTest, ConditionEvaluatorDriverVersionTest, ChangeDetectorTest pass (CI java job, both nodes) |
| AC2D.2 | verified | `DriverStringsTest` (38 rows, each with a source; every row's family and version hold) |
| AC2R.2 | verified | `StartupNoticesTest.theFlagIsSavedThroughSettingsSaver`; `SettingsWritesTest` (no ClientSettings save outside SettingsSaver in src/client) |
| AC2L.1 | verified | `PerfCountersTest` (fake registry: 1, 0, REG_SZ, absent, not Windows), `NoRegistryWriteTest`; this PC read-only (W13) |
| AC2L.2 | verified for Tools (C18's notice detail is WS-W2's; `PerfCounterAdvice.lines` is ready) | `PerfCounterAdviceTest`; `A11yGameTest.walkToolsStartup` with a seeded probe result (3 legs) and this PC's real result (W13) |
| AC2L.3 | verified | `PerfCounterAdviceTest.whatTheAdviceSays` (no "lodctr"), `.bothMicrosoftPagesAreLinked`; LangCheckTest, WordingTest, PseudoLocaleTest (CI both nodes) |
| AC2L.4 | verified | the detection on the probe's worker (never preLaunch; not read on Linux); `workerCpuMs5s` within 300 on every leg; the timer allocates nothing per tick |
| AC2L.5 | not yet (Phase 5, the RC jar) | W13 is the same check on this branch: the advice with "3.1 s", the Perflib export byte-identical |
| AC4h.1 | verified | `OutsideChangesTest` |
| AC4h.2 | verified | `AwarenessGameTest.settingsChangedOutside` under brand theseus (3 legs; local W13) |
| AC4h.3 | verified (compat040 closes with WS-E's interpreter) | `V050WrittenWsWTest` (round trip), `OutsideChangesClientTest.theStopHandlerDoesOneUpdateAndNothingElse`; compat030 PASS; the released 0.4.0 AwarenessStore keeps the field (manual) |
| AC4h.4 | verified | `OutsideChangesClientTest.theFanOutLineIsOnlyForTheModrinthAppAndOnlyWhenSettingsWereWrittenNow`, `.theApplyStatusLineNeedsAVanillaSettingWrittenNow`, `.thePreviewLineNeedsOptionsTxtWrittenNow`; `settingsChangedOutside` finds it in the Apply status under theseus |
