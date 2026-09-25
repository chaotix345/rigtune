# Self-update end-to-end test

Checks, in a real production Minecraft client, that an installed RigTune finds its own update on Modrinth, downloads
it, and that the post-exit helper swaps the jars; then starts the new version on the same instance and checks that it
reads the old version's files (SPEC item 5). The main scenario runs the unmodified released v0.1.0 jar. Nothing here
is part of `./gradlew build` or the shipped jar.

```sh
export JAVA_HOME=<JDK 25>
./gradlew :26.2:jar
python tools/e2e/self_update_e2e.py --name v010-to-dev \
    --old-jar <rigtune-0.1.0.jar from the v0.1.0 GitHub release> \
    --old-sha256 8294d04a6b67e76dcff298366be38f85048ebf19a120baa9e8ed5b08b2e4b950 \
    --new-jar versions/26.2/build/libs/rigtune-0.2.0-dev+mc26.2.jar \
    --work <scratch folder> \
    --evidence docs/smoke/self-update/v010-to-dev \
    --capture-fixtures src/test/resources/v010/captured
python -m unittest discover -s tools/e2e/tests
```

Windows only (process checks use PowerShell 7, `pwsh`). It opens a game window twice (well under a minute each) and
needs port 443 free on 127.0.0.1. Exit code 0 means every
check passed, 1 a failed check, 3 that the game-test lock is held. For a 0.2 → newer 0.2 run (plan review M12), build
two jars with `-Pmod_version=...`, pass them as `--old-jar`/`--new-jar`, and pass the v0.1.0 jar as
`--driver-api-jar`.

Two more modes (Phase 5):
- `--legacy-disable` (self-update): 0.1.0 also disables a test mod (`e2e-legacy`) in the same apply as its update, as
  a player who ticks another row would. 0.2's legacy import only records changes that aren't RigTune's own, so without
  one there is no `legacy-import` entry to check. Use it with `--expect-history`.
- `--scenario undo --new-jar <0.2 jar>` (plan review M14): a fresh instance with the 0.2 jar, fabric-api and a test mod
  `e2e-disable-me`; the fake Modrinth also serves a test mod `e2e-added`. Three launches with the undo driver:
  `mod-apply` (one Apply: add `e2e-added`, disable `e2e-disable-me`; quit; the helper applies both), `mod-undo` (Undo
  last apply: the plan, a screenshot of the confirmation screen, `undo(plan)`; quit; the helper reverts both),
  `mod-check` (the mods as before, nothing left to undo).

## How it works

