# WS-D: Distant Horizons + Iris settings

Code path for SPEC item 7 (P1): reading and patching `config/DistantHorizons.toml` and
`config/iris.properties` so `dh.*`/`iris.*` settings keys can flow through the existing
`ConfigTargets` → `Recommender` → stage → `PATCH_TOML`/`PATCH_PROPERTIES` pipeline, unchanged from
how Sodium's `PATCH_JSON` already works. Rule *content* (what DH/Iris keys to recommend and at what
values) is WS-H's job; this workstream is the mechanism only.

## Files

- `core/apply/TomlDocument.java` (new): a minimal TOML reader.
- `core/apply/TomlConfigPatcher.java`, `core/apply/PropertiesConfigPatcher.java`: replace the
  contract stubs. Same public shape as `SodiumConfigPatcher`: `readValues`, `patch`/`patchFile`,
  `stage` → `SodiumConfigPatcher.Staged`.
- `core/model/SettingKeys.java`: `dh.`/`iris.` namespaces added to `changeable()`.
- `client/probe/SettingsBridge.java`: `read()` now pulls every `ConfigTargets` namespace (sodium,
  dh, iris) through one `readTargets`/mtime-cache path, replacing the sodium-only line.
- Fixtures: `src/test/resources/dh/DistantHorizons.toml`, `src/test/resources/iris/iris.properties`
  — trimmed from the user's real, read-only files (settings only, nothing personal).

Nothing in `client/RealController.java` or `client/ConfigTargets.java` changed — `apply()` already
routes `dh.`/`iris.` keys through `ConfigTargets.forKey()` to these patchers' `stage()`.

## The TOML reader (`TomlDocument`)

DH's `DistantHorizons.toml` has one property that makes a naive editor dangerous (dh-iris.md §8.1):
**a bare token isn't necessarily the same Java type the field expects.** DH writes ints and bools
bare (`numberOfThreads = 8`, `disableShadowPassFrustumCulling = false`) but every double/float and
every enum as a *quoted string* (`threadRunTimeRatio = "1.0"`, `verticalQuality = "HIGH"`). A patcher
that infers quoting from the new value's own shape (`"looks like a number → write it bare"`) will
write `overdrawPrevention = -1.0` into a field DH's parser expects quoted, which the first code
review round confirmed independently (Critical 1): nothing stopped a rules typo from writing
`numberOfThreads = HIGH` or `disableShadowPassFrustumCulling = 1.5`, silently corrupting the field.

**The type-preservation rule, as shipped:** `TomlConfigPatcher.unsafe()` never infers a value's type
from the incoming string. It always looks at what's *already* in the file for that key
(`TomlDocument.Value.quoted()` and `.raw()`) and requires the new value to be the same kind:

- **Quoted key** → the new value just can't contain a `"` (would end the string early) or a `\`
  (this reader doesn't decode TOML string escapes, so an unescaped or misinterpreted backslash could
  either fail to parse or silently mean something other than what was written). Anything else is
  written back inside the same two quote characters.
- **Bare key** → DH's own writer only ever puts a plain boolean or a whole number there (confirmed
  from the user's live file, §8.1). `TomlDocument.recognize()` enforces this on the *read* side too:
  a bare token that isn't exactly `true`, `false`, or `-?\d+` is never turned into a `Value` at all —
  the key is treated as absent. That guarantee is what lets `unsafe()` on the *write* side safely
  assume `existingRaw` is always a recognised boolean or integer when `quoted` is false, and refuse a
  same-kind mismatch (`"HIGH"` → an int key, `"1.5"` → a whole-number key) with a clear message
  instead of writing something DH can't parse.

**Everything else `TomlDocument` refuses to guess at**, again always by *excluding the key* rather
than parsing it wrong: a single-quoted (TOML literal) string, an inline comment after a value that
survives comment-stripping in some unexpected shape, a key that appears more than once in the file
(picking neither the first nor the last occurrence — the original code review flagged that duplicate
keys crossing a mis-detected section boundary could let a patch for `a.x` silently land on `b.x`), and
any line that looks like a section header (`[...]`) but doesn't parse as one — which locks out every
key until the next real header, rather than silently keeping the previous section's keys open for
attribution. A `#`-comment is stripped quote-aware (a `#` inside a quoted string doesn't start a
comment) before a line is classified as a header or a value, so `[b] # note` is still recognised as
the header `b`, not folded into whatever section came before it.

Every key whose path has a segment starting with `_` (DH's own `_version` schema marker, and
anything future-`_`-prefixed) is excluded the same way — never exposed as a settable key at all.

**Patching is offset-based, not line-based.** `TomlDocument.Value` carries the `[start, end)`
character span of the *original* token (quotes included) in the source text. `TomlConfigPatcher.patch`
validates every requested key first, then splices each replacement directly into the original text via
`StringBuilder.replace`, applying edits from the highest offset down so an earlier edit's span is
never shifted by a later one. Nothing else in the file — indentation, comments, blank lines, or (since
this never reconstructs a line) each line's own line ending, even in a file with mixed CRLF/LF — is
touched. A single missing or unsafe key in a batch throws before any edit is applied, so `patchFile`
never partially writes a batch.

