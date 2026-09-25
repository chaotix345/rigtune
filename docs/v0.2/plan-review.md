# RigTune v0.2.0 plan review (independent)

Scope: docs/v0.2/SPEC.md, docs/v0.2/PLAN.md, contract commit cae06b8, DESIGN.md, RULES_SCHEMA.md, research docs, and the
source on `feat/v0.2.0` (identical to tag v0.1.0 = 2cf4317 apart from cae06b8, checked with `git rev-parse v0.1.0^{commit}`).
Paths below are relative to the repo; `core/` = `src/main/java/io/github/chaotix345/rigtune/core/`, `client/` =
`src/client/java/io/github/chaotix345/rigtune/client/`.

Verified by running something:
- Gson 2.14.0 (the version in the Gradle cache): an unknown enum constant (`"type":"PATCH_TOML"` read by an enum without it)
  becomes `null`, and re-serializing drops the `type` field (scratch test T.java).
- A nested `ApplyLock`-style acquire in the same JVM gets `OverlappingFileLockException`. On Windows the outer lock is
  still held after the inner channel closes (tested with a child process). POSIX: NOT verified (see M4).
- Loom 1.17.21 `AbstractProductionRunTask` constructor adds `tasks.named("jar")` (or `remapJar`) and
  `productionRuntimeMods` to `mods`, and passes `mods` as `-Dfabric.addMods=` (javap).
- Fabric Loader 0.19.5 has `VersionPredicate.parse(String)` and `Version.parse(String)` (javap).
- v0.1.0 does no host check on download URLs: `HttpModrinthClient.download` uses `URI.create(file.url())` (core/modrinth/HttpModrinthClient.java:151).

Counts: HIGH 5, MEDIUM 16, LOW 11.

---

## 1. Safety and backward compatibility (rules v2, v1 projection, 0.1.0 files, downgrade)

### H1 (HIGH) Fail closed only covers unknown keys. Indeterminate values still fail open under `not`.
Evidence: SPEC §2 table (lines 46-51) says invalid regex, exhausted budget, no GPU info, width/height ≤ 0, mod not
loaded and unparseable predicates all evaluate to `false`. The fail-closed paragraph (line 53) poisons the tree only for
unknown *keys*, and itself explains why `false` inside `not` flips to `true`. `ConditionEvaluator.matches` returns
`!matches(c.not)` (core/rules/ConditionEvaluator.java:85). The Nvidium rule triage.md proposes (lines 262-263) puts
`not: { gpuModelMatches: ... }` inside `avoidWhen`. So a client with no GPU renderer string, or one that runs out of
regex budget, gets a ticked "Disable Nvidium". The same hole exists for unknown *values* of enumerated fields
(`flags`, `gpuVendor`, `backend`, `os`, `goal`). A future `not: {flags: ["mesh-shaders"]}` is `true` on every client
that can't detect that flag, and neither the parser nor check_rules_v1.py looks at values. The v1 fields `ramMb*`,
`vramMb*` and `refreshRateAtLeast` are already `false` when unknown (lines 46-58), so under `not` they fail open too.
Fix: specify three-valued evaluation (TRUE/FALSE/UNKNOWN). UNKNOWN comes from any unknown key, invalid or indeterminate
input, or unknown enum value. `not UNKNOWN` = UNKNOWN; `anyOf` is TRUE if any branch is TRUE, else UNKNOWN if any branch
is UNKNOWN. A top-level UNKNOWN is `false` for every rule kind. Write the known value sets for the enumerated fields into
RULES_SCHEMA.md. check_rules_v1.py rejects values outside the v0.1.0 vocabularies. Add WS-A tests: a v2 field inside
`not` with no GPU info, an unknown flag inside `not`, and an avoidWhen with `not` + indeterminate gives no disable.

