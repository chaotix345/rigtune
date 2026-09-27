# WS-CI: rock-solid CI (v0.5 P0.1) as landed

Branch `feat/v05-ci` (from `feat/v0.5.0` @ ab2c1947). Research and evidence: docs/research/v0.5/ci-robustness.md (every
`build.yml` failure since v0.1 classified: 1 in 6 runs since the full v0.4 suite failed for a reason other than the code,
9 outages and 5 flakes). Coordinator decisions: the r-ci row of docs/PROGRESS.md.

## What landed

| Item | Change | Files | Tests / proof |
|---|---|---|---|
| No network in tests | Unit, Python and client game tests run in a loopback-only network namespace: `tools/ci/offline.sh` (`sudo --preserve-env … unshare --net`, `ip link set lo up`, back to the runner user with `setpriv`, environment kept). Every Gradle step after one "Resolve dependencies (network)" step runs `--offline`. | `.github/workflows/build.yml`, `tools/ci/offline.sh` | proof (a) below; every CI run since |
| One retried download step | `prefetchDependencies` (each node resolves every resolvable configuration) plus the node's `downloadAssets`, catalog and jars in the legs; the released E2E jars in the java job. `tools/ci/retry.sh` runs it up to 3 times, 30 s / 90 s apart. Tests are never retried. | `build.gradle`, `build.yml`, `tools/ci/retry.sh` | CI |
| Fake Modrinth for the game tests | `ModrinthFixture`, a BuildService next to `RulesFixtureServer`, runs the E2E's `FakeModrinth.java` as a single-file program on 127.0.0.1 with a free port (stdin kept open; closed at the end of the build). `gametestModrinthCatalog` writes its catalog per node: the `productionGameTestMods` jars (fabric-api, Mod Menu, Sodium, real hashes) and one fixture version per other rules mod (fixed ids and dates, requiring fabric-api). `runProductionClientGameTest` and the dev `runClientGameTest` get `-Drigtune.modrinth.baseUrl`. The request log (`build/gametest-modrinth/requests.jsonl`) is uploaded, and a step fails a leg if it is empty or holds a 5xx. `FakeModrinth` answers `GET /v2/versions?ids=`. | `build.gradle`, `tools/e2e/java/…/FakeModrinth.java`, `build.yml` | `FakeModrinthTest.versionsByIdReturnsKnownVersionsOnly` (red first); CI |
| Caches, pins, retries | `setup-gradle` in the java job; `main` and `feat/v0.5.0` write the Gradle cache (other refs read it). `loom_version=1.17.21` (the SNAPSHOT's resolution; jars byte-identical). `org.gradle.internal.repository.max.tentatives=6`, `initial.backoff=1000`. `actions/cache` for the three released jars (under their sha256s) and for the lavapipe `.deb`s (per runner image). Every job on `ubuntu-24.04`. Runner image and CPU in the leg's step summary. | `build.yml`, `gradle.properties` | CI |
| Footprint ns gates | Budgets min(ceiling, 4 × max observed); the monitor-on tick work gated as a median ratio to a reference workload (`tickHookOnVsReference`, limit 1.95) with a per-run "called twice" self-check (below). | `tools/footprint-budgets.json`, `FootprintGameTest`, `FootprintBudgetsTest` | `FootprintBudgetsTest.theTickRatioGateSitsBetweenTheObservedOneAndTwoTimes`; proof (b) |
| Base-URL hardening | `-Drigtune.modrinth.baseUrl` counts only as https, or plain http on a loopback host (`localhost`, `127.x.x.x`, `[::1]`), like `-Drigtune.rules.baseUrl`; anything else is ignored with a WARN, a valid override is logged once. | `HttpModrinthClient` | `HttpModrinthClientTest.baseUrlPropertyTakesOnlyHttpsOrHttpOnThisMachine` |
| "Stream N cancelled" | Root cause below. Both Modrinth HttpClients speak HTTP/1.1; a failure without an HTTP response (not a status error, not an interrupt) drops the client that had it, so the next request builds a fresh one; the lookup warnings print the cause chain (`LogSafe.error`). | `HttpModrinthClient`, `OnlineDataFetcher` | `theClientsSpeakHttp11`, `aTransportFailureStartsAFreshClientButAnHttpErrorDoesNot` (both red without the fix) |
| Network switch in game tests | `GameTestNet.set`: save, `settingsChanged()`, wait for the report built after it with the matching online state. Used by A11y, Awareness, BenchmarkHistory, Jvm, Profiles, ServerLimits and Stutter; BenchmarkHistory and Profiles flipped the field without a rescan. | `GameTestNet.java` + 7 classes | CI |
| UiGameTest settings wait | `SettingsSaver.shared().flush(10 s)`, then one read of `settings.json` (was a 100-tick file poll). After restoring the defaults it waits for the online report, so later classes start from the fake Modrinth's report. | `UiGameTest` | CI |
| BenchmarkGameTest hang (run 36235561444) | Not reproduced (0 in 47 repeats, research). The game-test step times out at 15 min (job 25); at 13 min a watcher sends SIGQUIT to the game's JVM (not the `xvfb-run` wrapper), which prints every thread's stack, its locks and any Java-level deadlock into the job log. | `build.yml` | UNVERIFIED on a real hang |
| Post-exit helper vs the next run | `runProductionClientGameTest` waits (≤ 60 s) until `config/rigtune/apply.lock` can be locked, so a later run's wipe and the artifacts see a finished folder (the r-ci experiment's second JVM failed `Unable to delete directory … New files were found`). | `build.gradle` | — |
| Smaller | PreviewGameTest's real preview waits 1 min (was 5, for sequential live lookups). ThreadSamplerTest's `stop()` bound 100 → 500 ms (the regression it guards is a 1000 ms join), plus "returned while the worker was still stuck". | `PreviewGameTest`, `ThreadSamplerTest` | — |

