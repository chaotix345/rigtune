# Minecraft version state for v0.4.0 (P0.1 refresh + snapshot-canary CI design)

Research date: 2026-09-26, on `feat/v0.4.0` @ `0ad889c`. Every version/API/tool claim below was checked live on that date (Mojang manifest, Fabric meta, Fabric API maven, Modrinth API) or against real jars in the Gradle cache with `javap`, unless marked UNVERIFIED. This is a refresh of `docs/research/v0.3/mc-versions.md` (same research question, same day): §1-2 confirm nothing has moved since that doc was written; §3 (this task's trial) is new and found something that doc's trial didn't; §4 (the CI workflow design) is new, requested for v0.4.0.

## 1. Headline

- **P0.1: no 26.4 node is needed now.** Confirmed live: latest Mojang release is still `26.3` (2026-09-15), latest snapshot is still `26.4-snapshot-1` (2026-09-22) -- identical to `docs/research/v0.3/mc-versions.md` §2, checked again independently today. No new snapshot, no 26.4 release, no Fabric API stable build, still zero Sodium/Iris/Distant Horizons builds for `26.4-snapshot-1`. Nothing to add.
- **But the trial found a real regression against the snapshot that v0.3's trial did not.** Two RigTune features added *after* the v0.3 apidiff trial ran (both landed today, 2026-09-26, ~04:25-04:40, as part of the benchmark-world work: `feat(benchmark): camera at the terrain floor...` and `test(benchmark): 3f chunk game test...`) call two Minecraft methods that were renamed on `26.4-snapshot-1`: `ChunkGenerator.getBaseHeight(...)` -> `getFirstFreeHeight(...)`, and `ServerLevel.getUncachedNoiseBiome(...)` -> `getUncachedBiome(...)` (the second was already flagged as a changed class by v0.3's apidiff, but nothing called it yet at the time). Building `26.4-snapshot-1` as a node today **fails to compile** without a two-line `//? if >=26.4-snapshot-1 {` fix (§3). This is exactly the kind of drift a one-off research trial can't catch after the fact -- which is the motivation for §4's automated weekly canary.
- **The `mc_apidiff.py` tool (built in v0.3) reproduces both breaks automatically**, exit code 1, with no false positives: `python tools/mc_apidiff.py 26.3 26.4-snapshot-1` lists exactly these 2 `MISSING` methods and nothing else new (§3.3).
- **The `vulkan-backend` rule fix recommended in v0.3 §5.4 has already landed**: `rules/source/knowledge.json:647` has `"mcVersionRange": "<26.4-"`. No outstanding action there.
- **No 26.4 release date has been announced.** Minecraft LIVE was today (2026-09-26); no 26.4 timing was in any source checked (§5). Press estimates ~December 2026 from cadence, same as v0.3 §6.

## 2. Live state (2026-09-26), re-verified independently of v0.3's doc

