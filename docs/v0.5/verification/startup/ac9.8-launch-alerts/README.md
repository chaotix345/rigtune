# AC9.8: C18's launch-time trend on real launches (WS-W2)

SPEC 9, AC9.8: "several launches with no change raise no notice under the estimated floor; a launch after adding a batch of
mods raises it with the right magnitude and the mod-count cause". Review decisions H1 (a slow streak is one regression)
and M2 + RW-19 (the player's real launch series, the crash-report setup left out) are recorded here too.

## Setup
- This PC: Windows 11, Ryzen 7 7800X3D, Radeon RX 7800 XT, 31 GB; Windows' performance counters switched off (2L), so
  vanilla's crash-report setup (`CrashReport.preload`) is slow and bimodal here.
- A production 26.2 client launched by `./gradlew :26.2:runProductionSmoke -PextraModsDir=<set>` on the worktree's own dev
  run dir (`versions/26.2/run`), never the player's instance; the player's mods are read-only copies from
  `%APPDATA%/ModrinthApp/profiles/Fabric 26.2/mods` into the scratch dir. The smoke run opens RigTuneScreen
  (`smoke-rigtune-p1`) and Tools (`smoke-tools`) and quits. Each series ran under the game-test lock
  (`launches.sh`, `wait-and-run.sh`, `runE.sh` here), one client at a time.
- Mod sets (`mods-*.txt`): **base** = fabric-api, Sodium, Mod Menu (7 mods with RigTune, its game-test jar and the
  game-test API); **batch** = base + 21 light mods of the player's (28 mods); **heavy** = batch + 18 more of the player's
  (46 mods: Iris, C2ME, Carpet and two addons, Litematica and its printer, REI, …). Distant Horizons and Xaero's maps were
  left out: they deadlock the game-test harness at world exit.
- Launch time is JVM start to the first title screen (RigTune's `StartupTimes`); the log line gives it with the
  crash-report setup and the assessment (`launch-lines.txt`).

## When each run happened (local time, 2026-09-28, AEST)
| run | when | build | what |
|---|---|---|---|
| A | 04:06-04:13 | branch before C18's code (0.4's recorder) | 7 unchanged launches (base). The coordinator's note: before the player's session; PROGRESS records one Modrinth App client process (mostly AFK) from 09-27 14:35 to 09-28 09:46, so an idle client was running. Kept as the code-deciding run |
| B | 11:04-11:08 | 78ff8fd2..4cb7a26b (C18 before the review) | 2 unchanged (base), then the batch twice. The player's client had exited (the lock was released ~11:00) |
| C | 11:20-11:22 | same | the heavy set twice |
| D | 11:34-11:35 | same | the heavy set once, with the first-run guide dismissed in the run dir's awareness.json (so the notice line shows C18) |
| E | 12:21-12:33 | 66c78c13 (the final trend: RW-19 and the streak rule) | a fresh history (A-D's file moved aside): 8 unchanged (base), the heavy set, then the Got it that the notice's button writes (its key into acknowledgedStartupRegressions), then the heavy set twice more |

## Results
**Run A (code-deciding).** 10.4-12.0 s (median 11.2 s, ±7.4 %, as la §1.3's ±7.5 %). With the planned rule, launches 6 and 7
are in line under floors of 18.7 % and 14.0 % (the MAD of these launches, above the 10 % minimum). MIN_RUNS 5 and the
≥ 10 % floor were kept.

**Runs A-D replayed with the final trend** (`replay-runs-A-D.tsv`; these runs carry no `preloadMs`, so it compares raw):
| launches | mods | launch time | assessment |
|---|---|---|---|
| 1-5 | 7 | 11.8, 11.2, 10.5, 12.0, 10.4 s | too few |
| 6-9 (unchanged) | 7 | 11.6, 10.6, 11.2, 9.6 s | IN_LINE: +3.2, -7.0, +0.4, -14.1 % (floors 18.7, 14.0, 16.2, 15.9 %) |
| 10-11 (the batch) | 28 | 11.5, 10.5 s | IN_LINE: +2.5 % (mod count changed), -6.7 %: 21 light mods added no measurable time |
| 12 (heavy) | 46 | 21.2 s | **SLOWER +94.7 %** vs 10.9 s (floor 12.6 %), MOD_COUNT: "Launch time 95% higher than usual (21.2 s vs your usual ~10.9 s)" / "May be related to your mod set changing (28 → 46 mods) since your last launch" |
| 13, 14 (heavy) | 46 | 15.5, 23.7 s | SLOWER +42.3 %, +108.7 %: the same streak (launch 12's key), "Slower for your last 2/3 launches; may be related to your mod set changing (28 → 46 mods) before the first of them" |

The live runs B-D (the pre-review build) logged the same kinds for 8-14 (each slow launch then had its own key); run C's
Tools screenshot showed the two lines for launch 12, run D's notice line showed "Launch time 109% higher than usual …".

**Run E, the final code, live** (`startup-times-run-E.json`, `replay-run-E.tsv`, `launch-lines.txt`):
| launch | mods | launch time (crash-report setup) | assessment (crash-report setup left out) | on screen |
|---|---|---|---|---|
| 1-5 | 7 | 17.6 (8.1), 12.4 (2.9), 11.2 (3.1), 9.4 (1.7), 10.6 (2.5) s | too few | |
| 6 | 7 | 11.7 (3.8) s | IN_LINE -2.3 % vs 8.1 s (floor 13.0 %) | no C18 notice |
| 7 | 7 | 9.5 (2.7) s | IMPROVEMENT -15.0 % (floor 10.0 %): never shown | no C18 notice |
| 8 | 7 | 10.8 (3.0) s | IN_LINE -3.6 % (floor 11.9 %) | no C18 notice |
| 9 | 46 | 14.8 (3.1) s | **SLOWER +46.8 %** vs 8.0 s (floor 10.0 %), MOD_COUNT | notice line: "Launch time 47% higher than usual, …" with Tools… and Got it; Tools: "Launch time 47% higher than usual, not counting Minecraft's crash-report setup (11.7 s vs your usual ~8.0 s)" and "May be related to your mod set changing (7 → 46 mods) since your last launch", startup line "Last launch 14.8 s · median of the last 8: 11.0 s" |
| (Got it) | | | `startup.regression.2026-09-28T02:30:53Z` into acknowledgedStartupRegressions | |
| 10, 11 | 46 | 14.4 (3.2), 14.2 (2.7) s | SLOWER +38.0 %, +42.5 %, the same streak (launch 9's key) | **no notice** on the line; Tools: "Slower for your last 3 launches; may be related to your mod set changing (7 → 46 mods) before the first of them" |

The same launches compared raw (`replay-run-E-raw.tsv`): floors 23.5-36.9 % (launch 1's 8.1 s cold setup widens them);
launch 9 is still SLOWER (+34.5 %, floor 27.6 %), launches 10 and 11 are IN_LINE (+27.7 %, +24.1 %).

Run E's launch 9 screenshot showed the numbers clipped off the notice line behind "not counting Minecraft's crash-report
setup", so the message now puts the numbers first ("Launch time 47% higher than usual (11.7 s vs your usual ~8.0 s, not
counting Minecraft's crash-report setup)"); the unit tests pin it.

**The player's real launches (M2, RW-19)**: 44 launches of the player's own instance, 07-09 to 09-27, from their logs
(the realworld agent's `launch-times.tsv`, committed as `src/test/resources/startup/real-launch-times-2026-09-28.tsv`;
`StartupTrendTest.thePlayersRealLaunches`). Raw launch-to-title: **0 SLOWER** of 39 assessed, although launches after the
09-20 mod updates were 34-39 % slower (floors 12-64 %). With the crash-report setup left out: **5 SLOWER** (09-20-2 +27.6 %,
09-21 +25.8 %, 09-22 +26.7 %, 09-23 +34.3 %, 09-24 +38.9 %), all in that real slowdown, **none outside it**. With the
streak rule that is **two notices**, not one: 09-20-3 and 09-20-4 (warm launches, in line and faster) end the first
streak, so 09-21 starts a second one that 09-22 to 09-24 continue. No retune was needed (no false SLOWER).

## Reading
- Unchanged launches raised no notice in any series: 4 raw (A/B) and 3 with the setup left out (E), plus the player's 39
  real assessments outside the 09-20..09-24 stretch.
- Adding mods raised it with the right magnitude and the mod-count cause, once the added mods took measurable time
  (the light batch didn't: +2.5 %, in line, which is also right).
- One Got it covered the rest of the slow streak.
- MIN_RUNS 5 and the ≥ 10 % floor stay estimates (SPEC 9): one PC's launches and one player's instance, both with the
  performance counters off.
