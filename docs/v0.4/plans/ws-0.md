# WS-0 plan: snapshot canary, snapshot patches, version bump

Branch `feat/v04-foundation`, worktree `C:/Dev/Worktrees/rigtune-found4`. Scope: PLAN.md "WS-0"; research: docs/research/v0.4/mc-versions.md sections 3-5.

## Task 1: the two 26.4-snapshot-1 API patches
Files: `src/client/java/io/github/chaotix345/rigtune/client/benchmark/BenchmarkWorld.java` (terrainFloor), `src/gametest/java/io/github/chaotix345/rigtune/gametest/BenchmarkGameTest.java` (worldCheck biome lookup).
- [x] Red: throwaway `python tools/add_mc_version.py 26.4-snapshot-1 --prerelease-ok`, `./gradlew :26.4-snapshot-1:build` fails at compileClientJava (getBaseHeight), then compileGametestJava (getUncachedNoiseBiome).
- [x] Green: `//? if >=26.4-snapshot-1 {` blocks (getFirstFreeHeight, getUncachedBiome); the snapshot node builds and unit-tests, and its gametest source set compiles.
- [x] Revert the throwaway node (restore settings.gradle, delete versions/26.4-snapshot-1); `./gradlew build` green for 26.2 and 26.3 with active version 26.2. Commit.

## Task 2: `.github/workflows/snapshot-canary.yml`
- [x] Weekly `schedule` + `workflow_dispatch` (input `mc`); resolve latest.snapshot from piston-meta; notice + success skip when latest.snapshot == latest.release or add_mc_version.py refuses; node added only in the runner checkout; `./gradlew :<mc>:build`.
- [x] Failure: one issue with a fixed title and label (label created if missing), a comment on later failures, auto-close with a comment when green. Build reports uploaded on failure. `permissions: contents: read, issues: write`; action versions as in build.yml; LF.
- [x] Lint locally (YAML parse, shell syntax via `bash -n` on extracted steps). Commit.

## Task 3: prove it on the branch
- [x] Temporary `push: branches: [feat/v04-foundation]` trigger.
- [x] (a) green run on the current snapshot; (b) failure run opens the issue (throwaway commit reverting one patch), second failure comments; (c) next green run closes it.
- [x] Record run and issue URLs in `docs/v0.4/verification/snapshot-canary/README.md`; remove the temporary trigger and throwaway commits' effects. Commit.

## Task 4: `gradle.properties` `mod_version=0.4.0-dev`. Commit.

## Task 5: `tools/MC_VERSIONS.md` "Snapshot canary" section. Commit.

## Task 6: finish
- [x] code-reviewer subagent on the diff; fix high/medium findings.
- [x] Merge origin/feat/v0.4.0, `./gradlew build`, push, build.yml green on every job; `docs/v0.4/design/ws-0.md`.
