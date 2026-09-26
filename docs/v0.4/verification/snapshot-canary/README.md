# Snapshot canary: verification (WS-0, 2026-09-26)

Workflow: `.github/workflows/snapshot-canary.yml`. A new workflow can't be dispatched before it is on
the default branch, so the self-test used a temporary `push: branches: [feat/v04-foundation]`
trigger (commit `4e5b7d3`, removed again before the branch was handed over). Every run below was a
push run on `feat/v04-foundation`, auto-resolving `latest.snapshot` from piston-meta:
`26.4-snapshot-1` (`latest.release` 26.3).

| Path | Run | Commit | Result |
|---|---|---|---|
| (a) green | https://github.com/chaotix345/rigtune/actions/runs/36212729614 | `4e5b7d3` (both 26.4 patches in) | resolve `26.4-snapshot-1`, `add_mc_version.py` added the node after 26.3, `./gradlew :26.4-snapshot-1:build` BUILD SUCCESSFUL (compileGametestJava + test ran), no open issue so nothing to close |
| (b) failure, first | https://github.com/chaotix345/rigtune/actions/runs/36212871708/attempts/1 | `cc433b6` (temporary commit removing the `getFirstFreeHeight` block) | build failed at `:26.4-snapshot-1:compileClientJava`, `BenchmarkWorld.java:323: cannot find symbol ... getBaseHeight(int,int,Types,ServerLevel,RandomState)`; reports artifact `snapshot-canary-reports-26.4-snapshot-1` uploaded (43767 bytes); label `snapshot-canary` created; issue #9 opened |
| (b) failure, second | https://github.com/chaotix345/rigtune/actions/runs/36212871708/attempts/2 | `cc433b6` (re-run) | failed the same way; commented on #9 ("Still failing on `26.4-snapshot-1` ..."), no second issue (1 open issue with the label) |
| (c) green again | https://github.com/chaotix345/rigtune/actions/runs/36213755284 | `984546d` (`cc433b6` reverted in `f03a97d`, plus the review fixes: `>=26.4-alpha` predicates, add-step error classification, read-only Gradle cache) | build green; closed #9 as completed with "Green again on `26.4-snapshot-1` ..." |

Issue: https://github.com/chaotix345/rigtune/issues/9 ("Snapshot canary: RigTune fails to build
against the newest Minecraft snapshot", label `snapshot-canary`), marked as the canary self-test,
closed automatically.

Run (a) and the failing runs used the first version of the workflow; run (c) used the final one.
The open/comment steps didn't change between them; the add step (error classification) did. Its
new branches were run locally against a stub `python` and a stub `gh` (first on `PATH`, checked):

- `add_mc_version: unexpected error: ...`, `HTTP 503 from https://...`, `https://...: timed out`,
  `unreadable JSON from ...`, no output: `::error`, exit 1 (the run fails, no issue).
- `... needs Java 26 ...`, `'26w14a' isn't a Minecraft version id this tool understands ...`:
  `::warning`, `ready=false`, exit 0.
- `no Fabric API build for ... yet`, `26.3 is already a node in settings.gradle`: `::notice`,
  `ready=false`, exit 0.
- The real `add_mc_version.py` in a scratch copy of the repo: `26.3` (already a node) and
  `26.99-snapshot-1` (not in the manifest) skip with a notice; `26.4-snapshot-1` writes the node,
  `ready=true prev=26.3`.
- Issue step with a stub `gh`: no issue and no label -> label created, issue opened with the body
  below; label present -> no label call; open issue -> comment only; close step -> close with a
  comment, or nothing when no issue is open.

Not run in CI: the skip paths (`latest.snapshot == latest.release`, an `add_mc_version.py`
refusal) and `workflow_dispatch` with `-f mc=`, since dispatch needs the file on `main` and a skip
needs a snapshot that isn't ready. Once merged: `gh workflow run snapshot-canary.yml -f mc=26.3`
should end green with the notice "add_mc_version.py refused 26.3: 26.3 is already a node in
settings.gradle".

Two stray issues, #7 and #8, were created and closed by a local dry run of the issue steps that
reached the real `gh` (the stub wasn't first on `PATH`). They are retitled "[accidental test,
ignore] ...", unlabelled and commented; the label they created was deleted, so run (b) created it
again.

## The 26.4 patches, locally (Windows, JDK 25.0.4.1)

- Red: throwaway `python tools/add_mc_version.py 26.4-snapshot-1 --prerelease-ok`, then
  `./gradlew :26.4-snapshot-1:compileClientJava` failed on `getBaseHeight` (as in the research).
- Green: with both blocks, `./gradlew :26.4-snapshot-1:build :26.4-snapshot-1:compileGametestJava`
  BUILD SUCCESSFUL, 1126 tests in 104 classes, 0 failures, 1 skipped (`LauncherDetectorTest`).
  The throwaway node (settings.gradle line, `versions/26.4-snapshot-1/`) was removed; never
  committed.
- `./gradlew build` (26.2 + 26.3, active 26.2): 1126 tests each, 0 failures, 1 skipped;
  `./gradlew "Reset active project"` leaves no diff.
- Predicate: `>=26.4-alpha` rather than `>=26.4-snapshot-1`. Stonecutter 0.9.8's bundled
  `dev.kikugie.semver` (called directly from a scratch Java program) gives `>=26.4-snapshot-1`
  false for `26.4-pre-1` and `26.4-rc-1`, while `>=26.4-alpha` is true for 26.4-snapshot-1/-2/-10,
  -pre-1, -rc-1, 26.4, 26.4.1 and false for 26.2, 26.3, 26.3.1, 26.3.1-rc-1. Run (c) is the CI
  build of the snapshot node with `>=26.4-alpha`.
