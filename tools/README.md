# RigTune rules pipeline

`tools/update_rules.py` regenerates, from one run:

- `rules/rules-v2.json` and its bundled copy `src/main/resources/rigtune/rules-v2.json` (what RigTune 0.2+ reads);
- `rules/rules-v1.json`, the **v1 projection** that 0.1.x clients download (0.2 doesn't bundle it);
- `rules/REVIEW.md`, a punch list for the maintainer.

Its inputs:

- `rules/source/knowledge.json` — the hand-maintained tuning knowledge (GPU/CPU/heap
  tiers, mod rules, obsolete mods, settings, advice, setting labels). Same shape as the
  schema in `docs/RULES_SCHEMA.md`, minus the fields the script generates, plus the
  maintainer-only `reviewIgnore` and per-rule `v1` fields.
- Live data: the Fabulously Optimized and Additive packwiz repos on GitHub, and the
  Modrinth API.

It requires only the Python 3.11+ standard library (no `pip install` needed).

## What it does

1. Loads and validates `knowledge.json` before any request (see "Knowledge errors").
2. Picks the target Minecraft versions: the newest 3 Modrinth `release`-type game
   versions, or whatever `--mc-versions 26.2,26.3` overrides it to.
3. For each target version, lists `Packwiz/{version}/mods/*.pw.toml` in
   `Fabulously-Optimized/fabulously-optimized` and
   `versions/fabric/{version}/mods/*.pw.toml` in `skywardmc/additive`, and reads each
   file's `[update.modrinth] mod-id`. A missing version folder (a pack that hasn't
   ported to a newer MC release yet) is treated as "this pack doesn't have this
   version" rather than an error.
4. Batches all the collected Modrinth project ids (both packs' upstream mods and every
   rule mod in `knowledge.json`) through `GET /v2/projects?ids=[...]`.
5. For each pack, uses the **newest target version that pack actually has** to decide
   which rule mods are "upstream" there (`mods[].upstream.fabulouslyOptimized` /
   `.additive`), and records the same pack's full slug list under the top-level
   `upstream` object.
6. Builds `availability[mcVersion]` per rule mod. A project's `game_versions`/
   `loaders` fields are unions across every version it has ever published, so
   they're used only as a cheap pre-filter (a mod that fails them is unavailable,
   no request needed). Anything that passes is confirmed with a per-version
   Modrinth query (`GET /project/{id}/version?loaders=["fabric"]&game_versions=["<mc>"]`);
   available means that query returns at least one version.
7. Builds the v2 document (the knowledge without the `v1` overrides) and the v1
   projection (below).
8. Diffs both against the existing `rules/rules-v2.json` and `rules/rules-v1.json`,
   **ignoring `revision` and `generatedAt`**. If neither changed, the files are left
   untouched. If either changed (or one doesn't exist yet), both get
   `revision = max(old revisions) + 1` and the same `generatedAt`, and all three files
   are written.
9. Writes `rules/REVIEW.md` — see below.

## The v1 projection and `v1` overrides

0.1.x must never get an unsafe recommendation from `rules-v1.json`. The projection works
per field, and `docs/RULES_SCHEMA.md` ("The v1 projection") has the full rules. In short,
for each rule in `knowledge.json`:

- `"v1": false` — leave the rule out of `rules-v1.json`.
- `"v1": { ... }` — shallow-merge these fields over the rule for `rules-v1.json` only.
  Use it to give 0.1.x a conservative version of a v2 condition, e.g. Nvidium's old
  tier-gated `recommendWhen` next to a v2 `gpuModelMatches`. Only v1 fields, only v1
  condition keys and values, and no `null` anywhere (0.1.x would read a null condition
  as "always").
- Without a `v1` key:
  - a v2-only `recommendWhen` or advice `when` becomes `{"always": false}`;
  - a v2-only `avoidWhen` is dropped, but only if 0.1.x never recommends the mod;
  - everything else that 0.1.x doesn't understand is an error (below).
- `v1` is never written to either output, and `settingLabels` never reaches `rules-v1.json`.

"v2-only" means a condition key 0.1.x doesn't know (`gpuModelMatches`,
`displayPixelsAtLeast`/`AtMost`, `modVersion`, `mcVersionRange`) anywhere in the tree, or
a value outside the v0.1.0 vocabularies.

### Knowledge errors

The script stops (exit code 2, nothing written) and lists every problem when
`knowledge.json` has:

- an unknown field on a rule (a typo) or on a tier rule (tier-rule changes need a new
  schemaVersion), an unknown condition key, a `null`, a value outside the vocabularies
  (`gpuVendor`, `backend`, `os`, `goal`, `flags`), or a `gpuModelMatches` over 200 characters;
