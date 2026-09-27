# E2E and release machinery: evidence (WS-E)

Design: docs/v0.5/design/ws-e.md. Harness: tools/e2e/README.md "v0.5: Linux CI".

## Release dry run on the branch (2026-09-27)

Run [36296717280](https://github.com/chaotix345/rigtune/actions/runs/36296717280):
- `gh workflow run release.yml --ref test/v05-e2e -f e2e-tier=release -f verify-tag=v0.4.0` at 199bed3e.
- The run took 11 min 26 s: build 3 min, the E2E release tier about 7 min (its longest job 416 s), publish 68 s.
- **Nothing was published:** `gh release list` still ends at v0.4.0, and `modrinth_project.py status` lists the same seven versions.

**build:**
- Tag `v0.5.0-dev` (dry run: `v<mod_version>`), and the tag guard passed.
- `./gradlew build` ran, then staging with SHA256SUMS:
  - `rigtune-0.5.0-dev+mc26.2.jar` `cca32f92…0ddb`
  - `rigtune-0.5.0-dev+mc26.3.jar` `56534f7d…7c41`
  - sources `31eb9996…c509` and `638d71ea…ef0c`
- These are the same bytes build.yml's java job produced for the same product sources on another runner (run 36284606444). Linux runner to Linux runner was byte-stable here; Windows vs Linux differs in one manifest entry's order (vg §5).

**e2e** (the reusable e2e.yml, release tier, on the staged `release-files`): 16 of 16 PASS on the first attempt.

| scenario | 26.2 | 26.3 |
|---|---|---|
| upgrade-from-0.4.0 | PASS 20/20 (115 s) | PASS 20/20 (124 s) |
| upgrade-from-0.3.0 | PASS 20/20 (135 s) | PASS 20/20 (119 s) |
| upgrade-from-0.2.0 | PASS 20/20 (122 s) | PASS 20/20 (105 s) |
| upgrade-from-0.1.0 (`--legacy-disable`, legacy import) | PASS 21/21 (106 s) | n/a |
| seeded-v010-dh (`chattr +i` hold) | PASS 26/26 (156 s) | n/a (generated seed: E8) |
| undo-profiles (real Battery / Max FPS switches) | PASS 81/81 (318 s) | PASS 81/81 (416 s) |
| undo-settings (staged Sodium key) | PASS 79/79 (377 s) | PASS 79/79 (318 s) |
| downgrade-to-0.4.0 | PASS 16/16 (130 s) | PASS 16/16 (121 s) |
| downgrade-to-0.3.0 | PASS 16/16 (122 s) | PASS 16/16 (128 s) |

Times are whole jobs, from checkout to the evidence upload. Each job's evidence (RESULT.md, checks.json, screenshots,
logs) is its `e2e-<scenario>-<mc>` artifact of the run.
- The downgrades composed `v040-written` only: `v050-written` doesn't exist yet. They prove the harness path on both nodes and both targets, not 0.5's files.
- The check names still say "0.4"/"0.3.0" (residual, E8).

**publish:**
- The staged files match SHA256SUMS (4 OK).
- `gh release create` was printed only.
- Minotaur's debug mode logged the 26.2 and 26.3 payloads (`versionNumber` 0.5.0-dev+mc26.x, `versionType` release) with "Not going to upload this version", with no token.
- The verify step checked v0.4.0 read-only (`tools/e2e/release_verify.py --tag v0.4.0`) on both nodes, all `verified:`:
  - the asset's sha512 matches Modrinth's metadata;
  - the CDN url (`…/versions/zeNyTOnF/rigtune-0.4.0%2Bmc26.2.jar`, `…/SzbFiyYW/…mc26.3.jar`) serves the GitHub asset's bytes;
  - `version_number`, `game_versions`, `loaders` and `version_type` are as expected.

## Static checks

- actionlint 1.7.12 with shellcheck 0.11.0 on e2e.yml and release.yml: clean (local).
- `tools/e2e/tests/test_e2e_workflows.py`, 11 tests (AC3a.6, AC3c.1): no retry; no network but the pinned, sha256-checked old jar; the caller's jars, never rebuilt; evidence always uploaded; build → e2e → publish on `release-files`; no rebuild in publish; a dry run publishes nothing; only publish may write.
  - A mutation check of 9 workflow edits fails at least one test each.

## compat040 (early)

`compat040-early.txt`: the released 0.4.0 jar, `v040-written`, PASS 14/14 (local; CI gets its step with E6).
