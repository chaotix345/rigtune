# WS-G: self-update E2E harness, implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:executing-plans (inline, this agent). Steps use checkbox (`- [ ]`) syntax.

**Goal:** Prove, with the unmodified released v0.1.0 jar in a real production client, that 0.1.0 finds, downloads and applies its own update to a 0.2 build through the post-exit helper, and that the 0.2 build then reads the 0.1.0 state. Also add `-Drigtune.modrinth.baseUrl` and the CDN download allowlist to 0.2.

**Architecture:** A JDK-only fake Modrinth HTTPS server (`tools/e2e/java/.../FakeModrinth.java`, runnable with `java <file>.java`, unit-tested through the test source set) serves `api.modrinth.com`, `cdn.modrinth.com` and `raw.githubusercontent.com` on 127.0.0.1:443. The client is redirected with JVM properties only (`-Djdk.net.hosts.file`, `-Djavax.net.ssl.trustStore`). A driver mod (own source set `e2e`, compiled against the v0.1.0 jar, never shipped) is added with `-Dfabric.addMods` by a dedicated Loom `ClientProductionRunTask` (`:<mc>:e2eClient`) whose `mods` is ONLY the driver jar and whose run dir is a scratch instance copy. A Python orchestrator (`tools/e2e/self_update_e2e.py`) prepares the instance, TLS material, hosts file and catalog, starts the server, takes the game-test lock, runs the two launches, watches for the helper process, asserts, and copies evidence and fixtures.

**Tech Stack:** Java 25 (JDK `HttpsServer`, `keytool`), Fabric Loom 1.17 `ClientProductionRunTask`, Stonecutter 0.9.8 build, Python 3.11 stdlib, JUnit 5.

**Spec:** docs/v0.2/SPEC.md item 5 + "Amendments" (item 5: M10, M12, L5, M14); docs/v0.2/plan-review.md §3 (M10, M11, M12, L4, L5); PLAN.md WS-G.

## Global Constraints
- Work only in `C:/Dev/Worktrees/rigtune-e2e`, branch `feat/self-update-e2e`; rebase onto `origin/feat/v0.2.0`; don't merge.
- `export JAVA_HOME="C:/Dev/Tools/jdk/jdk-25.0.4.1+1"` before every `./gradlew`; `./gradlew build` green for 26.2 and 26.3.
- Committed Stonecutter state stays 26.2.
- `core/` has no Minecraft imports. Only `core/modrinth/HttpModrinthClient.java` is touched in shipped code.
- Game launches ONLY while holding `C:/Dev/Worktrees/.gametest-lock` (atomic mkdir + owner.txt; no wait loop; confirm the client and helper exited, then remove it). Kill only processes whose command line has this worktree or the scratch instance path.
- Never read or write the user's instance under %APPDATA%\ModrinthApp. Scratch: `C:/Users/Admin/AppData/Local/Temp/claude/C--Dev-Minecraft-Setting-Optimisation-Mod/097c9765-76fe-415d-a5ae-7debe28cb5de/scratchpad/ws-g/`.
- The released v0.1.0 jar: sha256 `8294d04a6b67e76dcff298366be38f85048ebf19a120baa9e8ed5b08b2e4b950`.
- M10: the production run must load RigTune only from the instance's `mods/` (the Loom default adds the dev jar via `-Dfabric.addMods`; replace `mods` with the driver jar only; never re-sync mods before the second launch).
- L5: 0.2 downloads only from `https://cdn.modrinth.com/`, unless `-Drigtune.modrinth.baseUrl` (or a non-default constructor base URL) widens it to that origin.
- No Python string literals with Windows backslashes. Commit trailer as in PLAN.md.