## "Stream N cancelled": root cause

From 16:39 to 19:19 UTC on 2026-09-26 every RigTune Modrinth lookup in CI failed within ~2 s with
`java.io.IOException: Stream N cancelled`, and in one JVM the ids ran 1, 3, 5 … 17: one HTTP/2 connection kept being used
and every new stream on it failed (research 1.3). In JDK 25's `java.net.http` (src.zip of the local JDK 25.0.4):
- A peer reset reads differently: `Stream.handleReset` reports `Received RST_STREAM: <code>`.
- A caller's own `cancel(true)` completes the returned future as cancelled (`CancellationException`), not with this
  IOException.
- `Http2Connection.putStream` does raise exactly `new IOException("Stream " + streamid + " cancelled", cause)` when it
  registers a new stream on a connection already marked for shutdown (`IDLE_SHUTDOWN_INITIATED` or
  `SHUTDOWN_REQUESTED`); the cause is the connection's shutdown cause, which RigTune's warning (`toString()`) dropped.
- The same message came from an idle-connection race in JDK 20 ([JDK-8312433](https://bugs.openjdk.org/browse/JDK-8312433),
  fixed in 21.0.2/22), and GOAWAY handling in JDK 24+ has an open follow-up
  ([JDK-8385131](https://bugs.openjdk.org/browse/JDK-8385131), fixed in 28).

So the failure is the JDK's HTTP/2 connection state, not Modrinth answering badly; why that connection was marked for
shutdown is UNVERIFIED (its cause wasn't logged). What RigTune controls: it no longer uses HTTP/2 for Modrinth (a handful
of small requests per session; HTTP/1.1 costs a few extra TLS handshakes on background threads), it never keeps using a
client that failed below HTTP, and a recurrence would now log its cause. Players were exposed to the same failure, so
this is a product fix, not only a CI one.

## Footprint budgets (user-approved: per-call ns limits and how they're measured)

Max observed = the largest value in every CI run with the metric (310 legs; 584 FrameHookBudgetTest values) and in the
r-ci probe's current-estimator measurements (567):

| key | max observed (source) | 4 × max | ceiling | limit (was) |
|---|---|---|---|---|
| frameHookNsPerCallOff | 7.422 (36228615454) | 29.69 | 20 | 20 (13) |
| frameHookNsPerCallOn | 39.149 (36258254138) | 156.60 | 200 | 157 (75) |
| frameHookNsPerCallOnPhases | 256.332 (36242976610) | 1025.33 | 400 | 400 (400) |
| tickHookNsPerCall | 68.91 (36230423586) | 275.64 | 2000 | 276 (111) |
| tickHookNsPerCallWorld | 98.19 (probe run 36288312579; CI max 91.62) | 392.76 | 2000 | 393 (87) |
| tickHookNsPerCallOn | 163.25 (probe run 36288312579; CI max 116.22) | 653.00 | 2000 | 653 (101) |

These are gross-regression backstops. The 2x gate is `tickHookOnVsReference`:
- Measurement (FootprintGameTest.timeTick, every tick case): 48 interleaved triples of 20,000 calls on the render
  thread, after 600,000 warm-up calls: the work, the work called twice per iteration, and a fixed pure-Java reference
  workload (6 xorshift rounds with array loads and stores). Wall clock (`System.nanoTime`): Windows quantises thread CPU
  time to 15.6 ms, and on Linux the probe found wall and thread CPU within 0.5 %. ns per call = the median work block
  (the ns keys above); bytes = the fewest-allocating work block (the 0-allocation keys, unchanged at 0).
- Gate: the median over triples of work / reference for the monitor-on work (`RigTuneClient.onTick` +
  `StutterHooks.tick`), limit 1.95. Probe: 1x at most 1.577, a 2x regression at least 2.393, over 189 measurements on 5
  runner CPU models; 1.95 = 1.577 × 1.24 = 2.393 / 1.23. Why a ratio: the old best-of-5 flaked when the JIT was still
  compiling during all 5 blocks (29 ms median of compilation in the outlying probe measurements, 0 in the others); a
  ratio of interleaved blocks shares the runner's speed and any compilation with both sides.
- Self-check, every run: the monitor-on work called twice must measure above the limit
  (`tickHookOnTwinVsReference`), or the run fails "can't see a 2x regression on this runner". It is the continuous form
  of proof (b).
- The sub-10 ns paths (title screen; world with the monitor off) keep the ns backstop and the 0-allocation gates only:
  the same code measures 1.6-42 ns there depending on the JVM's inlining, so no per-call gate can catch a 2x there
  without flaking (research 5.1). Their ratios are recorded in the JSON (`tickHookTiming`, `tickHookTimingWorld`).
- The JSON also records the per-block numbers, the JIT milliseconds during the blocks and the CPU model.

Unchanged: every ceiling, `renderThreadInitCpuMs` and `clientStartedWallMs` (coordinator decision 6), the CPU-ms, byte,
0-allocation and leak budgets.

## Proofs

- (a) A live call fails under the namespace: PROOF_A.
- (b) A 2x slowdown of the monitor-on tick fails the gate: PROOF_B.
- (c) The branch head green on every job, twice, no re-runs: PROOF_C.

## Deviations

1. The unit tests and the Python tests run in the namespace too, not only the game tests: they needed nothing online
   (research 1.2), so it costs nothing and keeps it that way.
2. The dev `runClientGameTest` also gets the fake Modrinth, so `GameTestNet`'s "online report" wait holds there; its mods
   aren't the catalog's jars, so installed mods go unrecognised in dev runs.
3. The JDK patch isn't pinned (`setup-java` `'25'` takes the newest 25.x); the footprint JSON records `java`.
4. FrameHookBudgetTest (JUnit) keeps ns gates with the new limits; a ratio there is the same method but unmeasured on CI.

## UNVERIFIED / residuals

- Why the HTTP/2 connection was marked for shutdown on 2026-09-26 (above).
- The SIGQUIT dump on a real hang (none since); the watcher only runs after 13 min.
- `org.gradle.internal.repository.*` are internal Gradle properties; whether they cover artifact downloads as well as
  metadata, and Loom's own retry behaviour for Mojang downloads.
- Cache hits: the lavapipe cache and the java job's Gradle cache are warm only from the second run on an image / the
  first run on a writing branch.

## For the coordinator

- DESIGN.md "Client game tests in CI": the namespace, the prefetch step, the fake Modrinth, the 15/25-min timeouts and
  the SIGQUIT dump. "RigTune's own footprint": replace "Budgets are 2 × the largest value observed" for the six per-call
  ns keys with the 4× rule and the ratio gate.
- SPEC 10 (v0.5): note `tickHookOnVsReference` and the self-check; WS-F's deviation 1 ("Modrinth is on during the 5 s
  window") now means "against the local fake".
