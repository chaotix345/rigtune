# RigTune v0.4.0: review round 2

Scope: commit `20c3db19` against `main`. Round 1 (`docs/reviews/review-7.md`) found only one issue across six broad dimensions, which was implausibly few for roughly 17,000 new production lines, so this round went narrower and deeper: seven area-focused passes (profiles, Stutter Doctor, apply-pipeline hardening, JVM/awareness/server-aware advice, benchmark/footprint/CI, accessibility/UI-versioning, compat-rules) plus a dedicated security pass, each reading its area's code line by line, tracing real call paths, and working through concrete multi-step scenarios by hand against `docs/v0.4/SPEC.md` (including its Amendments) and the documented residuals in `docs/v0.4/design/*.md`. All read-only (`git diff`/`git show`/`git grep`/`git log` against `20c3db19`, no code executed).

**Result:** 19 findings survived verification: **2 HIGH, 10 MEDIUM, 7 LOW**, all CONFIRMED except one MEDIUM left PLAUSIBLE (JW-2, blocked only by the read-only sandbox lacking a real NVIDIA Vulkan driver-string capture). Three findings are noted as (partially) documented residuals the verifier disagreed with accepting as-is (AH-1, JW-2, UV-3); all three are still reported per the task's instruction to report a residual when the reviewer disagrees with the acceptance.

Counts by final severity:

| Verdict | High | Medium | Low | Total |
|---|---|---|---|---|
| CONFIRMED | 2 | 9 | 7 | 18 |
| PLAUSIBLE | 0 | 1 | 0 | 1 |
| **Total** | **2** | **10** | **7** | **19** |

## Coverage

**profiles** — Full read of `core/profile/{ShareCode,ShareKeys,ProfileNames,ProfileStore,EffectiveSettings,ProfileSwitch,ActiveProfile,BatteryPrompt,ProfileTemplates,ProfileImport,ProfileNotes,ProfileView}.java`, `core/apply/PendingActions.java` merge/repeat logic, `core/store/{StateStore,JsonStateFile}.java`, and a full 592-line trace of `client/profile/ProfileService.java` (switchTo/resolve/powerChanged/batteryNotice/batteryAction/import/export). UI: `ProfilesScreen`, `ProfileImportScreen`, `PreviewScreen` (both constructors, button wiring). Cross-checked `ShareCodeInjectionTest`/`ShareCodeFuzzTest`/`V030CompatTest` and `docs/v0.4/design/ws-p.md`'s residual list. Hand-traced scenarios: A→B→A→B profile switching before restart, hostile share-code import, undo-after-restart, battery-offer edges/debounce, templates on hardware lacking Sodium/DH/Iris, `profiles.json` corrupt/newer/huge.

**stutter** — Full read of `core/stutter/{FrameRing,SpikeDetector,Attributor,StutterAnalyzer,StutterRings,RecordRing,GcClock,GcKind}.java` and `client/stutter/{StutterMonitor,StutterHooks,StutterCapture,GcListener,ThreadSampler,BuildBacklog,DevStutter,StutterService}.java`, the phase-timer mixins, `BenchmarkController`'s stutter-sweep call sites, and a partial read of `StutterScreen.java`. Cross-checked `docs/v0.4/design/ws-s.md`'s residual list (items 1–21) and `docs/research/v0.4/stutter.md`. Hand-traced: monitor toggled on/off repeatedly, world exit/dimension change mid-capture, a GC notification arriving after stop, very high FPS vs. ring capacities, and Sodium absent/different-version fallback.

**apply-hardening** — Full trace of `core/apply/{ApplyExecutor,UnfinishedGroups,HelperLauncher,PendingActions,ModJars}.java`, `core/history/{Journal,JournalChange,PartlyApplied,HistoryModel,UndoPlanner,FolderCheck}.java`, `client/undo/{DisableGuard,GameState,HistoryStartup,Staging}.java`, `core/history/LegacyImport.java`, and `core/modrinth/{VersionPins,DownloadPlanner,StagedProjects}.java`/`client/probe/FabricPins.java`. Verified the vendored `HelperCompat030Test` 0.3.0-downgrade round-trip. Cross-checked `docs/v0.4/SPEC.md` (Compatibility promise, 2d/2e/2n, Amendments incl. the apply-pipeline audit block) and `ws-g1.md`/`ws-g2.md`. Hand-traced: several applies/undos before one restart; helper killed mid-group then an older 0.1.0–0.3.0 helper resumes; a 0.1.0/0.3.0-shaped `pending.json` run by the new helper; Discard/Undo during a half-applied group; batch pin-check with staged jars; a locked/sharing-violation file during rename.

**jvm-awareness-server** — Full read of `core/jvm/*`, `client/probe/JvmProbe.java`, `client/jvm/JvmService.java`, JVM/launcher-advice UI and rendering, `core/report/ShareReport.java` (`java()`), `core/rules/ConditionEvaluator.java` (jvm-/driverVersion conditions), `core/awareness/{Fingerprint,ChangeDetector,AwarenessStore,WhatsNew}.java` and their client-side consumers, `core/hardware/DriverVersionParser.java`/`core/model/DriverVersion.java`, `client/mixin/ClientPacketListenerMixin.java`+accessor, `client/server/ServerLimitsTracker.java`, `core/server/ServerLimitsStore.java`, `core/recommend/ServerCap.java`, notice priority ordering, and the wholly-new `core/store/{JsonStateFile,StateStore}.java`. Used `javap`-substitute (`unzip -p ... | grep -a`) against both 26.2/26.3 client jars to confirm `Connection.tick()` runs disconnect handling on the render thread. Cross-checked SPEC items 6/8/9/13, Amendments (J-M1/J-M2/W-H1/W-L1-3/X-M1/X-M3), and `ws-w.md`/`ws-k.md`.

**bench-footprint-ci** — Full read of `core/benchmark/{BenchmarkHistory,BenchmarkRecord,BenchmarkTrend,ChangeWindow,BenchmarkMath,BenchmarkRecords,TrendText}.java`, `client/benchmark/{TrendService,BenchmarkStore,BenchmarkConditions}.java`, `client/footprint/StartupTimes.java`, `core/footprint/{StartupTimesStore,FootprintBudgets}.java`, `client/ui/{BenchmarkResultScreen,BenchmarkHistoryScreen}.java`, `client/notice/{RegressionNoticeSource,BenchmarkStaleNoticeSource}.java`, `core/awareness/AwarenessStore.java` regression-ack logic. Full 673-line read of `FootprintGameTest.java` and `FrameHookBudgetTest.java`. Reviewed `.github/workflows/{build.yml,snapshot-canary.yml}`, `build.gradle`'s `RulesFixtureServer`, `tools/e2e/compat030.py`, and confirmed the WS0-M1 Stonecutter version-gate fix (`>=26.4-alpha`) is actually applied. Cross-checked `ws-b.md`/`ws-f.md`/`ws-f2.md` and SPEC items 7/10/13 + Amendments.