## Facts verified before planning (2026-09-25)
- v0.1.0 finds its update with `POST /v2/version_files` (current version, needs `project_id`) + `POST /v2/version_files/update` (`loaders: ["fabric"]`, `game_versions: ["26.2"]`), offers it if `next.id != cur.id`, `next.date_published >= cur.date_published`, same-or-better stability (OnlineDataFetcher.isUpdate). `/v2/projects?ids=` must return a JSON array or the whole fetch goes offline. Recommendation id `update:rigtune`, `Action.UpdateMod`, default-ticked. Download: `URI.create(file.url())`, SHA-512 check, `<mods>/<name>.rigtune-pending`; staged group {DISABLE_FILE old, ENABLE_FILE new (modId)}; helper launched on CLIENT_STOPPING from `config/rigtune/helper/` copies.
- 0.1.0 writes `rules-cache.json` only after a successful fetch of `https://raw.githubusercontent.com/chaotix345/rigtune/main/rules/rules-v1.json`, so the fake server serves that path too.
- Loom 1.17.21 `AbstractProductionRunTask` adds `jar` (and `productionRuntimeMods`) to `mods` in its constructor and passes `mods` as `-Dfabric.addMods`; `ClientProductionRunTask` passes `--gameDir <runDir>`, main class KnotClient. `mods.setFrom(driverJar)` replaces the defaults.
- MC 26.2: `Screenshot.grab(File gameDir, String name, RenderTarget, int downscale, Consumer<Component>)` writes `gameDir/screenshots/<name>`; `minecraft.gameRenderer.mainRenderTarget()`; the title screen's Quit button calls `minecraft.stop()`.
- `sourceSets.client.compileClasspath` = MC + fabric-api + loader + modmenu + `main` output. The e2e source set subtracts `main.output` and adds the v0.1.0 jar.
- Port 443 is free on this machine; `keytool` is in the JDK.

---

### Task 1: `-Drigtune.modrinth.baseUrl` and the CDN download allowlist (L5)

**Files:**
- Modify: `src/main/java/io/github/chaotix345/rigtune/core/modrinth/HttpModrinthClient.java`
- Test: `src/test/java/io/github/chaotix345/rigtune/core/modrinth/HttpModrinthClientTest.java`

**Interfaces:**
- Produces: `HttpModrinthClient.BASE_URL_PROPERTY = "rigtune.modrinth.baseUrl"`; `new HttpModrinthClient(modVersion)` uses the property when set and non-blank; `static boolean allowedDownload(URI uri, String baseUrl)` (package-private).

- [ ] Step 1: failing tests
  - `allowedDownloadIsTheModrinthCdnOverHttpsOnly`: `https://cdn.modrinth.com/data/x.jar` and `https://CDN.modrinth.com:443/x.jar` true; `http://cdn.modrinth.com/x.jar`, `https://cdn.modrinth.com:8443/x.jar`, `https://cdn.modrinth.com.evil.example/x.jar`, `https://cdn.modrinth.com@evil.example/x.jar`, `https://api.modrinth.com/x.jar`, `ftp://cdn.modrinth.com/x.jar` false (base = DEFAULT_BASE_URL).
  - `aTestBaseUrlAlsoAllowsItsOwnOrigin`: base `http://127.0.0.1:1234/` allows `http://127.0.0.1:1234/cdn/x.jar`, still allows the CDN, refuses `http://127.0.0.1:9999/x.jar` and `https://127.0.0.1:1234/x.jar`.
  - `defaultClientRefusesADownloadOutsideTheCdnBeforeRequesting`: `new HttpModrinthClient("1.2.3")` downloading `url("/cdn/mod.jar")` (the local server) throws IOException mentioning `cdn.modrinth.com`, the server got 0 hits, the target dir has no files.
  - `baseUrlPropertyRedirectsRequestsAndDownloads`: with `System.setProperty(BASE_URL_PROPERTY, url("/"))` (cleared in finally), `versionsByHashes` hits the local server and `download(url("/cdn/mod.jar"))` succeeds.
  - `downloadRedirectedToAnotherOriginIsRefused`: a second local HttpServer; `/cdn/moved.jar` answers 302 to it; download throws, nothing left behind.
