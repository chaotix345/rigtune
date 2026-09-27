# RigTune v0.4.0: bug audit of the new features

Repo: `C:\Dev\Minecraft Setting Optimisation Mod`, branch `main` @ `987179e4` (v0.4.0 as released, PR #10/#11).
Read-only: files were read with the Read tool and `git grep`/`git show`/`git log`. No builds, no Java/Gradle/Minecraft runs,
no edits, no git state changes.

Scope: Performance Profiles and share codes, Stutter Doctor, JVM and memory advice, server-aware advice and change awareness
(including the notice sources and NoticeScreen), benchmark history, and the accessibility layer (RowFocus, RowList, Palette).

Method: three area passes (profiles; Stutter Doctor; JVM/server/awareness/notices) ran in parallel, and I did benchmark
history and a11y myself. I then re-read every cited line in the source before keeping a finding. Excluded: everything in
`docs/reviews/review-7.md`..`review-10.md`, `docs/v0.4/audit-verification.md`, and the documented residuals/deviations in
`docs/v0.4/design/{ws-p,ws-s,ws-j,ws-w,ws-b,ws-x,ws-k,ws-f2,fix-8b,fix-9}.md`.

**Result:** 15 findings: **0 high, 2 medium, 13 low**. 13 are CONFIRMED by a full trace in code. 2 need a test to confirm
(PF-5, SD-6). Two more latent items are noted at the end, not counted.

## Summary

| id | area | sev | verdict | title | main evidence |
|---|---|---|---|---|---|
| PF-1 | profiles | MEDIUM | CONFIRMED | Taking the Battery offer with no active profile means plugging back in offers nothing | `client/profile/ProfileService.java:334,367-368`; `core/profile/BatteryPrompt.java:38` |
| SD-1 | stutter | MEDIUM | CONFIRMED | In long sessions, GC and sampler evidence covers only the last ~15-17 min, but shares and rules divide by every spike | `core/stutter/StutterRings.java:9-10`; `core/stutter/StutterAnalyzer.java:104,247-266` |
| SD-2 | stutter | LOW | CONFIRMED | "1% low" is computed over the frame ring's tail while average FPS covers the whole capture | `core/stutter/StutterAnalyzer.java:116,124` |
| AW-1 | awareness | LOW | CONFIRMED | A shown hardware/driver-change notice disappears at the next rescan (power edge, network toggle) | `client/awareness/AwarenessService.java:93` |
| PF-2 | profiles | LOW | CONFIRMED | "Don't offer again" can't be undone in game, and on the plug-in notice it also kills the unplug offer | `client/profile/ProfileService.java:293,303-305`; `core/profile/BatteryPrompt.java:29` |
| SD-3 | stutter | LOW | CONFIRMED | After a session that isn't saved, Stutter Doctor says "No sessions recorded yet" while stutter.json has sessions | `client/stutter/StutterService.java:196,323-326` |
| BH-1 | bench history | LOW | CONFIRMED | Benchmark history says "3 comparable runs" and then "Not enough comparable runs for a trend yet (2 of 3)" | `core/benchmark/BenchmarkTrend.java:345`; `core/benchmark/TrendText.java:42-45` |
| BH-2 | bench history | LOW | CONFIRMED | A staged change journaled before a run is listed as "changes since then" for that run once a restart applies it | `core/benchmark/ChangeWindow.java:65-71` |
| PF-3 | profiles | LOW | CONFIRMED | Deleting the offered profile leaves a live "Switch back to ??" notice | `client/profile/ProfileService.java:285,486` |
| PF-4 | profiles | LOW | CONFIRMED | A fixed 60 FPS cap shared from a 60 Hz PC arrives as "match the display" (140 on 144 Hz) | `core/profile/ShareCode.java:77-79` |
| AW-2 | notices | LOW | CONFIRMED | A notice first listed after a NoticeScreen rebuild never counts as shown | `client/awareness/AwarenessService.java:79-80`; `client/ui/NoticeScreen.java:59,66` |
| SD-4 | stutter | LOW | CONFIRMED (timing) | Clear pressed while the saved-summary load is queued brings the deleted summary back | `client/stutter/StutterService.java:328-331` |
| SD-5 | stutter | LOW | CONFIRMED (hand edit) | Copy summary throws an NPE on a saved session with `"causes": {"unknown": null}` | `core/stutter/StutterSummary.java:61` |
| PF-5 | profiles | LOW | NEEDS TEST | "My settings" silently drops values outside the share table's ranges (DH radius > 512) | `client/profile/ProfileService.java:506-514`; `core/profile/ShareKeys.java:137` |
| SD-6 | stutter | LOW | NEEDS TEST | Under non-generational Shenandoah, the live set sums every memory pool (Metaspace, CodeHeap) | `client/stutter/GcListener.java:94-106` |

All paths below are under `src/main/java/io/github/chaotix345/rigtune/` (core) or `src/client/java/io/github/chaotix345/rigtune/`
(client) unless written in full.

---

## PF-1 (MEDIUM, CONFIRMED): taking the Battery offer with no active profile means plugging back in offers nothing

**Scenario** (the typical first-time laptop path):
1. The player has never switched profiles, or the last switch was undone (ActiveProfile voids it).
2. The player unplugs and gets "Switch to the Battery profile?". They click **Switch to Battery**.
3. `switchTo` creates "My settings" (`ensureBaseline`), but `previous = active()` is null, so `markActive` stores
   `previousProfile = null`.
4. The player plugs back in. `BatteryPrompt.onEdge(false, ...)` returns NONE because `previousProfile() == null`. No notice
   and no toast appear, and Battery (60 FPS, VSync, short render distance) stays applied on AC until the player finds
   Profiles → My settings.
5. Variant: if a previously remembered profile's switch was undone, the next Battery switch overwrites the remembered id
   with null.

**Evidence**
- `client/profile/ProfileService.java:330-331`: `// The way back: "My settings" exists before the first switch, even one made from the battery offer.` / `ensureBaseline();`
- `client/profile/ProfileService.java:334`: `String previous = active();`
- `client/profile/ProfileService.java:462-469`: `active()` returns null when nothing is stored or `ActiveProfile.inEffect(...)` is false.
- `client/profile/ProfileService.java:367-368`: `if (BatteryPrompt.BATTERY.equals(id) && !BatteryPrompt.BATTERY.equals(previous)) { store().rememberPrevious(previous); }`
- `core/profile/ProfileStore.java:326-328`: `root.getAsJsonObject(BATTERY).addProperty(BATTERY_PREVIOUS_PROFILE, previous);` (writes JSON null).
- `core/profile/BatteryPrompt.java:38`: `if (!BATTERY.equals(active) || state.previousProfile() == null || BATTERY.equals(state.previousProfile())) { return Decision.NONE; }`
- `README.md:80` promises it "offers your previous profile when you plug back in". `CHANGELOG.md:18` says the same.
- The game test switches to My settings first, so it never covers this path: `src/gametest/.../ProfilesGameTest.java:371-372`
  (`// On My settings ... the profile the reverse edge offers back.` / `controller.switchProfile(mine)`).

**Why:** the baseline is created as "the way back" but is never used as the remembered previous profile.

**Fix:** in `markActive`, when switching to Battery with `previous == null`, remember the baseline instead:
`store().rememberPrevious(previous != null ? previous : store().baseline().id())`. `ensureBaseline()` has already run.

**Test:** in `ProfilesGameTest.batteryOffer`, start from a fresh profiles.json with no active profile (drop the
`switchProfile(mine)` line). Call `powerChanged(true)`, take the offer, then `powerChanged(false)`. Expect a `battery-back:`
notice targeting My settings. Today `batteryNotice()` is null.

---

## SD-1 (MEDIUM, CONFIRMED): in long sessions the GC and sampler evidence covers only the last ~15-17 minutes, but shares and rules count every spike

**Scenario:**
1. The player turns the session monitor on (its intended always-on use) and plays for 60 minutes.
2. The sampler ring holds 4096 samples at 4 Hz, about 17 minutes. With uncapped FPS, the GC ring's 2048 notifications also
   wrap after roughly 15-20 minutes (verification run A3 logged 358 G1 pauses in about 2.5 minutes).
3. Spikes still cover the whole session: the frame ring holds 9-36 minutes, and the 4096 candidate records go back further.
4. Spikes older than the evidence rings can't get a GC claim or a `dh`/`cpuContention` tag, so they count as "not explained".
5. The collection counters (full, explicit, stalls) and the live set are recounted from the held GC records only, so an
   early full GC disappears from `gcFullPausesAtLeast`.
6. Effect on advice:
   - `stutter-dh-threads` needs `stutterTaggedShareAtLeast {dh: 40}`. With spikes spread evenly over a session longer than
     about 42 minutes, the share can't reach 40 % even if every spike was DH work (at most 17/60 ≈ 28 %).
   - `ram-stutter-gc-heap` (`stutterShareAtLeast gc 30`, plus `gcFullPausesAtLeast 1`) is diluted and can lose its full-GC
     trigger.
   - A `not {gcFullPausesAtLeast: 1}` evaluates TRUE on a session that did have an early full GC. That breaks the fail-closed
     intent of ws-s self-review M2: dilution reads as FALSE, not UNKNOWN.

**Evidence**
- `core/stutter/StutterRings.java:9-10`: `GC_CAPACITY = 2048;` / `SAMPLE_CAPACITY = 4096;`
- `client/stutter/ThreadSampler.java:29`: `static final long PERIOD_MS = 250;`
- `core/stutter/FrameRing.java:17-18`: `SESSION_FRAMES = 1 << 17;` / `SESSION_CANDIDATES = 4096;`
- `core/stutter/StutterAnalyzer.java:55-56`: spikes come from `SpikeDetector.fromCandidates(f, ringStart)` plus `SpikeDetector.detect(ends)`, i.e. the whole capture.
- `core/stutter/StutterAnalyzer.java:93-95`: `causes.put(cause, round((double) ns / lost, 2))`, where `lost` is summed over every spike.
- `core/stutter/StutterAnalyzer.java:104`: `taggedShares.put(tag, 100.0 * n / spikes.size());`
- `core/stutter/StutterAnalyzer.java:247-266`: `full`/`stalls`/`explicit` are counted inside the loop over `in.rings().gc()` (held records only).
- `core/stutter/Attributor.java:152-166`: a GC claim needs an overlapping held `GcEvent`. `:217-231`: the dh/contention tag needs a held sample within `SAMPLE_NEAR`.
- `rules/source/knowledge.json:922`: `"stutterShareAtLeast": { "gc": 30 }, "anyOf": [ { "gcFullPausesAtLeast": 1 }, ...`. `:949`: `"stutterTaggedShareAtLeast": { "dh": 40 }`.
- `docs/research/v0.4/stutter.md:204,206` planned "GC ring ... wraps; **summary counters kept**" and an 8192-sample
  (34 min) sampler ring. Neither was built, and ws-s doesn't list this as a residual.

**Why:** the evidence rings are much shorter than the spike window, and there are no whole-capture counters.

**Fix (minimal):**
1. Keep per-capture running counters for full/explicit/stall collections (and, if wanted, a small reservoir of live-set
   samples) in `StutterRings.gc(...)` under its lock. The analyzer reads those instead of recounting the ring.
2. Compute claim and tag shares only over spikes newer than the oldest held sample or GC record. Alternatively, when a ring
   wrapped before the oldest spike, add `gc`/`dh`/`cpuContention` to `unmeasured` so the rules fail closed.

**Tests (fail today, `StutterAnalyzerTest`):**
- Write 1 full-GC record and then 2048 young records into a `StutterRings`. Expect `facts().gcFullPauses() == 1`; today it is 0.
- Write 5000 DH-heavy samples with spikes spread over the whole span. Expect the `dh` tagged share over the covered spikes
  to be 100 (or `dh` in `unmeasured`); today it is about 80 or less.

---

## SD-2 (LOW, CONFIRMED): "1% low" covers only the frame ring's last frames, while average FPS covers the whole capture

**Scenario:** the player plays uncapped: 30 minutes at ~50 FPS in a heavy base, then 10 minutes at ~300 FPS somewhere light.
The session frame ring (131,072 frames) holds only the last ~7 minutes. The header and Copy summary read like
"average 112 FPS · 1% low 200 FPS", which can't be true of one session. More commonly the numbers look plausible but
describe different windows: at 144 FPS the 1 % low covers only the last ~15 minutes of an hour-long session. A benchmark
capture (32,768 frames) at 1000 FPS covers the last ~33 seconds of sweeps.

**Evidence**
- `core/stutter/StutterAnalyzer.java:53`: `long[] ends = f.ends();`
- `core/stutter/StutterAnalyzer.java:116`: `FrameStats stats = FrameStats.of(gameplayDurations(ends));` (held frames only).
- `core/stutter/StutterAnalyzer.java:123-124`: `round(gameplaySeconds > 0 ? f.gameplayFrames() / gameplaySeconds : 0, 1), round(stats.onePercentLowFps(), 1)` (whole capture next to held frames).
- `core/stutter/FrameRing.java:17,19`: `SESSION_FRAMES = 1 << 17;` / `BENCHMARK_FRAMES = 1 << 15;`
- Shown together at `client/ui/StutterScreen.java:194` (`"rigtune.stutter.header.frames", number(r.frames()), number(r.avgFps()), number(r.onePercentLowFps())`) and in `core/stutter/StutterSummary.java:45-46` (`avg %.0f FPS · 1%% low %.0f FPS`).
- The research planned a whole-session fine histogram for this (`docs/research/v0.4/stutter.md:202`). The shipped
  histogram is 9 display buckets, too coarse for a 1 % low.

**Why:** the two statistics are computed over different frame windows and shown side by side.

**Fix:** derive the 1 % low from a whole-capture log-spaced histogram (research §2.5's 24 buckets), or compute both numbers
over the held window and label it ("last N min").

**Test:** feed a `FrameRing(SESSION_FRAMES, SESSION_CANDIDATES)` 131,072 frames of 20 ms and then 131,072 of 5 ms, and
analyze. Assert `report.onePercentLowFps() <= report.avgFps()`. Today it is about 200 vs about 80.

---

## AW-1 (LOW, CONFIRMED): a shown hardware/driver-change notice disappears at the next rescan

**Scenario:**
1. The player updates the GPU driver and launches. "Your GPU driver changed since last time (A → B)" appears, with
   Re-scan and Re-benchmark.
2. The notice is on the notice line, so `shown()` commits the new fingerprint to awareness.json.
3. Before acting on it, a rescan happens. The laptop is plugged in or unplugged (every confirmed power edge rescans), or the
   player toggles RigTune's network setting.
4. `afterProbe` compares against the fingerprint it just committed, finds no change, and sets `hardware = null`. The
   notice and its Re-benchmark button are gone for the rest of the session. The benchmark "needs a rerun" marker doesn't
   cover GPU or driver changes, so nothing else prompts a re-benchmark.

**Evidence**
- `client/awareness/AwarenessService.java:38-40`: `// A change stays a notice for the session; the fingerprint is committed once the notice was shown ...`
- `client/awareness/AwarenessService.java:93`: `hardware = change.changed() ? new Pending(change, now, HARDWARE_KEY_PREFIX + now.id(), new AtomicBoolean()) : null;`
- `client/awareness/AwarenessService.java:169-172`: `shown()` sets `committed` and runs `ChangeDetector.commit(store, p.now())`.
- `core/awareness/ChangeDetector.java:121-134`: `check` compares with the stored (now committed) fingerprint, so the result is NONE.
- Rescan triggers: `client/RealController.java:280` (`awarenessService.afterProbe(hardware)` inside `rescan()`);
  `client/profile/ProfileService.java:274` (`controller.rescan()` on every power edge, offer or not);
  `client/ui/RigTuneSettingsScreen.java:130-133` (`networkChanged()` → `controller.settingsChanged()` → `RealController.java:248-250` → `rescan()`).
- `docs/v0.4/design/ws-w.md:72`: "Once shown, the fingerprint is committed (async); the notice stays for the session."

**Why:** `afterProbe` can't tell "no change because we just committed it" from "no change at all".

**Fix:** in `afterProbe`, when the result is NONE, keep the existing `Pending p` if `p.committed().get()` and `p.now()` equals
the new fingerprint. Clear it only when the hardware differs from `p.now()`. The notice's own Re-scan action can still
clear it explicitly.

**Test:** in AwarenessGameTest, after the notice is shown and committed, call `controller.rescan()`, wait for the new report,
and assert `awarenessService().hardwareNotice() != null`. Or extract the keep/replace decision into a pure static and unit-test it.

---

## PF-2 (LOW, CONFIRMED): "Don't offer again" can't be undone in the game, and on the plug-in notice it also turns off the unplug offer

**Scenario:** the player clicks **Don't offer again** on the "You're plugged in again. Switch back to X?" notice, meaning
"stop offering to switch back" (or clicks it by mistake). From then on no unplug offer ever appears either. No setting or
screen turns it back on; only hand-editing `profiles.json` (`battery.snoozed`) does.

**Evidence**
- `client/profile/ProfileService.java:293`: the back-offer gets the same `new NoticeAction(ACTION_SNOOZE, Text.of("rigtune.battery.action.snooze", "Don't offer again"))`.
- `client/profile/ProfileService.java:303-305`: `if (ACTION_SNOOZE.equals(actionId)) { store().snoozeBattery(true); return; }`
- `core/profile/BatteryPrompt.java:29`: `if (!state.prompt() || state.snoozed() || benchmarkRunning) { return Decision.NONE; }` applies to both edges.
- `git grep` finds `snoozeBattery(` called only with `true` (`ProfileService.java:304`) and no UI writer of `battery.prompt`
  (`ProfileStore.java:74-78` only defaults it).
- `docs/research/v0.4/profiles.md:398` calls it a "Don't offer again" *toggle*. `README.md:80` says "Don't offer again turns the offer off" but not how to turn it back on.

**Fix:** add a "Battery offer: On/Off" row (RigTuneSettingsScreen or ProfilesScreen) that calls `snoozeBattery(false)`.
Optionally give the back-offer a plain dismiss instead of the global snooze.

**Test:** snooze from the back-offer, re-enable through the new toggle, then `powerChanged(true)` offers Battery again.

---

## SD-3 (LOW, CONFIRMED): after a session that isn't saved, Stutter Doctor says "No sessions recorded yet" until restart

**Scenario:**
1. Fresh launch with the monitor on and earlier sessions in `stutter.json`. The player starts a benchmark-world run, so a
   session starts when the world loads.
2. `begin()` ends that session as benchmark-interrupted. It has under 2 minutes of gameplay, so it isn't saved.
3. The player presses Esc, which cancels the run (`keep == false`, so no benchmark summary is saved).
4. The fresh post-run session ends at world exit and isn't saved either.
5. Tools → Stutter Doctor shows "No sessions recorded yet." and Copy summary has nothing to copy. This lasts until the next
   saved session or a restart. A normal session end whose write fails behaves the same way.

**Evidence**
- `client/stutter/StutterService.java:196`: `savedState = Saved.DONE;` runs on every `end()`, before the save's outcome is known.
- `client/stutter/StutterService.java:203-206`: the `!StutterStore.worthSaving(...)` branch returns without touching `saved`.
- `client/stutter/StutterService.java:277-279`: `if (copy == null || !keep) { return; }` (a cancelled run saves nothing).
- `client/stutter/StutterService.java:323-326`: `if (savedState != Saved.UNKNOWN) { return; }`, so `loadSaved()` never reads the file.
- `client/stutter/StutterService.java:102-104`: with no session, `shown = saved` (null).
- `client/ui/StutterScreen.java:175-176`: `rigtune.stutter.none`.

**Why:** `end()` marks the saved summary as known without knowing whether anything was saved.

**Fix:** don't set `savedState = Saved.DONE` in `end()`, or set it only where `saved = a` is assigned. The `io` chain is
ordered, so a later `loadSaved()` already reads after any queued save.

**Test (StutterGameTest):** seed `stutter.json` with one session, turn the monitor on, start a benchmark-world run and cancel
it, leave the world, open StutterScreen, and expect `shownView().report() != null`. Today it is null.

---

## BH-1 (LOW, CONFIRMED): Benchmark history says "3 comparable runs" next to "Not enough comparable runs for a trend yet (2 of 3)"

**Scenario:** the player does their 3rd benchmark under the same conditions and opens Benchmark history. The note line reads
"3 comparable runs; 0 with different conditions not shown", and the line under it reads "Not enough comparable runs for a
trend yet (2 of 3)". The result screen right after that run shows the same "(2 of 3)". After the 5th run, the note says
"5 comparable runs" and the trend says "... (4 comparable runs)". The two lines count different sets (with and without the
latest run) under the same words.

**Evidence**
- `core/benchmark/BenchmarkTrend.java:342,345`: `comparableRuns = group.size()`, i.e. every run of the context including the latest.
- `core/benchmark/BenchmarkTrend.java:242-253`: the assessment's `baselineRuns` = `baseline(latest, runs).size()`, i.e. the runs before the latest.
- `core/benchmark/TrendText.java:42-45`: `too_few` "... (%s of 3)", `a.baselineRuns()`; `in_line` "... (%s comparable runs)", `a.baselineRuns()`.
- `core/benchmark/TrendText.java:230-232`: `note(...)` "%s comparable runs; ...", `view.comparableRuns()`.
- `src/main/resources/assets/rigtune/lang/en_us.json:637,649,621`: the same wording.
- ws-b deviation 1 documents only that "your usual" excludes the latest run, not the contradictory counts on one screen.

**Fix:** word the trend's count as what it is ("needs 3 earlier comparable runs (2 so far)", "in line with your usual,
from N earlier runs"), or pass the same count to both lines.

**Test:** `TrendTextTest`: a view with 3 comparable runs gives a note count and a `too_few` argument that agree, or wording
that names "earlier" runs.

---

## BH-2 (LOW, CONFIRMED): a staged change journaled before a run is listed as a "change since then" for that run once a restart applies it

**Scenario:**
1. Four comparable runs exist.
2. The player applies RigTune changes that take effect at the next start (a mod add/update, Sodium/DH/Iris settings). They
   are journaled STAGED.
3. Without restarting, the player benchmarks again (run B), for example as the "after" half of a Measure. B regresses for an
   unrelated reason. The notice lists no changes, because the staged ones are still STAGED ("No change recorded; possibly a
   driver, OS or other change").
4. The player restarts. The staged changes become APPLIED. The regression notice for B (not yet acknowledged) now says
   "Changes since then (may be related): · Added <mod>", although that mod wasn't loaded during B. B's own `modSetHash`
   proves it wasn't.

**Evidence**
- `core/benchmark/ChangeWindow.java:65-71`: every window change with `tookEffect(change)` (APPLIED or REVERTED *now*) is listed, with no check that it took effect before the latest run.
- `core/benchmark/ChangeWindow.java:57-63`: the review-M1 fix handles the same "staged, takes effect at the next start" case on the baseline side only (`carried(...)`, `stagedChangeIds`).
- `client/benchmark/TrendService.java:85-93`: the window is recomputed from the current journal whenever history.json changes (the memo key is its mtime and size), so the list changes after the restart.
- `client/benchmark/BenchmarkController.java:210,222-223`: the journal cursor is taken at the run's start (`BenchmarkConditions.journalCursor()`), so a STAGED entry made before the run is inside `(baseline.cursor, latest.cursor]`.
- `core/history/JournalChange.java`: there's no applied-at time to compare with the run's `createdAt`.

**Fix (minimal, additive):** record, in the run's context, the ids of journal changes still STAGED when the run starts (or
simply "pending ops existed"). Leave those ids out of that run's own window, and carry them into the next run's window
(the existing `carried` path). Without a schema change, a cheaper option: for window changes that are staged
(`row != SETTING` or in `stagedChangeIds`) in entries at or before the latest run's cursor, list them only when the latest
run's `modSetHash` differs from the baseline's (for mod rows).

**Test:** `ChangeWindowTest`: baseline cursor e0; entry e1 with a mod-file change; the latest run's cursor e1 (made while
e1 was STAGED); now e1 is APPLIED. Expect the latest run's window to omit e1's change. Today it lists it.

---

## PF-3 (LOW, CONFIRMED): deleting the offered profile leaves a live "Switch back to ??" notice

**Scenario:**
1. Battery is active, and the previous profile is "Evening". The player plugs in, and the back-offer shows.
2. The player opens Profiles and deletes Evening. The store clears `previousProfile`, but the in-memory offer stays.
3. Back on the RigTune screen the notice reads "You're plugged in again. Switch back to ??". Clicking Switch back gives
   "That profile isn't available right now."

**Evidence**
- `core/profile/ProfileStore.java:213-215`: `delete` clears only the stored `previousProfile`.
- `client/profile/ProfileService.java:284-288`: `batteryNotice()` retires the offer only when `target.equals(active())`.
- `client/profile/ProfileService.java:325`: `"You're plugged in again. Switch back to %s?", displayName(offer.decision().target())`.
- `client/profile/ProfileService.java:485-486`: `Profile profile = store().profile(id); return profile == null ? Text.literal("?") : name(profile);`
- `client/profile/ProfileService.java:134-135` / `:409-411`: `resolve` returns null, so the click gets `rigtune.profile.status.unavailable`.

**Fix:** in `batteryNotice()`, also retire a PREVIOUS offer whose `p-` target no longer resolves (`store().profile(target) == null`),
or have `deleteProfile` retire an offer targeting that id.

**Test (ProfilesGameTest):** reach the back-offer, delete its target, and assert `batteryNotice() == null`.

---

## PF-4 (LOW, CONFIRMED; a consequence of ws-p deviation 3 that isn't documented): a fixed 60 FPS cap shared from a 60 Hz PC arrives as "match the display"

**Scenario:** on a 60 Hz laptop, the player copies the code for Recording (`$recordingFps` = 60) or for their own saved
profile with maxFps 60. Since 60 equals this display's `refreshRateCap(60)`, the encoder sends wire value 26 ("match the
display"). A friend on 144 Hz imports it and gets maxFps 140, which defeats a profile whose point is a steady 60 FPS
capture. Preview shows "→ 140", so it's visible before Apply, but the sender can't avoid it.

**Evidence**
- `core/profile/ShareCode.java:77-79`: `if (key.kind() == ShareKeys.Kind.INT10 && refreshRate > 0 && wire.equals(key.encode(Integer.toString(SettingValues.refreshRateCap(refreshRate))))) { wire = ShareKeys.MATCH_DISPLAY; }`
- `core/recommend/SettingValues.java:51-57`: `refreshRateCap(60)` = 60 (the `-10` only applies from 100 Hz).
- `core/profile/ProfileTemplates.java:170-173`: `recordingFps` is 60 for any display of 60 Hz or more.
- `client/profile/ProfileService.java:217-223`: templates and saved profiles are both exported through this encoder.
- `docs/v0.4/design/ws-p.md:68-69` (deviation 3) documents the encoder rule, not this consequence.

**Fix:** emit `MATCH_DISPLAY` only when the value came from `$refreshRateCap` (carry provenance from `ProfileTemplates`), or
never for 60 Hz senders, where the cap and the common fixed cap coincide.

**Test:** `ShareCodeTest`: `decode(encode("Recording", {vanilla.maxFps: "60"}, 60)).values(144)` should give maxFps "60".
Today it gives "140".

---

## AW-2 (LOW, CONFIRMED): a notice first listed after a NoticeScreen rebuild never counts as shown

**Scenario:** at a small GUI size (e.g. 854x480 at scale 3), NoticeScreen fits about 2 notice rows. Several notices are active
(battery offer, server limit, regression, hardware change). The hardware notice sits under "+N more". The player dismisses
the server-limit notice, and `rebuildWidgets()` now lists the hardware notice, which the player reads. It's never recorded as
shown, so the fingerprint isn't committed and the same notice returns next session (unless it happens to become the RigTune
screen's current notice).

**Evidence**
- `client/awareness/AwarenessService.java:76-81`: NoticeScreen's list is counted only in `ScreenEvents.AFTER_INIT` (`notices.shown().forEach(this::shown)`); only RigTuneScreen gets a per-screen `afterTick`.
- `client/ui/NoticeScreen.java:58-60,64-66`: actions and dismissals call `rebuildWidgets()`.
- `client/ui/NoticeScreen.java:82-84`: rows past `bottom` aren't listed.
- `docs/v0.4/design/ws-w.md:68`: "Fabric's AFTER_INIT doesn't fire on `rebuildWidgets`".

**Fix:** also register `ScreenEvents.afterTick(screen)` for NoticeScreen calling `notices.shown().forEach(this::shown)`. It
returns early once committed.

**Test (game test):** 3+ notices at 854x480@3. Open NoticeScreen, dismiss the top one, and assert awareness.json holds the new fingerprint.

---

## SD-4 (LOW, CONFIRMED in code; needs a timing window): Clear pressed while the saved-summary load is queued brings the deleted summary back

**Scenario:** soon after launch, while `Probes.EXECUTOR` (2 threads) is busy with the startup probe or a report rebuild, the
player opens Stutter Doctor (no session running), so `loadSaved()` is queued. The player presses Clear (always enabled). The
load was queued first, so it runs first and sets `saved = <old summary>`. Then the clear task empties the file. The screen
shows the deleted summary ("Showing the last saved summary") for the rest of the game run, and Copy summary copies it.

**Evidence**
- `client/stutter/StutterService.java:328-331`: `saved = latest == null ? null : new Analysis(null, latest, ...)` has no generation check.
- `client/stutter/StutterService.java:134-144`: `clear()` sets `saved = null`, `savedState = Saved.DONE`, `generation++`, then queues `store().clear()` behind the load.
- Compare `:209` and `:286`, where saves do check `gen == generation`.
- `client/ui/StutterScreen.java:98`: the Clear button has no `active` gate.

**Fix:** capture `generation` when `loadSaved()` queues, and assign `saved` only if it still matches.

**Test:** give StutterService an injectable executor. Queue the load, call `clear()`, run the tasks in order, and assert `saved == null`.

---

## SD-5 (LOW, CONFIRMED for the NPE; hand-edited file only): Copy summary throws on `"causes": {"unknown": null}`

**Scenario:** a saved session in `stutter.json` has at least one spike and `"causes": {"unknown": null}`. The player opens
Stutter Doctor and presses Copy summary. `getOrDefault` returns the stored null, and unboxing it to `double` throws inside the
click handler. Whether 26.x turns that into a crash report needs a test. ws-s self-review L12 claims a hand-edited file
"with nulls ... reads safely".

**Evidence**
- `core/stutter/StutterSummary.java:61`: `double unexplained = r.causes().getOrDefault(Attributor.UNKNOWN, causes.isEmpty() ? 1.0 : 0.0);`
- `core/stutter/StutterReport.java:27`: `causes = causes == null ? Map.of() : causes;` keeps null values.

**Fix:** drop null values from `causes` and `tags` in `StutterReport`'s compact constructor, or read `unknown` null-safely.

**Test (`StutterStoreTest`):** write a session with `spikes.minor = 1` and `causes {"unknown": null}`, load it, and call
`StutterSummary.text(latest, List.of())`. It throws an NPE today.

---

## PF-5 (LOW, NEEDS A TEST): "My settings" silently drops values outside the share table's ranges

**Scenario:** a strong PC runs Distant Horizons with an LOD radius above 512, if DH allows it (the research says DH's bounds
are runtime API values).
1. The first Profiles open saves "My settings" without that key, because 1024 doesn't encode in the v1 table.
2. Switching to a template sets the radius (256 on tier 5, capped lower on tiers 1-3).
3. Switching back to My settings leaves the template's value, and Preview says nothing about the missing key. Only History
   Undo restores it.

**Evidence**
- `client/profile/ProfileService.java:506-514`: `managed()` keeps a value only `if (value != null && key.encode(value) != null)`.
- `core/profile/ShareKeys.java:137`: `new Key(22, "dh.client.advanced.graphics.quality.lodChunkRenderDistanceRadius", Kind.INT, 32, 512, List.of(), true)`.
- `docs/research/v0.4/profiles.md:327`: "(static 32..512, then DH's own API min/max)". `docs/research/v0.2/dh-iris.md:122`
  says "read at runtime, don't hardcode". The runtime bound wasn't implemented.

**To confirm:** read DH's `getMaxValue()` for `lodChunkRenderDistanceRadius` in a real game. If it's above 512, this is live.

**Fix:** save "My settings" from the raw managed snapshot (range-check only when encoding a share code), or at least add a
Preview note for keys the baseline couldn't hold.

**Test:** a baseline built from a snapshot with radius "1024" holds the key. Today it's dropped.

---

## SD-6 (LOW, NEEDS A TEST): under non-generational Shenandoah the live set sums every memory pool

**Scenario:** a player on `-XX:+UseShenandoahGC` (single heap pool "Shenandoah") with a modded game (hundreds of MB of
Metaspace). Shenandoah cycle notifications carry MAJOR. `oldGenerationUsed()` finds no "Old"/"Tenured" pool and sums every
pool in `GcInfo.getMemoryUsageAfterGc()`, which per its Javadoc covers all memory pools, non-heap included. The live-set
percentage is inflated by Metaspace + CodeHeap (about +10-15 points on a 4 GB heap). That feeds `liveSetPercentAtLeast: 75`
in `ram-stutter-gc-heap` (`knowledge.json:922`).

**Evidence**
- `client/stutter/GcListener.java:83`: `long used = (flags & GcKind.MAJOR) != 0 ? oldGenerationUsed(gc.getMemoryUsageAfterGc()) : 0;`
- `client/stutter/GcListener.java:93-106`: `// The old generation's pool when there is one, else every pool (Shenandoah has a single one).` / `return found ? old : all;`
- `core/stutter/GcKind.java:45-46`: MAJOR for Shenandoah cycles.
- `core/stutter/StutterAnalyzer.java:271-275`: `liveSet = 100.0 * live.get(live.size() / 2) / (in.heapMaxMb() * 1024.0 * 1024.0);`

**To confirm:** log the map's keys once on a real Shenandoah run.

**Fix:** sum only heap pools (`ManagementFactory.getMemoryPoolMXBeans()` with `getType() == MemoryType.HEAP`, names captured
once when the listener starts).

**Test:** `oldGenerationUsed({"Shenandoah": 1 GB, "Metaspace": 400 MB, "CodeHeap 'profiled nmethods'": 100 MB})` should be
1 GB. Today it is 1.5 GB.

---

## Latent items (not counted as player bugs today)

- **Share codes and future enum values (SPEC-mandated).** `core/profile/ShareKeys.java:13-15` says later versions add
  "values at the end of an enum ... so older decoders can skip what they don't know". But `core/profile/ShareCode.java:174-176`
  rejects the *whole* code on an out-of-range known key (`Reason.OUT_OF_RANGE`), as SPEC 4 requires. If v0.5 appends an enum
  value or widens a range (as PIXEL_ART was appended during v0.4), every 0.4.0 player gets "That code has a setting RigTune
  can't accept" for any code using it. Rule for future versions: never extend an existing key; add a new key index. Fix the
  ShareKeys comment and pin each key's `maxWire()` in `ShareKeysTest`.
- **The GPU probe's `"unknown"` placeholder counts as a real GPU.** `client/probe/HardwareProbe.java:94-109` falls back to
  `"unknown"` for name/vendor/driver. `core/awareness/ChangeDetector.java:42` treats any non-blank renderer as known, so an
  `"unknown"` probe after a good one would raise a false "Your GPU changed" (and a second one after the commit). This
  contradicts the class comment (`ChangeDetector.java:15-16`, "an empty probe value ... is no change"). No reachable
  trigger was found: the probe runs after `CLIENT_STARTED`, and the power-edge rescan hops to the render thread. Fix:
  `Fingerprint.of` maps `"unknown"` to `""`.

## Checked and OK (summary)

- **Share codes and names:** the full encode/decode round trip at every boundary (INT10 260 and "match display" 26,
  QUARTER rounding, enum bounds, local-only keys dropped and counted, duplicate and unknown indices, varint canonicality,
  base64 spare bits, UTF-8 name cuts); name sanitising; imported text reaching the UI through SafeLiteral/Texts.
- **profiles.json and switching:** corrupt, newer and hand-edited files; rename/delete of the active profile; the
  EffectiveSettings A→B→A path; ActiveProfile voiding on Undo; refusals (benchmark running, downloading, not ready);
  StateStore locking between the power and render threads; the offer compare-and-set.
- **Stutter Doctor:** ring and slot maths at wrap; the candidate/`detect()` spike boundary; phase-word encoding;
  wrap-safe nanoTime comparisons; the post-fix-9 benchmark end/restart flow (in place, benchmark world, Esc, throttle, a
  throwing `begin`); ordering of the Clear/CLIENT_STOPPING io chain; singular/plural; shares capped at 100 %.
- **JVM:** JvmArgs size suffixes, last-wins and junk handling; classifier IGNORED/OVERRIDDEN/Aikar/typed logic; the OpenJ9
  fallback; JvmProbe timeout and successor guard; JvmScreen rebuilds.
- **Server-aware:** ServerCap's W-H1 arithmetic at every T/C/L ordering; JOIN vs login-mixin ordering; "was N"; kind
  detection (Open-to-LAN host = singleplayer); IPv6, case and default-port keys; salt replacement and pruning; `rebuild()`
  always runs on the render thread.
- **Awareness and notices:** backend-switch and JW-2 prefix compare; RAM tolerance; WhatsNew ids vs Recommender; NoticeBoard
  ordering and index maths; stale action keys; action screens opening with the right parents; driver strings and rule text
  through SafeText.
- **Benchmark history:** comparability ⇔ equal context keys; median/MAD/floor maths and zero guards; the "since" run always
  among the shown points; ChangeWindow cursor/time fallbacks; the memo keyed on benchmarks.json and history.json stamps; the
  run context and the stale marker read DH/Iris the same way (`OptionalMods.dhRendering()`/`shadersInUse()` at
  `BenchmarkController.java:180` and `BenchmarkConditions.java:58-62`); notice sources poll only on screen init.
- **Accessibility:** RowFocus narration and Enter/Space; the focus frame's exact rectangle; RowList's 26.2-only narration
  block; `initialFocus` clamping and null paths; Palette's mapping (identity when off).
