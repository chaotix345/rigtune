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

## AC1e.4: local subset run twice

Windows 11, JDK 25.0.4, `:26.2:runProductionClientGameTest` with only RigTuneClientGameTest and BenchmarkGameTest as
entrypoints, twice in a row under `C:/Dev/Worktrees/.gametest-lock` (2026-09-27 15:30 and 15:33 AEST): both
`BUILD SUCCESSFUL` (178 s, 176 s), the second run's wipe of `build/run/productionClientGameTest` succeeded, no "New
files were found". Both runs leave a pending apply, so RigTune's apply helper ran after each.
Local check (a third subset run, 16:01 AEST, after the review fix): the helper applied its plan at 16:01:02.50 and `awaitApplyHelper` logged "waited 1758 ms; pending.json applied" before the build ended.

## AC1c: caches and pins

- AC1c.2: `./gradlew buildEnvironment` resolves `net.fabricmc.fabric-loom.gradle.plugin:1.17.21 -> net.fabricmc:fabric-loom:1.17.21`.
  `:26.2:jar` and `:26.3:jar` built under the pin (`--rerun`) are byte-identical to the 1.17-SNAPSHOT build of the same
  commit: sha256 `7027e993…` (26.2) and `8957760d…` (26.3) (docs/research/v0.5/ci-robustness.md 1.2).
- AC1c.4 (run 36297288375): the java job restored `e2e-old-8294d04a6b67e76d-67275e232fe4de9f-5717f65cb90c71ae`, so its
  `gh release download` never ran (`[ -s … ] ||`), and the three jars passed `sha256sum -c`; the Vulkan leg restored
  `lavapipe-ubuntu24-20260920.314.1` and installed `mesa-vulkan-drivers 25.2.8-0ubuntu0.24.04.2` from the cached `.deb`s
  (the step took 2 s).
- AC1c.1 (the second run of one SHA restores the Gradle cache; its first `./gradlew` step under 60 s): PROOF_C_CACHE
- AC1c.3: `tools/tests/test_ci_workflow.py`.

## Head green on every job, twice, no re-runs

PROOF_C
