# RigTune v0.5.0: verification of the v0.4.0 features audit

Input: `docs/research/v0.5/audit-v040-features.md` (15 findings: 0 high, 2 medium, 13 low; PF-5 and SD-6 "need a test";
2 latent notes). Code checked on `feat/v0.5.0` @ `918927ea`. `git diff 987179e4 918927ea -- src rules tools` is empty, so
every line reference below is also v0.4.0 as released.

Method: every finding was re-traced in the current code and I tried to refute it, looking for a guard elsewhere, a caller
that never passes the value, an existing test, or a doc that makes it intended. The docs checked were DESIGN.md,
v0.4/SPEC.md, the ws-p/ws-s/ws-w/ws-b design docs, README and CHANGELOG. Ten findings plus the latent GPU note were settled
with throwaway JUnit tests in the worktree `C:/Dev/Worktrees/rigtune-audit-verify` (branch `research/v05-audit-verify`, not
committed, not pushed; copies are in the audit-verify scratch dir under `tests/`). Each test asserts the CORRECT
behaviour, so a failure means the finding holds. Run: `./gradlew :26.2:test --tests '*AuditVerify*'` gave **12 tests, 11
failed, all for the predicted reason** (the 12th is a ring-capacity sanity check). Two findings needed evidence outside
the repo:
- PF-5: the real Distant Horizons 3.3.2 jar, disassembled with `javap`.
- SD-6: a JDK 25.0.4 probe program run under Shenandoah, generational Shenandoah, G1 and ZGC.

SD-5's crash claim was checked in MC 26.2's `MouseHandler` bytecode.

**Result: 15 of 15 CONFIRMED, 0 refuted, 0 uncertain. No severity changes (2 medium, 13 low). Both latent notes also
confirmed (still latent).** Three of the audit's proposed fixes need correcting:
- PF-5: the range gate is in 3 places, and key 22 must not be widened.
- AW-2: the proposed per-tick call allocates every tick.
- SD-1: the "mark unmeasured" alternative would silence the rules in every long session. Separately, the audit misread
  A3's GC pause rate, so the GC ring lasts ~35 min, not 15-20. The 17-min sampler half is exact.

One new item (NEW-1, generational Shenandoah) was found while tracing.

## Summary

Paths: `client/…` = `src/client/java/io/github/chaotix345/rigtune/client/…`, `core/…` = `src/main/java/io/github/chaotix345/rigtune/core/…`,
`lang` = `src/main/resources/assets/rigtune/lang/en_us.json`.

