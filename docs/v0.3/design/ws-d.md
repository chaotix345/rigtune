# WS-D (rules) design notes

Branch `feat/rules-v03`. Items: SPEC 7 (hardware tiers), 12 (spark), item 1's `vulkan-backend` and change C, 3d (bot PRs), with the plan-review amendments D-H1, D-M1, D-M2, D-L1 and V-L1. Plan: docs/v0.3/plans/ws-d.md.

## Status

| AC | status | evidence |
|---|---|---|
| AC7.1 | verified | GpuClassifierTest: 29 new CASES (research §4), `newModelsMatchTheirOwnRows`, `newRowsDoNotCatchOtherModels` (5050 Ti, plain `Intel(R) Graphics`, Arc Pro A60, 5050 Laptop, 9070 XT, Arc B390). CpuClassifierTest: `newPartsUseTheExistingRows`, `lunarLakeIsNotAKSeriesPart`. |
| AC7.2 | verified | RulesV1DifferentialTest: `rtx-5070`, `rx-9070-gre`, `arc-pro-b60`, `ryzen-9800x3d` (tier-5 CPU, heap 6-8 GB) pass `repoRulesV1AddsNoTickedActionAndLosesNoWarning` with no difference; `newHardwareKeepsItsV010Classification` (pinned v0.1.0 classifier, baseline vs new rules-v1.json, 13 GPU + 4 CPU strings); `theDifferentialCatchesTheNewTierRowsInV1` shows the GRE and Arc Pro entries fail if the rows reach v1; `tierTablesStayAtV010` (review M1) fails if rules-v1.json's tier tables or vendor fallback ever differ from 0.1.0's. |
| AC7.3 | verified | tools/tests/test_projection.py `TierV1Tests` (8), test_check_rules_v1.py (4 new), SchemaConsistencyTest `sourceOnlyTierFieldsAreNoClientField`. |
| AC7.4 | verified | one live run from feat/v0.3.0's revision 10 → 11 (log below; re-derived once after the review's spark wording fix, again from revision 10); `check_rules_v1.py` OK; `cmp` rules-v2.json = bundled copy; `./gradlew build` green on 26.2 and 26.3 (858 tests each, 0 skipped). |
| AC12.1 | verified | KnowledgeV2ScenarioTest `sparkAdviceOnlyWithSpark`. |
| AC1.4 | verified | test_update_rules.py `TargetVersionTests` (fake tag list with 26.4, 26.4.1, 26.3.x hotfixes, an rc, `26.20`), `StonecutterNodesTests`, `MainTargetTests`; the live run printed `target MC versions: 26.3, 26.2`. |
| AC1.7 | verified | KnowledgeV2ScenarioTest `vulkanBackendAdviceOnlyBefore264` (Vulkan on 26.2/26.3/26.3.1 shown; 26.4-alpha.1, 26.4-rc.1, 26.4, 26.4.1 not; OpenGL never); RulesV1DifferentialTest `vulkanBackendAdviceKeepsItsV010Condition`. |
| AC3.4 (3d) | no PR seen | see "Bot PRs" |

## Decisions

### Tier rows: source-only `"v1": false`
- `SOURCE_ONLY_TIER_FIELDS = {"gpuTiers": {"v1"}, "cpuTiers": {"v1"}}` in update_rules.py, separate from `V1_RULE_FIELDS`, so SchemaConsistencyTest's field comparison with the Java classes is unchanged. A new test asserts the source-only keys are no field of the v1 (pinned 0.1.0) or v2 tier classes.
- Only `false` is accepted. An override object (a different tier for 0.1.x) was left out on purpose: by RulesV1DifferentialTest's definition neither a higher nor a lower tier is "more conservative" (plan review D-H1), so the only safe v1 decision for a new row is "0.1.x doesn't see it". `v1` on `heapTiers` is an error (nothing needs it).
- `v2_content` strips the key; `v1_projection` omits the row and adds `("gpuTiers[<source index>] <pattern>", 'omitted ("v1": false)')` to REVIEW.md (d). check_rules_v1.py needed no change: it rebuilds both outputs through `ur.v2_content`/`ur.v1_projection`, and its shape check already rejects a `v1` key on a v1 tier row (tests cover a leaked row, a leaked key in v1, and a leaked key in v2).

