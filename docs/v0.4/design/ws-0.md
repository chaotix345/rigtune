# WS-0 design notes: snapshot canary, 26.4 patches, 0.4.0-dev

Branch `feat/v04-foundation`. SPEC item 1; PLAN "WS-0". Evidence:
docs/v0.4/verification/snapshot-canary/README.md.

## What was done

- **26.4 API patches** (`BenchmarkWorld.terrainFloor`, `BenchmarkGameTest.worldCheck`): Stonecutter
  blocks use `getFirstFreeHeight` / `getUncachedBiome` on 26.4 and keep `getBaseHeight` /
  `getUncachedNoiseBiome` on 26.2/26.3. Committed state is 26.2.
- **`.github/workflows/snapshot-canary.yml`**: `schedule` (Wed 05:00 UTC) + `workflow_dispatch`
  (input `mc`), `permissions: contents: read, issues: write`, `concurrency: snapshot-canary` without
  cancel. The same action versions as build.yml (checkout@v7, wrapper-validation@v6,
  setup-python@v7, setup-java@v6 microsoft 25, setup-gradle@v6, upload-artifact@v7). Resolve
  `latest.snapshot` (skip when it equals `latest.release`) -> `add_mc_version.py <mc> --prerelease-ok`
  in the runner checkout -> `./gradlew :<mc>:build` -> on failure: reports artifact, one issue
  (title "Snapshot canary: Minecraft snapshot build is failing", label `snapshot-canary`, created if
  missing, found by label + exact title), a comment on later failures -> on success: close with a
  comment. `persist-credentials: false`; the dispatch input goes through `env` (no `${{ }}` inside
  `run:`) and has its whitespace stripped before `$GITHUB_OUTPUT`.
- `gradle.properties`: `mod_version=0.4.0-dev` (`changelogForVersion` strips `-dev`; release.yml's
  tag check will require bumping it to 0.4.0 before tagging).
- `tools/MC_VERSIONS.md`: "Snapshot canary" section; the "Version ranges" note on pre-release
  blocks corrected (below).

## Deviations from SPEC item 1 / PLAN

1. **Predicate `>=26.4-alpha`, not `>=26.4-snapshot-1`.** A code-review finding, checked against
   Stonecutter 0.9.8's bundled `dev.kikugie.semver`: pre-release ids compare as text, so
   `26.4-pre-1` and `26.4-rc-1` sort below `26.4-snapshot-1`. With the SPEC's predicate the canary
   would go red the week Mojang ships 26.4-pre-1, even though the fix is in. `>=26.4-alpha` matches
   every 26.4 snapshot, pre-release, RC and the release, and no 26.3.x. When the 26.4 node lands it
   can be rewritten as `>=26.4`. MC_VERSIONS.md's old sentence ("a `//? if >=<id>` block written
   for the pre-release still matches the release") was true only for the final release. I
   corrected it: a small edit outside the canary section, needed because the canary's issue points
   there.
2. **An add_mc_version.py network error or crash fails the run** (red, no issue) instead of
   skipping. `add_mc_version.py` exits 1 for a refusal, a `NetworkError` and an unexpected
   exception alike. Skipping all of them would let a broken check stay green for months. The
   workflow sorts them by the tool's `add_mc_version: <reason>` line. A Java-version or unknown-id
   refusal is a `::warning` (still green), because it needs a change here. All other refusals are
   a `::notice`, as the SPEC asks. The string matching depends on add_mc_version.py's message
   wording (NetworkError: `HTTP n from`, `no response from`, `unreadable ... from`, `<url>: ...`;
   crash: `unexpected error:`). A separate exit code in the tool would be sturdier, but the tool
   isn't WS-0's file.
3. **Reports are uploaded only on a failed build** (the coordinator's prompt), not on every run.
4. **Self-test by push, not dispatch.** Dispatch needs the file on `main`. Temporary `push:`
   triggers (and, for the forced ids, a temporary `inputs.mc || '<id>'` default) stood in for
   `gh workflow run -f mc=`. All of them were removed; the final file has no `push:`.
5. **(b) on the branch itself.** The unpatched run came from a temporary commit on
   `feat/v04-foundation` (`cc433b6`, reverted in `f03a97d`), not from a scratch branch.
   History keeps these self-test commits (no force push).
6. **`setup-gradle` is `cache-read-only: true`** (review finding): snapshot Minecraft and Fabric
   artifacts don't take build.yml's cache space. Each canary run re-downloads them (the build took
   about 1 min in CI anyway).

## Review findings (code-reviewer subagent)

0 high, 2 medium, 4 low. Fixed: M1 (predicate), M2 (error classification), L3 (read-only cache), L4
(issue body: compile the previous node before `mc_apidiff.py`), L6 (MC_VERSIONS.md says a release
isn't canaried and an old issue stays open until a green run). Not done: L5. It proposed
splitting the issue steps into a second job, so the snapshot build (third-party snapshot code)
never shares a job with the `issues: write` token. The token isn't in the build step's env and
checkout doesn't persist it, but a malicious dependency could still write `$GITHUB_ENV` or
`$GITHUB_PATH` for the later `gh` steps. The reviewer rated it low, since the blast radius is
issues only and build.yml already trusts the same dependencies. Worth doing if the canary ever
gets more permissions.

## Incident: stray issues #7 and #8

A local dry run of the issue steps meant to use a stub `gh`, but the stub's directory was put on
`PATH` as a `C:/...` path, whose colon split the entry, so the real `gh` ran. It created the label
and issues #7 and #8 ("Snapshot canary: T") and closed both. Cleanup: both were retitled
"[accidental test, ignore] snapshot-canary local dry run", unlabelled and commented, and the label
was deleted. Issues weren't deleted (irreversible; the owner can delete them if wanted). Later
stubs used `cygpath -u` and a `command -v` check before running. The run also showed that
`gh issue list --label` can miss an issue created a few seconds earlier: #8 was a duplicate made
seconds after #7. Real canary runs are a week apart and serialized by `concurrency`, so this can't
happen in practice.

## Follow-ups (not WS-0 files)

- `tools/add_mc_version.py`'s printed checklist still says "Fix compile errors with
  `//? if >=<mc> {` blocks". For a pre-release id it should say `>=<base>-alpha` (see deviation 1).
- The ubuntu-latest image moves to Ubuntu 26 from 2026-10-19 (a runner notice on every run). The
  canary follows build.yml's `java` job in using `ubuntu-latest`.

## UNVERIFIED

- The `latest.snapshot == latest.release` skip has never run (it needs Mojang's manifest in that
  state). It is a string comparison followed by a notice.
- A real `workflow_dispatch` run and the `schedule` trigger: possible only once the file is on
  `main`. First check after the merge: `gh workflow run snapshot-canary.yml -f mc=26.3` should end
  green with the "already a node" notice.
- The final issue title was never used by a real run. #9 carries the earlier title; the logic reads
  the title from `ISSUE_TITLE` in both versions.
- The warning and error branches of the add step ran only locally, against a stub `python`.
- `>=26.4-alpha` on a real 26.4-pre-N or -rc-N node: none exists yet. The evidence is the
  semver library's own comparison, not a Stonecutter build.
