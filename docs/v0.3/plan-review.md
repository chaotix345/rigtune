# RigTune v0.3.0: spec and plan review

Scope: docs/v0.3/SPEC.md and docs/v0.3/PLAN.md (branch feat/v0.3.0 at e9d8ebf), checked against docs/research/v0.3/*.md, docs/DESIGN.md, docs/RULES_SCHEMA.md, docs/reviews/review-4.md, and the source each design claim touches. File:line references are to that commit. No code was run: no Gradle, no game.

**Result: 4 high, 25 medium, 11 low.** The four highs:
1. **A-H1**: 3b's "post-update view" would let an addition through on the strength of an update that may never apply, and the resolver doesn't have the data the design needs.
2. **B-H1**: per-entry undo has no rule for "a later entry changed it again". Undoing an older entry can silently revert a newer entry's update or override its staged patch.
3. **D-H1**: putting the RX 9070 GRE row in v1 fails RulesV1DifferentialTest by the test's own definition. The fix then either weakens the baseline or breaks AC7.2.
4. **G-H1**: the core `Text` type that Wave A is told to "use from the start" has no owner and no phase.

IDs: `W0` = WS-0 (foundation), `A`…`H`, `V`, `P` = workstreams, `X` = cross-cutting plan issues.

---

## WS-0: CI game tests, build and release (SPEC 2, item 1 A/B)

### W0-M1: A node without Sodium would turn the generated game-test matrix red
- **Problem.** Change A only makes `localRuntime` Sodium optional. The production game-test run the plan ports still requires Sodium, and so do three of the existing game-test classes. AC2.5 generates a leg for every `versions/*/` node. So the first node added without a Sodium build (the 26.4 situation, mc-versions.md §5.4) fails CI, which defeats "adding a version is one command".
- **Evidence.**
  - ci-gametests.md:51: `productionGameTestMods "maven.modrinth:sodium:${project.sodium_version}"` is unconditional.
  - ci-gametests.md:73: "Sodium and ModMenu are required. RigTuneClientGameTest, UndoGameTest and UiGameTest use them."
  - SPEC:20, :33, :36 (AC2.5).
- **Fix.**
  - In WS-0, make `productionGameTestMods` Sodium conditional on `sodium_version`, the same way as change A.
  - Either make the Sodium-specific game-test assertions skip when Sodium isn't loaded (log it), or have the matrix script leave out nodes without `sodium_version` and print a warning.
  - Add a test case for the matrix script covering a node without Sodium.

### W0-M2: The plan's WS-0 task list contradicts the spec and leaves out most of it
- **Problem.** PLAN:60 says "retries only for known native flakes". SPEC:35 and the research (ci-gametests.md §3.6) say "no automatic retries". PLAN:60 also says "a game-test job per MC version". It doesn't mention:
  - the matrix generated from `versions/*/` (AC2.5);
  - the extra Vulkan leg for 26.3 and later;
  - the `latest.log` backend check;
  - `SDL_OPENGL_FORCE_SRGB_FRAMEBUFFER`;
  - the ubuntu-24.04 pin;
  - the 20-minute step timeout.

  An agent working from PLAN would build the wrong job.
- **Evidence.** PLAN:60 against SPEC:32-36.
- **Fix.** Replace the PLAN:60 task text with "implement SPEC item 2 exactly (AC2.1-AC2.5)", list those six points, and delete "retries only for known native flakes".

### W0-M3: Re-running a partly failed release doesn't publish, and a rebuilt jar may not match the GitHub asset
- **Problem.** The brief asks for byte-identical GitHub and Modrinth jars. Today a re-run after a partial Modrinth failure can't finish the release:
  - "Re-run failed jobs" re-runs the whole job, including `gh release create`, which fails because the release already exists.
  - The Modrinth steps are gated on `steps.release.outcome == 'success'`, so they're skipped.
  - The workflow comment claims the opposite.
  - Even with that fixed, a re-run publishes a rebuilt jar, which is byte-identical to the GitHub asset only if the build is reproducible. Nobody has shown that.

  Change B (the publish loop) keeps this flaw.
- **Evidence.** release.yml "Create GitHub release" step (`gh release create "$tag"`) and the Modrinth steps' `if: ... steps.release.outcome == 'success'`. Comment above "Publish to Modrinth (26.2)". SPEC:21, :72, :73 (AC4.3).
- **Fix.**
  - In change B, make the release step idempotent: if the release exists, download its assets instead of creating it.
  - Publish those downloaded files to Modrinth (Minotaur `uploadFile` = the downloaded path), never a rebuilt jar.
  - Add to AC1.5: a dry run of the re-run path, plus a reproducibility check (two clean builds, compare sha256).

### W0-L1: CI job concurrency
- **Problem.** Each push starts 7 jobs (java, python, rules-consistency, rules-v1-compat and 3 game-test legs). With 8 Wave A branches pushing often, runs queue behind the account's concurrent-job limit, and superseded runs keep running.
- **Evidence.** build.yml jobs; PLAN:24 ("Push often (CI runs ... game tests on every push)").
- **Fix.** Add `concurrency: { group: build-${{ github.ref }}, cancel-in-progress: true }` to build.yml in WS-0. Tags and main keep their runs, since each ref has its own group.

---

## WS-A: deferred defects (SPEC 3a-3c, raw game version)

### A-H1: 3b's post-update view is unsafe as specified, and the data it needs isn't there
- **Problem 1: safety.** SPEC:48 judges an addition against "installed, with every update in this batch applied". But an update and an unrelated addition are separate all-or-nothing groups. The update can then fail or never run while the addition applies:
  - the helper fails it (the real 0.1.0 DH failure was exactly this);
  - it's abandoned after 3 tries;
  - 3a's `dropQueuedUpdates` drops it;
  - the user discards it or undoes it with "Undo this".

  Case (i) of AC3.2 (B declares A v1 incompatible, and A is updated to v2) then installs B next to A v1. That's the declared incompatibility review-4 rules-accuracy-2 was fixed to prevent.
- **Problem 2: order.** `DownloadPlanner.plan` handles recommendations in selection order. If B comes before A's update, B is still checked against the old view, so AC3.2(i) passes or fails depending on tick order.
- **Problem 3: data.**
  - Updates never go through the resolver: `updateMod` uses `update.update().file()` directly.
  - `UpdateInfo` has no dependency list, so case (iii) ("an update's own version declaring incompatible") can't be checked.
  - The resolver gets a flat set of version ids, not project → version, so "the old one no longer counts" can't be computed inside it.
  - For AC3.2(ii), the direction "an installed mod declares the updated version incompatible" isn't checked for additions either. Installed versions' dependencies aren't kept.
- **Evidence.**
  - DownloadPlanner.java:64-88 (selection order), :141-164 (updateMod: no resolver call; not added to `batch.versions`).
  - UpdateInfo.java:3 (no dependencies).
  - DependencyResolver.java:19, :40 (`Set<String> installedVersionIds`), :90-116 (checks only the found versions' dependencies against installed and batch).
  - RealController.java:435, :481 (flat set passed in).
  - OnlineDataFetcher.java:26, :70 (the update's `ModrinthVersion`, dependencies included, is reduced to `UpdateInfo`).
- **Fix (spec text for 3b).**
  1. `DownloadPlanner.plan` handles every UpdateMod before any AddMod.
  2. `OnlineDataFetcher.Result` also keeps each update's `ModrinthVersion` (with its dependencies) and a versionId → projectId map. `UpdateInfo` stays unchanged: the e2e driver reads it.
  3. Each successfully staged update enters `batch.versions`. Its dependencies are checked against installed projects and the batch, both ways.
  4. An addition is judged against the post-update view only through updates committed earlier in this batch. If it would have been refused against the pre-update view, it **joins that update's group**, so both apply or neither does. 3a's group drop then removes both.
  5. Rewrite AC3.2(ii) to say B is being added in the batch. Record "an installed mod's own `incompatible` entry against an update target" as a known gap, or cover it using the dependencies of the installed versions from `versionsByHashes`.
  6. Add AC tests: tick order doesn't matter; a failed update download refuses the dependent addition; the two groups are joined.

### A-M1: 3a's "recompute staged ids from pending.json" can't be done: pending.json has no recommendation ids
- **Problem.** The two sides use different kinds of id:
  - `staged` holds recommendation ids ("update:x", "add:slug").
  - pending.json and the journal hold only op ids and groups.
  - merge rewrites op ids: a repeated change keeps the existing op's id.

  So AC3.1's last clause ("the staged ids after a drop equal the ids still in pending.json") compares two kinds of id and can't be tested as written. The undo path wipes `staged` completely.
- **Evidence.**
  - RealController.java:88, :517-523 (`staged.addAll(ids)`), :303-317, :620-621.
  - JournalChange.java:9-10 (no recommendation id).
  - PendingActions.java:149-158 and :194-199 (op ids survive merges only through `survivingIds`).
  - Staging.java:76-95 (returns only a boolean).
- **Fix.**
  - `Staging.stage` returns the `Merge` (or its `survivingIds`).
  - RealController keeps a map from recommendation id to the surviving op ids, built at staging.
  - After any drop, undo or discard, a recommendation id stays staged only while one of its op ids is still in pending.json. Ops carried over from another session stay in `carriedOverOps`.
  - AC3.1: "for every recommendation id in `staged`, at least one of its recorded op ids is in pending.json, and none of the dropped group's recommendation ids remain", tested against a fake Staging.

### A-M2: 3c relies on a version lookup the client doesn't have
- **Problem.** SPEC:52 says the project title comes "from the version lookup". `ModrinthClient` has no get-version-by-id call. `name()` passes a version id to `projects()`, finds nothing and falls back to the raw id. That's today's bug. An agent may add a `/version/{id}` endpoint, which touches HttpModrinthClient, GatedModrinthClient, every fake in the tests and the e2e FakeModrinth, and adds a network call.
- **Evidence.** ModrinthClient.java:12-22; DependencyResolver.java:101-102, :127-137.
- **Fix.** Name the project from local data, with no new endpoint:
  - for an installed version, from the versionId → projectId map in A-H1 step 2 (from `versionsByHashes`);
  - for a version in the batch, from `ModrinthVersion.projectId()`.

  Then use the project title, slug, then "another mod". Say "no new Modrinth call" in the spec.

### A-L1: Loose ends in 3a and the raw game version
- **3a notice wording.** Under 3a's rule, an undo that re-enables a *loaded* mod with a queued update is also dropped, but the status text says "Cancelled RigTune's pending update of X". Make the message say "change" rather than "update", or give undos their own key.
- **Raw game version.**
  - `OnlineLookupGate` builds the lookup from `hardware().mcVersion()` (RealController.java:233-237), and WS-A doesn't own OnlineLookupGate. Add it.
  - `Recommender`'s offline availability map is keyed by Modrinth tags but looked up by the normalized version. It misses on snapshots only; record that.
  - "A unit test that the Modrinth query uses the raw id" needs a seam in RealController. Name it.

---

## WS-B: History screen, per-entry undo, failed-op reasons (SPEC 6, 3e)

### B-H1: Per-entry undo has no rule for "changed again by a later entry"
- **Problem.** SPEC:89 promises that a change a later entry changed again is skipped. Reusing `plan()`'s selection (PLAN:72) doesn't give that. `build()` compares only the current file or value against the chosen entry's own changes:
  - **Same-name update chains** (supported: review M6; `updateMod` allows the target name to equal the current jar).
    - Apply 1 updates `mod.jar` from v1 to v2 (v1 → `mod.jar.disabled`). Apply 2 updates v2 to v3 (v2 → `.disabled.1`).
    - "Undo this" on Apply 1: `move()` takes the jar now named `mod.jar`, which is **v3**, disables it, and re-enables v1.
    - The folder check passes. Apply 2's changes stay APPLIED in the journal while its jar is disabled.
  - **Staged config patches.**
    - Apply 1's Sodium key is applied. Apply 2 stages a different value for the same key.
    - "Undo this" on Apply 1 stages a patch back to Apply 1's `before`. Merge keeps both ops, since the values differ.
    - The helper runs Apply 2's group first, then the undo's. The file ends at the old value, but Apply 2's change is marked APPLIED.
  - **Vanilla settings** are skipped with a misleading reason ("You changed it since").

  `recheck()` re-plans by change id only. A check done only in `planEntry` would therefore be skipped at confirm time, for example when a download finished in between and added to the later entry.

  "Undo last" can reach the same case today, because `isEmpty()` treats a plan with only skips as empty and falls through to an older entry.
- **Evidence.**
  - UndoPlanner.java:118-131 (selection; falls through when a plan is all skips), :138-164 (recheck), :252-265 (build), :350-356 (value check), :493-518 (`move()` identifies files by name).
  - UndoPlan.java:49-51.
  - PendingActions.java:149-158, :179 (different-value patches both kept, appended).
  - DownloadPlanner.java:151.
  - DESIGN.md:184 (groups run in the order of their first op).
- **Fix.**
  - Add a "superseded" pass inside `build()`, so it also runs in `recheck`. It's a no-op for Undo last and Undo everything.
  - A selected change is SKIP ("Changed again by a later apply") when any **later, non-undo** entry has a change that is **STAGED, or APPLIED and not being reverted**, on:
    - the same settings key;
    - the same `file` or `resultFile` name, in either direction;
    - or an enable with the same `modId`.
  - Skip the whole group with it.
  - Add AC6.1 cases for each example above: same-name chain, staged config patch after an applied one, and a later entry that has itself been undone (the older one can be undone again).

### B-M1: AC3.5's fixture has no failure in it; WARN repetition and attempt numbers are undefined
- **Problem.**
  - The captured 0.1.0 `last-apply.json` contains only OK results, so it can't produce any WARN line. The one FAILED fixture is the hand-written `src/test/resources/v010/last-apply.json`.
  - `last-apply.json` stays on disk until the next helper run, so "at startup ... logs one WARN line per failed op" repeats every launch, about old failures.
  - The op recorded in it holds the attempt count from *before* the run, so "attempt n of 3" is n = attempts + 1.
- **Evidence.** src/test/resources/v010/captured/last-apply.json (2 OK results); ApplyExecutor.java:178, :214; SPEC:61-62; PROGRESS.md "Real-world feedback" (the user's real FAILED DH group, attempts 1/3).
- **Fix.**
  - Capture the user's real 0.1.0 `last-apply.json` (read-only, templated like `captured/`). It holds the DH failure.
  - Log the WARN lines once per `finishedAt`, remembered next to `lastShownApply`.
  - Define n = `attempts + 1`.
  - Show the reason for ABANDONED changes too ("Not applied: ...").

### B-M2: The History screen needs a history.json state that the Journal doesn't expose, and Journal runs in the helper
- **Problem.** `Journal.entries()` returns an empty list for missing, corrupt and newer files alike. Only `readOnly()` tells NEWER apart. SPEC:90's separate empty, corrupt and newer messages need a public state. The `.bad` backup is made only on the next `update()`, not when the file is read.

  Journal.java is on the helper path, and WS-B's ownership list doesn't include it.
- **Evidence.** Journal.java:90-107, :137; PLAN:71.
- **Fix.**
  - Give WS-B Journal.java for **one read-only accessor** (`state()`), helper-safe.
  - HelperLauncherTest must still pass.
  - The corrupt message says a backup "will be kept when RigTune next writes the history", not that one was made.

### B-M3: The restart path of per-entry undo isn't tested end to end
- **Problem.** The new risk is mod-file reversal of an **older** entry, applied by the helper after a restart. AC4.2 re-runs only the existing undo-after-restart scenario, which is "Undo last". HistoryGameTest can't cover the restart.
- **Evidence.** SPEC:73 (AC4.2), :91 (AC6.3); UndoDriver.java:188-220 (Undo last only).
- **Fix.** Add a per-entry case to the e2eUndo driver and to AC4.2:
  1. Two applies, each adding a mod.
  2. "Undo this" on the older one, then restart.
  3. Check that only the older mod is disabled and that both journal entries are right.

### B-L1: Details the History screen leaves open
- "Isn't fully undone" isn't defined. `ctx.undone` marks an entry undone once any undo change on it took effect, even with leftover changes that can still be undone. Define it as "has changes that can still be undone".
- "Undo last" includes legacy-import entries, but "Undo this" excludes them. Pick one.
- An update shows as "Disabled a.jar" plus "Added b.jar". Pair a disable and an enable with the same group and `modId` into "Updated <mod>".
- AC6.4 needs LangCheckTest, which only exists after Wave B. Move AC6.4 to WS-G's verification.

---

## WS-C: launcher-aware RAM advice (SPEC 5)

### C-M1: The bounded reader isn't specified enough to be safe or to work on real files
- **Problem 1: which files.** "Bounded" and "oversized → no value" don't say which files may be opened. Non-regular files are the real hazard: a FIFO named `instance.cfg` blocks the read forever on Linux.
- **Problem 2: file size.** `minecraftinstance.json` lists metadata for every installed addon, so real files for big packs can be megabytes. Verify real sizes. A small cap turns every large CurseForge pack into "Unknown", and reading the whole file wastes memory.
- **Problem 3: levels.** "Walking up ... at most 3 levels" is ambiguous and more than needed:
  - CurseForge's file is in the game dir itself (level 0);
  - Prism's `instance.cfg` is one level up;
  - levels 2-3 read `%APPDATA%` and the user's home folder for no benefit.
- **Problem 4: thread.** The spec doesn't say which thread does the reading. A game dir on a network share can stall.
- **Evidence.** SPEC:81, :85; launcher-ram.md:103-119, :146.
- **Fix.** Spec text:
  - Levels 0-1 only (the game dir and its parent), absolute and normalized.
  - `Files.isRegularFile` (a symlink to a regular file is fine) and a size check before opening.
  - `instance.cfg` capped at 64 KiB.
  - `minecraftinstance.json` read with Gson's streaming `JsonReader`: only the top-level `isMemoryOverride`, other values skipped, stopping after 32 MiB.
  - The whole probe runs on `Probes.EXECUTOR` with a timeout, and any Throwable means Unknown.
  - Add FIFO or directory named `instance.cfg`, symlink and large-file fixtures to AC5.1.

### C-M2: The global memory steps don't work when the instance overrides memory
- **Problem.**
  - Modrinth App and Prism both let an instance override the global memory setting.
  - Modrinth's override is stored in `app.db`, which RigTune must not read.
  - Prism's `instance.cfg` has an override switch, which the spec doesn't read (it reads `MaxMemAlloc`).

  Global click steps are then wrong for exactly the users who already changed memory for the instance.
- **Evidence.** launcher-ram.md:44-49 (Modrinth `instance_launch_overrides`), :167 (Prism override toggle), SPEC:81, :84.
- **Fix.**
  - Always give the **instance-level** steps for Modrinth App and Prism. They work whether or not the instance already overrides memory ("turn on the override, then set ...").
  - For CurseForge, use `isMemoryOverride` as specified.
  - Don't read `MaxMemAlloc` or `allocatedMemory`. `Runtime.maxMemory()` already is the effective value, and nothing else uses them.

### C-M3: The brief asks for the official launcher; the spec makes it optional without recording a deferral
- **Problem.** The brief names the official launcher with exact click steps. SPEC:80 drops it silently if the brand literal can't be verified, and the research found no primary source.
- **Evidence.** SPEC:80; launcher-ram.md:68, :169.
- **Fix.**
  - Give WS-C a concrete way to verify: the Minecraft Wiki says the brand "is included in crash reports", so public crash reports from official-launcher users (for example in mod issue trackers) are primary evidence of the literal.
  - If it still can't be verified, record the deferral with its reason in PROGRESS as a P1 deviation.
  - The click steps (Installations → Edit → More Options → JVM Arguments) can still ship once detection is confirmed.

### C-L1: Smaller WS-C items
- AC5.3 names UiGameTest; the plan says LauncherGameTest. The "without it" screenshot assumes the production run task passes no brand. Confirm that from the CI JVM arguments or `latest.log`.
- AC5.2's scenario tests need their own class. KnowledgeV2ScenarioTest belongs to WS-D (PLAN:79).
- `ShareReport.format` has 26 call sites. Add an overload rather than changing the signature.

---

## WS-D: rules (SPEC 7, 12, vulkan-backend, change C, 3d)

### D-H1: "RX 9070 GRE: more conservative, so also in v1" fails RulesV1DifferentialTest by the test's own definition
- **Problem.** The differential test counts every appliable action whose id **and value** 0.1.x didn't get before as "added", ticked or not. A GRE at GPU tier 4 instead of 5 gets `set:vanilla.renderDistance=12` where the baseline gave `=16`, plus other tier-gated values. AC7.2 adds a GRE to the matrix, so the test fails.

  The only documented way out is replacing the whole baseline, which resets the 0.1.x guard for every rule. Lower tiers can also newly trigger `tierAtMost`-gated additions. "Lower tier = more conservative" isn't the project's definition of safe.

  Separately, a new matrix entry only tests the GPU row if the GPU is the limiting tier. Paired with an 8-core non-X3D CPU (formula tier 3) or a small heap, it tests nothing.
- **Evidence.**
  - RulesV1DifferentialTest.java:215-222 ("added" for any new action), :244 (`id + "=" + newValue`).
  - knowledge.json:480 (RD 16 at `tierAtLeast: 5`) and :22 (the GRE currently matches the tier-5 row).
  - SPEC:8, :96, :101.
  - RULES_SCHEMA.md:222.
- **Fix.**
  - Give the GRE row `"v1": false` too. Simplest: every new tier row, since RTX 5050 doesn't change anything.
  - Rewrite SPEC:96 so that 0.1.x keeps its v0.1.0 classification for every new row.
  - AC7.2: each new matrix entry uses a tier-5 CPU (a K or X3D model) and a heap of at least 6 GB, so the GPU row decides the tier, and the test shows no difference against the baseline.
  - Add a unit test that the pinned v0.1.0 classifier gives the new hardware strings the same tier and integrated flag with the old and new rules-v1.json.

### D-M1: The weekly bot PR on main and feat/v0.3.0 will both produce revision 11
- **Problem.** The updater sets revision = max(old) + 1, per branch.
  - The Monday cron opens PRs against **main**, which is at revision 10. WS-D's regeneration on feat/v0.3.0 also goes from 10 to 11.
  - If a bot r11 lands on main before the release, v0.3.0's own r11 reaches main with the same revision. Clients switch to a remote document only when it's strictly newer, and on a tie a cached copy beats the bundled one.
  - So 0.2.0 clients that cached the bot r11, and 0.3.0 clients with that cache, keep the bot's content instead of v0.3's rules until the next bump.
  - SPEC:56 says a bot PR is "merged into feat/v0.3.0", but those PRs target main.
- **Evidence.** update-rules.yml:5 (`cron`), :64 (`--base main`); update_rules.py:887-901; RULES_SCHEMA.md:25-26; SPEC:56.
- **Fix.**
  - Disable the cron (keep `workflow_dispatch`) until v0.3.0 ships, or triage bot PRs only by re-running the updater on feat/v0.3.0.
  - Add to Phase 7: after merging `origin/main` into the release branch, re-run the updater so the revision is higher than main's.
  - AC4.5: rules revision on the release commit > the revision on main before the merge.

### D-M2: The vulkan-backend `mcVersionRange` change has no acceptance criterion
- **Problem.** It's a P0 item 1 change, but nothing checks it:
  - that 26.2 and 26.3 on Vulkan still get the advice;
  - that `26.4-alpha.1` (a snapshot's normalized version) and `26.4` don't;
  - that 0.1.x keeps it through the `v1` override.

  The v1 projection sets an info advice with a v2 key to `always:false` without asking, and only the differential matrix's single Vulkan entry would notice.
- **Evidence.** SPEC:26; knowledge.json:642-648; RULES_SCHEMA.md:213.
- **Fix.** Add AC1.7: KnowledgeV2ScenarioTest covers the four versions, and rules-v1.json keeps `"when": {"backend": ["vulkan"]}` for this advice (checked by check_rules_v1.py or a unit test).

### D-L1: Tier `v1` handling in the updater, and the spark advice
- **Tier `v1`.** `v2_content()` strips `v1` only from the rule kinds, not from tier rows, and `validate_knowledge` rejects unknown tier fields. SchemaConsistencyTest compares the updater's field lists with Java. Add `v1` as a source-only tier key outside `V1_RULE_FIELDS` (update_rules.py:103-112, :332-339, :819-824).
- **Spark advice.**
  - While spark is loaded, it adds 1 to the title-screen "N suggestions" toast forever, on 0.1.x too (RigTuneClient.java:172 counts every recommendation).
  - Keep the text short: the five reading tips are long for the advice row.
  - Say that `/sparkc profiler stop` uploads a public profile, including system and JVM details, to spark.lucko.me. Verify the exact contents.
  - Make it `impact: low`.

---

## WS-E: benchmark (SPEC 3f, 8)

### E-M1: The settle rule's chunk area is undefined, and a timed-out step still counts, which brings the bias back at high render distances
- **Problem 1: shape.** AC8.2 says "every chunk within RD−1" without a shape. The server sends a circle: the probe's 377 chunks at radius 11 is about π·11². A square check waits for corner chunks that never arrive, so every step hits the 20 s timeout.
- **Problem 2: timeout.** "Or at a 20 s timeout that logs the missing count" then measures anyway. At high render distances (up-steps up to 32, about 3,000 chunks to generate), weaker machines will time out. The step is then measured on partial terrain, which is the same over-estimate 3f exists to fix.
- **Evidence.** benchmark.md:28, :36, :39; BenchmarkController.java:373-382; SPEC:66, :112.
- **Fix.**
  - Define the area as a circle: dx² + dz² ≤ (RD−1)², in chunks, from the camera's chunk.
  - A step whose settle timed out with more than 2% of in-range chunks missing is recorded as **not passing** for the up-search (the planner stays conservative), and its missing count is logged.
  - Unit-test this in a core `SettleCheck`.

### E-M2: A Tune in the player's own world will now generate terrain there
- **Problem.** Once the server gets the new view distance, every up-step in the CURRENT scene makes the integrated server generate and save chunks out to the tested render distance (up to 32) **in the player's own save**. That grows the world on disk and permanently generates terrain for players who keep areas ungenerated for future worldgen updates. v0.2's design stressed that the benchmark never alters the player's own world (DESIGN.md:206).
- **Evidence.** benchmark.md:37; BenchmarkController.java:54, :159.
- **Fix.** Pick one and put it in SPEC 8:
  - (a) CURRENT-scene Tune up-steps are capped at the join-time render distance plus 8, and the result says so; or
  - (b) the benchmark menu shows a one-line note ("raising the render distance loads and saves more of your world") before a CURRENT-scene Tune.

  Either way, write it in DESIGN and README.

### E-M3: AC3.6's game test can flake on the CI runners
- **Problem.** On a 4-vCPU llvmpipe runner, generating about 450 chunks for RD 12 in a fresh save can exceed the 20 s settle timeout. That happens on every leg, including lavapipe. The test would then fail intermittently.
- **Evidence.** ci-gametests.md:191, :247 (4 vCPUs; about 37 FPS); SPEC:67.
- **Fix.**
  - Make the settle timeout a `Config` value; the game test uses 60 s.
  - Assert that the step's settle didn't time out, then that the chunks are present.
  - Record the settle time per leg in the log, to calibrate.

### E-L1: Smaller WS-E items
- The shader-settings menu path in the advice text is marked UNVERIFIED in the research (benchmark.md:223) but stated as fact in SPEC:110. Verify it in the Phase 5 smoke, or word it generically.
- AC8.7's smoke needs a way to force the advice, since the dev machine may meet the target with shaders on. Add a dev-only `-Drigtune.dev.targetFps`.
- For camera height = terrain floor + 16, assert that the camera block and the one above it are air (tall trees on a future version).
- Confirm that `broadcastOptions()` does nothing when there's no player or connection (the finish path after a disconnect).
- Simulation distance isn't in `ClientInformation`, so broadcasting after an SD change is harmless but has no effect. Don't claim otherwise.

---

## WS-F: Report a problem (SPEC 10, 11)

### F-M1: A 2,000-character URL on vanilla's confirm screen
- **Problem.** `ConfirmLinkScreen` shows the whole URI as its message. A percent-encoded share report of about 2,000 characters wraps to dozens of lines on a 240-pixel-tall GUI:
  - the player can't read what they're about to open;
  - the buttons may be pushed off-screen at small sizes (unverified).

  AC10.2's screenshots would only find this after the feature is built.
- **Evidence.** SPEC:124, :126; misc.md:33.
- **Fix.**
  - Do a spike first: screenshot `confirmLinkNow` with a 2,000-character URL at 640×480 scale 2.
  - Default design: the URL carries only the title and a short `report` (the version and hardware lines, about 500 characters). The full report is **always** copied to the clipboard.
  - problem.yml asks the reporter to paste it.
  - AC10.1: URL ≤ 800 characters.

### F-L1: Smaller WS-F items
- **Encoding.** Every RigTune version contains `+` (`0.3.0+mc26.2`). Encode with a form encoder (`+` → `%2B`), and add `+` and `%` to AC10.1's encoding cases. The `URI` multi-argument constructors leave `+` as is, and GitHub then reads it as a space.
- **Where the template lives.** problem.yml must be on **main** before the release is published, or the link opens the wrong form.
- **Clipboard.** Tell the player the clipboard was replaced (a status line).
- **Ownership.** WS-F needs a RealController method (versions for the title), but the hotspot table doesn't list F for RealController.

---

## WS-G: localisation (SPEC 9)

### G-H1: The `Text` "contracts commit" has no owner
- **Problem.** SPEC:117 says "New v0.3 code uses `Text` from the start (contracts commit)", but no phase or workstream creates `Text`: WS-0 is only CI and build (PLAN:58-60). Wave A's UI workstreams (A, B, C, E, F) will then either each write their own type, giving competing classes at merge, or write plain English strings that Wave B has to redo.
- **Evidence.** SPEC:117; PLAN:56-60, :102-103.
- **Fix.** Add a "contracts" task to WS-0, merged before Wave A. It covers:
  - `core/model/Text` (key, arguments, English fallback; no Minecraft imports);
  - a client `Texts.component(Text)` that renders it (after the javap check of `Component.translatableWithFallback` on both versions, PLAN:103);
  - the RigTuneController `// v0.3` default-method stubs each workstream fills in.

  Or remove "from the start" and let Wave B convert everything.

### G-M1: The `Text` conversion must stay off the helper path and off the types the e2e drivers use
- **Problem.**
  - `last-apply.json` messages are shown in the UI, but converting ApplyExecutor or ApplyResult messages would change a persisted format. That breaks the downgrade promise at SPEC:10 and puts new code on the helper path.
  - Changing the type of `Recommendation.title()` or `reason()`, `UndoPlan.Item`, or the `UndoScreen` or `RigTuneController` signatures breaks the e2e drivers (NoSuchMethodError on the 0.3.0 side of the self-update run). The drivers are compiled against the old jar or the current sources and aren't built in CI (H-M3). The break would show up only in Phase 5.
- **Evidence.**
  - SelfUpdateDriver.java:143-144, :371-372 (`title()`, `reason()`); UndoDriver.java:188, :195, :255.
  - build.gradle:262-301.
  - ApplyResult.java:19.
  - SPEC:10.
- **Fix.** Spec text:
  - `Text` is **added next to** the existing String accessors (for example `titleText()`); no existing signature changes.
  - No persisted string (pending.json, last-apply.json, history.json, benchmarks.json) and no class reachable from ApplyHelper changes.
  - Add HelperLauncherTest and an e2e driver compile to WS-G's checks.

### G-M2: AC9.3 can't be tested as written, and LangCheckTest (a) misses real cases
- **Problem 1.** "No core-built UI string reaches the UI untranslated (unit level)" has no set of paths to check.
- **Problem 2.** Check (a) scans only `Component.literal("…")`. It misses:
  - `graphics.text(font, "…")` and `centeredText` with a String;
  - string pieces containing letters concatenated into a variable that's later passed to `Component.literal(var)`: `" GB"` and `" Hz"` go through `value(...)`, and UndoPlanner's descriptions arrive as `Component.literal(item.description())`.
- **Evidence.** SPEC:118, :120; RigTuneScreen.java:206, :264, :272; BenchmarkResultScreen.java:216; UndoScreen.java:236-238.
- **Fix.**
  - AC9.3 becomes: "For fixture Reports, UndoPlans and resolver or planner errors, every field the UI shows is a `Text` whose key is in en_us.json. Rendered through an uppercasing stub, the output is uppercase except for argument values."
  - Check (a) also flags string pieces with 2 or more letters inside `text(`, `centeredText(` and `literal(` calls and in `+` chains in `client/ui`, with an allowlist.
  - Treat UndoPlanner's description texts (UndoPlanner.java:40-56, :486, :681) as in scope.

---

## WS-H: self-update end to end (SPEC 4)

### H-M1: The 0.2.0 old-side driver may need build.gradle changes, which no Wave A workstream may make
- **Problem.** The e2e driver compiles against `-Pe2e.oldJar` and "only uses API that 0.1.0 has". Whether it also compiles and links against the 0.2.0 jar (and runs on the 0.3.0 side) is unknown. If it doesn't, a new source set is needed. build.gradle belongs to Phase 3 only (PLAN:52); WS-H only has "coordinate with the coordinator" (PLAN:95). That's an unplanned block on a P0 item.
- **Evidence.** build.gradle:266-290; PLAN:52, :95-96.
- **Fix.** In Phase 3:
  - WS-0 runs `compileE2eJava -Pe2e.oldJar=<released 0.2.0 jar>`.
  - If it fails, WS-0 adds a source set parameterized by the old version now, or gives WS-H a marked build.gradle section.

### H-M2: The real user's upgrade state isn't in the E2E
- **Problem.** The user's instance runs 0.1.0 and has:
  - a pending DH update group that failed once (attempts 1/3);
  - DH's own build waiting in `mods/update/`.

  Upgrading to 0.3.0 must deal with that. AC4.1's 0.1.0 → 0.3.0 run repeats v0.2's scenario (RigTune's own update only), so the one path we know a real user will take goes untested:
  - the legacy import;
  - 3a dropping the carried-over DH group ("loaded" rule);
  - the 3e WARN lines;
  - no race with DH's updater.
- **Evidence.** PROGRESS.md "Real-world feedback"; SPEC:60, :71, :73.
- **Fix.** Add a seeded variant to AC4.1. Its fixtures are built from the user's real `pending.json` and `last-apply.json` (read-only, templated) plus a jar with id `distanthorizons` in `mods/update/`. It passes when:
  - after the first 0.3.0 launch the group is dropped, with the notice;
  - the journal marks it DISCARDED;
  - `latest.log` has the WARN line;
  - nothing in `mods/` changes at exit.

### H-M3: The e2e drivers aren't compiled in CI
- **Problem.** Any Wave A or B change to `UndoScreen`'s constructor, `RigTuneController`, `UndoPlan`, `Recommendation` or `UpdateInfo` can break the e2e or e2eUndo drivers. Nothing notices until Phase 5.
- **Evidence.** build.gradle:262 ("never part of ... `build`"), :292-297; UndoDriver.java:195 (`new UndoScreen(screen, controller, false)`).
- **Fix.** In WS-0's CI:
  - `./gradlew compileE2eUndoJava`;
  - `compileE2eJava` against the released 0.1.0 and 0.2.0 jars, downloaded from the GitHub releases and checked by sha256.

  WS-B keeps the `UndoScreen(Screen, RigTuneController, boolean)` constructor.

---

## WS-V: MC-version tooling (SPEC item 1)

### V-M1: add_mc_version.py and mc_apidiff.py are P0, but no newer stable version exists
- **Problem.** The brief's P0 is "newer MC versions", and the research found none. Item 1's value now is AC1.1, one-time changes A, B and C, the raw game version and the vulkan rule. The two new tools, their offline fixtures (AC1.2) and a live dry run (AC1.3) are convenience work for a future 26.4. As P0 they can hold up the release.
- **Evidence.** SPEC:16, :24, :28; mc-versions.md:7, :263.
- **Fix.** Make `add_mc_version.py` and `mc_apidiff.py` (AC1.2, AC1.3, AC1.6) P1, and first in the cut order. Keep changes A, B, C and D as P0 only if they cost almost nothing (D just commits the research harness).

### V-L1: Shared tool docs and test folders
tools/README.md and `tools/tests/` belong to WS-D (PLAN:79), but WS-V adds tools and tests there. Give WS-V its own `tools/README.md` section, or a `tools/MC_VERSIONS.md`, and a `tools/tests/test_add_mc_version.py` that WS-D doesn't touch.

---

## Cross-cutting plan issues

### X-M1: One game-client lock for 8 parallel workstreams
- **Problem.**
  - Every workstream does "one final local run per version under the lock" (PLAN:37).
  - WS-E also runs two real Tune autoruns.
  - WS-H runs multi-launch e2e dry runs.
  - 26.3 locally crashes at OpenAL startup about half the time, with up to 5 retries.

  Agents give up after 30 minutes of waiting (PLAN:22). With about 8 workstreams finishing close together, some will give up or finish without their required local runs. CI already runs every game test on both versions.
- **Evidence.** PLAN:22-23, :37, :84, :96.
- **Fix.**
  - Wave A local runs become optional where the CI legs cover the change.
  - Keep the lock for WS-E's autorun (AC3.7), WS-H's e2e and Phase 5.
  - The coordinator gives out lock slots in merge order rather than first come, first served.

### X-M2: The RigTuneScreen button row has three new buttons and no single owner
- **Problem.**
  - B (History), F (Report a problem) and P (Preview) each add to the same button list. That's up to 11 buttons.
  - At 640×480 scale 2 (a size in the existing screenshots) that's 4 rows (about 90 of 240 pixels), which squeezes the recommendation list.
  - Three workstreams editing adjacent lines will also conflict.
- **Evidence.** RigTuneScreen.java:118-165, :393-395; RigTuneClientGameTest.java:101; PLAN:46.
- **Fix.** One owner (WS-B) decides the layout in the contracts step:
  - "History…" goes next to the Undo buttons;
  - "Report a problem" goes into the Copy report button as a split, or into Settings;
  - Preview is left out unless P ships.

  Add a 640×480 scale 2 screenshot to AC6.3 and AC10.2.

### X-L1: The hotspot table misses files that more than one workstream edits
Add these rows to PLAN:44-52:
- RealController: F (report versions).
- OnlineLookupGate: A.
- OnlineDataFetcher.Result: A; RealController reads it.
- Journal.java: B, helper-safe.
- UndoScreen: B; the e2eUndo driver uses it.
- ShareReport: C, and G's "report stays English".
- tools/README.md: D and V.
- KnowledgeV2ScenarioTest: D, with C's and D's spark tests.
- DownloadPlanner: A in Wave A; G and P in Wave B.

### X-L2: Items with no owner
- docs/modrinth/body-0.3.md (SPEC:72), plus the CHANGELOG, README and DESIGN version lines: give them to a Phase 7 docs task.
- AC3.4 has to be re-checked right before the tag, after WS-D has finished: that's the coordinator's job.
- AC9.4's "screenshots unchanged" needs a method: a pixel diff of CI artifacts from before and after the WS-G merge, like ci-gametests.md:166.

### X-L3: Wave B's G and P collide
Both edit RigTuneScreen, RealController, en_us.json and DownloadPlanner: G converts messages to `Text`, P refactors for dry runs. Run P after G has merged, or cut it (see below).

---

## Compatibility checks that passed
- **0.2.0 and the new rules.** 0.2.0 understands `mcVersionRange` and evaluates `<26.4-` fail-closed (v0.2.0 ConditionEvaluator.java:101, :288-291). The spark advice uses only fields 0.1.x and 0.2.x know (`modPresent`, `kind: info`). No new rule-level field is planned.
- **0.2.0 reading 0.3.0 files.** A `context` field in benchmarks.json is ignored by 0.2.0's Gson and dropped if 0.2.0 rewrites the file; that only loses comparability. A per-entry undo writes an `undo` entry with `undoOf: <entryId>`, which 0.2.0's `Context` already handles (UndoPlanner.java:203-205). A downgrade to 0.2.0 is safe, provided G-M1 keeps last-apply.json and the journal unchanged.
- **3a against review-4's rules-accuracy-1.** Requiring the mod to be loaded keeps the protection: an update is offered only for a loaded mod, and nobody updates an unloaded mod's jar at exit, so there's no race to guard. Separately, a drop still takes its whole group (Staging.java:195-212).
- **3f.** `broadcastOptions()` exists on both versions. It's public, and the research probe called it on both (benchmark.md:20, :30). The finish path already saves and broadcasts the original values (BenchmarkController.java:466-467).
- **Report a problem.** `ConfirmLinkScreen.confirmLinkNow(Screen, URI)` has the same signature on both versions and always asks before opening (misc.md:28-35). RigTune makes no request itself, the query values are encoded, and the base URL is fixed. ShareReport already strips paths and escapes Markdown and `@` (ShareReport.java:210-241).

## Cut order if time runs short
1. **P2 13: Preview.** Cut entirely (X-L3).
2. **Item 9's core `Text` conversion.** Keep LangCheckTest (a)-(d), the README guide and the dead-key removal. This also removes the G-M1 risk. If G-H1 isn't resolved in Phase 3, cut it at once.
3. **Item 1's `add_mc_version.py` and `mc_apidiff.py`** (AC1.2, AC1.3, AC1.6). Keep changes A, B and C, the raw game version and the vulkan rule.
4. **P2 12: spark advice, and P2 11: the Quilt FAQ.** Cheap, but the spark advice touches rules-v1.json.
5. **Item 8 extras:** the shader advice line (a) and the camera-height change. Keep 3f, E-M1, `context` and the SceneVariety check.
6. **Item 5's file walking** (CurseForge and Prism files). Keep detection from properties and environment variables: Prism, Modrinth App (`theseus`), ATLauncher. That also removes most of C-M1's attack surface.
7. **Item 6's "Undo this".** Ship the read-only History screen with statuses and failure reasons, and defer per-entry undo to v0.4 if B-H1's superseded pass isn't done and tested.
8. **Item 10 fallback:** the button copies the full report and opens the blank problem form with the title only.

Never cut: item 2, 3a-3f (with A-H1's group joining), item 4 (both E2E paths, H-M2's seeded variant, and W0-M3's byte-identical release), and item 7 (with D-H1 applied).