### New GPU rows (all `"v1": false`, D-H1)
| row | inserted before | today (v2 and v1) | v2 after | 0.1.x after |
|---|---|---|---|---|
| `RTX\s*5050\b(?!\s*Ti)` nvidia, tier 3 | the RTX 20x0/3050/GTX 10x0 tier-3 row | vendor fallback 3 | 3 (explicit) | fallback 3 |
| `RX\s*9070\s*GRE\b` amd, tier 4 | `RX ... 90[7-9]0` tier 5 | tier 5 | 4 | 5 |
| `Arc(TM) Pro B50` intel, discrete, tier 3 | the Arc catch-all `\d{3}V\|B3\d0\|Graphics` | Intel fallback 2, discrete | 3 | 2 |
| `Arc(TM) Pro B[67]\d` intel, discrete, tier 4 | same | Intel fallback 2, discrete | 4 | 2 |

- **Research correction.** hardware-tiers.md §1 (and SPEC item 7) say the Arc Pro B50/B60 "fall into the integrated tier-3 catch-all". They don't: that row needs `Arc(TM)` directly followed by `\d{3}V`, `B3\d0` or `Graphics`, and "Pro" sits in between. GpuClassifierTest's RED run showed `Intel(R) Arc(TM) Pro B60 Graphics -> GpuClass[vendor=INTEL, integrated=false, tier=2, matchedPattern=null]`: the Intel vendor fallback (2), discrete through the `\barc\b` heuristic. The new rows still help (tier 2 → 3/4, and `integrated: false` explicit rather than heuristic), and the v1 decision is unchanged (the rows raise the tier).
- The 5050 Laptop keeps landing on the laptop catch-all (tier 2), which sits earlier in the file; a hypothetical 5050 Ti falls through to the fallback (tested).
- The Panther Lake `B3\d0` iGPU alternative in the catch-all is untouched; the new rows need the literal "Pro", so they can't swallow B370/B390 (tested with B390).
- No CPU rows (SPEC item 7): the 9000X3D, Core Ultra 200S K and Lunar Lake cases are regression tests only.

### vulkan-backend (item 1, D-M2)
- `"when": {"backend": ["vulkan"], "mcVersionRange": "<26.4-"}` with `"v1": {"when": {"backend": ["vulkan"]}}`.
- The trailing `-` matters: Loader normalizes 26.4-snapshot-1 to `26.4-alpha.1`, a semantic pre-release of 26.4, so `<26.4` would still fire on every 26.4 snapshot and pre-release. `<26.4-` is FALSE from the first snapshot on (tested with 26.4-alpha.1 and 26.4-rc.1 through Fabric Loader's own `VersionPredicate`, which 0.2.0's ConditionEvaluator also uses, checked at tag v0.2.0).
- 0.1.x keeps the exact v0.1.0 `when` (the v1 override); 0.1.0 declares `"minecraft": "~26.2"` (tag v0.1.0 fabric.mod.json), so a 0.1.x client never runs on 26.4 anyway.
- No 26.4 replacement advice ("try OpenGL if you have problems") was added: 26.4 isn't a node, and its wording should be written against the real 26.4 release.

### spark hint (item 12, D-L1)
- `spark-profiler`: `when {"modPresent": ["spark"]}`, info, impact low, `"v1": false`, 334 characters (the longest existing advice text is 333; the scenario test caps it at 340).
- Text: "Run /sparkc profiler start, play through the lag, then /sparkc profiler stop and open the link it prints: under Render thread, the highest percentages cost the most time. Stopping uploads the profile to spark.lucko.me with your player name and UUID, mod list, system details and Java launch arguments; anyone with the link can see it." (Review L4: the viewer opens in the call-tree view with percentages, not the flame view's bar widths; the JVM launch arguments usually carry paths with the OS account name, so they're named explicitly.)
- Sources (read 2026-09-26 with the gstack headless browser):
  - spark.lucko.me/docs/Command-Usage: `/sparkc` replaces `/spark` on Fabric/Forge clients; `profiler stop` stops and shows the results; `profiler cancel` stops "without uploading the results"; `stop --save-to-file` saves instead of uploading; `--timeout`, `--thread *`.
  - spark.lucko.me/docs/Using-the-viewer: "your profile will be automatically uploaded to the viewer, and you will be presented with a link. You can freely share this link".
  - lucko/spark `spark-common/src/main/proto/spark/spark_sampler.proto` `SamplerMetadata`: `creator` (CommandSenderMetadata: name, unique_id), `platform_metadata` (incl. minecraft_version), `system_statistics`, `sources` (plugin/mod metadata), `server_configurations`; `spark.proto` `SystemStatistics`: cpu (threads, model_name), memory, disk, os (arch, name, version), java (vendor, version, vm_args), jvm, net interfaces. Hence "player name and UUID, mod list, system details and Java launch arguments".
