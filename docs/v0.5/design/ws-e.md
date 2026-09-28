# WS-E: verification gaps and publishing machinery (design and TDD plan)

Branch `test/v05-e2e`, worktree `rigtune-e2e5`. SPEC 3a-3f and 3h's release.yml, AC4j.3's E2E leg, the `v050-written`
convention. Research: docs/research/v0.5/verification-gaps.md (vg). Plan: docs/v0.5/PLAN.md "WS-E".

**Status (2026-09-28):** the early part (E1-E4), E5/E7 and the later part are done except the items under "Residuals"
(the DH server-note run, the generated seeds, and the legs that wait for WS-H and WS-L1/L2). CI run 36363179808 is green on
every job. See "Later part: status".

## Early part: tasks (TDD; each ends with `python -m unittest discover -s tools/e2e/tests` green and a commit)

### E1. The harness's Linux path (AC3a.1)
- **What:** the `tools/e2e/` parts of research commits 25243b63 and 1f4b6b3a, applied as their diff. Neither
  `r-verify-experiment.yml` nor the build.yml hunk comes along: build.yml is ws-ci's until it merges.
  - `posix_java_processes()` from `/proc`; SIGKILL in `kill_own`.
  - A Python watcher thread for the helper's command lines.
  - `separator=os.pathsep` for the classpath check.
  - `sudo -n chattr +i/-i` as the seeded hold on Linux.
  - `preflight` accepts Linux.
  - `RELEASED` gains `0.2.0+mc26.3`, `0.3.0+mc26.3`, `0.4.0+mc26.2` and `0.4.0+mc26.3` (GitHub release digests).
  - The log filter: the narrator and SoundSystem lines, and GLFW's X11 cursor block only when its third line is that message.
- **Tests the research commits didn't have** (new file `tests/test_e2e_linux.py`):
  - `posix_java_processes(proc=<fake /proc>)`: java and javaw are listed with the NUL-joined command line; a non-java process, a non-numeric entry and an unreadable `cmdline` are skipped. This needs a `proc` parameter, default `/proc`.
  - The seeded hold on POSIX: `chattr +i` runs before the launch with `check=True`, and `chattr -i` runs after it even when the launch raises. Checked with `subprocess.run` and `launch_and_apply` patched. On Windows the file is opened instead.
  - The watcher thread appends `pid\tcmdline` lines for this run's `ApplyHelper` only, and `stop_watcher` joins it.
  - `kill_own` on POSIX sends SIGKILL to own processes only, and a vanished pid is ignored.
  - `rigtune_log_problems`: the Xvfb lines are dropped; a GL ERROR block with another message is kept; a cursor line without the GL ERROR header two lines up is kept.
  - `preflight` on POSIX needs `/proc/self/cmdline`, not pwsh.
- **Red first:** the new tests run against the base harness (`git show origin/feat/v0.5.0:tools/e2e/…` in a temp folder) fail: `posix_java_processes` is missing, and the filter keeps the Xvfb lines. Output recorded in the commit message.
- **Pins test:** `test_ci_pins_the_same_jars` compares `RELEASED` with build.yml's `sha256sum -c` lines. Until WS-E may edit build.yml, it checks that every jar build.yml pins is in `RELEASED` with the same digest (a subset).
  - The build.yml task (E6) adds the new pins and restores equality. Recorded under Residuals until then.

### E2. `tools/e2e/e2e_matrix.py` (AC3a.2)
- **What:** prints `{"include": [...]}` for `--tier push|release` from one table. Each row has `id`, `mc`, `old` (version or ""), `tag`, `asset`, `sha256` and `args` (a `shlex.join`ed string).
  - Old jars, tags and digests come from `self_update_e2e.RELEASED`; a version without `+mc` (0.1.0) is 26.2's.
  - Nodes come from `versions/*/` (`gametest_matrix.nodes`).
- **Rows** (only scenarios the harness supports today; later scenarios are added as their tasks land):
  - Push: upgrade from the newest release on each node (0.4.0 → new).
  - Release:
    - upgrades from every other release on each node;
    - 0.1.0 with `--legacy-disable --expect-history auto`;
    - seeded `v010-dh` (26.2);
    - undo with `--profile-switch profile` and with `settings`;
    - downgrade to 0.4.0 and to 0.3.0 on each node.
