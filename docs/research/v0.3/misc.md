# v0.3 research: misc (P1 #9-10, P2 #11-12)

Research only, no code changes. All web sources accessed 2026-09-26 unless noted. Anything not independently confirmed is marked **UNVERIFIED**.

---

## A. "Report a problem" (P1 item 10)

### A.1 GitHub new-issue URL prefill

Confirmed query parameters for `https://github.com/<owner>/<repo>/issues/new` (classic, non-template): `title`, `body`, `labels` (comma-separated), `assignees` (comma-separated), `milestone`, `projects`, `template`.
Source: [GitHub Docs — Creating an issue, "Creating an issue from a URL query"](https://docs.github.com/en/issues/tracking-your-work-with-issues/using-issues/creating-an-issue#creating-an-issue-from-a-url-query), accessed 2026-09-26.

- **Bad/unpermitted values**: "If you create an invalid URL using query parameters, or if you don't have the proper permissions, the URL will return a `404 Not Found` error page." Same docs page. So a `labels=` value naming a label that doesn't exist in `chaotix345/rigtune`, or an `assignees=`/`projects=` value the anonymous visitor can't set, fails the whole prefill (404), not a silent drop. **Recommendation: only ever send `title` + `body` (+ `template` if we add one) — never `labels`/`assignees`, since RigTune can't guarantee the visiting user has permission and a wrong/missing label 404s the entire link.**
- **URL length**: no documented exact byte limit for GitHub's side; docs only say "If you create a URL that exceeds the server limit, the URL will return a `414 URI Too Long` error page" (same page). Community reports confirm there's no raise-able limit or workaround for long `body=` (GitHub Community Discussion #22946, accessed 2026-09-26: "There's no direct workaround for using URL parameters that are too long"; suggested alternatives are the REST API or issue templates, neither fits a "no telemetry, open in browser" flow).
- **Practical safe ceiling**: independent of GitHub's own 414 threshold, browsers/Windows impose their own ceilings. Multiple browser-length surveys agree ~2,048 chars is the cross-browser/cross-server/proxy-safe boundary, while Chrome itself tolerates far more (~2 MB) (GeeksforGeeks / Baeldung / general 2026 URL-length surveys, accessed 2026-09-26). Windows `ShellExecute`'s exact limit was **not independently confirmed** in this pass — treat as **UNVERIFIED**; the practical constraint is GitHub's own 414 plus the 2,048-char cross-platform safe line, whichever is hit first.
- **Recommended truncation strategy**: build the `body=` from `ShareReport.format(...)` but cap it well under 2,048 total URL chars (title + fixed GitHub boilerplate + body), e.g. truncate the report to ~1,500 chars using the report's own `hardCut`/line-boundary logic (see A.4), append a line like "Report truncated — paste the full report below" and a "Copy full report" button that writes the untruncated text to the clipboard (Minecraft already has clipboard support via `Screen`/`Minecraft` GUI clipboard calls used elsewhere in the codebase for "Copy report"). This mirrors the existing `ShareReport.DISCORD_LIMIT` pattern but needs a smaller limit for the URL case.

### A.2 Opening a URL safely in MC 26.2/26.3 — verified via javap on both client jars

Jars used: `net/minecraft/minecraft-clientonly-deobf-{26.2,26.3}.jar` and `-common-deobf-` under `C:\Users\Admin\.gradle\caches\fabric-loom\minecraftMaven\net\minecraft\`.

**Real API difference found between versions** (not previously documented anywhere I could find — discovered by decompiling):
- **26.2**: the actual URI-open call is `net.minecraft.util.Util$OS.openUri(java.net.URI)`, reached as `Util.getPlatform().openUri(uri)`. `Util$OS` has public `openUri(URI)`, `openUri(String)`, `openFile(File)`, `openPath(Path)`.
- **26.3**: `Util$OS` **no longer has `openUri`/`openFile`/`openPath`/`getOpenUriArguments` at all** (javap shows only `values()`, `valueOf()`, `telemetryName()`). The real open call moved to `com.mojang.blaze3d.Blaze3D.openUri(java.net.URI)` (confirmed by disassembling `Screen$$lambda$clickUrlAction$0` and `ConfirmLinkScreen$$lambda$confirmLinkNow$0` bytecode with `javap -c`).
- **Do not call either of these directly** — they're internal, version-fragile, and skip the confirm dialog.

**The stable, version-safe API is `ConfirmLinkScreen`** (`net.minecraft.client.gui.screens.ConfirmLinkScreen`, package unchanged across both versions):
```
public static void confirmLinkNow(Screen screen, URI uri)                 // present, identical signature, in 26.2 AND 26.3
public static void confirmLinkNow(Screen screen, URI uri, boolean showWarning)
```
Decompiling `confirmLinkNow(Screen, URI)` (both versions) shows it unconditionally calls `Minecraft.getInstance().gui.setScreen(new ConfirmLinkScreen(...))` — i.e. it **always** shows the confirm-before-open screen (unlike `Screen.clickUrlAction`, which is gated by the `chatLinks`/`chatLinksPrompt` options and is for clickable chat text, not a button click). Only on the user's confirmation does the callback invoke the version-specific `Util$OS.openUri`/`Blaze3D.openUri`.

**Version difference that matters for RigTune's code**: 26.2 additionally has `String`-based overloads (`confirmLinkNow(Screen, String, boolean)`, `confirmLink(Screen, String, ...)`); **26.3 removed all `String` overloads — only `URI` overloads remain**. **Recommendation: use `ConfirmLinkScreen.confirmLinkNow(screen, uri)` with a `java.net.URI` built via `URI.create(...)` (or `Util.parseAndValidateUntrustedUri` — present in both versions' `Util` class) — this single call compiles and behaves identically on both 26.2 and 26.3**, and gives the "user reviews and submits themselves" confirm step the task asks for, for free.

### A.3 Issue templates

`.github/` currently has only `workflows/` (`build.yml`, `release.yml`, `update-rules.yml`) — **no `.github/ISSUE_TEMPLATE/` directory exists today**.

GitHub's YAML issue-form templates (`.github/ISSUE_TEMPLATE/*.yml`) **can** be prefilled: give a form field a stable `id:`, then pass `?template=<file>.yml&<id>=<url-encoded value>` — "the `id` is the identifier for form elements... if provided, the id is the canonical identifier for the field in URL query parameter prefills" (GitHub Docs, "Syntax for GitHub's form schema", accessed 2026-09-26; example confirmed: `.../issues/new?template=my_template.yaml&field1=some+pre-filled+value`).

**Recommendation**: add one issue form, `.github/ISSUE_TEMPLATE/bug_report.yml`, with a `type: textarea` field `id: report` pre-populated from the button (`&template=bug_report.yml&report=<encoded ShareReport text>&title=...`), plus a short "what happened / what did you expect" free-text field the user fills in themselves. This is strictly better than the current no-template state: it guarantees a consistent structure and doesn't need `labels=` in the URL (labels can be set as template defaults in the YAML's `labels:` key instead, which does not 404 since it's not a user-permission-gated query param).

### A.4 Share report content / scrubbing

`src/main/java/io/github/chaotix345/rigtune/core/report/ShareReport.java`:
- `ShareReport.java:22` `DISCORD_LIMIT = 2000` (current default cap, tuned for Discord, too high for a URL-embedded body per A.1).
- `ShareReport.java:24-31,211-217` `scrub()` — regex-strips anything path-shaped (drive/home/root-relative Windows and POSIX paths, UNC/backslash paths) and replaces with the literal `(path)`. This is where a Windows username (`C:\Users\<name>\...`) would otherwise leak, and it's covered.
- `ShareReport.java:221-226,228-241` `field()` — clips every free-text field (CPU/GPU names, mod titles, rule source, etc.) to 120 chars, scrubs paths, then `escape()` neutralizes Markdown control chars and inserts a zero-width space after `@` so a maliciously-named mod (e.g. `"*@everyone*"`) can't format the message or ping in a Discord paste.
- Per the file's own header comment (`ShareReport.java:19-20`): "Only what the report shows about the machine and the suggestions' titles: no reasons, no file paths, no user or world names."
- **Verdict: safe for a public GitHub issue body as-is** — no absolute paths, no OS username, no world/save names, pings/Markdown neutralized. The only thing to double check before shipping the "Report a problem" feature is that nothing new added to the report later (e.g. a future field) bypasses `field()`/`scrub()`. No changes needed for v0.3 as researched.

---

## B. Quilt (P2 item 11)

- **Quilt Loader**: `meta.quiltmc.org/v3/versions/game` lists both `26.2` and `26.3` as `"stable": true` (accessed 2026-09-26) — the loader itself claims version support for both.
- **QSL / QFAPI status — critical finding**: Quilt Standard Libraries, Quilt Kotlin Libraries, and **Quilted Fabric API were all retired as of Minecraft 26.1** — QuiltMC's own blog post, "Bringing Quilt into the non-obfuscated era" (quiltmc.org/en/blog/2026-02-03-non-obfuscated-updates/, accessed 2026-09-26): maintenance "fell on one sole developer... unable to commit enough time," and QSL's incompatible design vs. Fabric API "has proven to be extremely difficult to maintain long-term... QFAPI is confusing for users and cumbersome for developers, leading to slow updates." QSL/QFAPI remain published for older MC versions only (Modrinth `qsl` project) — **there is no QFAPI/QSL build for 26.2 or 26.3**, confirmed by absence on Modrinth's `qsl` version list (accessed 2026-09-26) and the blog post's own framing of the retirement as tied to keeping up with new MC releases.
- A live server-side bug, `QuiltMC/quilt-loader#509` (opened 2026-09-19, closed by the time of this check): Quilt server installs on 26.3 got a 404 hitting `meta.quiltmc.org/v3/versions/loader/26.3`; the reporter noted the **Quilt client on 26.3 worked fine** — i.e. even the loader's own server tooling had a live, version-specific rough edge days before this research (2026-09-26), separate from the QSL question.
- **Does RigTune's Fabric API usage work under Quilt?** `fabric.mod.json:35-40` requires `"fabric-api": "*"`, and the code imports real `net.fabricmc.fabric.api.*` classes directly (`client/event/lifecycle/v1`, `client/keymapping/v1`, `client/rendering/v1/hud`, `client/screen/v1`, plus gametest-only `client/gametest/v1`) — see `build.gradle:53` and the client source set. None of this is QSL/QFAPI API; it's the real FabricMC `fabric-api` artifact. Whether Quilt Loader can now load the **real** `fabric-api` mod directly (post-QFAPI-retirement) instead of needing the discontinued QFAPI shim was **not confirmed** by the blog post or FAQ fetched in this pass — **UNVERIFIED**, and it's the single fact that decides this question either way.
- **Recommendation**: **do not declare Quilt support for v0.3.** The QSL/QFAPI retirement at 26.1 removes the only previously-known compatibility path for a `fabric-api`-dependent mod on Quilt for exactly RigTune's target versions (26.2/26.3), and Quilt Loader's own tooling had an active 26.3 rough edge as of last week. A real verification would need: (1) confirming from QuiltMC directly (Discord/GitHub) whether Quilt Loader 26.2/26.3 can load the unmodified FabricMC `fabric-api` jar without QFAPI, and if so which loader version introduced that; (2) an actual smoke test — install Quilt Loader + real `fabric-api` + the RigTune jar into a 26.2 and a 26.3 instance and check `FabricLoader.getInstance().isModLoaded(...)` / the `preLaunch` entrypoint actually fires (`fabric.mod.json:19-20`, `RigTunePreLaunch`). Revisit once (1) is answered.

---

## C. spark (P2 item 12)

- **Mod id**: `"spark"` — confirmed by fetching spark's own `spark-fabric/src/main/resources/fabric.mod.json` from `github.com/lucko/spark` (accessed 2026-09-26): `"id": "spark"`, entrypoints `main: me.lucko.spark.fabric.FabricSparkMod`, `client: me.lucko.spark.fabric.FabricSparkMod::initializeClient`; depends on `minecraft >=26.1`, `fabricloader >=0.18.4`, `fabric-api-base`, `fabric-command-api-v2`, `fabric-lifecycle-events-v1`, `fabric-permissions-api-v0`. **Detect with `FabricLoader.getInstance().isModLoaded("spark")`** — same pattern RigTune already uses for optional Distant Horizons/Iris compat (`client/compat/OptionalMods.java`).
- **Client profiler commands** (spark docs, `spark.lucko.me/docs/Command-Usage`, accessed 2026-09-26): on a Fabric **client**, the command is `/sparkc` (not `/spark`).
  - `/sparkc profiler start` — begin sampling.
  - `/sparkc profiler start --timeout <seconds>` — auto-stop after N seconds.
  - `/sparkc profiler start --thread *` / `--thread <name>` — which thread(s) to sample (client render thread vs. others).
  - `/sparkc profiler start --only-ticks-over <ms>` — filter to slow ticks only.
  - `/sparkc profiler start --alloc` — memory/allocation profiling instead of CPU.
  - `/sparkc profiler stop` — stop and upload; the profile is "automatically uploaded to the viewer, and you will be presented with a link" (spark docs, `Using-the-viewer`, accessed 2026-09-26) — i.e. **the web viewer link requires a network upload to spark's server**, which is a real telemetry/privacy consideration if RigTune ever surfaced a "run spark for me" button (it would not be "no telemetry" unless the user explicitly triggers it and is told data leaves the machine).
  - `/sparkc profiler open` — view the in-progress profile without stopping it.
  - `/sparkc profiler cancel` — stop without uploading.
- **5 tips for reading a client profile for FPS problems** (spark docs `Using-the-viewer`, plus corroborating third-party "how to read a spark report" guides, accessed 2026-09-26 — flagging the guides as secondary sources):
  1. **Width = time** in the flame graph; each node's percentage is time share — start at the top thread node and repeatedly expand the single widest child until you hit a leaf that names a concrete subsystem.
  2. On a client, look at the **render thread** first for FPS-specific stalls (as opposed to a dedicated server's "Server thread") — spark's own docs focus on server threads by name but the same width/percentage method applies per-thread on client.
  3. Use `--thread *` if a suspected stall is off the main/render thread (e.g. chunk-building worker threads, network thread) — single-thread profiling by default will miss those.
  4. **Chunk building/loading** shows up as wide nodes under chunk-task-executor-style frames; if that's a large fraction of frame time, the fix is usually render/simulation distance, not code — directly relevant to RigTune's own render-distance tuning.
  5. **GC pauses**: heavy/megamorphic allocation shows as GC time in the profile or as visible frame-time spikes; spark's health/GC reporting flags this as a RAM-allocation or excessive-object-churn signal — cross-check against RigTune's own heap/RAM readout in `ShareReport.hardware()` (`ShareReport.java:93`, "heap … GB").
  6. Re-run with `--only-ticks-over`/a timeout focused on the exact reproduction window rather than a long open-ended capture — keeps the flame graph readable and avoids diluting the hot path with idle time.

---

## D. Localisation (P1 item 9)

### D.1 Conventions
- Format: one flat JSON object per locale at `assets/<namespace>/lang/<locale>.json`; RigTune's is `src/main/resources/assets/rigtune/lang/en_us.json`. Confirmed: "Language files are located by assets/[namespace]/lang/[locale].json... the US English translation... would be assets/examplemod/lang/en_us.json" (Fabric Wiki, `wiki.fabricmc.net/tutorial:lang`, accessed 2026-09-26).
- Placeholders: `%s` in order, or `%1$s`/`%2$s` to fix positional order independent of source order — "since... the order of variables may change [between languages]" (same Fabric wiki page). RigTune already uses plain `%s` throughout (e.g. `rigtune.toast.title`: `"RigTune: %s suggestions"`).
- Fallback: confirmed by Minecraft Wiki (`minecraft.wiki/w/En_us.json`, accessed 2026-09-26) — "Language files are merged with other selected packs, so any names that are not present are loaded from packs of lesser priority," i.e. any key missing from the active locale (or an entirely absent locale file) falls back to `en_us.json`.
- Locale code list: the canonical source is the Minecraft Wiki "Language" page's locale table (Java Edition column, e.g. `en_us`, `de_de`, `fr_fr`) — `minecraft.wiki/w/Language`, accessed 2026-09-26.
- Contribution model: a translator PRs a new `assets/rigtune/lang/<locale>.json` with the same key set as `en_us.json` — this is the de facto standard across Fabric mods (not itself independently sourced beyond the file-location convention above; treat as **UNVERIFIED-but-standard**).

### D.2 Current state
- `en_us.json` has **185 keys** (`src/main/resources/assets/rigtune/lang/en_us.json`, counted via `json.load`).
- Code already uses `Component.translatable(...)` **158 times** across 10 client files — the codebase is disciplined.
- **Hard-coded `Component.literal("...")` offenders — only 4 total, and all are legitimate**:
  - `RigTuneScreen.java:213` — `Component.literal(" · ")`, a punctuation separator, no translatable text.
  - `RigTuneScreen.java:441` — `Component.literal("  " + count)`, a numeric badge, no literal words.
  - `UndoScreen.java:110` — `Component.literal("  " + items.size())`, same pattern.
  - `gametest/StubController.java:56` — `"Stub: " + n + " change(s) would be applied."` — **test-only** code (`src/gametest`), never shipped in the jar.
  - No offending `Button.builder(Component.literal(...))` or `Tooltip.create(Component.literal(...))` calls found anywhere.
- `String.format(...)` of UI-adjacent text (5 call sites, all in `src/client`) are all **numeric formatting only**, feeding into an already-translatable string as a `%s` arg, not user-facing language text themselves: `BenchmarkWorld.java:296` (a `/tp` command string, not UI), `RigTuneScreen.java:272` (`"%.1f GB"`), `BenchmarkResultScreen.java:115,122,216` (`"%.1f"`/`"%.0f%%"`). None are offenders.
- **Real dead-key finding**: `rigtune.screen.benchmark` ("Run benchmark") and `rigtune.screen.benchmark.needs_world` (`en_us.json:12-13`) are **orphaned** — no code references them; the actual button uses `rigtune.screen.benchmark_menu` (`RigTuneScreen.java:133`, `"Benchmark…"`). These look like leftovers from before the benchmark button became a menu. Candidates to delete.
- **Dynamic key construction — a real complication for any unused-key checker** (D.3): several key families are built at runtime as `"rigtune.<prefix>." + enum.name().toLowerCase(Locale.ROOT)` rather than referenced as string literals: `rigtune.goal.*`/`*.tooltip` (`RigTuneScreen.java:102,104`, `RigTuneSettingsScreen.java:80,82`), `rigtune.category.*` (`RigTuneScreen.java:440`), `rigtune.impact.*` (`RigTuneScreen.java:478`), `rigtune.limit.*` (`RigTuneScreen.java:188`), `rigtune.benchmark.scene.*` (`RigTuneScreen.java`/`BenchmarkMenuScreen.java:129`/`BenchmarkResultScreen.java:234`), `rigtune.benchmark.menu.scene.*.hint` (`BenchmarkMenuScreen.java:141`), and `rigtune.benchmark.{cancelled,throttled}` base + `.title`/`.body` suffixes (`BenchmarkController.java:513-538`). Also `key.category.rigtune.rigtune` is never a Java string literal at all — it's auto-derived by Minecraft from `KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "rigtune"))` (`RigTuneClient.java:71`) as `key.category.<namespace>.<path>`. A naive literal-string scan flags all of these as "unused," which they are not.

### D.3 Proposed automated checks
- **Hard-coded-UI-string check**: a small unit test (JUnit, run in `test`, already wired to `check` via `tasks.named("check")`, `build.gradle:109`) that scans `src/client/java/**/*.java` (and `src/main` where UI-adjacent) with a regex for `Component\.literal\(\s*"` and `Component\.literal\(\s*[A-Za-z_]+\s*\+\s*"` and fails if a match isn't on an explicit allowlist (file:line or a `// literal-ok:` marker comment). Given the current code style (no reflection/bytecode tricks, plain `Component.literal`/`Component.translatable` calls, consistent naming), a **regex/text-scan test is robust enough here** — an ArchUnit/bytecode approach would add a new dependency for no real gain, since the offending pattern is a specific static method call on **source** text, and the 4 existing exceptions are simple to allowlist by file:line. Recommend the plain-text/regex unit test.
- **Key-usage check (used-but-missing / defined-but-unused)**: also feasible as a regex-based JUnit test, but must special-case the **dynamic-prefix families** found in D.2 (`goal.`, `category.`, `impact.`, `limit.`, `benchmark.scene.`, `benchmark.menu.scene.*.hint`, `benchmark.{cancelled,throttled}.{title,body}`, and `key.category.<namespace>.<path>` from `KeyMapping.Category`) — either by enumerating the finite enum values at the call sites (Goal, Category, Impact, the two Scene enums) and generating the expected concrete keys, or by an explicit prefix allowlist that suppresses "unused" for keys matching those prefixes while still catching genuinely dead keys like `rigtune.screen.benchmark`. A pure "grep for the literal key string" check (no dynamic-prefix handling) would have produced 20+ false "unused" positives in this exact codebase today (see D.2) — **not robust as a naive regex; needs the enum-driven or prefix-allowlist refinement to be trustworthy.**

### D.4 Adding a language
- **Feasible for v0.3, recommend yes**, with a verification method since neither of us reads the target language natively: (1) an independent agent (or reviewer) translates `en_us.json` → `<locale>.json`; (2) a **second, separate** independent agent back-translates `<locale>.json` → English with no sight of the original; (3) diff the back-translation against the original `en_us.json` values for semantic drift (not exact wording — tone/meaning), flagging any key where the back-translation changes the meaning (e.g. drops a negation, swaps a noun) for human review; (4) a mechanical check that the new file has **exactly the same 185 keys** as `en_us.json` (same key set, valid JSON, UTF-8, no leftover `%s`/`%1$s` count mismatch per key — placeholder count must match exactly since a translator can drop/duplicate a `%s`).
- **Recommended language**: German (`de_de`). Reasoning: Minecraft's Java Edition modding community skews heavily German/English (large existing precedent of German lang files across the Fabric mod ecosystem — **UNVERIFIED as a hard statistic**, based on general modding-community knowledge, not a source fetched in this pass), and German has strict, low-ambiguity grammar that makes the back-translation drift check in step (3) more reliable than a more context-dependent language (e.g. Japanese, which would need a second check for honorifics/formality drift). If a data-driven pick is wanted instead, Modrinth's own per-mod download-by-locale stats would need to be pulled per project — not done here.

---

**File written**: `docs/research/v0.3/misc.md` (this file). Uncommitted, per instructions.
