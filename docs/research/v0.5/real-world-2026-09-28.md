# Real-world sessions 2026-09-27 evening → 2026-09-28 (RigTune 0.4.0): read-only analysis

This follows `docs/research/v0.5/real-world-2026-09-27.md` (RW-1..RW-16, committed as f60aab44). The instance is the
same one, the user's Modrinth App instance `Fabric 26.2` (7800X3D, RX 7800 XT, 2560x1440 at 180 Hz, 52 jars). Times are
local (UTC+10); JSON times are UTC.

**Inputs.** Read-only copies are in this folder. Nothing under %APPDATA%/ModrinthApp was written, renamed or deleted, and
app.db wasn't needed or opened.
- `logs/2026-09-27-3.log` (gunzipped from the `.gz` next to it), `logs/latest.log`, `logs/launcher_log.txt` (the
  app's copy of the game's output), and `logs/2026-09-27-2.log` (session 2 of the earlier analysis, for reference).
- `config/rigtune/*`. There is no pending.json, unfinished-groups.json or profiles.json in the instance.
- `config/dynamic_fps.json`, `options.txt`, `mods-listing.txt` (names, sizes, mtimes), `crash-reports-listing.txt` (no
  new report since 2026-09-24) and `instance-root-listing.txt`.

**Other read-only checks:**
- Windows' System log for sleep, wake and shutdown events: none between 09-27 14:00 and 09-28 10:00.
- javap of vanilla 26.2's `FramerateLimitTracker` (loom cache).
- Every earlier launch log, back to 2026-07-09, for the launch-time series in §4.1.

**Code references** are for `feat/v0.5.0` at c59b8b93 (WS-S merged); where 0.4.0 differs, that is stated. No repo
change, no git write, no game run.

## 0. The timeline correction first

The brief described two sessions, about 23:00–00:25 on 09-27 and 09:30–09:46 on 09-28. The files show something else:
**one game process ran from 09-27 14:35:47 to 09-28 09:46:38.**
- `launcher_log.txt:1` says "started at 2026-09-27 14:35:45", and its last line is "Process exited … exit code: 0".
- `2026-09-27-3.log:1` launches at 14:35:47. `latest.log` begins at 09:45:42 with "Saving and pausing game", not with a
  launch; log4j only rolled the file over at the first log line after midnight.
- No log line at all between **16:12:11 and 09:45:42** (`launcher_log.txt:1425-1426`; `2026-09-27-3.log:1422` is the
  last line of the 09-27 file).

**What the process did:**
- **Active play, 14:38:13–~16:12:** world THE ONE, RD 12. A carpet fake player "IronFarm" was spawned at 15:41:59
  (`2026-09-27-3.log:1369`), so this looks like an AFK iron farm.
- **In-world idle, ~16:12 → 09:45:3x.** The PC stayed awake (no sleep or wake events). The game stayed focused, in the
  world, with no menu open. It rendered at ~7.5 FPS. This fits vanilla's AFK limiter: `options.txt` has
  `inactivityFpsLimit:"afk"`, and 26.2's `FramerateLimitTracker` drops to 30 FPS after 60 s and to 10 FPS after 600 s
  without input (javap constants `AFK_LIMIT 30`, `LONG_AFK_LIMIT 10`, `LONG_AFK_THRESHOLD_MS 600000`). Dynamic FPS is
  also installed with an empty, default config; which of the two throttled it is UNVERIFIED.
- **Return, 09-28 09:45:42–09:46:38:** pause, Save and Quit, re-open THE ONE for 30 s, quit, exit.

**Evidence of no in-game activity 23:00–00:25 or 09:30–09:45:**
- **The capture's frame histogram** (`stutter.json:477-498`) has only ~52 min at the 170-FPS cap (526,868 frames in
  the 4.2–8.3 ms bucket) and ~22 min of transitional 30/10-FPS frames. That fits 14:38–16:12 alone, minus 27.5 min of
  menus and pauses, and leaves no room for another ~85 min of active play.
- **At 09:45:33–09:45:36** the worst spikes still sit on the 133 ms AFK baseline (`stutter.json:539-540`). So the
  first game input of the morning came at about 09:45:40.
- If the user was at the PC during those windows, they didn't touch the game (UNVERIFIED).

## Findings (RW-17 on)

