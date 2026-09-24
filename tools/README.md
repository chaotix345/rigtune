# RigTune rules pipeline

`tools/update_rules.py` regenerates `rules/rules-v1.json` (and its bundled copy at
`src/main/resources/rigtune/rules-v1.json`) from two inputs:

- `rules/source/knowledge.json` — the hand-maintained tuning knowledge (GPU/CPU/heap
  tiers, mod rules, obsolete mods, settings, advice). Same shape as the schema in
  `docs/RULES_SCHEMA.md`, minus the fields the script generates.
- Live data: the Fabulously Optimized and Additive packwiz repos on GitHub, and the
  Modrinth API.

It requires only the Python 3.11+ standard library (no `pip install` needed).

## What it does

1. Picks the target Minecraft versions: the newest 3 Modrinth `release`-type game
   versions, or whatever `--mc-versions 26.2,26.3` overrides it to.
2. For each target version, lists `Packwiz/{version}/mods/*.pw.toml` in
   `Fabulously-Optimized/fabulously-optimized` and
   `versions/fabric/{version}/mods/*.pw.toml` in `skywardmc/additive`, and reads each
   file's `[update.modrinth] mod-id`. A missing version folder (a pack that hasn't
   ported to a newer MC release yet) is treated as "this pack doesn't have this
   version" rather than an error.
3. Batches all the collected Modrinth project ids (both packs' upstream mods and every
   rule mod in `knowledge.json`) through `GET /v2/projects?ids=[...]`.
4. For each pack, uses the **newest target version that pack actually has** to decide
   which rule mods are "upstream" there (`mods[].upstream.fabulouslyOptimized` /
   `.additive`), and records the same pack's full slug list under the top-level
   `upstream` object.
5. Builds `availability[mcVersion]` per rule mod. A project's `game_versions`/
   `loaders` fields are unions across every version it has ever published, so
   they're used only as a cheap pre-filter (a mod that fails them is unavailable,
   no request needed). Anything that passes is confirmed with a per-version
   Modrinth query (`GET /project/{id}/version?loaders=["fabric"]&game_versions=["<mc>"]`);
   available means that query returns at least one version.
6. Diffs the newly assembled content against the existing `rules/rules-v1.json`,
   **ignoring `revision` and `generatedAt`**. If nothing changed, the file is left
   untouched. If it changed (or there's no existing file), `revision` becomes
   `old_revision + 1`, `generatedAt` is stamped, and both output files are written
   identically.
7. Writes `rules/REVIEW.md` — see below.

## Running it

```
python tools/update_rules.py                      # normal run, writes the real files
python tools/update_rules.py --dry-run             # compute + print a summary, write nothing
python tools/update_rules.py --mc-versions 26.2,26.3
python tools/update_rules.py --knowledge path/to/knowledge.json --out-dir path/to/scratch
python tools/update_rules.py --offline-fixtures path/to/fixtures   # no network; see below
```

An optional `GITHUB_TOKEN` environment variable is used for the GitHub API calls
(higher rate limit); it isn't required. Requests carry a descriptive User-Agent, retry
429/5xx with backoff, and throttle Modrinth calls to stay well under its 300 req/min
limit.

`--offline-fixtures DIR` replaces all HTTP calls with reads from `DIR/<sha256(url)>.json`
files (`{"status": 200, "body": ..., "headers": {...}}`). It's meant for reproducing a
run without the network, not for the unit tests, which inject a fake fetch function
directly (see `tools/tests/test_update_rules.py`).

## Triaging `rules/REVIEW.md`

The update-rules GitHub Action runs this script weekly (and on demand) and, if
anything changed, opens or updates a PR whose description is `rules/REVIEW.md`. It has
three sections a maintainer should work through before merging:

- **(a) Upstream mods not yet tracked** — mods Fabulously Optimized or Additive ship
  that aren't in `knowledge.json` yet. For each: consider whether it's worth adding as
  a `ModRule` (the "optimization category" column is a hint, not a verdict — plenty of
  cosmetic/QoL mods are also worth recommending, and not everything tagged
  "optimization" fits RigTune's scope). If you decide **not** to add one, record that
  decision in `knowledge.json`'s top-level `reviewIgnore` array (`{"slug": "...",
  "reason": "..."}`) so it stops showing up in this section every week. `reviewIgnore`
  is maintainer bookkeeping only — the updater strips it out of section (a) but never
  writes it into `rules-v1.json`.
- **(b) Rule mods needing a status check** — a tracked mod's Modrinth project isn't
  `approved`/`unlisted` (e.g. archived, rejected, taken down), or it used to be shipped
  by Fabulously Optimized or Additive and no longer is by either. Investigate whether
  the mod should move to `obsolete` or be removed from `knowledge.json` outright.
- **(c) Rule mods with no Fabric release for the newest target version** — a tracked
  mod hasn't published a build for the newest MC version RigTune targets yet. Usually
  means "wait for upstream," sometimes means "the project is dead, reconsider it."

None of this auto-applies: `knowledge.json` is hand-edited, and the generated
`rules-v1.json` only reflects what's already there. REVIEW.md is a punch list for the
*next* edit to `knowledge.json`, not something the script acts on itself.

## Tests

```
python -m unittest discover -s tools/tests -v
```

No network access is used or required; `tools/tests/fixtures/` holds small, hand-written
sample knowledge/rules files for the tests to load.