- The five reading tips were cut to one (percentages under Render thread) for length (D-L1). `--timeout 60` and `profiler cancel` were left out for the same reason.
- Title-screen toast: in 0.2+ the toast is shown only when a warning or a high-impact recommendation exists (`RigTuneClient.important`); the spark hint can't trigger it, but adds 1 to the count the toast shows when it appears. 0.1.x never sees the hint.

### Change C: MC targets
- `stonecutter_nodes(settings.gradle)` finds the Stonecutter `versions '…', '…'` call (either quote style, with or without parentheses, across lines; tested against the repo's settings.gradle, which must list exactly the `versions/*/` folders). A missing file or list exits 1 with a message; `--mc-versions` skips it.
- Targets = the nodes plus Modrinth `release` tags that are exactly `<node>.<digits>`, newest first. 26.4 and 26.4.1 never become targets until 26.4 is a node, so no hotfix can push out 26.2. A node Modrinth doesn't list (yet) is still a target.
- `version_sort_key` now sorts a pre-release id below its release (`26.4-snapshot-1` < `26.4` < `26.4.1`); before, mixing a snapshot node with releases would have raised TypeError.
- update-rules.yml needs no change (it runs the updater from the checkout root, where settings.gradle is).
- Effect of the live run: targets `26.3, 26.2` (no hotfix exists), so `availability["26.1.2"]` is gone from both files. No RigTune release runs on 26.1.x (0.1.0 is `~26.2`, 0.2.0 has nodes 26.2 and 26.3).

## rules-v1.json diff vs feat/v0.3.0 (revision 10 → 11), line by line
```
-  "revision": 10,
-  "generatedAt": "2026-09-25T07:10:27Z",
+  "revision": 11,
+  "generatedAt": "2026-09-25T19:14:14Z",
@@ availability
-    ],
-    "26.1.2": [
-      "asynclogger", ... 27 slugs ... "zfastnoise"
     ]
```
- `revision` 10 → 11: the one updater run bumps both files together (rules-v2.json is also 11). 0.1.x uses the candidate with the highest revision (v0.1.0 RulesSources.java:105), so 0.1.x clients pick up revision 11; since nothing they evaluate changed, they behave exactly as on revision 10.
- `generatedAt`: a timestamp, not evaluated.
- `availability["26.1.2"]` removed: 0.1.x reads availability only for the running MC version, and 0.1.0 requires `~26.2`, so no 0.1.x client can run on 26.1.2. `availability["26.2"]` and `["26.3"]` are byte-identical to revision 10.
- Everything else is identical, which is what makes it safe:
  - `gpuTiers`/`cpuTiers`/`heapTiers`/`gpuVendorFallback` unchanged: the four new rows are `"v1": false`. The pinned v0.1.0 classifier gives every new hardware string the same vendor, integrated flag, tier and matched row under the baseline and the new file.
  - `advice[vulkan-backend]` unchanged: the v1 override keeps `{"backend": ["vulkan"]}` (asserted equal to the v0.1.0 baseline).
  - No `spark-profiler` advice (`"v1": false`).
  - mods, obsolete, settings, other advice, upstream: unchanged (the live Modrinth/pack data hadn't moved since revision 10).
- RulesV1DifferentialTest (18 hardware profiles incl. the 4 new ones × 15 mod sets × 3 settings snapshots × 3 goals) reports no added, newly ticked or lost recommendation against the baseline; check_rules_v1.py passes.

## rules-v2.json for 0.2.0
- The four tier rows use only the fields 0.2.0 knows; the patterns are valid Java regexes under 200 characters (the lookahead in the 5050 row included). 0.2.0 classifies the GRE as tier 4 and the Arc Pro cards as 3/4, which is the intended v2 change.
- `mcVersionRange` is a key 0.2.0 evaluates (v0.2.0 ConditionEvaluator.java:101, :288); on 26.2/26.3 it's TRUE, so 0.2.0 shows the Vulkan advice as before.
- `spark-profiler` uses only `modPresent`.

## Bot PRs (3d, D-M1)
- 2026-09-26: `gh pr list --state all` shows only #1-#3 (merged, not bot PRs); update-rules ran on main 2026-09-24 and 2026-09-25 (workflow_dispatch, success) without opening a PR. No `bot/rules-update-*` PR appeared while WS-D worked.
- The next cron is Monday 2026-09-28 03:00 UTC, on main (still the top-3 target logic until v0.3.0 merges). If it opens a PR it will be at revision 11 on main, colliding with this branch's 11: per D-M1 the coordinator triages it by re-running the updater on feat/v0.3.0 (not merging the PR), and Phase 7 re-runs the updater after merging origin/main so the release revision is above main's.

## Self-review (code-reviewer subagent on `git diff feat/v0.3.0...HEAD`)
0 High, 1 Medium, 9 Low. The reviewer also re-checked `<26.4-` with Loader 0.19.5's `VersionPredicate` (TRUE for 26.2, 26.3, 26.3.1, 26.3.1-rc.1; FALSE for 26.4-alpha.1, 26.4-beta.1, 26.4-rc.1, 26.4, 26.4.1) and simulated the classifier over about 27 strings (no row catches anything unintended).

| finding | disposition |
|---|---|
| M1 D-H1 only enforced for the listed strings | fixed: `tierTablesStayAtV010` (rules-v1.json tier tables and vendor fallback = 0.1.0's) |
| L1 settings.gradle comments / several lists | fixed: Groovy comments stripped (strings kept), the list must start a line, exactly one list; tests |
| L2 pre-release ids sorted as strings | fixed: snapshot < pre < rc, numeric parts as numbers; test |
| L3 hotfix targets grow | documented in tools/README.md (each hotfix is a version a player can run; the list shrinks when a node is dropped) |
| L4 spark wording (call-tree view; UUID and JVM args) | fixed: new text, test asserts "UUID" and "launch arguments"; revision 11 re-derived from revision 10 in one run |
| L5 tier sections not type-checked | fixed: "'<kind>' must be an array"; test |
| L6 doc section under HeapTierRule | fixed: own `##` heading (anchor unchanged) |
| L7 SPEC item 7's Arc Pro claim | not changed (SPEC.md is the coordinator's): the correction is above, under "New GPU rows" |
| L8 settings.gradle read from the script's repo | documented in tools/README.md |
| L9 heapTiers `v1` message | fixed: dedicated message; test |

## Other notes
- REVIEW.md (d) prints tier rows as raw regexes; GitHub markdown drops a backslash before punctuation, so `\(TM\)` renders as `(TM)`. Cosmetic (the table is for triage); wrapping the pattern in a code span would fix it.
- Shell quirk (for later agents): the Bash tool collapsed `\\` in a heredoc, which broke a scripted JSON edit; edit knowledge.json with the Edit tool.

## UNVERIFIED
- The literal renderer strings for RTX 50, RX 9070 GRE and Arc Pro B-series cards (research: from the naming convention, no log found). A different string would fall back exactly as before.
- `<26.4-` on a real 26.4 client (only checked through Fabric Loader's `VersionPredicate` with the normalized ids).
- The spark commands weren't run in-game; the upload's player name/UUID and JVM arguments are from spark's schema (the command sender, `SystemStatistics.java.vm_args`), not observed in a client profile. The viewer's default view (call tree with percentages) is per the reviewer, not checked in a live viewer.

## Regeneration log (revision 11; the second run, from revision 10 again, printed the same)
```
target MC versions: 26.3, 26.2
Fabulously Optimized: newest available = 26.3, 38 mods
Additive: newest available = 26.3, 50 mods
REVIEW.md: {'new_upstream': 0, 'status_or_removed': 0, 'missing_fabric': 4, 'v1_projection': 33}
revision 10 -> 11
```
