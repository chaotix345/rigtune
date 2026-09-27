# WS-CI: rock-solid CI (v0.5 P0.1, SPEC 1) as landed

Branch `feat/v05-ci` (from `feat/v0.5.0` @ ab2c1947). Research and evidence: docs/research/v0.5/ci-robustness.md (every
`build.yml` failure since v0.1 classified: 1 in 6 runs since the full v0.4 suite failed for a reason other than the code,
9 outages and 5 flakes). Decisions: the r-ci row of docs/PROGRESS.md; SPEC 1 (AC1a.1-AC1g.3). Proof runs and their
evidence: docs/v0.5/verification/ci/README.md and docs/v0.5/verification/footprint/README.md.

## What landed

| SPEC | Change | Files | Tests / proof |
|---|---|---|---|
| 1a | One "Resolve dependencies (network)" step per building job (`prefetchDependencies`: each node resolves every resolvable configuration; plus the leg's `downloadAssets`, fake-Modrinth catalog and jars; plus the released E2E jars in the java job), retried by `tools/ci/retry.sh` (3 tries, 30 s / 90 s). Every later Gradle step runs `--offline`. Unit, Python and client game tests run with no network but loopback: `tools/ci/offline.sh` (`sudo --preserve-env env PATH HOME unshare --net`, `ip link set lo up`, back to the runner's uid/gid with `setpriv`, the environment kept). `prefetchDependencies` fails when any configuration doesn't resolve, so the retry covers it. Tests are never retried. | `.github/workflows/build.yml`, `build.gradle`, `tools/ci/offline.sh`, `tools/ci/retry.sh` | `tools/tests/test_ci_workflow.py` (AC1a.1); proof (a) (AC1a.2) |
| 1b | `ModrinthFixture`, a BuildService next to `RulesFixtureServer`, runs the E2E's `FakeModrinth.java` as a single-file program on 127.0.0.1 with a free port (it serves until its stdin closes, in `close()`). `gametestModrinthCatalog` writes the node's catalog: the `productionGameTestMods` jars (fabric-api, Mod Menu, Sodium; real hashes) and one fixture version per other rules mod (ids from a hash of slug and node, a fixed date), requiring fabric-api unless `tools/gametest/modrinth-candidates.json` lists relations (Entity Culling incompatible with Sodium; Sodium Extra requiring Sodium). `runProductionClientGameTest` and the dev `runClientGameTest` get `-Drigtune.modrinth.baseUrl`. `FakeModrinth` answers `GET /v2/versions?ids=`. The request log is uploaded; `tools/ci/check_fake_modrinth.py` fails a leg without the four start-up lookups answered 200, with any answer other than 2xx or a 404 to a lookup of one project or file hash (the fake answers 400 when its own routing throws), or with a "Modrinth lookups failed; using offline data" line outside a Modrinth-off test. A fake that didn't start fails every run task of the build (never a missing URL, which would mean live Modrinth). PreviewGameTest's real preview waits 1200 ticks (was 6000). | `build.gradle`, `tools/e2e/java/…/FakeModrinth.java`, `tools/gametest/modrinth-candidates.json`, `tools/ci/check_fake_modrinth.py`, `PreviewGameTest` | `FakeModrinthTest.versionsByIdReturnsKnownVersionsOnly` (AC1b.1, red first); `test_ci_workflow.CheckFakeModrinthTests`; every leg (AC1b.2, AC1b.3) |
| 1c | `setup-gradle` in the java job; `main` and `feat/v0.5.0` write the Gradle cache, other refs read it. `loom_version=1.17.21`; `org.gradle.internal.repository.max.tentatives=6`, `initial.backoff=1000`. `actions/cache` for the three released jars (key = their sha256s) and the lavapipe `.deb`s (key = `$ImageOS-$ImageVersion`, `dpkg -i` on a hit). Every job in every workflow on `ubuntu-24.04`; `setup-java` `25.0.3` everywhere; each leg prints image, Mesa and CPU; the footprint JSON records `cpu`. | `build.yml`, `release.yml`, `snapshot-canary.yml`, `update-rules.yml`, `gradle.properties` | `test_ci_workflow.AllWorkflowsTests` (AC1c.3); AC1c.2 below |
| 1d | Footprint budgets and the ratio gates (next section). | `tools/footprint-budgets.json`, `FootprintGameTest`, `FrameHookBudgetTest`, `FootprintBudgetsTest` | `FootprintBudgetsTest.timingLimitsFollowTheirRecordedRule` (AC1d.1), `theRatioGatesSitBetweenTheirCalibratedOneAndTwoTimes`; proof (b) (AC1d.3) |
| 1e | `GameTestNet.set(context, controller[, configDir], on)`: save the switch, `settingsChanged()`, wait for the report built after it with `online() == (on && modrinthAllowed)`; used by A11y, Awareness, BenchmarkHistory, Jvm, Profiles, ServerLimits, Stutter and UiGameTest's restore. BenchmarkHistoryGameTest checks and logs, before each of its 8 screenshots, that the offline report is on screen and the Apply label. UiGameTest's `waitForSaved` flushes `SettingsSaver` (10 s) and reads `settings.json` once. The game-test step times out at 15 min (job 25); at 13 min a watcher sends SIGQUIT to the game's JVM only (`/proc/<pid>/comm` = `java`, not the `xvfb-run` wrapper). `awaitApplyHelper`, a finalizer of `runProductionClientGameTest` (so after a failed run too): while `config/rigtune/pending.json` exists, up to 60 s, it waits until RigTune's apply helper has deleted it or, after 10 s (the helper's 2 s settle plus its JVM start), `apply.lock` is free. ThreadSamplerTest: `stop()` bound 500 ms plus "returned while the worker was still stuck". | `GameTestNet.java` + 8 game-test classes, `build.yml`, `build.gradle`, `SettingsSaverTest`, `ThreadSamplerTest` | `GameTestSourcesTest` (AC1e.1, AC1e.2 unit part), `SettingsSaverTest.aSaveIsNotBlockedByBusyWorkerAndNetworkPools` (AC1e.1); hang proof (AC1e.3); local subset run (AC1e.4) |
| 1f | Root cause below. Both Modrinth HttpClients speak HTTP/1.1. A lookup that fails without an HTTP response (not a status error, a timeout or an interrupt) drops the HttpClient and is sent once more on a fresh one, with the same stall and deadline limits (downloads are never retried). The lookup warnings print the cause chain (`LogSafe.error`). `-Drigtune.modrinth.baseUrl` counts only as https, or http on a loopback host (`localhost`, `127.x.x.x`, `[::1]`); anything else is ignored with one WARN naming the property, an accepted override is logged once. | `HttpModrinthClient`, `OnlineDataFetcher` | `HttpModrinthClientTest`: `aLookupThatFailsOnceIsRetriedOnAFreshClient`, `twoFailuresInARowFallBackToOfflineDataWithOneWarning` (AC1f.1), `theClientsSpeakHttp11`, `aTransportFailureStartsAFreshClientButAnHttpErrorDoesNot` (red without the fix), `baseUrlPropertyTakesOnlyHttpsOrHttpOnThisMachine` (AC1f.2) |
| 1g | `tools/ci_streak.py`: lists `build.yml` runs on a branch (optionally one SHA), oldest first; a run counts when push or dispatch, attempt 1, every job green, none skipped, the required jobs and ≥ 3 legs present; a cancelled run or another event neither counts nor breaks; any other completed run breaks. Writes `docs/v0.5/verification/ci-streak.md`. | `tools/ci_streak.py` | `tools/tests/test_ci_streak.py` (AC1g.1) |

Local: `./gradlew build` green on both nodes, 1852 unit tests each (1 skipped); `tools/tests` 343, `tools/e2e/tests` 216.

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
an idempotent lookup on a fresh client, never keeping a client that failed below HTTP, and the cause chain in the log.
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
  methods, warmed up in 300 short calls each; the blocks start once the JIT has finished nothing for 100 ms (cap 10 s);
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
  work after its 65 s sampler window, and the production values so far are 1.44-1.50 (36293436864).
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
- Hang, AC1e.3: run 36297443360 (`scratch/ws-ci-proof-hang` 24ade351): on all 3 legs a full thread dump 13:00 into the
  step naming `HangProbeGameTest.sleepsForever(HangProbeGameTest.java:17)`, the step timed out at 15 min, the job ended
  3 s later.
- AC1e.4, a local subset run (RigTuneClientGameTest + BenchmarkGameTest, 26.2, Windows, under the game-test lock) twice in
  a row: green both times (178 s, 176 s), the second wipe fine.
- AC1c.2: `./gradlew buildEnvironment` shows `net.fabricmc:fabric-loom:1.17.21`; `:26.2:jar`/`:26.3:jar` rebuilt with
  `--rerun` under the pin are byte-identical to the SNAPSHOT build of the same commit (`7027e993…`, `8957760d…`, research
  1.2). AC1c.4: in 36297288375 the released jars and the lavapipe `.deb`s came from their caches.
- Head green on every job, twice, no re-runs: PROOF_C.

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

## Code review

A code-reviewer subagent on ab2c1947..d5142178 (REQUEST CHANGES: 1 high, 2 medium, 6 low); every later commit was
checked against the same findings.
- H1 `prefetchDependencies` logged a failed configuration and succeeded, so `retry.sh` never retried and a later
  `--offline` step failed instead: fixed, it fails the step naming the configurations (local and CI: 48 of 48 per node).
- M2 the apply-lock wait found the lock free before the helper took it (the helper settles 2 s after exit) and was
  skipped after a failed run: fixed, `awaitApplyHelper` (1e row). Local check (a third subset run, 16:01 AEST, after the review fix): the helper applied its plan at 16:01:02.50 and `awaitApplyHelper` logged "waited 1758 ms; pending.json applied" before the build ended.
- M3 the fake check saw only 5xx, while the fake answers 400 when its own routing throws: fixed, with a test.
- L4 `LogSafe.error` caps the line at 200 characters, so a long outer message could cut "(caused by …)": not changed.
  `BoundedHttp.send` rethrows I/O failures unwrapped, so "Stream N cancelled" plus its cause stays short; only non-I/O
  causes get the URL wrapper, and that message already contains the cause.
- L5 any I/O failure but a timeout or an interrupt drops the client and retries a lookup, including an oversized body or
  a download's local write error, and a parallel lookup can meet a client just shut down: the comments now say so; kept,
  because the cost is one new connection and at most one repeated lookup, and the parallel case is retried on the fresh
  client.
- L6 after a failed start the fixture returned no URL to later run tasks (so `-Drigtune.modrinth.baseUrl=null`, live
  Modrinth): fixed, the failure is rethrown; `close()` synchronized.
- L7 `GameTestNet.set` could accept the report from before the switch: not so. `settingsChanged()` → `rescan()` sets
  the report to null on the client thread inside the same `computeOnClient`, before the wait; a later rebuild with the
  same online state (an in-flight lookup finishing) can still replace it.
- L8 a 25-min job around a 15-min step: kept (SPEC 1e). The hang proof shows the step timeout ends the run under `sudo`
  (each job over 3 s later); the steps before it take about 1 min warm. Only a hang plus a very slow retry cycle could
  reach the job timeout first.
- L9 the cached lavapipe `.deb`s are installed with `dpkg -i`, without apt's signature check: accepted. The key is per
  runner image, and a branch only restores its own caches and the default branch's.

## For the coordinator

- DESIGN.md "Client game tests in CI": the namespace, the prefetch step, the fake Modrinth and its check, the 15/25-min
  timeouts and the SIGQUIT dump, the apply-lock wait. "RigTune's own footprint": the 4× rule for the six per-call ns keys,
  the three ratio gates with their self-check, and "the 5 s worker window runs against the local fake Modrinth" (SPEC 1b).
- The first 5-run streak (AC1g.2): after the merge, dispatch one at a time with a push freeze, then
  `python tools/ci_streak.py --branch feat/v0.5.0 --sha <merge SHA> --write docs/v0.5/verification/ci-streak.md`.
- Branches for you to delete (I delete none): `scratch/ws-ci-proof-slowdown`, `scratch/ws-ci-proof-hang`, and once this
  is merged `research/v05-ci`; worktrees `C:/Dev/Worktrees/rigtune-r-ci` and `C:/Dev/Worktrees/rigtune-ci-proofs`.