| What | Value | Source (checked today) |
|---|---|---|
| Mojang `latest.release` / `latest.snapshot` | `26.3` / `26.4-snapshot-1` | `GET piston-meta.mojang.com/mc/game/version_manifest_v2.json` |
| Fabric meta game versions (top) | `26.4-snapshot-1` (stable:false), `26.3` (stable:true) | `GET meta.fabricmc.net/v2/versions/game` |
| Fabric Loader for `26.4-snapshot-1` | 253 loader entries listed; newest stable `0.19.5` (matches the repo's `gradle.properties` `loader_version=0.19.5`) | `GET meta.fabricmc.net/v2/versions/loader/26.4-snapshot-1` |
| Fabric API | newest on the Fabric maven: `0.161.1+26.4` (`lastUpdated` 20260922201439); `0.161.0+26.3`, `0.161.0+26.2` are the newest release-branch builds | `GET maven.fabricmc.net/.../fabric-api/maven-metadata.xml` |
| Mod Menu | `22.0.0-alpha.1` for `26.4-snapshot-1` (2026-09-24), also present on `maven.terraformersmc.com` | Modrinth `/project/modmenu/version?game_versions=["26.4-snapshot-1"]`, Terraformers `maven-metadata.xml` |
| Sodium | **0** versions for `26.4-snapshot-1` | Modrinth `/project/sodium/version?game_versions=["26.4-snapshot-1"]&loaders=["fabric"]` -> `[]` |
| Iris | **0** versions for `26.4-snapshot-1` | same query, project `iris` -> `[]` |
| Distant Horizons API | **0** versions for `26.4-snapshot-1` | same query, project `distanthorizonsapi` -> `[]` |
| Fabric blog | no "Fabric for Minecraft 26.4" post; newest is 26.3 | `fabricmc.net/feed.xml` |
| Vulkan default (26.4-snapshot-1) | Confirmed: the Graphics API setting "Default" now behaves like "Prefer Vulkan"; Minecraft also stops auto-reverting the graphics API after a startup crash | [minecraft.net, "Minecraft 26.4 Snapshot 1"](https://www.minecraft.net/en-us/article/minecraft-26-4-snapshot-1) (via search excerpt), corroborated by my own `javap` diff of `Options` (the `"options.graphicsApi.tooltip.vulkan"` string is gone on 26.4-snapshot-1) and by v0.3's disassembly of `PreferredGraphicsApi.getBackendsToTry()` |
| 26.4 release timing | Not announced. Minecraft LIVE (2026-09-26) is "where we'll get the lowdown" on 26.4's theme -- nothing about 26.4 timing surfaced in the sources fetched today. Press estimate: ~December 2026 | [PCGamesN](https://www.pcgamesn.com/minecraft/next-update-release-date), [Sportskeeda](https://www.sportskeeda.com/minecraft/minecraft-live-september-2026-date-time-announced) |

**Conclusion for P0.1, unchanged from v0.3: do not add a 26.4 node now.** `26.4-snapshot-1` is still the only build newer than the shipped `26.3`, it's still a snapshot (not a release, needs `--prerelease-ok`), and Sodium/Iris/DH still have no build for it. Re-run this check (or better, let §4's canary do it) before the next release.

## 3. Trial: adding `26.4-snapshot-1` as a node today (WS: what CI would actually do)

### 3.1 Setup

Worktree `C:/Dev/Worktrees/rigtune-r-snap`, branch `research/snapshot-canary`, from `feat/v0.4.0` @ `0ad889c` (clean). `JAVA_HOME=C:/Dev/Tools/jdk/jdk-25.0.4.1+1`. Commands run, in order:

```
git worktree add C:/Dev/Worktrees/rigtune-r-snap -b research/snapshot-canary
cd C:/Dev/Worktrees/rigtune-r-snap
python tools/add_mc_version.py 26.4-snapshot-1 --prerelease-ok --dry-run   # sanity check first
python tools/add_mc_version.py 26.4-snapshot-1 --prerelease-ok            # writes the node
./gradlew :26.4-snapshot-1:build --stacktrace
```

The dry run printed exactly the same plan as the live checks in §2 predict (Fabric API `0.161.1+26.4` beta, Mod Menu `22.0.0-alpha.1` alpha, no Sodium, Iris kept from 26.3's `bAdKrpw8`, `minecraft_dependency=~26.4-`) and matches `docs/v0.3/verification/mc-tooling/add_mc_version-26.4-snapshot-1-dry-run.txt` byte-for-byte apart from the timestamp. The tool wrote `settings.gradle` and `versions/26.4-snapshot-1/gradle.properties` (2 files, nothing else, uncommitted).

### 3.2 Build result: FAILED, then FAILED again, then PASSED after a 2-line fix

| Attempt | Command | Result | Time |
|---|---|---|---|
| 1 (unpatched HEAD) | `./gradlew :26.4-snapshot-1:build` | **FAILED** at `compileClientJava`: `BenchmarkWorld.java:323: cannot find symbol: method getBaseHeight(int,int,Types,ServerLevel,RandomState) location: class ChunkGenerator` | 17.6 s |
| 2 (after patching `BenchmarkWorld.java` only) | same | **FAILED** at `compileGametestJava`: `BenchmarkGameTest.java:247: cannot find symbol: method getUncachedNoiseBiome(int,int,int) location: variable level of type ServerLevel` | 6.0 s (mostly UP-TO-DATE) |
| 3 (after patching both files) | same | **BUILD SUCCESSFUL** -- 20 tasks (9 executed, 11 UP-TO-DATE) | 1 m 30 s |

Test result (attempt 3): **1126 tests across 104 test classes, 0 failures, 0 errors, 1 skipped** (`LauncherDetectorTest` -- an OS-detection test, unrelated to the MC version). Built `rigtune-0.3.0+mc26.4-snapshot-1.jar` and its sources jar.

Both compile errors are genuine API breaks introduced on `26.4-snapshot-1`, confirmed by `javap` against the cached jars (`~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-common-deobf/{26.3,26.4-snapshot-1}/`):

- `net.minecraft.world.level.chunk.ChunkGenerator.getBaseHeight(int, int, Heightmap$Types, LevelHeightAccessor, RandomState)` exists on 26.3, **does not exist** on 26.4-snapshot-1. The class instead has `getFirstFreeHeight(...)` and a new `getFirstOccupiedHeight(...)`, same parameter shape -- a rename, not a removal of the concept.
- `net.minecraft.server.level.ServerLevel.getUncachedNoiseBiome(int, int, int)` exists on 26.3, **does not exist** on 26.4-snapshot-1 (also gone from `LevelReader`, which declares it `abstract`). Renamed to `getUncachedBiome(int, int, int)`; the backing field's type also changed from `BiomeResolver` to `BiomeManager`. This exact rename was already listed as a `DIFF` class in v0.3's apidiff (`docs/research/v0.3/mc-versions.md` §5.3, "ClientLevel, ServerLevel ... getUncachedNoiseBiome -> getUncachedBiome") -- it just had no caller in RigTune's code at the time.

Both call sites are new: `git log -L` shows `BenchmarkWorld.java`'s `terrainFloor` was added at `f4d2582` ("camera at the terrain floor + 16, independent of trees (AC8.6)") and `BenchmarkGameTest.java`'s caller at `98f0674` ("3f chunk game test..."), both today, both after the v0.3 apidiff trial. **This is not a flaw in the v0.3 research -- it's proof that a point-in-time trial goes stale the moment new code lands, which is exactly the gap §4's weekly canary closes.**

The fix applied (uncommitted, in the throwaway worktree only, left in place for inspection):

```java
// BenchmarkWorld.java
public static int terrainFloor(ServerLevel level, int x, int z) {
    //? if >=26.4-snapshot-1 {
    /*return level.getChunkSource().getGenerator().getFirstFreeHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, level.getChunkSource().randomState());
    *///?} else
    return level.getChunkSource().getGenerator().getBaseHeight(x, z, Heightmap.Types.OCEAN_FLOOR_WG, level, level.getChunkSource().randomState());
}

// BenchmarkGameTest.java
//? if >=26.4-snapshot-1 {
/*samples.add(new SceneVariety.Sample(o[0], o[1], floor, level.getUncachedBiome(x >> 2, floor >> 2, z >> 2).getRegisteredName()));
*///?} else
samples.add(new SceneVariety.Sample(o[0], o[1], floor, level.getUncachedNoiseBiome(x >> 2, floor >> 2, z >> 2).getRegisteredName()));
```

This is a real fix a human/foundation team should carry over **when 26.4 actually ships** (not now -- it's a pre-release node, don't ship it, per `tools/MC_VERSIONS.md` "Version ranges"). It is not committed anywhere; it exists only as an uncommitted diff in the throwaway worktree.

### 3.3 `mc_apidiff.py` confirms both breaks automatically (Q5)

After compiling `26.3`'s classes (`./gradlew :26.3:classes :26.3:clientClasses :26.3:gametestClasses`, 15.8 s) and running against the **patched** HEAD:

```
python tools/mc_apidiff.py 26.3 26.4-snapshot-1 --sets main,client,gametest --out <dir>
```

Result: **exit code 1**, `RESULT: 2 breaking change(s), 0 gap(s)`:
```
MISSING method net/minecraft/server/level/ServerLevel.getUncachedNoiseBiome(III)Lnet/minecraft/core/Holder;: not found on the new version
MISSING method net/minecraft/world/level/chunk/ChunkGenerator.getBaseHeight(IILnet/minecraft/world/level/levelgen/Heightmap$Types;Lnet/minecraft/world/level/LevelHeightAccessor;Lnet/minecraft/world/level/levelgen/RandomState;)I: not found on the new version
```
This is checking whether **26.3's compiled bytecode** (which, correctly, still calls the old method names on the `else` branch) still resolves against the 26.4-snapshot-1 classpath -- it does not, which is exactly the tool doing its job: it proves the `//? if` branch is necessary, not optional. The 26.4-snapshot-1 node's *own* build (the one actually shipped for that version) already uses the new names and links fine, as attempt 3 above showed. Exactly 2 breaking changes, matching the 2 compile failures found by hand -- no other missing/changed references, no other overrides broken, 550/552 member references resolve unchanged.

Referenced-class diff: 181 checked (up from v0.3's 144, reflecting more code), 165 `SAME`, 16 `DIFF` (v0.3 had 13 `DIFF` of 144): the same set (`RenderSystem`, `GpuDevice`, `Options`, `GuiGraphicsExtractor`, `ClientLevel`, `LocalPlayer`, `LevelRenderer`, `MinecraftServer`, `ServerLevel`, `Difficulty`, `GameType`, `ChunkGenerator`, `Heightmap`, `Heightmap$Types`) plus two new ones now referenced by the newer benchmark code (`ServerPlayer`, `RandomState`). None of the *other* 14 diffs affect RigTune beyond the 2 already listed -- consistent with v0.3 §5.3's per-class review.

Full output: `report.json`/`summary.txt`/`diff-*.txt` are in the throwaway worktree's run only (not copied into the repo, per the read-only/single-output-file constraint on this task); the key findings are captured above and in §3.2.

### 3.4 Cleanup

Per instructions, the worktree is **left in place** at `C:/Dev/Worktrees/rigtune-r-snap` (branch `research/snapshot-canary`, uncommitted changes: the 2-file node + the 2-file patch above) for the coordinator to inspect or remove. Nothing was committed or pushed.

## 4. Weekly snapshot-canary CI workflow (design)

### 4.1 Goal and non-goals

Catch drift like §3 automatically, every week, without ever gating a PR or `main`: build + unit-test the newest Minecraft snapshot as an ephemeral Stonecutter node that is **never committed**. On failure, open (or update) exactly one tracking issue. On a skip day (no snapshot ahead of the release, or the ecosystem isn't ready yet), do nothing but log a notice -- never fail the job for that.

**Why it's safe for the other automation that reads `versions/*/`:** this workflow only ever runs `tools/add_mc_version.py` inside its own ephemeral runner checkout and never `git commit`/`git push`s the result. `versions/*/` on `main` (and any PR branch) is untouched, so `tools/gametest_matrix.py` (read by `build.yml`'s `gametest-matrix` job), `release.yml`'s per-node publish loop, and `update-rules.yml`'s target list all keep working from the real, committed node set exactly as before. This workflow triggers only on `schedule`/`workflow_dispatch`, never on `push`/`pull_request`, so it structurally cannot block anything.

**GitHub's rule, noted explicitly in the file:** a `schedule` trigger only fires from the workflow file **as committed on the repository's default branch** (`main`, confirmed via `gh repo view --json defaultBranchRef` -> `main`). A copy of this file on a feature branch will never fire on its own schedule; `workflow_dispatch` is how you test it (§4.3).

### 4.2 Draft: `.github/workflows/snapshot-canary.yml`

Reuses the exact action versions and idioms already in this repo (`actions/checkout@v7`, `gradle/actions/wrapper-validation@v6`, `actions/setup-java@v6` with `distribution: microsoft`/`java-version: '25'`, `gradle/actions/setup-gradle@v6`, `actions/setup-python@v7` with `python-version: '3.11'`, `actions/upload-artifact@v7`; the `gh issue list --json ... --jq ...` dedupe idiom mirrors `update-rules.yml`'s `gh pr list --json number --jq` pattern).

```yaml
name: snapshot-canary

# Weekly check that RigTune still builds and unit-tests cleanly against the newest Minecraft
# snapshot, so a porting problem (docs/research/v0.4/mc-versions.md, section 3) surfaces before the
# version ships, not on release day. Never blocks PRs or main: it only runs on a schedule or by
# hand (never on push/pull_request), and it never commits or pushes anything -- the node it adds
# with add_mc_version.py exists only in this run's checkout, which GitHub discards with the runner.
# versions/*/ on every real branch is untouched, so tools/gametest_matrix.py (build.yml), the
# release.yml per-node publish loop and update-rules.yml's targets are all unaffected.
#
# GitHub only fires `schedule` from the copy of this file on the default branch (main). To test a
# change on a feature branch, push it and run it by hand:
#   gh workflow run snapshot-canary.yml --ref <branch>                          # auto-resolve
#   gh workflow run snapshot-canary.yml --ref <branch> -f mc=26.4-snapshot-1    # pin one to test

on:
  schedule:
    - cron: '0 5 * * 3' # Wednesday 05:00 UTC (offset from update-rules.yml's Monday run)
  workflow_dispatch:
    inputs:
      mc:
        description: 'Minecraft snapshot id to test (blank = auto-resolve latest.snapshot)'
        required: false
        default: ''

permissions:
  contents: read
  issues: write

# Only one canary run at a time; don't cancel a scheduled run just because someone dispatched one.
concurrency:
  group: snapshot-canary
  cancel-in-progress: false

env:
  ISSUE_TITLE: 'Snapshot canary: Minecraft snapshot build is failing'

jobs:
  canary:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7

      - uses: gradle/actions/wrapper-validation@v6

      # No Fabric API / Sodium / etc. lookup here: add_mc_version.py already does all of that.
      - name: Resolve the snapshot to test
        id: resolve
        run: |
          set -eu
          if [ -n "${{ inputs.mc }}" ]; then
            mc="${{ inputs.mc }}"
          else
            manifest=$(curl -sf -H "User-Agent: chaotix345/rigtune-snapshot-canary/1.0 (github.com/chaotix345/rigtune)" \
              https://piston-meta.mojang.com/mc/game/version_manifest_v2.json)
            mc=$(echo "$manifest" | jq -r '.latest.snapshot')
            release=$(echo "$manifest" | jq -r '.latest.release')
            if [ "$mc" = "$release" ]; then
              echo "::notice::latest.snapshot ($mc) equals latest.release: nothing newer than the current release to test."
              echo "skip=true" >> "$GITHUB_OUTPUT"
              exit 0
            fi
          fi
          echo "mc=$mc" >> "$GITHUB_OUTPUT"
          echo "skip=false" >> "$GITHUB_OUTPUT"

      - uses: actions/setup-python@v7
        if: steps.resolve.outputs.skip != 'true'
        with:
          python-version: '3.11'

      - uses: actions/setup-java@v6
        if: steps.resolve.outputs.skip != 'true'
        with:
          distribution: microsoft
          java-version: '25'

      - uses: gradle/actions/setup-gradle@v6
        if: steps.resolve.outputs.skip != 'true'

      # Writes settings.gradle + versions/<mc>/gradle.properties in THIS checkout only; nothing is
      # ever committed or pushed. add_mc_version.py refuses (exit 1, nothing written) when there's no
      # Fabric API build yet, the Java version doesn't match, or any other precondition fails -- all
      # of that is a skip here, not a failure, since it's the expected state right after a snapshot drops.
      - name: Add the snapshot as a Stonecutter node (this run only)
        id: add
        if: steps.resolve.outputs.skip != 'true'
        run: |
          set +e
          out=$(python tools/add_mc_version.py "${{ steps.resolve.outputs.mc }}" --prerelease-ok 2>&1)
          status=$?
          echo "$out"
          if [ $status -ne 0 ]; then
            echo "::notice::${{ steps.resolve.outputs.mc }} isn't ready to test yet (see the log above for why: no Fabric API build, Java mismatch, etc). Skipping the build."
            echo "ready=false" >> "$GITHUB_OUTPUT"
          else
            echo "ready=true" >> "$GITHUB_OUTPUT"
          fi

      - name: Build and unit-test the snapshot node
        id: build
        if: steps.add.outputs.ready == 'true'
        run: ./gradlew ":${{ steps.resolve.outputs.mc }}:build" --stacktrace

      - name: Upload test reports
        if: always() && steps.add.outputs.ready == 'true'
        uses: actions/upload-artifact@v7
        with:
          name: snapshot-canary-${{ steps.resolve.outputs.mc }}
          path: |
            versions/*/build/reports/tests/**
            versions/*/build/test-results/**
          if-no-files-found: ignore

      # Dedupe on the fixed title: one issue tracks canary health regardless of which snapshot id is
      # current, with a comment per failed run (mirrors update-rules.yml's gh pr list/create dedupe).
      - name: Open or update the tracking issue
        if: always() && steps.build.outcome == 'failure'
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          set -eu
          run_url="$GITHUB_SERVER_URL/$GITHUB_REPOSITORY/actions/runs/$GITHUB_RUN_ID"
          body="\`./gradlew :${{ steps.resolve.outputs.mc }}:build\` failed on ${{ steps.resolve.outputs.mc }} ($(date -u +%Y-%m-%d)). $run_url"
          existing=$(gh issue list --repo "$GITHUB_REPOSITORY" --state open --json number,title \
            --jq ".[] | select(.title == \"$ISSUE_TITLE\") | .number" | head -1)
          if [ -n "$existing" ]; then
            gh issue comment "$existing" --repo "$GITHUB_REPOSITORY" --body "$body"
          else
            gh issue create --repo "$GITHUB_REPOSITORY" --title "$ISSUE_TITLE" --body "$body" --label bug
          fi

      - name: Close the tracking issue once the canary is green again
        if: always() && steps.build.outcome == 'success'
        env:
          GH_TOKEN: ${{ github.token }}
        run: |
          set -eu
          existing=$(gh issue list --repo "$GITHUB_REPOSITORY" --state open --json number,title \
            --jq ".[] | select(.title == \"$ISSUE_TITLE\") | .number" | head -1)
          if [ -n "$existing" ]; then
            gh issue comment "$existing" --repo "$GITHUB_REPOSITORY" --body "Green again on ${{ steps.resolve.outputs.mc }}: closing."
            gh issue close "$existing" --repo "$GITHUB_REPOSITORY"
          fi
```

### 4.3 How to test it

1. Push this file on a feature branch (it does not need to be on `main` to be dispatched, only to fire on its own `schedule`).
2. `gh workflow run snapshot-canary.yml --ref <branch>` to exercise the auto-resolve path (today, that would resolve `26.4-snapshot-1` and reproduce §3.2's failure -- a good first real-world test, since HEAD is currently broken against it).
3. `gh workflow run snapshot-canary.yml --ref <branch> -f mc=26.3` to force the "already a real node" path and confirm `add_mc_version.py` refuses cleanly (`26.3 is already a node in settings.gradle`) and the job skips without failing.
4. `gh workflow run snapshot-canary.yml --ref <branch> -f mc=26.99-snapshot-1` (a made-up id) to confirm the "not in the Mojang manifest" refusal also skips cleanly.
5. Watch the run with `gh run watch`; check that a failing run opens exactly one issue titled `Snapshot canary: Minecraft snapshot build is failing`, and that dispatching it again while that issue is open adds a comment instead of a second issue.

**Open items for the foundation agent implementing this:** (a) the `gh issue list --jq` title match is exact-string, case-sensitive -- fine as long as `ISSUE_TITLE` never changes; (b) consider also uploading the built jar on success, low priority; (c) the cron day/time (`0 5 * * 3`) is an arbitrary non-clashing slot, not evidence-based -- change freely.

## 5. Porting readiness summary for when 26.4 ships

- **Code fix needed (found in this trial, §3.2-3.3):** the two `//? if >=26.4-snapshot-1 {` blocks above, for `BenchmarkWorld.terrainFloor` and `BenchmarkGameTest`'s biome lookup. Small, mechanical, already drafted.
- **Already done (from v0.3, verified still in place):** `vulkan-backend` rule scoped to `<26.4-` (`rules/source/knowledge.json:647`).
- **Still open (from v0.3 §5.4, unchanged today):**
  1. Sodium/Iris have no 26.4 build yet -- can't set `sodium_version` until they do (build.gradle's optional-Sodium change already handles this gracefully, confirmed working in this trial: the node built and tested with Sodium omitted).
  2. The snapshot-vs-normalized version string issue (`FabricLoader.getRawGameVersion()` vs `getFriendlyString()`) only matters if a snapshot/pre-release jar is ever shipped, which policy says not to do.
  3. Vulkan-as-default runtime behaviour (does Sodium's eventual 26.4 port work on Vulkan?) is still UNVERIFIED -- no client was launched, per this task's constraints and v0.3's.
- **Vulkan-default, cited:** minecraft.net's own "Minecraft 26.4 Snapshot 1" article states the Graphics API "Default" option now behaves like "Prefer Vulkan", and Minecraft no longer auto-reverts the graphics API after a startup crash (per search excerpt of that article; direct fetch timed out today, so this is corroborated rather than directly quoted -- but it matches both v0.3's independent disassembly of `PreferredGraphicsApi.getBackendsToTry()` and this trial's own `javap` diff showing `Options` lost the string `"options.graphicsApi.tooltip.vulkan"` on 26.4-snapshot-1). No other Vulkan-relevant Mojang statement was found for 26.4.
- **26.4 timing:** still not announced anywhere checked today, including Minecraft LIVE (2026-09-26) coverage. Treat v0.3 §6's ~December 2026 cadence estimate as the working assumption; nothing here changes it.

## 6. Risks and UNVERIFIED

- The minecraft.net article fetch timed out; the Vulkan-default claim rests on search-result excerpts (PCQuest, daily.dev) plus this task's own bytecode verification, not a direct primary-source quote. Re-fetch if a verbatim citation is needed.
- Minecraft LIVE's other announcements (themes, features) are out of scope for this doc and unverified either way.
- Only `main,client,gametest` source sets were checked by `mc_apidiff.py` in this trial (no `test`, `e2e`, `e2eUndo` -- `e2e`/`e2eUndo` need the released `v0.1.0`/`v0.2.0` jars and weren't fetched; `test` wasn't compiled for `26.3` in this worktree). Given the `test` source set only exercises the two source sets already checked, this is a low-risk gap, but not zero.
- The draft workflow (§4.2) has not been merged or run in real CI; §4.3 lists how the foundation agent should validate it before relying on it.
- As with v0.3: runtime-only behaviour on `26.4-snapshot-1` (SDL input, the Vulkan default, benchmark-world generation) is still untested -- no client was launched, per this task's rules.