### H2 (HIGH) The v1 projection drops whole ModRules, so 0.1.x loses safety warnings. A `null` in an override fails open.
Evidence: SPEC §2 projection step 3 (line 61) omits the whole rule if any part uses a v2 feature. The safety argument
(line 65) says "Omitting a mod/advice/avoid rule only removes a recommendation". That's false for ModRules: one object
carries `recommendWhen`, `avoidWhen` (a disable suggestion) and `conflictsWith` (a HIGH "conflicts" warning)
(Recommender.java:150-181). Omitting it removes the avoid and conflict warnings that 0.1.x shows today. Concrete case:
SPEC item 9 says Nvidium's `v1` override "keeps its old condition" (recommendWhen). But the triage's v2 avoidWhen also
uses `gpuModelMatches`, so without an avoidWhen override the whole Nvidium rule leaves rules-v1.json, and 0.1.x users
running Nvidium with shaders are no longer told to disable it. Also, `null` means "always": `matches(null)` returns true
(ConditionEvaluator.java:17-19). A shallow-merged override such as `"v1": {"recommendWhen": null}`, or a projection that
strips a field instead of replacing it, makes 0.1.x recommend the mod on every machine. Step 3 also checks only condition
keys, `requires` and settings keys. It doesn't check rule-level fields v1 doesn't know (see H3's `defaultSelected`, or a
future `avoidSelected`), which 0.1.x silently ignores.
Fix: project per field, not per rule. A v2-only `recommendWhen` or advice `when` becomes `{"always": false}`. A v2-only
`avoidWhen` is dropped (no disable). `conflictsWith`, `modIds` and the other v1-safe fields stay. Never write `null` or
remove a condition field: the updater errors on a null in a `v1` override. Whitelist the v1 rule-level fields per rule
type. Any other field needs an explicit `v1` override or `"v1": false`, otherwise the updater errors (it doesn't silently
omit). Make implicit omission of *setting* entries an error too (explicit `v1` required), instead of "or be reviewed in
section (d)". Stronger option: a differential test that runs a pinned copy of the v0.1.0 Recommender and
ConditionEvaluator (from the tag, under src/test) over a hardware × mods matrix, against the previous and the new
rules-v1.json. It fails on any new ticked recommendation that isn't strictly more conservative.

### H3 (HIGH) "LambDynamicLights unticked by default" can't be expressed, has no owner, and ships ticked to 0.1.x.
Evidence: `disable()` hard-codes `selected = true` (Recommender.java:327). `defaultSelected` is read only for additions
(line 204) and settings. The triage rule (triage.md:61-77) uses only v1 keys (`always:false`, `tierAtMost:2`,
`defaultSelected:false`), so the projection copies it unchanged into rules-v1.json. Every 0.1.x user on tier ≤ 2 who has
LambDynamicLights then gets a ticked "Disable LambDynamicLights", and Apply turns off their dynamic lights. AC9.2 expects
"unticked" but no workstream owns the code: WS-H owns rules only, and WS-A owns Recommender only for `requires`/labels.
Fix: give WS-A a task: a new ModRule field (e.g. `avoidSelected`) honoured by `avoided()`, with a test. Under H2's
rule-level whitelist that field is v2-only, so the LDL rule needs `"v1": false` (or a v1 override without `avoidWhen`).
Add a projection test for it. Merge order: WS-A before WS-H (already implied).

