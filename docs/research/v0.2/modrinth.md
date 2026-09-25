# RigTune v0.2.0 — Modrinth Publishing Research

Research date: 2026-09-25. All facts below were pulled live (curl against `api.modrinth.com`/`staging-api.modrinth.com`, WebFetch against `docs.modrinth.com`/`modrinth.com`/GitHub, and the GitHub REST API) on this date — re-verify before relying on it if much time has passed. The `rigtune` slug is confirmed 404 on `GET https://api.modrinth.com/v2/project/rigtune` as of this research (live check, see §7).

---

## 1. Recommendation

**Use Minotaur** (`com.modrinth.minotaur`, latest **v2.10.0**, released 2026-09-17 — 8 days before this research, https://github.com/modrinth/minotaur/releases/tag/v2.10.0) as a Gradle plugin invoked from the existing `release.yml` workflow, **not mc-publish and not raw API calls from a shell script.**

Why:
- It's Modrinth's own tool and the one they support: the mc-publish README literally says "note that Modrinth does not give support for `mc-publish` where we do for Minotaur" (https://github.com/modrinth/minotaur — quoted verbatim from the README's intro).
- It's actively maintained: repo `pushed_at` = 2026-09-16 (yesterday relative to research date), latest tag 2026-09-17.
- **mc-publish has moved.** `Kir-Antipov/mc-publish` now 301-redirects at the GitHub API level to `Kira-NT/mc-publish` (`full_name: Kira-NT/mc-publish`, confirmed via `GET /repos/Kir-Antipov/mc-publish` → `Location: .../repositories/410524768`). It's not archived and is reasonably active (`pushed_at` 2026-07-14, latest release `v3.3.1` published 2026-07-14), but the fork/rename adds a footgun: any doc, tutorial, or copy-pasted YAML referencing `Kir-Antipov/mc-publish@v3` needs updating to `Kira-NT/mc-publish@v3`. Didn't find a deprecation notice on the Node runtime in the fetched README excerpt or `action.yml` (UNVERIFIED — see §7).
- Idempotency is built in for the case that matters (a failed release job re-run): Minotaur's own README states the `modrinth` task **"Will fail if Modrinth has this version already"** (quoted verbatim, https://github.com/modrinth/minotaur README, Usage Guide). So re-running the workflow after a partial failure either succeeds (if the Modrinth upload never happened) or fails loudly with no duplicate version — it does not silently create a second `0.2.0+mc26.2` version. This is the same idempotency property either tool would have (it's server-side, since `version_number` is unique per project on Modrinth), but Minotaur is the one to standardize on given the support statement above.
- **Gradle 9.5/9.6 compatibility (this repo's wrapper is 9.5.1, per `docs/research/toolchain.md`):** Minotaur's own build only pins Gradle **8.14.4** in its wrapper (`gradle/wrapper/gradle-wrapper.properties` on `master`) — it does not test itself on Gradle 9. However, a real Gradle-9-only incompatibility (`Invocation of 'Task.project' by task ':modrinth' at execution time is unsupported with the configuration cache`) was reported and **closed** in issue #75 ("Add Gradle 9 cache support", closed 2025-07-27, https://github.com/modrinth/minotaur/issues/75) — over a year before the current v2.10.0 release, and no open issues currently mention Gradle 9. So general Gradle 9.x compatibility looks solid; the exact 9.5.1 combo isn't separately confirmed (UNVERIFIED, §7) but there's no evidence of a live blocker.
- **No `remapJar` (MC 26.x is unobfuscated) — `uploadFile = jar` is correct, but note the README's default guidance is stale for this case.** Minotaur's README Groovy example literally says `uploadFile = jar // With Loom, this MUST be set to remapJar instead of jar!` — that comment predates unobfuscated MC versions. For 26.2/26.3 there is no `remapJar` task (per the task brief and `docs/research/toolchain.md`), so `uploadFile = jar` (the plain Loom-produced `jar` task output) is correct and is what should be used here — do not add a `remapJar` reference or the build will fail with "task not found."

## 2. Exact steps for the user (token creation + secret)

1. Go to **https://modrinth.com/settings/account** (confirmed URL — quoted from `docs.modrinth.com/api/`: *"Personal access tokens (PATs) can be generated in from [the user settings]"* linking to this exact path). UNVERIFIED: the precise on-page label/section name (e.g. whether it's titled "Personal Access Tokens" directly on that page or nested under a sub-tab) — couldn't log in to confirm the click path past this URL; Modrinth's settings UI may have moved this since. If it's not directly there, it will be one click away under Settings.
2. Create a token and select scopes. Two tiers, matching what's needed:
   - **Project + gallery creation** (one-time, done once by the human, not CI): `PROJECT_CREATE` and `PROJECT_WRITE` (gallery upload endpoint explicitly requires `PROJECT_WRITE`, confirmed on `docs.modrinth.com/api/operations/addgalleryimage/`).
   - **CI version publishing only** (what `MODRINTH_TOKEN` in `release.yml` should actually be scoped to): `VERSION_CREATE`.
     - Note a naming discrepancy I found and could not resolve without logging in: Minotaur's README says the `modrinth` Gradle task needs a token with scope **`CREATE_VERSION`**, but the live backend source (`modrinth/labrinth`, `src/models/v3/pats.rs`, fetched today) defines the scope constant as **`VERSION_CREATE`** (word order reversed). This is very likely just the README being stale/inconsistent with the current API naming — the underlying permission is the same one. Practically: when creating the token in the web UI, tick whichever checkbox is labeled **"Create versions"** — the UI label is unambiguous regardless of which internal name it maps to. Flagging as UNVERIFIED only because I couldn't screenshot the actual PAT-creation UI to confirm the exact on-screen wording.
   - Don't grant more than that to the CI token — no `PROJECT_WRITE`, `PROJECT_DELETE`, etc. needed for routine per-tag releases.
3. Set an expiry. UNVERIFIED — couldn't reach the token-creation form without logging in to confirm what expiry options are offered (e.g. 30/60/90 days / custom / no-expiry). If Modrinth only offers dated expiries, whoever owns the account will need a recurring reminder to rotate `MODRINTH_TOKEN` before it lapses (a rotation that silently breaks `release.yml` with an auth error, not a data-loss risk).
4. Store it as a GitHub Actions repo secret:
   ```
   gh secret set MODRINTH_TOKEN --repo chaotix345/rigtune
   ```
   (verified: `gh secret set <name> --repo OWNER/REPO` is valid `gh` CLI syntax — confirmed via `gh secret set --help` in this environment; it reads the value from stdin if `-b/--body` isn't passed, so either pipe the token in or paste it when prompted.)

## 3. Project creation plan

**Recommendation: create the project via the Modrinth web UI, not the API**, even though `POST /v2/project` (multipart, `data` JSON part + `icon` file part) is real and works with a PAT holding `PROJECT_CREATE` (confirmed field-by-field against `docs.modrinth.com/api/operations/createproject/`). Reasons:
- It's a one-time action (not something CI repeats), so there's no automation benefit to scripting it.
- The API's `is_draft` field is documented as **"Deprecated - please always mark this as true"** and `initial_versions` as **"Deprecated - please upload version files after initial upload"** (both quoted verbatim from the live docs page) — i.e. the "create project with an initial version in one call" path is explicitly discouraged now; you create the project (draft), then upload versions separately via `POST /version`, matching the Minotaur flow anyway.
- UNVERIFIED: exactly how a project transitions from `draft` to being submitted for moderator review via the API alone (what `requested_status` value on a `PATCH` does, vs. a "Submit for review" action that might only exist in the web UI). Given this is a one-time step, doing it in the web UI sidesteps the ambiguity entirely.

If it *is* done via the API anyway (e.g. to keep it scripted/reproducible), here's the payload draft — every field below is confirmed against the live docs page:

```json
{
  "slug": "rigtune",
  "title": "RigTune",
  "description": "Hardware-aware performance tuning for Fabric. Detects your CPU/GPU/RAM and applies recommended settings and mod suggestions.",
  "categories": ["optimization"],
  "additional_categories": ["utility"],
  "client_side": "required",
  "server_side": "unsupported",
  "project_type": "mod",
  "license_id": "MIT",
  "source_url": "https://github.com/chaotix345/rigtune",
  "issues_url": "https://github.com/chaotix345/rigtune/issues",
  "is_draft": true
}
```
sent as the `data` multipart field, with a separate `icon` file part (PNG/JPG/JPEG/BMP/GIF/WebP/SVG/SVGZ, per the docs' allowed-format list; live docs currently say **"up to 256KiB in size"** for the icon — note a closed GitHub PR, `modrinth/code#7622`, title *"chore: bump icon size limit from 256KiB to 512KiB"*, suggests this may have since increased to 512KiB; the docs page I fetched still says 256KiB, so treat 256KiB as the safe assumption unless you hit a size-rejection and can retry larger — UNVERIFIED which is current).

`categories`/`additional_categories` are validated against `GET /v2/tag/category` (live-fetched full mod-type list, §6). `license_id` takes an SPDX identifier string (confirmed on the docs page, e.g. `MIT`, `LGPL-3.0-or-later`). `slug` must match `^[\w!@$()`.+,"\-']{3,64}$` (quoted from the docs).

Gallery images go to `POST /project/{id}/gallery` afterward (separate call, `PROJECT_WRITE` scope, up to 5MiB per image per the docs — *"Modrinth allows you to upload files of up to 5MiB to a project's gallery"*, formats png/jpg/jpeg/bmp/gif/webp/svg/svgz/rgb).

## 4. Release workflow sketch

**`build.gradle` addition:**
```groovy
plugins {
    id 'com.modrinth.minotaur' version '2.+'
}

modrinth {
    token = System.getenv('MODRINTH_TOKEN')
    projectId = 'rigtune'
    versionNumber = "${version}+mc${project.findProperty('mcVersion')}"
    versionType = 'release'
    uploadFile = jar   // NOT remapJar — 26.x has no remapJar task (unobfuscated)
    gameVersions = [project.findProperty('mcVersion')]  // '26.2' or '26.3'
    loaders = ['fabric']
    dependencies {
        required.project 'fabric-api'
    }
}
```
Since v0.2.0 ships **one jar per MC version**, this needs to run twice per tag with `mcVersion` swapped (e.g. two Gradle invocations with `-PmcVersion=26.2` / `-PmcVersion=26.3`, or two subprojects/source sets — whichever this repo's actual multi-version build strategy turns out to be; that's a build-layout decision outside this research's scope, not a Modrinth-API constraint).

**`release.yml` sketch** (replacing the commented-out block at `.github/workflows/release.yml:41-65`):
```yaml
      - name: Publish to Modrinth (26.2)
        env:
          MODRINTH_TOKEN: ${{ secrets.MODRINTH_TOKEN }}
        run: ./gradlew modrinth -PmcVersion=26.2

      - name: Publish to Modrinth (26.3)
        env:
          MODRINTH_TOKEN: ${{ secrets.MODRINTH_TOKEN }}
        run: ./gradlew modrinth -PmcVersion=26.3
```
**Idempotency on retry:** because Minotaur's `modrinth` task fails (rather than silently overwriting) when `versionNumber` already exists on the project (README, quoted in §1), re-running the whole job after a failure is safe: any MC-version upload that already succeeded will just fail-fast on retry (harmless, though it does mean a partial-failure retry needs `continue-on-error: true` or a per-step check if you want the *other* MC version's upload to still happen — plain sequential steps as sketched above will stop at the first failure, which for a genuinely-new tag is fine, but for a retry-after-partial-failure means you'd want to either split into independent jobs or add `if: always()` between the two publish steps).

## 5. Moderation and content rules

**What "the Modrinth versions are live" realistically means on day one:** files ARE downloadable from the CDN immediately, even before moderator approval, and even anonymously. From live investigation of `modrinth/code#7434` (an open bug report I fetched): for a project with `status: processing` (awaiting review), *"CDN downloads function normally, even anonymously"* and `GET /v2/project/{id}/version` still returns all versions — the reporter's complaint is only that one specific update-check endpoint (`POST /v2/version_files/update`) inconsistently returns empty for `processing` projects while other routes serve it fine. So: **the jar is fetchable by direct URL right away**, but the project won't appear in Modrinth search/browse, and probably won't resolve by slug in a "nice" way for random visitors, until a moderator approves it.

**Review timeline** (quoted verbatim from https://support.modrinth.com/en/articles/8793355-modrinth-project-review-times, fetched live): target is *"within 24 to 48 hours of submission (with fluctuations averaging two to four weeks, sometimes longer)"* for regular projects (mods, not modpacks — modpacks are 72–96 hours target but have had much worse real-world delays per a June 2026 Modrinth News post on modpack-permission changes, not directly relevant to a mod project). The same article explicitly asks submitters not to chase status: *"Requesting updates on how long it will take to review a given project will slow us down."* Practical read: **budget at least a few days, possibly weeks, between submitting the RigTune project for review and it being publicly discoverable** — don't gate a v0.2.0 announcement on Modrinth approval landing same-day.

**Content Rules** (https://modrinth.com/legal/rules, quoted verbatim from live fetch) — the load-bearing one for RigTune's runtime-download behavior:

> "Be designed to upload any data to a remote server (i.e. one that the user does not directly choose to connect to in-game) without clear disclosure."

is listed under Prohibited Content — note this is about **uploading** data without disclosure. RigTune's behavior is the reverse (downloading, at explicit user click, from Modrinth's own CDN, hash-verified), so this specific clause doesn't prohibit it, but the pattern (network activity the user didn't necessarily expect from a "settings optimizer" mod) squarely triggers the separate, broader obligation under **§2, "Clear and Honest Function"**:

> "Projects, a form of Content, must make a clear and honest attempt to describe their purpose in designated areas on the project page."

and §2.1's "three required elements users should understand from project descriptions" (the WebFetch summary didn't quote the three elements verbatim before truncating — UNVERIFIED exact wording of all three, but the general principle — describe what it does, why, and anything critical to know before installing — is clear enough to act on).

There is **no rule found** that specifically names "auto-updaters" or bans downloading other Modrinth-hosted content — the WebFetch pass over the rules page found nothing under Prohibited Content, Cheats and Hacks, or Misc that addresses this pattern directly (I did not find a rules clause banning launcher-style "install other mods for the user" behavior). §3 "Cheats and Hacks" only concerns unfair multiplayer advantage, not applicable. §6 (AI) not applicable (no AI-generated content mentioned in the brief).

**Verdict: RigTune's behavior (user-click-gated, hash-verified downloads only from `cdn.modrinth.com` via the official API; self-update the same way; a JSON rules file fetched from `raw.githubusercontent.com` with no code in it) is very likely permitted, contingent entirely on disclosure.** Nothing found bans downloading-other-mods-at-user-request or self-updating; the risk surface is entirely "did you tell people clearly enough," not "is this feature type banned." Given the mod also reaches outside Modrinth's own domain (`raw.githubusercontent.com` for the rules JSON), that should be disclosed too even though it's static data, not code — being conservative with disclosure costs nothing and directly serves §2/§2.1.

**Suggested disclosure paragraph for the project description / body** (draft, not verified against a moderator's actual judgment — this is my best-effort compliance language, not a guarantee of approval):

> RigTune detects your hardware and recommends performance mods and settings. At your explicit request, it can download recommended mods directly from Modrinth's own servers (via the official Modrinth API and CDN) — every downloaded file's checksum is verified against Modrinth before use. RigTune can also update itself the same way. No files are downloaded, installed, or modified without you clicking to confirm first, and RigTune sends no telemetry or usage data anywhere. A small, static rules file (no executable code) is fetched from GitHub to drive RigTune's hardware-based recommendations.

**Other rules worth flagging to whoever writes the final listing copy:**
- **Descriptions must avoid excessive external links and misleading claims** — from §5 "Miscellaneous" ("metadata accuracy, titles, summaries, links, images, dependencies, and file organization" per the WebFetch summary — UNVERIFIED exact wording, the summarizer paraphrased this section rather than quoting it). For an "optimization" mod specifically: don't claim specific FPS numbers or hardware-support claims that can't be substantiated, since §1's misleading-claims rule ("cannot make or share intentionally wrong or misleading claims") plainly extends to performance marketing copy, not just legal/licensing claims.
- **Images need alt text** — "your project may be rejected if you don't add alt text" (from the Advanced Markdown formatting help article, paraphrased in a WebSearch result — UNVERIFIED exact quoted wording, but consistent enough across sources to act on).
- **AI disclosure**: only relevant if a "substantial portion" of code/description/images comes from generative AI (§6) — not applicable unless that changes.

## 6. Listing requirements checklist

- [ ] **`project_type`**: `mod` (not `modpack`/`plugin`/`resourcepack`).
- [ ] **Categories** (validated live against `GET /v2/tag/category`, filtered to `project_type: mod`, `header: categories`): full valid list is `adventure, cursed, decoration, economy, equipment, food, game-mechanics, library, magic, management, minigame, mobs, optimization, social, storage, technology, transportation, utility, worldgen`. Recommended for RigTune: **featured `optimization`**, additional `utility`.
- [ ] **`client_side` / `server_side`**: `required` / `unsupported` (these fields are marked deprecated in favor of a new per-version `environment` field — see note below — but are still the fields `POST /v2/project` accepts today; `environment` is confirmed to currently live only in the **experimental v3 API**, not yet in v2, per Modrinth's own announcement post fetched live today — so stick with `client_side`/`server_side` for now).
- [ ] **`license_id`**: `MIT` (confirmed the field takes SPDX identifier strings).
- [ ] **Links**: `source_url` → `https://github.com/chaotix345/rigtune`, `issues_url` → `https://github.com/chaotix345/rigtune/issues`. No `wiki_url`/`discord_url`/`donation_urls` needed unless those exist.
- [ ] **Icon**: PNG/JPG/JPEG/BMP/GIF/WebP/SVG/SVGZ; docs currently say max **256KiB** (possibly raised to 512KiB per an unconfirmed merged PR — verify empirically at upload time). No documented pixel-dimension requirement found (a GitHub issue, `modrinth/code#2764`, title *"Mod icon of any size/shape is accepted"*, suggests it's not enforced) — square, reasonably high-res (e.g. 512×512) is the de facto community norm even if unenforced.
- [ ] **Gallery**: `POST /project/{id}/gallery`, up to 5MiB/image, same format list as icon, `title`/`description`/`ordering`/`featured` optional-ish query params.
- [ ] **Body markdown**: GitHub Flavored Markdown; raw HTML also works but Modrinth "advises against using HTML elements, except when there is no Markdown equivalent (e.g., spoilers and YouTube embeds)" because HTML overrides default element styling (from the Advanced Markdown formatting help article, paraphrased — UNVERIFIED exact quote).
- [ ] **Version fields** (`POST /v2/version`, confirmed field-by-field against live docs): required `project_id`, `file_parts`, `version_number`, `name`; relevant optional ones for RigTune: `changelog`, `dependencies` (array of `{version_id, project_id, file_name, dependency_type}` where `dependency_type` ∈ `required/optional/incompatible/embedded` — use `required.project "fabric-api"` equivalent), `game_versions` (`["26.2"]` or `["26.3"]` — both confirmed as valid, currently-tagged `release`-type entries via live `GET /v2/tag/game_version`), `version_type` (`release/beta/alpha`), `loaders` (`["fabric"]`, confirmed valid loader tag via `GET /v2/tag/loader`), `featured` (bool), `status` (`listed/archived/draft/unlisted/scheduled/unknown`), `primary_file`.
- [ ] **Rate limit / User-Agent**: 300 req/min per IP (confirmed empirically — live unauthenticated request to `api.modrinth.com/v2/project/rigtune` returned `X-Ratelimit-Limit: 300`), higher limits need emailing support@modrinth.com. Docs recommend a distinctive `User-Agent` like `github_username/project_name/version (contact)` — not usually your concern when going through Minotaur (it sets its own), but relevant if any part of the pipeline ever calls the raw API directly.

## 7. UNVERIFIED items (flagged inline above, collected here)

- Exact click path inside `modrinth.com/settings/account` to reach PAT creation, and what expiry options are offered — couldn't log in to check.
- Whether the on-screen PAT scope checkbox is literally labeled to match `VERSION_CREATE` (source-confirmed backend scope name) or `CREATE_VERSION` (Minotaur README's name) — pick by the human-readable label ("Create versions"), not the internal constant.
- Current icon max size: docs page says 256KiB; a merged GitHub PR title suggests 512KiB. Which is live today wasn't independently confirmed.
- Exact mechanism to move a project from `draft` to "submitted for review" via the API alone (vs. only via a web UI action) — sidestepped by recommending web-UI project creation (§3).
- mc-publish (`Kira-NT/mc-publish`) Node.js runtime version and any GitHub Actions Node-runtime deprecation warnings — the fetched README excerpt and `action.yml` didn't surface this; moot anyway since Minotaur is the recommendation.
- Minotaur's compatibility with the *exact* Gradle 9.5.1 point release used by this repo — general Gradle 9.x compatibility looks solid (closed issue, current activity) but no source pins or tests the specific 9.5.1 build.
- Exact verbatim wording of Content Rules §2.1's "three required elements" and §5 "Miscellaneous" — WebFetch's summarizer paraphrased instead of quoting; re-fetch `https://modrinth.com/legal/rules` directly (ideally render the actual page, not just a text-extraction summary) before finalizing listing copy if exact wording matters for a compliance sign-off.
- Whether `staging-api.modrinth.com` would accept a *separate* staging-account token for a safe dry run (§ below) — only checked unauthenticated reachability, no token available to test write behavior.

**Staging API note (from the brief's Q7):** `staging-api.modrinth.com` **does exist and responds** — a live unauthenticated `GET /` returned a normal labrinth JSON banner (`"name":"modrinth-labrinth","version":"2.7.0"`, build date 2026-09-21), and `GET /v2/tag/category` on it returned 200 with real data. So read-only dry-running against staging (e.g. sanity-checking payload shapes with `GET` calls) works today with no credentials. Whether it accepts a *write* (`POST /version`, `POST /project`) from a token issued on a separate staging account, and whether staging data is isolated from production, wasn't tested — no token was available and the task explicitly disallowed obtaining one. Treat this as a promising but unverified dry-run path, not a confirmed one.