**ui-versions** — Full read of `client/ui/{RowFocus,RowList,RigTuneScreen,ToolsScreen,NoticeScreen,StutterScreen,JvmScreen,ProfilesScreen,ProfileImportScreen,BenchmarkHistoryScreen,Palette,TrendChart,BenchmarkTrendLines}.java` at `20c3db19` (re-pinned mid-session after HEAD moved). Diffed `RigTuneClient.java`/`HistoryScreen.java`/`UndoScreen.java`/`BenchmarkResultScreen.java` for the a11y/2p/Palette retrofits specifically. `git grep`'d for raw button/key-code/scroll handling (found none beyond already-fixed `MOUSE_BUTTON_LEFT` sites) and for hard-coded strings/symbolic notice-button labels. Cross-checked `docs/v0.4/design/ws-x.md` (self-review M1-M3/L1-L6, "Not covered"/UNVERIFIED) and `docs/research/v0.4/bench-history-a11y.md`. No JDK/`javap` was reachable in the sandbox; fell back to a constant-pool string scan of both MC client jars for method-name presence (26.2 vs 26.3), which found no divergence but is materially weaker than `javap`.

**compat-rules** — Full diff of `rules/source/knowledge.json`, `rules/rules-v1.json`/`rules-v2.json` (revision 13→15), `tools/update_rules.py` (all condition-key sets and `*_problems()` validators), and `tools/check_rules_v1.py`/`tools/e2e/compat030.py`. Byte-identical comparison of `core/rules/{RulesDocument,ConditionEvaluator,Condition,ConditionAdapterFactory}.java` against real v0.2.0/v0.3.0 tagged sources and the pinned v0.3.0 test copies. Confirmed `Recommender.SUPPORTED_FEATURES` is empty on every real prior version. Full trace of `ApplyExecutor`'s group-rename recovery path across a version downgrade, cross-checked against `docs/v0.4/design/ws-g3.md`. Read `SchemaConsistencyTest`/`LegacyConditionFailClosedTest`/`PinnedCopiesTest` in full.

**security** — Re-verified round-1's S-1 is still present unfixed. Full read of `core/stutter/StutterSummary.java`, `core/report/ShareReport.java` (`field()`/`escape()`), `core/stutter/StutterAdvisor.java`, and the new `client/ui/StutterScreen.java` advice-rendering path. Checked `client/ui/{JvmScreen,BenchmarkHistoryScreen}.java` for other remote-text-to-UI paths (none found beyond Stutter Doctor). Full read of `core/apply/ModJars.java` (sanitizer), `core/modrinth/VersionPins.java`, `client/probe/FabricPins.java`, `core/modrinth/DownloadPlanner.java` (every catch site), `core/apply/SafeFileNames.java` (char-by-char), `core/model/Text.java`. Traced mod-name display paths through `JarInfo`/`HistoryModel`/`StagedChanges`/`LegacyImport`. Verified the network kill-switch (`ClientSettings.networkEnabled`) gates every `HttpClient` construction site. Checked server-packet handling for raw-text injection (none), `.github/workflows/snapshot-canary.yml` permissions/trigger surface, and `build.gradle`'s loopback-only `RulesFixtureServer`.

## Findings table

| id | area | severity | verdict | title | file:line |
|---|---|---|---|---|---|
| AH-1 | apply-hardening | HIGH | CONFIRMED | Discard pending / Undo silently orphans a mod when the helper is killed mid-group | `core/history/PartlyApplied.java:27` |
| CR-1 | compat-rules | HIGH | CONFIRMED | A 0.3.0 downgrade wipes `unfinished-groups.json`, permanently defeating H4 rollback for an in-progress group | `core/apply/ApplyExecutor.java:441` |
| PR-1 | profiles | MEDIUM | CONFIRMED | Import Preview's "Save only" button stays clickable when the import isn't ready and does nothing | `client/ui/PreviewScreen.java:181` |
| ST-1 | stutter | MEDIUM | CONFIRMED | Stopping the last Stutter Doctor capture can block the render thread up to 1s via `ThreadSampler.stop()`'s `join` | `client/stutter/ThreadSampler.java:54-64` |
| ST-2 | stutter | MEDIUM | CONFIRMED | The 4096-slot candidate ring can lose a still-live spike's phase evidence to unrelated candidate churn | `core/stutter/StutterAnalyzer.java:59-63,72` |
| JW-1 | jvm-awareness-server | MEDIUM | CONFIRMED | `JsonStateFile` logs the full absolute path (Windows username) of every new v0.4 state file on error | `core/store/JsonStateFile.java:123` |
| JW-2 | jvm-awareness-server | MEDIUM | PLAUSIBLE | `ChangeDetector`'s driver-version compare has no tolerance for GL vs Vulkan reporting different precision | `core/awareness/ChangeDetector.java:59` |
| BF-1 | bench-footprint-ci | MEDIUM | CONFIRMED | `startup-times.json`'s modSetHash includes RigTune's own mod entry, unlike the benchmark's modSetHash | `client/footprint/StartupTimes.java:62` |
| UV-2 | ui-versions | MEDIUM | CONFIRMED | A notice's own message is never exposed to narration — only its symbolic action buttons are | `client/ui/RigTuneScreen.java:462` |
| UV-3 | ui-versions | MEDIUM | CONFIRMED | BenchmarkHistoryScreen's plain trend/regression text is bundled into the chart's "deferred to v0.5" excuse | `client/ui/BenchmarkHistoryScreen.java:145` |
| SE-1 | security | MEDIUM | CONFIRMED | Stutter Doctor's Copy-summary clipboard text still embeds remote rules-feed advice titles unescaped (review-7 S-1, unfixed) | `core/stutter/StutterSummary.java:80` |
| SE-2 | security | MEDIUM | CONFIRMED | Stutter Doctor's on-screen advice list renders the same remote rule title/text raw via `Component.literal` | `client/ui/StutterScreen.java:307` |
| PR-2 | profiles | LOW | CONFIRMED | `ProfileService.batteryNotice()` can race `PowerWatcher`'s thread and silently drop a fresh battery offer | `client/profile/ProfileService.java:276` |
| ST-3 | stutter | LOW | CONFIRMED | Phase-timer EWMA baselines stay frozen across a load/dimension-change exclusion, skewing post-load attribution | `core/stutter/FrameRing.java:68-71` |
| UV-1 | ui-versions | LOW | CONFIRMED | Keyboard-focus outline frame is drawn 1px short at the bottom | `client/ui/RowFocus.java:90` |
| UV-4 | ui-versions | LOW | CONFIRMED | ToolsScreen's startup-time line and mod-set-changed note are drawn as plain text, never narrated | `client/ui/ToolsScreen.java:126` |
| CR-2 | compat-rules | LOW | CONFIRMED | `restrictive_problems()` skips the legacy-compat check whenever a settings rule sets `value` alongside `min`/`max` | `tools/update_rules.py:366` |
| SE-3 | security | LOW | CONFIRMED | `SafeFileNames` doesn't reject/escape Unicode bidi/format-control characters in a Modrinth-supplied file name | `core/apply/SafeFileNames.java:87` |
| SE-4 | security | LOW | CONFIRMED | `DownloadPlanner` logs a raw, network-supplied file name and mod id with no control-character stripping | `core/modrinth/DownloadPlanner.java:372` |

