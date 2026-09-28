# Review 12, area R12REL: the release and publish path (ce8b1a8b..ac109a2d)

Read-only, worktree C:/Dev/Worktrees/rigtune-review12 @ ac109a2d. Nothing run except reading files. Minotaur 2.10.0's payload was
read from the class files in the local Gradle cache (`com.modrinth.minotaur/Minotaur/2.10.0`, the constant pools of
`TaskModrinthUpload` and `TemporaryCreateVersion$TemporaryCreateVersionRequest[Builder]`). No network.

Counts: HIGH 0, MEDIUM 0, LOW 7. CI-1 is still open at ac109a2d, and r-ci's unmerged fix is needed (see below).

## Review-11 findings in this area

| id | status at ac109a2d | evidence |
|---|---|---|
| CI-1 (M) | NOT FIXED here; the fix exists, unmerged | `tools/ci_streak.py:87` still counts any skipped job as "not green". Every build.yml push or dispatch run has `e2e / jars` skipped (e2e.yml:54, since build.yml passes `jars-artifact`), plus `e2e / stutter script (...)` and `e2e / battery OSHI leg (...)` skipped in the push tier (e2e.yml:193, 259). So no run can count, and AC1g.2's RC streak can never reach 5. r-ci's fix is **73f3114c on `fix/v05-footprint-checkpoint`** (not in feat/v0.5.0). It adds a DESIGN_SKIPPED prefix allow-list that applies only when the job is skipped and isn't required or `--require`d, and with the split it requires every part k/n of each leg. It is tested on run 36380625735's real job list. I read it and found no defect: `startswith` also covers GitHub's unexpanded matrix name for a skipped matrix job, and a skipped scenario row (`e2e / e2e ...`) still breaks the streak. **The branch is needed** before the RC streak is counted. |
| CI-2 (M) | FIXED | publish has no JDK and no Gradle (release.yml:142-234). It runs `tools/e2e/modrinth_publish.py` -> `tools/ci/retry.sh python3 tools/modrinth_project.py upload-version --sha256 <SHA256SUMS digest>` per node. That call is idempotent by version_number (modrinth_project.py:375-378). The token never reaches a Gradle configuration, and the checkout has `persist-credentials: false`. Residual window: R12REL-1. |
| CI-3 (L) | FIXED | stutter_run.py:51-67. `since()` unwraps across midnight (below -12 h: +86400) for tp, entry and the GC pauses. `client_log()` reads the rotated `*.log.gz` (mtime order) and then latest.log. |
| CI-4 (L) | FIXED | e2e_matrix.py:40-42, 60-62, 92: a node without Sodium gets `undo` (no `--profile-switch`, so `prepare_profile_instance`/`self.sodium()` isn't reached, self_update_e2e.py:394, 464) and `helper-kill`. stutter_run.py:191-198 adds Sodium only when it has a version. build.gradle:58, 402 already guard the Gradle side. `brand` stays 26.2-only. |
| CI-5 (L) | FIXED | release.yml:151-155: a non-fork tag push without MODRINTH_TOKEN fails before "Create GitHub release". A fork gets the notice. |
| CI-6 (L) | PARTLY (the part left out is justified) | build.yml:364-370 plus tools/rules_revision_check.py: a PR into main with changed content needs a higher revision, and unchanged content needs the same revision. Leaving out `finalize_documents` under `--skip-main-check` is justified: SPEC 2T's repeated regenerations stay in one R, and the merge-time check covers re-emission. New regression-class issue: R12REL-5. |
| SEC-6 (L) | FIXED | release.yml:145-147. |
| SEC-7 (L) | FIXED | build.yml:166 adds `github.event.pull_request.head.repo.full_name == github.repository` (short-circuits for push). |
| COMPAT-5 (L) | FIXED | Compat040.java:339-364: an `ApplyResult` kind (0.4.0's load, reconcile with nothing pending, and History reasons from the file's failures), used in ws-h/expect.json. ws-s2 and ws-t add `ApplyHelper appliesGroup`. written.py:47-49 adds awareness.json's three lists (incl. `acknowledgedStartupRegressions`) to V050.kept. |

## The new payload against v0.4.0's Minotaur 2.10.0 publish

| field | Minotaur 2.10.0 (build.gradle:180-195) | now (modrinth_publish.py -> modrinth_project.py:352-363) | same? |
|---|---|---|---|
| version_number | `project.version` = `<mod_version>+mc<node>` | the staged jar's name without `rigtune-`/`.jar` | yes (the tag guard makes tag = mod_version) |
| name | `RigTune <mod_version> (MC <node>)` | `RigTune <tag without v> (MC <node>)` | yes |
| game_versions | `[stonecutter.current.version]` (no fallback, because it is set) | `[<node dir>]` | yes |
| loaders | `['fabric']` (+ detected fabric, deduped) | `fabric` | yes |
| version_type | `release` if the node is `\d+\.\d+(\.\d+)?`, else `alpha` | the same regex | yes; release_verify checks it |
| changelog | `changelogForVersion` (the `## [...]` section), CRLF to LF | a line-for-line port (`changelog()`), read_text normalises newlines | yes (empty in both when the section is missing: R12REL-3) |
| dependencies | `required.project 'fabric-api'`, slug resolved to id | fabric-api resolved to its id, `required` | yes (fabric.mod.json unchanged since v0.4.0: only fabric-api is required) |
| featured | never set, so the builder's primitive `false` is serialised | `True` | **no**: R12REL-2 |
| environment / status / requested_status | not set (null, omitted) | not sent | yes (R12REL-6 is about AC3h.1, not a regression) |
| file / primary | one file, `rigtune-<ver>+mc<node>.jar`, no `primary_file` | one file, the same name, part `file`, no `primary_file` | yes. A single file is the version's file, and the client falls back to `files[0]` anyway (ModrinthVersion.java:48) |
| per-node split | one version per node | one `upload-version` per `versions/*` dir, each in retry.sh | yes |

## Findings

| id | severity | file:line | scenario | fix |
|---|---|---|---|---|
| R12REL-1 | LOW | .github/workflows/release.yml:170-196 (GitHub release) before :224-234 (first Modrinth contact) | The first request to Modrinth comes after `gh release create` has made v0.5.0 public. The retry budget is 3 tries, 30 s + 90 s apart. Any of these at tag time outlasts it: a Modrinth API outage of more than about 3 min (SPEC 1 counts Modrinth outages lasting hours), an expired or revoked CI token, or a token missing a scope. The result is a public GitHub release with neither Modrinth version, which needs the manual recovery at :210-223 with the user's local setup token. CI-2's fix shrank this window (no Gradle) but didn't move it. The same preflight would also catch a `0.5.0+mc*` version that already exists with other bytes (R12REL-7) before anything is public. | Before "Create GitHub release", on a tag push, add a read-only step with the token: `tools/ci/retry.sh python3 tools/modrinth_project.py status` (authenticated GET of the project and its version list). Fail if it errors, or if a version_number `<ver>+mc<node>` already exists whose file sha512 isn't the staged jar's. A failure there leaves nothing public, and the job can simply be re-run. |
| R12REL-2 | LOW | tools/modrinth_project.py:361; the claim at tools/e2e/modrinth_publish.py:7-9, release.yml:199-200, docs/v0.5/design/ws-e.md:232 | The CI-2 fix says the payload "is what build.gradle's `modrinth {}` block sent". Minotaur 2.10.0 never calls the builder's `featured(...)`, and the field is a primitive boolean with no builder default, so 0.2.0-0.4.0 went up with `featured: false`. upload-version always sends `featured: true`. At v0.5.0 both 0.5.0 versions become Featured on the project page, next to 0.1.0 (uploaded with this tool on 2026-09-25, so also featured). No product code reads `featured` (grep: only FakeModrinth). | Decide which you want. Either add `--featured/--no-featured` (default false for parity) and have modrinth_publish pass it, or keep true, correct the three claims, and unfeature 0.1.0 after the release. |
| R12REL-3 | LOW | tools/e2e/modrinth_publish.py:29-38, 67 (and modrinth_project.py:350-351) | Nothing checks the changelog before the GitHub release goes public. (a) A missing or misnamed `## [0.5.0]` heading (at ac109a2d CHANGELOG.md has only `[Unreleased]`, :7) publishes both versions with an empty changelog, silently. (b) labrinth caps the changelog at 65536 characters (UNVERIFIED offline). 0.4.0's section was already 20,626 characters, and v0.5 adds 5 features, P0 hardening and 2 review rounds. An oversized section fails the POST with a 400 on all 3 tries for both nodes, after the GitHub release exists. Minotaur behaved the same, so this is not a regression. | In the build job, on a tag push (before anything is public), check `modrinth_publish.changelog(CHANGELOG.md, mod_version)` and fail if it is empty or longer than 65,000 characters. Or add `--check` to modrinth_publish.py and call it there. |
| R12REL-4 | LOW | build.gradle:3, 146-150, 176-177, 180-195 | The Minotaur path is dead for CI but still advertised. The comments say "release.yml runs it for every versions/*/ node in a loop, after building" and "`./gradlew :<mc>:modrinth` runs this directly", and every Gradle run still resolves Minotaur, Modrinth4J and okhttp in the prefetch. Scenario: a node's publish fails and whoever recovers follows build.gradle rather than release.yml:210-223, running `./gradlew :26.3:modrinth` with MODRINTH_TOKEN set. Without `-PmodrinthFile`, Minotaur uploads the local rebuild, which isn't byte-stable (vg §5). Modrinth then serves bytes nobody tested, and P0.3 is broken. The version number is then taken, and fixing it means deleting the version by hand. It also sends `featured: false`, unlike the CI path. | Remove the plugin and the `modrinth {}` block (plus the `-PmodrinthDryRun` mentions in docs). If it has to stay, make it refuse to run without `-PmodrinthFile` and correct the comments to point at modrinth_publish.py and the recovery text. |
| R12REL-5 | LOW (a CI re-run flake) | .github/workflows/build.yml:364-370 | The check compares the PR's merge commit with **main's live tip** (`git fetch origin main`), not the base the merge commit was built on. Scenario: PR X doesn't touch the rules, and its run's merge ref has main at r16. The weekly bot then merges r17 into main. A later "Re-run all jobs" of X's run (same GITHUB_SHA) compares the head's r16 content with main's r17 and fails "the rules changed but the revision is 16 (main has 17)". X changes nothing in the rules. | Use `actions/checkout` with `fetch-depth: 2` in rules-consistency and compare `git show HEAD^1:rules/rules-v2.json` (the base side of GitHub's merge commit) with the head's file. That is deterministic across re-runs and needs no network step. A release PR and a bot r17 still conflict on `generatedAt`, which forces a new merge and a new run, so no case the live fetch caught is lost. |
| R12REL-6 | LOW (not a regression) | tools/modrinth_project.py:352-363, 387-405; docs/v0.5/SPEC.md:466 (AC3h.1) | AC3h.1 wants both 0.5.0 versions listed "with the environment set". Neither the old nor the new upload sends one. The only thing that sets it is `submit`'s sides PATCH (:396-398, "review refuses a missing environment"), and AC3h.1 runs `submit` only "while the project isn't approved". If moderation approves the project before the tag, following the checklist leaves 0.5.0 without an environment and AC3h.1 fails. | Split the idempotent sides PATCH out of `submit` (e.g. a `sides` subcommand), and run it after every release whatever the status. Update AC3h.1's step list. |
| R12REL-7 | LOW | tools/modrinth_project.py:105 (`timeout=60` for every call, including the POST), 375-379; tools/e2e/modrinth_publish.py:76-78 | Idempotency is by version_number only, and is checked client-side before the POST. (a) Duplicate: under a slow Modrinth, the POST's 60 s read timeout fires (an uncaught exception, exit 1) while the origin, behind Cloudflare's roughly 100 s, is still creating the version. retry.sh waits 30 s, the list doesn't show it yet, and a second POST goes out. That can make two `0.5.0+mc26.x` versions if labrinth doesn't enforce unique version numbers (UNVERIFIED offline; research v0.2 says it does). (b) Foreign bytes: an existing version with other bytes (an earlier aborted run, a re-tag) prints "already exists", exits 0, and modrinth_publish says "published". Only the verify step notices, after both are public. | Give the POST a read timeout above Cloudflare's (e.g. 180 s). In the "already exists" branch, compare the local file's sha512 with the existing version's `files[].hashes.sha512` and raise ModrinthError when none match. Print "already on Modrinth" rather than "published" in that case. |