## Properties patcher

`iris.properties` is `java.util.Properties` end to end (`load`/`store` on an `InputStream`/
`OutputStream` are already ISO-8859-1 with `\uXXXX` escaping for anything outside it — nothing custom
needed). Same "never set a key that isn't already there" rule as TOML: Iris writes every key this
mod proposes as soon as it's run once, so a missing key means Iris hasn't saved that field yet.

A missing key or a null value throws/refuses with `IllegalArgumentException`, not `IOException` — an
earlier version of this code threw `IOException` for a missing key, which `ApplyExecutor.retrying()`
treats as transient and retries up to 10 times (~3 s) before giving up on something that was never
going to succeed. `IllegalArgumentException` fails immediately, matching how `TomlConfigPatcher`
already reports a missing/unsafe key. `patchFile`/`stage` also now distinguish "no such file" from
"couldn't parse this file as properties" (a malformed `\uXXXX` escape) instead of reporting every
unreadable file as missing, which had made the log message misleading for the latter case.

## SettingsBridge: reading every ConfigTargets namespace, cached by mtime

`SettingsBridge.read()` used to read sodium directly; it now loops `ConfigTargets.all(configDir)`
(sodium, dh, iris) through one `readTargets` path, prefixing each target's reader output with its
namespace. This is what lets `SettingKeys.changeable("dh....")`/`Recommender.settings()` ever see a
`dh.`/`iris.` key in the snapshot — without it, `snapshot.has(key)` is always false for those
namespaces and no DH/Iris `SettingRule` could ever fire, regardless of what WS-H puts in the rules.

`read()` runs on the render thread (`RealController.rebuild()`); DH's TOML can run past 1000 lines,
so re-parsing it on every rebuild would be real per-frame-adjacent cost. Each target's file is cached
by `Files.getLastModifiedTime`, re-parsed only when that changes. The cache also:
- re-checks the mtime *after* the read, not only before, and drops the cache entry (never caches)
  if the file changed during the read — a concurrent write (DH/Iris themselves, or this mod's own
  post-exit helper) could otherwise get cached as a possibly-torn read under a stale timestamp;
- never caches an empty read — every reader here returns `Map.of()` for a parse failure too, and a
  transient failure racing a concurrent write shouldn't stick around until the mtime happens to
  change again for an unrelated reason;
- stores `Map.copyOf(values)`, not the reader's own map, so a reader that reuses or later mutates its
  returned map can't corrupt what a later call serves from cache.

## Known gap, not fixed here (out of WS-D's file ownership)

`core/recommend/SettingValues.label()` prefixes a recommendation title with "Sodium " for
`sodium.*` keys but has no equivalent for `dh.`/`iris.` keys — a DH/Iris setting recommendation's
title currently reads as a bare field name with no mod context. `SettingValues.java` and
`Recommender.java` (titles) are WS-A's files (PLAN.md: "core/recommend/Recommender.java (requires,
labels in titles)"), not WS-D's, so this wasn't changed here. Flagging for WS-A/WS-H: add
`"Distant Horizons "` / `"Iris "` prefixes (or use `RulesDocument.settingLabels` once WS-H populates
it for these keys, which is the v2 mechanism already designed for exactly this).

## Verification

- `./gradlew build` green for both `:26.2` and `:26.3` (unit tests + game-test source compile).
  296 unit tests pass on each version. No game test was added — this workstream is pure file/string
  logic with no Minecraft or Fabric dependency, per the plan's game-test note.
- AC7.1 (TOML reader/patcher and properties patcher unit tests: quoted floats stay quoted, bare ints
  stay bare, section scoping, missing key refused, CRLF, comments): covered by
  `TomlDocumentTest`/`TomlConfigPatcherTest`/`PropertiesConfigPatcherTest`.
- AC7.2 (Recommender scenario tests for DH/Iris profiles): `RecommenderTest` has three tests
  exercising the full `dh.`/`iris.` key path (present + `modPresent` gating, a clamp, and "missing
  from the snapshot never recommends") against hand-written rules, since WS-H's real rule content
  isn't in `rules/source/knowledge.json` yet.
- AC7.3 (production smoke run with the user's real DH config): explicitly **not** verified by this
  workstream — it's Phase 5 (coordinator + WS-G), which runs against the merged integration build
  with a copy of the user's actual instance.
- UNVERIFIED by this workstream: real-world behaviour against DH's own parser (this reader was built
  and tested against a trimmed *copy* of the user's live file and the behaviour documented in
  docs/research/v0.2/dh-iris.md, never against a running DH instance — that's what AC7.3 covers).
