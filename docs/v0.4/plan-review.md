# RigTune v0.4.0: spec and plan review

Scope: docs/v0.4/SPEC.md and docs/v0.4/PLAN.md (branch feat/v0.4.0 at a6fe7db), checked against docs/research/v0.4/*.md (jvm-gc.md §3-6 were still placeholders, so item 6 is reviewed as a draft), the source at that commit, the released tags (`git show v0.1.0:/v0.2.0:/v0.3.0:`), and the Minecraft, Fabric API, gametest-API, Sodium and Stonecutter jars in the Gradle/Loom caches (`javap`, JDK 25.0.4.1). File:line references are to a6fe7db unless a tag is named. Nothing was built and the game wasn't launched. One throwaway Java probe was run against the cached Stonecutter 0.9.8 jar (WS0-M1).

**Result: 4 high, 11 medium, 13 low.** The four highs:
1. **P-H1**: the new same-key patch replacement records the discarded switch's staged value as the next switch's `before`. After two switches and a restart, Undo last restores the discarded profile's config values, and Undo all leaves them on disk (for example Battery's "DH off" and "shaders off").
2. **A-H1**: 2d adds staged projects to `installedProjects`, which the resolver also uses to skip required dependencies. A later addition that needs a staged mod is staged without it and in a separate group. If the earlier group is then undone, discarded or abandoned, the game won't start (missing dependency).
3. **B-H1**: putting the mod set in the "comparable" key contradicts item 7's own change window and AC7.5. After any mod update the latest run isn't comparable, so the main scenario (a mod update, then lower 1 % lows) can never raise a regression.
4. **W-H1**: the server render-distance clamp recommends lowering render distance (16 → 10) when that gains nothing on the server, because vanilla already renders min(client, server). After Apply, the player's singleplayer and other servers are stuck at the lower value. AC8.1 requires exactly this behaviour.

IDs: `WS0` = WS-0 (foundation), `K` = WS-K (contracts), `H`, `A`, `R`, `P`, `S`, `J`, `B`, `W`, `F` = the Wave A workstreams, `X` = cross-cutting (not WS-X, which has no findings).

---

## WS-0: snapshot canary (SPEC 1)

### WS0-M1: `//? if >=26.4-snapshot-1` is false for 26.4 pre-releases and release candidates, so the canary fails when nothing is wrong
- **Problem.** Stonecutter compares pre-release tags by semver string order, and `pre` and `rc` sort before `snapshot`. When Mojang moves to `26.4-pre-1` and then `26.4-rc-1` (the manifest's `latest.snapshot` points at those), the canary's throwaway node builds the old-API branch. That branch no longer compiles (`getBaseHeight` and `getUncachedNoiseBiome` are gone since snapshot-1), so the canary goes red and opens its issue for code that is correct.
- **Evidence.** Probe against `dev/kikugie/stonecutter/0.9.8/stonecutter-0.9.8.jar` (`SemanticOperations.INSTANCE.eval(v, ">=26.4-snapshot-1")`):
  - 26.2 → false; 26.3 → false; 26.4-snapshot-1 → true; 26.4-snapshot-2 → true;
  - **26.4-pre-1 → false; 26.4-rc-1 → false**; 26.4 → true.
  - The same probe with `>=26.4-alpha` or `>=26.4-0` gives false for 26.3 and 26.3.1, and true for every 26.4 snapshot, pre-release, RC and release.
  - `>=26.4-` throws "No pre-release component defined".
  - SPEC:37; mc-versions.md §3.2 (the trial only compiled `26.4-snapshot-1`).
- **Fix.**
  - Write the two blocks as `//? if >=26.4-alpha {` (or `>=26.4-0`).
  - Add a note to tools/MC_VERSIONS.md: a snapshot-introduced break must use the `-alpha` floor, never `-snapshot-N`.
  - Add a `tools/tests` case that runs the node-name ordering through the same comparison: a Python mirror of semver pre-release ordering, asserting that pre/rc names don't sort below `snapshot`.

---

## WS-K: shared contracts (SPEC C1-C7)

### K-M1: The new condition field types break the "a bad value poisons only its condition" rule of v0.2
- **Problem.** C2 adds the stutter keys as `Condition` fields. Research §5.2 shapes them as `{"stutterShareAtLeast": {"gc": 30}}`, which is naturally a `Map<String, Integer>`, and as fractional thresholds (`spikesPerMinuteAtLeast`), which are naturally `Double`.
  - `ConditionAdapterFactory.readable()` only vets `Boolean`, `Integer`, `Long` and `Map` (by "values are primitives"). A `Double` field accepts any JSON.
  - A `Map<String, Integer>` accepts `{"gc": "x"}`, and then Gson's delegate throws `NumberFormatException`/`JsonSyntaxException`.
  - `RulesLoader.parse` turns that into "Rules file is not valid JSON" and rejects the whole remote document. So one malformed value in a future rules-v2.json stops every 0.4 client from taking rules updates. This is exactly what the adapter's header says it prevents.
- **Evidence.** ConditionAdapterFactory.java (identical in v0.2.0, v0.3.0 and HEAD) `readable()` and the class comment ("a map field ... that isn't an object of strings ... (Gson would reject the whole document)"). RulesLoader.java:44-52 (v0.2.0). stutter.md:406-423. SPEC:145, :232.
- **Fix.**
  - Define every new `Condition` field as one of the adapter-vetted types: `Integer`/`Long`, with shares as whole percent and rates ×10; `List<String>`; `Map<String, String>`, parsed by the evaluator (UNKNOWN on a bad number, as `driverVersion` already does).
  - Or extend `readable()` for `Double` and typed maps in the same commit.
  - Add a ConditionAdapterFactoryTest case per new key with a wrong-typed value: the condition is poisoned, and the document still loads.

### K-L1: Which pinned copies exist is inconsistent, and some ACs need copies nobody pins
- SPEC:11 puts `LegacyConditionFailClosedTest`'s copies under `v020/core/rules/`. PLAN:66 has WS-K pin `v030/` "(history, apply, benchmark, rules)" and "v010 PendingActions if missing". Only `v010/…` and `v020/core/benchmark` exist today. core/rules is byte-identical in v0.2.0 and v0.3.0 (`git diff v0.2.0 v0.3.0 -- core/rules` is empty), so one copy is enough, but pick one package and say so.
- AC6.3 ("with the pinned 0.2.0 classes and 0.3.0's empty `SUPPORTED_FEATURES` every `jvm-*` rule is skipped") needs a pinned `Recommender.supported()`/`SUPPORTED_FEATURES`. No workstream pins it.
- A "verbatim" `ConditionEvaluator` copy imports the *current* `core.model.*`, `core.hardware.GpuClassifier` and `core.recommend.SettingValues` (ConditionEvaluator.java:3-12). It stays verbatim only while those classes don't change. v010 pinned its model classes for this reason.
- **Fix.** Put the exact pinned file list into `docs/v0.4/design/ws-k.md`:
  - rules: Condition, ConditionAdapterFactory, ConditionEvaluator, EvalContext, Truth, RulesDocument, RulesLoader, BudgetedChars;
  - plus a pinned `Recommender.supported` + `SUPPORTED_FEATURES` stub;
  - and note which current model classes the copies compile against.

---

## WS-H: self-update E2E and the released-jar harness (SPEC 3)

### H-M1: The downgrade run and the released-jar harness need 0.4-written files, which WS-H can't produce and nobody else is asked to provide
- **Problem.** AC3.2 (`downgrade-040-to-030`) has 0.4 write:
  - a profile switch;
  - a staged Apply with `projectId` and `modName`;
  - benchmark context fields;
  - `stutter.json`, `awareness.json`, `server-limits.json` and `startup-times.json`.

  AC3.3 feeds 0.4-written history/pending/benchmarks/settings and the regenerated rules to the released 0.3.0 classes. Those files only exist once WS-P, WS-A, WS-B, WS-S, WS-W, WS-F and WS-R have merged. Yet WS-H is "early start: independent of the contracts commit" (PLAN:55-59), and its task list covers neither the harness nor the feature drivers. PLAN:150 assigns the harness to WS-H only in the self-review line.
  - Driving each feature through the e2e client is expensive and fragile. `server-limits.json` alone needs a dedicated server connection.
- **Evidence.** SPEC:99-100, :103. PLAN:57-59, :150. profiles.md §3 (the harness method).
- **Fix.**
  - Seed instead of drive. Each feature workstream commits a small "written by 0.4" fixture produced by its own unit or game test, under `src/test/resources/v040-written/` (profiles.json plus a history.json with a switch entry; pending.json with `projectId`; history.json with `modName`; benchmarks.json with the new context fields; stutter.json; awareness.json; server-limits.json; startup-times.json).
  - The downgrade run copies these into the instance before starting 0.3.0, and the released-jar harness reads them.
  - Add the fixture to each feature workstream's Owns list. Add a Phase 5 step, owned by the coordinator, that regenerates them from the RC and re-runs AC3.2/AC3.3.
  - Write the released-jar harness into WS-H's Owns and Tasks. It needs fabric-loader and slf4j on its classpath: 0.3.0's `RulesLoader`/`ConditionEvaluator` load `RigTune.LOGGER` and `VersionPredicate`.

---

## WS-A: deferred defects (SPEC 2)

### A-H1: 2d's fold into `installedProjects` makes the resolver treat a staged dependency as installed
- **Problem.** SPEC 2d folds the projectIds of pending ENABLE_FILE ops into `installedProjects` "so DependencyResolver's existing installed-project path refuses" incompatibilities. `DependencyResolver.resolve` uses the same set for a second purpose: it seeds `seen`, and a required dependency whose project is in `seen` is never queued. So with the fold:
  - Apply 1 stages X (group G1).
  - Apply 2 adds Y, which requires X. X is "installed", so Y is staged alone in group G2, with no link to G1.
  - v0.3 re-resolves X instead. The ENABLE_FILE for the same mod id replaces the staged one and joins the groups (PendingActions.java:160-178). The planner's own comment says batch-staged projects are kept out of `projects` on purpose, "so they come back from the resolver and are joined".

  Any of the following then leaves Y without X, and Fabric refuses to start:
  - "Undo this" on Apply 1 (a STAGED change drops its whole group, G1 only);
  - the helper abandoning G1 after 3 failed runs (whole group, and G2 still applies);
  - a download failure for X at the next exit.

  Today's worst case is a re-download. With the fold it's a game that won't start.
- **Evidence.**
  - DependencyResolver.java:95, :100, :119 (`seen = new HashSet<>(installedProjectIds)`; dependencies skipped when seen).
  - DownloadPlanner.java:141, :144, :238-240.
  - UndoPlanner.java:26; ApplyExecutor.java:171-189, :193-221.
  - SPEC:57.
- **Fix.**
  - Keep `installedProjects` as it is. Pass the staged projectIds as a separate `stagedProjects` set that only the incompatibility checks read (`refuseIncompatible` :161 and `checkUpdate`), never `seen`.
  - Alternatively, when a resolution needs a staged project, join the new ops to that project's staged group, the same way `groupOfProject` does inside a batch. That needs the op's group, which pending.json already has.
  - Add to AC2d.1: an addition that *requires* a staged mod is joined to the staged mod's group. Undo of the first Apply then drops both (or refuses), never Y alone.

### A-M1: The "reverse declaration" half of AC2d.1 can't be checked from a projectId
- **Problem.** "Both directions" means a staged mod's own version declaring a later addition incompatible should also refuse it. The resolver checks the other side's declarations through `installed.values()`: the `ModrinthVersion` objects of installed jars, fetched by hash (DependencyResolver.java:36, :57, :175-186). A pending op carries only `projectId` (SPEC:13, :57), so the staged version's dependency list isn't available.
  - Refetching the project's latest version can pick a different version from the one staged.
- **Evidence.** DependencyResolver.java:175-186; SPEC:57, :59.
- **Fix.** Store the Modrinth `versionId` next to `projectId` on ENABLE_FILE ops (optional, same rules). Let `StagedProjects` return `(projectId, versionId)`. Have the planner fetch those versions (they're cached for the run) into the resolver's `installed` map, marked as staged, so both directions use the existing code. Record the network dependency: with Modrinth off, the reverse check is skipped and the forward check still works.

### A-L1: Loose ends in 2d
- Every copy-with method must carry `projectId`: `inGroup`, `withModId`, `withAttempts` (PendingActions.java:68-78). Otherwise a merge regroup, or the 0.4 helper's retry rewrite (`op.withAttempts(...)`, ApplyExecutor.java:214), silently drops it. Add a PendingActionsTest round trip through each.
- Build the fold from the *relocated* view (`PendingActions.relocated`, as Staging does), so a copied instance's foreign ops don't block anything.
- SPEC:57's line references are off. RealController.java:519 is download time and :724 is Preview. There's no report-time site. Name the two call sites by method (`download()`, `preview()`).

---

## WS-R: rules (SPEC 2k, 2l, C2 tools side, content)

### R-L1: "An unknown key fails closed" is only safe when not firing is the conservative outcome
- On 0.2.0 and 0.3.0 an unknown key makes a condition UNKNOWN, and the rule doesn't fire. That's safe for recommendations and warnings. It is *not* safe for rules whose firing is the protection:
  - a clamp (`Recommender` skips a clamp whose `when` isn't TRUE, Recommender.java:343);
  - an `avoidWhen` (no disable offered);
  - a `skipUpdateWhen` (update offered).

  SPEC:189 says `driverVersion` "needs no `requires` (it fails closed on 0.2/0.3)". That's true for the two planned warnings, but it's stated as a general rule.
- **Fix.** In update_rules.py, refuse a v2-new key (`driverVersion`, anything in `STUTTER_CONDITION_KEYS`) inside a clamp entry, `avoidWhen` or `skipUpdateWhen` unless the rule has `requires`, and add a Python test. Reword SPEC:189 to "fails closed, which is safe for advice and value entries".

---

## WS-P: Profiles and share codes (SPEC 4)

### P-H1: Same-key patch replacement records the wrong `before`, so undoing a switch restores a value the player never had
- **Problem.** The flow with two switches and a restart:
  1. The file has V0. Switch 1 (Battery) stages `PATCH key=V1` (op1). Its journal change is recorded as `V0 → V1`.
  2. Switch 2 (Max FPS) stages `key=V2`. Under the new rule, op2 replaces op1 and op1's change is marked DISCARDED.
  3. `StagedChanges.of` records op2's change with `before = stagedValue(base, …)`, and `base` still contains op1, so it records `V1 → V2`.
  4. At restart the helper writes V2 and the change becomes APPLIED.

  Undo then goes wrong:
  - `UndoPlanner.planSettings` only considers APPLIED setting changes and restores the oldest `before` in the chain. The DISCARDED V0 → V1 change isn't in it.
  - **Undo last** therefore stages V1: Battery's values, which were never on disk.
  - **Undo last** again (switch 1) reverts only switch 1's vanilla keys, so its staged keys stay at V1.
  - **Undo all** ends with every staged key at Battery's value: DH `rendererMode` DISABLED, shaders off, and Sodium values.

  v0.3 has no such bug: without replacement both ops stay, the helper applies them in order, and the chain V0 → V1 → V2 is consistent.
- **Evidence.**
  - StagedChanges.java:60-63, :75-85 (`before` from the last staged op in `base`).
  - Staging.java:99-112 (replaced ops marked DISCARDED).
  - UndoPlanner.java:446-451 (APPLIED only), :471-500 (restore target = oldest `before`).
  - PendingActions.java:143-201 (today only ENABLE_FILE replaces).
  - SPEC:110, AC4.8 (:131), AC4.11 (:134). The e2e `undo-after-restart-040` (SPEC:98) uses a single switch, so it can't catch this.
- **Fix.** Preferred: **drop the same-key replacement.** Two switches then leave two ops per key. The helper applies both in order (the final value is right), History shows both switches as applied, and Undo last/Undo all work unchanged, the same way the released 0.3.0 undoes switch entries (profiles.md §3). This removes code from the helper-adjacent path and removes AC4.8.

  If replacement stays:
  - compute the new change's `before` from `base` minus `merged.replaced()`, so it becomes V0;
  - accept that Undo last then leaves a hybrid state (vanilla at Battery, config at the original values), and say so on the Undo screen.

  Either way, add to AC4.11 and to `undo-after-restart-040`: Battery → Max FPS → restart → Undo last → Undo last, then every staged and vanilla key back at its pre-Battery value (plus Undo all from the same start).

### P-L1: The name sanitiser keeps `§`, and Minecraft renders `§` codes in literal text
- `§` (U+00A7) is category So, so the Cc/Cf/Co/Cs/Cn/Zl/Zp filter keeps it. MC's text path formats literal strings through `StringDecomposer.iterateFormatted` (`javap -c` of 26.2 `net.minecraft.network.chat.SubStringSource` shows both calls; `ClientLanguage.getVisualOrder` → `FormattedBidiReorder.reorder` → `SubStringSource`). So a code named `§k…` renders as obfuscated noise and `§0…` renders black on the dark background.
- The player's own Rename box is safe: vanilla `EditBox` filters `§`. The imported name is the only way one gets in.
- The same applies to 2c's `modName`, which is read from an untrusted, downloaded `fabric.mod.json` with no length cap (SPEC:53).
- **Fix.**
  - Drop U+00A7 in the sanitiser and add it to AC4.3's list.
  - For `modName`, strip `§` and controls and cap it at 64 code points before it goes into history.json.

### P-L2: Two honesty and safety gaps in switching
- The toast "Switched to Battery. Undo it in History." (SPEC:121) is shown while Battery's largest savings, shaders off and DH rendering off, are only *staged* for the next exit (Iris and DH keys go through the helper; RealController.java:386-390, :416-429).
  - Make the toast "Switched to Battery; N changes apply after a restart" whenever anything was staged.
- Templates apply every rules clamp last (SPEC:112), but imported and saved profiles don't. A friend's RD 32 code imported on a 2 GB heap only meets MC's own option range (`SettingsBridge.problems`), not RigTune's heap cap (RD ≤ 8 at ≤ 2100 MB, AC4.6).
  - Have Preview mark values above a rules clamp ("above RigTune's limit for your memory"). Or clamp them and list the clamp, as the templates do.

---

## WS-S: Stutter Doctor (SPEC 5)

### S-M1: The phase-timer injections crash the game for users whose other mods touch the same calls
- **Problem.** The optional phase timers are `@Inject(at = @At("INVOKE", …))` pairs inside `Minecraft.runTick`/`renderFrame` (SPEC:141). The mixin config is `"required": true` with `"defaultRequire": 1` (rigtune.client.mixins.json:2, :9).
  - If another mod `@Redirect`s or `@Overwrite`s one of those invokes, the target disappears, and mixin throws at class load. That's a startup crash caused by an opt-in *diagnostic*.
  - "Ship only if the first real run shows them injecting" only proves RigTune alone; it says nothing about other mods.
- **Evidence.** rigtune.client.mixins.json; SPEC:141, AC5.8 (:156).
- **Fix.**
  - Declare the phase-timer injectors with `require = 0` (and `expect = 0`). Set a static `injected` flag from each handler's first call, and turn phase attribution off when a pair is incomplete (use the no-phase rules, research §4.2).
  - Keep `require = 1` only for the existing `logFrameDuration` HEAD hook and the two packet-listener TAIL hooks.
  - Add to AC5.8: a run with the phase-timer class present but its target renamed (a test mixin config) still starts and shows "phase timing unavailable".

---

## WS-J: JVM and GC advice (SPEC 6, draft)

### J-M1: A known `jvm-` prefix makes missing JVM facts read as "no", not "can't tell"
- **Problem.** C2 and item 6 make `ConditionEvaluator` accept `jvm-` flags, so `knownFlag` becomes true and an absent flag evaluates to FALSE (ConditionEvaluator.java:196-206, v0.2.0 = HEAD). The facts are absent whenever:
  - the probe hasn't finished (it runs on `Probes.EXECUTOR`, SPEC:160);
  - it threw;
  - the JVM has no `com.sun.management` (OpenJ9: "turns the check off").

  In those cases `not {flags: [jvm-gc-typed]}` is TRUE, and so is any `anyOf` built on it. That's the fail-open that `backend-vulkan` is guarded against (UNKNOWN while the backend is unknown, :198-202). AC6.2's "no HotSpot bean → no facts and no exception" currently hides this.
- **Fix.**
  - Add a `jvm-probed` fact, set only when the probe read the GC beans and `HotSpotDiagnosticMXBean`.
  - Evaluate any `jvm-*` flag as UNKNOWN while `jvm-probed` is absent.
  - Add evaluator tests with `not` and `anyOf` over a profile with no JVM facts.
  - Have update_rules.py refuse `jvm-probed` itself in rules.

### J-M2: The advice list contradicts the "Deferred" list
- SPEC:163 ships `jvm-heap-reserved` ("`-Xms` ≥ 75 % of `-Xmx` … or `AlwaysPreTouch` with ≤ 16 GB RAM") and the facts `jvm-xms-large` and `jvm-pretouch` (:161). SPEC:293 defers "`-Xms`, `AlwaysPreTouch`, large pages or thread-priority advice".
- Both WS-R (content) and WS-J (facts) would build whichever sentence they read first.
- **Fix.** Decide when jvm-gc.md is final. §2.1 classes `AlwaysPreTouch` as "H (low)" and §4 was pending, which argues for deferring. Delete the other sentence. Keep AC6.2/AC6.3's lists in step.

---

## WS-B: benchmark history (SPEC 7)

### B-H1: With the mod set in the comparable key, a mod update can never raise a regression, which the item's own texts and AC7.5 need
- **Problem.** SPEC:169 defines *comparable* as the same MC version, scene, render and simulation distance, context **and mod set** (`modSetHash` over every loaded mod's id and version, RigTune's own included). Consequences:
  - Any mod update, including one RigTune applied or its own self-update, makes the latest run incomparable with everything before it. The alert path turns into "Performance changed under different conditions (mod set); cause unknown", and the trend restarts.
  - "Something outside RigTune changed too (the mod set differs)" (SPEC:171) needs a comparable pair whose hashes differ, which can't exist.
  - AC7.5 seeds "4 comparable runs + a regressed latest run; history.json with a mod update in between" and expects the regression line naming the update. That only passes if the fixture gives both sides the same hash despite the update, and then the test proves a state the code can't reach.

  The research used the hash for the explanation, not for comparability: bench-history-a11y.md:43, :92 (explanation), :82, :100 (comparable = scene + MC + RD + SD + context).
- **Evidence.** SPEC:169, :171, AC7.1 (:174), AC7.5 (:174). bench-history-a11y.md:43, :82, :92, :100. External review §2 (external-review.md:11) asks for the mod set in the *"needs a rerun"* marker, not in the regression comparison.
- **Fix.**
  - Take `modSetHash` out of `comparable()`. Keep it in the "needs a rerun" marker (SPEC:173) and the change window.
  - When the hashes differ and the journal window has no matching file change, show "Something outside RigTune changed too".
  - Rewrite AC7.1 ("runs without `modSetHash` only with each other" goes away) and keep AC7.5 as written, which then tests a reachable state.

### B-L1: "Needs a rerun" and the stale notice fire on every window resize
- The benchmark's `Context.width/height` is the window size (BenchmarkController.java:204-205). A windowed player who resizes or maximises gets "needs a rerun (resolution)" and the BENCHMARK_STALE notice on every RigTuneScreen open (SPEC:173). Nothing says that notice is dismissible.
- **Fix.** Make BENCHMARK_STALE dismissible per latest-run id (stored with the other dismissals). Consider treating a resolution change of less than 10 % in pixel count as the same for the marker. Keep comparability exact.

---

## WS-W: server-aware advice and change awareness (SPEC 8, 9)

### W-H1: The server clamp recommends a lower render distance that changes nothing on that server and lowers it everywhere else
- **Problem.** On a server that sends 10 chunks, a player at RD 16 already renders 10: vanilla's `Options.getEffectiveRenderDistance()` is min(client, server), verified in server-view-distance.md:18, :115-124 and present on both versions (`javap`).
  - The SPEC clamps the *target* to the server limit (SPEC:180), and AC8.1 requires "RD 16 with a live server limit of 10 → 10 plus the reason". Recommender emits a SetSetting whenever the clamped target differs from the current value (Recommender.java:343-378; the existing heap clamp already works this way on the current value, :347). So the player sees a ticked "Render distance 16 → 10: The server sends at most 10 chunks".
  - Applying it gains nothing on the server and writes RD 10 to options.txt. Singleplayer and every other server then run at 10 until the player notices.
  - After DISCONNECT only the live value is cleared (SPEC:178). Nothing rebuilds the cached report, so the clamped recommendation can still be applied back in singleplayer.
- **Fix.**
  - The server limit may only *lower a proposed increase*. With target T, current C and limit L:
    - if T > C, the new target is min(T, max(C, L)), with the reason when it was capped;
    - if C ≥ L and T ≤ C, don't emit a decrease because of the server.
  - The notice explains the cap ("you set 16; the server sends 10, so 10 is what you see").
  - Rebuild the report on JOIN and DISCONNECT.
  - Rewrite AC8.1: RD 16 with limit 10 → no render-distance recommendation from the server; RD 6 with target 12 and limit 10 → 6 → 10 with the reason; after disconnect the report no longer has the server reason.

### W-L1: Say honestly what the server-address hash protects
- An unsalted SHA-256 of `host:port` is a pseudonym, not privacy. Anyone holding server-limits.json (a zipped config folder in a bug report, for instance) can check "did this player use `mc.hypixel.net:25565`?" by hashing well-known addresses. It only stops casual reading.
- **Fix.**
  - Use HMAC-SHA256 with a random 16-byte salt created once and stored in the file. This defeats precomputed lists of public servers, though anyone holding the file can still test single guesses.
  - Word the SPEC and README as "not stored in readable form", not "private".
  - Keep the file out of the share report (it already is).

### W-L2: AC8.4 cites the wrong gametest-API version for 26.2
- SPEC:183 says `TestWorldBuilder.createServer(Properties)` is "present in the cached fabric-client-gametest-api-v1 6.0.7 and 6.0.8 jars". Fabric API 0.161.0+26.2 bundles **6.0.2** (its pom). 6.0.7 is 26.3's and 6.0.8 is 26.4's.
- `javap` on 6.0.2 shows `createServer(Properties)` and `TestDedicatedServerContext.connect()` are there too, so the claim holds.
- **Fix.** Correct the citation: 6.0.2 (26.2) and 6.0.7 (26.3).

### W-L3: Notice semantics that will surprise
- HARDWARE_CHANGED: "the fingerprint updates when it's shown or dismissed" (SPEC:188). If a higher-priority notice holds the slot, "shown" is ambiguous, and a driver-change notice hidden behind "+N more" could be used up unseen.
  - Define "shown" as rendered as the top notice, or cycled to.
- WHATS_NEW: the baseline updates only on dismissal (SPEC:191). A player who never dismisses it gets "new since you last looked" for every id that appeared for any reason (a goal change, a newly installed mod) at each weekly rules revision.
  - Seed the baseline on every screen open where the revision is unchanged, or limit "new" to ids the previous rules revision didn't have (compare against the rules, not the report).

---

## WS-F: footprint guard and startup times (SPEC 10, 13)

### F-M1: The heap check counts only the shallow size of RigTune's own classes, so it can't see the rings or a leak
- **Problem.** `gcClassHistogram` gives `#bytes` per *class*. The plan sums the rows whose class name starts with `io.github.chaotix345.rigtune.` (SPEC:198; footprint.md:258-262). That's the shallow size of RigTune's own objects only. What RigTune actually retains is mostly JDK types:
  - the stutter rings (`[J`, `[I`, `[F`, ~1.8 MiB);
  - Gson's parsed rules tree (`ArrayList`, `LinkedHashMap`, `String`, `com.google.gson.*`);
  - strings and collections held by screens.

  So "≤ idle + 2.5 MiB with the monitor on", "back within idle + 256 KiB after it's off" and the screen-leak check pass whatever happens. AC10.2's gate test only exercises time and allocation, never retention. footprint.md:264-272 already notes the 8 MiB figure was never measured.
  - Each histogram call without `-all` also forces a full GC. That's fine in a test, but it will show up in the stutter monitor if it's on.
- **Evidence.** SPEC:198, :200, AC10.4 (:202); footprint.md:21, :243-272.
- **Fix.** Measure retention in two ways:
  - Explicit accounting. `StutterMonitor.retainedBytes()` returns the ring array sizes, and the monitor-on/off budget asserts on it (it's exact).
  - A whole-heap delta. Call `MemoryMXBean.getHeapMemoryUsage().getUsed()` after the histogram's full GC, at idle and after opening and closing the screens 20 times, and assert the growth with a CI-calibrated tolerance.

  Keep the class histogram only as a diagnostic list of RigTune instance counts, where a count that grows across the 20 cycles is the leak signal.

### F-L1: Dependencies and P2 coupling
- AC10.4 (monitor-on numbers) needs WS-S merged. PLAN:106 says "may be done by a follow-up branch" but names no owner, and AC10.4 belongs to WS-F. Name the owner: the coordinator in Phase 5, or WS-F after WS-S merges.
- Item 13 (P2) is threaded into P0/P1 artefacts:
  - `startup-times.json` is in AC3.2's required downgrade file list (SPEC:99) and in C1;
  - the startup line is in C3's ToolsScreen;
  - AC13.2 is asserted inside the P1 FootprintGameTest.

  Cutting item 13 would then mean editing P0 evidence. Mark those parts "if item 13 ships".

---

## Cross-cutting

### X-M1: awareness.json and profiles.json have several writers and no owner of the read-modify-write
- **Problem.** Writers of awareness.json:
  - NoticeCenter dismissals, on the render thread (C3, SPEC:236);
  - AwarenessService's fingerprint and what's-new baseline, "one call after the probe" (probe completion runs on `Probes.EXECUTOR`, 2 threads);
  - TrendService's `acknowledgedRegressions` (WS-B, SPEC:172).

  Writers of profiles.json:
  - ProfileService (switch labels, saves, renames);
  - BatteryPrompt state from the "RigTune power" thread (SPEC:122).

  C1 only promises atomic writes. Two read-modify-write cycles that overlap lose one update: a dismissal comes back, or a "Profile: Battery" label reverts to "Apply".

  Ownership is split too. WS-W owns `AwarenessStore` (PLAN:101), but WS-B needs its `acknowledgedRegressions` API in parallel (PLAN:97).
- **Fix.** WS-K lands `AwarenessStore` and `ProfileStore` shells with every C1 field, with one process-wide instance per file whose `update(UnaryOperator<JsonObject>)` is synchronized and re-reads under the lock. Feature workstreams only add typed accessors. Add a unit test with two threads updating different fields 1,000 times, and assert both survive.

### X-M2: At 640×480@2 the Tools button and the notice line leave room for about one recommendation
- **Problem.** At 320×240 scaled, `columnWidth` = 288 (RigTuneScreen.java:450-452), so the footer holds 3 buttons per row (:156).
  - Today: 8 buttons, or 9 with Discard. With Tools… that becomes 9 or 10, i.e. 3 or **4 rows**. Four rows start at footerTop = 240 − 4 − 92 = 144, so the list ends at 128 (:160-162).
  - With a known launcher the header is already 4 lines (LauncherLines.java:25-28, RigTuneScreen.java:202-229), so headerBottom = 72. The notice line adds 10 px, or 20 px with action buttons, so the list starts at about 86-96.
  - That leaves a 32-42 px list (rows are ≥ 24 px, :472), about one recommendation. Without the notice it would be two.
  - C3's notice is "the top notice, at most 2 action buttons, a dismiss button, and '+N more'" on one 288 px line. The server-limit message alone is about 250 px in the default font, so one line can't fit it.
- **Fix.** Specify the layout in C3:
  - At widths below about 400 scaled px, the notice line shows the message, truncated with a tooltip, plus a single "…" button that opens a small NoticeScreen holding the actions, dismiss and the other notices.
  - Tools… replaces Benchmark in the footer: Benchmark history already lives in Tools, so put BenchmarkMenuScreen there as its first entry and keep the footer count at 8 (9 with Discard).
  - Add to UiGameTest: at 640×480@2 with Discard visible and a notice present, at least 2 recommendation rows are fully visible (assert the list height ≥ 2 × 24).

### X-M3: WS-P and WS-W both change `Recommender.settings()`, and the plan calls them "separate methods"
- **Problem.** WS-P extracts the value and clamp loops (Recommender.java:326-378) into `settingTargets(...)`. WS-W's server clamp has to live in exactly those loops, because the render-distance target is computed there. Two parallel branches rewriting the same 50 lines will conflict, whatever the hotspot table says (PLAN:125).
  - Nothing says whether templates computed while connected include the server clamp. If they do, a "Max FPS" or "Quality" switch done on a server writes the server's RD into the player's global options, which is W-H1 again.
- **Fix.** Order it: WS-P's extraction merges first, as a small early PR with recommend() output unchanged. WS-W then applies its (W-H1-corrected) cap to the *output* of `settingTargets` in the main-list path only. State in SPEC 4 that templates never use server limits.

### X-L1: README.md has four editors and isn't in the hotspot table
- Editors: WS-R (2l "What has been verified"), WS-F ("RigTune's own footprint"), WS-P (AC12.1's shader-pack note) and WS-J (help for flags that stop the JVM from starting, SPEC:161).
- **Fix.** Add a hotspot row: one section per workstream, created as empty headings by WS-K.

### X-L2: AC2m.1's before/after pixel diff has no stable "before"
- The same screenshot changes because of 2j (header text), C3 (notice line, Tools button) and possibly X-M2 (layout), so the row moves.
- **Fix.** "Before" = the CI screenshot of feat/v0.4.0 right after WS-K merges. Compare the crop of the first recommendation row, located by the list's y from the game test's log line.

### X-L3: Phase 5 list and lock budget
- The Phase 5 list (PLAN:145) misses AC2i.1's read-only real-instance check and AC10.5's RC numbers for the README.
- Wave A local runs under the one-client lock:
  - WS-H: 5 dev e2e runs, each old client + new client;
  - WS-S: the early phase-timer smoke;
  - WS-F: the calibration run.

  26.3's native-crash retries (up to 6) lengthen each hold. Ask WS-H to batch its dev runs in one lock hold per scenario pair, and let WS-S and WS-F go first, since their runs are short and they unblock code decisions.

---

## Compatibility checks that passed
- **Unknown top-level sections.** 0.2.0/0.3.0 `RulesLoader.parse` is plain `Gson.fromJson(json, RulesDocument.class)`, with an adapter only for `Condition`. Unknown members (`profileTemplates`, `stutterAdvice`) are skipped without their conditions being parsed. There's no strict-key check, and the size cap is 2 MiB against 68 KB today (RulesLoader.java:28, :44-63 at v0.2.0). core/rules is byte-identical across v0.2.0, v0.3.0 and HEAD (`git diff` empty).
- **Unknown condition keys.** The adapter records unknown or unreadable keys on the node that has them. `evaluate()` then returns UNKNOWN for the whole tree, including nested `not`/`anyOf` (ConditionEvaluator.java:55-70, v0.2.0). Every `Condition`-typed field goes through the adapter: `recommendWhen`, `avoidWhen`, `skipUpdateWhen`, settings `when` (value and clamp entries), advice `when`. Tier rows have no conditions. A `jvm-` flag that 0.2.0/0.3.0 doesn't produce reads UNKNOWN there (`knownFlag` false, :196-206). R-L1 covers the one caveat.
- **`requires` in 0.2.0.** It exists on `ModRule`, `ObsoleteRule`, `SettingRule` and `AdviceRule` (RulesDocument.java at v0.2.0), with `SUPPORTED_FEATURES = Set.of()` and `supported()` filtering all four kinds (Recommender.java:48-49, :115-116, :162-165 at v0.2.0). So `requires: ["jvm-flags"]` is skipped by 0.2.0 and 0.3.0.
- **2k on 0.1.x.** 0.1.0's `SettingRule` has `defaultSelected`, and `selected()` honours it (v0.1.0 RulesDocument.java:171, Recommender.java:246, :387-388), so the VSync entry arrives unticked. RulesV1DifferentialTest flags only newly ticked or added actions and lost warnings (RulesV1DifferentialTest.java:148-205), so unticking passes.
- **v1 projection.** `v1_projection` copies any top-level key it doesn't know (update_rules.py:867-897, the final `else` branch at :893), so the new sections must be stripped explicitly, as SPEC:10 says. 0.1.0's Gson would ignore them anyway. `check_rules_v1.py` should reject them so a missed strip fails CI rather than passing silently.
- **Files older versions rewrite.** No new optional field collides with an existing name in 0.2.0/0.3.0 records (`JournalChange`, `BenchmarkRecord.Context` at v0.3.0). The drops on rewrite are the ones the SPEC lists.
- **Helper path.** The running version's own jar is copied into `helper/` (HelperLauncher.java:49-50, :79-93), so an older helper meets a 0.4 pending.json only after a manual downgrade with a plan left behind. It then ignores `projectId` (plain records). Abandoned or done ops leave the plan (ApplyExecutor.java:193-221), so no stale projectIds block anything after an abandon. The 2d/2e refusals always last only as long as the real staged state: none blocks an update forever.

## Technical claims verified with javap (both 26.2 and 26.3 unless noted)
- `DebugScreenOverlay.logFrameDuration(long)`, public.
- `ClientPacketListener`: `handleLogin`, `handleSetChunkCacheRadius` and `handleSetSimulationDistance` (each has a single `return`, so TAIL is sound), plus the private int fields `serverChunkRadius` and `serverSimulationDistance`.
- `Options.getEffectiveRenderDistance()` and the private `serverRenderDistance`.
- `Minecraft.runTick(boolean)` (private) and `renderFrame(boolean)`.
- `SectionRenderDispatcher.getCompileQueueSize()`.
- `ServerData.isRealm()` and `isLan()`; `IntegratedServer.isPublished()`; `ClientLevel.getServerSimulationDistance()`.
- Fabric lifecycle events 4.1.4 (26.2) and 4.1.9 (26.3): `ServerLifecycleEvents.BEFORE_SAVE/AFTER_SAVE` (`onBeforeSave/onAfterSave(MinecraftServer, boolean, boolean)`), `ClientChunkEvents.CHUNK_LOAD`, `ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE`.
- Networking 6.3.4 and 6.3.8: `ClientPlayConnectionEvents.DISCONNECT`.
- Gametest API 6.0.2 and 6.0.7 (and 6.0.8): `createServer(Properties)`, `connect()`.
- Sodium mc26.2 and mc26.3 0.9.2: `SodiumWorldRenderer.instanceNullable()`, private field `renderSectionManager`, `RenderSectionManager.getBuilder()`, `ChunkBuilder.getScheduledJobCount/getBusyThreadCount/getTotalThreadCount()`.
- CI's JDK is Microsoft OpenJDK 25 (build.yml:28, :116), a HotSpot build, so the `DiagnosticCommand` MBean is present. This is inferred from the vendor, not run.
- Not re-verified: the GC clock (AC5.3 checks it against recorded tuples, which is the right test), and ThreadMXBean sampling without safepoints (research).

## Cut order if time runs short
1. **P2 13, startup times.** Take `startup-times.json` out of AC3.2's list first (F-L1).
2. **P2 11, accessibility.** 2m stays (P0).
3. **Item 9's what's-new notice** (W-L3). Keep change detection and the two driver seeds.
4. **Item 5's phase timers and the Sodium reflection signal.** Keep the vanilla compile-queue fallback, the GC and save windows and the tags.
5. **Item 4's battery hook** (PowerWatcher, BatteryPrompt, the notice). Keep profiles, templates and share codes.
6. **Item 7's context selector.** Show the current context's trend only.

Never cut: item 2 (with A-H1 and A-M1 fixed), item 3 (every upgrade run, `undo-after-restart-040` with P-H1's two-switch case, and the downgrade run), item 1 (with WS0-M1), P-H1's fix, and W-H1's fix if item 8 ships at all.

## Summary

| id | sev | workstream | one line |
|---|---|---|---|
| P-H1 | H | P | same-key replacement records the discarded switch's value as `before`; Undo restores Battery's staged values |
| A-H1 | H | A | 2d's fold hides staged dependencies from the resolver; undo, discard or abandon leaves a mod without its dependency |
| B-H1 | H | B | mod set in the comparable key makes a mod-update regression unreachable; contradicts SPEC:171 and AC7.5 |
| W-H1 | H | W | server clamp recommends an RD decrease that gains nothing on the server and lowers RD everywhere else |
| WS0-M1 | M | 0 | `>=26.4-snapshot-1` is false for 26.4-pre/rc (probe); use `>=26.4-alpha` |
| K-M1 | M | K | new Condition field types (`Double`, typed maps) let one bad value reject the whole remote rules file |
| H-M1 | M | H | downgrade e2e and released-jar harness need 0.4-written fixtures nobody owns; seed, don't drive |
| A-M1 | M | A | reverse-direction incompatibility needs the staged version, not just its projectId |
| S-M1 | M | S | phase-timer INVOKE injections under `defaultRequire: 1` crash with conflicting mods; use `require = 0` |
| J-M1 | M | J | known `jvm-` prefix makes absent facts FALSE (fail-open under `not`); gate on `jvm-probed` |
| J-M2 | M | J/R | `jvm-heap-reserved` (-Xms/AlwaysPreTouch) contradicts the Deferred list |
| F-M1 | M | F | class-histogram sum counts only shallow RigTune objects; rings and leaks are invisible |
| X-M1 | M | K/W/B/P | awareness.json and profiles.json have concurrent read-modify-write writers and split ownership |
| X-M2 | M | K/A | at 640×480@2, Tools + notice leave ~1 list row; the notice line can't fit its buttons |
| X-M3 | M | P/W | P's `settingTargets` extraction and W's clamp edit the same loops; templates vs server limits unspecified |
| K-L1 | L | K | pinned-copy set inconsistent (v020 vs v030); AC6.3 needs a pinned `supported()` |
| A-L1 | L | A | `projectId` must survive `inGroup/withModId/withAttempts`; fold from the relocated view; line refs |
| R-L1 | L | R | "fails closed" isn't safe for clamps/avoidWhen/skipUpdateWhen; enforce `requires` there |
| P-L1 | L | P/A | `§` survives the name sanitiser and renders as formatting; cap and strip `modName` too |
| P-L2 | L | P | switch toast hides restart-staged changes; imported codes bypass the rules' heap clamps |
| W-L1 | L | W | unsalted SHA-256 of host:port is guessable; salt it and word it honestly |
| W-L2 | L | W | AC8.4 cites gametest 6.0.7/6.0.8; 26.2 uses 6.0.2 (claim still holds) |
| W-L3 | L | W | "fingerprint updates when shown" vs "+N more"; what's-new baseline drifts |
| B-L1 | L | B | window resize makes the last benchmark "stale" and raises a notice every time |
| F-L1 | L | F | AC10.4 needs WS-S; P2 item 13 is threaded into P0/P1 evidence |
| X-L1 | L | all | README.md edited by R, F, P and J; not in the hotspot table |
| X-L2 | L | A | AC2m.1's pixel diff has no stable baseline |
| X-L3 | L | coord | Phase 5 list misses AC2i.1/AC10.5; schedule Wave A lock use |
