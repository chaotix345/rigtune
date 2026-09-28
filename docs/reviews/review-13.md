# Review 13: final focused re-check of the review-12 fixes (RigTune v0.5.0)

Repo C:/Dev/Minecraft Setting Optimisation Mod @ 3511cad7, fix range ac109a2d..3511cad7. Read-only; nothing built or run.
Paths: `core/` = src/main/java/io/github/chaotix345/rigtune/core/, `client/` = src/client/java/io/github/chaotix345/rigtune/client/,
`gt/` = src/gametest/java/io/github/chaotix345/rigtune/gametest/.

Counts: 0 HIGH, 1 MEDIUM, 1 LOW (new). Every review-12 HIGH/MEDIUM is FIXED.

## (1) Review-12 HIGH/MEDIUM status at 3511cad7

| id | status | evidence |
|---|---|---|
| R12APPLY-1 | FIXED | client/undo/UndoService.java:193-195 passes `Staging.stagedGroups` (ApplyExecutor.startedGroups, "op:<id>" keys, relocated plan) into plan/planEntry/recheck; core/history/UndoPlanner.java:527 waits on a started key, so a started group is never DISCARD_STAGED. |
| R12APPLY-2 = R12X-1 | FIXED | client/undo/Staging.java:297 (dropQueuedUpdates) and :424 (discardExcept) key ops by `key(op)` (group or "op:<id>"); `keptGroup`/`keptHeld` come from the kept ops (:433-438), not from `halfDone` being non-empty. |
| R12APPLY-3 | FIXED (the review's alternative) | Held started groups are kept but no longer claimed "finished at the next restart": Discard says `discarded_with_held` (Staging.java:366, :433-438; RealController.java:777 passes `holds`), and Undo says HELD_PARTLY/HELD_ENTRY (UndoPlanner.java:528-531). They are WAITING keys, so Undo last stays on that entry until the player chooses in the held notice, which is shown whenever `held` is non-empty under LAUNCHER/PENDING (LauncherRepairService.java:148-157). That doesn't deadlock: both notice buttons clear the group. |
| R12FEAT-1 = R12X-2 | FIXED (LOW residual R13-2) | `GpuName.clean` on record (client/benchmark/BenchmarkConditions.java:114) and `GpuName.same` on compare (core/benchmark/BenchmarkTrend.java:195, core/stutter/FixConditions.java:142). Older raw records are cleaned at compare time. |
| R12STUTTER-1 | FIXED | Both sides drop the capture's first `SETTLE_NANOS` (180 s): client/stutter/StutterMonitor.java:303 marks the ring and core/stutter/StutterAnalyzer.java:239-241 starts `Compared` there. Spikes (`end() > from`) and gameplay (`total - gameplayAtMark`, which includes the first frame past the mark) cover the same span. docs/v0.5/verification/stutter-fixes/sims/sim4.after.txt shows H0 false LESS <= ~4%. |
| R12STUTTER-2 = R12X-6 | FIXED | core/stutter/FrameRing.java:146 stamps `C_GAMEPLAY` on each candidate. When both rings wrapped, StutterAnalyzer.java:227-231 starts at the oldest held candidate with `total - stamp`. The mark only moves `from` later, and candidates stay contiguous from the oldest, so every spike after `from` is known. |
| R12STUTTER-6 | FIXED (new defect in its expiry: R13-1) | BASELINE -> READY -> Apply (core/stutter/FixTracker.java:237-266; client/stutter/StutterFixService.java `start`/`apply`). The session that led to the offer is never a side, and the before side is a fresh session that counts only after the choice (`startedAt >= chosenAt`, both truncated to the second). |
| R12X-3 (= R12APPLY-6, PERF-2) | FIXED | client/stutter/StutterFixService.java:91 `JournalCache.snapshot(ClientJournal.get())`. history.json is read only while some record `followsJournal` (advanceAll, holds). |
| CI-1 | FIXED | r-ci's 73f3114c is an ancestor of 3511cad7. tools/ci_streak.py has DESIGN_SKIPPED (skipped and not required only) and requires every part k/n of a split leg. |
| Returning-player delta gate (RC-BLOCKER-1) | FIXED | tools/footprint-budgets.json:10-11 has `returningAdded*` at 80 ms, mode fail. gt/FootprintGameTest.java `returningPlayer()` gates returning minus the same leg's fresh value. The build.yml step "Returning-player footprint" copies the fresh JSON before the wipe. Note, not a defect: across 9 CI pairs the max was +64 / +60 ms, so the headroom is 16-20 ms. |
| FL-1 | FIXED | gt/LauncherManagedGameTest.java:572 waits (200 ticks) for DISCARDED x2 before the check. |
| FL-2 | FIXED | gt/LauncherManagedGameTest.java:580 recounts with `real.stagedChanged()` itself. |
| FL-3 | FIXED | gt/StutterGameTest.java:151, :174 wait for the exact session by `startedAt`. |
| FL-4 | FIXED | gt/StutterGameTest.java:105 `waitFor(explicitGc(gcCalled), 200)`. |
| FL-5 | FIXED | client/server/ServerProfileService `lookups` counter (`thenRun`); gt/ServerProfilesGameTest.java:167 waits for it. |
| FL-6 | FIXED | gt/BatteryFlowGameTest.java:251 times the product side (`watch.handledNanos`). |

Also checked as fixed in passing: R12APPLY-4 (ApplyExecutor.rollBack marks `done:false` before the move and re-marks
true only if stuck) and R12APPLY-5 (RigTunePreLaunch.readAtStart runs HistoryStartup before readState; readState only
reads, so swapping the order is safe).

## (2) Regression hunt (only code the review-12 fixes changed)

| id | sev | file:line | scenario | fix |
|---|---|---|---|---|
| R13-1 | MEDIUM | core/stutter/FixTracker.java:238-239, :262-263 (a before-apply record expires to plain EXPIRED); FixTracker.java:105-107 (`undoable`); core/stutter/FixText.java:136-139 (`applied`); core/stutter/FixHold.java:42 (EXPIRED counts as in effect); client/ui/StutterScreen.java:537-540 | **An expired chosen fix, never applied, reads as an applied one.** The player presses "Try this fix…" (RD 16 -> 12). Then either five baseline sessions don't count (for example SHORT_BEFORE: each shorter than about 8 min of wall time, now that the first 3 minutes are cut), or the player leaves it READY for 14 days. `baseline()` then returns `withState(EXPIRED)`, which is the same record an applied fix expires to. Results: (a) `tracked()` (newest not dismissed) shows the block "Render distance: 16 -> 12, applied <date>". `beforeApply()` is false for EXPIRED, so the "chosen" wording isn't used, and nothing was applied. (b) `undoable()` is true (EXPIRED without GONE), so "Undo this change…" is shown and opens UndoScreen for an entry id that history.json never had. (c) `FixHold.holds` counts it as in effect. If RD is later at 12 anyway (for example the main screen's own Apply of the same recommendation), every recommendation that moves RD away is unticked, with "You set this on <chosen date> with the Stutter Doctor's fix; changing it here undoes that fix". FixTrackerTest (the new test near :54) asserts EXPIRED but not `undoable()` or the hold. | Keep a before-apply expiry distinct. For example, expire with a marker skip (`NEVER_APPLIED`, or keep the last skip and add a boolean), and treat that like GONE: `undoable()` false, `FixHold` not in effect, `FixText.applied` says "chosen". A simpler option is to expire it to NOT_APPLIED with its own state line. Test: BASELINE/READY past MAX_AGE, and after 5 skips, gives `!undoable()`, no FixHold hold, and the "chosen" wording. |
| R13-2 | LOW | core/benchmark/GpuName.java:15 (`VERSION_GROUP` drops the whole parenthesised group) | The review asked to keep the chip id. The fix drops the whole group when any token in it has a version, so "(radeonsi, <chip>, LLVM x.y, DRM x.y, ...)" loses the chip. Mesa reports AMD APUs under a generic model name (the repo's own Vulkan row, real-strings.tsv:31, is "AMD Radeon Graphics (RADV GFX1201)"; the OpenGL radeonsi form with the chip inside the version group is UNVERIFIED in-repo). If so, two different APUs both clean to "AMD Radeon Graphics". An instance synced or moved between two such machines then gets no "GPU changed" in the benchmark trend, the rerun marker or C20's GRAPHICS skip. It needs a moved instance, so this is edge only. | Inside a matched group, drop only the tokens with a version (LLVM x, DRM x, a kernel release, N bits) and keep the rest ("radeonsi, gfx1201"). Test: two radeonsi strings that differ only in LLVM/DRM/kernel are the same; the same generic name with renoir vs rembrandt differs. |

## Checked, no defect

**UndoPlanner/Staging held/started logic**
- Keys match everywhere: `key(op)`, `startedGroups` and `keys(held)` all use group or "op:<id>".
- `held` is taken only among started or partly groups. An unstarted held group is still cancellable, as the notice's Cancel does.
- Undo everything skips a held group without `waitWhole`.
- `discardPending` computes `held` on the full plan before it saves the kept one.
- A held group in Undo is a subset of the notice's (relocated vs. saved plan; the records carry the relocated paths), so Undo never points to a notice that isn't shown.
- `recheck` gets the same StagedGroups under the lock.

**C20 flow, including the SETTINGS_CHANGED count**
- BASELINE can't get stuck. `advanceAll` runs on every session end and on each 5 s refresh; MAX_AGE (from `chosenAt`) or 5 skips end it (see R13-1 for how).
- The offer can't vanish. READY's Apply is built from the record (StutterScreen `applyFix`), not from `shown`; `apply()` re-checks only `stillAt`, and `add()` replaces by entry id.
- Shared rings are new per session (StutterMonitor.stop sets `rings = null`), so `settingChanges` doesn't carry over.
- An immediate fix's own change is dated `STATE.since()`, the previous tick, before the restarted session starts, so it isn't counted as CHANGED.

**`GpuName.clean` on real strings**
- Unchanged: NVIDIA "/PCIe/SSE2", "Intel(R) ... (TM)", "(RADV GFX1201)", "(256 bits)" and "(KBL GT2)".
- Dropped: the radeonsi and llvmpipe version groups.
- Zink's nested parentheses aren't matched, so the string is left as is (harmless).

**release.yml preflight and the no-duplicate lookup**
- The preflight runs before "Create GitHub release", read-only with the token (on the dry run too), and fails the job before anything is public.
- A lost POST response with a cached version list is caught by `version_by_hash`.
- Other bytes under the same number, or the same bytes under another number, is an error, never "published". Modrinth's own duplicate-hash refusal backs this up.
- `"${dry[@]}"` under `set -u` is fine on the runner's bash 5.

**Frame-ratio re-measure (FrameHookBudgetTest.ratioGate)**
- Only an over-limit first measure is re-measured. The second measure is both gated and self-checked (its doubled work must exceed the limit).
- A real 2x regression measures >= min2x 1.92 against the 1.65 limit, so it can't pass twice.
- A drift near the limit is detected about half as often, which is accepted for a 2x gate.
- The scripted-clock test covers outlier, pass and fail.
