# RigTune review 2 (feat/rigtune-mvp @ 6f42500, fixes 58c6d6a..HEAD). Read-only.
core = src/main/java/io/github/chaotix345/rigtune/core, client = src/client/java/io/github/chaotix345/rigtune/client

## Status of the 18 review-1 findings

| # | Status | Evidence |
|---|---|---|
| 1 | FIXED | The helper runs from copies in config/rigtune/helper (core/apply/HelperLauncher.java:33-42, 63-108). Self-update/disable is filtered out when RigTune isn't running from a jar (client/RealController.java:204, 214-220). An update is now a group (see #2). |
| 2 | FIXED (gap: N1) | Groups are all-or-nothing, disables run first, and rollback runs in reverse (core/apply/ApplyExecutor.java:108-148, 150-159, 174-192). The helper holds apply.lock (core/apply/ApplyHelper.java:307-317). preLaunch waits up to 5 s and then reports (client/RigTunePreLaunch.java:24-53). Update ops are grouped (RealController.java:409-416). |
| 3 | FIXED | Name validation is at core/apply/SafeFileNames.java:199-239, and resolve plus direct-child checks at :165-184. It's used at RealController.java:378, 405, 452 and core/modrinth/HttpModrinthClient.java:144, and each op is re-checked in ApplyExecutor.java:194-226. |
| 4 | FIXED | Each ENABLE records its modId (RealController.java:394, 410). Merge replaces an existing ENABLE for the same modId, takes over its group, and retires the old jar (core/apply/PendingActions.java:114-160, RealController.java:474-486). loadedIds is deliberately not seeded (DESIGN, Deviations (client)). |
| 5 | FIXED | stage() runs under apply.lock (RealController.java:458). The helper re-reads the plan and removes only the ops that succeeded, matched by id (ApplyExecutor.java:57-73, PendingActions.java:67-72). |
| 6 | FIXED | Body cap, stall and deadline, then cancel (core/net/BoundedHttp.java:57-92, 118-145). Limits and one 429 retry (HttpModrinthClient.java:49-53, 150, 228-266). Errors are logged (core/modrinth/OnlineDataFetcher.java:94-104). The downloading flag is always reset (RealController.java:305-330). |
| 7 | FIXED | Test values are in memory only (client/benchmark/BenchmarkController.java:124, 178, save=false) and saved once on restore (:255-261). Tick exceptions call finish(true) (:155-166). CLIENT_STOPPING cancels (client/RigTuneClient.java:69-72). |
| 8 | FIXED | The run is uncapped (BenchmarkController.java:35-38, 124) against refreshRateCap (:88). Best effort uses the highest 1% low (core/benchmark/RenderDistancePlanner.java:81-91, PlannerResult.suggestedRd). The "Use N" label changes when the target is missed (client/ui/BenchmarkResultScreen.java:53-58). |
| 9 | FIXED | Allowlist and control-character check (core/model/SettingKeys.java:8-35), enforced in client/probe/SettingsBridge.java:130-165 and core/recommend/Recommender.java:275. Every vanilla key in rules-v1.json and every key the benchmark sets is on the allowlist. |
| 10 | FIXED | Each existing field keeps its JSON type, and a value that doesn't fit fails the op (core/apply/SodiumConfigPatcher.java:53-105). There's still no range check, but the suggested fix didn't ask for one. See N3 for a side effect. |
| 11 | FIXED | "actions: write" (.github/workflows/update-rules.yml:11). build.yml has workflow_dispatch. The repo setting "Allow GitHub Actions to create and approve pull requests" is still manual. |
| 12 | FIXED | Java checks availability per version (OnlineDataFetcher.java:72-91, 109-149, and pickLatest requires a file). Python uses the project fields as a pre-filter only, then checks per version (tools/update_rules.py:279-304). |
| 13 | FIXED | 2 MB body cap (core/rules/RemoteRulesFetcher.java:52-55), cache size check and OOM handling (core/rules/RulesLoader.java:40, 73). Patterns over 200 chars are dropped (core/rules/RulesDocument.java:80-93), and matching uses a read budget (RulesDocument.java:102-113, core/rules/BudgetedChars.java). Classifiers go through find() (GpuClassifier, CpuClassifier). |
| 14 | FIXED | BigInteger compare (Recommender.java:98-110), with each section isolated (:75-96). |
| 15 | FIXED | The SWEEP_DOWN pitch is +25 (BenchmarkController.java:210). |
| 16 | FIXED | One shared instance (client/ClientState.java:24-29, RealController.java:87, RigTuneClient.java:172, RigTunePreLaunch.java:60), written temp-then-ATOMIC_MOVE (ClientState.java:145-163). |
| 17 | FIXED (caveat: N6) | client/probe/ModScanner.java:35, 47, 63-65 set the file only for direct children of mods/. RealController.java:240 and 402 re-check this. |
| 18 | FIXED | Both Retry-After forms are parsed (tools/update_rules.py:110-127), bad TOML is skipped (:218-222), table cells are escaped (:376-384, used at :402/416/426), and the revision is guarded (:491-496). |