- **Tests** (`tests/test_e2e_matrix.py`):
  - every `RELEASED` entry is used by the release tier;
  - the push tier is exactly 0.4.0 → new per node;
  - nodes follow `versions/*/`: a temp root with an extra node gets its rows, and a node with no release gets only the no-old-jar rows;
  - ids are unique and shell-safe;
  - every row's `args` (plus the workflow's fixed arguments) passes `self_update_e2e.parse_args`;
  - no row names a jar outside `RELEASED`;
  - the output is compact JSON.
- **Red first:** the tests fail on the missing module.

### E3. `written.py`: fixture generations and the `v050-written` consumption (3b)
- **What:**
  - A `Generation` per fixture root:
    - `v040-written`: sets `ws-a…ws-f` and today's `NEW_FILES`/`KEPT`.
    - `v050-written`: sets `ws-l1, ws-l2, ws-s, ws-s2, ws-p2, ws-b, ws-t, ws-h, ws-w2, ws-f` (PLAN, contracts item 15); new files `stutter-fixes.json`, `tryit.json`, `server-profiles.json`.
  - `resolve(root)` infers the generation from the folder name. `resolve_all(roots)` resolves several roots, older first.
  - `compose` merges a file several sets provide:
    - history.json's entries by `at` (ids unique);
    - pending.json's ops (ids unique);
    - any other JSON object deep-merged: objects key by key, lists concatenated without exact duplicates. A scalar that differs between two sets of the same generation is an error; across generations the newer one wins, because 0.5 rewrote the file.
    - A file only one set provides keeps its bytes (unchanged).
  - `new_files_for(old_version)`: the files an older version never reads, i.e. the union of every newer generation's new files. 0.4.0 → the three 0.5 files; 0.3.0 → those plus 0.4's five.
  - `kept_for(generations)`: the union of each generation's `KEPT`. 0.5's new files join `KEPT` when their owners' formats land (not invented here).
  - The harness's `--written` may repeat. The default is `v040-written` plus `v050-written` when it exists, because a 0.5 instance also holds what 0.4 wrote. `seeded_state` takes the old version's `new_files_for`.
  - `NEW_FILES`, `KEPT` and `SETS` stay as v0.4's names, so the existing callers and tests read the same.
- **Tests** (`tests/test_written.py` additions):
  - the generation is inferred from the root name;
  - `resolve_all` keeps order and marks placeholders;
  - deep merge: objects, lists, the same-generation scalar conflict and the newer-generation win;
  - history.json across generations sorted by `at`, with a repeated id refused;
  - `new_files_for("0.4.0+mc26.2")` and `("0.3.0+mc26.3")`;
  - the one-provider byte copy is unchanged;
  - the harness default `--written` with and without a `v050-written` folder.

### E4. `compat040.py` + `compat/Compat040.java` (AC3b.1, AC3b.2's harness side)
- **What:** compat030 generalised to the released 0.4.0.
  - Pinned to `rigtune-0.4.0+mc26.2.jar` sha256 `801cd3b8…868a` through `RELEASED`; any other jar is refused.
  - The Java program compiles at launch against that jar, Gson 2.14.0, fabric-loader and slf4j (as compat030 does).
  - Default `--written` is `v040-written` + `v050-written`.
- **Checks** (one line each, in the `EXPECTED` list):
  - `Journal`: state OK with the same entries;
  - `HistoryModel`: every entry, no unknown kind;
  - `UndoPlanner`: Undo this on every entry with a change still applied or staged plans without a problem; Undo last and Undo all plan;
  - `BenchmarkHistory`: no `.bad`, contexts kept;
  - `PendingActions`: every op's type, id and mod id;
  - `ClientSettings`: read as written;
  - `StutterStore`: every session loads, and `StutterSummary` renders it;
  - `AwarenessStore`: loads, and on a copy in a temp folder an update keeps the unknown fields;
  - `ProfileStore`: loads, with every switch's label;
  - `ServerLimitsStore`: loads every server;
  - `RulesLoader`: the same counts with and without the top-level sections 0.4.0 doesn't know (from the file itself);
  - 0.4.0 reading them changed no file.
- compat030 gets the same multi-root `--written` (AC3b.2 runs it on the `v050-written` sets).
- **Tests** (`tests/test_compat040.py`, like `test_compat030.py`):
  - the jar gate (a 0.4.0-named jar with other bytes is refused, as is the wrong version);
  - `EXPECTED`/`missing`/`parse`;
  - the classpath lookup;
  - the default roots.
