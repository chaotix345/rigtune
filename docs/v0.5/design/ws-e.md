# WS-E: verification gaps and publishing machinery (design and TDD plan)

Branch `test/v05-e2e`, worktree `rigtune-e2e5`. SPEC 3a-3f and 3h's release.yml, AC4j.3's E2E leg, the `v050-written`
convention. Research: docs/research/v0.5/verification-gaps.md (vg). Plan: docs/v0.5/PLAN.md "WS-E".

**Status: early part only** (PLAN "Phase 3 detail"): files under `tools/e2e/` and their tests. `.github/workflows/*`,
`build.gradle`, `gradle.properties` and `src/` wait for ws-ci and WS-K; the coordinator says when.

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

## Residuals (early part)
- E1's pins test checks a subset until E6.
- compat040 runs in CI only once E6 adds its step. Until then it's the local run above.
- The downgrade checks' names still say "0.4"/"0.3.0" (e2e_checks `after_downgrade_*`). They are generalised with the downgrade-to-0.4.0 rows' first real run (E8).
- 0.5's new files join `V050.kept` when their owners' formats land (WS-S2, WS-T, WS-P2).

## Docs (for the docs workstream)
- (filled at the end)
