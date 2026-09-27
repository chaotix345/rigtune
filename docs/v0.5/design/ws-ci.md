# WS-CI: rock-solid CI (v0.5 P0.1, SPEC 1) as landed

Branch `feat/v05-ci` (from `feat/v0.5.0` @ ab2c1947; `feat/v0.5.0` merged in again at b208c795, with the SPEC/PLAN
amendments of 054cc882). Research and evidence: docs/research/v0.5/ci-robustness.md (every `build.yml` failure since
v0.1 classified: 1 in 6 runs since the full v0.4 suite failed for a reason other than the code, 9 outages and 5 flakes).
Decisions: the r-ci row of docs/PROGRESS.md; SPEC 1 (AC1a.1-AC1g.4) with the plan-review amendments for ws-ci (SPEC-3/
PLAN-2 loopback multicast, PLAN-3 the dormant split, SPEC-4 E2E offline, SPEC-29 SIGQUIT to KnotClient, PLAN-21 the
streak's job set, PLAN-23 AC1c.3's scope). Proof runs and their evidence: docs/v0.5/verification/ci/README.md and
docs/v0.5/verification/footprint/README.md.

## What landed

| SPEC | Change | Files | Tests / proof |
|---|---|---|---|
| 1a | One "Resolve dependencies (network)" step per building job (`prefetchDependencies`: each node resolves every resolvable configuration; plus the leg's `downloadAssets`, fake-Modrinth catalog and jars; plus the released E2E jars in the java job), retried by `tools/ci/retry.sh` (3 tries, 30 s / 90 s). Every later Gradle step runs `--offline`. Unit, Python and client game tests run with no network but loopback: `tools/ci/offline.sh` (`sudo --preserve-env env PATH HOME unshare --net`, `ip link set lo up`, back to the runner's uid/gid with `setpriv`, the environment kept). In the namespace loopback also carries multicast (`ip link set lo multicast on`, `ip route add 224.0.0.0/4 dev lo`: nothing off the machine), so vanilla's LAN discovery works; every leg checks it first with `tools/ci/MulticastCheck.java` (one datagram to 224.0.2.60:4445, received on a `MulticastSocket(4445)` joined to the group, as vanilla's pinger and detector do) and that 1.1.1.1:443 stays unreachable. `prefetchDependencies` fails when any configuration doesn't resolve, so the retry covers it. Tests are never retried. | `.github/workflows/build.yml`, `build.gradle`, `tools/ci/offline.sh`, `tools/ci/retry.sh`, `tools/ci/MulticastCheck.java` | `tools/tests/test_ci_workflow.py` (AC1a.1; e2e.yml's rule waits for WS-E's file); proof (a) (AC1a.2); every leg's "Check loopback multicast" (AC1a.3) |
| 1b | `ModrinthFixture`, a BuildService next to `RulesFixtureServer`, runs the E2E's `FakeModrinth.java` as a single-file program on 127.0.0.1 with a free port (it serves until its stdin closes, in `close()`). `gametestModrinthCatalog` writes the node's catalog: the `productionGameTestMods` jars (fabric-api, Mod Menu, Sodium; real hashes) and one fixture version per other rules mod (ids from a hash of slug and node, a fixed date), requiring fabric-api unless `tools/gametest/modrinth-candidates.json` lists relations (Entity Culling incompatible with Sodium; Sodium Extra requiring Sodium). `runProductionClientGameTest` and the dev `runClientGameTest` get `-Drigtune.modrinth.baseUrl`. `FakeModrinth` answers `GET /v2/versions?ids=`. The request log is uploaded; `tools/ci/check_fake_modrinth.py` fails a leg without the four start-up lookups answered 200, with any answer other than 2xx or a 404 to a lookup of one project or file hash (the fake answers 400 when its own routing throws), or with a "Modrinth lookups failed; using offline data" line outside a Modrinth-off test. A fake that didn't start fails every run task of the build (never a missing URL, which would mean live Modrinth). PreviewGameTest's real preview waits 1200 ticks (was 6000). | `build.gradle`, `tools/e2e/java/…/FakeModrinth.java`, `tools/gametest/modrinth-candidates.json`, `tools/ci/check_fake_modrinth.py`, `PreviewGameTest` | `FakeModrinthTest.versionsByIdReturnsKnownVersionsOnly` (AC1b.1, red first); `test_ci_workflow.CheckFakeModrinthTests`; every leg (AC1b.2, AC1b.3) |
| 1c | `setup-gradle` in the java job; `main` and `feat/v0.5.0` write the Gradle cache, other refs read it. `loom_version=1.17.21`; `org.gradle.internal.repository.max.tentatives=6`, `initial.backoff=1000`. `actions/cache` for the three released jars (key = their sha256s) and the lavapipe `.deb`s (key = `$ImageOS-$ImageVersion`, `dpkg -i` on a hit). Every job of build.yml and release.yml on `ubuntu-24.04` with `setup-java` `25.0.3` (snapshot-canary.yml and update-rules.yml are frozen and left as they were, PLAN-23); each leg prints image, Mesa and CPU; the footprint JSON records `cpu`. | `build.yml`, `release.yml`, `gradle.properties` | `test_ci_workflow.StreakWorkflowsTests` (AC1c.3: build.yml, release.yml, e2e.yml once it exists); AC1c.2 below |
| 1d | Footprint budgets and the ratio gates (next section). | `tools/footprint-budgets.json`, `FootprintGameTest`, `FrameHookBudgetTest`, `FootprintBudgetsTest` | `FootprintBudgetsTest.timingLimitsFollowTheirRecordedRule` (AC1d.1), `theRatioGatesSitBetweenTheirCalibratedOneAndTwoTimes`; proof (b) (AC1d.3) |
| 1e | `GameTestNet.set(context, controller[, configDir], on)`: save the switch, `settingsChanged()`, wait for a new report instance (not the one from before the switch) with `online() == (on && modrinthAllowed)`; used by A11y, Awareness, BenchmarkHistory, Jvm, Profiles, ServerLimits, Stutter and UiGameTest's restore. BenchmarkHistoryGameTest checks and logs, before each of its 8 screenshots, that the offline report is on screen and the Apply label. UiGameTest's `waitForSaved` flushes `SettingsSaver` (10 s) and reads `settings.json` once. The game-test step times out at 15 min, its job at 35 (a cold cache plus a retry cycle). At 13 min a watcher sends SIGQUIT to the game's JVM only: of the processes named `java` (`pgrep -x`), the one whose whole `/proc/<pid>/cmdline` has the argument `net.fabricmc.loader.impl.launch.knot.KnotClient` (not Gradle's or the fake Modrinth's JVM, not the `xvfb-run` wrapper; KnotClient comes after a long classpath, so `pgrep -f` isn't used). At 14 min `tools/ci/offline.sh --timeout 14m` (GNU `timeout --kill-after=15s`, running as root inside the sudo, before `setpriv`) sends TERM to the run's process group (KILL 15 s later if Gradle's launcher hasn't exited); the step then waits up to 20 s for every java process to end and kills what is left, by 14:35 at worst, inside the step's 15 min (the step limit itself can't signal the root-owned sudo). `awaitApplyHelper`, a finalizer of `runProductionClientGameTest` (so after a failed run too), up to 60 s: where the OS shows command lines (Linux, CI) it waits for the helper process itself (ApplyHelper and this run's pending.json on its command line); on Windows, whose ProcessHandle shows none (checked on JDK 25.0.4), until the helper has deleted `pending.json` or, after 10 s (its 2 s settle plus its JVM start), `apply.lock` is free. ThreadSamplerTest: `stop()` bound 500 ms plus "returned while the worker was still stuck". | `GameTestNet.java` + 8 game-test classes, `build.yml`, `build.gradle`, `SettingsSaverTest`, `ThreadSamplerTest` | `GameTestSourcesTest` (AC1e.1, AC1e.2 unit part), `SettingsSaverTest.aSaveIsNotBlockedByBusyWorkerAndNetworkPools` (AC1e.1); hang proof (AC1e.3); local subset run (AC1e.4) |
| 1f | Root cause below. Both Modrinth HttpClients speak HTTP/1.1. A connection failure (no HTTP response: a reset, "Stream N cancelled", a timeout) drops the HttpClient; a failure of RigTune's own body handling (over the cap, a disk write: `BoundedHttp.Progress.failedInBody`) keeps it. A lookup that fails with an I/O error other than a timeout or an interrupt is sent once more, on the fresh client, with the same stall and deadline limits (downloads get no such retry). A request that failed before any response on a client another thread had dropped meanwhile is sent once more on the fresh one (not once a body has begun: a download's sink would already hold part of it). The lookup warnings print the cause chain (`LogSafe.error`, message and root cause capped separately, so the cause is never cut off). `-Drigtune.modrinth.baseUrl` counts only as https, or http on a loopback host (`localhost`, `127.x.x.x`, `[::1]`); anything else is ignored with one WARN naming the property, an accepted override is logged once. | `HttpModrinthClient`, `OnlineDataFetcher` | `HttpModrinthClientTest`: `aLookupThatFailsOnceIsRetriedOnAFreshClient`, `twoFailuresInARowFallBackToOfflineDataWithOneWarning` (AC1f.1), `theClientsSpeakHttp11`, `aTransportFailureStartsAFreshClientButAnHttpErrorDoesNot` (red without the fix), `jsonBodiesAreCapped` (the client stays), `aRequestOnAClientAnotherFailureJustDroppedIsSentOnTheFreshOne`, `aDownloadWhoseBodyHadStartedIsNotSentAgain`, `LogSafeTest.aLongMessageKeepsItsRootCause` (red first), `baseUrlPropertyTakesOnlyHttpsOrHttpOnThisMachine` (AC1f.2) |
| 1g | `tools/ci_streak.py`: lists `build.yml` runs on a branch (optionally one SHA), oldest first; a run counts when push or dispatch, attempt 1, every job it has green (none skipped), and ws-ci's minimum set present (java, python, gametest-matrix, rules-consistency, rules-v1-compat, a job for each of the 3 legs, a split leg's parts counting as the leg) plus any `--require` job (the RC streak: WS-E's E2E push jobs); a cancelled run or another event neither counts nor breaks; any other completed run breaks. `docs/v0.5/verification/ci-streak.md` gets each run's row (SHA, event, attempt, total and per-leg durations) and, from each leg's job log, every game-test class's wall time, the requests to the fake Modrinth, and `tickHookOnVsReference` with its twin. The dormant split: one flag in build.yml, `env: GAMETEST_PARTS: 1` (a dispatch's `gametest_parts` input overrides it for one run), goes to `tools/gametest_matrix.py --parts N`, which turns each leg into N jobs, each with a contiguous slice of fabric.mod.json's classes (the first class in part 1), named "…, part k/N", with `-part<k>` artifacts; build.gradle's `-PgametestClasses=A,B` keeps those classes; `TimedGameTests`, a Fabric language adapter every game-test entrypoint goes through, logs "Game-test class <Name> runTest returned in <ms> ms" (or "threw"; returning isn't passing: Fabric checks more after runTest); `awaitApplyHelper` (1e) is the wait between two parts run one after the other. FootprintGameTest stays second to last, before A11yGameTest, in either mode (test). | `tools/ci_streak.py`, `tools/gametest_matrix.py`, `build.yml`, `build.gradle`, `TimedGameTests.java` | `tools/tests/test_ci_streak.py` (AC1g.1), `test_gametest_matrix.py` (one part = the legs unchanged; two parts run every class once, first class in part 1; FootprintGameTest second to last in its part), `test_ci_workflow` (the part reaches Gradle and the artifact names); split proof (AC1g.4) |

Local: `./gradlew build` green on both nodes, 1855 unit tests each (1 skipped); `tools/tests` 357 (1 skipped: e2e.yml), `tools/e2e/tests` 216.
A local run of `:26.2:runProductionClientGameTest -PgametestClasses=RigTuneClientGameTest,BenchmarkGameTest` (under the
game-test lock) ran those two classes only and logged "Game-test class RigTuneClientGameTest passed in 45801 ms" and
"… BenchmarkGameTest passed in 107133 ms" (the line's wording then; now "runTest returned"); `awaitApplyHelper` waited
2007 ms for the helper (the Windows path).

## "Stream N cancelled": root cause (SPEC 1f)

From 16:39 to 19:19 UTC on 2026-09-26 every RigTune Modrinth lookup in CI failed within ~2 s with
`java.io.IOException: Stream N cancelled`, the ids in one JVM running 1, 3, 5 … 17 (research 1.3). In JDK 25's
`java.net.http` (the local JDK 25.0.4's src.zip):
- `Http2Connection.putStream` (`Http2Connection.java:1590-1609`) opens a new stream and, when the connection is already
  marked for shutdown (`IDLE_SHUTDOWN_INITIATED` or `SHUTDOWN_REQUESTED`, `:1998-2001`), cancels it with
  `new IOException("Stream " + streamid + " cancelled", cause.get())`: exactly the message, with the connection's shutdown
  cause attached, which RigTune's warning (`toString()`) dropped.
- `Stream.cancel()` (`Stream.java:1473-1478`) raises the same text, but only for a cancelled exchange
  (`MultiExchange.cancel(true)`, `MultiExchange.java:274-292`, or `checkRequestCancelled`, `Stream.java:541-547`); a caller's
  own `cancel(true)` completes the returned future as cancelled (`CancellationException`), not with this IOException.
  RigTune's only cancel (`BoundedHttp.send`'s `finally`) runs after it has already thrown its own timeout or interrupt
  exception, so it isn't the source.
- A peer reset reads `Received RST_STREAM: <code>` (`Stream.handleReset`, `Stream.java:748-800`), not this.

So the failure came from the JDK's HTTP/2 connection state, not from Modrinth's answers or RigTune's code. Related JDK
bugs: the idle-connection race that produced the same message ([JDK-8312433](https://bugs.openjdk.org/browse/JDK-8312433),
fixed in 21.0.2/22) and GOAWAY handling in JDK 24+ ([JDK-8385131](https://bugs.openjdk.org/browse/JDK-8385131), fixed in 28).
Why that connection was marked for shutdown is UNVERIFIED (its cause wasn't logged). RigTune's fix: no HTTP/2 for Modrinth
(a handful of small requests per session; HTTP/1.1 costs a few extra TLS handshakes on background threads), one retry of
an idempotent lookup on a fresh client, never keeping a client whose connection failed, and the cause chain in the log.
Players were exposed to the same failure, so this is a product fix, not only a CI one.

## Footprint budgets (user-approved: the per-call ns limits and how they're measured)

**(a) The six per-call ns keys: min(ceiling, 4 × max observed).** Max observed = the largest value in every CI run with
the metric (310 legs; 584 FrameHookBudgetTest values) and the r-ci probe's current-estimator measurements (567). The
budgets file records each timing key's `observedMax` and `rule`; `FootprintBudgetsTest.timingLimitsFollowTheirRecordedRule`
checks `limit = min(ceiling, ⌈factor × observedMax⌉)`: 4× for these six, 2× for the other five timing keys (v0.4's
calibration: renderThreadInitWallMs 183.67 → 368, renderThreadInitCpuMs 95.94 → 150 (ceiling), clientStartedWallMs 70.37
→ 141, workerCpuMs5s 186.72 → 300 (ceiling), samplerCpuMsPer60s 50.83 → 102; all unchanged).

| key | max observed (source) | 4 × max | ceiling | limit (was) |
|---|---|---|---|---|
| frameHookNsPerCallOff | 7.422 (36228615454) | 29.69 | 20 | 20 (13) |
| frameHookNsPerCallOn | 39.149 (36258254138) | 156.60 | 200 | 157 (75) |
| frameHookNsPerCallOnPhases | 256.332 (36242976610) | 1025.33 | 400 | 400 (400) |
| tickHookNsPerCall | 68.91 (36230423586) | 275.64 | 2000 | 276 (111) |
| tickHookNsPerCallWorld | 98.19 (probe run 36288312579; CI max 91.62) | 392.76 | 2000 | 393 (87) |
| tickHookNsPerCallOn | 163.25 (probe run 36288312579; CI max 116.22) | 653.00 | 2000 | 653 (101) |

**(b) The gates that catch a 2x regression: median ratios to a reference workload.**
- Tick (FootprintGameTest.timeTick, every tick case, on the render thread): the work, the work called twice per
  iteration, and a fixed pure-Java reference (6 xorshift rounds with array loads and stores per call) are three small loop
  methods, warmed up in 300 rounds of 1,000 calls each; the blocks start once the JIT has finished nothing for 100 ms (cap 10 s);
  then 48 interleaved triples of 20,000 calls, timed by the wall clock. ns per call = the median work block (the ns keys);
  bytes = the fewest-allocating work block (the 0-allocation keys, still 0). Gate `tickHookOnVsReference` = the median
  over triples of work / reference for the monitor-on work (`RigTuneClient.onTick` + `StutterHooks.tick`).
- Frame (FrameHookBudgetTest, the java job): the same design with 48 triples of 200,000 frames (monitor on) and 20,000
  (monitor on with the phase timers), the latter against the reference plus the same 8 `System.nanoTime()` reads per call:
  `frameHookOnVsReference`, `frameHookOnPhasesVsReference`.
- Self-check on every run: the doubled work's ratio must exceed the limit, or the run fails "can't see a 2x regression on
  this runner". That is the continuous form of proof (b).
- Limits, each between its calibration's largest 1x and smallest 2x ratio (recorded in the budgets file as `max1x`,
  `min2x`; `FootprintBudgetsTest.theRatioGatesSitBetweenTheirCalibratedOneAndTwoTimes`):

| key | calibration | 1x max | 2x min | limit |
|---|---|---|---|---|
| tickHookOnVsReference | research/v05-ci runs 36295704245 + 36296194458: TimingProbe2GameTest (this timing code, early in the JVM, 1 min after world creation), 192 measurements on 6 CPU models | 1.745 | 2.462 (the monitor's own tick doubled; the work twice: 2.498) | 2.05 |
| frameHookOnVsReference | run 36296732786: 16 runners × 3, 5 CPU models | 1.419 | 1.920 | 1.65 |
| frameHookOnPhasesVsReference | the same | 0.957 | 1.833 | 1.32 |

- Why wall clock, not thread CPU time as the SPEC had it: Windows counts thread CPU in 15.6 ms steps, so a 1 ms block reads
  0 or 15.6 ms there (local P5 runs); on Linux the probe found the two within 0.5 %. The tick JSON records the thread-CPU
  ratio as a diagnostic (`vsReferenceCpu`).
- Why the limit is 2.05, not SPEC's 1.95: the r-ci probe's 1.95 came from an earlier version of the timing. The first
  proof run (36295129832, the classes cut to two, so the timing ran a minute into the JVM with 250-380 ms of compilation
  during the blocks) caught the doubled monitor tick on 2 legs (2.115, 2.146) and missed it on EPYC 9V45 (1.829): the
  reference read 1.3-1.8x slow. The compiled loop methods and the JIT-quiet wait fixed that (reference 23-34 ns, 0-2 ms of
  compilation during the blocks in 192 measurements), and the calibration of that code puts the limit at 2.05 (1.17x above
  the 1x max, 1.20x below the doubled monitor's min). One probe JVM read 2.47-2.56 at 1x for two repetitions: its world
  was a few seconds old (the monitor's tick work depends on chunks still building); FootprintGameTest times the monitor-on
  work after its 65 s sampler window, and the production values so far are 1.31-1.55 (36293436864, 36297288375).
- The sub-10 ns paths (title screen; world with the monitor off) keep the ns backstop and the 0-allocation gates only; their
  ratios are recorded (`tickHookTiming`, `tickHookTimingWorld`): 1x and 2x overlap there under any estimator (research 5.1).

Unchanged and strict: every ceiling; `renderThreadInitCpuMs`, `clientStartedWallMs` and the other CPU-ms/wall-ms keys; bytes;
0-allocation; leak checks; retained bytes.

## Proofs and evidence

Details and log lines: docs/v0.5/verification/ci/README.md and docs/v0.5/verification/footprint/README.md.
- (a) AC1a.2, a live call fails under the namespace: run 36293980104 (probe commit c2496742, reverted by abeddba2): all 3
  legs red on `ConnectException` to api.modrinth.com, the java job red on the unit-test probe; the same code without it
  green in 36293436864 (088b0385) and 36297288375 (b9f5c002).
- (b) AC1d.3, the monitor-on tick doing its work twice: run 36297418835 (`scratch/ws-ci-proof-slowdown` fb2177b8): every
  leg red on `tickHookOnVsReference` (2.839, 2.859, 2.704 > 2.05), `tickHookNsPerCallOn` green (64-80 ns < 653). The
  first attempt, 36295129832 (reverted by 406714be), missed on one leg: see the budgets section.
- Hang, AC1e.3, three runs of `scratch/ws-ci-proof-hang` (HangProbeGameTest.sleepsForever as the first class, later
  merges of feat/v05-ci): 36297443360 (24ade351, the first watcher): a full thread dump 13:00 into the step naming
  `HangProbeGameTest.sleepsForever(HangProbeGameTest.java:17)` on all 3 legs, then the 15-min step timeout. 36301097653
  (00ca8da3, the whole-cmdline watcher): the same. 36304837319 (df3a64f7, the final wiring): the dump at 13:00, "The
  game-test run exited 124" at 14:00, "No java process left (1 s after the run exited)", each step over at 14:01 and each
  job 4-6 s later, on all 3 legs. (36303866869, df3a64f7's parent 2f6ee87c, printed three java processes still shutting
  down 0.1 s after timeout returned, which is why the step now waits for them and kills any left.) With the final
  constants (KILL 15 s after TERM, a 20-s wait): 36306689929 (11cfc25d), the same on all 3 legs: dump at 13:00, exit 124
  at 14:00, "No java process left (1 s after the run exited)", step over at 14:01.
- The apply-helper wait on Linux: `scratch/ws-ci-proof-helper` (654760d9, only RigTuneClientGameTest and
  BenchmarkGameTest, so the helper runs after the game), run 36303870792: "awaitApplyHelper: waited 2101-2451 ms for the
  apply helper process; it exited" on all 3 legs, all legs green (its python job fails on purpose: the ordering test sees
  FootprintGameTest missing).
- AC1e.4, a local subset run (RigTuneClientGameTest + BenchmarkGameTest, 26.2, Windows, under the game-test lock) twice in
  a row: green both times (178 s, 176 s), the second wipe fine.
- AC1c.2: `./gradlew buildEnvironment` shows `net.fabricmc:fabric-loom:1.17.21`; `:26.2:jar`/`:26.3:jar` rebuilt with
  `--rerun` under the pin are byte-identical to the SNAPSHOT build of the same commit (`7027e993…`, `8957760d…`, research
  1.2). AC1c.4: in 36297288375 the released jars and the lavapipe `.deb`s came from their caches.
- AC1a.3, loopback multicast in the namespace: "Loopback multicast works" on every leg of 36300180213 and every job of
  36300926064; with the outside check, 36303836102 (c890b1ea): multicast works and "1.1.1.1:443 gave
  java.net.SocketException: Network is unreachable" on all 3 legs.
- AC1g.4, the split: 36300926064 (`-f gametest_parts=2`, 24235857): 6 jobs, per leg the 16 classes each once across the
  two parts, all green; 36300180213 (same SHA, one part): the 3 legs unchanged, green. Again with the build.yml flag and
  "runTest returned": 36304357982 (c890b1ea, `-f gametest_parts=2`): 6 jobs green, 16 classes once per leg, part 2 ending
  FootprintGameTest, A11yGameTest; 36303836102 (c890b1ea, one part) green.
- SPEC-4, E2E offline: locally (Windows, under the lock) after `:26.2:prefetchDependencies :26.2:downloadAssets`,
  `./gradlew --offline :26.2:e2eClient -Pe2e.driver=undo` on an empty scratch instance built the driver and launched the
  client ("Loading Minecraft 26.2 with Fabric Loader 0.19.5"), which stopped at Fabric's dependency check as intended (no
  RigTune in the instance). The local Gradle cache was warm anyway, so this shows e2eClient needs nothing online beyond
  the cache; that the prefetch fills everything rests on the game-test legs, the same task type, running offline on CI.
- AC1c.1: both runs of 24235857 restored the Gradle cache; the java job's first `./gradlew` step took 18 s and 20 s.
- Head green on every job, twice, no re-runs: a commit can't name its own runs; they are in the handoff.

## Deviations

1. Wall clock for the ratio blocks (SPEC: thread CPU time), and the tick limit 2.05 (SPEC: 1.95): above.
2. Two network steps, not one, may retry: "Resolve dependencies (network)" and, on the Vulkan leg's cache miss, the lavapipe
   install (named "…network on a cache miss"); the workflow test allows retries only in steps with "network" in the name.
3. The unit and Python tests also run in the namespace (SPEC: the game-test step): they need nothing online (research 1.2).
4. The fake Modrinth's candidates come from the rules (every rules mod not already loaded), with
   `tools/gametest/modrinth-candidates.json` listing only the relations; SPEC 1b had the whole candidate list committed.
   New rules mods are served without an edit.
5. The dev `runClientGameTest` also gets the fake Modrinth, so `GameTestNet`'s online wait holds there; its mods aren't the
   catalog's jars, so installed mods go unrecognised in dev runs.
6. ThreadSamplerTest keeps a wall bound (500 ms, was 100) beside the new "worker still stuck" check: the state check alone
   can't see a bounded `join(1000)`, which is the regression the test guards.
7. SPEC 1f's retry covers every transport failure of a lookup, not only "Stream N cancelled", and the transport is HTTP/1.1,
   so that exact exception can't recur from RigTune's Modrinth client.
8. SPEC 1a says the prefetch logs and skips a configuration it can't resolve; it fails instead (code review H1), so the
   retry loop covers it. All 48 configurations per node resolve today; one that never can would have to be excluded by name.
9. SPEC 1b fails a leg on a 5xx from the fake; the check also fails on a 400/405 and on a 404 outside a project or hash
   lookup (code review M3).
10. SPEC 1e's lock wait in `runProductionClientGameTest`'s `doLast` is the `awaitApplyHelper` finalizer, waiting for the
    helper process where command lines are visible (the coordinator's decision on code review M2): the helper takes the
    lock only 2 s after the game exits, and a `doLast` doesn't run after a failed run.
11. SPEC 1d(b) says 200k warm-up calls; the timing warms each loop method with 300 x 1,000 calls and then waits for the
    JIT to go quiet (above).
12. The split's parts are separate matrix jobs (a runner each, research 7 item 5), not two JVMs one after the other in
    one job: parallel parts shorten the run, and a part's step keeps its own 15-min timeout. Run by hand one after the
    other (`-PgametestClasses=…` twice), `awaitApplyHelper` is the wait between them.
13. ci-streak.md doesn't carry the frame-hook ratios: they are in the java job's test reports, not its log.
14. The game-test job's timeout is 35 min (SPEC 1e: 25), the coordinator's decision on code review L8, and the run has
    its own 14-min timeout as root inside the sudo (KILL 15 s later), plus the step's 20-s wait and kill.
15. The split has one flag in build.yml (`GAMETEST_PARTS`) as well as the `part` column in `tools/gametest_matrix.py`.

## UNVERIFIED / residuals

- Why the HTTP/2 connection was marked for shutdown on 2026-09-26.
- `org.gradle.internal.repository.*` are internal Gradle properties; whether they cover artifact downloads as well as
  metadata, and Loom's own retry behaviour for Mojang downloads. The prefetch loop covers both either way.
- AC1c.1 on `feat/v0.5.0` itself: this branch reads the Gradle cache but never writes it, so the 60 s bound is shown on
  this branch's runs (above) and holds on `feat/v0.5.0` only once a run there has written its entry.
- The ratio limits come from 16-runner calibrations on the CPU models GitHub handed out that day (7763, 9V74, 9V45,
  Xeon 8573C, 8370C, 6973P-C); a new runner type could sit elsewhere, which the self-check would at least flag.
- AC1e.2's pixel comparison of `bench-history-*` between two runs of one SHA: with the offline report asserted before every
  screenshot, the remaining differences are the known regions; not pixel-diffed here.
- SPEC-4's E2E jobs aren't in build.yml yet (WS-E adds them): not run offline on CI. What they need is what the game-test
  legs already fetch: `prefetchDependencies` and `downloadAssets` in their "(network)" step (`e2eClient` is the same
  `ClientProductionRunTask` as `runProductionClientGameTest`; the drivers already compile `--offline` in the java job);
  then `--offline`, also in the harness's Gradle arguments (the harness has no option for that yet). The local check
  above ran on a warm cache. The workflow test applies AC1a.1 to e2e.yml as soon as it exists.
- Whether GNU timeout's TERM reaches Gradle's daemon through the process group or the daemon cancels the build when its
  launcher dies: either way no java process was left 1 s later (36304837319); the step's kill covers anything slower.
- `pgrep -f`'s 4096-byte view of a command line (the second review's point): not checked; the watcher doesn't use it.
- LanGuestGameTest (WS-E) is where vanilla's own LAN discovery runs in the namespace; MulticastCheck mirrors its calls.

## Code review

A code-reviewer subagent on ab2c1947..d5142178 (REQUEST CHANGES: 1 high, 2 medium, 6 low); every later commit was
checked against the same findings.
- H1 `prefetchDependencies` logged a failed configuration and succeeded, so `retry.sh` never retried and a later
  `--offline` step failed instead: fixed, it fails the step naming the configurations (local and CI: 48 of 48 per node).
- M2 the apply-lock wait found the lock free before the helper took it (the helper settles 2 s after exit) and was
  skipped after a failed run: fixed, `awaitApplyHelper` (1e row), and on the coordinator's decision it waits for the
  helper process where the OS shows command lines. Windows (a local subset run, 16:01 AEST): the helper applied its plan at
  16:01:02.50 and `awaitApplyHelper` logged "waited 1758 ms; pending.json applied" before the build ended. Linux: run
  36303870792 above.
- M3 the fake check saw only 5xx, while the fake answers 400 when its own routing throws: fixed, with a test.
- L4 `LogSafe.error` capped the whole line at 200 characters, so a long outer message could cut "(caused by …)": fixed
  (coordinator's decision), each part capped separately, with a test.
- L5 any I/O failure but a timeout or an interrupt dropped the client, including an oversized body or a download's local
  write error, and a request could meet a client another thread had just shut down: fixed (coordinator's decision), the
  client is dropped only on connection failures and such a request is sent once more on the fresh client, with tests.
- L6 after a failed start the fixture returned no URL to later run tasks (so `-Drigtune.modrinth.baseUrl=null`, live
  Modrinth): fixed, the failure is rethrown; `close()` synchronized.
- L7 `GameTestNet.set` could accept the report from before the switch: `settingsChanged()` → `rescan()` already sets the
  report to null inside the same `computeOnClient`, and on the coordinator's decision the wait also asks for a new
  report instance.
- L8 a 25-min job around a 15-min step: the job now has 35 min, and the run its own 14-min timeout as root inside the
  sudo plus the step's wait-and-kill (1e row); proven in 36304837319.
- L9 the cached lavapipe `.deb`s are installed with `dpkg -i`, without apt's signature check: accepted (coordinator). The
  key is per runner image, and a branch only restores its own caches and the default branch's. Their sha256s aren't
  pinned: a list written when the cache is filled would live in the same cache entry, so it would only catch corruption,
  which `dpkg -i` already rejects.

A second review (code-reviewer subagent) on the follow-up commits a1107cdf, 67d6b51c, ce0583aa (REQUEST CHANGES: 1 high,
2 medium, 2 low; a1107cdf's fixes checked fine):
- H the watcher's `pgrep -f KnotClient` may never match: KnotClient follows a 9-19 KB classpath and `pgrep -f` may see
  only the first 4096 bytes of a command line (the reviewer's memory; UNVERIFIED here). Fixed without relying on it: every
  `java` process's whole `/proc/<pid>/cmdline` is searched. The hang proof was re-run with this watcher: 36301097653, a dump on all 3 legs.
- M `ci_streak.py` ended on the first job log GitHub wouldn't give, and fetched every streak run's logs: a missing log is
  now "no log", with a test, and only the last `--need` runs' logs are fetched.
- M `TimedGameTests` is on in every run: shown green in 36300180213 (one part) and 36300926064 (two parts).
- L "passed" was logged before Fabric's own end-of-class checks: the line now says "runTest returned" (or "threw").
- L the ratio regex took no exponent or sign, and a class line seen twice counted twice: fixed (coordinator), with a test.
- On the coordinator's reading of the same review, the watcher matches the exact argument
  `net.fabricmc.loader.impl.launch.knot.KnotClient`, proven in 36304837319 and 36306689929.

A third review (code-reviewer subagent) on c890b1ea and 60a64ce2, the coordinator's fix round (APPROVE after one medium
fix; the retry bounds, the 429 counter, client dropping, the Groovy, offline.sh's arguments, the watcher's match against
Loom 1.17.21's command line and the Actions expressions checked fine):
- M a download could be sent again after its body had begun (another thread had dropped the client while both failed
  mid-body): the resent body would be appended to the same temp file and digest and fail as a hash mismatch, hiding the
  real error. Fixed: the resend only happens before any response (`BoundedHttp.Progress.started`, set when the body
  subscriber starts); `aDownloadWhoseBodyHadStartedIsNotSentAgain`, red first. The case the resend exists for fails
  before any response (JDK 25.0.4 `HttpClientImpl.java:971-990`: a failed future `IOException("closed")` once shutdown
  is requested; the reviewer's citation).
- L no slack in the time budget (14:00 + 30 s KILL + 30 s wait = 15:00): KILL 15 s after TERM and a 20-s wait (14:35 at
  worst); the test now asserts strictly less than the step's limit.
- L gametest_matrix.py's docstring still called PARTS the switch: now build.yml's GAMETEST_PARTS.

## For the coordinator

- DESIGN.md "Client game tests in CI": the namespace (loopback multicast, nothing off the machine), the prefetch step,
  the fake Modrinth and its check, the 15/35-min timeouts, the SIGQUIT dump at 13 min and the 14-min timeout with the
  step's wait-and-kill, the apply-helper wait, the split flag. "RigTune's own footprint": the 4× rule for the six per-call ns keys,
  the three ratio gates with their self-check, and "the 5 s worker window runs against the local fake Modrinth" (SPEC 1b).
- The first 5-run streak (AC1g.2): after the merge, dispatch one at a time with a push freeze, then
  `python tools/ci_streak.py --branch feat/v0.5.0 --sha <merge SHA> --write docs/v0.5/verification/ci-streak.md`; for the
  RC streak add `--require "<E2E push job name>"` for each of WS-E's two jobs.
- The split switch: `GAMETEST_PARTS: 2` in build.yml's top-level `env` (one line); a single run with two parts:
  `gh workflow run build.yml --ref <branch> -f gametest_parts=2`. Part 1 today is RigTuneClientGameTest to PreviewGameTest
  (8 classes), part 2 ProfilesGameTest to A11yGameTest (8).
- release.yml (WS-E's) got only the runner and JDK pins AC1c.3 needs; WS-E keeps them in its restructure.
- A decision: the tick ratio limit is 2.05, not the SPEC's 1.95 (AC1d.2, 1h), with the calibration above.
- X4's render-thread flag (the X4 check reading "resolved on the render thread during preLaunch, onInitializeClient or the
  CLIENT_STARTED handler"): `client/FootprintStats` is WS-K's in the PLAN's ownership table, not in ws-ci's files, so the
  resolving thread is recorded there by WS-K; FootprintGameTest (ws-ci, then WS-K) is ready for its assertion.
- Branches for you to delete (I delete none): `scratch/ws-ci-proof-slowdown`, `scratch/ws-ci-proof-hang`,
  `scratch/ws-ci-proof-helper`, and once this is merged `research/v05-ci`; worktrees `C:/Dev/Worktrees/rigtune-r-ci` and `C:/Dev/Worktrees/rigtune-ci-proofs`.