- [ ] Step 2: run `./gradlew :26.2:test --tests '*HttpModrinthClientTest'`; expect compile failure / failures.
- [ ] Step 3: implement: constructor reads the property; `download()` calls `requireAllowed(uri)` before creating the temp file and checks `response.uri()` after the exchange; origin = scheme + host (case-insensitive) + effective port (443/80 defaults).
- [ ] Step 4: the whole HttpModrinthClientTest passes (existing download tests use the base URL's origin, so they stay green).
- [ ] Step 5: commit `feat(modrinth): base URL property and CDN-only downloads (L5)`.

### Task 2: the fake Modrinth server

**Files:**
- Create: `tools/e2e/java/io/github/chaotix345/rigtune/e2e/FakeModrinth.java` (JDK only: `HttpServer`/`HttpsServer`, a small JSON reader/writer, SHA-1/SHA-512)
- Modify: `build.gradle` (WS-G block: `sourceSets.test.java.srcDir(rootProject.file('tools/e2e/java'))`)
- Test: `src/test/java/io/github/chaotix345/rigtune/e2e/FakeModrinthTest.java`

**Interfaces:**
- Produces: `FakeModrinth.start(Path catalog, InetSocketAddress bind, SSLContext tlsOrNull, Path requestLogOrNull)` → running instance with `port()`, `stop()`; `main(String[])` with `--catalog --bind --port --keystore --storepass --log`, prints `FakeModrinth listening on <scheme>://<bind>:<port>` when ready and stops on stdin EOF.
- Catalog JSON (written by the orchestrator):
  ```json
  {"hosts": {"api": "api.modrinth.com", "cdn": "cdn.modrinth.com"},
   "cdnBase": "https://cdn.modrinth.com",
   "projects": [{"id": "e2eRigTn", "slug": "rigtune", "title": "RigTune",
     "versions": [{"id": "e2eV0100", "version_number": "0.1.0", "version_type": "release",
       "date_published": "2026-09-24T08:00:00Z", "game_versions": ["26.2"], "loaders": ["fabric"],
       "file": "<abs path>", "dependencies": [{"project_id": "P7dR8mSH", "dependency_type": "required"}]}]}],
   "static": [{"host": "raw.githubusercontent.com", "path": "/chaotix345/rigtune/main/rules/rules-v1.json", "file": "<abs path>"}]}
  ```
  `hosts` absent → single origin (any Host header serves everything; used by `-Drigtune.modrinth.baseUrl` runs and tests).
- Endpoints: `POST /v2/version_files` and `POST /v2/version_files/update` (hash → version map, unknown hashes omitted; update = newest by `date_published` among the project's versions matching `loaders` and `game_versions`); `GET /v2/projects?ids=[...]` (array, unknown dropped); `GET /v2/project/{id|slug}/version?loaders=&game_versions=` (array, 404 if unknown project); `GET /v2/version_file/{hash}` (version or 404); `GET /data/{projectId}/versions/{versionId}/{filename}` on the CDN host (bytes; `+` encoded as `%2B` in URLs, as Modrinth does). Everything else 404. Each request is logged as one JSON line: time, method, host, path, query, status, user-agent.

- [ ] Step 1: failing tests (plain HTTP server on port 0 with a temp catalog of two fake jars):
  - `versionFilesMapsKnownHashesAndOmitsUnknownOnes`
  - `updateReturnsTheNewestMatchingVersionAndHonoursFilters` (game_versions `["26.3"]` → omitted)
  - `projectsReturnsAnArrayOfKnownProjects` (`ids=["rigtune","nope"]` → one element; empty ids → `[]`)
  - `projectVersionsFiltersAndUnknownProjectIs404`
  - `cdnServesTheJarWithTheAdvertisedHashes` (sha1/sha512/size of the served bytes equal the version JSON's)
  - `hostRoutingServesApiOnlyOnTheApiHostAndFilesOnlyOnTheCdnHost` (raw socket requests with Host headers)
  - `staticFilesAreServedOnTheirHost`
  - `requestsAreLogged`
  - `realClientFindsAndDownloadsTheUpdate`: the repo's `HttpModrinthClient` (base URL = the fake, `cdnBase` = the fake's origin) + `OnlineDataFetcher.fetchAll` with an `InstalledMod("rigtune", ..., "0.1.0", file, sha1(old))` gives `UpdateInfo` 0.1.0 → new, and `download` of its `ModFile` verifies the SHA-512. (core/modrinth is byte-identical to v0.1.0's, `git diff v0.1.0 -- src/main/java/.../core/modrinth` is empty before Task 1, so this is also what 0.1.0 does.)
  - `httpsWithAKeytoolCertificate`: generate a PKCS12 keystore with `keytool` (SAN dns:localhost), start with TLS, fetch with an HttpClient trusting it.
- [ ] Step 2: `./gradlew :26.2:test --tests '*FakeModrinthTest'` fails.
- [ ] Step 3: implement `FakeModrinth.java`.
- [ ] Step 4: tests pass on `:26.2:test` and `:26.3:test` (check the srcDir survives Stonecutter for the generated 26.3 source set).
- [ ] Step 5: commit `test(e2e): fake Modrinth server`.

### Task 3: JVM-property redirection proof (no game)

**Files:**
- Create: `tools/e2e/java/io/github/chaotix345/rigtune/e2e/RedirectProbe.java` (JDK only; GETs each URL argument; prints resolved addresses, TLS peer, status, body bytes; `InetAddress.getLocalHost()` and `localhost` results; an unlisted host must fail to resolve; exit 1 on any unexpected result)
- Create: `tools/e2e/e2e_env.py` (keytool TLS material, hosts file, catalog writing; imported by the orchestrator)
- Test: `tools/e2e/tests/test_e2e_env.py` (hosts file content, catalog shape, keytool argument lists)
- Evidence: `docs/smoke/self-update/redirect-proof.txt`

- [ ] Step 1: failing Python tests for `hosts_file_text(hostname)` (lists api/cdn/raw hosts, `localhost`, the machine name, all 127.0.0.1) and `catalog(...)`.
- [ ] Step 2: implement `e2e_env.py`: `make_tls(dir) -> Tls(keystore, truststore, password)` via `keytool -genkeypair ... -ext SAN=dns:api.modrinth.com,dns:cdn.modrinth.com,dns:raw.githubusercontent.com,dns:localhost` + `-exportcert` + `-importcert` into a PKCS12 truststore; `jvm_args(tls, hosts)` returns `-Djdk.net.hosts.file=..`, `-Djavax.net.ssl.trustStore=..`, `-Djavax.net.ssl.trustStorePassword=..`, `-Djavax.net.ssl.trustStoreType=PKCS12`.
- [ ] Step 3: `python -m unittest discover -s tools/e2e/tests` passes.
- [ ] Step 4: manual proof: start FakeModrinth on 127.0.0.1:443 with the keystore, run `java <jvm args> RedirectProbe.java https://api.modrinth.com/v2/project/rigtune/version https://cdn.modrinth.com/data/... https://raw.githubusercontent.com/...`; also once without `localhost`/hostname in the hosts file to record L4's `getLocalHost` behaviour. Save the output to `docs/smoke/self-update/redirect-proof.txt`.
- [ ] Step 5: commit `test(e2e): prove hosts-file and truststore redirection`.

### Task 4: the driver mod and the `e2eClient` run task

**Files:**
- Create: `src/e2e/java/io/github/chaotix345/rigtune/e2e/driver/SelfUpdateDriver.java` (ClientModInitializer; state machine on `ClientTickEvents.END_CLIENT_TICK`)
- Create: `src/e2e/resources/fabric.mod.json` (id `rigtune-e2e-driver`, client entrypoint, depends `rigtune: *`)
- Modify: `build.gradle` (WS-G block: `e2e` source set with `compileClasspath += sourceSets.client.compileClasspath - sourceSets.main.output + files(<-Pe2e.oldJar>)`; `e2eDriverJar` (Jar, base name `rigtune-e2e-driver`); `e2eClient` (`ClientProductionRunTask`: `runDir` = `-Pe2e.instance`, `mods.setFrom(e2eDriverJar)`, `jvmArgs` = lines of `-Pe2e.jvmArgsFile`, `--width 1280 --height 720`))

**Interfaces:**
- Consumes (from the v0.1.0 jar, all present at the tag): `RigTuneClient.controller()`, `RigTuneClient.open(Screen)`, `RigTuneController.report()/goal()/setGoal(Goal)/apply(List<Recommendation>)/status()`, `Report.recommendations()/rulesRevision()/rulesSource()/online()`, `Recommendation.id()/category()/impact()/title()/reason()/action()/selectedByDefault()`, `Action.UpdateMod`, `Goal`.
- Produces (read by the orchestrator), under `-Drigtune.e2e.out`:
  - `driver-<phase>.json`: `{"phase", "ok", "error", "rigtuneVersion", "rigtuneOrigin", "goal", "updateOffered", "update": {"from","to","file"}, "status", "events": [...]}`
  - `report-<phase>.txt`: one line per recommendation.
  - `pending-before-exit.json` (phase `update`): a copy of `config/rigtune/pending.json` taken before quitting (L4).
  - screenshots in `<instance>/screenshots/e2e-<phase>-*.png`.
- Phases (`-Drigtune.e2e.phase`): `update` = title screen → `setGoal(QUALITY)` → wait ≤180 s for `update:rigtune` with `Action.UpdateMod` → open the RigTune screen, screenshot → `apply(List.of(update))` → wait ≤120 s for pending.json with an ENABLE_FILE whose target is the offered file name → copy it → screenshot → `minecraft.stop()`. `verify` = title screen → wait 40 ticks → screenshot (apply toast) → wait ≤180 s for a report with online data → record goal, version, origin, whether `update:rigtune` is offered → open the RigTune screen, screenshot → stop. A 6-minute watchdog writes `ok: false` and stops the game.

- [ ] Step 1: write the driver and the build block.
- [ ] Step 2: `./gradlew :26.2:e2eDriverJar -Pe2e.oldJar=<v010 jar>` compiles; `./gradlew build` still green and does NOT compile `e2e` (check the task list); `unzip -l` shows only driver classes + fabric.mod.json.
- [ ] Step 3: commit `test(e2e): driver mod and e2eClient run task`.

### Task 5: the orchestrator and its assertions

**Files:**
- Create: `tools/e2e/self_update_e2e.py` (CLI), `tools/e2e/e2e_checks.py` (pure assertions over an instance dir), `tools/e2e/fixtures.py` (templating)
- Test: `tools/e2e/tests/test_e2e_checks.py`, `tools/e2e/tests/test_fixtures.py`
- Create: `tools/e2e/README.md`

**Interfaces:**
- CLI: `python tools/e2e/self_update_e2e.py --old-jar P [--old-sha256 H] --new-jar P --name N [--mc 26.2] [--evidence DIR] [--capture-fixtures DIR] [--expect-history] [--work DIR]`.
- `e2e_checks.after_update(instance, old_jar, new_jar, server_log, helper_cmdlines) -> list[Check]`; `e2e_checks.after_verify(instance, new_jar, driver_verify, last_apply, expect_history) -> list[Check]`; `e2e_checks.depends_not_stricter(old_jar, new_jar) -> Check`; `Check(name, ok, detail)`.
- `fixtures.template(text, instance_root) -> str` replaces the instance root (raw, JSON-escaped, forward-slash forms) with `${INSTANCE}`.
- Flow: verify old jar hash → lock (mkdir; fail fast if held) → fresh instance `<work>/<name>-<utc>/instance/{mods,config}` + `options.txt` (`onboardAccessibility:false`, `fullscreen:false`) → fabric-api from the Gradle cache → TLS/hosts/catalog → server on 127.0.0.1:443 → RedirectProbe preflight → helper watcher (PowerShell `Get-CimInstance Win32_Process` loop, java command lines containing `ApplyHelper` and the instance path) → `gradlew :<mc>:e2eClient` phase update → wait for the helper (≤120 s: no helper process, no pending.json, helper.log done) → capture 0.1.0 files → checks A → `gradlew :<mc>:e2eClient` phase verify → checks B → evidence (`RESULT.md`, `checks.json`, filtered logs, requests.jsonl, helper.log, helper command line, mods listing with hashes, screenshots, driver outputs) → stop server → confirm no own java process remains → release the lock. Orphans (own instance path) are killed on timeout.
- Checks A (SPEC 5.5, AC5.2): driver ok; exactly one `rigtune*.jar` in mods = the new file name with the served SHA-512; `<old>.disabled` = the old bytes; no `pending.json`; no `*.rigtune-pending`; last-apply.json results all `OK`, exactly {DISABLE_FILE old, ENABLE_FILE new}; the helper's `-cp` entries are all under `config/rigtune/helper/`; downloads came from host `cdn.modrinth.com`; `depends` not stricter (M12 guard).
- Checks B (SPEC 5.6): the loaded RigTune version = the new jar's, from `mods/`; goal QUALITY kept; `rigtune.json` `lastShownApply` = last-apply.json `finishedAt` (the apply toast path ran); no `update:rigtune` offered; no crash report; mods unchanged; with `--expect-history`: history.json has one `legacy-import` entry with no change for RigTune's own jars.

- [ ] Step 1: failing Python tests on synthetic instance trees (pass and each failure mode) and for templating.
- [ ] Step 2: implement; tests pass.
- [ ] Step 3: commit `test(e2e): orchestrator and assertions`.

### Task 6: Wave A run, v0.1.0 → this branch (game)
- [ ] Build `./gradlew :26.2:jar` (0.2.0-dev), take the lock, run `self_update_e2e.py --name v010-to-dev --old-jar <v010> --old-sha256 8294d04a... --new-jar versions/26.2/build/libs/rigtune-0.2.0-dev+mc26.2.jar --evidence docs/smoke/self-update/v010-to-dev --capture-fixtures src/test/resources/v010/captured`.
- [ ] Look at every screenshot; read RESULT.md; fix harness bugs (systematic debugging) and rerun.
- [ ] Commit evidence + fixtures (+ `src/test/resources/v010/captured/README.md` explaining `${INSTANCE}` and the run).

### Task 7: M12 run, 0.2.0-dev.1 → 0.2.0-dev.2 (game)
- [ ] Build both with `-Pmod_version=0.2.0-dev.1` / `-Pmod_version=0.2.0-dev.2` into the scratch dir; run with `--name dev-to-dev`; evidence in `docs/smoke/self-update/dev-to-dev/`; commit.

### Task 8: finish
- [ ] `docs/smoke/self-update/README.md` (what ran, verdicts, links), `docs/v0.2/design/ws-g.md` (design, deviations, Phase 5 instructions incl. M14 undo).
- [ ] CI: add a step to the `python` job in `.github/workflows/build.yml`: `python -m unittest discover -s tools/e2e/tests -v`.
- [ ] Code review subagent on the diff; fix high/medium.
- [ ] Rebase onto `origin/feat/v0.2.0`, `./gradlew build`, Python tests, push, `gh run watch`; verification-before-completion.

## Phase 5 (later, coordinator message)
Rerun Task 6 with the merged integration jar and `--expect-history`; rerun Task 7; add M14 (0.2 applies a mod change → quit → helper → Undo last → quit → helper → assert) as a third driver phase once WS-B's `undoPlan`/`undo` exist.

## Phase 5 tasks (done 2026-09-25)
- [x] Merge origin/feat/v0.2.0 (5f57eee), build both versions (784 tests each).
- [x] `--legacy-disable`: the 0.1.0 driver also disables a test mod in the update's apply; `--expect-history` requires one `legacy-import` entry holding that disable as APPLIED and nothing of RigTune's. Tests: tools/e2e/tests/test_e2e_checks_phase5.py.
- [x] `--scenario undo` (M14) with a driver compiled against 0.2 (`src/e2eUndo`, `-Pe2e.driver=undo`): apply {add, disable} → helper → Undo last (the confirmation screen's button) → helper → next start. Checks `after_mod_apply`, `after_mod_undo`, `after_mod_check`.
- [x] Runs: docs/smoke/self-update/final-v010-to-020 and undo-after-restart.