| id | sev | what happened / could happen | evidence (copies) | root cause | proposed fix | owner | test |
|---|---|---|---|---|---|---|---|
| RW-17 | MEDIUM | The Stutter Doctor recorded **17.4 h of AFK-throttled idling as gameplay**: session 14:38:15 → 09:45:51, `gameplaySeconds` 67,209, 1,023,362 frames, "avg 15.2 FPS / 1 % low 7.3 FPS", 225 spikes (26 severe, 5 freeze), 6 of the 10 worst measured against a 133 ms AFK baseline. It was saved "(OK)" and is now in stutter.json's history. Under v0.5's SD-2 fix it would read "over the last ~4 h 50 min" (the 131,072-frame ring at ~7.5 FPS, all idle): honest-looking but meaningless. A C20 fix being measured would take it as an "after" session and compare hitch and lost-ms rates diluted ~17×, giving a false "less" verdict (§4.2). | `stutter.json:467-476` (start, gameplay, avg, low), `:477-498` (474,588 frames at 100–250 ms, 62,735 s), `:539-540`; `latest.log:77` ("225 spikes in 67209 s of gameplay") | `client/stutter/StutterHooks.java:77` excludes only an open screen or an unfocused window. A focused window, in the world, throttled by vanilla's AFK limiter (or Dynamic FPS) counts as gameplay, and `StutterMonitor.Capture.frame` (`StutterMonitor.java:50`) records every other frame. Unchanged on `feat/v0.5.0`. | Also exclude frames while `minecraft.getFramerateLimitTracker().getThrottleReason() != NONE` (SHORT_AFK, LONG_AFK, WINDOW_ICONIFIED, OUT_OF_LEVEL_MENU), and while Dynamic FPS throttles; `BenchmarkController` already samples both (`:524`, the `DYNAMIC_FPS` check). Store the excluded idle time (e.g. `idleSeconds`) so the summary can say "3 h idle not counted". C20's `FixConditions` should refuse a session with more idle than gameplay. | WS-S (C20 consumes it) | StutterHooks/StutterMonitor unit with a throttle-reason seam: 10 min of LONG_AFK frames add 0 s of gameplay and no spikes; a game test that idles 70 s in a world (SHORT_AFK) and checks the session's gameplay seconds |
| RW-18 | LOW | Rejoining for 30 s saved a 13.6 s session (`enoughData: false`, 1 spike) that is now the one StutterScreen shows, hiding the previous capture (here RW-17's; normally a real play session). | `stutter.json:598-607`; `latest.log:308` | `StutterStore.worthSaving` (`StutterStore.java:32-33`) drops short sessions only around a benchmark (StutterStoreTest:183 asserts short normal sessions are saved), and the screen shows the newest saved summary (`StutterService.java:357`). | Keep saving it, but show the newest session with `enoughData`, and list the short one as "a short session (14 s) since". | WS-S | StutterService/StutterStore unit: [long OK, short not-enough] → the screen shows the long one plus the short-session line |
| RW-19 | MEDIUM (v0.5 design, AC9.8) | C18's floor, run over **44 real launches of this instance** (07-09 → 09-27), never fires: 0 SLOWER of 39 assessed. That includes a real **+34–39 % slowdown after the 09-20 mod updates** (09-23: 33.4 s vs a baseline median of 24.9; 09-24: 35.8 vs 25.8). The floor stayed at 40–51 % because RW-16's vanilla wait makes launch time bimodal: 0–2 s when WMI is warm, 5–9 s cold. Subtract the measured wait and the same series flags 09-20 to 09-24 (5 SLOWER), and no launch with an unchanged mod count fires outside that stretch. | `launch-times.tsv`, `c18-simulation.txt` (this folder); `startup-times.json` (32,284 / 27,870 / 32,059 ms, same modSetHash) | The launch-time value C18 compares mixes a large, bimodal cost outside Minecraft's and the mods' control (vanilla `CrashReport.preload` on this PC) into the "mods got slower" signal. The MAD floor then widens to hide both. | Store `preloadMs` (2L's `PreloadTimer`, already on `feat/v0.5.0`) as an optional field of each startup-times run. `StartupTrend` compares launch-to-title minus `preloadMs` when both the latest and the baseline runs have it; otherwise today's value. Keep `MIN_RUNS` 5. | WS-W2 (WS-W stores the field) | `StartupTrendTest` with this series (the TSV as a fixture): raw → 0 SLOWER; preload-subtracted → the 09-20 to 09-24 stretch flagged, no other flag. AC9.8's dev-PC run should record `preloadMs` per launch. |
| RW-20 | LOW | v0.5's RW-1 fix (4f) applies to future helper runs only. This instance's history.json still says "Disabled fabric-26.2.jar" / "Added DistantHorizons-3.3.2-…jar": **Applied**, although the Modrinth App put that jar there. After upgrading to 0.5, History keeps claiming it. Undo is safe: 4c skips it under LAUNCHER, and RW-14 skips the disable under RIGTUNE. | `history.json` (unchanged since 09-27 11:08:48); `last-apply.json` (still that run: both SKIPPED_ALREADY_DONE, no `resultPath`) | No migration corrects already-journaled SKIPPED_ALREADY_DONE claims. | At the first 0.5 start, while `last-apply.json` is still the run that produced them: an APPLIED file change whose op result there is SKIPPED_ALREADY_DONE, with no `resultPath` and no `unfinished-groups.json` record, becomes ABANDONED "installed another way". Once, under the lock, only for that file's run. | WS-H | Unit over this folder's history.json + last-apply.json: the two DH changes turn ABANDONED and the 15 real ones stay APPLIED; idempotent on a second start |

No other divergence was found. There is no RigTune WARN or ERROR line in either log, and no RigTune stack frame
(`grep chaotix345` → 0 in both). The FastQuit WARNs are RW-12's known race again (§3.5).

## 1. Session A: launch 09-27 14:35:47, active until ~16:12, then idle in the world

| item | what happened | evidence |
|---|---|---|
| RigTune version / launcher | 0.4.0+mc26.2 (the jar from 09-27 10:15:17); "RigTune: launcher Modrinth App" | `2026-09-27-3.log:159, 600`; `mods-listing.txt` |
| JVM / hardware | Java 25.0.3 Azul, G1 (Java's choice), 0 notes; the same hardware line as 09-27 | `:598, 601` |
| vanilla OSHI wait (RW-16) | 14:35:56 → 14:36:02, 6 s on the main thread | `:282-290` |
| launch to title | **32,059 ms** (52 mods); footprint: preLaunch + init 71.6 ms (CPU 46.9), client start 13.3 ms, RigTune threads 156.3 ms CPU in 5 s | `:643-644`; `startup-times.json` run 3 |
| preLaunch | No WARN (no pending.json). No leftover, applied or failed toast: `lastShownApply` was already the 11:08 run, shown at 11:30 | `rigtune.json:3` |
| notices | **Benchmark stale** notice shown and **dismissed at 14:36:26**, 9 s after the title screen. Correct under 0.4.0 (`BenchmarkStaleNoticeSource`): the 10:48 benchmark ran with DH rendering off at RD 31/32, and the player now plays with DH on at RD 12 (RW-9's context). No what's-new (awareness r16 = rules r16). | `awareness.json:3-5` (`benchmark.stale.2026-09-27T00:48:02Z-5e1e`) |
| goal | Changed **BALANCED → PERFORMANCE** at 14:37:55, then no Apply | `rigtune.json:2` (mtime 14:37:55) |
| applied / staged / undone / held | **Nothing.** history.json, last-apply.json and helper.log are byte-identical to 09-27 11:08:48. No pending.json, no unfinished-groups.json, no helper at exit. | copies; `latest.log` has no "Started the RigTune apply helper" |
| world | THE ONE at RD 12 / SD 12 (14:38:13); shaders on at 14:42:02 then off at 14:42:07 (no compile error this time); X-ray resource pack reloads at 15:04:00 and 15:04:50; default audio device → AirPods at 15:09:50 | `:695, 702, 944-945, 967, 1057, 1125, 1184` |
| stutter | Monitor capture 14:38:15 → 09-28 09:45:50: RW-17 (causes tick 0.21, unknown 0.79, gc 0.0; tags chunksLoading 15, movingFast 30; hitches 96; advice []). The worst **2,572 ms spike at 14:42:04 is labelled `tick:medium`**, 2 s after the shader toggle: RW-11 again, and v0.5's `settingsChanged` tag (AC2S.13) would cover it. 807 / 874 ms spikes at 15:05:18/20 have no cause, 28–30 s after the 15:04:50 resource reload with a pause menu (15:04:56) in between; v0.5's 10 s wall-clock window wouldn't reach them (the link to the reload is UNVERIFIED). | `stutter.json:467-595`; `latest.log:77` |
| benchmarks | None (benchmarks.json unchanged; no benchmark world opened) | copies |
| RigTune WARN/ERROR | none | grep |
| RW-1 / RW-2 state | Unchanged. The DH group is still APPLIED in history.json. DistantHorizons-3.3.2-26.2-fabric-neoforge.jar (27,702,766 B, app-installed 09-27 10:33:02) is in mods; no fabric-26.2.jar(.disabled); **no mods/update/**, and DH's own updater says "already up to date" (14:36:17). Entity Culling's 1.11.1.disabled + 1.11.2 pair is unchanged. No Undo was used. | `mods-listing.txt`; `2026-09-27-3.log:639-640` |

## 2. Session B: 09-28 09:45:42–09:46:38, the return and a 30 s re-join (same process)

| item | what happened | evidence |
|---|---|---|
| version / launcher | Same process: 0.4.0, Modrinth App; no new launch, so no startup-times entry | `latest.log:1` |
| sequence | 09:45:42 and 09:45:47 pause → 09:45:48 Save and Quit → capture off; session saved "(OK): 225 spikes in 67209 s of gameplay" (RW-17) → 09:46:03 re-open THE ONE → 09:46:06 capture on → 09:46:34 Save and Quit → saved "(OK): 1 spikes in 14 s" (RW-18) → 09:46:37 Stopping! | `latest.log:1-2, 77, 79-82, 183, 308, 311` |
| notices | none logged; a mid-process world re-join shows no startup toasts | – |
| applied / staged / undone / held | nothing; no helper at exit | as §1 |
| stutter | 30.3 s session, 13.6 s gameplay, 2,283 frames, avg 168.2 / 1 % low 117.6, 1 spike (20.4 ms, `gc:high`), `enoughData: false` | `stutter.json:598-607` |
| benchmarks / start-up | none / none | – |
| RigTune WARN/ERROR | none | grep |
| RW-1 / RW-2 state | unchanged | as §1 |

## 3. Expected (0.4.0) vs actual, and what v0.5 predicts

1. **Start-up and notices** match 0.4.0's design: no WARN with nothing staged, no second applied toast, and the stale
   notice is correct. The earlier RW-3 problems are gone because the user fixed the instance.
   - v0.5: in a LAUNCHER instance, 4g's repair notice would appear on this start. It would find the Entity Culling pair
     and 7 added jars exactly as AC4g.1 predicts, since the files are unchanged. 4c would make the "Imported from 0.1"
     entry's file changes non-undoable.
2. **RW-1 / RW-2:** nothing moved. v0.5 covers the future (4f) and Undo (4c, RW-14); the stale APPLIED claim stays
   (RW-20).
3. **Stutter Doctor:**
   - 0.4.0's design excludes menus and unfocused windows but not a focused, AFK-throttled game (RW-17).
   - v0.5 as specified (SD-1, SD-2, RW-11) doesn't change that: SD-2 would label the numbers as the last ~4 h 50 min of
     idling, and SD-1's covered-spike shares would be computed over idle spikes.
   - RW-11's tag would fix the 14:42 misreading.
4. **Launch time:**
   - 32.3 / 27.9 / 32.1 s over three 0.4.0 launches, all with the same modSetHash. That is 3 < `MIN_RUNS` 5, so C18
     would say TOO_FEW. That is correct, though the spread (13.7 %) is already above the ±7.5 % estimate.
   - RW-16's wait was 6 s this time (§4.1).
5. **FastQuit:** "THE ONE was not registered" at 09:45:48 and 09:46:35, "Waiting for THE ONE" at 09:46:03, a false
   "allowing a world to load while another is being saved", and at quit "Waiting for "THE ONE" & "THE ONE"".
   - This is RW-12's race exactly: the server finished first, and the stale entries accumulate within the process.
     Harmless, and not RigTune.
6. **Not RigTune:** ModernFix's "Total time to load game and open world was 153.0 s" at 09:46:06 (its own timer after
   a 19-h process); the AirPods audio-device switches; `[ALSOFT] Failed to get padding: 0x88890004` at exit.

## 4. Evidence for AC9.8, C20 and C09

### 4.1 AC9.8 (launch-time floor): a real 44-launch series

`launch-times.tsv` gives, per launch log since 07-09:
- JVM start to title. JVM start = ModernFix's bootstrap time minus its "s after launch". The title is the first "Creating
  pipeline for dimension minecraft:overworld" line, which matched RigTune's "Launch to title" to the second on all
  three 0.4.0 launches.
- Fabric's mod count.
- The vanilla OSHI wait.

`c18-simulation.txt` applies SPEC §9's rule: baseline = the newest ≤ 10 earlier runs, `MIN_RUNS` 5, floor =
`2 × max(0.05, 1.4826·MAD/median) × 100` (`BenchmarkTrend.noiseFloorPercent` on `feat/v0.5.0`, `:220-225`).

| compared value | floor range (median) | SLOWER |
|---|---|---|
| launch to title (what C18 plans) | 12–64 % (~28 %) | **0 of 39**, including the +34–39 % stretch after the 09-20 updates (the 09-21 launch: +49.0 % vs a 49.5 % floor) |
| minus the vanilla OSHI wait | 12–54 % | **5**: 09-20-2, 09-21, 09-22, 09-23, 09-24, all in the real slowdown after the 09-20 22:15 mod updates. None elsewhere. |

**Why:** launches cluster at a 5–9 s cold wait versus a 0–2 s warm one, which inflates the MAD. RW-19 proposes
subtracting the measured `preloadMs`.

**Limits of the data:**
- Timestamps are whole seconds.
- Fabric's count includes nested mods, so it isn't RigTune's 52.
- No mod-set hash exists before 0.4.0, so the simulation's cause lines are only "NONE" (count unchanged); the 09-20
  updates changed the jars (mtimes), which would be MOD_SET.

**For the AC9.8 dev-PC run:** record `preloadMs` per launch. On any PC with this condition, "several unchanged launches
raise no notice" passes trivially, because the floor is too wide to catch anything.

### 4.2 C20 (Stutter Doctor causes and one-click fixes)

- **The FixGate floor would pass the RW-17 capture:** it is a monitor session, 96 hitches ≥ 8, and 67,209 s ≥ 300 s.
  Only the advice layer (advice []) prevented an offer.
- **Worse, the comparison:** a fix being measured would count this session as an "after" session.
  - `FixConditions` compares MC version, mod set, heap, window size and fullscreen, and the managed keys, and all of
    those are the same.
  - Its hitch rate is 96 hitches in 67,209 s, ≈ 0.09/min, against ~1–3/min in real play here. That yields a false
    "less".
  - C20 needs RW-17, or at least a condition that refuses sessions dominated by throttled or idle time.
- **Causes:**
  - The biggest real spike (2,572 ms) was a shader toggle labelled `tick:medium`; v0.5's `settingsChanged` would tag
    it.
  - No `gc` claims in 19 h (gc 0.0, one explicit GC, live set 26 %).
  - `unknown 0.79` is dominated by idle spikes that no cause can claim.
  - Nothing here would have fired C20's `causeSpikesAtLeast` for a fixable cause.
- **Short sessions:** the 13.6 s re-join session is below C20's 120 s per-session minimum, so it's ignored as designed.

### 4.3 C09 (Measured Try It)

- **Throttling is realistic here.** `inactivityFpsLimit:"afk"` in options.txt, and Dynamic FPS with a default config.
  - C09 lifts `inactivityFpsLimit`, `maxFps` and `enableVsync` for its runs (SPEC §6).
  - 0.4.0's BenchmarkController cancels throttled runs (`BenchmarkController.java:524-545` on `feat/v0.5.0`).
  - The 09-27 benchmark's sweeps all logged "frame limit 260, NONE", so the lift works on this PC. AC6.16's dev-PC
    pairs should run with the player's real `inactivityFpsLimit` ("afk") to keep that true.
- **The player dismissed a re-measure prompt.** The only benchmark notice the player saw (stale, 14:36) was dismissed
  within seconds rather than re-run. That is one data point for C09's notice wording and its "Measure now" flow.
- **`maxFps` is fixed:** the player caps at `maxFps:170` (options.txt), and C09 refuses `vanilla.maxFps` as a triable
  key, as designed.

## 5. Files in this folder

- `real-world-2026-09-28.md`: this report.
- `logs/`, `config/`, `options.txt`, `mods-listing.txt`, `crash-reports-listing.txt`, `instance-root-listing.txt`: the
  read-only copies.
- `launch-times.tsv`: 44 launches with launch-to-title, the OSHI wait and Fabric's mod count.
- `c18-simulation.txt`: C18's floor simulated both ways (§4.1).
