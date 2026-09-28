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
- The check names said "0.4"/"0.3.0" then; they name the versions they run since 4c3b6696.

**publish:**
- The staged files match SHA256SUMS (4 OK).
- `gh release create` was printed only.
- Minotaur's debug mode logged the 26.2 and 26.3 payloads (`versionNumber` 0.5.0-dev+mc26.x, `versionType` release) with "Not going to upload this version", with no token.
- The verify step checked v0.4.0 read-only (`tools/e2e/release_verify.py --tag v0.4.0`) on both nodes, all `verified:`:
  - the asset's sha512 matches Modrinth's metadata;
  - the CDN url (`…/versions/zeNyTOnF/rigtune-0.4.0%2Bmc26.2.jar`, `…/SzbFiyYW/…mc26.3.jar`) serves the GitHub asset's bytes;
  - `version_number`, `game_versions`, `loaders` and `version_type` are as expected.

## Local runs, Windows 11, 26.2 (2026-09-28)

Evidence in `local-windows-26.2/` (RESULT.md per run, scrubbed).
- **helper-kill** (AC3f.5): PASS, 3 starts.
  - The helper (0.5's) was killed 1.5 s after it recorded the group, while op 2 retried the held file; mods/ held 1.0.0 again then (op 1 rolled back before the pause).
  - The next exit's helper applied both ops (`OK`, `OK`), and `unfinished-groups.json` dropped the group.
  - The third start loaded e2e-kill 1.1.0, and History shows "Updated e2e-kill … Applied" (`helper-kill-check-history.png`).
- **The undo scenario with `guard-apply`** (AC3f.7): PASS, first run.
  - The pinned update was refused: "RigTune E2E test mod e2e-pinner, which is installed, needs RigTune E2E test mod e2e-pin-target 1.0.x, not 2.0.0".
  - The addition was staged and applied at exit.
  - The reverse check refused the other update: "Modrinth marks e2e-rev-add, which is waiting for a restart, as incompatible with e2e-rev-target". The fake Modrinth logged the `GET /v2/versions` naming the staged version.
- **Downgrade to 0.4.0 and to 0.3.0 with `v050-written`** (AC3b.3): PASS.
  - The sets: WS-E's placeholders, plus WS-P2's real `ws-p2`.
  - The first run failed on ws-t's placeholder (its try's patch target already held the value): fixed in the placeholder.
  - 0.5 reads its files back, `server-profiles.json`'s servers included.

## Local runs, Windows 11, 26.2 (2026-09-28, after the review round and the Wave A merges)

`local-windows-26.2/2026-09-28-*` holds the results. The jar was built from 97aaeaef's tree plus the harness changes of 26df47f3.

| run | result | notes |
|---|---|---|
| helper-kill | PASS | the loaded e2e-kill is now checked as 1.1.0 (L9) |
| undo (with guard-apply) | PASS | each Apply waits for its own status key (L4) |
| downgrade to 0.4.0 | PASS | the real ws-b, f, p, p2, s and w sets plus the placeholders. The composed journal (57 entries) is trimmed to 46 plus the staged entries: without the trim, the old versions' cap evicted the checked Undo-last pair |
| downgrade to 0.3.0 | PASS | same |
| stale-seed `v010-dh-app-reinstalled` (AC2H.6) | PASS | dropped with "RigTune dropped its pending change to Distant Horizons: it is already installed (DistantHorizons-3.3.2-26.2-fabric-neoforge.jar)"; both changes ABANDONED; no helper at exit |
| stale-seed `v010-dh-app-reinstalled-disabled` | PASS | same, with fabric-26.2.jar disabled instead of removed |
| brand theseus (AC4j.3) | PASS | Apply everything held the 0.4 file group ("Held 1 operation(s)") with mods/ unchanged and maxFps applied; the next start's held notice → Cancel them: download superseded, change DISCARDED (`2026-09-28-brand-held-notice.png`) |
| downgrades again, with WS-L2's ws-l2 | PASS, PASS | its held group has no journal entry; the check expects none |
| after review round 2 (r8): brand, stale-seed `v010-dh-app-reinstalled`, both downgrades | PASS ×4 | brand: the staged Sodium patch applied ("OK PATCH_JSON", `chunk_builder_threads` 3, APPLIED) while the file group held; stale-seed with `expectStatus` ABANDONED; the downgrades trimmed to 46 entries plus baselines and profile-referenced ones (`2026-09-28-r8-*`) |
| downgrades with WS-W2's real ws-w2 set (r9, the CI jar of a470a489) | PASS, PASS | startup-times.json composed from ws-f's 3 runs and ws-w2's 6 (with `preloadMs`); 0.5 reads its runs back after 0.4.0, and 0.3.0 leaves it byte-identical (`2026-09-28-r9-*`) |
| held-group hand-over from 0.4.0 (`--scenario handover`, vg §1.5; dev run on 26.2, the release row is 26.3's) | PASS | stage: 0.4.0 staged the DH 3.3.0 → 3.3.2 group, its helper failed it on the held jar ("Gave up after 10 tries", attempts 1); update: the self-update swapped RigTune, the group failed again (attempts 2); verify: 0.5's exit finished it (both ops OK, 3.3.2 enabled, journal APPLIED). 0.4.0 leaves the journal's changes STAGED through both failures (`2026-09-28-handover-*`) |

## Static checks

- actionlint 1.7.12 with shellcheck 0.11.0 on e2e.yml and release.yml: clean (local).
- `tools/e2e/tests/test_e2e_workflows.py`, 11 tests (AC3a.6, AC3c.1): no retry; no network but the pinned, sha256-checked old jar; the caller's jars, never rebuilt; evidence always uploaded; build → e2e → publish on `release-files`; no rebuild in publish; a dry run publishes nothing; only publish may write.
  - A mutation check of 9 workflow edits fails at least one test each.

## compat040 (early)

`compat040-early.txt`: the released 0.4.0 jar, `v040-written`, PASS 14/14 (local; CI gets its step with E6).