- **Proof of the Java program:** a local run on the released 0.4.0 jar against `v040-written` (no client, no lock). Output in `docs/v0.5/verification/e2e/compat040-early.txt`. CI runs it once the build.yml step exists (E6).

## Later part (after ws-ci and WS-K merge; the coordinator's go)
- E5 `.github/workflows/e2e.yml`, reusable (AC3a.3, AC3a.6).
- E6 build.yml: the `e2e` job, the compat040 and compat030 steps, and the new pins (restores E1's equality).
- E7 release.yml: build → e2e → publish, the CDN sha512 and metadata checks (AC3c.1-2).
- E8 generated seeds; `helper-kill` (3f); the reverse-check and version-pin scenarios (AC3f.7); `v010-dh-app-reinstalled` (AC2H.6); the launcher-brand leg (AC4j.3).
- E9 `LanGuestGameTest` and the Realms block (3d).
- E10 `BatteryFlowGameTest` after PF-1, and the tmpfs OSHI leg (3e).
- E11 the high-contrast A11y method (AC3f.3); the snapshot-canary fixture test (AC3f.8).
- E12 docs/v0.5/verification/{e2e,server,battery}/.

## Early part: status (2026-09-27)

| task | commit | tests | red first | result |
|---|---|---|---|---|
| E1 Linux path | 2a495093 | `test_e2e_linux.py` 12 | 11 of 12 fail against the base harness (9 missing functions, 2 Xvfb lines reported) | pass; research CI runs 36288269972 / 36288792339 / 36289370916 ran this code on Linux (61-272 s per scenario, vg §1.3) |
| E2 `e2e_matrix.py` | 99e59018 | `test_e2e_matrix.py` 11 | module missing | push tier: 0.4.0 → new on 26.2 and 26.3; release tier: 16 rows |
| E3 fixture generations | ed97abac | `test_written_v05.py` 14; v0.4's two-set test rewritten | 13 errors + 1 failure | pass; CI 36295057958 green on every job |
| E4 compat040 | 420f58b3 | `test_compat040.py` 7 | module missing, compat030 had no `parse_args` | local run on the released 0.4.0 jar: **PASS 14/14** (`docs/v0.5/verification/e2e/compat040-early.txt`); compat030 still PASS 9/9 |

| E5 `e2e.yml` + E7 `release.yml` (coordinator's go, 2026-09-27) | 199bed3e | `test_e2e_workflows.py` 11 (a mutation check of 9 edits fails each), `test_release_verify.py` 8 | modules and workflow shape missing | actionlint + shellcheck clean. **Release dry run 36296717280: build → e2e release tier 16/16 PASS on the first attempt → publish (nothing published; verify of v0.4.0 incl. CDN bytes, all verified)**. Evidence: `docs/v0.5/verification/e2e/README.md` |

Harness suite: 279 tests (was 216 at the branch point).

E5/E7 notes:
- A dispatch of release.yml is always a dry run.
  - The tag is `v<mod_version>`; `gh release create` is only printed; Modrinth gets `-PmodrinthDryRun` with no token.
  - The verify step reads an existing release (`verify-tag`).
- `-x assemble` on the Modrinth task: Minotaur depends on `assemble`, but the upload is the staged file, so publish builds nothing (`-m`: only `:<mc>:modrinth` runs).
- e2e.yml's own dispatch (it builds its jars) works only once the file is on the default branch. On the branch it runs through release.yml's `workflow_call`.
- For E6 (build.yml): an `e2e` job `uses: ./.github/workflows/e2e.yml` with `jars-artifact: rigtune-jars`.
  - The tier: `release` for a pull_request into main from `feat/v*`, else `push`.
  - The compat040 and compat030 steps in the java job, and the new pins (restoring the equality test).
  - ws-ci's offline/prefetch mechanism, if it lands, applies to e2e.yml's Gradle steps too.

For WS-K's `v050-written/README.md`: compose deep-merges a file several sets provide:
- objects are merged key by key;
- lists are merged without exact duplicates;
- a scalar two sets both hold takes the later set's value (set order: `ws-l1 … ws-f`, after all of v0.4's sets) and is listed as "merged: …" in compat040's output;
- a different `formatVersion`/`schemaVersion` is refused.

So each set may commit the whole file its own test writes.

## Later part: status (2026-09-28)

Held locally during the coordinator's first CI streak, then pushed in batches.
- **CI [36363179808](https://github.com/chaotix345/rigtune/actions/runs/36363179808) (97aaeaef): green on every job.**
  - LanGuestGameTest, BatteryFlowGameTest and A11yGameTest's high-contrast block pass on 26.2 OpenGL, 26.3 OpenGL and 26.3 Vulkan.
  - Both e2e push rows (upgrade-from-0.4.0 on both nodes) pass.
  - The java job's compat040 + compat030 pass.
- The release-tier legs added here (battery-oshi, stutter-script) ran on the scratch branch `scratch/ws-e-battery-oshi` (never merged): e2e.yml with only those legs on.

| task | commits | tests | result |
|---|---|---|---|
| Merges of `origin/feat/v0.5.0` (ws-ci + WS-K; then Wave A's early merges) | 2f67875a, a60c9048 | full suites | release.yml conflict with ws-ci's JDK/runner pins (ours, then JDK 25.0.3); WS-P2's real `ws-p2` set replaced its placeholder |
| E6 build.yml + SPEC-4 | b97bcd86 | `test_e2e_workflows.py` (BuildWorkflowTest), `test_e2e_v04.py` (pins = build.yml), `tools/tests/test_ci_workflow.py` (e2e.yml `--offline` rule) | build.yml's `e2e` job runs e2e.yml on the java job's `rigtune-jars`: push tier on every push, release tier on a PR into main from `feat/v*`. e2e.yml: one "(network)" step per job (prefetchDependencies + downloadAssets, the released jar on a cache miss, sha256-checked), every later Gradle call `--offline`. The java job pins 7 released jars and runs compat040 + compat030. The pins test caught a cache-key typo in ws-ci's build.yml (`…71ae` → `…71aa`) |
| compat040 data-driven + v050 placeholders (3b, PLAN-20) | 85763ec4, c001ddcd, 4c3b6696, be56b00b, e1de5575 | `test_compat040.py` 10, `test_written_v05.py` 16 | each set's `expect.json` interpreted by `Compat040.java` on that set alone (spare copy for writes); a check of a file the set doesn't hold fails (found on WS-P2's `ws-p2`, routed). Local: compat040 PASS, compat030 PASS on v040 + v050 |
| Downgrade E2E with v050 (AC3b.3) | 4c3b6696, e1de5575 | `test_e2e_downgrade.py` | local Windows 26.2, to 0.4.0 and to 0.3.0: PASS with the placeholders + real `ws-p2` (`docs/v0.5/verification/e2e/local-windows-26.2/`). The first run failed on ws-t's placeholder try (its PATCH target already held the value 0.4's switch left): fixed in the placeholder, 2 → 4. Check names now name the versions they run |
| E9 LanGuestGameTest + Realms (3d) | 5a2066b7, b8699e8b, 97aaeaef | game test | local Windows 26.2: PASS in 17 s. The LAN list join, the restart's "(was 6)", and Realms through `RealmsConnect`. CI 36363179808: PASS on all 3 legs (13-15 s). In CI the detected address is 0.0.0.0 (below). Evidence in `docs/v0.5/verification/server/` |
| E11a A11yGameTest high contrast (AC3f.3) | 5a2066b7 | game test | local and CI 36363179808 (3 legs): PASS. It uses the option's own pack reload. Label pixels, grey / high-contrast: 0 / 2268 on, 2908 / 0 off in CI |
| E10 BatteryFlowGameTest + the OSHI leg (3e) | 5ecc4edf (landed once PF-1 was in feat) | game test; `test_e2e_workflows.py` battery leg; BatteryPromptTest skew boundary | CI 36363179808: PASS on 3 legs; the offer and its toast 1.85-2.05 s after each unplug. **battery-oshi (release tier), scratch run 36362848495: PASS on both nodes.** The startup probe saw `hasBattery=true, onBattery=true`; STATUS changes were seen after 54-60 s each. Evidence in `docs/v0.5/verification/battery/` |
| AC3e.3 PowerWatcher unit cases | 1a1cdb32, bb9aaf14 | PowerWatcherTest +4 (6 on both nodes) | unreadable polls aren't AC, one of two batteries discharging, a wiggle, stop before the probe (a source seam: red without the guard, review M1) |
| E11b snapshot canary (AC3f.8) | 43e34cbb | `tools/tests/test_snapshot_canary.py` 6, two fixture manifests | `tools/snapshot_canary.py` = the workflow's resolve step. The workflow's one edit (calling it) waits for the first scheduled run (2026-09-30) |
| E8 helper-kill (AC3f.5) | a08645d2 | `test_e2e_helper_kill.py` 12 | local Windows 26.2: PASS. The helper was killed 1.5 s after it recorded the group (its op 2 retrying a held file); the next exit's helper applied both ops; History shows "Updated e2e-kill" Applied. Release tier, both nodes |
| E8 reverse check + version pin (AC3f.7) | 6ee35ee6, b8699e8b | `test_e2e_guard.py` 5 | local Windows 26.2: the whole undo scenario PASS, `guard-apply` included, first run (`docs/v0.5/verification/e2e/local-windows-26.2/undo-guard-*`). Release tier: both undo rows, both nodes |
| AC3f.1 stutter script (26.3) | 2c0eee08, c87d7e13, 6156f948 | `test_stutter_run.py` 7 (v0.4's recorded C1r run passes; each criterion broken fails) | `tools/e2e/stutter_run.py` + e2e.yml's `stutter-script` leg (release tier, per node, the caller's jar, offline). Scratch run 36362848495 ran it on both nodes. The dev script finished; GC claims matched JVM pauses; the remainder is shown. Two tag checks copied from v0.4's evaluator (a 30 s window) failed; the product tags only its 10 s `TELEPORT_WINDOW`, and in that window both nodes pass. See `docs/v0.5/verification/stutter/ac3f1-stutter-script/` |
| Wave A fixture round (merge c59b8b93) | f355d3f0, 4e3b75ce | `test_written_v05.py` 17 | the real `ws-b/f/p/s/w` sets replace their placeholders. compat040's `StutterSummary` kind is for WS-S (AC2S.13). profiles.json keeps one baseline when composed (WS-P's question: the latest set's, as ProfileStore does). ws-p2's check on an absent file was dropped (a recorded cross-owner edit, the coordinator's decision). compat040 and compat030 PASS locally on all sets |
| Review round (0 H, 3 M, 7 L; the coordinator's decisions) | bb9aaf14, b8699e8b, 5ecc4edf | as above | M1 is the PowerWatcher source seam. M2: battery-oshi runs the caller's jar (`-PgametestModJar`, the mod jar isn't built). M3: battery-oshi ran once (36362848495). L4: guard-apply waits for each Apply's own status key. L5: LanGuest picks its own server's entry and checks a local address in CI. L6: LanGuest leaves cleanly and restores the store and the network switch (ServerLimitsGameTest's switch restore too). L7: the battery watcher's thread is awaited. L8: offer latency logged per unplug. L9: helper-kill checks the loaded e2e-kill 1.1.0. L10: none |
| JDK download retry (ws-ci's rule) | c87d7e13 | JdkRetryTests; `test_e2e_workflows.py` allows continue-on-error only there | every setup-java step in e2e.yml and release.yml's publish job |
| AC2H.6 `stale-seed` (WS-H's RW-3) | 26df47f3 | `test_e2e_stale_seed.py` 4 | seeds `v010-dh-app-reinstalled` and `-disabled`: v010-dh's pending.json and last-apply.json with DH 3.3.2 at the group's target name, no download, no mods/update, `fabric-26.2.jar` removed or disabled. The new version starts on the state directly. Local Windows 26.2, both legs PASS: the group is dropped, the `stale_installed` line names Distant Horizons, History shows both changes ABANDONED, latest.log has "can never run" and no "will be retried", and nothing happens at exit. Release tier, 26.2 |
| Downgrade with the Wave A sets | 26df47f3 | `test_written_v05.py` (the trim, a staged entry kept) | WS-P's ws-p is at the journal cap (50), so the composed journal had 57 entries. The old versions' own cap (MAX_ENTRIES, entries with nothing left to undo go first) then evicted the Undo-last pair the check looks for. The downgrade instance keeps the newest 46 entries plus every entry a staged op belongs to (`DOWNGRADE_HISTORY`). Local: both targets PASS with the real ws-b/f/p/p2/s/w sets |
| Local reruns after the review round | (runs) | | helper-kill, the undo scenario with guard-apply, both downgrades and both stale-seed legs: all PASS on 2026-09-28 (`docs/v0.5/verification/e2e/local-windows-26.2/2026-09-28-*`) |

**AC3f.7 (`guard-apply`).** It runs after entry-check on the undo scenario's instance, in one start:
- The update of `e2e-pin-target` 1.0.0 → 2.0.0 is refused. The installed `e2e-pinner` pins the target to `1.0.x` in its fabric.mod.json.
- The addition `e2e-rev-add` is staged, then applied at exit.
- The update of `e2e-rev-target` 1.0.0 → 1.1.0 is refused. The staged addition's Modrinth version declares that version incompatible, by version id.
  - RigTune can know this only by reading the staged version back through FakeModrinth's `GET /v2/versions?ids=`; the check requires that request.

Each Apply's outcome is the status line its downloads leave (`pinStatus`/`addStatus`/`reverseStatus` in the driver's JSON).

**helper-kill design notes.**
- When op 2 fails, the executor rolls op 1 back before it pauses (ApplyExecutor.runGroup). So a kill "during the back-off" leaves the group recorded but not half-applied, and the next run starts it again cleanly.
- The half-applied path (a kill between op 1's rename and its rollback) is a timing window of milliseconds that the harness doesn't aim for. `after_helper_kill` accepts either state; the next exit's result is checked either way.
- On Linux, `chattr +i` gives EPERM. Files.move reports it as an AccessDeniedException, which the executor treats as a sharing violation. So Linux gets the same ~30 s retry budget as Windows' held handle.

**javap (SPEC X9 / SPEC-17), 26.2 vs 26.3 (Loom's mapped client jars):** identical signatures for:
- `LanServerPinger`, `LanServer`, `ServerSelectionList` and `NetworkServerEntry`, `JoinMultiplayerScreen`;
- `RealmsConnect`, `RealmsServer`;
- `ToastManager.getToast`;
- `Gui.overlay()`;
- `Options`' high-contrast callback (the `high_contrast` pack add/remove plus `updateResourcePacks` → `reloadResourcePacks`).

The fabric client gametest API is the same too: `createServer(Properties)`, `clickScreenButton`, `setScreen` in 6.0.2 (26.2) and 6.0.7/6.0.8 (26.3).

## Acceptance criteria (WS-E's)

| AC | how | status |
|---|---|---|
| AC3a.1-3a.3 | the Linux harness, e2e_matrix, e2e.yml | early part; release tier 16/16 on the dry run 36296717280; build.yml hookup in CI with the push |
| AC3a.4 | the release tier on the release PR | Phase 5 (the coordinator schedules it) |
| AC3a.5 | the Windows RC set (seeded run with a real handle, 0.4.0 → RC, undo, downgrade) | Phase 5 |
| AC3a.6 | `test_e2e_workflows.py` + `test_ci_workflow.py` | CI python job green (36363179808) |
| AC3b.1, AC3b.2 | compat040 / compat030 in the java job | CI java job green (36363179808) |
| AC3b.3 | the downgrade rows (both targets, both nodes) | local 26.2 PASS with v050 sets; the "back on 0.5" part checks the placeholders' files (and ws-p2's servers) are read back. The tracked fix, the open try and the profile labels need WS-S2/WS-T/WS-P's real sets and code |
| AC3c.1, AC3c.2 | release.yml build → e2e → publish | dry run 36296717280; AC3c.2 at the v0.5.0 release |
| AC3d.1, AC3d.2 | LanGuestGameTest | CI PASS on 3 legs (36363179808) |
| AC3d.3 | README "Known limits" | docs workstream (text in "Docs" below) |
| AC3e.1, AC3e.2 | BatteryFlowGameTest, `battery-oshi` | CI PASS on 3 legs (36363179808); battery-oshi PASS on both nodes (scratch 36362848495) |
| AC3e.3 | PowerWatcherTest, BatteryPromptTest | in; CI green |
| AC3e.4 | README battery line | docs (below) |
| AC3f.1 | the 26.3 stutter-script CI run | scratch 36362848495 (both nodes): PASS in the product's teleport window; record in `docs/v0.5/verification/stutter/ac3f1-stutter-script/` |
| AC3f.3 | A11yGameTest high contrast | CI PASS on 3 legs (36363179808) |
| AC3f.4 | the DH server-note run | open (code-deciding, under the lock) |
| AC3f.5 | helper-kill | local Windows PASS; Linux release tier with the push |
| AC3f.7 | guard-apply | local Windows PASS; Linux release tier with the push |
| AC3f.8 | snapshot_canary.py + fixture test | test in; the workflow edit after 2026-09-30 |
| AC4j.3 | the launcher-brand leg | after WS-L1/L2 |
| AC2H.6 | `v010-dh-app-reinstalled`, both legs (`--scenario stale-seed`) | local Windows PASS; Linux release tier to run |

## Residuals
- **Fixed since the early part:**
  - the pins test covers all 7 released jars (E6);
  - compat040 has its CI step (E6);
  - the downgrade checks name the versions they run (4c3b6696);
  - `server-profiles.json` is in `V050.kept` (e1de5575).
- **Open:**
  - `stutter-fixes.json` and `tryit.json` join `V050.kept` when WS-S2's and WS-T's real sets land. Until then their placeholders are only checked byte-identical after a downgrade.
  - The LAN source address in CI is 0.0.0.0 (offline.sh's multicast route has no `src`). Sent to ws-ci; LanGuestGameTest accepts a loopback or wildcard address until then.
  - Local Windows reruns after this round's changes (helper-kill with L9, undo with L4, the downgrade with the real Wave A sets) wait for the game-test lock (the user is playing).
  - The snapshot-canary workflow edit (its resolve step calls `tools/snapshot_canary.py`) is made after the scheduled run on 2026-09-30.
  - Still to do:
    - AC3f.4 (the DH server-note run): the coordinator decides the approach.
    - The launcher-brand leg: after WS-L1/L2.
    - The first Linux run of the release tier with this round's rows (helper-kill on both nodes, guard-apply, stale-seed, the downgrades with v050 sets): the coordinator schedules it.
  - **The generated seeds (vg §1.5) as designed can't reproduce v010-dh's shape.** 0.2.0 and later cancel their own pending update once the mod's build is queued in mods/update (`rigtune.status.queued_update_dropped` is in 0.2.0+mc26.3's, 0.3.0's and 0.4.0's lang files). So the old side of a generated seed drops the group itself.
    - The proposed honest equivalent: the old version's held DH group is carried across the self-update and finished by 0.5 at its exit.
    - This is waiting for the coordinator's decision.
- **A note for the docs workstream:** in BatteryFlowGameTest's screenshot at 854×480 the battery notice's text is cut ("You're on battery power. Switch t…") by its two buttons. That is v0.4's notice layout (WS-P's).

## Stays UNVERIFIED (WS-E's part)
- **A second PC's Open-to-LAN host:** its integrated server authenticates guests; one-client rule.
- **The real Realms service** (no subscription).
  - What LanGuestGameTest's Realms block proves:
    - vanilla's `RealmsConnect.connect(RealmsServer, ServerAddress)` path (its connect thread, the handshake listener with the `ServerData` that `RealmsServer.toServerData` makes, Type.REALM, the login), against a local offline dedicated server;
    - RigTune's side of it: `isRealm()`, the REALM classification, the `realm:<world name>` key hashed in server-limits.json with no plaintext name, and the view-distance notice.
  - What it doesn't prove:
    - the Realms API: the worlds list, `RealmsMainScreen`'s Play, and the join call that returns the address;
    - a Realms server's authenticated (online-mode) login and its resource-pack prompt;
    - how a real Realm sends view distance;
    - minigame Realms.
- **Windows Firewall's multicast prompt and networks that block multicast.** The local LAN run passed unattended; this PC's firewall rules weren't inspected.
- **Windows OSHI on a real laptop battery:** the user's laptop run (3g).
- **A helper killed between op 1's rename and its rollback** (the half-applied state): a millisecond window the E2E doesn't aim for. ApplyGroupsTest's `aHelperKilledBetweenTheRenamesIsFinishedByTheNextRun` and `…IsRolledBackWhenTheNextRunCantFinish` cover it in unit.
- **A physical power cut** (3f): needs a VM with a lossy disk.

## Docs (for the docs workstream)
- **README "Known limits", LAN and Realms (AC3d.3):** "Server limits were tested with a dedicated server joined through Minecraft's own LAN discovery (Multiplayer → the LAN list) and through the Realms connection path against a local server. Not tested: an Open to LAN game hosted on a second PC, and the real Realms service."
- **README battery line (AC3e.4):** "The battery offer was tested with a simulated battery through RigTune's real power watcher, and on Linux CI with a simulated battery read by the same hardware library the game uses; [the user's laptop run, if it happened]." Keep "real Windows laptop: not yet" until 3g.
- **PROGRESS / verification index:** `docs/v0.5/verification/server/README.md`, `docs/v0.5/verification/e2e/README.md` (+ `local-windows-26.2/`), and `battery/` once the battery branch lands.