## Checked, fine
- **Order and failure handling:**
  - Guard (token) -> download -> `sha256sum -c` -> GitHub release -> Modrinth (retried per node, one node's failure doesn't stop the other, the step fails at the end) -> verify (`!cancelled()`).
  - A failed guard or sha check skips the GitHub release, and Modrinth is gated on `steps.release.outcome == 'success'`.
  - A "Re-run failed jobs" of publish after a partial success is harmless. `gh release create` fails because the release exists, and Modrinth and verify are skipped.
  - A "Re-run all jobs" rebuilds, but can never reach Modrinth for the same reason. So a rebuilt jar can't be published through a re-run.
  - No partial Modrinth version: `POST /version` is one request.
- **Byte identity:**
  - One `release-files` artifact (jars + SHA256SUMS). e2e.yml's scenario, stutter and battery jobs test it. No e2e.yml upload uses that name (e2e-new-jars, e2e-ID-MC, stutter-script-MC, battery-oshi-MC), and v4+ artifacts are immutable.
  - publish re-checks SHA256SUMS, uploads the same paths to GitHub, and passes each jar's SHA256SUMS digest to upload-version, which re-hashes before the POST.
  - release_verify compares the GitHub asset with Modrinth's sha1/sha512 and the CDN bytes (no token to the CDN), plus number, game_versions, loaders and type per node.
  - The glob `rigtune-*+mc<node>.jar` can't match the -sources jar.