### M1 (MEDIUM) Poisoning an *edited* restrictive rule removes a restriction older 0.2.x clients had.
Evidence: SPEC line 53 makes a poisoned rule behave as if absent. Settings resolve by last-match-wins, then clamps
(RULES_SCHEMA.md:104-105, Recommender.java:240-265). Say a later knowledge edit adds a v2.1 field to an *existing* clamp
(e.g. narrows "tier ≤ 2 → RD max 8"). Every 0.2.0 client then loses that clamp entirely, and nothing like REVIEW (d)
covers v2-vs-older-v2 clients (there's one rules-v2.json for all 0.2+).
Fix: an updater rule, stated in RULES_SCHEMA.md and enforced by diffing against the previously published rules-v2.json:
an existing setting/avoid rule may not gain a field unknown to the oldest supported client. Add a new rule next to the
old one, or gate it with `requires`.

### M2 (MEDIUM) A 0.2 → 0.1 downgrade silently turns the network-off switch back on.
Evidence: 0.1.0 `ClientState` has only `goal` and `lastShownApply`, and saves with `GSON.toJson(this)`
(client/ClientState.java; 0.1.0 = the file without the cae06b8 lines). 0.1.0 saves in `showNotices` whenever it shows an
apply result (RigTuneClient.java:272-275), which is common. That drops `networkEnabled`, `remoteRules` and so on. After
re-upgrading, the defaults (`true`) come back and requests resume, against the privacy promise in SPEC item 8.
Fix (cheap now, breaking later): keep the v0.2 settings in a new file 0.1.x never writes (e.g.
`config/rigtune/settings.json`), with rigtune.json kept for goal/lastShownApply. Or at least document the hazard.
Contract change to ClientState, so decide before WS-E starts.

### M3 (MEDIUM) Downgrade and upgrade behaviour has no acceptance criteria.
Evidence: SPEC item 7 says a 0.1 helper "fails and is abandoned after 3 runs; documented". Verified: 0.1's Gson turns
PATCH_TOML into `type:null` and writes `pending.json` back without `type`, so a re-upgraded 0.2 can never run those ops
either. The 0.1 game's `stage()` → `relocated()` drops null-type ops at once, with the misleading log "for another
instance's folders" (PendingActions.java:127-130, RealController.java:408-411). history.json goes stale (STAGED forever).
None of this is tested.
Fix: add an AC and a WS-B unit test that loads the *released* v0.1.0 jar's `PendingActions`/`ApplyExecutor` through a
URLClassLoader. It parses a 0.2 pending.json (PATCH_TOML, new fields) and asserts no exception, `type == null` and the
op refused. Document the downgrade matrix in DESIGN.md (owner: coordinator).

### L1 (LOW) Rule-level forward compatibility has gaps.
`requires` exists only on Mod/Obsolete/Setting/Advice rules (cae06b8 RulesDocument). An unknown field on a
`gpuTiers`/`cpuTiers`/`heapTiers` rule is silently ignored (fail open in tiering). Fix: document that tier-rule schema
changes need a new schemaVersion. Optionally, poison rules with unknown rule-level keys the same way as conditions.

---

## 2. Undo design (journal, races, locks, groups, simulated folder, self-update, helper versions)

### H4 (HIGH, contract) `ChangeRecorder.record(kind, changes)` can't group one Apply into one entry.
Evidence: the contract has only `record(String kind, List<JournalChange>)` and no entry handle
(core/history/ChangeRecorder.java). One Apply click produces vanilla changes (immediate), Sodium, DH and Iris staging
(WS-D's own call, PLAN line 89), and file ops recorded when downloads finish, asynchronously, in `finishDownloads`
(RealController.java:309-355). That's 2-4 `record` calls, so 2-4 entries. "Undo last apply" (SPEC §3 Undo) would then
revert only the last fragment, e.g. only the downloads.
Fix before Wave A: change the contract to `ChangeRecorder.begin(kind) → Entry` with `add(List<JournalChange>)`, or add
an `applyId` parameter so records with the same id merge into one entry. RealController creates the id in `apply()` and
passes it to `startDownloads`.

### H5 (HIGH) Recorded op ids and groups can differ from what is actually in pending.json.
Evidence:
- `PendingActions.merge` drops an incoming op that repeats a staged change and keeps the *existing* id.
- It replaces a staged ENABLE for the same mod id and hands the old op's group to the new op.
- It regroups across batches (PendingActions.java:138-184).
- `stage()` drops ops for "another instance" via `relocated()`, and replaces an unreadable plan wholesale
  (RealController.java:397-413).
- `PendingActions.discard` returns only a count (PendingActions.java:218-237).

A recorder that uses the ops it *passed* to `stage()` stores op ids that never reach pending.json. Those changes stay
STAGED forever, the helper never updates them, and undo tries to remove ops that don't exist. Group-based removal
("remove its whole group", SPEC §3) uses stale groups. AC3.1 ("a correct status") can't be met as designed. A related
problem: `before` for a config key is read "from the file at staging". When a same-key op is already staged (e.g.
sodium threads 0→4 staged, then 0→6), the second change records before=0, so undoing it restores 0, not 4.
Fix: record inside `stage()`, under the lock, *after* the merge. Extend `Merged` with `Map<incomingId, survivingId>`
and `List<replacedOpId>`. Replaced ops → DISCARDED. Make `discard` return the dropped ids. Compute `before` for config
keys as the value after the already-staged ops for that key. Add startup reconciliation under the lock (WS-B, in
preLaunch): a STAGED change whose op id is in neither pending.json nor last-apply.json becomes ABANDONED ("lost"). This
also covers a helper killed mid-run and a 0.1.x helper after a downgrade.

### M4 (MEDIUM) `ApplyLock` is not reentrant, and the plan creates nested acquires.
Evidence: `ApplyLock.acquire` swallows `OverlappingFileLockException`, closes the channel, polls, and returns `null`
after the wait (core/apply/ApplyLock.java:296-322). New nested sites:
- The legacy import runs in preLaunch while `onPreLaunch` already holds the lock around `readState`
  (RigTunePreLaunch.java:27-46).
- The recorder is called inside `stage()`, which holds the lock (per H5).
- The undo removes staged ops under the lock and then calls `stage()`.
Each nested acquire waits the full timeout (2 s on the render thread), then returns null, and the journal write is lost
silently. On Windows the outer lock survives (tested). On Linux/macOS, closing any fd on the file may release the
process's POSIX record lock (JDK FileLock docs: "on some systems"). NOT verified here.
Fix: make ApplyLock reentrant in-process: a static `ReentrantLock` plus a reference-counted `FileLock` per path. Or give
the journal store an explicit `withLockHeld` API and document that it must never acquire on its own. Do journal I/O off
the render thread, or keep the lock wait short with an in-memory retry.

### M5 (MEDIUM) The helper's classpath constraint isn't in the plan, and a journal failure can break the helper.
Evidence: the helper runs with only RigTune's jar and Gson (HelperLauncher.java:41). `ModJars.readModId` deliberately
doesn't log because "the apply helper runs without a logger on its classpath" (core/apply/ModJars.java:28). WS-B puts
journal updates in `ApplyExecutor.run`. WS-D's patchers run in the helper. If either touches `RigTune.LOGGER` (SLF4J) or
Fabric Loader, the helper gets `NoClassDefFoundError`. That is an `Error`, which `applyLocked` doesn't catch
(ApplyHelper.java:251 catches IOException/RuntimeException), and it can come before `pending.json` and last-apply.json
are rewritten. Unit tests won't notice because they have the full classpath.
Fix: add to Global Constraints: "code reachable from ApplyHelper uses only the JDK, Gson and RigTune core; no logger, no
Fabric Loader". Run the journal update *after* `writeRemaining` and `result.save`, and catch `Throwable`. Add an AC and
test: run ApplyHelper in a subprocess with exactly the built jar plus Gson, over a plan with every op type, and check
that history.json is updated.

### M6 (MEDIUM) The disabled file name isn't always `<file>.disabled`, so undo can re-enable the wrong jar.
Evidence: `disabledTarget` falls back to `.disabled.1`, `.disabled.2`, ... when the name is taken
(ApplyExecutor.java:410-417). The actual target appears only in the OpResult message text. SPEC §3 defines "disable
means `<file>` became `<file>.disabled`", and undo stages `<file>.disabled` → `<file>`. Case: a user re-installs a mod
RigTune disabled earlier, or an update chain keeps one file name (a→b→c all named `mod.jar`). Undo then re-enables the
older jar, and the simulated folder can't tell.
Fix (contract): add the actual target to `OpResult` (e.g. `target`) and to `JournalChange` (e.g. `disabledAs`). Both
are new optional JSON fields that older readers ignore. Legacy import parses 0.1.0's "Disabled X -> Y" message or scans
for candidates.

### M7 (MEDIUM) `graphicsPreset` changes a dozen options that the journal doesn't record.
Evidence: "graphicsPreset rewrites a dozen other options when set, so it goes first" (client/probe/SettingsBridge.java:126).
The journal records only the requested keys. Undo sets the preset back, which re-applies preset defaults over any of
those options the user had customised before the Apply. The "you changed it since" check can't see implicit changes.
Fix: when a vanilla batch contains `graphicsPreset`, snapshot all vanilla options before the write and journal every
option that changed. Undo restores the preset first, then the explicit values (same order as apply). Add a unit test.

### M8 (MEDIUM, contract) The undo shown isn't necessarily the undo executed. Several undo semantics are underspecified.
Evidence:
- `UndoPlan.Item` has no change ids, and `undo(boolean all)` recomputes the plan (cae06b8 RigTuneController, UndoPlan).
  A download finishing or a setting changing between screen and Confirm can make "last" a different entry.
- "Earliest before" (SPEC §3) ignores user edits between two applies of the same key (12→16, user sets 10, 10→20: undo
  all gives 12, not 10).
- Pressing "Undo last" twice before a restart stages the same reversals again: merge dedupes the ops, but the second
  undo entry keeps phantom op ids (see H5), and vanilla items show a misleading "you changed it since".
- An entry whose remaining changes were all skipped stays "last" forever, so "Undo last" can never reach older entries.
- The 50-entry cap means "Undo everything" isn't everything.
Fix: `undo(UndoPlan)`, or a plan fingerprint that `undo` re-checks under the lock and refuses on mismatch. Chain vanilla
reversals newest-first, stopping where `older.after != newer.before`. Exclude entries with a pending (STAGED) undo. Add
an entry state "undo attempted" so skipped entries are passed over. Prune only fully terminal entries first.

### M9 (MEDIUM) Undo can leave the game unable to start.
Evidence: reversal ops are plain renames with no dependency check. The executor checks only duplicate mod ids
(ApplyExecutor.java:187-206). If another mod now `depends` on it, undoing an update is a downgrade below that
requirement, and undoing an add disables a dependency. Fabric then refuses to start, and there's no RigTune UI to
recover. The RigTune exclusion rests on journal `modId`. DISABLE ops carry no mod id (DownloadPlanner.java:202-203).
Legacy-import entries of the self-update's `rigtune-0.1.0.jar` disable therefore need a group/jar lookup. Otherwise "Undo
everything" could re-enable `rigtune-0.1.0.jar.disabled` next to 0.2.0, which gives a duplicate mod id. The executor
abandons that only if the ENABLE op carries `modId`.
Fix:
- UndoPlanner takes the loaded mods' Fabric metadata (`ModMetadata.getDependencies()`) and skips, with a reason, any
  reversal that breaks a `depends` predicate.
- Every reversal ENABLE carries a `modId` read from the `.disabled` jar's fabric.mod.json, so the executor's duplicate
  check is a backstop.
- Exclude RigTune by reading the jar, not only by the journal's modId.

### L2 (LOW) Legacy import details.
- Identify RigTune's DISABLE in last-apply.json via its group's ENABLE with `modId == "rigtune"`.
- Skip the import when preLaunch couldn't get the lock (the helper is still running and last-apply.json is stale).
  Otherwise the real result is never imported, because history.json then exists.
- Leftover 0.1.0 ops in pending.json could be imported as STAGED changes.
- Undoing a config setting whose `before` was absent can't be staged: PATCH_JSON can't delete a key and creates missing
  ones (SodiumConfigPatcher "New fields get an inferred type"). Skip it with a reason.
- `SodiumConfigPatcher.Staged` carries no before-values (PLAN contracts), so WS-B has to re-read them. Say so in the
  plan.

### L3 (LOW) Removing a staged self-update.
SPEC says RigTune's own jar is "never offered for undo". State whether removing a *staged* (not yet applied) RigTune
update as part of its group is allowed. It's harmless, the same as Discard.

---

## 3. Self-update E2E with the unmodified v0.1.0 jar

What was verified in code: 0.1.0 offers its own update only if `POST /v2/version_files` knows the jar's SHA-1 with a
`project_id` (OnlineDataFetcher.java:53-59). So the finding that GitHub-installed 0.1.0 needs the identical jar on
Modrinth is correct. It also needs `next.date_published >= cur.date_published` and a release type at least as stable
(lines 152-158). The fake `/v2/projects` must return a JSON array: `projects()` is in the same try block as the hash
lookups, so a failure makes the whole fetch offline and hides the update (lines 42-104). Updates don't resolve
dependencies (DownloadPlanner.updateMod, lines 186-204). There is no host check on download URLs (HttpModrinthClient:151),
so any HTTPS URL on the fake host works.

### M10 (MEDIUM) Loom's production run task adds the dev jar and passes mods outside mods/.
Evidence (javap, fabric-loom 1.17.21): `AbstractProductionRunTask` adds the project's `jar` to `mods` by default and
passes `mods` as `-Dfabric.addMods=`. build.gradle:160-166 (`runProductionSmoke`) relies on this. For the E2E this means
two `rigtune` mods (0.1.0 in run/mods plus the dev jar), and Fabric refuses to start. Any jar passed via `addMods` sits
outside the mods folder, so ModScanner gives it `file == null`. The update then becomes `Action.None` ("update it in
your launcher", Recommender.java:224-228) and can't be applied. The same trap applies to the 0.2.0 relaunch.
`prepareProductionSmoke` uses `fs.sync` into run/mods (build.gradle:139-142), which deletes `.disabled` and
`.rigtune-pending` files if it runs again.
Fix for WS-G: a dedicated task with `mods.setFrom(<driver jar>)` only, rigtune-0.1.0 and fabric-api copied into
`<scratch>/mods`, `runDir` set to the scratch copy, and no re-sync before the second launch.

### M11 (MEDIUM) A GitHub-installed 0.2.x is only matched if Modrinth hosts the byte-identical release asset.
Evidence: the same hash matching applies to every later update. SPEC item 4 wants "independent steps/jobs" for Modrinth
publishing. A separate job, or a re-run after a partial failure, rebuilds the jar, and it matches only if the build is
bit-reproducible. Gradle 9's reproducible-archive defaults are NOT verified for this Loom/Stonecutter build.
Fix: Minotaur's `uploadFile` must be the exact file attached to the GitHub release (download the release asset in the
Modrinth job). Add to AC4.1: the sha512 of each GitHub asset equals the sha512 of the Modrinth primary file
(`GET /v2/version_file/{sha1}`).

### M12 (MEDIUM) Nothing stops 0.2.x from bricking the post-exit self-update.
Evidence: the update is applied after exit with no dependency check. If 0.2.0's `fabric.mod.json` `depends` becomes
stricter than 0.1.0's (`fabricloader >=0.19.5`, `fabric-api *`, `java >=25`), a user whose loader or fabric-api is older
can't start the game, and there's no RigTune to undo it. Unchanged today (src/main/resources/fabric.mod.json and the
feat/multi-version copy), but nothing guards it.
Fix: a CI check or AC that the released `depends` of each version are no stricter than the previous release's, or that
any tightening is paired with an `advice`/`minModVersion` plan.

### L4 (LOW) E2E harness details (UNVERIFIED items marked).
- `jdk.net.hosts.file` makes every unlisted host fail to resolve. Include `127.0.0.1 localhost` and the machine's host
  name (UNVERIFIED: whether Minecraft/Log4j handle `getLocalHost` failures). WS-G task 2 proves the property itself.
- A PKCS12 truststore made by keytool needs `-Djavax.net.ssl.trustStorePassword` or the cert bags may not load
  (UNVERIFIED).
- The cert needs SANs for `api.modrinth.com` and `cdn.modrinth.com`, or put the download URL on `api.modrinth.com`.
- Port 443 must be free on 127.0.0.1 (UNVERIFIED on this machine).
- A Java `HttpsServer` (`java Fake.java`) avoids needing openssl to get a PEM key for Python's `ssl`.
- The driver should use reflection: `RigTuneController.apply` returns `Component`, so compiling against 0.1.0 needs MC
  classes. It must be removed or inert for the 0.2.0 launch, or it will try to "update" again.
- Capture pending.json *before* quitting, because the helper deletes it.
- Captured fixtures hold absolute scratchpad/user paths. Template them: the 0.2 helper refuses ops outside its folders
  (ApplyExecutor.containmentProblem) and the game drops them (`relocated`).

### L5 (LOW) The disclosure says downloads come from Modrinth's CDN, but nothing enforces that.
Evidence: no host allowlist exists (HttpModrinthClient.java:151). Fix: in 0.2, require https and `cdn.modrinth.com`,
plus the host of `-Drigtune.modrinth.baseUrl` when set. Owner: WS-G, which already edits HttpModrinthClient.

---

## 4. Workstream split and ownership

### M13 (MEDIUM) Three workstreams edit `RealController.apply()` at once, and WS-D gets a second recording path.
Evidence: the Hotspots table puts B (recording), D (dh./iris. routing) and E (Modrinth gating, "Add/Update become
advice") all in `apply()`/`startDownloads` (RealController.java:224-289, 309-334). WS-D is told to call
`ChangeRecorder.current().record(...)` directly if B hasn't merged (PLAN line 89). With H4 that yields separate journal
entries, and a recording path B must later find and remove. B needs vanilla before-values next to
`SettingsBridge.applyVanilla`, but D owns SettingsBridge.
Fix: before Wave A, the coordinator makes one refactor commit that splits `apply()` into seams:
- a `ConfigStager` registry keyed by namespace (Sodium now; D registers dh/iris);
- a `Staging` class that owns `stage()`, the merge and the recording (B);
- a `NetworkPolicy` (E).
Merge B before D, or have D stage only through the registry and never call the recorder.

### M14 (MEDIUM) Unowned work and hidden dependencies.
- The Recommender change for the unticked avoid (H3). Owner: WS-A.
- Startup journal reconciliation (H5). Owner: WS-B.
- An end-to-end undo after a restart (AC3.2 says "via the post-exit pipeline" but only unit tests cover it; AC3.4's game
  test only exercises the STAGED-removal path, because the Sodium change never gets applied in-session). Add it to
  WS-G's Phase 5 run: relaunch 0.2.0 → apply a mod change → quit → helper → Undo last → quit → helper → assert.
- The `mod_version` bump and CHANGELOG content (F has only a skeleton).
- WS-G's driver "own source set or tools subproject" edits build.gradle/settings.gradle (Stonecutter), which the
  Hotspots table doesn't list.
- README: E (Privacy), Phase 3 (Build from source) and F (body.md adapted from the README) all touch it.
- Settings toggles "trigger a rescan" (SPEC item 8), which needs WS-A's `loadRules` to be re-runnable. Name that seam.
- WS-A's `modVersion` needs mod versions in `EvalContext` (the record has only `loadedModIds`,
  core/rules/EvalContext.java). That's internal to WS-A but touches the tests' constructors.

### M15 (MEDIUM) WS-F's irreversible public actions contradict the SPEC.
Evidence: SPEC item 4 (line 148) says uploading the v0.1.0 jar is "a public action beyond publish v0.2.0: ask the user
first". PLAN WS-F task 2 (line 111) runs it and submits for review without an ask step. The "Authorised by the user"
note covers only token handling.
Fix: put an explicit user confirmation in task 2, covering project creation, the v0.1.0 upload and submit-for-review,
and record it in F.md. Also, the 0.1.0 upload must happen before any 0.2.0 upload: the `date_published` ordering above
means otherwise no update is offered.

### L6 (LOW) Small contract and spec mismatches.
- `ClientState.benchmarkScene = "CURRENT"` (cae06b8) vs SPEC item 8 `"current"`/`"benchmark-world"`. `BenchmarkSummary.scene`
  and `benchmarks.json` also need one spelling. Pick the enum names.
- `modrinthAllowed() = networkEnabled && updateChecks` also turns off Add and downloads. Rename the switch "Modrinth
  lookups", or split it.
- en_us.json contract keys went in out of alphabetical order (after `rigtune.benchmark.title`). Trivial.

---

## 5. Scope and risk: what's likely to slip, and what to cut

### M16 (MEDIUM) The benchmark protocol doesn't fit its time budget, and the DH knob can't be measured in it.
Evidence: SPEC item 6 gives per candidate settle ≤ 20 s + 1.5 s + 2 × 8 s, plus 2 final repeats, with a total of ≤ ~4
min. The research budget (benchmark.md:298-303) assumed settle ≤ 2 s and a single 6 s phase for secondary knobs. With
the SPEC's numbers the worst case is RD 6 × 37.5 s + 3 knobs × 3 × 37.5 s + 35 s ≈ 10 min. The deadline will routinely
cut the DH and shader knobs. A DH LOD change also triggers LOD regeneration, far slower than 20 s.
Fix: use the research's cheaper secondary-knob protocol. Make DH LOD a measured-at-current-LOD report, or drop it from the
descent. State in AC6.1/6.2 which knobs must finish inside the budget.

### M-risk (listed with M16, not counted) The harness deadlock with DH blocks AC6.4 and AC7.3 as written.
Evidence: docs/smoke/README.md:24-37 shows the client game-test harness deadlocks on world exit with DH loaded, and
ProductionSmoke is harness-driven (`-Dfabric.client.gametest`, build.gradle:165). AC7.3 needs a clean exit so
CLIENT_STOPPING launches the helper. A killed client never launches it.
Fix: stage the DH patch from the title screen (no world) and quit from there. Check AC6.4's values in-test and then
kill the client. Or use a non-harness production launch for these ACs. The same phaser deadlock may hit
benchmark-world's "exit to title" (UNVERIFIED), which is one more reason the WS-C spike comes first.

Most likely to slip, in order:
1. The benchmark-world scene (WorldOpenFlows inside the harness, exit-to-title deadlock, determinism check).
2. The DH/Iris benchmark knobs (budget, LOD regeneration, Iris reload hitch; the shader cost needs a pack in the smoke
   instance).
3. AC7.3 and AC6.4 production runs with DH.
4. Modrinth approval timing (outside our control; AC4.3 only needs honest reporting).
5. The self-update E2E plumbing (M10, L4).

Cut first, after the P2 items:
1. benchmark-world (AC6.3).
2. The Iris shader-cost report.
3. The DH LOD knob in the descent. Keep DH/Iris *settings* recommendations (item 7), which are cheap and unit-testable.
4. The before/after chart. Keep the gain line.

Never cut: H1-H5 fixes, M5, M10.

### L7 (LOW) Submitting for review with only 0.1.0 exposes the project to a 0.1.0-based rejection.
0.1.0 has no network switch. Moderators apply §2 "Clear and Honest Function" (modrinth.md:112-120) against the jar
under review. The trade-off against review lag is reasonable. Note it in F.md, and have the listing body describe only
0.1.0's behaviour until 0.2.0 is uploaded.

---

## 6. Contracts (cae06b8): will anything force a breaking change mid-flight?

Breaking changes to make **now**, before workstreams start:
- **ChangeRecorder** has no entry grouping (H4).
- **PendingActions.Merged** needs op-id mapping and replaced ids, and `discard` needs to return the dropped ids (H5).
- **OpResult** and **JournalChange** need the actual disabled target (M6).
- **RigTuneController.undo(boolean)** should become `undo(UndoPlan)` or a fingerprint (M8).
- **ClientState**: decide whether the v0.2 settings move to their own file (M2).
- **ApplyLock**: reentrant, or a documented `withLockHeld` journal API (M4).

### L8 (LOW) Smaller contract notes.
- The `JournalEntry` compact constructor's `List.copyOf` throws on a null element, so one bad element sends the whole
  history.json to `.bad`. Filter nulls instead.
- `JournalChange.setting(...)` can't express "key absent before" (see L2).
- `TomlConfigPatcher.stage`/`PropertiesConfigPatcher.stage` return `SodiumConfigPatcher.Staged`. Fine, but it has no
  before-values (see L2).

### L9 (LOW) Label the undo action type in the plan.
`UndoPlan.Item` has `needsRestart` but no action type. The confirmation screen can't group revert, discard (group
removal) and skip without parsing strings. Add an enum.

### L10 (LOW) Iris toggling persists immediately.
`IrisApi...setShadersEnabledAndApply` saves iris.properties at once (dh-iris.md:367-377). After a crash and a downgrade
to 0.1 (no restore marker support), shaders stay off. Mention it in the downgrade doc (M3).

### L11 (LOW) `SettingsBridge.read` on the render thread will also parse the TOML and properties files.
It runs on the render thread in `rebuild()` (RealController.java:186). With DH's 1000+-line TOML and iris.properties
added, cache by mtime.

---

## Not verified (said so above where relevant)
- POSIX release of the outer lock on a nested close (M4).
- Gradle 9 reproducible archives for this build (M11).
- `jdk.net.hosts.file` behaviour for localhost/getLocalHost, a PKCS12 truststore without a password, and port 443 being
  free (L4).
- `VersionPredicate` behaviour on non-SemVer mod versions (it only matters for H1's UNKNOWN classification).
- The harness phaser deadlock with a WorldOpenFlows-created world (M-risk).
- Moderator reaction to a project with only 0.1.0 (L7).
