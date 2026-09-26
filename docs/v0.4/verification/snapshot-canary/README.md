# Snapshot canary: verification (WS-0, 2026-09-26)

Workflow: `.github/workflows/snapshot-canary.yml` (SPEC item 1, AC1.2-AC1.4). A new workflow can't be
dispatched until it is on the default branch, so the self-test used a temporary
`push: branches: [feat/v04-foundation]` trigger instead of `workflow_dispatch` (commits `4e5b7d3`,
removed in `3425a65`; again in `726f82b`/`c46a841` for the forced ids, removed in the commit that
adds this line). Every run below is a push run on `feat/v04-foundation`. Auto-resolve picked
`latest.snapshot` from piston-meta: `26.4-snapshot-1` (`latest.release` 26.3).

| AC1.3 | Run | Commit | Result |
|---|---|---|---|
| (a) auto-resolve, patched: green | https://github.com/chaotix345/rigtune/actions/runs/36212729614 | `4e5b7d3` | resolved `26.4-snapshot-1`; `add_mc_version.py` added it after 26.3; `./gradlew :26.4-snapshot-1:build` BUILD SUCCESSFUL (compileGametestJava and test ran); no open issue, nothing to close |
| (b) unpatched: red, one issue | https://github.com/chaotix345/rigtune/actions/runs/36212871708/attempts/1 | `cc433b6` (temporary commit removing the `getFirstFreeHeight` block) | failed at `:26.4-snapshot-1:compileClientJava`: `BenchmarkWorld.java:323: cannot find symbol ... getBaseHeight(int,int,Types,ServerLevel,RandomState)`; reports artifact `snapshot-canary-reports-26.4-snapshot-1` (43767 bytes); label `snapshot-canary` created; issue #9 opened with the run link |
| (b) second failure: a comment | https://github.com/chaotix345/rigtune/actions/runs/36212871708/attempts/2 | `cc433b6` (re-run) | failed the same way; commented "Still failing on `26.4-snapshot-1` ... attempts/2" on #9; still one open issue with the label |
| (c) green again: closed | https://github.com/chaotix345/rigtune/actions/runs/36213755284 | `984546d` (`cc433b6` reverted in `f03a97d`, plus the review fixes) | build green; #9 closed as completed with "Green again on `26.4-snapshot-1` ... " |
| (d) `mc=26.3`: skip | https://github.com/chaotix345/rigtune/actions/runs/36214117005 | `726f82b` (temporary `INPUT_MC: ${{ inputs.mc \|\| '26.3' }}`) | green; notice "add_mc_version.py refused 26.3: 26.3 is already a node in settings.gradle"; Java, Gradle and build steps skipped; no issue |
| (d) `mc=26.99-snapshot-1`: skip | https://github.com/chaotix345/rigtune/actions/runs/36214329378 | `c46a841` (same, `'26.99-snapshot-1'`) | green; notice "add_mc_version.py refused 26.99-snapshot-1: 26.99-snapshot-1 isn't in the Mojang version manifest"; no issue |

Issue: https://github.com/chaotix345/rigtune/issues/9, labelled `snapshot-canary`, commented as
the canary self-test, closed automatically by run (c).

What changed between the runs: (a) and (b) used the first version of the workflow. (c) used the
code-review fixes (the `>=26.4-alpha` predicates, the add step's error classification, a
read-only Gradle cache, the issue body text). (d) used the final file, which differs from (c)
only in the issue title: "Snapshot canary: Minecraft snapshot build is failing", as SPEC item 1
specifies, instead of "Snapshot canary: RigTune fails to build against the newest Minecraft
snapshot" (issue #9's title). The title is the `ISSUE_TITLE` env value, and the find/comment/close
logic reads it from there. `forced mc` came from a temporary expression default rather than
`-f mc=`; both set the same `INPUT_MC` env var the resolve step reads.

Checked locally only (Git Bash, the extracted `run:` scripts, a stub `python` or `gh` first on
`PATH`, checked with `command -v` before every run):

- Add step, `add_mc_version.py` exit 1 with `unexpected error: ...`, `HTTP 503 from https://...`,
  `https://...: timed out`, `unreadable JSON from ...` or no output: `::error`, exit 1 (the run fails,
  the issue steps are skipped). `... needs Java 26 ...` or `'26w14a' isn't a Minecraft version id
  this tool understands ...`: `::warning`, `ready=false`, exit 0. `no Fabric API build for ... yet`:
  `::notice`, `ready=false`, exit 0.
- The real `add_mc_version.py` in a scratch copy of the repo: `26.4-snapshot-1` writes the node
  and gives `ready=true prev=26.3`.
- Issue step: no issue and no label -> label created, issue opened; label present -> no label call;
  an open issue -> a comment only. Close step: close with a comment, or nothing when no issue is
  open.
- The `latest.snapshot == latest.release` skip: not run anywhere (it needs Mojang's manifest in
  that state; the branch is a string comparison followed by a notice).

After the merge to `main`, `gh workflow run snapshot-canary.yml -f mc=26.3` should end green with
the notice above; that is the first real `workflow_dispatch` run.

Two stray issues, #7 and #8, came from a local dry run of the issue steps that reached the real
`gh` (the stub directory's Windows path broke `PATH`). Both were closed by that same dry run; they
are retitled "[accidental test, ignore] snapshot-canary local dry run", unlabelled and commented.
The label they created was deleted, so run (b) created it again.

## The 26.4 patches

- Red (local, throwaway node): `python tools/add_mc_version.py 26.4-snapshot-1 --prerelease-ok`,
  then `./gradlew :26.4-snapshot-1:compileClientJava` failed on `getBaseHeight`, as in
  docs/research/v0.4/mc-versions.md section 3.2.
- Green (local, throwaway node, final `>=26.4-alpha` blocks): `local-snapshot-node.log` in this
  folder. `:26.4-snapshot-1:build` (which compiles the game tests: `check` depends on
  `gametestClasses`) BUILD SUCCESSFUL; 104 test classes, 1126 tests, 0 failures, 0 errors, 1
  skipped (`LauncherDetectorTest`). The node (its settings.gradle entry and
  `versions/26.4-snapshot-1/`) was removed afterwards and never committed. CI run (c) is the same
  build on Linux.
- `./gradlew build` for 26.2 and 26.3 (committed active version 26.2): 1126 tests each, 0
  failures, 1 skipped; `./gradlew "Reset active project"` leaves no diff.
- The predicate is `>=26.4-alpha`, not `>=26.4-snapshot-1` (the SPEC's text). Stonecutter 0.9.8
  bundles `dev.kikugie.semver`, which compares pre-release ids as text. Calling it directly from a
  scratch Java program: `>=26.4-snapshot-1` is false for `26.4-pre-1` and `26.4-rc-1`, so those
  would build the old API and fail. `>=26.4-alpha` is true for 26.4-snapshot-1/-2/-10, -pre-1,
  -rc-1, 26.4 and 26.4.1, and false for 26.2, 26.3, 26.3.1 and 26.3.1-rc-1.