- **Tag guard:** `grep -qxF "mod_version=${TAG#v}"` in build before anything is built. The dry-run tag is `v<mod_version>`. The payload's name and the verify's expectations derive from the same tag.
- **Token:**
  - MODRINTH_TOKEN appears only on the Modrinth step (push only; empty on dispatch, with `--dry-run`, so read_token isn't even called) and the verify step. The job level has only the boolean, and build/e2e get no secrets.
  - Not printed: the tool never prints it. ApiError carries the URL and at most 500 characters of the response body. An uncaught URLError/timeout traceback has no headers. retry.sh's warning prints only the argv (paths, digest, names).
  - It goes only to api.modrinth.com (the fabric-api GET too).
  - `persist-credentials: false`. gh takes GITHUB_TOKEN from the step env and resolves the repo from the origin remote.
- **Scopes:** the CI token's "read projects, create and read versions" covers every call: GET project and version list (verify already uses the list), GET fabric-api (public), and POST version (what Minotaur sent). The dry run exercises the step's paths (python3 3.12, retry.sh at mode 100755, the cwd-relative `tools/ci/retry.sh`) but no API call. The first real call is the tag. Recommended: one release.yml dispatch on the RC (push tier) to prove the new step's Actions path. It never ran in Actions: the only release dry run, 36296717280, used Minotaur.
- **version_number / name:** within labrinth's limits (12 and 23 characters, URL-safe incl. `+`, as 0.2-0.4).
- **The JDK retry:**
  - Every setup-java in build/e2e/release has the id'd first try, `sleep 30` and an unguarded retry.
  - publish no longer has a JDK, and JdkRetryTests parses all three files.
  - `continue-on-error` appears nowhere else.
- **The game-test split (GAMETEST_PARTS 2):**
  - Job names are "client game tests (MC, BACKEND, part k/2)". ci_streak's `leg_of` strips the part, so REQUIRED_LEGS still match.
  - r-ci's fix adds the "every part present" check.
  - `PARTS: inputs.gametest_parts || env.GAMETEST_PARTS` is valid at step level.
  - build.gradle's Sodium dependencies are guarded for a no-Sodium node.
  - Note: docs/v0.5/verification/ci/README.md records only the pre-C02 AC1g.4 proof and the switch. r-ci's commit message cites green split runs on 111cb2be and e4acccfd; record one of them there (review-11's "part 2 starts with a NEW player" concern then has its evidence).
- **CI-5 edge cases:**
  - A fork's tag push gets the notice (`!github.event.repository.fork`).
  - A dispatch without the secret still runs the dry-run payload.
  - The notice step can't fire after the guard failed (default `success()`).
- **Version type for a pre-release tag** (e.g. v0.5.0-rc.1): published as `release` on a 26.2/26.3 node. This is the same rule as Minotaur (only the node decides), so it is not a regression. Don't push an RC tag expecting an alpha.
