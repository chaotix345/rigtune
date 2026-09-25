# WS-0 (Phase 3 foundation): design notes

Date: 2026-09-26. Branch `feat/v03-foundation`. Scope: SPEC item 2 (client game tests in CI), item 1 changes A and B, `mod_version=0.3.0-dev`, and the plan-review amendments W0-M1, W0-M2, W0-M3, W0-L1 and H-M1/H-M3.

## What changed

- **`tools/gametest_matrix.py`** (+ `tools/tests/test_gametest_matrix.py`, 13 tests). It prints `{"include": [{"mc", "backend"}, ...]}`: an OpenGL leg for every non-hidden directory under `versions/` (the same set as release.yml's `versions/*/`), plus a Vulkan leg for every node whose version core is at least 26.3. A node id must match `\d+.\d+(.\d+)?(-[0-9A-Za-z.-]+)?`, because the id goes into the workflow's shell steps. Nodes sort as snapshot < pre < rc < release < hotfix.
- **`build.yml`**
  - `permissions: contents: read`, and a per-ref `concurrency` group with `cancel-in-progress: true`.
  - A `gametest-matrix` job runs the script.
  - A `client-gametest` job reads that matrix with `fromJSON`. It is the research prototype's job: ubuntu-24.04, Loom's own xvfb-run, `SDL_OPENGL_FORCE_SRGB_FRAMEBUFFER=skip` on the OpenGL legs, lavapipe on the Vulkan legs, a 20-minute step timeout inside a 30-minute job, no retries, a backend check on `latest.log`, and screenshots, logs, crash reports and `hs_err` files uploaded per leg with `if: always()`.
  - The `java` job also compiles the E2E drivers: `compileE2eUndoJava` on every node, and `:26.2:compileE2eJava` against the released 0.1.0 and 0.2.0 jars. Those jars come from the GitHub releases through `gh`, pinned by sha256. The 0.1.0 hash matches tools/e2e/README.md; the 0.2.0 hash is GitHub's asset digest.
- **`build.gradle`**
  - `runProductionClientGameTest` is research §2 verbatim, except that Sodium in `productionGameTestMods` is conditional.
  - Change A: `localRuntime` Sodium only when `findProperty("sodium_version")` is non-empty.
  - Change B: `versionType` is `release` only for a plain `x.y` or `x.y.z` id, and `alpha` for everything else.
  - `-PmodrinthFile=<path>` makes Minotaur upload that exact file.
- **`stonecutter.gradle`**: `order 'runProductionClientGameTest'`. **`gradle.properties`**: `mod_version=0.3.0-dev`.
- **`release.yml`**
  - A first step fails unless the tag equals `v<mod_version>`.
  - The release step copies each node's jar and sources jar to `$RUNNER_TEMP/release-files` and attaches those copies.
  - One publish step loops over `versions/*/` and passes the node's copy as `-PmodrinthFile`. Each failure is an `::error::`, the loop carries on, and the step exits 1 if any node failed.
  - "Verify Modrinth files match" is unchanged and still runs after a failed publish (`!cancelled()`).
  - The old comment claimed that a job re-run would publish the missing node. That was false, so the comment now holds the recovery runbook below.

## Deviations from the research and PLAN

- **The matrix is generated, not static.** Research §2 argued for a static matrix; SPEC/AC2.5 wants it generated.
- **Sodium isn't required.**
  - Research §2 said three game-test classes use Sodium. No assertion needs it loaded.
  - Run 36172689580 (temporary commit 55beef5, reverted in a7a9727) built 26.3 without `sodium_version`: `java` ran 848/848 unit tests on 26.3, and both 26.3 game-test legs were green.
  - Their `latest.log` lists no `sodium` mod, and RigTune shows "Install Sodium (high)".
  - So no game test needed a skip (W0-M1).
- **No automatic retries.** This is W0-M2; the PLAN text is corrected.
- **The re-run path was dropped (W0-M3).**
  - An idempotent release step (download the assets when the release exists) was proposed, then dropped by the coordinator after the code review: nodes that had already published would still fail the loop.
  - Byte identity now comes from construction on the first run, since Modrinth gets the copy that was attached to the GitHub release.
  - Recovery is manual and never rebuilds (below).
- **The E2E driver compile is a step in `java`, not its own job.** It reuses the warm Gradle daemon and takes about 7 s. `compileE2eJava` compiled against the 0.2.0 jar with no change, so WS-H needs no parameterisation.

## Release recovery runbook (also in release.yml)

When a node's Modrinth publish fails, don't re-run the job. "Create GitHub release" fails because the release exists, and every step after it is skipped. Don't rebuild either.

From a checkout of the tag, in Git Bash, per failed node (`MODRINTH_TOKEN` comes from the user environment):

```sh
tag=v0.3.0 mc=26.3; ver=${tag#v}; jar="rigtune-$ver+mc$mc.jar"
gh release download "$tag" -p "$jar" -D recovery
digest=$(gh release view "$tag" --json assets -q ".assets[] | select(.name == \"$jar\") | .digest")
awk -v h="[$ver]" '/^## /{p = index($0, h) > 0; next} p' CHANGELOG.md > recovery/changelog.md
python tools/modrinth_project.py upload-version --file "recovery/$jar" \
  --version-number "$ver+mc$mc" --name "RigTune $ver (MC $mc)" --game-versions "$mc" \
  --version-type release --changelog-file recovery/changelog.md --sha256 "${digest#sha256:}"
```

Use `--version-type alpha` for a node whose id isn't a plain release.

What the tool does:
- It refuses a sha256 mismatch.
- It does nothing if the version_number already exists.
- `--dry-run` prints the payload without any network call.

Then check it the way the verify step does. `python tools/modrinth_project.py status` lists the version. In the authenticated version list (`GET /v2/project/rigtune/version`), the file with the GitHub asset's sha1 must have the asset's sha512.

Dry run of these exact commands against v0.2.0/26.2 (2026-09-26):
- The download and digest lookup worked.
- The changelog extraction gave the `[0.2.0]` section.
- `upload-version --dry-run` printed `0.2.0+mc26.2`, release, with fabric-api required.
- A wrong `--sha256` was refused.

## Reproducibility (W0-M3 note)

- **Local.** Two clean builds of 3acd192 (`clean assemble`, Windows) produced identical sha256 for all four jars.
- **CI.** Three CI builds of trees with identical jar inputs produced identical jars: runs 36166758669, 36168188639 and 36171445195 (26.2 `57a073b4…`, 26.3 `a2a4ba06…`).
- **Windows against Linux.** The main jars differ only in `META-INF/MANIFEST.MF`. Loom writes `Fabric-Loom-Client-Only-Entries` in filesystem order: Windows sorted, Linux not. All class and resource entries are byte-identical, and the sources jars are identical everywhere (they also equal 0.2.0's released sources jars, since no source changed yet).
- **Consequence.** A jar rebuilt on another OS doesn't match the GitHub asset, which is one more reason recovery must upload the GitHub asset itself.

## Evidence

- **Change A.**
  - 26.3 with `sodium_version` removed ran 848/848 unit tests locally.
  - `localRuntime` and `productionGameTestMods` then resolve without Sodium.
  - An empty `sodium_version=` resolves too.
  - CI run 36172689580 confirmed it.
- **Change B.**
  - `-PmodrinthDryRun` (no token in the environment) prints `0.3.0-dev+mc26.2` and `+mc26.3` as `release`.
  - The predicate was checked with temporary edits (reverted): `26.4-snapshot-1`, `26.3-pre-1`, `26.3-rc-1` and `26.4-experimental-1` give `alpha`; `26.3.1`, `26.4` and `27.1` give `release`.
  - actionlint 1.7.12 with shellcheck 0.11.0 is clean on all three workflows.
  - A local simulation of the tag, release and publish steps (bash -e, stubbed `gh` and `gradlew`, 26.2's publish failing) showed:
    - the tag guard rejects `v0.3.1` and `v0x3.0` against `mod_version=0.3.0`;
    - the release step attaches the staged copies;
    - the loop publishes those same paths, records 26.2's error, still publishes 26.3, and exits 1.
- **AC2.2.** Run 36167437975 (probe 7046bae, reverted in 15985c6) went red on all three legs with the probe's AssertionError, and still uploaded 35 screenshots and `latest.log` per leg.
- **AC2.5.**
  - `test_extra_node_gets_legs_without_other_changes` and `test_node_without_sodium_gets_its_legs` cover it.
  - A dry run with a temporary empty `versions/26.4-snapshot-1/` printed 5 legs, adding 26.4-snapshot-1 on OpenGL and on Vulkan.
- **Game-test legs.**
  - Every run shows the intended backend on every leg: `latest.log` has "Using graphics backend OpenGL … Mesa 25.2.8" or "Vulkan … llvmpipe".
  - Green job durations over runs 36166758669, 36168188639, 36171445195 and 36172689580 are cold-cache (see Risks): 26.2 OpenGL 4m36s–5m04s, 26.3 OpenGL 4m18s–4m43s, 26.3 Vulkan 4m19s–4m39s.
- **Screenshots** (run 36171445195, 52 per leg).
  - A pixel check found no black or flat frame. The darkest is 26.2 `0039_ui-undo-last-from-button` (mean 11.7, 1091 colours), a dark world behind the "Nothing to undo" dialog.
  - I viewed 26.2 `0005` (main screen: llvmpipe, OpenGL, Tier 0/5, Online) and `0039`, 26.3 OpenGL `0014_real-world`, and 26.3 Vulkan `0021_bench-world-after` and `0045_ui-settings`. All rendered correctly.

## Risks

- **The Gradle cache is cold on every branch.** setup-gradle writes its cache only from the default branch (`main`), and `main` has no setup-gradle job yet. Every leg on a feature branch is a cold run (about 4.5–5 min). This improves once v0.3.0 reaches `main`.
- **The Vulkan leg depends on OpenGL failing on 26.3** (research §3.2). If Mesa, Xvfb or SDL ever expose an sRGB GLX visual, the backend check fails loudly. The fix is `preferredGraphicsBackend:"vulkan"` in `options.txt`.
- **26.4 makes Vulkan the default.** Its OpenGL legs would rely on Vulkan failing (no ICD is installed on those legs) and on the fallback to OpenGL. UNVERIFIED until a 26.4 node exists; the backend check would show it.
- **A new node without Sodium** gets green legs today (probe above). A future game test that needs Sodium loaded must skip when it isn't.
- **The release workflow has never run for real.** It was checked by lint, a local simulation and Minotaur's dry run only (UNVERIFIED until the v0.3.0 release, AC1.5).
- **Minotaur's `modrinth` task depends on `assemble`,** so the publish step can touch `versions/<mc>/build/libs`. The uploads use the staged copies, and Verify looks up asset names there, which are identical.
- **`cancel-in-progress` cancels a run when a newer push to the same ref arrives.** A deliberate probe must finish before its revert is pushed.
- **The E2E compile pins `:26.2` and the two released jars' hashes.** The E2E harness runs on 26.2.
- **Moving and outside dependencies.** Loom 1.17-SNAPSHOT, Maven outages (Fabric, Modrinth, Terraformers), runner Mesa updates, and the live Modrinth API used by the game tests (research §3.7, §4).
