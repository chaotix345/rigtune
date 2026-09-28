# v0.5 research: CI robustness (r-ci)

2026-09-27. Scope: SPEC P0.1 of v0.5 (no live Modrinth in game tests or CI, cached jars, robust footprint ns gates,
known flakes fixed; acceptance: 5 consecutive full `build.yml` runs green on `feat/v0.5.0`, no re-runs).

Evidence, all reproducible:
- `build.yml` history: 541 runs (2026-09-24 04:19 to 2026-09-27 00:23 UTC; 392 success, 111 cancelled, 37 failure,
  9 with a second attempt), job data for every failed/cancelled/re-run attempt, and the 94 failed job logs, in the
  scratch folder `C:/Users/Admin/AppData/Local/Temp/claude/C--Dev-Minecraft-Setting-Optimisation-Mod/590d2d3e-58b4-418a-a809-0e625214088f/scratchpad/r-ci`
  (`runs.json`, `jobs.json`, `logs/`, `classify.txt`).
- Footprint numbers: 334 `footprint-*.json` artifacts from 223 runs (`fp/`, `fp_rows.json`), 584 FrameHookBudgetTest
  values from 215 runs' `test-reports` (`fh.json`).
- Experiments on branch `research/v05-ci` (never to be merged; worktree `C:/Dev/Worktrees/rigtune-r-ci`):
  commit 6ca8c080 (runs [36286920450](https://github.com/chaotix345/rigtune/actions/runs/36286920450) ci-experiment,
  [36286920437](https://github.com/chaotix345/rigtune/actions/runs/36286920437) build) and 434b250c
  ([36288312579](https://github.com/chaotix345/rigtune/actions/runs/36288312579) ci-experiment,
  [36288312580](https://github.com/chaotix345/rigtune/actions/runs/36288312580) build). They add
  `TimingProbeGameTest` (tick-hook timing estimators), `HangWatchdog` (thread dump + halt), `.github/ci/prefetch.init.gradle`
  and `.github/workflows/ci-experiment.yml`; all reusable for Phase 3. Both `build.yml` runs on that branch were green
  with Loom pinned to 1.17.21.

## Summary

| # | Item | Recommended fix | Files touched | Effort | Risk |
|---|---|---|---|---|---|
| 1 | Game tests reach the internet (Modrinth API, Mojang, Realms) | Run the game-test step in a loopback-only network namespace with `--offline`, after a separate prefetch step. Proven: all 3 legs green offline (run 36288312579). | `build.yml` | S | Low |
| 2 | No fake Modrinth for game tests | Start the E2E's `FakeModrinth` (single-file program, `--port 0`) from a Gradle BuildService beside `RulesFixtureServer`; catalog generated from `productionGameTestMods` + a committed candidate list; pass `-Drigtune.modrinth.baseUrl`. No product switch needed. | `build.gradle`, `tools/e2e/java/.../FakeModrinth.java` (+`/v2/versions`), new `tools/gametest/modrinth-candidates.json`, `FakeModrinthTest` | M | Low-Med |
| 3 | `rigtune.modrinth.baseUrl` accepts any URL for API calls | Validate like `RulesSources.baseUrl` (https, or http on loopback) and log one WARN when overridden | `HttpModrinthClient.java` (+ test) | S | Low |
| 4 | Java job has no Gradle cache; Loom is a SNAPSHOT; downloads have no retry | `setup-gradle` in the java job; cache writes on `feat/v0.5.0`; `loom_version=1.17.21` (jars byte-identical); Gradle repository retries; prefetch step with a bounded retry loop; cache the 3 released E2E jars and the lavapipe `.deb` | `build.yml`, `gradle.properties` | S | Low |
| 5 | Per-call ns gates flake (2 of ~133 CI legs since the monitor gates existed; 4 of 567 probe measurements) | (a) budgets = min(ceiling, 4 x max observed) now; (b) measure each hook interleaved with a fixed reference workload, gate the median ratio (catches 2x with ~23 % margin both ways, n = 189), and assert a built-in "hook twice" twin exceeds the limit on every run | `tools/footprint-budgets.json`, `FootprintGameTest`, `FrameHookBudgetTest`, `FootprintBudgetsTest` | M | Low |
| 6 | UiGameTest settings wait polls the file (100 ticks) | Wait on `SettingsSaver.shared().flush(...)`, then read `settings.json` once | `UiGameTest.java` | S | Low |
| 7 | BenchmarkHistoryGameTest network-off race | One shared `setNetwork` helper: save, `settingsChanged()`, wait for a report with `online() == false` | 5 game-test classes + new `GameTestNet.java` | S | Low |
| 8 | BenchmarkGameTest hang on 26.3 GL (run 36235561444) | Not reproducible (0 hangs in 47 targeted runs); make it fail fast with evidence: SIGQUIT thread dump at 13 min (workflow) and/or an in-JVM watchdog; step timeout 15 min | `build.yml`, optional `HangWatchdog.java` | S | Low |
| 9 | Run-dir wipe races RigTune's post-exit helper (only when a subset of classes runs) | `runProductionClientGameTest` waits for `config/rigtune/apply.lock` to be free (doLast, bounded) | `build.gradle` | S | Low |
| 10 | Floating environment (ubuntu-latest, JDK patch, Mesa from apt) | Pin `ubuntu-24.04` for every job, pin the JDK patch, record image + Mesa versions per leg | `build.yml` | S | Low |
| 11 | 5-run acceptance | Sequential `gh workflow run build.yml --ref feat/v0.5.0` (the concurrency group cancels overlapping runs), checked and recorded by a small script | new `tools/ci_streak.py`, `docs/v0.5/verification/ci-streak.md` | S | Low |
| 12 | Wall time | Items 1, 2 and 4 take the network waits out of the critical path (the java job's first `./gradlew`: 39 s median, 292 s worst today); step timeout 15 min; sharding not now | `build.yml` | S | Low |

Decisions for the coordinator (details in the sections):
1. Item 1 changes where the game tests run (a network namespace via `sudo unshare --net`); it is Linux-runner-only and
   needs `sudo`, which GitHub-hosted runners have.
2. Item 3 is a small product change (validation of an existing system property) that is not strictly needed for CI.
3. Item 5(b) changes what `FootprintGameTest` gates for `tickHookNsPerCallOn` (a dimensionless ratio instead of ns). The
   user's approval covers "redesigning how those timings are measured"; the SPEC 10 text should say so.
4. Not covered by the approval, but close to their limits: `renderThreadInitCpuMs` (max 131.1 of 150, 87 %) and
   `clientStartedWallMs` (max 102.2 of 141, 73 %). No flake so far; see 4.4.
5. A product observation for triage: from 16:39 to 19:19 UTC on 2026-09-26 every RigTune Modrinth lookup in CI failed
   within ~2 s with `java.io.IOException: Stream N cancelled` (19-20 per leg), and the game tests still passed on the
   offline path. Cause UNVERIFIED (see 1.3).

## 1. Network dependence inventory

### 1.1 Repositories and hosts

Printed from the build itself (an init script listing every repository, run in the worktree):

```
pluginManagement: https://maven.fabricmc.net/, https://plugins.gradle.org/m2, https://repo.maven.apache.org/maven2/
:26.2 (and :26.3): Fabric https://maven.fabricmc.net/, MavenRepo https://repo.maven.apache.org/maven2/,
  Modrinth https://api.modrinth.com/maven, Mojang https://libraries.minecraft.net/,
  TerraformersMC https://maven.terraformersmc.com/releases, plus Loom's local file repositories
```

Loom also downloads the Minecraft jars from `piston-meta`/`piston-data.mojang.com` (seen failing in run
36176378908) and the assets in `:<mc>:downloadAssets` (host not checked: UNVERIFIED).

### 1.2 Every place a job or test reaches the internet

| Where | Job / test | Host(s) | On an outage (evidence) | Fix |
|---|---|---|---|---|
| Gradle distribution | java (every run, no cache: `Downloading https://services.gradle.org/distributions/gradle-9.5.1-bin.zip` in run 36282354607) | services.gradle.org | job fails at the first `./gradlew` | `setup-gradle` caches `wrapper/dists` (item 4) |
| Plugins | java (every run); game-test legs on a cache miss | plugins.gradle.org, maven.fabricmc.net, Maven Central | `Plugin [id: 'dev.kikugie.stonecutter', version: '0.9.8'] was not found` (36177595248, 26.2 GL) | cache + prefetch with retry + Loom pin (item 4) |
| Loom 1.17-SNAPSHOT | every configuration; Gradle re-checks a changing module every 24 h | maven.fabricmc.net | would fail or silently change the build tool | `loom_version=1.17.21` (the snapshot marker's `<dependency>` is `fabric-loom:1.17.21`; `:26.2:jar`/`:26.3:jar` rebuilt with `--rerun` are byte-identical: `7027e993…`, `8957760d…`) |
| Project dependencies | java (every run); legs on a cache miss | Maven Central, Fabric, Modrinth maven, TerraformersMC, libraries.minecraft.net | Maven Central `403 Forbidden` for gson/asm (36251656520); Modrinth maven `520` for Sodium (36254171669 attempt 1) and `Read timed out` for Iris (attempt 2) | cache + prefetch with retry (item 4) |
| Minecraft jars | java; legs on a miss | piston-data.mojang.com | `DownloadException: Failed to download file from (https://piston-data.mojang.com/…/server.jar)` (36176378908) | cache (Loom's files live in `~/.gradle/caches/fabric-loom`, which `setup-gradle` saves) |
| Game assets | legs (`downloadAssets`) | Mojang asset host (UNVERIFIED) | none seen | cache + prefetch |
| Released E2E jars | java, "Compile the E2E drivers" (3 × `gh release download`) | github.com | none seen | `actions/cache` keyed by the three pinned sha256s; download only on a miss |
| lavapipe | Vulkan leg (`apt-get update && apt-get install mesa-vulkan-drivers`, 12.4 MB index + 17.5 MB .deb) | azure.archive.ubuntu.com | none seen; the installed Mesa also floats with the archive (`25.2.8-0ubuntu0.24.04.2` today) | cache the .deb keyed by `$ImageOS-$ImageVersion`; log the version (item 10) |
| RigTune's Modrinth client | every game-test leg: `OnlineDataFetcher` at start and after every `rescan()`/`settingsChanged()` with Modrinth allowed; PreviewGameTest's real preview; FootprintGameTest's 5 s worker window | api.modrinth.com (and cdn.modrinth.com for downloads, which no game test does) | 502/503/timeouts 12:06-12:55 UTC 2026-09-26 turned 6 runs red (UiGameTest, see 4.3); request timeouts at 16:26 made PreviewGameTest's 5-minute wait expire (36255335999 attempt 1); 16:39-19:19 every lookup failed (`Stream N cancelled`) but tests passed | fake Modrinth on loopback (item 2) inside the no-network namespace (item 1) |
| RigTune's rules | every leg | raw.githubusercontent.com | none: already served by `RulesFixtureServer` on loopback (`build.gradle:360-368`) | keep |
| Minecraft's own services | every leg | Mojang's player-attributes endpoint (HTTP 401 for the dev account), discovery.minecraftservices.com, pc.realms.minecraft.net | harmless: the offline runs log `UnknownHostException` / Realms 500 and pass | nothing (the namespace makes them fail fast and identically every run) |
| Unit tests (JUnit) | java | none | `./gradlew --offline build` passed with loopback only (run 36288312579, offline-unit: `gradle-exit=0`) | keep; the namespace enforces it |
| Python tests | python | none | `tools/tests` and `tools/e2e/tests` passed with loopback only (same job: `py-exit=0`, `py-e2e-exit=0`, 216 e2e tests OK) | keep |
| release.yml, snapshot-canary.yml, update-rules.yml | not part of the acceptance | GitHub, Modrinth API, Mojang, Fabric meta | by design | leave online; don't count them in the streak |

Proof that nothing live is needed, and stays so: in run 36288312579 every game-test leg ran its full suite with no
route off the machine (`sudo unshare --net`, loopback only, `--offline`) and passed; with that as the normal CI setup,
any new live dependency fails on every run instead of on outage days.

The self-update E2E on Linux (r-verify's topic) uses the same `FakeModrinth` with TLS and a hosts file; it can run inside
the same namespace, which then proves the E2E never leaves the machine either.

### 1.3 Live Modrinth changes what the game tests exercise, run to run

Per-leg count of `Modrinth lookups failed; using offline data: …` in game-test logs (the full table is in
`scratchpad/r-ci`, computed over every downloaded log):

| When (UTC, 2026-09-26/27) | Runs | Per leg | Cause |
|---|---|---|---|
| until 09:52 | many | 0-5 | only "Modrinth is off in RigTune's settings" (tests turning it off) |
| 12:06-12:55 | 36240897813 … 36243402401 | 6-8 | `POST https://api.modrinth.com/v2/version_files returned HTTP 502/503`, `HttpTimeoutException` |
| 16:24 | 36255335999 a1 | many `Could not check fabric versions of <slug>: HttpTimeoutException` | Modrinth timeouts |
| 16:39-19:19 | 36255335999 a2, 36263433380, 36265652232 | 19-20 | `java.io.IOException: Stream N cancelled`, ~2.6 s after start |
| 00:24 (09-27) | 36282354607 | 0-4 | normal |

In the "Stream N cancelled" window every report in every game test was offline, yet all legs passed: the tests don't
assert online data, so a Modrinth-side change silently switches the code path under test. Whether that exception is a
peer reset (HTTP/2 RST_STREAM) or a local cancellation is UNVERIFIED; `BoundedHttp.send` only cancels on its own
stall/deadline timeouts (30 s / 60 s), which don't fit a 2 s failure. Worth a product look (log the cause chain; retry
once on an HTTP/2 stream reset), since players would see the same.

## 2. A local fake Modrinth for the game tests

### 2.1 What each game-test class asks Modrinth (from the code)

All 16 classes share one JVM with the real `RealController`. With the network and Modrinth allowed (the default), every
`rescan()`/`settingsChanged()` and the start run `OnlineDataFetcher.fetchAll` (`OnlineDataFetcher.java:74-153`):

| Request | Purpose | Needed answer |
|---|---|---|
| `POST /v2/version_files` `{"hashes":[sha1…],"algorithm":"sha1"}` | installed jars → Modrinth versions | versions for the fabric-api, Mod Menu and Sodium jars of `productionGameTestMods` (by their real sha1); RigTune's dev jar and the test mod unknown |
| `POST /v2/version_files/update` (+ `loaders:["fabric"]`, `game_versions:["<mc>"]`) | update offers (also RigTune's own) | the same versions (no update), optionally one newer version to exercise the update row |
| `GET /v2/projects?ids=[slug…]` | availability of the rules' candidate mods | project JSON (`id`, `slug`, `status`, `game_versions`, `loaders`) for the candidates |
| `GET /v2/project/{id}/version?loaders=["fabric"]&game_versions=["<mc>"]&include_changelog=false` | "a version exists for this MC" (parallel "RigTune Modrinth check" threads) | one version per candidate that supports `<mc>` |

Classes, and what they add on top:

| Class | Network in the class | Modrinth-specific need |
|---|---|---|
| RigTuneClientGameTest, BenchmarkGameTest, UndoGameTest, ReportGameTest, HistoryGameTest | on | start-up lookups only; settings-only applies, no downloads |
| LauncherGameTest | on | `real.rescan()` per launcher signal → the four requests again |
| UiGameTest | toggles off / Modrinth off / on | lookups after each `settingsChanged()`; asserts `!report.online()` with the network off (`UiGameTest.java:503`) |
| PreviewGameTest | on | the real preview of the report's ticked items (`PreviewGameTest.java:185-217`): `DependencyResolver` → `GET /v2/project/{slug}/version` per ticked addition, `GET /v2/projects?ids=` for missing required dependencies, `GET /v2/versions?ids=` only when `pending.json` stages versions (never in CI). Waits up to 6000 ticks (5 min) because the lookups are sequential |
| ProfilesGameTest, StutterGameTest, JvmGameTest, A11yGameTest, BenchmarkHistoryGameTest | off (X1) | none (A11y's preview uses a stub controller) |
| ServerLimitsGameTest, AwarenessGameTest | off, then on again | the four requests after re-enabling |
| FootprintGameTest | on | the start-up lookups inside the 5 s `workerCpuMs5s` window (WS-F deviation 1) |

No game test downloads a file, and none asserts a value that came from Modrinth.

### 2.2 Options

| Option | For | Against |
|---|---|---|
| A. `tools/e2e`'s `FakeModrinth`, started as a child process (`java FakeModrinth.java --catalog … --port 0 --bind 127.0.0.1 --log …`) by a Gradle BuildService | Already serves `version_files`, `version_files/update`, `projects`, `project/{ref}`, `project/{ref}/version`, `version_file/{hash}` and CDN paths; answers from real jar hashes; request log; covered by `FakeModrinthTest` (which also runs the real `HttpModrinthClient` against it over loopback http); run exactly as the E2E runs it; one fake for E2E and game tests | Needs `/v2/versions?ids=` (also missing for WS-A's 2d reverse check); catalog `file` paths are absolute, so the catalog is generated at execution time; a second process (≈1 s start, in-memory compile) |
| B. Grow `RulesFixtureServer` (`build.gradle:225-260`) into a Modrinth fake | In the Gradle daemon, no process, already wired | Re-implements A in untested Groovy inside `build.gradle` |
| C. Serve from inside the game JVM (a gametest `preLaunch` entrypoint sets the property) | No Gradle work | Pollutes FootprintGameTest: its class histogram counts `io.github.chaotix345.rigtune.*` classes and its thread census counts threads; ordering vs `onInitializeClient` is fragile |
| D. No fake: only the no-network namespace | Zero code; proven green | Every lookup fails fast, so the online code path (availability, update offers, the preview's dependency resolution) is never exercised in CI |

### 2.3 Pointing the client at it, and is the switch safe to ship?

The switch already exists and ships: `HttpModrinthClient.BASE_URL_PROPERTY = "rigtune.modrinth.baseUrl"`
(`HttpModrinthClient.java:45`, read in the constructor at `:71-77`); downloads are then also allowed from that origin
only if it is https or http on loopback (`allowedDownload`/`extraDownloadOrigin`, `:269-288`). The E2E doesn't need it
(it redirects the real hostnames with `-Djdk.net.hosts.file` and a trust store, `tools/e2e/README.md:206-210`), so a
game-test run passes `-Drigtune.modrinth.baseUrl=http://127.0.0.1:<port>` exactly like `-Drigtune.rules.baseUrl` today.
No new test-only switch is needed.

Risk of honouring it in the shipped jar: setting a JVM property requires control of the launch command, which already
allows arbitrary code (`-javaagent`, a jar in `mods/`), so it grants nothing new. One gap: unlike `RulesSources.baseUrl`
(`RulesSources.java:41-56`), the Modrinth API base accepts any scheme and host, so `-Drigtune.modrinth.baseUrl=http://…`
would send lookups in clear text to a remote host that could offer any `cdn.modrinth.com` file as an "update". Item 3:
validate it the same way (https, or http on loopback) and log one WARN naming the override.

### 2.4 Recommended design (option A)

1. `FakeModrinth.java`: add `GET /v2/versions?ids=[…]` (versions by id); keep it JDK-only and single-file. Test in
   `FakeModrinthTest`.
2. `tools/gametest/modrinth-candidates.json` (committed): the candidate projects (id, slug, title, per-node
   `game_versions`, dependencies: e.g. Lithium requires fabric-api, one "incompatible with Sodium" relation), fixed ids and
   dates.
3. `build.gradle`, per node: a task `gametestModrinthCatalog` writes `build/gametest-modrinth/catalog.json`: the installed
   mods from `configurations.productionGameTestMods` (so hashes match what the game loads) plus the candidates, each with
   a tiny generated jar (deterministic bytes). A BuildService `ModrinthFixture` (next to `RulesFixtureServer`) starts
   `java <javaLauncher> tools/e2e/java/…/FakeModrinth.java --catalog … --bind 127.0.0.1 --port 0 --log build/gametest-modrinth/requests.jsonl`,
   reads the port from its "FakeModrinth listening on http://127.0.0.1:NNNNN" line, and destroys the process in
   `close()`. `runProductionClientGameTest` gets `usesService`, a dependency on the catalog task, and
   `jvmArgs.add(fixture.map { "-Drigtune.modrinth.baseUrl=" + it.baseUrl() })`.
4. Optional: serve the rules from the same process (the catalog's `static` entries) and drop `RulesFixtureServer`.
5. `build.yml`: upload `requests.jsonl` with the logs; a step can fail the leg if any request to the fake returned 5xx.
6. PreviewGameTest: the real preview then takes seconds; lower its wait from 6000 ticks to e.g. 1200.

Consequence for the footprint guard: without TLS the "RigTune network" thread's CPU in the 5 s window drops (offline
runs: 2.7-3.6 ms; online CI: 2.5-45.5 ms over 79 legs). `workerCpuMs5s` (a CPU-ms budget, kept strict) only gets more
headroom; SPEC 10's note "Modrinth is on during the 5 s window" should say "against the local fake".

## 3. Caching the downloaded jars

### 3.1 Today

- `java` job (`build.yml:21-84`): `actions/setup-java@v6` without `cache:` and no `setup-gradle`, so nothing is cached;
  every run downloads the Gradle distribution, all plugins and dependencies, and Loom's Minecraft files. Its "Check
  sources" step (the first `./gradlew`) takes 39 s median, 292 s max over 26 recent green runs.
- Game-test legs (`build.yml:124`): `gradle/actions/setup-gradle@v6` with defaults: the cache is written only on the
  default branch and restored read-only elsewhere (`cache-read-only: true` in the leg log; restored
  `gradle-home-v2|Linux-X64|client-gametest[…]-987179e4…`, 776 MB, from main). A dependency that changed on a
  feature branch is downloaded on every run of that branch.
- `snapshot-canary.yml`: `setup-gradle` read-only by design.

### 3.2 What still needs the network on a warm cache

Measured, not guessed: with the restored cache, `./gradlew --offline :<mc>:runProductionClientGameTest` failed on 26.2
because `minecraftTestClientRuntimeLibraries` natives (`lwjgl-*-natives-linux.jar`, `jtracy-…-natives-linux.jar`) were
not in the restored entry (run 36286920450, offline 26.2), and `./gradlew --offline build` after `assemble testClasses
gametestClasses` failed on `testRuntimeClasspath` (`dev-launch-injector`, `fabric-log4j-util`,
`junit-platform-launcher`). After a prefetch that resolves every resolvable configuration (48 per node,
`.github/ci/prefetch.init.gradle` on the research branch), `--offline` succeeded everywhere: `build` + unit tests, and
the full game-test suite on 26.2 GL, 26.3 GL and 26.3 Vulkan (run 36288312579). The Loom SNAPSHOT is the only changing
module; nothing else re-checks the network once resolved.

### 3.3 Design

1. `java` job: add `gradle/actions/setup-gradle@v6` (as the legs). Let `feat/v0.5.0` write the cache too:
   `cache-read-only: ${{ github.ref != 'refs/heads/main' && github.ref != 'refs/heads/feat/v0.5.0' }}` (a branch reads
   its own caches and the default branch's, so runs on the integration branch then start warm even when its
   dependencies differ from main's).
2. `gradle.properties`: `loom_version=1.17.21`; `systemProp.org.gradle.internal.repository.max.tentatives=6` and
   `systemProp.org.gradle.internal.repository.initial.backoff=1000`. Both names are in Gradle 9.5.1
   (`ErrorHandlingModuleComponentRepository$ErrorHandlingModuleComponentRepositoryAccess` in
   `gradle-dependency-management-9.5.1.jar`); they are internal, and whether they also cover artifact downloads (not only
   metadata) is UNVERIFIED. Loom's own Minecraft downloads don't go through them (their retry behaviour: UNVERIFIED).
3. A "Resolve dependencies" step before the build/test steps, the only step that touches the network:
   `./gradlew --no-daemon --init-script .github/ci/prefetch.init.gradle prefetchDependencies <node tasks>` inside a bash
   loop of 3 attempts with 30 s / 90 s back-off. Retrying a download can't hide a product regression; the no-retry rule
   (`build.yml:103`) stays for tests. The init script (or an equivalent `prefetchDependencies` task in `build.gradle`)
   resolves each project's own configurations in a task action; it logs and skips a configuration that can't resolve.
   `--no-daemon` matters (reasoning, not tested the other way): a daemon left running holds the Gradle cache locks, and
   a build in another network namespace can't reach it over loopback to ask for them.
4. Tests then run `--offline`; the game-test step also runs inside `sudo unshare --net` (loopback only), so an
   accidental network dependency fails every time instead of sometimes (summary item 1).
5. `actions/cache` for `build/e2e-old` keyed by the three sha256s (`build.yml:57-61`), and for the lavapipe `.deb`
   keyed by `${ImageOS}-${ImageVersion}` (install with `dpkg -i` on a hit).
6. Gradle dependency verification (`gradle/verification-metadata.xml`, sha256 for every artifact) is not needed for
   flakes: it protects against a changed artifact, not an unavailable one, and Loom's generated local artifacts would
   need trust rules. Worth it later for supply-chain integrity; not in P0.1.
7. A committed mirror is neither needed nor allowed in full: Mojang's EULA forbids redistributing Minecraft's jars, and
   Sodium's licence (Polyform Shield, per its Modrinth page; UNVERIFIED here) restricts redistribution. The Actions cache
   is private to the repository's workflows, not a redistribution.

## 4. Flakes: every failure, classified

### 4.1 Rates

Since the full v0.4 suite existed (2026-09-26 08:00 UTC): 84 completed, non-cancelled runs; 14 of them (16.7 %, about
1 in 6) failed on the first attempt for a reason that wasn't the code under test: 9 infrastructure outages, 5 flakes.
At that rate 5 green runs in a row happen with probability 0.83^5 = 0.40.

### 4.2 Every failed attempt of build.yml

Real = a genuine defect in the code or workflow under test (caught correctly). Probe = a deliberate red commit.

| Run (attempt) | Branch | Job(s) | Signature | Class | Root cause / fix |
|---|---|---|---|---|---|
| 35955174491 (1), 35955237339 (1) | feat/rigtune-mvp | java | `./gradlew: Permission denied` | real | wrapper not executable; fixed then |
| 35957057350 | feat/rigtune-mvp | java | RecommenderScenarioTest x2 | real | v0.1 development |
| 36084116874 | feat/v0.2.0 | java (26.3) | FakeModrinthTest.requestsAreLogged | flake | the fake logged after answering; fixed in 43f854f8 ("Logged before answering", `FakeModrinth.java` handle()) |
| 36105310899 | docs/v0.2-release | python | rules-v1 projection, GalleryTests | real | branch state |
| 36167437975 | feat/v03-foundation | 3 legs | `CI failure probe (WS-0 AC2.2)` | probe | |
| 36176378908 | feat/mc-tooling | 26.2 GL | Loom `Failed to download … piston-data.mojang.com/…/server.jar` | infra | Mojang; no cache on feature branches then |
| 36177595248 (1) | feat/launcher-ram | 26.2 GL | Stonecutter 0.9.8 plugin not found in Fabric/Maven Central/plugin portal | infra | plugin resolution outage; attempt 2 green |
| 36202977666 | main | 3 legs | backend check: `latest.log doesn't name the … backend` | real (CI) | run crossed midnight UTC; the check now reads rotated logs (7dad498b, PR #5) |
| 36216633708 | feat/v04-contracts | 3 legs | `notice line shows test-server with 1 more` | real | |
| 36217288857, 36229741259 (1) | feat/v0.4.0 | one leg | `preview 640x480-scale2: '' [0,32 320x180] overlaps ''` | flake (product race) | Preview/History/Undo started their async load before adding widgets (duplicate widgets); fixed bc3a2f3c |
| 36221492908, 36221770754, 36222476889 | fix/v04-deferred | java | PreviewDifferentialTest.withItemsThatMeetInOneBatch | real | |
| 36222562070 | feat/awareness | 3 legs | ServerLimitsGameTest `TimeoutException` at `dedicatedServer:93` | real | EULA not accepted for the in-run server; fixed 1740cef3 |
| 36222846655, 36222850330 | scratch/ws-f-proof-* | all | footprint gate proofs | probe | |
| 36223132295 | feat/profiles | java (26.2) | ShareCodeFuzzTest.oversizedInputIsRejectedBeforeDecodingAndFast | flake | worst of 2000 samples < 5 ms; now mean and p99 (39c631ed) |
| 36223268473 | feat/awareness | all | `unclosed string literal` | real | |
| 36225154458, 36225492097 | feat/profiles | java, python | compat030 `RESULT FAIL`, test_e2e_downgrade | real | |
| 36225350621, 36225769403 | feat/stutter | 3 legs | StutterGameTest checks | real | |
| 36225769403, 36231385325, 36231594937, 36232640349 | several | java (one node) | FrameRingAllocationTest.aMillionFramesAllocateNothing | flake | JIT bookkeeping allocations; 64 KiB noise bound (e8e5195b) |
| 36226332673 | fix/v04-deferred | python | test_e2e_downgrade | real | |
| 36227826218 | feat/profiles | 3 legs | `rigtuneClassBytesIdle: 114568 > 109296` | real (budget) | new code; recalibrated |
| 36231493749 | feat/footprint-monitor | 3 legs | `monitorOffLeftoverInstances: 6 > 0` | real | the leak check counted the Snapshot EMPTY constants; test fixed |
| 36231584362 | fix/mouse-left-263-red | 26.3 legs | HistoryGameTest `Timed out waiting for predicate` | probe | deliberate red: 26.3's left button is 1 |
| 36232105975, 36232640349 | feat/footprint-monitor | 3 legs | `samplerCpuMsPer60s: 69.71 > 30` / `50.30 > 30` | real (budget) | ceiling raised to 120 by the coordinator |
| 36233537368, 36234089522 | feat/a11y | 3 legs | narration / high-contrast checks | real | |
| 36235561444 (1) | feat/a11y | 26.3 GL | step timed out after 20 min | flake (hang) | 4.3.1 |
| 36240897813 (1 and 2), 36241924437 (1), 36242449310, 36242976610, 36243219955, 36243402401 | fix/review-7, test/p5-*, ci/review-7-base-control, feat/v0.4.0 | 2-3 legs each | UiGameTest `waitForSaved` (`UiGameTest.java:412`) timed out | infra (Modrinth 502/503/timeouts) exposing a product design issue | 4.3.3 |
| 36251656520 | feat/v0.4.0 | java | Maven Central `403 Forbidden` (gson, asm) | infra | item 4 |
| 36254171669 (1 and 2) | docs/v0.4-release | java | Modrinth maven `520` (Sodium), then `Read timed out` (Iris) | infra | item 4 |
| 36255335999 (1) | docs/v0.4-release | 26.2 GL | PreviewGameTest `waitForPreview` 6000 ticks | infra (Modrinth timeouts) | item 2 |
| 36255335999 (2) | docs/v0.4-release | 26.2 GL | `tickHookNsPerCallWorld: 91.62 > 87` | flake (JIT timing) | section 5 |
| 36263433380 (1) | main | 26.3 GL | `tickHookNsPerCallOn: 116.22 > 101` | flake (JIT timing) | section 5 |

Re-runs (attempt 2): 36263433380, 36255335999, 36254171669, 36244131778 (a deliberate second green run), 36241924437,
36240897813, 36235561444 (cancelled by a newer push), 36229741259, 36177595248. Every one is in the table.

### 4.3 The mandatory root causes

#### 4.3.1 BenchmarkGameTest hanging on 26.3 GL (run 36235561444)

- Evidence: attempt 1's `latest.log` (artifact 10904372597) ends at 10:25:31 with the test thread's
  `Benchmark game test: server's requested view distance 5 after the cancelled Tune (render distance 5)`
  (`BenchmarkGameTest.java:396`); the next statement is `context.runOnClient(BenchmarkWorld::exitNow)` (`:397`). In every
  good run (attempt 2, and all 48 repeats below) the server logs `<player> lost connection: Disconnected` and
  `Stopping singleplayer server as player logged out` within the same second. Here nothing more was logged for 17 min,
  from any thread, until the 20-minute step timeout. The server had logged `Saving and pausing game...` (the Esc that
  cancelled the run also opened the pause screen), which is also the case in the good runs.
- So the disconnect never reached the integrated server: either `exitNow` never ran on the render thread, or
  `Minecraft.disconnectFromWorld` blocked before closing the connection. Hypotheses, none verifiable without a thread
  dump: (a) a client/server lock-step deadlock in the Fabric client-gametest harness during a world exit (PROGRESS
  "Lessons" already records harness deadlocks on world exit with Xaero's World Map or DH); (b) the render thread
  blocked in the driver (SDL3 + llvmpipe + Xvfb) on 26.3's GL path; (c) the test thread's `runOnClient` hand-off lost.
- Repro attempts: 48 JVMs running RigTuneClientGameTest + BenchmarkGameTest on 26.3 OpenGL with a 9-minute watchdog
  (12 jobs × 4, runs 36286920450 and 36288312579): 0 hangs (47 ran and passed in 147-221 s; the 48th never started, see 4.4).
  CI rate: 1 in ~290 legs.
- Deterministic fix: make the next occurrence fail in minutes with the evidence instead of guessing a product change.
  In `build.yml`, before the game-test command:
  `( sleep 780; for p in $(pgrep -f fabric.client.gametest); do [ "$(cat /proc/$p/comm)" = java ] && kill -QUIT "$p"; done ) &`
  (only the game's JVM, not the `xvfb-run` wrapper whose command line also matches; HotSpot answers SIGQUIT with a
  dump of every thread, its locks and any Java-level deadlock on stdout, which reaches the job log; not exercised here),
  and step timeout 15 min (the slowest green leg's step took 11.7 min). Optionally the in-JVM `HangWatchdog` from the research branch (dumps with
  `ThreadMXBean.dumpAllThreads(true, true)`, writes `footprint/watchdog-threads.txt`, halts with exit code 3). Proof:
  a probe commit that sleeps forever in a game test must fail the leg at 13 min with a thread dump naming the sleeping
  frame.

#### 4.3.2 BenchmarkHistoryGameTest's network-off screenshot race

- Evidence: WS-X's pixel diff (`docs/v0.4/design/ws-x.md:103-108`): screenshot 0147 showed a report built before vs
  after the network switch ("Apply (21)" vs "Apply (9)").
- Root cause: `BenchmarkHistoryGameTest.java:92` sets `ClientSettings.shared(configDir).networkEnabled = false` and
  never calls `settingsChanged()`, so whether the report on screen is the online or the offline one depends on when
  something else next rebuilds it (and on whether live Modrinth answered at start-up). The restore at `:101` has the same
  gap. Four other classes each carry their own `setNetwork` helper (A11y `:496`, Awareness `:249`, Jvm `:102`,
  ServerLimits `:276`) that save, call `settingsChanged()` and wait for `report() != null`.
- Fix: one shared helper (`GameTestNet.set(context, controller, configDir, on)`): save, `settingsChanged()`, then
  `waitFor(report != null && report.online() == (on && modrinthAllowed))`; use it in all five classes, including
  BenchmarkHistoryGameTest's restore. With the fake Modrinth the online report is also the same every run.
- Test that proves it: BenchmarkHistoryGameTest logs and asserts the report's `online()` and the Apply button's label
  before each screenshot; a pixel comparison of `bench-history-*` between two CI runs of the same SHA shows no difference
  outside the known regions (timestamps, the live stutter line).

#### 4.3.3 UiGameTest's settings wait (and does it now wait on SettingsSaver?)

- No. `UiGameTest.waitForSaved` (`UiGameTest.java:412-415`) still polls `ClientSettings.load(configDir)` for 100 ticks.
  4002af11 ("UiGameTest's wait is unchanged") fixed the cause in the product: settings were saved on
  `Probes.EXECUTOR`, the 2-thread pool that Modrinth lookups also used; with Modrinth returning 502/503 and timing out
  (the 6 runs, 7 attempts, in 4.2, including the control run 36242449310 of an unchanged base), both threads sat in
  lookups and the save missed the 100 ticks. `SettingsSaver` (own daemon thread) and f245ae9d (lookups on `Probes.NETWORK`) removed the
  starvation. 0 UiGameTest failures since.
- Remaining nondeterminism: the 5 s bound on a file appearing. Fix: after `cycle(...)` (the click queues the save
  synchronously on the render thread, `RigTuneSettingsScreen.java:125-128`), call
  `check(SettingsSaver.shared().flush(10_000), …)` from the test thread, then read `settings.json` once.
- Test that proves it: SettingsSaverTest already shows a save completes while both worker threads are blocked; add a
  UiGameTest variant (or a unit test of the helper) that blocks `Probes.NETWORK` and `Probes.EXECUTOR` and still sees the
  save.

#### 4.3.4 The footprint per-call ns gates (runs 36255335999, 36263433380)

Section 5. Root cause, measured: the best-of-5 blocks run while the JIT is still compiling. In the probe, the
monitor-on measurements whose best-of-5 was over 1.3 × the settled median (8 of 93 in run 36286920450) had a median of
29 ms of JIT compilation during the measurement; the others had 0 (`CompilationMXBean.getTotalCompilationTime()`
around the blocks).

### 4.4 Other nondeterminism found

| Finding | Evidence | Fix |
|---|---|---|
| Run-dir wipe vs RigTune's post-exit helper | hang job 5 of 36288312579: the second JVM's `runProductionClientGameTest` failed in 2 s: `Unable to delete directory …/productionClientGameTest … New files were found … config/sodium-options.json` (`build.gradle:335`). With only RigTuneClientGameTest + BenchmarkGameTest, the Sodium patch RigTuneClientGameTest stages is still pending at exit (`Started the RigTune apply helper for …/pending.json`), and the helper JVM was writing while Gradle deleted the folder. The full suite discards the staged ops first, so no helper starts at exit there (checked in 36282354607) | `runProductionClientGameTest` doLast: wait (≤ 60 s) until `<runDir>/config/rigtune/apply.lock` (`ApplyLock.FILE_NAME`) can be locked. Matters for subsets of classes: local repeat runs, experiments, any future sharding |
| `renderThreadInitCpuMs` headroom | 310 legs: p50 96.9, p99 118.5, max 131.1 against limit = ceiling 150 (87 %) | CPU-ms stays strict (user); lower the cost (it's class loading, mostly Gson in preLaunch, DESIGN "footprint") or ask the user |
| `clientStartedWallMs` headroom | p50 35.7, p99 75.0, max 102.2 against 141 (73 %); wall time | measure render-thread CPU instead, or recalibrate with the user's approval (not a per-call ns budget) |
| Other timing gates | `renderThreadInitWallMs` max 276.1 of 368 (75 %), `workerCpuMs5s` 224.9 of 300 (75 %), `samplerCpuMsPer60s` 73.2 of 102 (72 %) | fine; the fake Modrinth lowers `workerCpuMs5s` |
| Wall-clock unit-test bounds | `ShareCodeFuzzTest` mean/p99 < 5 ms (`:176-190`); `ThreadSamplerTest` `stop()` < 100 ms (`:339-343`) | low risk; the second could assert "returned while the hung source is still blocked" instead of 100 ms |
| Floating environment | `java`, `python`, rules jobs on `ubuntu-latest` (moves to 26.04 per the build.yml comment); `setup-java` `'25'` resolves the newest patch (25.0.3 now); Mesa comes from the image and apt | pin `ubuntu-24.04` everywhere; pin the JDK patch and bump it deliberately; print `$ImageVersion` and the Mesa version per leg |
| Runner hardware | 16 probe jobs landed on 5 CPU models (AMD EPYC 7763, 9V74, 9V45; Intel Xeon Platinum 8573C, 6973P-C) | normalise timing (section 5); record `cpu` in the footprint JSON |

## 5. Footprint per-call ns gates

### 5.1 Observed values

In CI (FootprintGameTest per leg; FrameHookBudgetTest per node in the java job), every run since each metric existed:

| metric | leg | n | min | p50 | p95 | p99 | max | current limit |
|---|---|---|---|---|---|---|---|---|
| tickHookNsPerCall | 26.2-OpenGL | 103 | 7.52 | 39.26 | 60.11 | 62.12 | 68.91 | 111 |
| tickHookNsPerCall | 26.3-OpenGL | 102 | 4.41 | 30.72 | 59.99 | 63.98 | 68.45 | 111 |
| tickHookNsPerCall | 26.3-Vulkan | 105 | 12.61 | 38.25 | 59.62 | 60.92 | 61.97 | 111 |
| tickHookNsPerCallWorld | 26.2-OpenGL | 40 | 5.48 | 18.18 | 35.19 | 72.48 | 91.62 | 87 |
| tickHookNsPerCallWorld | 26.3-OpenGL | 40 | 9.74 | 19.63 | 34.98 | 41.84 | 43.28 | 87 |
| tickHookNsPerCallWorld | 26.3-Vulkan | 41 | 10.57 | 21.25 | 28.06 | 34.84 | 35.11 | 87 |
| tickHookNsPerCallOn | 26.2-OpenGL | 44 | 30.55 | 42.78 | 46.71 | 47.57 | 48.00 | 101 |
| tickHookNsPerCallOn | 26.3-OpenGL | 44 | 34.56 | 43.22 | 47.75 | 87.15 | 116.22 | 101 |
| tickHookNsPerCallOn | 26.3-Vulkan | 45 | 30.41 | 43.08 | 50.29 | 64.85 | 74.15 | 101 |
| frameHookNsPerCallOff | java job, 26.2 | 123 | 0.084 | 0.216 | 0.570 | 0.609 | 7.207 | 13 |
| frameHookNsPerCallOff | java job, 26.3 | 123 | 0.083 | 0.213 | 0.631 | 5.572 | 7.422 | 13 |
| frameHookNsPerCallOn | java job, 26.2 | 57 | 22.777 | 34.700 | 38.846 | 39.063 | 39.080 | 75 |
| frameHookNsPerCallOn | java job, 26.3 | 57 | 22.767 | 34.705 | 38.522 | 39.083 | 39.149 | 75 |
| frameHookNsPerCallOnPhases | java job, 26.2 | 57 | 139.518 | 238.175 | 244.612 | 256.263 | 256.332 | 400 |
| frameHookNsPerCallOnPhases | java job, 26.3 | 57 | 139.160 | 238.251 | 244.189 | 255.551 | 255.554 | 400 |

(Includes runs before fix-9's best-of-5, which only affected the tick hooks; the scratch/ gate-proof branches are
excluded.) The frame hooks are bimodal by runner CPU (22.8 vs 34.7-39.1 ns; 139 vs 238-256 ns with 8 `nanoTime()`
reads; the java job doesn't record the CPU model, so "by CPU" is inferred), and the 26.2 and 26.3 values of one run
are nearly identical: the runner, not the code, sets the level.

The probe (`TimingProbeGameTest`, 63 JVMs on 16+16 runners, 3 repetitions each = 189 measurements per case) times
the same hooks three ways in one JVM: the current estimator (200k warm-up, best of 5 × 100k), a "JIT-quiet" best of 5
(start only after a 20k block with no JIT compilation, cap 5 s), and 48 interleaved triples of 20k calls: the hook, the
hook called twice per iteration (a deliberate, exact 2x regression), and a fixed pure-Java reference workload
(6 xorshift rounds with array loads/stores, ≈24-58 ns), each block timed by wall clock and thread CPU time.

| case | current estimator: p50 / max (max/p50) | JIT-quiet best of 5: p50 / max | interleaved median ns: p50 / max | ratio hook/ref: p50 / p99 / max | ratio of the 2x twin: min | per-run twin/1x: min-max |
|---|---|---|---|---|---|---|
| tickHookNsPerCallOn | 41.6 / 163.3 (3.9) | 41.6 / 86.9 (2.1) | 41.1 / 53.7 | 1.403 / 1.569 / 1.577 | 2.393 | 1.86-2.11 |
| tickHookNsPerCallWorld | 25.9 / 98.2 (3.8) | 27.8 / 96.0 | 9.3 / 42.0 | 0.326 / 0.775 / 0.777 | 0.376 | 1.66-2.48 |
| tickHookNsPerCall | 5.2 / 30.0 | 8.9 / 39.4 | 7.1 / 37.2 | 0.232 / 0.686 / 0.709 | 0.145 | 1.64-3.44 |

(Current-estimator and JIT-quiet columns: run 36288312579, n = 96; the others pooled over both runs, n = 189. The
probe calls the hook through an interface, so its absolute ns differ a little from FootprintGameTest's direct call.)

Findings:
1. The current estimator crossed today's limits in 4 of 567 probe measurements (World 97.3 and 98.2 > 87; On 157.6
   and 163.3 > 101): the CI flake reproduced at about the CI rate.
2. Its outliers are JIT compilation, not the hook: on the monitor-on case, outlying measurements (8 of 93 in run
   36286920450) had a median of 29 ms of compilation during the 5 blocks, the others 0. Starting only after one
   JIT-quiet block is not enough (max still 2.1 × p50), presumably because a queued compilation shows no compile time
   until it finishes (UNVERIFIED).
3. For the ≈40 ns monitor-on path, the median of the interleaved ratio has CV 5.3 % across 5 CPU models and separates a
   2x regression cleanly: every 1x ratio ≤ 1.577, every 2x ratio ≥ 2.393.
4. For the sub-10 ns paths (title screen; world with the monitor off) the same code measures 1.6-42 ns depending on
   the JVM's inlining decisions; 1x and 2x overlap under any estimator. No per-call gate on a shared runner can catch a
   2x regression of a hook that costs a few field reads without flaking; a 5 ns regression there is also not a player
   cost worth gating. Gate them as a gross-regression backstop, and rely on the exact 0-allocation checks, which catch
   the common real regressions (boxing, lambdas, string building).
5. Thread CPU time and wall time agree within 0.5 % here (the render thread wasn't preempted during 1 ms blocks);
   CPU time is still the safer choice.

### 5.2 (a) New budgets now: min(ceiling, 4 × max observed)

Max observed = the largest value in CI (5.1) or the probe's current-estimator column, rounded up:

| key | max observed (source) | 4 × max | ceiling | proposed limit (current) |
|---|---|---|---|---|
| frameHookNsPerCallOff | 7.422 (36228615454, 26.3) | 29.69 | 20 | 20 (13) |
| frameHookNsPerCallOn | 39.149 (36258254138) | 156.60 | 200 | 157 (75) |
| frameHookNsPerCallOnPhases | 256.332 (36242976610) | 1025.33 | 400 | 400 (400, unchanged) |
| tickHookNsPerCall | 68.91 (36230423586, 26.2 GL) | 275.64 | 2000 | 276 (111) |
| tickHookNsPerCallWorld | 98.19 (probe, 36288312579; CI max 91.62) | 392.76 | 2000 | 393 (87) |
| tickHookNsPerCallOn | 163.25 (probe, 36288312579; CI max 116.22) | 653.00 | 2000 | 653 (101) |

No observed value (310 CI legs, 584 frame values, 567 probe measurements) exceeds these. They catch only gross
regressions: a doubled `tickHookNsPerCallOn` (p50 42 → 84 ns) passes 653. That's what (b) is for. All other budgets,
the CPU-ms and byte budgets, the 0-allocation keys and the leak checks stay as they are. Update the `about` text and
DESIGN.md's "Budgets are 2 × the largest value observed" sentence for these six keys.

### 5.3 (b) Measurement that still catches a 2x regression

For `tickHookNsPerCallOn` (FootprintGameTest) and, the same way, `frameHookNsPerCallOn`/`…OnPhases`
(FrameHookBudgetTest, whose reference for the phase case should include 8 `System.nanoTime()` reads so the runner's
clock cost cancels; UNVERIFIED by experiment):

1. Warm up 200k calls, then run 48 interleaved triples of 20k calls: hook, hook twice, reference. Time each block with
   `ThreadMXBean.getCurrentThreadCpuTime()` (and wall, recorded).
2. Gate `tickHookOnVsReference = median over triples of (hook block / reference block)`: new key, limit **1.95**, no
   ceiling (the ns key stays as the backstop from (a)). Arithmetic: 1x max 1.577 × 1.24 = 1.95 = 2x min 2.393 / 1.23,
   so both margins are ≈ 23 %; 1.95 is 7 standard deviations above the 1x mean (CV 5.3 %). Worst case by CPU model:
   the lowest 1x ratio (EPYC 9V45, 1.20) doubles to ≥ 2.39.
3. Self-check on every run: `twin = median(hook-twice block / reference block)` must exceed the limit; if it doesn't,
   the runner couldn't have seen a 2x regression, and the test fails with that message (observed minimum 2.393, 23 %
   above). This is the continuous deliberate-slowdown proof.
4. Record the per-block arrays, `cpu` (from `/proc/cpuinfo`), and the JIT milliseconds in the JSON, so an outlier can be
   explained from the artifact.
5. Cost: ≈5 M extra hook calls, well under a second per case.

One-time proof as well (as WS-F's gate proofs): a scratch branch whose `StutterHooks.tick` does its work twice must
turn every leg red on `tickHookOnVsReference` while the ns backstop stays green.

## 6. Protocol: 5 consecutive full CI runs, no re-runs

- Trigger fresh runs with `gh workflow run build.yml --ref feat/v0.5.0` (`workflow_dispatch` exists, `build.yml:9`),
  one at a time: `concurrency: build-${{ github.ref }}` with `cancel-in-progress: true` (`build.yml:16-18`) cancels a
  running run when the next one starts, so parallel dispatches or empty commits would cancel each other. No commits are
  needed; each dispatch is a new run (attempt 1) of the same SHA.
- Freeze pushes to `feat/v0.5.0` during the streak (a push cancels the running run); run the streak on the SHA that
  carries all of P0.1, and again on the release candidate.
- A run counts when: event is `push` or `workflow_dispatch` on `feat/v0.5.0`, `attempt == 1`, every job (java, python,
  gametest-matrix, rules-consistency, rules-v1-compat, all 3 game-test legs) concluded `success`, and no job was skipped.
  The streak is broken by any completed run with another conclusion; cancelled runs don't count and, to keep the
  evidence clean, shouldn't occur inside the streak.
- Record with a small script (`tools/ci_streak.py`, stdlib + `gh`): `gh run list --workflow build.yml --branch
  feat/v0.5.0 --json databaseId,headSha,event,attempt,conclusion,createdAt,updatedAt`, then `gh run view <id> --json
  jobs` for each; it prints pass/fail and writes `docs/v0.5/verification/ci-streak.md`: run id and link, SHA, event,
  attempt, conclusion, total and per-leg durations, each leg's Modrinth request count from the fake's log, and the
  footprint ratios (`tickHookOnVsReference`, twin). Poll `gh run view <id> --json status` between dispatches (don't wait
  on notifications).
- Spread the five over a few hours if convenient: the midnight-rotation bug (36202977666) was time-dependent.

## 7. Wall time today and cheap speedups

Median / max over 26 green runs since 2026-09-26 14:00 UTC:

| Job | Job total | Main steps |
|---|---|---|
| java | 203 s / 465 s | "Check sources" (first `./gradlew`, all downloads) 39 s / 292 s; "Build and test" 133 s / 157 s; E2E drivers 11 s |
| client game tests (26.2, OpenGL) | 521 s / 672 s | "Client game tests" 496 s / 640 s |
| client game tests (26.3, OpenGL) | 503 s / 736 s | 472 s / 699 s |
| client game tests (26.3, Vulkan) | 495 s / 707 s | 456 s / 653 s; apt 11 s / 26 s |
| python, rules-consistency, rules-v1-compat, gametest-matrix | 10-12 s | |

Inside a leg (run 36282354607): ~50 s of Gradle work before the game (downloadAssets, compile), game start ~20 s, then
BenchmarkGameTest (with RigTuneClientGameTest) ≈ 150-190 s, FootprintGameTest ≈ 85 s (its 65 s sampler window),
A11yGameTest ≈ 25 s, the other 12 classes ≈ 5-30 s each. The whole-run wall time is the slowest leg.

Speedups that also cut flake exposure:
1. The offline legs in 36288312579 took 425-457 s for the game-test command (Gradle configuration + game; compilation
   had already run in the prefetch step), vs 456-496 s step medians online: no waiting on Modrinth. With the fake,
   PreviewGameTest's wait no longer depends on the internet.
2. `setup-gradle` in the java job: the first `./gradlew` drops from 39 s median (292 s worst) to a cache restore
   (the legs restore 776 MB in ≈5 s), and ≈10 hosts leave the critical path.
3. Step timeout 20 → 15 min, job 30 → 25 min: the slowest green leg's step was 11.7 min; a hang then costs 13-15 min
   (with the thread dump) instead of 20.
4. The lavapipe `.deb` from the cache: −10 s and one network touch on the Vulkan leg.
5. Not recommended now: sharding the 16 classes over two JVMs per leg (≈4.5 min legs) doubles runner setups and needs a
   per-class skip property and item 9's helper wait. Revisit if wall time matters more than runner count.

## Appendix: reproducing the experiments

- `git -C "C:/Dev/Minecraft Setting Optimisation Mod" show research/v05-ci:.github/workflows/ci-experiment.yml`: the
  `timing` matrix (16 legs, 2 JVMs × 3 reps each), `hang` (6 legs × 4 JVMs, 26.3 OpenGL, RigTuneClientGameTest +
  BenchmarkGameTest, 9-minute watchdog), `offline-gametest` (3 legs: prefetch, then
  `sudo unshare --net -- env … bash -c 'ip link set lo up; exec sudo -u runner env … ./gradlew --offline :<mc>:runProductionClientGameTest'`)
  and `offline-unit` (prefetch, then `./gradlew --offline build` and both Python suites in the namespace).
- Analysis scripts in the scratch folder: `classify.py` (failure signatures), `fp_stats.py` + `fp_dist.py` (footprint
  distributions), `fetch_fh.py` (frame-hook values from test reports), `timing_analysis.py <dir>` (probe estimators).