## Details

### AH-1 (CONFIRMED, HIGH, documented residual — disagree with acceptance): Discard pending / Undo silently orphans a mod when the helper is killed mid-group

**Scenario:** Player stages an update-shaped group (disable `old-mod.jar`, enable `new-mod.jar`, same group). On exit the post-exit helper renames `old-mod.jar`→`old-mod.jar.disabled` and is killed (Task Manager, forced reboot, crash, AV) before renaming `new-mod.jar.rigtune-pending` into place. Because the kill happens inside `ApplyExecutor.execute()`, `run()` never reaches `writeRemaining()`, so `pending.json`'s ops still show `attempts()==0` even though the disable genuinely already happened on disk. The player relaunches, sees the change still listed as pending, and presses "Discard pending" (or Undo). `Staging.discard()`→`halfDoneGroups()`→`PartlyApplied.groups()` requires a `DISABLE_FILE` op's `attempts()>0` to recognise the group as half-applied (`UndoPlanner.planStaged` has the identical gate for Undo); with `attempts()==0` the group is invisible, so the ordinary `PendingActions.discard()` path runs instead — it retires the still-present `new-mod.jar.rigtune-pending` download to `.rigtune-superseded` (permanently inert) and deletes `pending.json`, while `old-mod.jar.disabled` is never touched. Net effect: the mod is now completely absent from the mods folder, and the player sees only a generic "Discarded N pending change(s)" message.

**Evidence:** `core/history/PartlyApplied.java:27` gates the check on `op.attempts() > 0`. `client/undo/Staging.java:261-296` (`discard()`→`halfDoneGroups()`→`PartlyApplied.groups(...)` at :295) is the only guard before the ordinary retirement path runs; `core/history/UndoPlanner.java` (`planStaged`, ~line 434) has the same gate. `core/apply/ApplyExecutor.java`: `attempts` is only incremented inside `writeRemaining()` (`op.withAttempts(...)`, line 254), reached only after `execute()` (line 271) returns — a kill during `execute()` leaves `pending.json`/attempts untouched while the completed rename stays on disk. `UnfinishedGroups` — which *does* record the rename before it happens, independent of attempts — is never consulted by `Staging` or `UndoPlanner` (`grep` confirms zero references) and is in fact package-private (`final class UnfinishedGroups`, `core/apply/UnfinishedGroups.java:22`), structurally inaccessible from `client/undo`/`core/history` at this commit.

**Verifier reason:** Traced full path at `20c3db19`; confirmed as above. Critically, the `SKIPPED_ALREADY_DONE` result this scenario produces has `resultPath=null`, so `leftHalfApplied()` (line 220-222, requires FAILED+resultPath) never flags the group as half-applied either — meaning `giveUpOnRepeatFailures` is *not* blocked from abandoning the enable after `MAX_FAILED_RUNS` retries even without the player ever pressing Discard/Undo, permanently retiring its download while `old.jar` stays renamed forever. `docs/v0.4/design/ws-g2.md`'s M2 section documents a related residual ("a helper killed mid-group leaves attempts at 0, so PartlyApplied doesn't see it... using the record itself is left to a follow-up"), and SPEC 2o lists M2 as MAY-defer, but that wording implies only "can't roll back," not that the group is silently abandoned and its download permanently retired with no corrective UI feedback — a materially worse, undisclosed consequence. Reported despite the partial residual because the acceptance doesn't cover the actual (worse) outcome. Real file-loss/missing-mod outcome from the player's perspective (can prevent Fabric from starting if the mod is a hard dependency) → HIGH.