| id | audit sev | verdict | final sev | effort | files (fix) | test |
|---|---|---|---|---|---|---|
| PF-1 | MEDIUM | CONFIRMED | MEDIUM | S | `client/profile/ProfileService.java` | ProfilesGameTest.batteryOffer from a fresh profiles.json (game test) |
| SD-1 | MEDIUM | CONFIRMED (2 unit tests fail) | MEDIUM | M | `core/stutter/StutterRings.java`, `core/stutter/StutterAnalyzer.java` (+ `StutterFacts.java` if coverage is exposed) | `AuditVerifyStutterTest.sd1*` → StutterAnalyzerTest |
| SD-2 | LOW | CONFIRMED (unit test fails: 1% low 200 > avg 80) | LOW | S (label) / M (histogram) | `core/stutter/StutterAnalyzer.java` (+ `FrameRing.java` for a fine histogram; `lang` if labelled) | `AuditVerifyStutterTest.sd2*` |
| AW-1 | LOW | CONFIRMED (unit test fails) | LOW | S | `client/awareness/AwarenessService.java` | `AuditVerifyAwarenessTest.aw1*` |
| PF-2 | LOW | CONFIRMED | LOW | S | `client/ui/RigTuneSettingsScreen.java`, `client/profile/ProfileService.java` (+ one delegate in `client/ui/RigTuneController.java`/`client/RealController.java`), `lang` | ProfilesGameTest: snooze, re-enable, offer again |
| SD-3 | LOW | CONFIRMED | LOW | S | `client/stutter/StutterService.java` | StutterGameTest (or a reflective unit test like SD-4's) |
| BH-1 | LOW | CONFIRMED (unit test fails) | LOW | S | `core/benchmark/TrendText.java`, `lang` | `AuditVerifyBenchmarkTest.bh1*` |
| BH-2 | LOW | CONFIRMED (unit test fails) | LOW | S (mod rows) / M (all staged rows) | `core/benchmark/ChangeWindow.java` (M: + `core/benchmark/BenchmarkRecord.java`, `client/benchmark/BenchmarkConditions.java`, `client/benchmark/BenchmarkController.java`) | `AuditVerifyBenchmarkTest.bh2*` → ChangeWindowTest |
| PF-3 | LOW | CONFIRMED | LOW | S | `client/profile/ProfileService.java` | ProfilesGameTest: delete the back-offer's target |
| PF-4 | LOW | CONFIRMED (unit test fails: 140) | LOW | S | `core/profile/ShareCode.java` | `AuditVerifyProfileTest.pf4*` → ShareCodeTest |
| AW-2 | LOW | CONFIRMED | LOW | S | `client/ui/NoticeScreen.java`, `client/awareness/AwarenessService.java` (+ `RigTuneController`/`RealController` for a callback) | AwarenessGameTest at 854x480@3 (game test) |
| SD-4 | LOW | CONFIRMED (unit test fails deterministically) | LOW | S | `client/stutter/StutterService.java` | `AuditVerifyStutterServiceTest.sd4*` |
| SD-5 | LOW | CONFIRMED (unit test NPE; the click crashes the game) | LOW (hand edit only) | S | `core/stutter/StutterReport.java` | `AuditVerifyStutterTest.sd5*` → StutterStoreTest |
| PF-5 | LOW (needs test) | CONFIRMED (DH 3.3.2 max = 4096; unit test fails) | LOW | M (S for a Preview note only) | `core/profile/ShareKeys.java`, `core/profile/ProfileStore.java`, `core/profile/ProfileSwitch.java`, `client/profile/ProfileService.java` | `AuditVerifyProfileTest.pf5*` + ProfileStoreTest/ProfileSwitchTest |
| SD-6 | LOW (needs test) | CONFIRMED (JDK 25 Shenandoah probe) | LOW | S | `client/stutter/GcListener.java` | unit test of `oldGenerationUsed` with a Shenandoah-shaped map |
| Latent 1 | - | CONFIRMED (latent) | latent | S (docs) | `core/profile/ShareKeys.java` comment, `ShareKeysTest` comment | n/a (a rule for future versions) |
| Latent 2 | - | CONFIRMED (latent; unit test fails) | latent | S | `core/awareness/Fingerprint.java` (or `client/probe/HardwareProbe.java`) | `AuditVerifyAwarenessTest.latentUnknownGpuIsNoChange` |
| NEW-1 | - | UNCERTAIN (impact) | LOW | S | `core/stutter/GcKind.java`, `client/stutter/GcListener.java` | a generational-Shenandoah session with -Xlog:gc |

**File ownership (so fixes don't collide):**
- `client/profile/ProfileService.java`: PF-1, PF-2, PF-3, PF-5. Give them to one owner.
- `client/stutter/StutterService.java`: SD-3, SD-4 and leftover **L1** (R10-1, `end()`). One owner.
- `core/stutter/StutterAnalyzer.java`: SD-1, SD-2.
- `client/awareness/AwarenessService.java`: AW-1, AW-2.
- `core/profile/ProfileStore.java`: PF-5 and leftover **L8** (switch-id index).
- `lang` (en_us.json): PF-2, BH-1, maybe SD-2, and leftovers L3/L7. Serialise edits to it.

No finding duplicates a leftover item. The interactions are listed under each finding.

---

## PF-1 (MEDIUM → CONFIRMED, MEDIUM): taking the Battery offer with no active profile means plugging back in offers nothing

**Trace (feat/v0.5.0):**
- The player has never switched profiles, so `ProfileStore.active()` is null (`core/profile/ProfileStore.java:222-225`) and
  `ProfileService.active()` returns null (`client/profile/ProfileService.java:462-474`).
- On unplugging, `BatteryPrompt.onEdge(true, …, active=null, …)` offers BATTERY (`core/profile/BatteryPrompt.java:32-36`).
- The offer's Switch → `batteryAction` → `switchProfile(target, null)` → `switchTo` (`ProfileService.java:297-314,
  329-361`). `ensureBaseline()` runs first (`:331`), but `previous = active()` is taken after it (`:334`) and is still
  null.
- `markActive` → `rememberPrevious(null)` (`:363-370`). `ProfileStore.rememberPrevious` writes JSON null
  (`ProfileStore.java:326-331`).
- On plugging back in, `BatteryPrompt.java:38` returns NONE (`state.previousProfile() == null`). No notice and no toast,
  and Battery stays applied on AC.

**Refutation attempts (none holds):**
- SPEC 4 / DESIGN.md:209 only say "back on AC it offers the previous profile". But `ProfileService.java:330` says "The way
  back: 'My settings' exists before the first switch, even one made from the battery offer". So the baseline was meant to
  be the way back from exactly this path.
- README.md:80 and CHANGELOG.md:18 promise the back-offer without conditions.
- No guard elsewhere fills `previousProfile`. The only other writer is `delete` (`ProfileStore.java:213-216`), which nulls
  it.
- The game test switches to My settings first (`src/gametest/…/ProfilesGameTest.java:372`), so it never covers this path.

**Severity:** MEDIUM kept. This is the feature's default first-use path on a laptop, and it leaves 60 FPS / VSync / short
render distance in place on AC with no prompt. Undo and Profiles still work, so nothing is lost.

**Fix:** in `markActive`, when switching to Battery, `store().rememberPrevious(previous != null ? previous :
baselineId())`, where `baselineId()` is `store().baseline()`'s id, or null when the store isn't writable
(`ensureBaseline` returns early then).

**Test:** ProfilesGameTest.batteryOffer variant from a fresh profiles.json without the `switchProfile(mine)` line:
- `powerChanged(true)`, take the offer, `powerChanged(false)`.
- Expect a `battery-back:` notice whose target is the baseline's id.
- Today `batteryNotice()` is null.

**Compatibility:** `previousProfile` gets an ordinary `p-<uuid>`. profiles.json is new in 0.4, so 0.1.x-0.3.x don't read
it. A downgrade to 0.4.0 reads it and offers My settings. **Overlaps:** none.

## SD-1 (MEDIUM → CONFIRMED, MEDIUM): in long sessions the sampler evidence covers only the newest ~17 min and the GC evidence the newest ~35 min, while shares and counts cover every spike

**Trace:**
- Ring sizes: `core/stutter/StutterRings.java:9-10` (GC 2048, samples 4096) and `client/stutter/ThreadSampler.java:29`
  (250 ms). The sampler therefore holds 1024 s ≈ 17 min.
- The GC ring's reach depends on the GC rate. Stutter verification logged 136 G1 pauses in A2's 150 s and 358 in A3's
  6.5 min, about 0.9/s uncapped at ~2,700 FPS (`docs/v0.4/verification/stutter/table.md:23,27`, `README.md:32`). At that
  rate 2048 records cover roughly 35-38 min, less with G1 phase notifications or a heavier, modded workload.
- **Correction:** the audit's "358 G1 pauses in about 2.5 minutes" misreads A3's length, so its 15-20 min GC figure is
  too short. The sampler half (17 min) is exact.
- Spikes come from the frame ring plus 4096 candidate records, which is the whole session
  (`core/stutter/StutterAnalyzer.java:53-57`).
- Shares divide by all of them (`:93-95` by `lost`, `:104` by `spikes.size()`).
- GC claims need a held `GcEvent` (`core/stutter/Attributor.java:152-165`) and the dh tag needs a held sample
  (`:217-232`).
- full/stall/explicit are recounted from the held records only (`StutterAnalyzer.java:247-270`).

**Tests (fail today):**
- `sd1FullGcSurvivesTheGcRingWrapping`: 1 full GC, then 2048 young GCs over 1200 s gives
  `gcFullPauses=0 report.facts.fullGcs=0` (expected 1).
- `sd1DhTagShareCoversTheWholeSession`: 60 min at 62.5 FPS with 358 spikes, every one during saturated DH work (dh 4
  cores, process 15 of 16), gives **dh tagged share 28.49 %**, `unmeasured=[gc, render]`. `stutter-dh-threads` needs 40
  (`rules/source/knowledge.json:949`), so it can never fire after ~42 min.

**Refutation attempts:**
- ws-s deviation 18 (fail closed on what wasn't measured) covers "never measured", not "measured, then overwritten".
- Research §2.5 planned "GC ring … wraps; summary counters kept" and an 8192-sample sampler
  (`docs/research/v0.4/stutter.md:204,206`). Neither was built, and no residual is listed.

**Correction to the audit:** no shipped rule uses `not {gcFullPausesAtLeast: …}`, so the "reads FALSE instead of
UNKNOWN" point is latent today. The live effects are:
- `stutter-dh-threads` under-fires in any session over ~42 min, and `ram-stutter-gc-heap` in sessions longer than the GC
  ring's reach.
- The Doctor's own "not explained %" and GC share are wrong in long sessions.

**Severity:** MEDIUM kept, because the always-on monitor's typical session is longer than the evidence window.

**Fix:**
1. Whole-capture counters for full/explicit/stall collections (and the live-set samples), kept under the existing lock
   in `StutterRings.gc(...)` (a few longs, allocation-free).
2. Compute the gc claim share over the spikes newer than the oldest held GC record, and the dh/cpuContention tag shares
   over the spikes newer than the oldest held sample, with the matching denominators.

The audit's alternative (put gc/dh/cpuContention in `unmeasured` once a ring wrapped) would make those rules silent in
every long session. Don't use it.

**Compatibility:** the stutter.json shape is unchanged (values become correct). Rules are unchanged. Downgrade is safe.

**Overlap with L2** (a chunks-loading rules condition): the `chunksLoading` tag comes from candidates plus frame phase
words (`StutterAnalyzer.java:182-209`), which cover the whole capture. It is not diluted, so SD-1's
coverage-restricted denominators must not be applied to it. L2's threshold calibration (P5C-F1, short sessions) is
unaffected.

## SD-2 (LOW → CONFIRMED, LOW): "1% low" covers the frame ring's tail, average FPS the whole capture

**Trace:**
- `StutterAnalyzer.java:116` computes `FrameStats.of(gameplayDurations(ends))` over the held frames only.
- `:123-124` computes the average from `f.gameplayFrames() / gameplaySeconds` (the whole capture) and puts the two side
  by side.
- They are shown at `client/ui/StutterScreen.java:194` and `core/stutter/StutterSummary.java:45-46`.
- The frame count shown is also whole-capture, so the header mixes windows.

**Test (fails):** `sd2OnePercentLowIsNeverAboveTheAverage`: 131,072 frames of 20 ms, then 131,072 of 5 ms, gives
`avgFps=80.0 onePercentLowFps=200.0`.

**Fix:** either derive the 1% low from a whole-capture log-spaced histogram (research §2.5's 24 buckets, a new
allocation-free array in `FrameRing`, M), or compute both numbers over the held window and label it (S).

**Compatibility:** the stutter.json field is unchanged. Old saved sessions keep their numbers.

## AW-1 (LOW → CONFIRMED, LOW): a shown hardware/driver notice disappears at the next rescan

**Trace:**
- `shown()` commits the new fingerprint asynchronously (`client/awareness/AwarenessService.java:167-176`).
- Any later `rescan()` calls `afterProbe`, and `ChangeDetector.check` against the committed fingerprint returns NONE, so
  `hardware = null` (`:86-97`, `:93`).
- Rescan triggers: every confirmed power edge (`client/profile/ProfileService.java:274`) and the network toggle
  (`client/ui/RigTuneSettingsScreen.java:130-134` → `client/RealController.java:247-251`).
- **Also** the main screen's Re-scan button (`client/ui/RigTuneScreen.java:174-178`), which the audit didn't list.
- `docs/v0.4/design/ws-w.md:72` says "Once shown, the fingerprint is committed (async); the notice stays for the
  session", and `AwarenessService.java:38-40` says the same.

**Test (fails):** `aw1ShownNoticeSurvivesARescan`:
- seed, then a driver change gives a notice;
- `shown()` commits it (verified in awareness.json);
- `afterProbe(same hardware)` makes `hardwareNotice()` return **null**.

**Fix:** in `afterProbe`, when the result is NONE and the current `Pending` is committed, keep it. A NONE against the
committed fingerprint means the hardware is still `p.now()` within the detector's tolerances, so no fingerprint equality
test is needed (RAM noise would break one). A changed-back or new change still replaces it. Decide whether the notice's
own Re-scan action should retire it (it has done its job; Re-benchmark hasn't).

**Compatibility:** in-memory only. **Overlap:** AW-2 (same file).

## PF-2 (LOW → CONFIRMED, LOW): "Don't offer again" can't be undone in game, and on the back-offer it also stops the unplug offer

**Trace:**
- The back-offer carries the same snooze action (`ProfileService.java:293`), and it sets the global flag (`:303-305`).
- `BatteryPrompt.java:29` gates both edges on it.
- `git grep` finds `snoozeBattery(` only with `true` (`ProfileService.java:304`) and no UI writer of `battery.prompt`
  (`ProfileStore.java:74-78` only defaults it).

**Refutation attempt:** the notice is also dismissible (`ProfileService.java:292-294`, last argument `true`), so a plain
× dismiss for this one offer already exists. The problem is only the global snooze's wording and scope, and that it can't
be reversed.

**Fix:**
- A "Battery offer: On/Off" row (RigTuneSettingsScreen) that calls `snoozeBattery(false)` / `(true)`.
- Optionally drop the snooze action from the back-offer, keeping × for it.

**Compatibility:** uses the existing `battery.snoozed` field. **Overlap:** `lang` with BH-1 and L3/L7.

## SD-3 (LOW → CONFIRMED, LOW): after a session that isn't saved, Stutter Doctor says "No sessions recorded yet"

**Trace:**
- `client/stutter/StutterService.java:196` sets `savedState = Saved.DONE` on every `end()`.
- `saved` only changes when the write is OK and the generation matches (`:208-211`).
- The not-worth-saving branch returns early (`:203-207`), and a cancelled benchmark saves nothing (`:277-279`).
- `loadSaved()` then never reads the file (`:323-326`), and `view()` shows `saved`, which is null (`:102-104`).

**Reachable path:** monitor on, play under 2 min, benchmark in place, Esc to cancel, leave within 2 min:
- both sessions are `aroundBenchmark` and too short (`StutterStore.worthSaving`);
- the fresh launch never loaded the file.

A failed write gives the same result.

**Fix:** drop the unconditional `savedState = Saved.DONE` in `end()` and set it where `saved = a` is assigned (as
`benchmarkFinished` does at `:286-288`). The io chain is ordered, so a later `loadSaved()` reads after the queued save.

**Test:** StutterGameTest (seed a session, run the path, expect `shownView().report() != null`), or a reflective unit
test in the style of SD-4's.

**Overlap:** leftover **L1** (R10-1) edits the same `end()`, and SD-4 edits `loadSaved()` in the same file. Give all
three to one owner.

## BH-1 (LOW → CONFIRMED, LOW): "3 comparable runs" next to "Not enough comparable runs for a trend yet (2 of 3)"

**Trace:**
- `core/benchmark/BenchmarkTrend.java:342,345`: `comparableRuns = group.size()`, which includes the latest run.
- `:242-253`: `baselineRuns` counts the runs before it.
- `core/benchmark/TrendText.java:42-45` and `:229-233` word both as "comparable runs".
- Both lines are on BenchmarkHistoryScreen (`client/ui/BenchmarkHistoryScreen.java:129,132`).
- ws-b deviation 1 (`docs/v0.4/design/ws-b.md:95-96`) documents the exclusion, not the wording.

**Test (fails):** `bh1NoteAndTrendAgree` prints `note='3 comparable runs; 0 with different conditions not shown'
trend='Not enough comparable runs for a trend yet (2 of 3)'`.

**Fix:** word the trend counts as earlier runs, e.g. "Not enough earlier comparable runs for a trend yet (%s of 3)" and
"(from %s earlier runs)".

**Overlap:** L3 makes these lines narratable (screen files). The wording flows through, but both touch `lang`.

## BH-2 (LOW → CONFIRMED, LOW): a change staged before run B is listed as "since then" for B once a restart applies it

**Trace:**
- The window is `(baseline.cursor, latest.cursor]`, and every change that took effect *now* is listed
  (`core/benchmark/ChangeWindow.java:65-71`, `:79-81`).
- The M1 staged-change handling exists only on the baseline side (`carried`, `:57-64`, `:83-103`).
- The cursor is the newest entry at the run's start (`client/benchmark/BenchmarkConditions.java:45-54`,
  `BenchmarkController.java:223`).
- The class comment (`ChangeWindow.java:16-18`) claims a staged change "never ran during either benchmark". For the
  latest run that isn't enforced.

**Test (fails):** `bh2ChangeStagedDuringTheLatestRunIsNotListedForIt` (B's mod set equals the baseline's) prints
`items for B: [e2 ADDED lithium-0.18.jar]`.

**Fix:**
- (S) For mod-file rows, list them for the latest run only when its `modSetHash` differs from the baseline's.
  `modSetHash` is computed once per game session, so it proves what was loaded.
- (M) Record the ids of changes still STAGED at the run's start in the run's context (an optional field), drop them from
  that run's window, and carry them into the next run's (the existing `carried` path).

**Compatibility:** (S) needs no schema change. (M) adds an optional benchmarks.json field: 0.4.0 ignores it (Gson) and
drops it if it rewrites the file, and 0.3.x drops it as it drops `modSetHash` (`BenchmarkRecord.java:51-53`).

## PF-3 (LOW → CONFIRMED, LOW): deleting the offered profile leaves "Switch back to ?"

**Trace:**
- `deleteProfile` (`ProfileService.java:231-236`) → `ProfileStore.delete` nulls only the stored previous profile
  (`ProfileStore.java:213-216`).
- `batteryNotice()` retires only when the target is active (`ProfileService.java:284-288`).
- The name renders as "?" (`:485-486`).
- A click resolves to null, giving `rigtune.profile.status.unavailable` (`:134-135`, `:409-411`). The click does retire
  the offer (`:302`).

Rare: Battery must be active with a back-offer pending.

**Fix:** in `batteryNotice()`, also retire a PREVIOUS offer whose `p-` target no longer resolves
(`store().profile(target) == null`), or retire it in `deleteProfile`.

**Test:** ProfilesGameTest.

## PF-4 (LOW → CONFIRMED, LOW): a fixed 60 FPS cap from a 60 Hz PC arrives as "match the display"

**Trace:**
- `core/profile/ShareCode.java:77-80`.
- `SettingValues.refreshRateCap(60) = 60` (`core/recommend/SettingValues.java:51-58`).
- Recording is 60 on any display of 60 Hz or more (`core/profile/ProfileTemplates.java:170-175`). Battery's 60 cap is hit
  the same way.
- ws-p deviation 3 (`docs/v0.4/design/ws-p.md:68-69`) documents the rule, not this consequence.

**Test (fails):** `pf4SixtyFromASixtyHzSenderStaysSixty` prints `wire=26 imported at 144 Hz=140`.

**Fix (S):** never emit `MATCH_DISPLAY` when the sender's cap is 60 (60-69 Hz displays), where the cap coincides with
the common fixed cap. Alternatively carry `$refreshRateCap` provenance from `ProfileTemplates` (M).
`ShareCodeTest.matchTheDisplayResolvesPerImportingDisplay` (180 Hz sender) is unaffected.

**Compatibility:** wire 5 (= 60) is valid for every 0.4.0 decoder.

## AW-2 (LOW → CONFIRMED, LOW): a notice first listed after a NoticeScreen rebuild never counts as shown

**Trace:**
- `AwarenessService.java:75-83` counts NoticeScreen's list only in AFTER_INIT.
- NoticeScreen's actions and dismissals call `rebuildWidgets()` (`client/ui/NoticeScreen.java:56-61,63-67`), which Fabric
  doesn't report through AFTER_INIT (`docs/v0.4/design/ws-w.md:68`).
- Rows past `bottom` aren't listed (`:82-84`).

**Effect (milder than "lost"):** the hardware fingerprint isn't committed, so the same notice returns next session. It
also counts as shown if it becomes RigTuneScreen's current notice or NoticeScreen is reopened.

**Correction to the audit's fix:** `ScreenEvents.afterTick(screen)` + `notices.shown()` allocates on every tick
(`NoticeScreen.shown()` is `List.copyOf`, `:120-122`), which breaks ws-w's "no allocation per tick" rule. Instead either:
- report the listed notices from the end of `NoticeScreen.init()` (which runs on every rebuild) through a callback, or
- add a non-allocating `lists(String key)` check to NoticeScreen for the per-tick listener.

**Test:** AwarenessGameTest at 854x480@3 with 3 or more notices: dismiss the top one, then check awareness.json.

## SD-4 (LOW → CONFIRMED, LOW): Clear while the saved-summary load is queued brings the summary back

**Trace:**
- `loadSaved` assigns `saved` with no generation check (`client/stutter/StutterService.java:328-331`).
- `clear()` bumps the generation and queues its clear behind the load (`:134-145`).
- Saves do check the generation (`:209`, `:286`).

**Test (fails, deterministic):** `sd4ClearWhileTheSavedLoadIsQueuedKeepsItCleared` holds the io chain with a latch,
queues `loadSaved()`, calls `clear()`, then releases. It prints `file sessions after clear=0`, but `saved` in memory is the
old summary and `summary().isEmpty()=false`.

In a real game the window needs the io chain or the 2-thread `Probes.EXECUTOR` to be busy (the startup probe, a queued
session save and its analysis) when the player clicks Clear. Narrow, so LOW.

**Fix:** capture `generation` when `loadSaved()` queues, and assign `saved` only if it still matches.

**Overlap:** same file as SD-3 and L1.

## SD-5 (LOW → CONFIRMED, LOW): Copy summary on `"causes": {"unknown": null}` crashes the game

**Trace:**
- `core/stutter/StutterSummary.java:61` unboxes `getOrDefault(UNKNOWN, …)`.
- `core/stutter/StutterReport.java:27` keeps null map values, although its header (`:14`) and ws-s self-review L12 say
  a hand-edited file "with nulls … reads safely".
- The screen itself is null-safe (`client/ui/StutterScreen.java:263-267`). Only Copy summary
  (`:100-103` → `:145-151` → `controller.stutterSummary()`) reaches the unboxing.

**Test (fails):** `sd5NullUnknownCauseDoesNotThrow` loads the file (`causes={unknown=null}`), then
`NullPointerException … Map.getOrDefault(Object, Object) is null at StutterSummary.text(StutterSummary.java:61)`.

**Crash check:**
- In MC 26.2's `net.minecraft.client.MouseHandler.onButton` (bytecode, `minecraft-client-only.jar`), `Screen.mouseClicked`
  sits in a `Throwable` handler (exception table 241-337 → 341).
- That handler builds `CrashReport.forThrowable(…, "mouseClicked event handler")` and throws `ReportedException`.

So the click crashes the game. Severity stays LOW because only a hand-edited file triggers it: Gson never writes a null
map value by default.

**Fix:** drop null values from `causes` and `tags` in `StutterReport`'s compact constructor. Also extend
`StutterStoreTest.aHandEditedSessionWithNullsReadsSafely` with `spikes.minor = 1` and `unknown: null`; today it uses
`{"gc": null}` with 0 spikes, so it never reaches line 61.

## PF-5 (LOW, needs test → CONFIRMED, LOW): "My settings" drops a DH LOD radius above 512

**DH's real range:**
- `javap` of the static initialiser of `com.seibel.distanthorizons.core.config.Config$Client$Advanced$Graphics$Quality` in
  DistantHorizons-3.3.2-26.2-fabric-neoforge.jar (the player's own Modrinth-App instance, copied to scratch).
- `lodChunkRenderDistanceRadius` is built with `setMinDefaultMax(32, 256, 4096)`.
- The repo's own research also cites DH's FAQ on "LOD radius 1024" (`docs/research/v0.2/dh-iris.md:426-427`).
- So values 513..4096 are live.

**Trace and test:** `ProfileService.managed` keeps a value only if `key.encode(value) != null`
(`client/profile/ProfileService.java:506-515`), and key 22 is `32..512` (`core/profile/ShareKeys.java:137`).
`pf5BaselineKeepsADhRadiusAbove512` prints `managed(…Radius=1024) -> {vanilla.renderDistance=12}` (dropped).

**Correction to the audit's fix:** the range gate is in **three** places, not one:
- `managed()`;
- `ProfileStore.settings()` on every read (`core/profile/ProfileStore.java:384-400`, line 391);
- `ProfileSwitch.build` (`core/profile/ProfileSwitch.java:37`).

Saving the raw snapshot alone would be dropped again on read. **Do not widen key 22's range**, because 0.4.0 decoders
reject the whole code as OUT_OF_RANGE (Latent 1).

**Fix (M):** give the table a local-only bound per key (DH radius 32..4096) used by those three gates. The share encoder
keeps the wire range and silently leaves out what it can't carry, as it does today. If sharing large radii matters, add a
new key index; older decoders skip unknown indices. The cheaper option (S) is a Preview note for keys the baseline
couldn't hold.

**Compatibility:** a 0.4.0 downgrade reading profiles.json with 1024 drops it on read (same as today, no crash).

**Overlap:** `ProfileStore.java` with leftover **L8**.

## SD-6 (LOW, needs test → CONFIRMED, LOW): under non-generational Shenandoah the live set includes Metaspace and CodeHeap

**Probe:** `ShenProbe.java` in scratch `shen/`; it calls the real `GcListener.oldGenerationUsed` by reflection. Output in
`shen/probe-output.txt`. JDK 25.0.4, `-XX:+UseShenandoahGC`:
- Pools: `Shenandoah` (HEAP), plus `Metaspace`, `Compressed Class Space` and three `CodeHeap '…'` (Non-heap).
- A `Shenandoah Cycles | end of GC cycle` notification is MAJOR.
- `getMemoryUsageAfterGc()` holds all 6 pools.
- RigTune's `oldGenerationUsed` = 80 MB against 77 MB in heap pools (+3 MB in a tiny program). A modded game's hundreds
  of MB of Metaspace plus CodeHeap add ~10-15 points on a 4 GB heap, as the audit said.

Code: `client/stutter/GcListener.java:83,93-107`. `GcKind.oldPool` needs "Old"/"Tenured" (`core/stutter/GcKind.java:99-101`).

Under G1 and ZGC the old pool is found (`G1 Old Gen`, `ZGC Old Generation`), so those collectors are unaffected.

**Fix (S):** when no old pool is found, sum only HEAP-type pools (names from `ManagementFactory.getMemoryPoolMXBeans()`,
captured once in `start()`).

**Test:** unit test of `oldGenerationUsed` with a Shenandoah-shaped map and the heap-pool names.

## Latent 1 (CONFIRMED, still latent): share codes can't tolerate a widened key

- `core/profile/ShareKeys.java:13-15` says later versions add "values at the end of an enum … so older decoders can skip
  what they don't know".
- But the decoder rejects the whole code on an out-of-range known key (`core/profile/ShareCode.java:174-176`), as SPEC 4
  requires ("a known key out of range → reject", `docs/v0.4/SPEC.md:120`).
- **Also:** `ShareKeysTest`'s pin comment (`src/test/…/core/profile/ShareKeysTest.java:15-16`) explicitly allows "a new
  enum value goes at the end of its list". The pin catches the edit, but its own comment tells the editor to make it.

Fix both comments to say "never extend an existing key; add a new key index". This bears directly on PF-5's fix and on
any v0.5 DH/Iris enum addition.

## Latent 2 (CONFIRMED, still latent): the probe's `"unknown"` GPU counts as a real GPU

**Trace:**
- `client/probe/HardwareProbe.java:93-109` falls back to `"unknown"`.
- `core/awareness/Fingerprint.java:28-34` passes it through.
- `ChangeDetector.java:42-45` treats a non-blank renderer as known.

**Test (fails):** `latentUnknownGpuIsNoChange` gives `Change[kind=GPU]`.

**Extra path:** the first-run seed (`ChangeDetector.java:126-128`) stores the fingerprint even when it's incomplete. A
first probe that failed would seed "unknown", and the next good probe would then raise "Your GPU changed".

**Reachability:** still none found. `RenderSystem.tryGetDevice()` is set before `CLIENT_STARTED`, and the power-edge
rescan hops to the render thread.

**Fix (S):** `Fingerprint.of` maps `"unknown"` to `""`.

## NEW-1 (LOW, UNCERTAIN impact): generational Shenandoah treats every cycle as a live-set sample

**Found by the SD-6 probe with `-XX:ShenandoahGCMode=generational`** (a JDK 25 product mode):
- The pools are `Shenandoah Young Gen` and `Shenandoah Old Gen`.
- Every `Shenandoah Cycles` notification, young cycles included, is MAJOR (`core/stutter/GcKind.java:45-47`:
  `CYCLE && startsWith("Shenandoah")`).
- `oldGenerationUsed` reads `Shenandoah Old Gen` after young cycles too. In the probe it read 0 MB, and 0 is filtered out
  (`StutterAnalyzer.java:267`).

Under G1 and ZGC only global/major events count. After young cycles the old-gen occupancy includes floating garbage, so
the median live set could run high on long sessions. The direction and size are unmeasured.

**Settle it:** a generational-Shenandoah MC session with `-Xlog:gc`, comparing `liveSetPct` with the log's old-gen
occupancy after global cycles.

**Possible fix:** MAJOR only for Shenandoah global/old cycles. Detection is still to be designed; the cause string or
action may distinguish them.

Files: `GcKind.java`, `GcListener.java`. It's rare (an opt-in mode of an opt-in collector), so it isn't worth doing
before SD-6.

## Things checked that don't add findings

- `ActiveProfile.inEffect(null, …)` returns true (`core/profile/ActiveProfile.java:19-22`), so an "already on Battery"
  switch (`activeEntry` null) keeps Battery active for both edges. No PF-1 variant there.
- `afterProbe` replaces a not-yet-committed Pending with an equal one on each rescan. Harmless: the same key, and at worst
  one extra commit.
- `StutterScreen` itself is null-safe for null `causes`/`tags` values. Only Copy summary hits SD-5.
