# WS-G: self-update E2E harness (design and deviations)

SPEC item 5 (P0), plan-review M10, M11 (context), M12, L4, L5. Plan: docs/v0.2/plans/ws-g.md. Usage: tools/e2e/README.md.
Results: docs/smoke/self-update/README.md.

## What ships
Only `core/modrinth/HttpModrinthClient.java` changed in the mod:
- `-Drigtune.modrinth.baseUrl=<url>` (`BASE_URL_PROPERTY`) replaces `https://api.modrinth.com` for every Modrinth call
  (blank means the default). For tests only; not documented for players.
- L5 download allowlist: a download must be `https://cdn.modrinth.com/` (port 443), or the origin (scheme, host, port)
  of a non-default base URL that is https, or plain http on this machine (localhost, 127.x, [::1]; the local test
  servers). Downloads use their own HttpClient with `Redirect.NEVER`: RigTune follows a redirect itself (at most 5
  hops, relative `Location`s resolved) and checks every hop against the allowlist before requesting it, so no request
  goes off the allowlist. Only a 2xx body is written and hashed. Userinfo tricks (`https://cdn.modrinth.com@evil/`),
  look-alike hosts and non-ASCII/percent-encoded hosts (no parsed host) fail the origin compare. A real Modrinth
  redirect off the CDN would now fail the download; checked 2026-09-25: an unauthenticated `curl -sI` of
  `https://cdn.modrinth.com/data/P7dR8mSH/versions/ewUK83HI/fabric-api-0.161.0%2B26.2.jar` answers `200 OK`
  (application/java-archive, 2566123 bytes) with no redirect.

Everything else is test tooling, outside the shipped jar and outside `./gradlew build`:
- `tools/e2e/java/.../FakeModrinth.java`, `RedirectProbe.java`: JDK-only, run with `java <file>.java`. The test source
  set adds `tools/e2e/java` as a source dir (build.gradle, WS-G block), so `FakeModrinthTest` runs in CI on both MC
  versions. The srcDir survives Stonecutter's generated 26.3 source set (checked: `:26.3:test` runs it).
- `src/e2e/` (Gradle source set `e2e`): the driver mod. Its compile classpath is the client classpath minus `main`'s
  output plus the released v0.1.0 jar (`-Pe2e.oldJar`), so it can only use 0.1.0's API.
- `:<mc>:e2eDriverJar`, `:<mc>:e2eClient` (build.gradle WS-G block).
- `tools/e2e/*.py` + `tools/e2e/tests/` (CI: a step in the `python` job).

## Decisions
- **Fake server in Java, not Python.** A JDK `HttpsServer` loads the keytool PKCS12 keystore directly; Python's `ssl`
  would need a PEM key, i.e. openssl. It also lets JUnit run it against the real `HttpModrinthClient` and
  `OnlineDataFetcher` (`realClientFindsAndDownloadsTheUpdate`), and core/modrinth was byte-identical to v0.1.0's before
  the L5 change, so that test also stands for 0.1.0.
- **Host routing by the Host header.** The API answers only for `api.modrinth.com`, files only for `cdn.modrinth.com`,
  rules only for `raw.githubusercontent.com`; file URLs point at the real CDN name. The request log therefore shows
  which host each request used, and the checks assert the download came from the CDN host with the old version's
  User-Agent. Without `hosts` in the catalog it serves everything on one origin (for `-Drigtune.modrinth.baseUrl`).
- **The rules host is faked too.** 0.1.0 writes `rules-cache.json` only after a successful remote fetch, and the fixture
  was wanted; the fake serves the repo's `rules/rules-v*.json` at their raw.githubusercontent.com paths.
