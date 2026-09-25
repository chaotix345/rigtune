# WS-A design notes: deferred defects (3a, 3b, 3c) and the raw game version

Branch `fix/v03-deferred`. Plan: docs/v0.3/plans/ws-a.md. Spec: SPEC 3a-3c, item 1's raw-version bullet, amendments A-H1, A-M1, A-M2, A-L1.

## Raw game version (SPEC item 1, A-L1)
- `FabricLoader.getRawGameVersion()` exists in Loader 0.19.5 (checked with javap on `fabric-loader-0.19.5.jar`).
- The named seam is `OnlineLookupGate(Supplier<String> rawGameVersion)`: RealController passes `FabricLoader.getInstance()::getRawGameVersion`; tests pass a lambda. `Lookup.gameVersion()` is what Modrinth is asked about (`OnlineDataFetcher.fetchAll`); `Lookup.hardware().mcVersion()` stays the normalized version for the rules' conditions. `modrinthGameVersion(normalized)` is used for the download resolver too, and falls back to the normalized version when the raw one is null or blank.
- Every Modrinth game-version query goes through these two paths: the hash lookups (`latestVersionsByHashes`, which is also the self-update check: RigTune's own jar is one of the hashed mods), project support checks and per-project version checks in OnlineDataFetcher, and `latestVersion` in DependencyResolver.
- Recorded, not changed: Recommender's offline availability map (bundled rules) is keyed by Modrinth game-version tags but looked up with the normalized version, so it misses only on a snapshot (normalized `26.4-alpha.1` vs tag `26.4-snapshot-1`); online availability is by slug and unaffected.

## 3b: the post-update view (A-H1)
- `OnlineDataFetcher.Result` gains `installedVersions` and `updateVersions` (Modrinth versions by version id, dependencies included) and `projectIdsByVersionId()`. The old 3-arg constructor stays; `UpdateInfo` is unchanged.
- `DependencyResolver(client, loader, gameVersion, Map<String, ModrinthVersion> installedVersions)`. The `Set<String>` constructor still works (versions with no project and no dependencies).
- `resolve(slug, installedProjects, batch, updatedProjects)` returns `Resolution(versions, updatesNeeded)`. For each version the addition brings in, an `incompatible` dependency:
  - naming a version installed now: refused, unless that version's project has a staged update in this batch; then the project goes into `updatesNeeded`;
  - naming an installed project: refused (the project stays installed after its update);
  - matching anything going in together (the batch, which holds the staged updates' new versions, and the addition's own versions): refused.
  - Reverse directions: a batch version, or an installed version, declaring the addition incompatible is refused; an installed version of an updated project goes into `updatesNeeded` instead (its new version is in the batch and is checked there).
- `checkUpdate(update, installedProjects, batch)`: the update's own version, checked the same way, with its own project's installed version ignored (it's the one being replaced). Another project's installed version counts even when that project is also being updated in the batch: refusing is safe, and the update is offered again next launch once the other one is installed. This keeps the outcome independent of the order of updates.
- DownloadPlanner: every UpdateMod runs before any AddMod (selection order kept within each kind), so tick order doesn't change the outcome (AC3.2 iv). A staged update adds its version to `batch.versions` and its project to `groupOfUpdate`. An addition resolves with `updatedProjects = groupOfUpdate.keySet()` and joins the group of every project in `updatesNeeded`, so the addition and the update apply together or not at all; a failed update download never commits, so the addition is judged against the installed version and refused (AC3.2 v). An update missing from `updateVersions` is checked as a bare version (id + project, no dependencies).
- `DownloadPlanner.Result.opIds`: each staged recommendation's op ids (its own ops; the ops of the group it joined when it brought none), for A-M1.

## 3c: names (A-M2)
- A version-only dependency is named by the project of that version, from local data only: the installed versions map, then the versions going in together; then the project's title, slug, and "another mod". No new Modrinth call or endpoint (the title comes from the same `projects()` call the messages already used). A project dependency keeps its title → slug → project id fallback, as before.
- The installed-direction message was the only one that could show a version id; the "together" and batch messages already named projects.

## 3a: the queued-update drop and staged bookkeeping (A-M1, A-L1)
- `Staging.dropQueuedUpdates(queued, loaded)` unstages an ENABLE_FILE (with its group) only when its mod id is loaded (`ModScanner.loadedIds()`: every mod Fabric loaded) and has a queued jar in `mods/update/`. An addition or an undo re-enable of a mod that isn't loaded stays. An undo re-enable of a loaded mod with a queued update is dropped too, so the notice now says "Cancelled RigTune's pending change to %s: ...", and names only the loaded queued mods (not an addition that went with one in its group).
- `Staging.stage` returns the `Merge` (null when nothing was staged). RealController keeps a `StagedRecommendations`: recommendation id → the ids its ops have in pending.json after the merge (`survivingIds`). Immediate ops map to every recommendation behind them (a config op's keys through `configIds`); downloads use `DownloadPlanner.Result.opIds`.
- After a queued drop, an undo and a discard, `recountStaged()` keeps a recommendation staged only while one of its op ids is still in pending.json, and sets `carriedOverOps` to the pending ops no staged recommendation owns (ops from another session, or 0.1.0 ops without ids).

## Deviations
- The installed mods' own `incompatible` entries are checked (the review's "known gap if not covered" is covered), for additions as well as updates. This refuses a few more additions than 0.2 did (the safe direction).
- `rigtune.status.queued_update_dropped` keeps its key; only its English text changed ("change" instead of "update").

## Known gaps
- An update staged in an earlier Apply (already in pending.json, not in this batch) isn't part of the view: an addition incompatible only with the installed old version is refused until that update has applied. Safe; it's offered again after the restart.
- Likewise, a mod the same Apply disables (an immediate DisableMod) still counts as installed for the resolver. Pre-existing for the addition's own declarations; now also for the installed mod's declarations.
- A recommendation whose download brought no ops and joined nothing (its mod was already in mods/) stays hidden until the next recount, then reappears; as in 0.2 it has nothing staged.
- The e2e drivers weren't run (their signatures are unchanged; WS-H runs them in Phase 5).
