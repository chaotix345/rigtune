# v0.4.0 leftovers for v0.5.0 P0.2

"Fix every deferred v0.4 issue and low, each with a test." Harvested from docs/PROGRESS.md (v0.4 and v0.3
sections), docs/v0.4/SPEC.md (body + Amendments), docs/v0.4/plan-review.md, docs/v0.4/design/*.md,
docs/reviews/review-7..10.md, docs/v0.4/verification/{README,P5A,P5B,P5C-FINDINGS}.md,
docs/v0.4/audit-verification.md, docs/research/v0.4/audit-apply-pipeline.md, docs/DESIGN.md, README.md,
CHANGELOG.md, and the v0.3 sections of the same. Every item below was checked against the current code on
`feat/v0.5.0` (file:line); nothing here is asserted from a doc alone without a source-code check unless marked
UNVERIFIED.

Conclusion up front: **every review-7/8/9/10 finding is FIXED except R10-1** (confirmed by re-reading review-9.md's
"NOT FIXED | 0" table and review-10.md's fix-status table). The real leftover pool is SPEC's own "Deferred / not in
v0.4" list, the residuals each design doc documents for itself, and the CHANGELOG/README "Known issues"/"Known
limits" the team chose to ship with.

## Summary table

| id | title | verdict | effort | files | test |
|---|---|---|---|---|---|
| L1 | R10-1: StutterService.end() can drop a session summary if the copy throws | FIX | S | `StutterService.java`, `StutterCapture.java` | `StutterServiceTest` (new): a throwing `copy()` still schedules a best-effort save / logs specifically |
| L2 | "chunks loading" stutter tag isn't a v2 rules condition | FIX | M | `tools/update_rules.py`, `rules/source/knowledge.json`, `core/rules/ConditionEvaluator.java` (client-side fact), `core/recommend` evaluator tests | `test_update_rules.py` new key case; `ConditionEvaluatorTest` new condition; `KnowledgeV2ScenarioTest`/`RecommenderV2Test` seeded rule; Python `test_generated_rules.py` fail-closed-on-old-client case |
| L3 | BenchmarkResultScreen / BenchmarkHistoryScreen chart+table aren't accessible | FIX | L (2-3 days, as SPEC already estimated) | `client/ui/BenchmarkResultScreen.java`, `client/ui/BenchmarkHistoryScreen.java`, `client/ui/RowList.java`/`RowFocus.java` (reuse) | `A11yGameTest` new Tab-walk block for both screens; pixel-diff unchanged-when-off |
| L4 | Tier text still says "entry-level CPU/graphics/hardware" on a combined tier (7 rules) | FIX | S | `rules/source/knowledge.json` (7 reason strings), regenerated `rules/rules-v1.json`/`rules-v2.json`, `src/main/resources/rigtune/rules-v2.json` | `WordingTest`/`LangCheckTest` string assertions updated; `tools/tests/test_check_rules_v1.py` stays green (wording-only, no `v1` field change needed) |
| L5 | Preview can list a download that Apply then refuses on a fabric.mod.json version range | NEEDS A DECISION | M/L | `core/modrinth/DryRunPlanner.java`, `DownloadPlanner.java` (`jarOf`, `checkVersions`), `client/RealController.java` (`preview()`) | `PreviewGameTest`/`PreviewScreenTest` new case: a ticked item Preview shows clean that Apply's real fabric.mod.json refuses |
| L6 | StartupNotices saves settings.json on the shared `Probes.EXECUTOR` worker pool, not `SettingsSaver` | FIX | S | `client/RigTuneClient.java:192`, `client/StartupNotices.java` | New unit/game test: quitting right after the first-run privacy toast still has the write observed by `SettingsSaver.shared().flush()` / no write lost when both fire close together |
| L7 | Discard's status line doesn't mention a kept half-applied group | FIX | S | `client/RealController.java` (`discardPending`), `en_us.json`, `core/apply/Staging.java` (return what was kept) | New unit test: `Staging.discard()` on a `PartlyApplied` state returns which group was kept; `RealController` status message names it |
| L8 | History's baseline fold (M6) loses the "Profile: X" label | FIX | S/M | `core/history/Journal.java` (`baseline()`), `core/history/HistoryModel.java`, `core/profile/ProfileStore.java` (switch-id index) | `JournalTest`/`HistoryModelTest`: a run containing a profile switch, once folded past `MAX_ENTRIES`, still reports `profile` on the baseline entry |
| L9 | 2d residual: staged DISABLE_FILE ops aren't folded into the resolver's view (under-blocking) | FIX | M | `core/apply/StagedProjects.java` (or wherever `fold` lives), `DownloadPlanner.java` | `DownloadPlannerTest`: an addition that would conflict with a mod staged for disabling in this same session is refused, not staged |
| L10 | ws-g1 residual: a mod staged for disabling still counts as present in resolver checks (over-blocking only) | NOT WORTH IT | - | `core/modrinth/DownloadPlanner.java` | - |
| L11 | ws-g1 residual: a top-level mod with neither hash nor mods/ file counts as nested | NOT WORTH IT | - | `core/modrinth/DownloadPlanner.java` (`topLevelIds`) | - |
| L12 | ws-a residual: a dependency-level incompatibility between two additions still fails only the later one (asymmetric) | NOT WORTH IT | - | `core/recommend`/`DependencyResolver` | - |
| L13 | ws-g3 residual: after downgrading to 0.3.0, its own helper can't see `unfinished-groups.json`, so a group 0.3.0 can't finish leaves the old jar disabled | NOT WORTH IT | - | n/a (0.3.0 is already shipped and immutable) | - |
| L14-L19 | Feature-level v0.4 deferrals (shader-pack profiles, jar-level optional-mod toggles, entity/redstone benchmark scene, full non-English translation, Quilt support, controller-specific support) | NEEDS A DECISION (out of P0.2 scope — these are features/scope calls, not defects) | - | - | - |
| L20 | Deliberate scope exclusions (automatic battery switching, "Apply" buttons on stutter advice, system-wide CPU load in the Doctor, per-mod startup attribution) | NOT WORTH IT (by design / platform-impossible, see reasons) | - | - | - |

## Owned elsewhere (verification gaps / CI flakes — not this file)

Real-hardware/session verification gaps (r-verify's territory): LAN guest and Realms end-to-end (AC8.5); PowerWatcher
on a real laptop; a real screen reader/TTS reading the Narrator text; Controlify/controller navigation in a real
game; NVIDIA/Intel Vulkan driver strings (only AMD/Mesa captured); CurseForge's Java-arguments precedence and the
official launcher's exact in-app labels (currently sourced from Mojang's articles only); Shenandoah and Java 26/27
JVM measurements; a real locked file / killed JVM / power cut between a rename and the helper's record (only
simulated via the Mover seam); Recording's premises (OBS capture, tearing); `add_mc_version.py`'s write path against
a real new release; `mc_apidiff.py` off Windows; whether Stonecutter accepts `//? if >=26.4-snapshot-1` for real;
spark commands run in game; the `problem.yml` GitHub form's live prefill.

CI flakes / robustness (r-ci's territory): per-call nanosecond footprint budgets flaking on shared runners (~1/6);
`FootprintGameTest` render-thread CPU quantization on local Windows (P5B-F1, gate proof only, not a product bug);
mocking Modrinth in game tests (PreviewGameTest / Sodium-Iris maven downloads going down turns CI red);
`UiGameTest.checkSettings`/`waitForSaved` and `BenchmarkGameTest`/`BenchmarkHistoryGameTest` screenshot-timing flakes
noted in WS-X and P5-B's environment notes.

---

## L1 — R10-1: a benchmark's forced session-end can silently drop the session summary if the copy step throws

- **Source:** docs/reviews/review-10.md (the only open finding from all four review rounds).
- **Scenario:** A player has a Stutter Doctor session running (≥ 2 min of real gameplay already logged, so it would
  be persisted) and starts a benchmark. `BenchmarkController.begin()` → `StutterHooks.benchmarkStarted()` →
  `StutterService.benchmarkStarted()` → `endSessionForBenchmark()` → `end(session, minecraft, false)`. If the copy
  step inside `end()` throws, the whole session's summary is discarded and the only trace is a generic warning that
  ending the session failed — nothing says a running session's data was specifically lost.
- **Root cause (verified against current source):**
  - `StutterService.java:193-194` — `end(...)`'s first line is `StutterCapture.Copy copy = StutterCapture.stop(session);`
    with no try/catch around it; only after this returns does `end()` build the `Runnable save` that queues the
    write to `stutter.json`.
  - `StutterCapture.java:38-41` — `stop(capture)` is `static synchronized`, `try { return copy(capture); } finally { StutterMonitor.stop(capture); ... }`.
    The capture is always correctly detached (no resource leak), but if `copy(capture)` (`StutterCapture.java:51`,
    a plain array copy of the frame/phase rings) throws a `RuntimeException`, it propagates straight out of `end()`.
  - `StutterHooks.java:129-135` — `benchmarkStarted()` wraps the whole `StutterService` call in try/catch and only
    logs `"Stutter Doctor: could not end the session for the benchmark"` (line 135) on any exception. This is the
    only place the exception is ever caught.
  - This ordering is pre-existing (also true for the world-leave and monitor-toggle-off callers of `end()`), but
    v0.4's fix-9 (starting a benchmark now calls `end()` on any running session, rather than just pausing it) is
    what makes this path reachable from every single benchmark run, not just world exit.
- **No live trigger was found** in review-10's hunt: `copy()`'s underlying `FrameRing.snapshot()`/`StutterRings.snapshot()`
  are plain in-memory reads with no I/O on the render thread, so this is an increase in exposure, not a demonstrated
  live defect.
- **Proposed minimal fix:** wrap `StutterCapture.stop(session)` inside `StutterService.end()` (or inside
  `endSessionForBenchmark`/`benchmarkStarted`) so a `copy()` failure still attempts a best-effort save of whatever
  can be salvaged (e.g. an empty/partial summary flagged as such), or at minimum logs specifically that a running
  session's summary was lost (distinct from "ending it for the benchmark failed").
- **Test:** a new `StutterServiceTest` (or extend `StutterMonitorTest`) that injects a `copy()` that throws (a test
  seam/spy on `StutterCapture`) and asserts either a partial save is queued or a distinguishable log line appears,
  and that no exception escapes to `BenchmarkController`.
- **Compatibility:** no format/schema change; purely internal exception handling. Safe on 0.1.x-0.4.x files and on
  downgrade to 0.4.0 (stutter.json's shape is unchanged either way).
- **Effort:** S. **Verdict: FIX** (a player could plausibly hit this if a JVM issue or a future ring-size change ever
  makes the copy step fallible; low cost to close the gap).

## L2 — the "chunks loading" stutter tag isn't a v2 rules condition (fix-8b)

- **Source:** docs/v0.4/SPEC.md:373 ("The tag is not yet usable as a rules condition (not in update_rules.py; v0.5)."),
  docs/v0.4/design/fix-8b.md ("not yet in the updater's `stutterTaggedShareAtLeast` vocabulary, so no rule can use it"),
  CHANGELOG.md Known issues, README.md "Known limits".
- **Scenario:** Stutter Doctor already tags individual hitches "while chunks were loading (not measured)" in the
  report (`Attributor`, confirmed live on the RC — see below), but nothing in the rules engine can *recommend*
  anything based on how often that tag fires for a player (e.g. "Sodium's Chunk Updates set to Deferred" when chunk
  loading dominates a player's own sessions). The vocabulary simply doesn't have a condition key for it yet.
- **Root cause:** `tools/update_rules.py` validates and generates `stutterAdvice` conditions from a fixed vocabulary
  (grep confirms `stutterTaggedShareAtLeast`-style keys are the existing pattern for other tags — GC, world-save,
  fast-movement — but nothing for the chunks-loading tag introduced in fix-8b's `Attributor`). The client's
  `ConditionEvaluator`/`ConditionAdapterFactory` (SPEC K-M1's vetted-type adapter) also has no case for it.
- **A verified case exists** to seed a real rule from: `docs/v0.4/verification/P5C-FINDINGS.md` (P5C-F1) and
  `docs/v0.4/verification/stutter/C-rerun/` — RC re-run `C1r`: 12 spikes, 9/12 tagged "chunks loading" (the 3
  untagged are inside the ~0.3 s before any chunk arrives); `C3r` (uncapped FPS): 14 spikes, 11 tagged, including a
  measured `chunkBuild:medium` (7%) case. This is real, reproducible data — a rule seeded from it (e.g. "if the tag
  fires in ≥ X% of a player's own recent sessions, suggest Sodium's Chunk Updates = Deferred") would be grounded in
  an actual measured scenario, not a guess.
- **Fail-closed requirement:** the new condition key must be treated exactly like every other v2-only key: SPEC
  R-L1 already requires `update_rules.py` to refuse a v2-new key inside a clamp/`avoidWhen`/`skipUpdateWhen` unless
  the rule carries `requires`; the new `stutterTaggedShareAtLeast`-style key must go through the same path so an
  0.1.x/0.2.x/0.3.x client (which can't evaluate it) never sees a rule silently misfire — it either doesn't get the
  rule at all (v1: false) or the rule's `requires` gates it off for clients that predate the tag itself (v0.3.x and
  older don't have "chunks loading" attribution to report against, so any rule using this key must be `"v1": false`
  and probably gated to schema ≥ 2 with the stutter share reported).
- **Proposed fix:** add the condition key to `ConditionEvaluator`'s stutter-share family (mirroring the existing
  GC/world-save/fast-movement keys), add the vocabulary entry + validation case to `tools/update_rules.py`, and seed
  exactly one advice rule (Sodium Chunk Updates = Deferred) gated on it, using the P5C-F1 data as the calibration
  reference for a sane threshold.
- **Test:** `ConditionEvaluatorTest` new case (present/absent/malformed share value → UNKNOWN, per K-M1); Python
  `tools/tests/test_update_rules.py` new key validation case (refuses the key outside `stutterAdvice`, refuses it in
  a clamp without `requires`); `tools/tests/test_generated_rules.py`/`test_check_rules_v1.py` confirm the seeded rule
  is absent from rules-v1.json (or gated) so old clients fail closed; `KnowledgeV2ScenarioTest`/`RecommenderV2Test`
  scenario using a canned high-chunks-loading-share profile.
- **Compatibility:** new v2-only condition key; must be `"v1": false` (or `requires`-gated) on the seeded rule so
  0.1.x-0.3.x never evaluate a key they don't understand — this is exactly the "fails closed" contract R-L1 added.
- **Effort:** M (new evaluator key + updater vocabulary + one seeded rule + the fail-closed test matrix).
- **Verdict: FIX.**

## L3 — BenchmarkResultScreen / BenchmarkHistoryScreen accessibility rebuild

- **Source:** docs/v0.4/SPEC.md:281 ("2-3 days... v0.5"), docs/v0.4/design/ws-x.md "Not covered" (lines 123-127),
  docs/DESIGN.md:251, CHANGELOG.md/README.md Known issues ("The benchmark result's table and the benchmark charts
  can't be reached with the keyboard or read by the Narrator yet").
- **Scenario:** A keyboard/screen-reader user opens a benchmark result or the benchmark history trend — the
  headline v0.4 features for measuring your own PC — and Tab never reaches the table or chart; the Narrator says
  nothing about them.
- **Root cause:** `BenchmarkResultScreen`'s table and `BenchmarkHistoryScreen`'s chart are hand-painted
  (`graphics.text`/`graphics.centeredText` calls at draw time), not `AbstractWidget`s, so they never enter
  `children()`/`narratables()` the way WS-X's `RowFocus`/`RowList` pattern requires. Note review-8's UV-3 finding
  already forced part of this: `BenchmarkHistoryScreen`'s plain trend/regression *text* rows (not the chart itself)
  were pulled out and fixed in fix-8b via `RowFocus.standalone` (confirmed FIXED in review-9.md/review-10.md) — so
  what's left is specifically the **painted table** (BenchmarkResultScreen) and the **painted chart** (both
  screens), which are genuinely pixel content, not text.
- **Proposed fix:** give `BenchmarkResultScreen`'s table the same `RowList`/`RowFocus` treatment WS-X already built
  for `StutterScreen.TextRow`/`BarRow` and `JvmScreen.Row` (turn each table row into a focusable, narratable row);
  for the chart itself (both screens), the pragmatic v0.5 fix is a textual equivalent alongside the pixel chart (a
  narratable summary of what the chart shows — the trend direction and the same regression text already fixed by
  UV-3), rather than trying to make the pixel drawing itself focusable.
- **Test:** extend `A11yGameTest`'s Tab-walk list to cover both screens (currently omitted per review-8's UV-3
  finding text: "A11yGameTest's covered-screen list omits this screen entirely"); narration assertions for each new
  row; pixel-diff-unchanged-when-accessibility-off, matching WS-X's existing AC11.2 methodology.
- **Compatibility:** UI-only; no format/schema/rules change.
- **Effort:** L (SPEC's own 2-3 day estimate for the table; the chart's textual-summary approach is smaller, S-M).
- **Verdict: FIX** (explicitly on the v0.5 deferred list, a player could hit this today, and the pattern to fix it
  already exists in the same codebase from WS-X).

## L4 — tier texts still say "entry-level CPU/graphics/hardware" on a combined tier

- **Source:** docs/v0.4/verification/P5B-FINDINGS.md F6 (the specific LambDynamicLights case — **already fixed**:
  rules r16, docs/PROGRESS.md fix-8a "rules r16 (LambDynamicLights text)"; current `rules/source/knowledge.json:424`
  reads "On this PC's estimated tier..." not "entry-level"). PROGRESS.md's v0.4.0-released "Deferred / next steps"
  line explicitly calls out "other tier texts still saying 'entry-level CPU/graphics' (also in rules-v1)" as
  unresolved — this item is about those **other 7 occurrences**, not the already-fixed LambDynamicLights one.
- **Scenario:** `tier = min(gpuTier, cpuTier, memTier)` (docs/RULES_SCHEMA.md:78) — the effective tier can be
  memory-limited even on a fast CPU/GPU (exactly F6's repro: a Ryzen 7 7800X3D, CPU tier 5, was pushed to combined
  tier 2 by a 2 GB heap). Any rule that keys on the *combined* `tierAtLeast`/`tierAtMost` but names a specific
  hardware type in its reason text can misattribute the cause the same way F6 did, and it's still wrong today for:
  - `rules/source/knowledge.json:481` — `vanilla.renderDistance` value 8, `tierAtLeast:2, tierAtMost:2` — "...for
    entry-level hardware."
  - `knowledge.json:489` — DH `vanilla.renderDistance` max 8, `tierAtMost:2` — "On entry-level hardware, stay..."
  - `knowledge.json:492` — `vanilla.simulationDistance` max 6, `tierAtLeast:2, tierAtMost:2` — "...an entry-level CPU."
  - `knowledge.json:509` — `vanilla.renderClouds` false, `tierAtMost:2` — "...entry-level graphics."
  - `knowledge.json:525` — DH `lodChunkRenderDistanceRadius` max 48, `tierAtMost:1` — "...entry-level PCs..." (`v1: false`)
  - `knowledge.json:526` — DH `lodChunkRenderDistanceRadius` max 64, `tierAtLeast:2, tierAtMost:2` — same wording (`v1: false`)
  - `knowledge.json:542` — `iris.maxShadowRenderDistance` max 6, `tierAtLeast:2, tierAtMost:2` — "...entry-level hardware." (`v1: false`)
- **Why this is fixable as wording-only (checked against `tools/update_rules.py` and the v1 pin tests):**
  `check_rules_v1`'s validation (`tools/update_rules.py:327-540`, `tools/tests/test_check_rules_v1.py`) only
  constrains **keys, values and condition types** reaching rules-v1.json — it never inspects `reason` text content.
  Rewording a `reason` string changes nothing `RulesV1DifferentialTest`/`tierTablesStayAtV010` check (those pin the
  `gpuTiers`/`cpuTiers`/`heapTiers` *classification tables*, not advice-rule prose). The DH thread-count rules
  (`knowledge.json:536-539`) already show the right pattern in this same file: they key on `cpuTierAtMost` (the
  *specific* component) rather than combined tier, so their prose never has this problem.
- **Proposed minimal fix (matches the F6 precedent exactly):** reword all 7 reason strings to name the effective
  tier generically ("on this PC's estimated tier...") instead of a specific hardware type, mirroring the exact
  phrase fix-8a already used for LambDynamicLights. Do **not** change the conditions (`tierAtLeast`/`tierAtMost` stay
  as they are) — that would be a semantic change requiring full v1-safety re-review, not a wording fix.
- **Test:** whatever test currently asserts these exact reason strings (search `WordingTest`/scenario golden text for
  "entry-level") gets updated to the new wording; `tools/tests/test_check_rules_v1.py` and
  `RulesV1DifferentialTest`/`tierTablesStayAtV010` must stay green untouched (proves it really is wording-only);
  regenerate `rules/rules-v1.json`/`rules-v2.json` via `tools/update_rules.py` and diff to confirm only `reason`
  fields (and revision/generatedAt) changed.
- **Compatibility:** wording-only; rules-v1.json becomes no less conservative (same keys/values/conditions); safe on
  0.1.x-0.4.x and on downgrade.
- **Effort:** S. **Verdict: FIX** (explicit v0.5 carry-over, mechanical given the F6 precedent already in the repo).

## L5 — Preview can list a download that Apply then refuses on a fabric.mod.json version range

- **Source:** docs/v0.4/design/ws-g1.md residuals ("the dry run (Preview) knows no jar's version, so it checks no
  range and can list a download Apply then refuses (the refusal is in Apply's status line)"), docs/DESIGN.md:373,
  CHANGELOG.md Known issues ("Preview can't show what Apply only learns once a file is downloaded: ... a version
  another mod's requirements don't allow").
- **Scenario:** A player ticks an update in Preview, it shows clean (no warning), they press Apply, and Apply
  refuses it because the real downloaded jar's `fabric.mod.json` declares a version range (`depends`/`breaks`) that
  conflicts with an installed mod's pin — a surprise refusal after the player already committed to the plan.
- **Root cause (verified):** `DryRunPlanner.java:37-41` — Preview's `Fetcher` returns a placeholder path via
  `SafeFileNames.resolveJar(modsDir, file.filename(), never)` that "is never created" (the file's own comment,
  line 18). `DownloadPlanner.jarOf()` (`DownloadPlanner.java:230-234`) calls `JarInfo.read(jar)` and
  `ModJars.rangesOf(jar, "depends"/"breaks")` on that path — since no bytes exist there, these read empty/`null`
  results, so `checkVersions()` (`DownloadPlanner.java:215`, body at `:455`) has nothing to check a range against
  for any Preview-planned jar. It isn't that Preview *skips* the check deliberately — it structurally cannot
  perform it without a real downloaded jar to read.
- **Options:**
  (a) Preview does a lightweight metadata-only fetch (HEAD/partial download of just `fabric.mod.json`, or use
  Modrinth API's own dependency metadata if it's granular enough) so H2's range check can run in Preview too — this
  closes the gap but adds real network cost/complexity to what's meant to be a fast dry run.
  (b) Preview's confirm screen/Apply's own status line already carries the refusal reason (per the residual note) —
  make sure the *player-facing* framing is "Preview couldn't fully check this; Apply may still refuse it" rather
  than presenting Preview as exhaustive, so the surprise is smaller even without (a).
  (c) Do nothing beyond (b): this is explicitly called out in ws-g1.md as a structural, accepted residual and is
  already disclosed in CHANGELOG.md.
- **Proposed:** (b) as a cheap v0.5 fix (a caveat line/tooltip on Preview's list, keyed off "this recommendation
  needs Modrinth's version-range data, unavailable in Preview"), with (a) flagged as a larger follow-up if the
  coordinator wants full parity. Given the CHANGELOG already discloses this and it's a UX/trust issue rather than
  data loss or a broken install, this is a genuine judgment call.
- **Test:** `PreviewScreenTest`/`PreviewGameTest` new case — a ticked item whose real fabric.mod.json would be
  refused by Apply shows the new caveat in Preview; existing `DownloadPlannerTest` H2 cases stay green (Apply's real
  behaviour unchanged).
- **Compatibility:** UI/text-only for (b); no format change. (a) would touch `DownloadInputs`/`DryRunPlanner`'s
  network behaviour and needs its own design pass.
- **Effort:** S for (b), L for (a). **Verdict: NEEDS A DECISION** (whether v0.5 does the cheap disclosure fix or the
  full metadata-fetch fix — recommend (b) given effort/risk, but this is a product call, not purely technical).

## L6 — StartupNotices saves settings.json on the shared `Probes.EXECUTOR` worker pool

- **Source:** not documented anywhere as a residual — found by tracing the actual save path per the coordinator's
  ask to "find it".
- **Scenario:** The very first time a player opens the title screen, `RigTuneClient.java:189-192` sets
  `settings.privacyNoticeShown = true` and calls `StartupNotices.takePrivacyNotice(settings, configDir,
  Probes.EXECUTOR)`.
- **Root cause (verified):**
  - `client/probe/Probes.java:7` — `public static final ExecutorService EXECUTOR = daemonPool(2, "RigTune worker");`
    — a generic 2-thread daemon pool shared by hardware/OSHI probe work, **not** dedicated to settings I/O.
  - `client/StartupNotices.java:13-22` — `takePrivacyNotice(...)` does `io.execute(() -> settings.save(configDir));`
    directly: a raw file write handed to whatever executor is passed in, with no dedup/ordering logic.
  - Compare `client/SettingsSaver.java:15-40` — the component every *other* settings write goes through: a
    dedicated single daemon thread ("RigTune settings"), a `synchronized` dedup (`save()` collapses a rapid double
    write into the same `CompletableFuture`), and a `flush(timeoutMillis)` that `RigTuneClient.java:85` calls on
    `CLIENT_STOPPING` (`SettingsSaver.shared().flush(2_000)`).
  - Because `takePrivacyNotice`'s write never goes through `SettingsSaver.shared()`, it (a) isn't included in the
    `CLIENT_STOPPING` flush — if the game exits very soon after the very first title screen, this write can be lost
    with no wait/guarantee at all — and (b) runs concurrently with any other in-flight `SettingsSaver` write to the
    same `settings.json` with no coordination between the two paths (last writer wins, could stomp a newer write
    with a stale in-memory `settings` snapshot depending on timing), and (c) shares a thread pool meant for
    hardware-probe blocking calls (OSHI) rather than fast, quit-time-sensitive file I/O.
- **Proposed fix:** change `RigTuneClient.java:192` to call `SettingsSaver.shared().save(settings, configDir)`
  instead of `StartupNotices.takePrivacyNotice(..., Probes.EXECUTOR)`'s raw `io.execute`; drop the `Executor io`
  parameter from `StartupNotices.takePrivacyNotice` (it becomes a pure state-flip method) and let the caller decide
  how to persist, matching every other settings mutation's pattern in the codebase.
- **Test:** a new unit/game test asserting the privacy-notice flag's save is observed by `SettingsSaver.shared().flush()`
  (i.e. it's actually queued on the dedicated saver), and — if feasible in a game test — that quitting immediately
  after the very first title screen still persists `privacyNoticeShown=true` (currently unguaranteed).
- **Compatibility:** internal only; `settings.json`'s shape is unchanged. Safe across all versions/downgrade.
- **Effort:** S. **Verdict: FIX** (real, if narrow, risk of a lost first-run flag or a lost write raced against
  `SettingsSaver`; the correct pattern already exists in the same class family).

## L7 — Discard's status line doesn't mention a kept half-applied group

- **Source:** docs/v0.4/design/ws-g2.md residuals ("Discard pending's status still says 'Discarded N pending
  change(s)' when a half-done group was kept... RealController's discard message is outside this package").
- **Scenario:** A helper run leaves a group half-applied (M2's `PartlyApplied` state: the disable succeeded, an
  enable didn't). The player presses "Discard pending changes". `Staging.discard()` correctly keeps that one group
  in pending.json (per M2's design, so the next exit can finish it) and discards everything else — but the status
  line the player sees doesn't say anything was kept.
- **Root cause (verified):** `RealController.java:666-683` (`discardPending()`) — `List<Op> dropped =
  staging.discard();` then unconditionally `return Component.translatable("rigtune.status.discarded",
  dropped.size());` (line 678). There is no branch for "and N more will finish at the next restart" — `dropped`
  only reflects what was actually discarded, and the kept group is invisible in the returned `Component`.
- **Proposed fix:** have `Staging.discard()` also return (or make queryable) what it kept — e.g. a small result
  record `{dropped: List<Op>, keptGroup: boolean}` — and add an `rigtune.status.discarded_with_kept` translatable
  ("Discarded %s pending change(s); one change already under way will finish next time you restart") that
  `discardPending()` uses when a group was kept.
- **Test:** new `RealControllerTest`/`StagingTest` case — Discard on a `PartlyApplied` state returns the kept-group
  signal and `discardPending()`'s returned `Component` differs from the plain case; an existing "clean discard"
  case stays on the current message (no regression).
- **Compatibility:** UI text only; pending.json's shape unchanged (M2 already made this data available, just not
  surfaced).
- **Effort:** S. **Verdict: FIX** (documented, disclosed-by-omission UX gap; the underlying safety mechanism already
  works, this is just visibility).

## L8 — History's baseline fold (M6) loses the "Profile: X" label

- **Source:** CHANGELOG.md Known issues ("History's oldest entries are folded into one baseline entry... a profile
  switch folded into it loses its 'Profile:' label."), README.md "Known limits" (same wording) — both explicitly
  shipped-with-disclosure in v0.4.0.
- **Scenario:** A player who has applied 50+ times (`Journal.MAX_ENTRIES = 50`, `Journal.java:35`) has their oldest
  entries folded into one synthetic baseline entry (SPEC 2o M6, `Journal.java:257-268`, `fold()`/`baseline()` at
  `:295`/`:341`). If one of the folded entries was a profile switch, the baseline entry no longer shows "Profile:
  Battery" the way the original entry did.
- **Root cause (verified):** `HistoryModel.Entry.profile` (`HistoryModel.java:85-87`) is documented as "the profile
  this entry switched to..., **from profiles.json**" — i.e. it's not stored on `JournalEntry`/history.json at all;
  it's cross-referenced live against a profile-switch record keyed by the *entry's own id*. `JournalEntry` itself
  (`JournalEntry.java:7-9`) has no `kind` value for "profile" (only `apply`/`benchmark`/`undo`/`legacy-import`) — a
  profile switch is an ordinary `apply` entry whose id happens to be in that separate index. `Journal.baseline()`
  (`Journal.java:341-370`) synthesizes a **new** entry from the folded run's `JournalChange`s only (settings/file
  changes), with a new id (the `BASELINE` prefix, `Journal.java:38`, `:276`) — nothing carries the old entry id (or
  a "this run included a profile switch to X" fact) into the new entry, so whatever keeps the profiles.json-side
  index (likely `ProfileStore`) has no matching key for the folded id and the lookup silently comes back null.
- **Proposed fix:** either (a) have `Journal.baseline()` record the *last* profile-switch id it folds (a new,
  optional field on the baseline entry, or reuse of an existing id-list mechanism) so `HistoryModel`'s lookup can
  still resolve it, or (b) have the profile-switch index itself get re-keyed to the baseline id when Journal folds
  (mirroring how M6 already had to keep Undo-all's reachability correct across a fold — this is the same class of
  problem, one more index to keep in sync).
- **Test:** `JournalTest`/`HistoryModelTest` — a run containing a profile switch, once folded past `MAX_ENTRIES` (or
  via `fold()` directly in a unit test), still reports the right `profile` value on the resulting baseline entry;
  round-trip through the pinned 0.3.0 reader (per M6's existing pattern) to confirm the baseline entry is still an
  ordinary Apply there too.
- **Compatibility:** needs care — whatever field/index change is chosen must not break the M6 guarantee already
  tested (`Journal.cap` round-trips through pinned/released 0.3.0 Journal). If a new field is added to
  `JournalEntry`, older readers (0.1.0-0.3.0) must tolerate an unknown field (Gson does this by default) and RigTune
  itself must handle its absence on entries written before this fix.
- **Effort:** S/M. **Verdict: FIX** (small, explicitly disclosed as a known gap the team wanted to close later, real
  player-visible loss of context on a history screen that exists specifically to give context).

## L9 — 2d residual: staged DISABLE_FILE ops aren't folded into the resolver's view

- **Source:** docs/v0.4/SPEC.md:290 ("Folding staged DISABLE_FILE ops into the resolver view (2d residual;
  under-blocking only)."), docs/v0.4/design/ws-a.md ("Residuals: staged disables aren't folded...").
- **Scenario:** Within one Preview/Apply session, a player stages "Disable ModX" and separately, an addition that
  would only be safe *because* ModX is going away (or conversely would conflict with ModX still being present) is
  evaluated against the **current on-disk** mods folder, not the "as if ModX's disable already happened" view —
  because it's under-blocking (SPEC's own word), the risk is a bad combination going through, not a good one being
  refused.
- **Root cause:** `A-H1`'s `StagedProjects.fold` (SPEC 2o/ws-a.md) produces a `stagedProjects` set read only by the
  incompatibility checks for **staged additions/updates** (ENABLE_FILE with `projectId`/`versionId`); it doesn't
  currently include projects staged for *disabling* in the same session (DISABLE_FILE ops), so the resolver's
  `installedProjects`/`loadedIds` view still treats a soon-to-be-disabled mod as present when judging a different
  ticked item in the same batch.
- **Proposed fix:** extend the staged-view fold (wherever `StagedProjects.fold`/the resolver's `installed`/`loaded`
  view is assembled — `DownloadPlanner`/`DependencyResolver`, following the H2/H3 pattern from ws-g1.md) to also
  subtract projects staged for disabling in this same session, mirroring how staged *enables* are already added.
- **Test:** `DownloadPlannerTest`/`DependencyResolverTest` new case — ticking "Disable ModX" and, in the same batch,
  an addition that conflicts with ModX still being present is now staged cleanly (currently it would be refused
  or allowed incorrectly depending on direction — confirm the exact under-blocking scenario against current
  behaviour before writing the red test).
- **Compatibility:** resolver/planning logic only; no format change.
- **Effort:** M. **Verdict: FIX** (explicitly called out by name in SPEC's own Deferred list as a real, if narrow,
  under-blocking gap — the "may defer" items from the same audit round, M4/M7/H1-B, all got fixed in Wave B; this
  one didn't and is still open).

## L10 — over-blocking: a mod staged for disabling still counts as present in resolver checks

- **Source:** docs/v0.4/design/ws-g1.md ("Residuals: ... a mod staged for disabling still counts as present
  (over-blocking only)").
- **Why NOT WORTH IT:** the direction of the error is safe (a player is told "no" slightly more often than strictly
  necessary, never "yes" when the real answer is "no"). Fixing it requires the same staged-disable-fold work as L9,
  but L9 is the direction that actually matters (under-blocking); once L9 is fixed, this over-blocking case is very
  likely fixed as a side effect (same fold mechanism, opposite direction). Recommend folding this into L9's test
  matrix rather than treating as a separate item — no separate work needed if L9 is done thoroughly.

## L11 — a top-level mod without a hash or file counts as nested (dev-only edge case)

- **Source:** docs/v0.4/design/ws-g1.md ("Residual (coordinator: use the sha1 signal): a top-level mod with neither
  a hash nor a mods/ file (loaded from a directory or a multi-path origin, dev only; or a jar outside mods/ whose
  hash failed) counts as nested, so a Modrinth copy of it could be staged next to it.").
- **Why NOT WORTH IT:** the doc itself says this only happens in a dev environment (a mod loaded from an exploded
  directory rather than a jar) or when a jar's own hash read fails — neither is a normal player's mods folder.
  Fixing `DownloadPlanner.topLevelIds()` (`H3`, `DownloadPlanner.java`) to special-case this would add complexity
  for a case real players essentially never hit.

## L12 — asymmetric refusal: a dependency-level incompatibility between two additions fails only the later one

- **Source:** docs/DESIGN.md:364 (ws-a.md fold-in: "Residuals: ... a dependency-level incompatibility between two
  additions still fails the later one").
- **Why NOT WORTH IT (for now):** this is a minor UX inconsistency (which of two mutually-incompatible additions
  gets the refusal message depends on processing order), not a correctness or safety issue — one of the two is
  always correctly refused, a bad combination never goes through. The 2e precedent SPEC already established
  ("RigTune can't know which one the player wanted... refused together, each naming the other") was deliberately
  applied to the *pinned-version* conflict case (H2) but not this one; making them consistent is a genuine
  improvement but low severity. Recommend leaving as-is unless the brainstorm surfaces a related resolver-quality
  feature that would touch this code anyway.

## L13 — downgrading to 0.3.0 can't see the newer helper's unfinished-groups record

- **Source:** docs/DESIGN.md:375 (ws-g3.md fold-in: "residual: 0.3.0's own helper can't see it, so after a
  downgrade a group 0.3.0 can't finish leaves the old jar disabled (0.3.0's own behaviour)").
- **Why NOT WORTH IT:** the residual is explicitly "0.3.0's own behaviour" — the already-released, immutable 0.3.0
  jar has no code path that reads `config/rigtune/helper/unfinished-groups.json` (that file didn't exist when 0.3.0
  shipped). Nothing v0.5 does to RigTune's *current* code changes what an old, already-shipped jar does. The only
  real lever is documentation (README/CHANGELOG already disclose downgrade behaviour); no code fix is possible here
  without also patching 0.3.0, which isn't on the table.

## L14-L19 — feature-level v0.4 deferrals (not defects; scope calls for the brainstorm/P1, not P0.2)

These are explicit "Deferred / not in v0.4" SPEC entries that are genuinely new features or scope decisions, not
bugs a player "hits" — flagging them here for completeness per the harvest brief, but they don't belong in a
"fix every deferred issue and low, with a test" pass:

- **Shader-pack profile switching** (P2 item 12) — needs a new helper op type; pack values are unsafe to carry in
  share codes (untrusted shader-pack option data). A real feature, not a fix.
- **Jar-level optional-mod toggles** (`Action.EnableMod`, a restart, a new helper op) — same category, new feature.
- **An entity/redstone-heavy benchmark scene** — a new benchmark scenario, not a fix to an existing one.
- **A full non-English translation** — needs a native-speaker verification pass RigTune can't self-certify; rule
  text is deliberately English-only until then.
- **Quilt support** — QSL/QFAPI retired at 26.1; whether Quilt Loader even loads Fabric API on 26.x is unverified;
  a support-scope decision, not a defect in RigTune.
- **Controller-specific support** — Controlify already drives vanilla focus and WS-X's a11y focus work already
  rides on vanilla focus, so this is "verify it works" (owned elsewhere, verification gap) more than "build it" —
  listed here rather than in Owned Elsewhere only because SPEC frames it as a deferred feature, not just unverified.

**Verdict for all of L14-L19: NEEDS A DECISION at the P1/brainstorm level, out of P0.2's scope.**

## L20 — deliberate scope exclusions (not bugs, not deferred by accident)

Automatic battery switching (offer only, by design — the battery hook only offers, never switches itself, per
docs/DESIGN.md:25); "Apply" buttons directly on stutter advice (also a docs/DESIGN.md:25 explicit non-goal);
system-wide CPU load in the Doctor (no cheap API on Windows, per SPEC's Deferred list); per-mod startup attribution
(Fabric Loader 0.19.5 exposes no per-mod timing at all — a platform limitation, not something RigTune chose not to
build).

**Verdict: NOT WORTH IT** — each has an explicit, still-valid reason in DESIGN.md/SPEC.md; none are silently
dropped, and none are newly reachable defects.
