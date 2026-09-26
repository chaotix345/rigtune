# Self-update end-to-end test

Checks, in a real production Minecraft client, that an installed RigTune finds its own update on Modrinth, downloads
it, and that the post-exit helper swaps the jars; then starts the new version on the same instance and checks that it
reads the old version's files (SPEC item 5). The main scenarios run the unmodified released v0.1.0, v0.2.0 and v0.3.0 jars. Nothing here
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

### v0.4 runs (docs/v0.4/plans/ws-h.md)

The final runs (Phase 5) start from every released version, on 26.2 only (vanilla 26.3 crashes natively on most local
launches). Released jars; the harness refuses a jar that claims a released version with other bytes (`RELEASED` in
`self_update_e2e.py`; CI's "Compile the E2E drivers" step pins the same three and compiles the driver against each):

```sh
gh release download v0.1.0 -p 'rigtune-0.1.0.jar'         # 8294d04a6b67e76dcff298366be38f85048ebf19a120baa9e8ed5b08b2e4b950
gh release download v0.2.0 -p 'rigtune-0.2.0+mc26.2.jar'  # 67275e232fe4de9f806dd6496f479d8385d8afabf9a6b93ffe909ce42f657de9
gh release download v0.3.0 -p 'rigtune-0.3.0+mc26.2.jar'  # 5717f65cb90c71aaeda844b7bd56e3ce9255e83f44418af0cfc6a589050cd7e9
```

The new jar is the release candidate's `./gradlew :26.2:jar` output, copied into the scratch folder first: the fake
Modrinth serves the file where it is, so a rebuild during a run would change it. Run the five one at a time. The
harness takes and releases the game-test lock itself (exit 3: busy, try again later); the `release` after each command
only removes a lock that a killed run of this worktree left behind.

```sh
export JAVA_HOME="C:/Dev/Tools/jdk/jdk-25.0.4.1+1"
S=<scratch folder>; J=$S/jars; NEW=$J/rigtune-0.4.0+mc26.2.jar; E=docs/smoke/self-update
LOCK=C:/Dev/Worktrees/.gametest-lock; WT=<this worktree, forward slashes>
release() { grep -q "worktree: $WT" $LOCK/owner.txt 2>/dev/null && { rm -f $LOCK/owner.txt; rmdir $LOCK; }; }
run() { python tools/e2e/self_update_e2e.py --work $S/work --evidence $E/$1 --name "$@"; rc=$?; release; return $rc; }

run final-v030-to-040 --old-jar $J/rigtune-0.3.0+mc26.2.jar --old-sha256 5717f65cb90c71aaeda844b7bd56e3ce9255e83f44418af0cfc6a589050cd7e9 --new-jar $NEW --expect-history auto
run final-v020-to-040 --old-jar $J/rigtune-0.2.0+mc26.2.jar --old-sha256 67275e232fe4de9f806dd6496f479d8385d8afabf9a6b93ffe909ce42f657de9 --new-jar $NEW --expect-history auto
run final-v010-to-040 --old-jar $J/rigtune-0.1.0.jar --old-sha256 8294d04a6b67e76dcff298366be38f85048ebf19a120baa9e8ed5b08b2e4b950 --new-jar $NEW --legacy-disable --expect-history auto
run final-v010-seeded-to-040 --old-jar $J/rigtune-0.1.0.jar --old-sha256 8294d04a6b67e76dcff298366be38f85048ebf19a120baa9e8ed5b08b2e4b950 --new-jar $NEW --seed tools/e2e/seeds/v010-dh --expect-history auto
run undo-after-restart-040 --scenario undo --new-jar $NEW --profile-switch profile --profile-name <a WS-P profile>
```

- `--expect-history auto` takes the check from the old jar's version (`e2e_checks.history_expectation`): 0.1.x →
  `legacy-import`, 0.2.0 and later → `own-update` (0.2.0 and 0.3.0 journal their own update as one `apply` entry that
  the new version must read as it is; with `--legacy-disable` that entry also holds the test mod's disable). An
  explicit `legacy-import`/`own-update` that doesn't fit the old version is refused, so the v0.3 commands still run.
- Each run's `RESULT.md` names the old and new jar (version, sha256) and the expected history. The dry runs on a
  0.4.0-dev build are in `docs/smoke/self-update/dev-*-040` (docs/v0.4/design/ws-h.md has the counts).

**Profile hook (`--profile-switch`, undo scenario).** After the per-entry case, three more launches on the same
instance: `profile-apply` (a profile switch, which is an ordinary Apply of setting changes: one `apply` journal entry,
labelled by WS-P in `config/rigtune/profiles.json`), `profile-undo` (Undo this on that entry in the next start:
`undoPlanFor(entryId)`, `UndoScreen` and its Undo button; the vanilla values revert at once) and `profile-check` (the
game runs with the values from before the switch, nothing left to undo on the entry, statuses and mods unchanged).
Checks: exactly one new `apply` entry whose changes are all `vanilla.*` settings, `APPLIED`, with `options.txt` holding
their `after`; the plan reverts every change of the entry with no restart; one `undo` entry reverting each change, the
switch's changes `REVERTED`, `options.txt` holding their `before`; no `pending.json` at any step.
- `--profile-switch settings` (usable now) is the stand-in: the driver applies `renderDistance:6,maxFps:90`
  (`PROFILE_SETTINGS`) through `controller.apply`, as a switch does, and the checks also want exactly those keys with
  the `options.txt` values from before the launch as `before`.
- `--profile-switch profile --profile-name <name>` (Phase 5, once WS-P has merged) also checks that `profiles.json`
  labels the entry (`switches: [{entryId, profileId, templateId, name}]`, docs/research/v0.4/profiles.md §3) before and
  after its undo. Before the final run:
  1. Put WS-P's switch call in `UndoDriver.switchProfile(controller, name)` (src/e2eUndo; today it throws): switch to
     the profile named `name` the way the Profiles screen does and return the status message. CI's
     `compileE2eUndoJava` then guards that API.
  2. If WS-P's `profiles.json` differs from that shape, adjust `e2e_checks.profile_label` and its tests
     (`tests/test_e2e_profile_hook.py`).
  3. Pick a profile that changes at least one vanilla option on a fresh instance (every option at its default, no
     Sodium/DH/Iris): SPEC item 3's run uses Battery (`--profile-name Battery`); profiles.json's `switches` shape is
     SPEC "Shared contracts" C1.
  4. SPEC item 3 also wants the switch's staged config patches restored after the next exit and a screenshot of History
     showing "Profile: Battery". The fresh instance has no config target, so neither is covered yet. For them: put
     Sodium in `mods/` (a `prepare()` copy like fabric-api's), run `profile-apply` and `profile-undo` through
     `launch_and_apply` (the helper applies and reverts the patches at exit), let the checks accept `sodium.*`
     changes (compare with `config/sodium-options.json` after each helper run instead of `options.txt`), and add a
     History screenshot to the driver's `profile-apply` (after the switch) and `profile-undo` (before Undo this).

### v0.3 runs (docs/v0.3/plans/ws-h.md)

Released jars: `gh release download v0.1.0 -p rigtune-0.1.0.jar` (sha256 `8294d04a…b950`) and
`gh release download v0.2.0 -p "rigtune-0.2.0+mc26.2.jar"` (sha256
`67275e232fe4de9f806dd6496f479d8385d8afabf9a6b93ffe909ce42f657de9`). The self-update driver compiles against whichever
old jar is passed (`compileE2eJava -Pe2e.oldJar=<jar>` works for both), so no `--driver-api-jar` is needed.

| run | command (plus `--work <scratch>` and `--evidence docs/smoke/self-update/<name>`) |
|---|---|
| 0.2.0 → new | `--name final-v020-to-030 --old-jar rigtune-0.2.0+mc26.2.jar --old-sha256 67275e23… --new-jar <new> --expect-history own-update` |
| 0.1.0 → new | `--name final-v010-to-030 --old-jar rigtune-0.1.0.jar --old-sha256 8294d04a… --new-jar <new> --legacy-disable --expect-history` |
| seeded 0.1.0 → new (H-M2) | `--name final-v010-seeded-to-030 --old-jar rigtune-0.1.0.jar --old-sha256 8294d04a… --new-jar <new> --seed tools/e2e/seeds/v010-dh --expect-history` |
| undo after restart + per entry (M14, B-M3) | `--scenario undo --name undo-after-restart-030 --new-jar <new>` |

- `--expect-history own-update` (a 0.2.x old side): 0.2.x journals its own update as one `apply` entry (disable the
  old jar, enable the new one) that its helper marks `APPLIED`; the new version must read that journal as it is (no
  legacy import, no status changed by the relaunch). `--expect-history` alone means `legacy-import` (a 0.1.x old side).
- `--seed <folder>` (plan review H-M2): the instance starts in a real user's 0.1.0 state. `tools/e2e/seeds/v010-dh` holds
  templated read-only copies of the user's `pending.json` and `last-apply.json` (made by `make_seed.py`, which only
  reads; every spelling of the instance folder becomes `${INSTANCE}`, messages included; `source.json` records the
  originals' sha256) and `seed.json`, the fake jars to create: Distant Horizons 3.3.0 as `mods/fabric-26.2.jar`, 3.3.2
  as RigTune's download (`.rigtune-pending`) and in `mods/update/<folder>/` (DH's own updater's build). The group in
  `pending.json` is RigTune's DH update (disable `fabric-26.2.jar`, enable 3.3.2), which failed once because DH's
  updater held the jar. The harness keeps `fabric-26.2.jar` open (on Windows that blocks the rename) from the old
  version's launch until its helper is done, so the helper's retry fails the same way and the group reaches the new
  version. Checks after the update: `pending.json` holds only that group, one attempt more; the update's ops `OK`, the
  group's `FAILED`. After the first new start: the group is dropped (3a) with the status notice, its journal changes
  (the legacy import) are `DISCARDED`, `latest.log` has a WARN line per failed op with "attempt n of 3" (3e), no helper
  runs at exit, `mods/` at exit equals `mods/` when the driver quit, DH's installed and queued jars are untouched, and
  during the session `mods/` changed only by retiring RigTune's dropped download (`.rigtune-superseded`).
- `--scenario undo` now continues on the same instance with plan review B-M3's per-entry case: `entry-apply` (two
  Applies in one start, adding `e2e-first` then `e2e-second` from the fake Modrinth), `entry-undo` (Undo this on the
  older Apply: `RigTuneController.undoPlanFor(entryId)`, then `UndoScreen(Screen, RigTuneController, entryId)` and its
  Undo button, as the History screen's Undo this does; quit; the helper disables `e2e-first`), `entry-check` (only
  `e2e-first` is off, nothing left to undo on that entry). Checks: the plan is one revert needing a restart for the
  older entry's change only; the newer mod stays enabled; the journal has one `undo` of the older entry, its change
  `REVERTED`, the newer entry's change still `APPLIED`. The driver calls that API directly, so CI's
  `compileE2eUndoJava` breaks if it changes.
- The driver records every RigTune status line it sees (`statuses`: key and text) and every file under `mods/` with its
  sha256 when it quits (`modsAtQuit`).

## How it works

| piece | what it does |
|---|---|
| `java/.../FakeModrinth.java` | JDK-only HTTPS server for `api.modrinth.com` (`/v2/version_files`, `/v2/version_files/update`, `/v2/projects`, `/v2/project/{id}/version`, `/v2/version_file/{hash}`), `cdn.modrinth.com` (`/data/...`, the real jars with their real SHA-1/SHA-512) and `raw.githubusercontent.com` (the repo's `rules/rules-v*.json`). Routing uses the Host header. Every request goes to `requests.jsonl`. Unit tests: `src/test/java/.../e2e/FakeModrinthTest.java` (the test source set compiles `tools/e2e/java`). |
| `java/.../RedirectProbe.java` | Run before the game with the game's JVM properties: every URL must resolve to 127.0.0.1 and answer over trusted TLS, and an unlisted host must not resolve. |
| `e2e_env.py` | keytool certificate (SANs for the three hosts) and PKCS12 truststore, the `jdk.net.hosts.file`, the JVM arguments, the catalog. |
| `src/e2e/` (Gradle source set `e2e`) | The driver mod, compiled against the released old jar (`-Pe2e.oldJar`: v0.1.0 or v0.2.0), never against the current sources. Phase `update`: title screen → goal QUALITY → wait for "Update RigTune" → apply only it → wait until it's in pending.json (copied out: the helper deletes it) → quit through `Minecraft.stop()`, like the Quit button. Phase `verify`: screenshots of the title screen (apply toast) and the RigTune screen, the loaded version, the goal, whether an update is still offered → quit. |
| `src/e2eUndo/` (Gradle source set `e2eUndo`) | The undo driver, compiled against this repository's sources (0.2's `undoPlan`/`undo(plan)` and `UndoScreen`). The test mods are minimal Fabric mods (a `fabric.mod.json` only) made by `e2e_env.test_mod_jar`. |
| `:<mc>:e2eClient` (build.gradle) | Loom `ClientProductionRunTask` with `runDir` = the scratch instance and `mods` = the driver jar only (`-Pe2e.driver=undo` picks the undo driver). Loom's default would add this project's jar via `-Dfabric.addMods` (two RigTunes, and one outside mods/ that can't be updated); here RigTune loads only from the instance's `mods/`. |
| `self_update_e2e.py` | Checks it can run (Windows, `pwsh`, the lock's folder), makes a fresh instance (`mods/` = the old jar + fabric-api from the Gradle cache, a minimal `options.txt`) and the TLS material, hosts file and catalog, builds the driver, then takes the game-test lock, starts the server, runs the probe, launches phase `update`, records the helper's command line while it runs, waits for it, checks, launches phase `verify`, checks, stops the server, makes sure none of its clients remain, releases the lock, and writes the evidence (and the fixtures, if the run passed). |
| `e2e_checks.py` | The assertions (below). |
| `fixtures.py` | Replaces the instance path with `${INSTANCE}` in captured files; templates and instantiates seeds. |
| `make_seed.py` | Makes a seed (H-M2) from a real instance's RigTune files, reading only. |

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
(atomic `mkdir`, `owner.txt` inside with the v0.3 PLAN's lines `agent:` (`--agent`, default `ws-h`), `worktree:`,
`started:`, plus `run:`) for the launches, fails fast if it's held (exit 3), kills only processes whose command line
contains its own run folder (a timestamp and a random suffix), one process at a time and never a process tree (a Gradle
daemon can be a child of the wrapper and serves other builds), waits until none of its clients or Gradle wrappers
remain, and only then releases the lock, and only if `owner.txt` names its run: `owner.txt` first, then the empty
folder (never a recursive delete). `--lock none` skips it elsewhere. If the script itself is killed, release it from the
shell in the same command: `python ...; rc=$?; grep -q "worktree: <this worktree>" <lock>/owner.txt && { rm -f
<lock>/owner.txt; rmdir <lock>; }; exit $rc`.