**Related:** Shares its root cause (loss of rollback tracking when `UnfinishedGroups`' record can't be relied on) with CR-1 below, but is reachable via a different path (the `Staging`/`UndoPlanner` UI layer, no version change needed) than CR-1 (a downgrade-then-upgrade sequence hitting `ApplyExecutor`'s own internal retry/rollback loop across relaunches).

**Proposed fix:** Have `PartlyApplied.groups()` (or `Staging.discard()`/`UndoPlanner.planStaged` directly) also consult `UnfinishedGroups`' own record, or at minimum detect the disable-done/enable-pending mismatch from file existence the way `ApplyExecutor` itself does when resuming, with a narrower guard than `attempts>0` to avoid false positives from a stale `.disabled` file.

---

### CR-1 (CONFIRMED, HIGH): A 0.3.0 downgrade wipes `unfinished-groups.json`, permanently defeating H4 rollback for an in-progress group

**Scenario:** (1) v0.4 stages a mod-update group; the helper's pass 1 disables `old.jar`→`old.jar.disabled`, records the rename in `unfinished-groups.json`, then the enable fails or the helper is killed. (2) Instead of relaunching v0.4, the player downgrades to 0.3.0 and launches; 0.3.0's `HelperLauncher` cleanup deletes every non-classpath file in `helper/`, including `unfinished-groups.json` (`UnfinishedGroups.java:16-21`: "0.1.0-0.3.0 never read it, and their HelperLauncher deletes it"; `ws-g3.md:123-125` calls this "harmless"). Say 0.3.0's own retry of the enable also fails for the same reason (e.g. a lock) — both ops stay pending, matching 0.3.0's pre-existing accepted non-atomic behaviour. (3) The player upgrades back to v0.4 and relaunches. `UnfinishedGroups.load()` now returns empty, so `earlierRenames()`/`inEffect()` (`ApplyExecutor.java:503-534`) yield no Undo for the already-renamed `old.jar`, even though it's still physically renamed on disk. (4) `tryOnce()`→`disable()` (line 690-698) returns `SKIPPED_ALREADY_DONE` with `undo=null` (only the `earlier.get(i)!=null` branch produces a trackable Undo), so this rename never enters `undos`; the enable is retried and fails again. (5) `rollBack(ops, undos, ...)` runs with nothing recorded for the disable, so nothing is put back — and because `renames()` never re-captures an untracked already-done rename, the loss is permanent: every later run repeats the same untracked pattern.

**Evidence:** `UnfinishedGroups.java:16-21` (0.3.0's `HelperLauncher` deletes the file); `docs/v0.4/design/ws-g3.md:123-125` ("harmless: their helper then re-runs the group as before" — reasons only about 0.3.0's own run, not a later v0.4 run losing rollback tracking); `ApplyExecutor.java:441-444` vs `:690-698` (an earlier-run rename is only trackable for rollback via the `earlier.get(i)!=null` branch; the "already done" fallback returns `new Applied(result, null)` with no Undo); `ApplyExecutor.java:503-534` (`earlierRenames`/`inEffect` depend entirely on the loaded `unfinished.all()` record); `ApplyExecutor.java:538-554` (`renames()` never re-records a rename this pass classified as "already done" via the fallback).

**Verifier reason:** Traced fully at `20c3db19`; every cited line matches. `ws-g3.md:130` ("Known limits") documents a closely related residual ("a group left half-applied by an older helper... has no record... can't roll back the older run's disable"), but that wording implies only an inability to roll back, not that the enable's download gets permanently, silently retired via `giveUpOnRepeatFailures` while the disable stays orphaned forever with no corrective UI — a materially worse, undisclosed consequence that defeats the core H4/WS-G3 guarantee. Reported as not fully covered by the existing acceptance. HIGH: can leave a (possibly hard-dependency) mod permanently missing, requiring manual file repair — matches `ws-g3.md`'s own description of the Fabric-refuses-to-start failure mode it exists to avoid.

**Related:** See AH-1 — same underlying rollback-tracking gap, different trigger (downgrade round-trip vs. immediate Discard/Undo) and different code path (`ApplyExecutor`'s own multi-run retry loop vs. the `Staging`/`UndoPlanner` UI layer).

**Proposed fix:** Have `earlierRenames()`/`inEffect()` (or the `SKIPPED_ALREADY_DONE` fallback in `tryOnce()`) synthesize a trackable Undo whenever on-disk state shows the op's target already exists and its source doesn't, even without a matching `UnfinishedGroups` record, so a later failure in the same pass/run can still roll the rename back and re-establish tracking going forward.

---

### PR-1 (CONFIRMED, MEDIUM): Import Preview's "Save only" button stays clickable when the import isn't ready, and does nothing

**Scenario:** Player opens Profiles→Import code while RigTune is still scanning the PC (`controller.rules()`/`hardwareProfile()` not yet populated). They paste a well-formed RT1- code and click Import. `ProfileImportScreen.importCode()` pre-validates only the code's shape (`ShareCode.decode()` succeeds) and opens `PreviewScreen` with a loader calling `controller.importProfileCode(submitted)`, which hits the `rules == null || hardware == null` branch and returns `ProfileImport.failed(...)`. The loader turns this into `ApplyPreview.EMPTY.withNotes(List.of(imported.error()))` — non-null, non-throwing — so `PreviewScreen`'s `failed` flag stays false. In `confirmButtons()`, `applyButton.active` correctly becomes false (`!preview.isEmpty()`), but `saveOnlyButton.active` only checks `preview != null && !loading && !failed`, never `preview.isEmpty()`/`ok()` — so it renders active. Clicking it calls `saveImportedProfile()`, which does check `!imported.ok()` and saves nothing, returning a generic "unavailable" status — but only after presenting a button that looked usable, with no toast (toasts only fire when `applied==true`).

**Evidence:** `PreviewScreen.java:174-181` (`applyButton.active = ... && !preview.isEmpty();` vs `saveOnlyButton.active = preview != null && !loading && !failed;` — asymmetric); `ProfileImportScreen.java:108-114` (loader swallows the failure into a non-null preview) and `:116-131` (toast only on `applied==true`); `ProfileService.java:166-170` (`rules == null || hardware == null` → `ProfileImport.failed(...)`) and `:197-202` (`saveImportedProfile`'s own `!imported.ok()` guard, proving button state and real outcome disagree); `core/preview/ApplyPreview.java:81-83` (`isEmpty()` ignores notes). `docs/v0.4/design/ws-p.md` self-review item 5 documents only that "a not-ready error shows as a Preview note" — not the Save-only button's active state.

**Verifier reason:** End-to-end trace confirms the mismatch; `ProfilesScreen.java:202-203` opens `ProfileImportScreen` with no readiness guard, so the scenario is reachable whenever import is attempted before the startup scan finishes. The error text does also appear as a grey Notes row (`PreviewScreen.java:263-266`), softening but not eliminating the bug. Not a covered residual.

**Proposed fix:** Gate `saveOnlyButton.active` (and, for clarity, `applyButton.active`) on the same not-ready signal — e.g. have the loader's failure path flip an explicit `failed`-equivalent flag, or carry an explicit ok/not-ready bit from the `AtomicReference<ProfileImport>` result so both buttons disable together.

---

### ST-1 (CONFIRMED, MEDIUM): Stopping the last Stutter Doctor capture can block the render thread up to 1s via `ThreadSampler.stop()`'s `join`

**Scenario:** A player leaves a world while the stutter monitor is on, or toggles it off mid-session. `StutterHooks.tick()` (on `END_CLIENT_TICK`, the render thread) → `StutterService.tick()` → `end()` → `StutterCapture.stop()`, which, since this is the last active capture, calls `SAMPLER.stop()` synchronously and inline on the render thread. `ThreadSampler.stop()` sets `thread = null`, then calls `t.interrupt(); t.join(1000);` directly on the calling thread. If the low-priority (`NORM_PRIORITY-1`) daemon sampler thread isn't scheduled promptly to notice the interrupt — most likely exactly when the system is under the CPU contention Stutter Doctor exists to diagnose — the render thread blocks for up to the full 1000ms: a real, player-visible freeze at the moment monitoring is turned off, the very kind of hitch the tool is meant to detect, not cause.

**Evidence:** `ThreadSampler.java:54-64` (`synchronized void stop() { ... t.interrupt(); try { t.join(1000); } ... }`); `StutterCapture.java:37-45` (`stop()` calls `SAMPLER.stop()` inline, no executor hop); `StutterService.java:151-160,186-187` (`end()` called from the documented render-thread tick path) — no dispatch to `Probes.EXECUTOR` anywhere in the chain.

**Verifier reason:** Full chain confirmed; `StutterMonitor.stop()` returns "last" (true) whenever no session/benchmark is active, the normal case for leaving a world or toggling off. The sampler's `Thread.sleep(PERIOD_MS)` loop usually catches the interrupt fast, but nothing bounds the theoretical worst case, and the same synchronous-join pattern recurs at `shutdown()`/`benchmarkFinished()`. Not mentioned in `ws-s.md`. Real, unmitigated player-visible-hitch risk, not a crash/corruption → MEDIUM.

**Proposed fix:** Drop the `t.join(1000)` on the calling thread (the daemon self-terminates via its own `thread == Thread.currentThread()`/`rings == null` checks) or move the join onto `Probes.EXECUTOR` fire-and-forget.

---

### ST-2 (CONFIRMED, MEDIUM): The 4096-slot candidate ring can lose a still-live spike's phase evidence to unrelated candidate churn

**Scenario:** On a high-FPS machine with a jittery collector, baseline frame time is only a few ms, so `FrameRing`'s candidate filter (≥20ms AND ≥1.5×baseline) fires on almost every minor GC pause — far more often than `SpikeDetector.isSpike`'s actual threshold requires. Over a session with a sustained candidate rate above roughly 1-in-32 frames, the 4096-slot candidate ring (`SESSION_CANDIDATES`, a 32:1 ratio vs. the 131072-frame `SESSION_FRAMES`) wraps and evicts older candidate records while the corresponding frames are still present in the much larger frame ring. `StutterAnalyzer.analyze()` still finds those frames as genuine spikes straight from the live frame-end array, but the `phases` map is built only from surviving candidate records, so `phases.get(s.end())` returns null for a spike whose phase evidence was captured but evicted. `Attributor.attribute()` then treats it as `measured=false` and discards the real, measured cause — the spike is reported as unattributed even though phase timing was on and the data existed at capture time.

**Evidence:** `FrameRing.java:13-14,66-98` (32:1 capacity ratio; candidate-write gate essentially matches the spike-detection floor at low baselines); `StutterAnalyzer.java:52-53,57-63,65,72` (spikes detected independent of candidate survival; `phases` built only from surviving records; silent null on eviction); `Attributor.java:159` (`measured = ctx.phaseTiming() && phases != null`). `FrameRingAllocationTest.candidatesWrapAndKeepTheirPhases` only asserts `FrameRing`'s own snapshot shape, never the cross-ring case; no `AttributorTest`/`StutterAnalyzerTest` covers this interaction.

**Verifier reason:** Mechanism verified exactly as described; triggering needs a sustained candidate rate above ~1-in-32 frames, plausible over a real GC/chunk-load-heavy session. Directly skews the player-visible causes/worst-spikes breakdown in `StutterScreen` → MEDIUM ("wrong behaviour a player would notice").

**Proposed fix:** Size the candidate ring so it can't be outpaced by the frame ring's worst-case candidate rate, or have `StutterAnalyzer` fall back to reconstructing phase evidence from the frame ring's raw per-frame data for any `detect()`-found spike rather than relying solely on the independently-sized candidate map.

---

### JW-1 (CONFIRMED, MEDIUM): `JsonStateFile` logs the full absolute path (Windows username) of every new v0.4 state file on error

**Scenario:** `AwarenessStore`/`ServerLimitsStore` are explicitly designed to store nothing "in readable form" (SPEC 8/9, W-L1: server addresses are HMAC-SHA256'd for exactly this reason). Both are built on the wholly-new `JsonStateFile`/`StateStore`. Ordinary, player-reachable conditions — a corrupt file, downgrading while a newer-formatVersion file is present (a scenario SPEC explicitly promises to support), a write failure, an oversized/unparseable file — log the file's full absolute `Path` (e.g. `C:\Users\<WindowsUsername>\AppData\Roaming\.minecraft\config\rigtune\awareness.json`) via SLF4J's `{}` placeholder. Players routinely paste `latest.log`/`debug.log` into GitHub issues or Discord, publishing their Windows account name.

**Evidence:** `JsonStateFile.java:123,130,135,164,175,179,189,240,243` all pass the constructor's `Path file` as an SLF4J arg. Contrast the same PR's own `AwarenessService.java`/`ServerLimitsTracker.java` call sites, which deliberately log only the bare `FILE_NAME` constant. `JvmSnapshot.java`'s own doc comment ("the arguments can hold paths with the Windows user name, so toString leaves them out") shows the discipline was already intended for this feature; `JsonStateFile` just doesn't follow it.

**Verifier reason:** Verified verbatim; all four trigger scenarios are real and reachable in ordinary play, and the contrasting one-layer-up call sites confirm the intended discipline. Downgraded from HIGH to MEDIUM: this exact pattern predates the PR elsewhere (`HistoryStartup.java` on `main` already logs full paths), so it isn't a novel regression, and a Windows account name is comparatively low-sensitivity PII already ambient in many other logs — the SPEC's "not stored in readable form" promise targets file *content* (correctly hashed here), not log paths. No design doc treats this as an accepted residual.

**Proposed fix:** Log `file.getFileName()` (or a name constant, as the calling feature code already does) instead of the full `Path` in every `JsonStateFile` log statement.

---

### JW-2 (PLAUSIBLE, MEDIUM, documented residual — disagree with acceptance): `ChangeDetector`'s driver-version compare has no tolerance for GL vs Vulkan reporting different precision

**Scenario:** SPEC 9 and `ws-w.md` state as a hard design goal that a GL↔Vulkan backend switch alone is a setting, not a hardware change (no notice), including for the driver-version comparison. `ChangeDetector.driver()` parses both sides independently and compares via `DriverVersion.compare()`, which zero-pads the shorter array. If the real, unchanged driver is reported with a different number of dotted components on the two backends for the same vendor/family (e.g. NVIDIA's Vulkan `driverInfo` carrying an extra build segment GL's embedded string doesn't, or vice versa), `compare()` treats the missing trailing component as 0 against the other side's non-zero value and reports a spurious `Kind.DRIVER` change purely from toggling the render backend — a false "your driver changed" notice with the "new" value being the same driver in a different string format.

**Evidence:** `ChangeDetector.java:59-69` (family-gated `compare()` call); `DriverVersion.java:71-79` (`compare()` zero-pads instead of ignoring a missing component); `DriverVersionParserTest.java:43-44,54` confirms the codebase's own fixtures already produce both 2-part and 3-part NVIDIA GEFORCE parses for real-looking strings, demonstrating the precision-mismatch precondition is real, not hypothetical. `ws-w.md`'s "UNVERIFIED/open" section: "Exact Vulkan driver strings for NVIDIA/AMD/Intel hardware (only lavapipe captured in CI): Phase 5 (AC9.8)."

**Verifier reason:** The algorithmic gap (zero-pad instead of common-prefix/normalize) is real and verified in code; `ChangeDetectorTest.backendOnlySwitchIsNothing` only exercises the equal-precision case and doesn't refute the differing-precision case. Cannot be raised to CONFIRMED without a real NVIDIA Vulkan `driverInfo` capture (unavailable read-only, explicitly deferred to Phase 5 in-repo), but the documented residual frames this purely as a string-capture task, not an acknowledgement of the `compare()` robustness gap — so the acceptance doesn't cover this specific risk. Reported as PLAUSIBLE MEDIUM.

**Proposed fix:** Compare only over `Math.min(a.length, b.length)` components (common-prefix comparison) before falling back to full zero-padded comparison, so a driver whose Vulkan and GL strings agree on every shared component can never register a spurious change from precision differences alone.

---

### BF-1 (CONFIRMED, MEDIUM): `startup-times.json`'s modSetHash includes RigTune's own mod entry, unlike the benchmark's modSetHash

**Scenario:** A player updates RigTune (self-update or manual jar swap) and changes nothing else. `StartupTimes.record()` builds its `mods` map from every non-builtin loaded mod with no exclusion for RigTune's own id, then hashes it. Because RigTune's own version string is part of the hashed input, the hash necessarily differs from the previous launch even though every other mod is unchanged. `StartupTimesStore.summarize()` sets `modSetChanged=true` on any hash difference, and `ToolsScreen` surfaces "the mod set changed since the previous launch (may be related)" — wrong, and it fires for essentially every RigTune update, even though `rigtuneVersion` is already tracked separately per run. This is inconsistent with the benchmark's own `modSetHash` (`BenchmarkConditions.java`), which explicitly excludes RigTune's id with the comment "its own update is named as a RigTune version change."

**Evidence:** `StartupTimes.java:62-72` (no `RigTune.MOD_ID` exclusion) vs. `BenchmarkConditions.java:27-40` (explicit exclusion + comment); `docs/v0.4/design/ws-f.md` ("every non-builtin loaded mod, nested ones included," no exclusion) vs. `ws-b.md` ("minus RigTune itself").

**Verifier reason:** `StartupTimesStore.summarize()` (lines 76-80) and `ToolsScreen.startupDetail()` (lines 83-89) confirmed to gate the message directly on the flag. Design docs confirm this is an unreconciled asymmetry, not an accepted trade-off — `ws-f.md`'s Deviations/self-review never mentions it, and SPEC's Amendments (WS-B/B-H1) address only the benchmark's role, not item 13's startup path. Matches MEDIUM ("wrong behaviour/advice a player would notice").

**Proposed fix:** Exclude RigTune's own mod id from the `mods` map in `StartupTimes.record()`, the same way `BenchmarkConditions.modSetHash()` does, leaving the separate `rigtuneVersion` field as the sole signal of a RigTune update.

---

### UV-2 (CONFIRMED, MEDIUM): A notice's own message is never exposed to narration — a screen-reader user hears only symbolic action buttons

**Scenario:** A Narrator user opens `RigTuneScreen` while a notice is showing (benchmark-stale, server-limit, etc.). `RigTuneScreen.extractNotice()` draws the notice's message with a plain `graphics.text(...)` call, never through any widget, so it never appears in any narration. The only narratable elements next to it are action buttons whose labels are frequently just symbols (`rigtune.notice.dismiss`='×', `rigtune.notice.open`='…'). The player hears a symbol-labelled button but is never told what the notice actually says. The identical gap exists on `NoticeScreen`.

**Evidence:** `RigTuneScreen.java:462-476` (`extractNotice()`, plain `graphics.text`) and `:412-443` (`noticeButton()`/`inlineNoticeButtons()` build only action-labelled Buttons); `NoticeScreen.java:120-132` (same pattern); `en_us.json:102-108` (symbolic labels). `ws-x.md`'s Tab-walk list and "Not covered" section never mention Notices — not a written-off residual.

**Verifier reason:** Confirmed exactly; vanilla `AbstractWidget` tooltip narration doesn't help since tooltips here never contain the notice's own message. SPEC 11's literal text is scoped to "custom list rows," which arguably excludes a standalone notice line, but the underlying accessibility gap for a genuinely new v0.4 UI element is real → MEDIUM.

**Proposed fix:** Give the notice line (and each `NoticeScreen` row) a `RowFocus`-style narratable child whose message is the notice's own text, the same pattern already used for every other custom row in this codebase.

---

### UV-3 (CONFIRMED, MEDIUM, documented residual — disagree with acceptance): BenchmarkHistoryScreen's plain trend/regression text is bundled into the chart's "deferred to v0.5" excuse

**Scenario:** A screen-reader user opens Benchmark history specifically to read a regression alert (SPEC item 7, a headline v0.4 feature). Every line — comparable-runs note, regression/assessment text, last-benchmark line, "needs a rerun" marker — is a plain `Component` drawn via `graphics.centeredText`, not a widget, so Tab never reaches it and the Narrator says nothing. `ws-x.md`'s "Not covered" section bundles this with "the painted tables and charts... they aren't widgets... v0.5," but unlike the actual chart (genuinely pixel-painted, hard to widgetise), these text rows are the same shape of content `StutterScreen`'s `TextRow`/`BarRow` and `JvmScreen`'s `Row` already turned into fully accessible, `RowFocus`-backed rows in this same commit.

**Evidence:** `BenchmarkHistoryScreen.java:140-157` (plain `graphics.centeredText`, no widget) and `:42-43` (`record Row(FormattedCharSequence, int)`, not a `ContainerObjectSelectionList.Entry`, unlike `StutterScreen.Row`/`JvmList.Row` in the same commit); `ws-x.md` line 124 (bundled v0.5 deferral) and line 79 (A11yGameTest's covered-screen list omits this screen entirely).

**Verifier reason:** Confirmed exactly. `documentedResidual=true` is correct per the finding's own framing, but disagreeing with the acceptance is reasonable given the double standard vs. `StutterScreen` in the same commit — there's no architectural reason these already-wrapped text rows couldn't use the identical pattern. → MEDIUM.

**Proposed fix:** Split the concern: keep the chart's own v0.5 deferral, but give the `rows` list the same `RowList`/`RowFocus` treatment already used for `StutterScreen.TextRow`, so the regression note and trend assessment are narratable now.

---

### SE-1 (CONFIRMED, MEDIUM): Stutter Doctor's Copy-summary clipboard text still embeds remote rules-feed advice titles unescaped (review-7's S-1, unfixed)

**Scenario:** Commit `20c3db19` is docs-only (adds `docs/reviews/review-7.md`); no fix was applied. A `stutterAdvice` rule's title in `rules-v2.json` (fetched from the default GitHub raw-content mirror, or any host via `-Drigtune.rules.baseUrl`) set to e.g. `@everyone` or a Markdown link/spoiler still reaches `StutterSummary.text()`'s "Advice: " line with no escaping (only a 2000-char truncation). Any player who presses "Copy summary" and pastes into Discord (the class's own documented use case) pastes live Discord markup/mentions verbatim.

**Evidence:** `StutterSummary.java:80`, byte-identical to review-7's cited evidence. `ShareReport.field()`/`escape()` remain the only sanitizer of this kind and are still not called from `StutterSummary`. `StutterAdvisor.java:72` still sources `Fired.title` straight from the unvalidated `RulesDocument.AdviceRule.title` Gson field.

**Verifier reason:** Re-verified line-for-line unchanged from round 1; no fix landed between `9cf84f66` and `20c3db19`. No documented residual covers it.

**Proposed fix:** Route `StutterSummary`'s advice titles through the same escaping `ShareReport.field()`/`escape()` uses (extract to a shared utility), as already proposed in review-7.

---

### SE-2 (CONFIRMED, MEDIUM): Stutter Doctor's on-screen advice list renders the same remote rule title/text raw via `Component.literal`

**Scenario:** Independently of the Copy-summary clipboard path (SE-1), `StutterScreen`'s `advice()` method renders every fired `stutterAdvice` rule's title and text directly in the live game UI, not just on copy. A rules-feed author (or anyone able to point `-Drigtune.rules.baseUrl` at their own host, or a compromise of the default mirror) sets a rule's title/text to Minecraft formatting-code sequences or Unicode bidi/format control characters; every player for whom the rule fires sees the raw, unsanitized text the moment they open Stutter Doctor — no copy/paste needed. Fixing SE-1 alone (sanitizing only inside `StutterSummary.text()`) would leave this path completely unprotected.

**Evidence:** `StutterScreen.java:306-311` (`Component.literal(f.title())`/`Component.literal(f.text())`, zero sanitization); `StutterAdvisor.java:72` confirms both fields are the same unvalidated remote fields SE-1 flags. Contrast the codebase's established pattern for untrusted display text (`ModJars.sanitizeName`), which strips formatting codes and Unicode FORMAT/CONTROL/SURROGATE/etc. before display — `StutterScreen.advice()` does neither.

**Verifier reason:** Confirmed as a genuinely separate code path from SE-1/S-1 (`StutterSummary` is never called here). Checked `ws-s.md`'s full self-review and "UNVERIFIED/Phase 5" list — sanitization of on-screen advice text isn't mentioned. Rated MEDIUM (not low) because it fires automatically for every affected player with no interaction required, unlike the copy/paste-gated SE-1.

**Proposed fix:** Apply the same shared escape/sanitize step proposed for SE-1 to `f.title()`/`f.text()` at both call sites in `StutterScreen.java`.

---

### PR-2 (CONFIRMED, LOW): `ProfileService.batteryNotice()` can race `PowerWatcher`'s thread and silently drop a fresh battery offer

**Scenario:** The "RigTune power" thread calls `powerChanged()`, which sets the volatile `offer` field to a new `Offer` on a confirmed edge. Concurrently, the render thread polls `batteryNotice()` every frame: it reads `offer`, and when its target already equals the active profile, clears it with a bare `offer = null` — no compare-and-swap against the value it read. If `powerChanged()` stores a brand-new offer in the narrow window between the read and the null-write, that fresh offer is clobbered back to null before it's ever shown — e.g. right after an AC→battery→AC flip-back, the "Switch back to X?" offer can vanish with no toast, requiring the player to unplug/replug again or switch manually.

**Evidence:** `ProfileService.java:81` (`private volatile @Nullable Offer offer;` — visibility only, not compound-op atomicity); `:270-286` (bare `offer = null;`, no CAS); `:243-267` (`powerChanged()` on `PowerWatcher`'s dedicated thread, per `PowerWatcher.java:16-19`'s class comment).

**Verifier reason:** Confirmed as an unguarded check-then-act race across two independent threads; the same unconditional-null pattern recurs at lines 293 and 334, no lock anywhere in the file. No design-doc residual addresses it. LOW: the race window is a few bytecode instructions wide, so occurrence is rare, and the only consequence is a missed/re-vanished UI offer, not data loss.

**Proposed fix:** Use an `AtomicReference<Offer>` and clear with `compareAndSet(current, null)` instead of an unconditional assignment.

---

### ST-3 (CONFIRMED, LOW): Phase-timer EWMA baselines stay frozen across a load/dimension-change exclusion, skewing post-load attribution

**Scenario:** A player changes dimension. `StutterMonitor.levelChanged()` marks the next 10s as excluded. During that window, `FrameRing.frame()`'s excluded branch returns immediately without touching `baseline`/`packetsBase`/`ticksBase`/`renderBase` — those EWMA fields freeze at their pre-exclusion (calm) values. The first non-excluded frames after the load are compared against that stale baseline rather than one representative of the just-loaded dimension's real (heavier) chunk-streaming load; since baselines only move 1/32 per frame, several seconds of post-load frames get skewed attribution — e.g. a legitimately-heavy chunk-packet frame over-attributed at HIGH confidence, or a merely-elevated frame flagged as a spike at all, purely because the baseline hasn't caught up.

**Evidence:** `FrameRing.java:68-71` (excluded branch exits before the baseline-update lines at :95-98); `StutterMonitor.java` (`LOADING_NANOS = 10_000_000_000L`, `levelChanged()`); `Attributor.java` consumes `phases.packetsBase()`/`ticksBase()`/`renderBase()` straight from the unrefreshed baseline.

**Verifier reason:** Confirmed; the single-frame `skipNext` force-exclusion is a separate mechanism from the 10s level-change exclusion, exactly as the finding states. Not mentioned in `ws-s.md`. LOW: effect confined to attribution confidence on a handful of post-load frames, not something most players would specifically notice.

**Proposed fix:** On resuming from an excluded span, reset `packetsBase`/`ticksBase`/`renderBase` (and optionally `baseline`) to reseed from the first post-resume frame instead of comparing against pre-exclusion conditions.

---

### UV-1 (CONFIRMED, LOW): Keyboard-focus outline frame is drawn 1px short at the bottom

**Scenario:** A keyboard user Tabs to any custom list row anywhere in the mod. `RowFocus.outline()` draws the focus box: the top edge is `fill(left, top, right, top+1, color)`, but the bottom edge is `fill(left, bottom-1, right, bottom, color)` where `bottom = top + entry.getHeight() - 1`. Since `fill`'s upper bound is exclusive (confirmed by this file's own sibling divider idiom elsewhere), the bottom border paints row `top+height-2`, not the row's true last pixel row — the frame is exactly 1px shorter than the row on every focused row in every accessible list.

**Evidence:** `RowFocus.java:90-98`; compare `RigTuneScreen.java:735`'s identical exclusive-upper-bound 1px-line idiom.

**Verifier reason:** Confirmed by direct calculation. Cosmetic only → LOW.

**Proposed fix:** Draw the bottom border at row `bottom` (not `bottom-1`) and extend the vertical strips' upper bound to `bottom` so all four edges enclose the same height-`entry.getHeight()` rectangle.

---

### UV-4 (CONFIRMED, LOW): ToolsScreen's startup-time line and mod-set-changed note are drawn as plain text, never narrated

**Scenario:** A screen-reader user opens Tools, where the launch-time line and its detail (SPEC item 13) sit below the five hub buttons. `ToolsScreen` has no `RowList`/`RowFocus` at all — the buttons and Done are natively narratable, but `startupLine`/`startupDetail` are drawn directly via `graphics.centeredText` with no widget backing, so Tab never lands on that text and it's never read aloud, even though it can carry an actionable note ("mod set changed, launch time isn't comparable").

**Evidence:** `ToolsScreen.java:124-133` (plain `graphics.centeredText`, no widget) and `:34-70` (plain `Component`/`FormattedCharSequence` fields, never wrapped in a `NarratableEntry`).

**Verifier reason:** Confirmed; `ws-x.md`'s "Not covered" list and SPEC §13 don't mention this gap. LOW: informational text, not core functionality.

**Proposed fix:** Add a minimal non-interactive `RowFocus`-equivalent wrapping the startup text so it participates in Tab order and narration.

---

### CR-2 (CONFIRMED, LOW): `restrictive_problems()` skips the legacy-compat check whenever a settings rule sets `value` alongside `min`/`max`

**Scenario:** A `knowledge.json` settings rule authored with both `value` and `min`/`max` (e.g. "set X, but never go below Y as a floor") and a `when` using a 0.2.0/0.3.0-unknown condition key with no `requires` would silently lose that `when` on 0.2.0/0.3.0. `restrictive_problems()` only checks the `when` for `kind=="settings"` when the rule is a *pure* clamp (`"value" not in rule and (min or max present)`); since `value` is present, the check is skipped entirely, so CI would accept such a rule even though its `when` behaves like a real restrictive clamp's on legacy clients. In practice this creates no live divergence today because `RulesDocument.SettingRule.isClampEntry()` is `!isValueEntry() && (min||max)`, so whenever `value` is set the min/max fields are dead code on every client version uniformly — and no such rule exists in the current `knowledge.json`.

**Evidence:** `tools/update_rules.py:366-367` (the `"value" not in rule` guard); contrast `template_setting_problems()` (~line 336, 346-349), which does enforce `has_value == has_clamp` as an error for `profileTemplates` entries; `core/rules/RulesDocument.java:204-210` (`isValueEntry`/`isClampEntry`, identical in v0.3.0-real and pinned copies); `core/recommend/Recommender.java:175-176,202-203`.

**Verifier reason:** Confirmed exactly; a real, currently-latent validator gap with no live production impact today since no such rule exists yet. Matches the finder's own low/cosmetic framing.

**Proposed fix:** Drop the `"value" not in rule` guard in `restrictive_problems()`, or reject a settings rule combining `value` and `min`/`max` outright, the same way `template_setting_problems()` already does for `profileTemplates`.

---

### SE-3 (CONFIRMED, LOW): `SafeFileNames` doesn't reject/escape Unicode bidi/format-control characters in a Modrinth-supplied file name

**Scenario:** A Modrinth project sets a version file's filename to something like a Right-to-Left-Override character before a reversed extension, or otherwise embeds Unicode Format-category characters. `SafeFileNames.problem()` only rejects ASCII control characters and a fixed Windows-forbidden-character set — it never checks `Character.getType()` for FORMAT/SURROGATE/etc. the way `ModJars.sanitizeName` does for mod display names — so such a name is accepted as "safe." If it then collides with an existing target, the resulting `IOException` message embeds the raw name via `quote()` (which also only escapes control chars), and `DownloadPlanner`'s outer catch wraps any non-`TextException` message as `Text.literal(e.getMessage())` verbatim, showing the untrusted bytes raw in the download-error line of the recommendations UI.

**Evidence:** `SafeFileNames.java:87-125` (no Unicode-category check, contrast `ModJars.java`'s `unsafe(cp)` helper) and `:133-142` (`quote()` only escapes control chars); `DownloadPlanner.java:352,394` (`SafeFileNames.resolveJar(modsDir, file.filename())` on untrusted `ModFile.filename`) and `~:198-201` (non-`TextException` messages passed through as `Text.literal`).

**Verifier reason:** Verified the gap and that the raw name does reach on-screen text via the outer catch/`Text.literal` path, though the exact trigger route is a bit indirect. Core defect (no Unicode-category filtering) and its low, cosmetic/display-only impact are verified. No documented residual covers it.

**Proposed fix:** Have `SafeFileNames.problem()`/`quote()` reject or escape the same Unicode FORMAT/CONTROL/SURROGATE/PRIVATE_USE/UNASSIGNED categories `ModJars.sanitizeName` already rejects.

---

### SE-4 (CONFIRMED, LOW): `DownloadPlanner` logs a raw, network-supplied file name and mod id with no control-character stripping

**Scenario:** A Modrinth project sets its version filename or its jar's `fabric.mod.json` `id` to a string containing embedded newlines or ANSI/terminal escape sequences (not restricted by RigTune's own Gson-based reader for a not-yet-loaded, freshly-downloaded jar). When RigTune skips it as a duplicate, `RigTune.LOGGER.info("Skipping {}: mod {} is already present", file.filename(), jarModId)` writes both raw strings into the game log with no stripping, letting an attacker forge fake-looking log lines or inject escape sequences into a log a player might paste for support.

**Evidence:** `DownloadPlanner.java:372`; contrast `ModJars.java:150-151`'s explicit comment on `readModId` ("Doesn't log: the apply helper runs without a logger on its classpath") showing the codebase already treats this exact field as sensitive elsewhere in the same pipeline, but this logger-equipped call site doesn't follow that discipline.

**Verifier reason:** Confirmed; `jarModId` comes from an uncapped, uncontrol-filtered JSON string with no length/character validation. Real, minor log-injection (CWE-117) gap. The finding's analogy to the `ModJars` comment overstates precedent (that's a technical constraint, not a deliberate anti-injection policy), but doesn't change that this call site logs untrusted content unsanitized. No documented residual covers it.

**Proposed fix:** Strip or replace control characters (`\n`, `\r`, ESC at minimum) from `file.filename()`/`jarModId` before logging, or log only the already-sanitized `ModJars.nameOf()` form.

## REFUTED findings

None — every finding raised this round survived verification as CONFIRMED or PLAUSIBLE.