- **A plain client-mod driver, not the client game-test framework.** `-Dfabric.client.gametest` changes the game
  (option resets, tick sync) and its own shutdown path. The driver runs on `END_CLIENT_TICK` as a state machine and
  quits with `Minecraft.stop()`, exactly what the title screen's Quit button calls, so RigTune's `CLIENT_STOPPING` hook
  starts the helper as it would for a player. Screenshots use `Screenshot.grab(gameDir, name, mainRenderTarget, 1, ...)`.
- **The driver calls the controller, not the UI.** `controller.apply(List.of(update))` is what the RigTune screen's
  Apply does with only that row ticked. The screen is open while it happens, so the screenshots show the report (scrolled
  to the "Update RigTune" row) and the staged state ("Restart Minecraft to finish applying 1 change(s)").
- **Direct calls, compiled against v0.1.0.** Plan review L4 suggested reflection because `apply` returns `Component`;
  the `e2e` source set has the Minecraft classes, so the driver is type-checked against the real 0.1.0 API. The same
  jar runs against 0.2 builds in the relaunch and the M12 run; 0.2 keeps those signatures (a NoSuchMethodError there
  would flag an API break of the controller).
- **M10.** `e2eClient` is a `ClientProductionRunTask` with `mods.setFrom(e2eDriverJar)`: Loom's constructor default
  (`jar` + `productionRuntimeMods`, javap-verified on Loom 1.17.21) would load a second RigTune via `-Dfabric.addMods`,
  outside mods/. `runDir` is the scratch instance and nothing re-syncs mods/ between the two launches. The check
  "the new RigTune is loaded from mods/" compares the mod container's origin path.
- **AC5.2 evidence.** A PowerShell loop records every `ApplyHelper` JVM command line of the run while it lives; the check
  requires every `-cp` entry to be under `config/rigtune/helper/`. The v0.1.0 run shows
  `helper/0-rigtune-0.1.0.jar;helper/1-gson-2.14.0.jar`, and the rename of `rigtune-0.1.0.jar` succeeded on Windows.
- **M12.** Two parts: the run 0.2.0-dev.1 → 0.2.0-dev.2 (the 0.2 helper applies its own successor), and a check in
  every run that the new jar's `depends` and `breaks` add or change nothing relative to the old jar's (a stricter one
  swapped in post-exit could leave the game unable to start with no RigTune to undo it). Nested jars aren't compared.