- a setting entry that uses a v2 condition, or a key outside `vanilla.`/`sodium.`, with no
  `v1` (omitting a setting entry can change which entry wins for 0.1.x, so it's always your call);
- a rule with `requires` or `avoidSelected` and no `v1` (use `"v1": false` unless the field
  can be left out exactly as safely: an empty `requires`, `avoidSelected: true`, or no
  `avoidWhen` in the v1 rule);
- a v2-only `avoidWhen` on a mod 0.1.x can still be offered (add a v1 `avoidWhen` override);
- a bad `v1` override (a `null`, a non-v1 field or condition).

## Running it

```
python tools/update_rules.py                      # normal run, writes the real files
python tools/update_rules.py --dry-run             # compute + print a summary, write nothing
python tools/update_rules.py --mc-versions 26.2,26.3
python tools/update_rules.py --knowledge path/to/knowledge.json --out-dir path/to/scratch
python tools/update_rules.py --offline-fixtures path/to/fixtures   # no network; see below
python tools/check_rules_v1.py                     # offline: is rules-v1.json safe and in sync?
```

After regenerating, run `./gradlew build` (both MC versions): the scenario tests read the
bundled rules, and `RulesV1DifferentialTest` checks `rules/rules-v1.json`.

An optional `GITHUB_TOKEN` environment variable is used for the GitHub API calls
(higher rate limit); it isn't required. Requests carry a descriptive User-Agent, retry
429/5xx with backoff, and throttle Modrinth calls to stay well under its 300 req/min
limit.

`--offline-fixtures DIR` replaces all HTTP calls with reads from `DIR/<sha256(url)>.json`
files (`{"status": 200, "body": ..., "headers": {...}}`). It's meant for reproducing a
run without the network, not for the unit tests, which inject a fake fetch function
directly (see `tools/tests/test_update_rules.py`).

## Checks that guard rules-v1.json

- `tools/check_rules_v1.py` (CI job `rules-v1-compat`): `rules-v1.json` has
  schemaVersion 1; only the fields, condition keys and values 0.1.0 understands; only
  `vanilla.`/`sodium.` settings keys; no `requires`, `avoidSelected`, `settingLabels` or
  `v1`; the same revision and `generatedAt` as `rules-v2.json`; and it equals the
  projection rebuilt offline from `knowledge.json` and `rules-v2.json`'s generated data
  (so it also fails when `knowledge.json` was edited without regenerating).
- CI job `rules-consistency`: `rules/rules-v2.json` equals the bundled copy, and no bundled
  `rules-v1.json` exists.
- `RulesV1DifferentialTest` (JUnit, part of `./gradlew build`): a pinned copy of the
  v0.1.0 recommender (`src/test/java/io/github/chaotix345/rigtune/v010/`, copied from the
  tag with only the package renamed) evaluates the baseline
  `src/test/resources/v010/rules-v1-baseline.json` (the rules 0.1.0 shipped) and the new
  `rules/rules-v1.json` over a hardware × mods × settings × goal matrix. It fails on any
  ticked action (add, disable, setting value) that the new file gives and the baseline
  doesn't. Removing actions is fine. If a new ticked action for 0.1.x is intended (for
  example a new mod rule that 0.1.x should see), review the listed actions and copy
  `rules/rules-v1.json` over the baseline in the same commit.

## Triaging `rules/REVIEW.md`

The update-rules GitHub Action runs this script weekly (and on demand) and, if
anything changed, opens or updates a PR whose description is `rules/REVIEW.md`. It has
four sections a maintainer should work through before merging:

- **(a) Upstream mods not yet tracked** — mods Fabulously Optimized or Additive ship
  that aren't in `knowledge.json` yet. For each: consider whether it's worth adding as
  a `ModRule` (the "optimization category" column is a hint, not a verdict — plenty of
  cosmetic/QoL mods are also worth recommending, and not everything tagged
  "optimization" fits RigTune's scope). If you decide **not** to add one, record that
  decision in `knowledge.json`'s top-level `reviewIgnore` array (`{"slug": "...",
  "reason": "..."}`) so it stops showing up in this section every week. `reviewIgnore`
  is maintainer bookkeeping only — the updater strips it out of section (a) but never
  writes it into either output.
- **(b) Rule mods needing a status check** — a tracked mod's Modrinth project isn't
  `approved`/`unlisted` (e.g. archived, rejected, taken down), or it used to be shipped
  by Fabulously Optimized or Additive and no longer is by either. Investigate whether
  the mod should move to `obsolete` or be removed from `knowledge.json` outright.
- **(c) Rule mods with no Fabric release for the newest target version** — a tracked
  mod hasn't published a build for the newest MC version RigTune targets yet. Usually
  means "wait for upstream," sometimes means "the project is dead, reconsider it."
- **(d) Omitted from rules-v1.json** — every rule the v1 projection leaves out or
  changes (a `v1` override, `"v1": false`, a condition turned into `{"always": false}`,
  a dropped `avoidWhen`, a left-out field), with the reason. Check that none of it makes
  0.1.x less safe; in particular, when a setting entry is left out, check which entry
  now wins for that key in 0.1.x.

None of this auto-applies: `knowledge.json` is hand-edited, and the generated
files only reflect what's already there. REVIEW.md is a punch list for the
*next* edit to `knowledge.json`, not something the script acts on itself.

## Tests

```
python -m unittest discover -s tools/tests -v
```

No network access is used or required; `tools/tests/fixtures/` holds small, hand-written
sample knowledge/rules files for the tests to load.
