# CI verification (v0.5 SPEC 1, ws-ci)

Design and budgets: docs/v0.5/design/ws-ci.md. Runs are `build.yml` on https://github.com/chaotix345/rigtune.

## AC1a.2: no network in the test steps

The probe commit c2496742 (on `feat/v05-ci`, reverted by abeddba2) added `LiveNetworkProbeGameTest` (first game-test
entrypoint; GET https://api.modrinth.com/v2/project/sodium) and `LiveNetworkProbeTest` (a unit test opening the same URL).

| run | commit | result |
|---|---|---|
| [36293980104](https://github.com/chaotix345/rigtune/actions/runs/36293980104) | c2496742, with the probes | all 3 legs red: `AssertionError: PROOF (ws-ci): a live call from a game test failed: https://api.modrinth.com/v2/project/sodium: java.net.ConnectException` (vanilla's own session-server and key lookups log `UnknownHostException` too); java red: `LiveNetworkProbeTest` `ConnectException` (`UnresolvedAddressException`) on both nodes, 1848 tests, 1 failed |
| [36293436864](https://github.com/chaotix345/rigtune/actions/runs/36293436864) | 088b0385, the same code without the probes (c2496742's grandparent; d5142178 between them is docs only) | every job green |
| [36297288375](https://github.com/chaotix345/rigtune/actions/runs/36297288375) | b9f5c002 (after the revert) | every job green |

The probe ran on the feature branch rather than a separate scratch branch, one commit and its revert.

## AC1e.3: a hung game test dumps its threads and ends the leg

Scratch branch `scratch/ws-ci-proof-hang` (24ade351): `HangProbeGameTest.sleepsForever` as the first game-test entrypoint.
Run [36297443360](https://github.com/chaotix345/rigtune/actions/runs/36297443360), all 3 legs:

| leg | step start | thread dump | step end |
|---|---|---|---|
| 26.2 OpenGL | 05:32:24 | 05:45:24 `Full thread dump OpenJDK 64-Bit Server VM (25.0.3+9-LTS …)`, `at io.github.chaotix345.rigtune.gametest.HangProbeGameTest.sleepsForever(HangProbeGameTest.java:17)` | 05:47:37 "The action 'Client game tests' has timed out after 15 minutes." |
| 26.3 OpenGL | 05:32:22 | 05:45:22, same frame | 05:47:34, same |
| 26.3 Vulkan | 05:32:25 | 05:45:25, same frame | 05:47:38, same |

The dump comes 13:00 into the step; the step timeout stops the Gradle run under `sudo unshare` (each job ended 3 s later,
well inside its 25-min timeout).

That run's watcher picked the JVM by `pgrep -f fabric.client.gametest`. The final watcher (every `java` process whose whole
`/proc/<pid>/cmdline` contains KnotClient, SPEC-29 and the second code review) was proven again: the hang probe merged with
feat/v05-ci at 00ca8da3, run [36301097653](https://github.com/chaotix345/rigtune/actions/runs/36301097653): on all 3
legs `Full thread dump OpenJDK 64-Bit Server VM (25.0.3+9-LTS …)` with
`at io.github.chaotix345.rigtune.gametest.HangProbeGameTest.sleepsForever(HangProbeGameTest.java:17)` 13:00 into the step
(07:00:49 / 07:00:58 / 07:00:56 for steps started 06:47:49 / 06:47:58 / 06:47:56), then "timed out after 15 minutes"
(07:03:02 / 07:03:11 / 07:03:08), each job over 2-6 s later.

The wiring of df3a64f7 (the exact `net.fabricmc.loader.impl.launch.knot.KnotClient` argument; offline.sh's
`--timeout 14m`, GNU timeout as root inside the sudo, KILL 30 s after TERM; the step's 30-s wait and kill): run
[36304837319](https://github.com/chaotix345/rigtune/actions/runs/36304837319) (df3a64f7): on all 3 legs the dump naming
`HangProbeGameTest.sleepsForever` 13:00 into the step, "The game-test run exited 124 (124: stopped by the 14-min
timeout)" at 14:00, "No java process left (1 s after the run exited)", the step over at 14:01 (08:15:31 / 08:15:40 /
08:15:36 for steps started 08:01:30 / 08:01:38 / 08:01:35), each job 4-6 s later. The run before it,
[36303866869](https://github.com/chaotix345/rigtune/actions/runs/36303866869) (2f6ee87c, without the wait), listed three
java processes still shutting down 0.1 s after timeout returned. With the final constants (KILL 15 s after TERM, the
step's wait 20 s): [36306689929](https://github.com/chaotix345/rigtune/actions/runs/36306689929) (11cfc25d): on all 3
legs the dump at 13:00, "exited 124" at 14:00, "No java process left (1 s after the run exited)", the step over at 14:01
(08:50:58 / 08:51:00 / 08:51:14 for steps started 08:36:57 / 08:36:59 / 08:37:12).

## The apply-helper wait on Linux

`scratch/ws-ci-proof-helper` (654760d9): fabric.mod.json cut to RigTuneClientGameTest and BenchmarkGameTest, so RigTune's
apply helper runs after the game. Run [36303870792](https://github.com/chaotix345/rigtune/actions/runs/36303870792): on
all 3 legs "Started the RigTune apply helper for …/config/rigtune/pending.json", then "awaitApplyHelper: waited 2451 /
2392 / 2101 ms for the apply helper process; it exited"; the legs green. Its python job is red on purpose: the ordering
test finds no FootprintGameTest in that cut list.

## AC1e.4: local subset run twice

Windows 11, JDK 25.0.4, `:26.2:runProductionClientGameTest` with only RigTuneClientGameTest and BenchmarkGameTest as
entrypoints, twice in a row under `C:/Dev/Worktrees/.gametest-lock` (2026-09-27 15:30 and 15:33 AEST): both
`BUILD SUCCESSFUL` (178 s, 176 s), the second run's wipe of `build/run/productionClientGameTest` succeeded, no "New
files were found". Both runs leave a pending apply, so RigTune's apply helper ran after each.
Local check (a third subset run, 16:01 AEST, after the review fix): the helper applied its plan at 16:01:02.50 and `awaitApplyHelper` logged "waited 1758 ms; pending.json applied" before the build ended.

## AC1a.3: loopback multicast in the namespace

Every leg runs `tools/ci/offline.sh java tools/ci/MulticastCheck.java` ("Check loopback multicast") before the game tests:
one datagram to 224.0.2.60:4445, received on a `MulticastSocket(4445)` joined to the group.
Run [36300180213](https://github.com/chaotix345/rigtune/actions/runs/36300180213) (24235857): "Loopback multicast works:
224.0.2.60:4445 received from 0.0.0.0" on all 3 legs; the same on all 6 jobs of 36300926064 below. With the check that
nothing off the machine answers: [36303836102](https://github.com/chaotix345/rigtune/actions/runs/36303836102)
(c890b1ea): "Nothing off the machine is reachable: 1.1.1.1:443 gave java.net.SocketException: Network is unreachable" on
all 3 legs.

## AC1g.4: the dormant split, proven once

Two runs of 24235857:
- [36300180213](https://github.com/chaotix345/rigtune/actions/runs/36300180213) (push, the default one part): the 3 legs
  as before, each running all 16 classes (the per-class lines: RigTuneClientGameTest 49-57 s, BenchmarkGameTest 95-157 s,
  FootprintGameTest 82-84 s, A11yGameTest 23-25 s, the others 2-41 s), every job green.
- [36300926064](https://github.com/chaotix345/rigtune/actions/runs/36300926064) (`gh workflow run build.yml --ref
  feat/v05-ci -f gametest_parts=2`): 6 game-test jobs, "client game tests (<mc>, <backend>, part 1/2)" with
  RigTuneClientGameTest to PreviewGameTest and "… part 2/2" with ProfilesGameTest to A11yGameTest. Per leg the two parts
  logged 16 "Game-test class" lines for 16 different classes, every job green. The parts' class time: 238-282 s (part 1),
  176-181 s (part 2); job wall times 5.6-6.6 min and 4.7 min, against 7.4-9.8 min unsplit in 36300180213.

With the switch as one flag in build.yml (`GAMETEST_PARTS`) and "runTest returned" lines, on c890b1ea:
[36303836102](https://github.com/chaotix345/rigtune/actions/runs/36303836102) (push, one part) green, and
[36304357982](https://github.com/chaotix345/rigtune/actions/runs/36304357982) (`-f gametest_parts=2`): 6 jobs green, per
leg 16 "runTest returned" lines for 16 different classes, part 2 ending FootprintGameTest, A11yGameTest.

## SPEC-4: e2eClient offline

Locally (Windows, under the game-test lock): `./gradlew :26.2:prefetchDependencies :26.2:downloadAssets`, then
`./gradlew --offline :26.2:e2eClient -Pe2e.driver=undo -Pe2e.instance=<empty scratch instance>` with
`-Djava.awt.headless=true`: the driver built and the client launched offline ("Loading Minecraft 26.2 with Fabric Loader
0.19.5"), then stopped at Fabric's dependency check as intended ("requires any version of rigtune, which is missing"). The
cache was warm, so this shows e2eClient needs nothing online beyond it; WS-E's E2E jobs run the same two prefetch tasks in
their network step.

## AC1c: caches and pins

- AC1c.2: `./gradlew buildEnvironment` resolves `net.fabricmc.fabric-loom.gradle.plugin:1.17.21 -> net.fabricmc:fabric-loom:1.17.21`.
  `:26.2:jar` and `:26.3:jar` built under the pin (`--rerun`) are byte-identical to the 1.17-SNAPSHOT build of the same
  commit: sha256 `7027e993…` (26.2) and `8957760d…` (26.3) (docs/research/v0.5/ci-robustness.md 1.2).
- AC1c.4 (run 36297288375): the java job restored `e2e-old-8294d04a6b67e76d-67275e232fe4de9f-5717f65cb90c71ae`, so its
  `gh release download` never ran (`[ -s … ] ||`), and the three jars passed `sha256sum -c`; the Vulkan leg restored
  `lavapipe-ubuntu24-20260920.314.1` and installed `mesa-vulkan-drivers 25.2.8-0ubuntu0.24.04.2` from the cached `.deb`s
  (the step took 2 s).
- AC1c.1: both runs of 24235857 (36300180213, 36300926064) restored the java job's Gradle cache (a restore-key hit on a
  `main` entry; this branch only reads) and their first `./gradlew` step, "Resolve dependencies (network)", took 18 s and
  20 s.
- AC1c.3: `tools/tests/test_ci_workflow.py`.

## Head green on every job, twice, no re-runs

A commit can't name its own runs: the final head's two runs are in the handoff to the coordinator (and PROGRESS).

## Flakes found after the first streak

| date | run | what | fix |
|---|---|---|---|
| 2026-09-27 | [36336797155](https://github.com/chaotix345/rigtune/actions/runs/36336797155) (94654c11, feat/v0.5.0) | the java job's `actions/setup-java` download of Microsoft JDK 25.0.3 failed its checksum (`sha256 expected 8c896f5e…, actual 1398c2c2…`); run 36336931445 downloaded it fine a minute later; every other job of the run was green | fix/v05-ci-jdk-retry: every JDK download in build.yml and release.yml is tried once more after 30 s, and a second failure fails the job; `continue-on-error` is allowed only there (test_ci_workflow.py `jdk_retry_problems`, JdkRetryTests). WS-E's e2e.yml follows the same rule. |