| piece | what it does |
|---|---|
| `java/.../FakeModrinth.java` | JDK-only HTTPS server for `api.modrinth.com` (`/v2/version_files`, `/v2/version_files/update`, `/v2/projects`, `/v2/project/{id}/version`, `/v2/version_file/{hash}`), `cdn.modrinth.com` (`/data/...`, the real jars with their real SHA-1/SHA-512) and `raw.githubusercontent.com` (the repo's `rules/rules-v*.json`). Routing uses the Host header. Every request goes to `requests.jsonl`. Unit tests: `src/test/java/.../e2e/FakeModrinthTest.java` (the test source set compiles `tools/e2e/java`). |
| `java/.../RedirectProbe.java` | Run before the game with the game's JVM properties: every URL must resolve to 127.0.0.1 and answer over trusted TLS, and an unlisted host must not resolve. |
| `e2e_env.py` | keytool certificate (SANs for the three hosts) and PKCS12 truststore, the `jdk.net.hosts.file`, the JVM arguments, the catalog. |
| `src/e2e/` (Gradle source set `e2e`) | The driver mod, compiled against the released v0.1.0 jar (`-Pe2e.oldJar`), never against the current sources. Phase `update`: title screen → goal QUALITY → wait for "Update RigTune" → apply only it → wait until it's in pending.json (copied out: the helper deletes it) → quit through `Minecraft.stop()`, like the Quit button. Phase `verify`: screenshots of the title screen (apply toast) and the RigTune screen, the loaded version, the goal, whether an update is still offered → quit. |
| `src/e2eUndo/` (Gradle source set `e2eUndo`) | The undo driver, compiled against this repository's sources (0.2's `undoPlan`/`undo(plan)` and `UndoScreen`). The test mods are minimal Fabric mods (a `fabric.mod.json` only) made by `e2e_env.test_mod_jar`. |
| `:<mc>:e2eClient` (build.gradle) | Loom `ClientProductionRunTask` with `runDir` = the scratch instance and `mods` = the driver jar only (`-Pe2e.driver=undo` picks the undo driver). Loom's default would add this project's jar via `-Dfabric.addMods` (two RigTunes, and one outside mods/ that can't be updated); here RigTune loads only from the instance's `mods/`. |
| `self_update_e2e.py` | Checks it can run (Windows, `pwsh`, the lock's folder), makes a fresh instance (`mods/` = the old jar + fabric-api from the Gradle cache, a minimal `options.txt`) and the TLS material, hosts file and catalog, builds the driver, then takes the game-test lock, starts the server, runs the probe, launches phase `update`, records the helper's command line while it runs, waits for it, checks, launches phase `verify`, checks, stops the server, makes sure none of its clients remain, releases the lock, and writes the evidence (and the fixtures, if the run passed). |
| `e2e_checks.py` | The assertions (below). |
| `fixtures.py` | Replaces the instance path with `${INSTANCE}` in captured files. |

Only JVM properties redirect the client (v0.1.0 has no base-URL setting):
`-Djdk.net.hosts.file=<hosts>` (the three hosts, `localhost` and the machine name → 127.0.0.1; any other name fails to
resolve, so Mojang services are offline) and `-Djavax.net.ssl.trustStore=<p12>` with its password and type (without the
password a PKCS12 truststore fails the handshake; see `docs/smoke/self-update/redirect-proof.txt`). 0.2 also has
`-Drigtune.modrinth.baseUrl=<url>` (pass it with `--jvm-arg`); it widens the download allowlist to that origin.

## Checks

After the old version applied the update and the helper finished:
- the client exited normally and the helper finished;
- the driver applied the offered update;
- exactly one `rigtune*.jar` in mods, byte-identical to the served new jar;
- `<old jar>.disabled` exists (the old bytes);
- no `pending.json`, no `*.rigtune-pending`;
- `last-apply.json` holds exactly the update's DISABLE_FILE and ENABLE_FILE, both `OK`;
- every classpath entry of the helper JVM is under `config/rigtune/helper/` (AC5.2);
- the old RigTune (its User-Agent) downloaded the jar from host `cdn.modrinth.com`;
- the new jar's `depends` and `breaks` add or change nothing relative to the old one's (plan review M12).

After the new version started on the same instance:
- it exited normally; it is the new version, loaded from `mods/`;
- the goal set by the old version (QUALITY) is kept;
- `rigtune.json` `lastShownApply` equals `last-apply.json` `finishedAt` (the apply toast was shown; see the screenshot);
- the report is online and offers no further RigTune update;
- no crash report, mods unchanged, no new pending.json;
- with `--expect-history` (0.2 builds with the journal): `history.json` has an `entries` list with exactly one
  `legacy-import` entry, and that entry has no change for RigTune's own jars (with `--legacy-disable`, it holds the
  test mod's disable as `APPLIED`).

Undo scenario, after each launch and helper run:
- `mod-apply`: the added mod is in mods (the served bytes), the other is `.disabled`, no pending.json,
  `last-apply.json` has both ops `OK`, `history.json` has one `apply` entry with both file changes `APPLIED`;
- `mod-undo`: the plan had two reverts needing a restart for that entry, the added mod is `.disabled` again, the other
  is back, `last-apply.json` has both reversal ops `OK`, `history.json` has one `undo` entry (its changes `APPLIED`,
  each `reverts` one of the apply's changes) and the apply's changes are `REVERTED`;
- `mod-check`: the other mod is loaded and the added one isn't, nothing is left to undo, no crash, mods and history
  statuses unchanged, no pending.json.

## Evidence and fixtures

`--evidence <dir>` gets `RESULT.md`, `checks.json`, the driver outputs and report dumps, the screenshots, the
request log, the helper log and command line, the redirect probe, filtered client logs and the captured files.
Absolute paths are replaced by `<instance>`, `<run>`, `<repo>` and `~`. An existing folder is replaced only if it is
empty or holds an earlier `RESULT.md`; otherwise the evidence stays in the run folder.

`--capture-fixtures <dir>` writes the old version's files (`pending.json` as staged before quitting, `last-apply.json`,
`rigtune.json`, `rules-cache.json`, `helper.log`) with the instance path replaced by `${INSTANCE}`, plus
`manifest.json` (with the run's verdict and any failed checks). Only a passing run writes them, unless
`--capture-anyway`. In JSON files only string values that start with the instance path change (the token, then `/`
separators); in `helper.log`, the paths after the token use `/` too. Tests substitute their own folder for the token
(JSON-escaped in JSON files).

## Game-test lock

Only one Minecraft client may run on this machine at a time. The script takes `C:/Dev/Worktrees/.gametest-lock`
(atomic `mkdir`, `owner.txt` inside) for the launches, fails fast if it's held (exit 3), kills only processes whose
command line contains its own run folder (a timestamp and a random suffix), one process at a time and never a process
tree (a Gradle daemon can be a child of the wrapper and serves other builds), waits until none of its clients or Gradle
wrappers remain, and only then removes the lock, and only if `owner.txt` names its run. `--lock none` skips it
elsewhere.