## New findings (most severe first)

N1. MEDIUM: Carried-over ENABLEs are applied without re-checking mods/. A mod replaced outside RigTune ends up as two jars and Fabric won't start.
core/apply/ApplyExecutor.java:132-136 (SKIPPED_ALREADY_DONE counts as success for the group), :282-286 (a DISABLE whose file is gone becomes SKIPPED), :267-279 (ENABLE only checks "to").
Scenario: the plan [DISABLE sodium-0.7.0.jar, ENABLE sodium-0.7.1.jar.rigtune-pending -> sodium-0.7.1.jar] is carried over because the game was killed or crashed (no CLIENT_STOPPING, so no helper), the helper timed out, or the group failed. The user then updates Sodium in their launcher, which deletes sodium-0.7.0.jar and writes sodium-0.7.2.jar. At the next exit the DISABLE is "already gone" and the ENABLE succeeds. mods/ now holds 0.7.2 and 0.7.1, and Fabric refuses to start (duplicate id). A carried-over AddMod plus a manual install of the same mod does the same.
Fix: in the executor, before an ENABLE_FILE that has a modId, scan modsDir/*.jar (ModJars.modIdOf) for that id, ignoring files this group has just disabled. If a match exists, fail the group, or retire the pending jar and drop the op. RealController could also drop carried-over ENABLEs whose modId is already loaded from a file other than the group's DISABLE path.

N2. MEDIUM: Dependency bookkeeping inside one download batch isn't transactional, and a dependency shared by two recommendations lands in only one group. A mod can be enabled without its required dependency.
client/RealController.java:373-395 and :416-421. installedProjects.add (:379) runs before fetch (:383), and loadedIds.add (:386) mutates the shared sets. If a later version in the same recommendation throws, the catch at :418 drops recOps but leaves the sets mutated.
(a) AddMod A resolves [A, lib]. fetch(lib) fails (stall, hash mismatch, unsafe name, 429), so A isn't staged but lib's project id is now "installed". AddMod B also needs lib, and DependencyResolver skips it (seen set, DependencyResolver.java:36-41). B is staged alone. Next launch: "B requires lib", so the game doesn't start. This part predates the fixes.
(b) Both succeed: lib is only in A's group (:416). If A's group fails at exit (e.g. A's target already exists), lib is rolled back, B's group applies, and B is missing lib. This is new with groups.
Fix: work on per-recommendation copies of installedProjects/loadedIds and commit them only on success. When a recommendation relies on a dependency added earlier in the batch, join the two groups (or group the whole batch).

N3. LOW-MEDIUM: A permanently failing op is retried at every exit, forever, and one bad Sodium key blocks every Sodium setting in that Apply.
core/apply/ApplyExecutor.java:57-73 (FAILED ops stay pending); core/apply/SodiumConfigPatcher.java:65-85 (new since #10: a type mismatch fails); client/RealController.java:262-264 (every selected Sodium key goes into one PATCH_JSON op).
Scenarios:
- A remote rule's Sodium value doesn't fit the existing field type (e.g. "auto" for a number). The whole PATCH_JSON op fails every exit, taking the valid keys with it.
- An UpdateMod target already exists (UpdateMod never checks Files.exists(target), unlike AddMod at RealController.java:380). The ENABLE fails with "already exists" every time.
- A same-file-name update (old name == new name) re-runs after the helper died between the renames and the pending.json rewrite, or after AtomicFiles' MoveFileEx(REPLACE_EXISTING) failed with AccessDenied because an AV scanner, indexer or sync client had pending.json open without FILE_SHARE_DELETE (no retry, core/apply/AtomicFiles.java:314). The DISABLE now moves the new jar, the ENABLE finds its source missing, and the rollback restores it. Both ops stay FAILED for good.
In every case the "N staged changes were not applied" toast returns on every launch (RigTunePreLaunch.java:69-71), and the UI has no way to clear it.
Fix: validate the Sodium patch against the current file before staging, and stage one op per key. Give ops an attempt counter and drop them (with a toast) after N failures, or add a "discard pending changes" action. Retry AtomicFiles' move a few times on AccessDeniedException.

N4. LOW: stage() keeps an existing plan's modsDir/configDir, so a copied or moved instance refuses all new ops and applies old ops to the other instance.
client/RealController.java:471 (the existing plan is the base); core/apply/ApplyExecutor.java:76-77 and :214 (containment is checked against plan.modsDir).
Scenario: an instance is copied (e.g. Prism "Copy instance") or moved while pending.json exists. In the copy every new op is refused ("not directly inside the mods folder <original>"), and the carried-over ops pass containment and rename jars in the original instance's mods/.
Fix: derive configDir from the pending.json location (pending.getParent().getParent()) and modsDir from that, in both the helper and stage(). Drop or refuse a plan whose recorded dirs differ.

N5. LOW: The helper holds apply.lock during its up-to-15-minute wait for the old JVM, so staging fails in a relaunched game.
core/apply/ApplyHelper.java:307 and :324; client/RealController.java:55 and :458-461.
Scenario: the old JVM lingers after its window closes (non-daemon threads or a slow integrated-server save) and the user relaunches. preLaunch warns, and then every Apply in the new session fails "the apply helper still holds apply.lock" for up to 15 minutes. Completed downloads are left as orphaned .rigtune-pending files, and the render thread blocks for 2 s per attempt. Nothing is corrupted: the lock is an OS lock, so no stale lock survives a crash.
Fix: don't hold the lock during the pid wait. Acquire it after the game exits (preLaunch already tolerates a busy helper), or cap the pre-exit hold (e.g. 30 s) and re-acquire after exit.

N6. LOW: modsDir is hard-coded to gameDir/mods, but Fabric honours -Dfabric.modsFolder (verified: the string is in fabric-loader 0.19.5's FabricLoaderImpl). With the #17 fix, such setups lose all update and disable actions, and AddMod downloads land in a folder Fabric never scans, so the "Install X" recommendation comes back every session.
client/probe/ModScanner.java:35; client/RealController.java:82.
Fix: resolve System.getProperty("fabric.modsFolder", "mods") against gameDir in both places. A symlinked mods/ may fail the same way if Fabric reports real paths; not verified.

N7. LOW: a SafeFileNames false negative: 8.3 short-name aliases pass the check.
core/apply/SafeFileNames.java:199-239 accepts "SODIUM~1.jar". On NTFS volumes with 8.3 names (the default on many system drives) Files.exists resolves that to an existing long-named jar. ENABLE is still safe (it refuses to overwrite), but in AddMod RealController.java:380 skips the dependency as "already installed", so the added mod is enabled without it.
Fix: reject names that match ~\d+\. in the stem, or compare target.toRealPath().getFileName() with the requested name when the target exists.

## Checked, no defect
- BoundedHttp: send() polls, then cancel(true) on the sendAsync future at stall or deadline. The body subscriber is async, so no caller thread blocks. A write racing the channel close fails as ClosedChannelException and the tmp file is deleted. No threads leak (checkVersions uses its own pool, shut down with shutdownNow).
- Regex guard: every read goes through the budgeted charAt, subSequence shares the budget, compile is capped at 200 chars, and exhaustion counts as no match. The cost is bounded (about 1M reads per rule).
- Lock files: OS FileLock, released on process death. preLaunch and stage() always close the lock (try/finally, try-with-resources). There's no in-JVM deadlock (OverlappingFileLockException is handled) and no lock-order cycle, because only one lock exists.
- Rollback: undo runs in reverse, is refused when the original name is taken, and a failed rollback is reported and left pending. PATCH_JSON is never grouped (RealController stages it ungrouped, and merge only joins groups on an identical change).
- Benchmark restore: every exit path calls finish(), which restores once and saves only when a value differs. A crash leaves options.txt untouched because test values are never saved. The only exception is a vanilla save during the run (e.g. F11), which a later restore overwrites.
- ClientState on Windows: ATOMIC_MOVE with REPLACE_EXISTING maps to MoveFileEx(REPLACE_EXISTING), which replaces fine. Java readers open with FILE_SHARE_DELETE. The only failure mode is the AccessDenied case in N3 (a lost write, logged).
- SettingKeys against the rules: every vanilla key in rules-v1.json, the benchmark's four keys and the result screen's renderDistance are all on the allowlist. Sodium keys pass by prefix.

Summary: 0 blockers, 2 MEDIUM (N1, N2), 1 LOW-MEDIUM (N3), 4 LOW (N4-N7). All 18 review-1 findings are fixed. Recommend fixing N1 and N2 before release.