- **Fixtures (for WS-B, AC3.3)** are the real files from a passing v0.1.0 run, with the instance path replaced by
  `${INSTANCE}` and the separators after it made `/` (so tests can substitute a Linux path on CI). In the JSON files
  only string values that start with the instance path are rewritten (parsed and re-serialised in Gson's layout), so no
  escape sequence can be damaged. `src/test/resources/v010/captured/README.md` explains them; `manifest.json` records
  the run, its verdict, any failed checks and the hashes.
- **Evidence** replaces absolute paths by `<instance>`, `<run>`, `<repo>` and `~`; client logs are filtered to RigTune,
  the driver, warnings, errors and the mod list. The errors in them (OSHI performance counters, authlib/Yggdrasil
  `UnknownHostException`) come from Minecraft running with Mojang's hosts unresolvable, not from RigTune.
- **Process safety.** A process is this run's only if its command line contains the run folder's name (timestamp plus
  a random suffix). The script kills only this run's KnotClient, ApplyHelper, FakeModrinth or Gradle wrapper JVMs, one
  PID at a time (never `/T`: a Gradle daemon started by the wrapper is its child and serves other builds), and before
  releasing the lock waits until none of its clients or wrappers remain. It removes the lock only if `owner.txt` names
  its run folder.

## L4 items, now verified (docs/smoke/self-update/redirect-proof.txt)
- `jdk.net.hosts.file`: listed hosts resolve to 127.0.0.1, unlisted ones fail (`example.com`,
  `resources.download.minecraft.net`). Without the machine name in the file, `InetAddress.getLocalHost()` throws
  `UnknownHostException`; with it, it works. The harness lists `localhost` and the machine name. Minecraft started fine
  with Mojang's hosts unresolvable (offline profile, no crash).
- A PKCS12 truststore given without `-Djavax.net.ssl.trustStorePassword` fails the handshake (`SSLException:
  (internal_error)`); with it, TLS 1.3 to the fake works. Without the truststore property, the JDK's cacerts rejects the
  fake certificate (PKIX), so real TLS validation is in force.
- Port 443 on 127.0.0.1 was free and bindable without admin rights.
- The certificate has SANs for all three hosts (plus localhost / 127.0.0.1).

## Finding: RigTune sometimes skips its Modrinth lookups at startup
One v0.1.0 run in five stayed "Offline" for 180 s. The fake server got the rules fetch but never `/v2/version_files`,
and there was no lookup warning (`docs/smoke/self-update/v010-offline-race/`). `RealController.fetchOnline()` returns
early while `rules` is null. It is called when the scan completes and, in `loadRules`, only when the remote document
beats the local one. `RulesLoader.pickNewest` keeps the first candidate on a revision tie. So when the scan finishes
before the bundled/cached rules are set and the remote rules have the same revision, nothing calls it until Rescan.
The timing matched: in passing runs the scan and the rules load landed in the same second, in the failing one the scan
finished about 1 s earlier. The same code is in 0.2 today (RealController `loadRules` / `rescan`), so this is reported
to the coordinator for WS-A. The fix: call `fetchOnline()` right after the local rules are set; it's a no-op until the
scan is done. For 0.1.0 users, a published rules-v1.json with a higher revision than the bundled r4 triggers the lookups
on the first launch that fetches it. The driver presses Rescan once after 20 s offline (a player's action) and
`RESULT.md` records whether it did.

## Deviations from the plan
- The fake server lives in `tools/e2e/java` (not a Python `http.server`), see above.
- `NoDefaultCurrentDirectoryInExePath` is set in this environment, so the orchestrator calls `gradlew.bat` by its
  absolute path.
- No separate Gradle subproject: the `e2e` source set and two tasks sit in build.gradle's WS-G block.
- After the first push, `origin/feat/v0.2.0` is merged into the branch rather than rebased onto it: a hook blocks
  force-pushes without the user's confirmation.

## Phase 5
Runs on the merged integration build (feat/v0.2.0 @ 5f57eee, `rigtune-0.2.0-dev+mc26.2.jar`); results in
docs/smoke/self-update/README.md.
- **Final v0.1.0 → 0.2.0** (`final-v010-to-020/`): `--legacy-disable --expect-history`. `LegacyImport.entry` returns
  null when 0.1.0's last helper run only touched RigTune's own jars (both are excluded), so a plain self-update leaves
  no `legacy-import` entry at all. With `--legacy-disable`, the 0.1.0 driver also passes a "Disable e2e-legacy" row to
  the same `apply()` (the row is built by the driver; 0.1.0's rules wouldn't recommend disabling a test mod), and the
  check requires exactly one `legacy-import` entry that holds that disable as `APPLIED` and nothing of RigTune's.
- **M14, undo after a restart** (`undo-after-restart/`): `--scenario undo`, a second driver (`src/e2eUndo`) compiled
  against 0.2's API. One Apply adds `e2e-added` from the fake Modrinth (the real download path: DependencyResolver,
  DownloadPlanner, the CDN allowlist, SHA-512) and disables `e2e-disable-me`; the helper applies both; the next start
  runs Undo last apply through `undoPlan(false)` and `undo(plan)` (what UndoScreen's confirm does, with the screen open
  for the screenshot); the helper reverts both; the third start checks the mods and that nothing is left to undo. The
  rows are built by the driver, not taken from the report: M14 is about the journal and undo pipeline after real
  restarts, not about which mods the rules suggest.
- The Modrinth listing itself (M11) is outside this harness; the harness proves only that a byte-identical hosted jar
  is matched by hash.
