# WS-H: apply/history leftovers (v0.5)

Branch `fix/v05-history` (worktree `rigtune-history5`), from `origin/feat/v0.5.0` @ a7613410 (ws-ci + WS-K merged).
Scope: docs/v0.5/PLAN.md "WS-H" (SPEC 2H: L5 as a real fix, L7, L9, RW-3, RW-4; SPEC 2V: AC2V.1, AC2V.3, AC2V.4,
AC2V.5). L8 is WS-P's; AC2V.2 is WS-L2's. Research: docs/research/v0.5/v04-leftovers.md (L5, L7, L9, L10),
real-world-2026-09-27.md (RW-3, RW-4, §2-§4). Contracts as landed: docs/v0.5/design/ws-k.md (StaleGroups and
RefusedDisables stubs, PreviewScreen.downloadChecks, the `rigtune.status.discarded` anchor, the footprint baseline).

This file is first the TDD task plan (committed before any code), then, as tasks land, what landed, the evidence and the
AC table.

## Code-deciding check (first task): does cdn.modrinth.com honour HTTP Range?

Done 2026-09-27 21:55 AEST (11:55 UTC), with curl, against
`https://cdn.modrinth.com/data/AANobbMI/versions/xJZxADzI/sodium-fabric-0.9.2%2Bmc26.2.jar` (1885572 bytes; its URL
from `GET /v2/project/sodium/version?loaders=["fabric"]&game_versions=["26.2"]`):

| request | answer |
|---|---|
| `Range: bytes=-65536` (suffix) | `206 Partial Content`, `Content-Range: bytes 1820036-1885571/1885572`, 65536 bytes; no redirect; `Server: cloudflare`, `CF-Cache-Status: BYPASS`, `ETag` |
| `Range: bytes=100-199` | `206`, `Content-Range: bytes 100-199/1885572`, 100 bytes, `Accept-Ranges: bytes` |
| `Range: bytes=1885000-` (open end) | `206`, `bytes 1885000-1885571/1885572`, 572 bytes |
| `Range: bytes=-9999999` (suffix longer than the file) | `206`, `bytes 0-1885571/1885572`: the whole file |
| `Range: bytes=0-9,20-29` (two ranges) | `206` but `Content-Range: bytes 0-1885571/1885572`: the whole file |
| `Range: bytes=5000000-5000100` (past the end) | `500 Internal Server Error`, empty body (not the 416 RFC 9110 asks for) |

What this decides:
- The CDN honours single byte ranges, suffix ranges included, over HTTP/1.1, without redirecting: L5 is feasible.
- The reader asks for one range per request, never several (the CDN answers several with the whole file), and accepts a
  206 only when its `Content-Range` is exactly the range asked for (suffix: the file's last N bytes) with the same total
  size in every answer; anything else is a failed read, whose body is dropped at once.
- A range past the end gives 500, so every range is computed from the total the first answer names, and any status other
  than 206 is a failed read (fallback), 416 and 500 alike.
- The tail holds the end of central directory record 22 bytes before the end (no comment), 1081 entries; the central
  directory is 135806 bytes, not inside a 64 KiB tail: typical reads are three requests (tail, central directory, the
  entry) of about 64 KiB + 136 KiB + 1.5 KiB, against 1.9 MB for the download.

The live check with RigTune's own (unit-tested) client runs once H1 lands; its output goes below ("H1 live run").

## TDD task plan

Each task: a red test first (for the P0.2 fixes, starting from the realworld / audit-verify throwaway tests where the
SPEC names them), then the code, then a commit. Local runs: `./gradlew :26.2:test --tests '<classes>'` in a build slot;
`:26.3:` too where client code differs (none expected: no new `//? if` block); the full build is CI's.

| # | task | files | tests (red first) | closes |
|---|---|---|---|---|
| H1 | L5 part 1: the Range reader. `RangeReader.read(ModFile)`: the allowed download origin only (`HttpModrinthClient.allowedDownload`'s rule, the same `-Drigtune.modrinth.baseUrl` origin), only while Modrinth is allowed (a gate checked before every request), one range per request (tail 64 KiB, then the central directory if the tail doesn't hold it, then the one entry), each 206 checked against the range asked for, every other answer failed and its body dropped (a capped subscriber with room 0), BoundedHttp's stall and deadline per request plus an overall deadline, byte caps (tail, central directory 2 MiB, entry `ModJars.MAX_FABRIC_MOD_JSON_BYTES` compressed and inflated), stored and deflated entries, CRC-32 checked, no ZIP64/multi-disk/encrypted (failed). The result: the entry's bytes, "no fabric.mod.json" (a central directory read in full without it), or a failure with its reason; the bytes read are counted. Nothing touches the disk. `FabricModJson.parse(bytes)`: id, name, version, provides, depends/breaks ranges, whether it nests jars (null when Apply's `ModJars` would read no id). The HttpClient is built on first use (on the preview thread) and closed with the reader. | new `core/modrinth/RangeReader`, new `core/modrinth/FabricModJson` | `RangeReaderTest` over fixture jars on a loopback `HttpServer` serving Range (stored entry, deflated entry, entry inside the tail, central directory outside it, no fabric.mod.json, truncated file, garbage, a 200 with the whole body, 416, 500, a wrong `Content-Range`, a stall, a disallowed origin, the gate off; bytes read never over the caps; the served ranges; the temp dir unchanged); `FabricModJsonTest` | AC2H.1, AC2H.1c (unit) |
| H2 | L5 part 2: the dry run checks what it read. `DownloadInputs.withJarChecks(JarChecks)` (the pins, the ids a jar might nest, the reader); `PreviewDownloads` reads each planned download's fabric.mod.json (Modrinth on only, bounded per preview), `DryRunPlanner` gives the planner the real mod id (an update's stays the updated mod's unless the jar says the same) and each read jar's `VersionPins.Jar`, and `DownloadPlanner` runs its `checkVersions` in the dry run with those jars. A jar whose read failed is judged on nothing (as today); a jar whose nesting is unknown (read failed, or it nests jars) counts as providing every id that is loaded but not top-level, so the dry run never refuses what Apply's full read would let through. `ApplyPreview.downloadsChecked` (new optional component, old constructors kept): every listed download was read. `RealController.preview` passes `PreviewJarChecks.of(...)` (new client helper: `FabricPins.loaded()`, the nested/provided ids, a `RangeReader`). | `DownloadPlanner`, `DryRunPlanner`, `core/preview/{DownloadInputs, PreviewDownloads, ApplyPreview}`, new `client/probe/PreviewJarChecks`, `RealController.preview` (one line) | `PreviewDownloadsTest`: a download whose fabric.mod.json breaks an installed mod gets Apply's own refusal line (`rigtune.download.*` text as `DownloadPlannerTest`'s H2 cases give it), a clean one is listed; read refused → listed as today, `downloadsChecked` false; Modrinth off → no request; a nesting jar can't be refused on a nested library. `DownloadPlannerTest`'s H2 cases unchanged; `PreviewDifferentialTest` unchanged | AC2H.1b (unit) |
| H3 | L5 part 3: the screen, the fake and the game test. `PreviewScreen.downloadChecks`: the disclosure `rigtune.preview.note.downloads` moves in here and shows only while a listed download wasn't checked; its English also names the version-range case. FakeModrinth's one marked method `ranged(HttpExchange, Response)` (+ one call in `handle`): single `bytes=a-b`, `a-` and `-n` ranges on CDN files → 206 with `Content-Range`, a range past the end → 416, several ranges → the whole file (as the CDN). PreviewGameTest: network on, a preview of additions of the real Mod Menu and Fabric API jars FakeModrinth serves, with a pin that Mod Menu's version breaks and a temp mods folder: Mod Menu under "Not changed" with Apply's line, Fabric API listed, no disclosure line; network off (GameTestNet): the disclosure line with downloads listed, none without; the real preview (text-file candidates, which aren't zips) shows the disclosure; the game dir's hashes unchanged. | `PreviewScreen.downloadChecks` (+ the moved line), `en_us.json` (`rigtune.preview.note.downloads`), `tools/e2e/.../FakeModrinth.java` (marked method), new `FakeModrinthRangeTest`, `PreviewGameTest` | `PreviewScreenTest` (the note with and without `downloadsChecked`), `FakeModrinthRangeTest`, PreviewGameTest on 3 legs, screenshots | AC2H.1b |
| H4 | RW-3: stale groups at launch. New `core/history/StaleOps`: a staged group is stale when an enable's download is missing (`Files.exists`), or its mod id is loaded from a jar other than the op's target and other than the jars its group disables; installed (the target exists or the mod is loaded from another jar) → ABANDONED, else DISCARDED; a half-done group (PartlyApplied) never. `Staging.dropStale(...)` (lock, unstage whole groups, retire downloads, journal statuses); `StaleGroups.drop`/`status` ("RigTune dropped its pending change to %s: it is already installed (%s)" / "…: its download is gone"). `RigTunePreLaunch.readState` and `warnOnce` leave stale ops out of the leftover count and the WARN lines, from `Files.exists` and FabricLoader's loaded-mod origins only (no jar opened; the class loads only when pending.json exists). | new `core/history/StaleOps`, `client/undo/Staging`, `client/undo/StaleGroups`, `client/RigTunePreLaunch`, `en_us.json` (`rigtune.status.stale_*`) | `StaleOpsTest` (both triggers; an update's own disable/enable; an undo re-enable; a half-done group kept; unrelated groups kept); `StagingTest` (the real case rebuilt from `tools/e2e/seeds/v010-dh`: DH at the staged name, no download, `fabric-26.2.jar` gone → ABANDONED, variant with it disabled; download gone and not installed → DISCARDED); `StaleGroupsTest` (status text); `RigTunePreLaunchTest` (only runnable ops counted, stale ops' WARN lines left out) | AC2H.5 (AC2H.6 is WS-E's release-tier E2E) |
| H5 | L7: `Staging.discardPending()` → `Discard(dropped, keptGroup)` (`discard()` kept); `RealController.discardPending` says `rigtune.status.discarded_with_kept` when a half-done group was kept. | `Staging`, `RealController.discardPending`, `en_us.json` | `StagingTest` (PartlyApplied → keptGroup; clean → not), `Staging.Discard.status()` text test (differs from the plain one; the clean case keeps today's key) | AC2H.2 |
| H6 | 2V (ws-g2): `dropQueuedUpdates` never unstages a half-done group. The rule is already in the code (0.4, `halfDoneGroups`); the SPEC asks for the test first. | `StagingTest` | a failed-rollback half-done DH group with a queued build stays staged while another queued mod's group is dropped (green on today's code: the item closes as verified) | AC2V.4 |
| H7 | L9: staged DISABLE_FILE ops count as absent for the dependency checks. `StagedProjects` folds a staged disable (the jar's mod id and SHA-1) unless a staged enable brings that mod back; the resolver maps it to its Modrinth project through the installed versions' file hashes; an addition that requires it (transitively) and an update whose new version requires it are refused ("… needs %s, which is being turned off at the next restart"); the incompatibility checks keep it present (L10). The preview folds the ticked "Disable X" items the same way. | `StagedProjects`, `DependencyResolver`, `DownloadPlanner`, `PreviewDownloads`, `en_us.json` (`rigtune.download.*`) | `DependencyResolverTest`/`DownloadPlannerTest` red against today: an addition and an update needing a mod whose disable is in pending.json; `PreviewDownloadsTest`: the same batch (Disable X ticked with Add Y); an addition conflicting with such a mod still refused (L10); an update of X (disable + enable) doesn't count | AC2H.4 |
| H8 | 2V (ws-a): `checkUpdate` against earlier batch versions alone: an update that waited for an addition, refused because the addition's version declares it incompatible, and the reverse (the update's version declaring the addition's). `refusedTogether` never pairs an update with an addition, so only that branch refuses. | `DownloadPlannerTest` | both directions (red if the branch is removed: checked once by hand) | AC2V.5 |
| H9 | RW-4: a disable without a mod id gets the mod id of the single enable in its group, at import (`LegacyImport.applied`) and on the staged path (`StagedChanges.of`). | `LegacyImport`, `StagedChanges` | `LegacyImportTest`/`HistoryModelTest` on `src/test/resources/v010/real-instance/last-apply.json` (the real 0.1.0 run, templated) with the DH group left in pending.json (`tools/e2e/seeds/v010-dh/pending.json`) and the old jars gone: four "Updated" rows (DH, Mod Menu, YACL, Zoomify) instead of eight | AC2H.7 |
| H10 | 2V (ws-g3 L6): a change of a group the helper left half applied (FAILED at the capped attempt: `attempt == MAX_ATTEMPTS`, which only a half-applied group reaches without being abandoned) reads "Can't finish on its own yet; RigTune tries again at each exit (see config/rigtune/helper.log)", never "try 3 of 3". | `HistoryScreen.failureText`, `en_us.json` (`rigtune.history.failed_held`) | `HistoryScreenTest` over a half-applied fixture | AC2V.1 |
| H11 | 2V (ws-g2): a "Disable X" DisableGuard refuses is listed under "Not changed" with its reason (`PreviewPlanner.withDisableRefusals`, fed by `RefusedDisables.previewRefusals` from `RealController.preview`), and Apply's status counts it (`RefusedDisables.afterApply`: "%s disable(s) not applied (see the toast)"). | `PreviewPlanner`, `client/undo/RefusedDisables`, `RealController.preview` (one line), `en_us.json` | `PreviewPlannerTest` (refused → Not changed with the reason; allowed → Renamed), `PreviewScreenTest`, `RefusedDisablesTest` (the status part with a refused disable; none → nothing added) | AC2V.3 |

Order: H1 → H2 → H3 (L5 first, as the PLAN says), then H4 → H5 → H6 (Staging: RW-3 → L7 → 2V, serialised), H7, H8,
H9, H10, H11. Pushes: the first with H1 (this file goes up with it), then batched; none during a streak.

### Design notes
- **Threads and network (X8).** The Range reads run on the preview's own "RigTune preview" thread (PreviewScreen's
  executor), never on `Probes.EXECUTOR` or the render thread. They are the only new network use (the PLAN's exception
  for L5). The reader's HttpClient, and the selector thread the JDK starts for it, is built on the first read of a
  preview and closed when that preview is done, so nothing stays running.
- **X4.** Nothing new at init. `PreviewJarChecks` and `RangeReader` load on the first preview. `StaleOps` loads in
  preLaunch only when `pending.json` exists; it runs `Files.exists` and reads FabricLoader's loaded-mod origins, and
  opens no jar.
- **Why a jar's nesting counts as unknown.** A read covers the central directory and fabric.mod.json only; nested jars are
  deflated entries (Sodium's 9, Iris's 5, Fabric API's 44; checked on the cached jars), and their own fabric.mod.json sits
  deep inside, so reading them would mean reading most of the file. VersionPins uses a jar's provided/nested ids only to
  skip judging a declaration on a mod that is loaded but not top-level; treating such a jar as possibly nesting every such
  id keeps the preview from showing a refusal Apply wouldn't make.
- **No new write.** No new file, no new field in a written file (history.json gets `modId` values and ABANDONED/DISCARDED
  statuses it already knows), so no `v050-written/ws-h` set.

## H1 live run
2026-09-27 22:20 AEST (12:20 UTC), RigTune's own `RangeReader` (the unit-tested client, default limits) against the same
Sodium 0.9.2 file on cdn.modrinth.com, through `RangeReaderTest.liveCheckAgainstModrinthsCdn` (runs only with
`RIGTUNE_LIVE_RANGE_CHECK=1`, never in CI): `read 4008 bytes of fabric.mod.json (3 request(s), 138327 bytes)`, parsed
as `FabricModJson[id=sodium, name=Sodium, version=0.9.2+mc26.2, provides=[indium], depends={fabricloader=[>=0.16.0],
fabric-resource-loader-v0=[*], minecraft=[~26.2], …}, breaks={embeddium=[*], …}]`. That is 7.3 % of the file's
1885572 bytes: the 64 KiB tail, the 70292 bytes of central directory before it, and the entry with its local header and
the 1 KiB slack.

## What landed

| task | commit | change | tests |
|---|---|---|---|
| H1 (L5 part 1) | 9fa3c9fb | `core/modrinth/RangeReader`: one single range per request (`bytes=-65536`, then the central directory before the tail if the tail doesn't hold it, then fabric.mod.json's local header and data, reusing whatever the tail already holds); a 206 counts only with the exact `Content-Range` asked for and the same total every time; any other status (200, 3xx, 416, 500) fails the read and its body is dropped at once (`BoundedHttp.bytes(0, true, …)`); `Content-Encoding` other than identity fails; ZIP64, split and encrypted archives, two fabric.mod.json entries, an entry past the central directory, a size or CRC-32 mismatch fail. Caps: tail 64 KiB, central directory 2 MiB, entry 1 MiB compressed and inflated (`ModJars.MAX_FABRIC_MOD_JSON_BYTES`), 16 MiB and 30 s per reader (one preview), BoundedHttp's stall 10 s and deadline 20 s per request. Only `HttpModrinthClient.allowedDownload`'s origins (the same `-Drigtune.modrinth.baseUrl` rule), only while the gate (RigTune's `modrinthAllowed`) says so, checked before every request. HTTP/1.1 client built on the first read, `shutdownNow()` on close. `core/modrinth/FabricModJson`: the entry as Apply's `ModJars`/`JarInfo` read the jar (id, sanitised name, version, own `provides`, `depends`/`breaks` ranges, whether it nests jars); null where Apply reads no id. | `RangeReaderTest` (16 since the review: deflated and stored entries, the entry inside the tail (1 request), a jar without fabric.mod.json (missing, not failed), truncated / text / CRC-corrupt files, a 200 with the whole body (0 bytes read), 416, 500, a wrong Content-Range, a stall (fails at the stall limit), a disallowed origin / plain http / not a URL (no request), the gate off (no request), central directory and entry over their caps (not asked for), the per-preview budget, a source check that the class has no file API; the live check, skipped in CI), `FabricModJsonTest` (3: equal to `ModJars`'/`JarInfo`'s reads of the same jar; no id → null) |
| H2 (L5 part 2) | 298ba527 | `DryRunPlanner.Checks(pins, nestedOrProvided, read, close)` and `plan(…, checks)` → `Planned(result, checkedFiles)`: the fetcher reads each file once; a read jar gets Apply's mod id (an update's file keeps the updated mod's id unless the jar says the same) and a `VersionPins.Jar` from its fabric.mod.json; a jar Apply finds no id in is refused as Apply refuses it (`not_a_mod`); a failed read keeps today's stand-in, judged on nothing. A jar whose nesting a read can't know (it nests jars, or wasn't read) gets `nestedOrProvided` (every loaded id that isn't a top-level jar: nested mods and provided ids) as `provides`, which only ever makes VersionPins skip a judgement, so the preview never refuses what Apply's full read lets through. `DownloadPlanner`'s dry-run constructor takes the dry jars and pins; `checkVersions` judges dry jars by their reads. `DownloadInputs.checks` (+ `withJarChecks`, old constructors kept), used only while lookups are on, closed when the plan is done; `ApplyPreview.downloadsChecked` (every listed download read; old constructors kept). `client/probe/PreviewJarChecks.of(modVersion, allowed)`: `FabricPins.of(mods)`, `nestedOrProvided(mods)`, a `RangeReader`; `RealController.preview` passes it (one line). | `PreviewDownloadChecksTest` (9: Apply's own refusal line, compared with the real `DownloadPlanner` over the real jar, for an addition that breaks an installed mod and an update an installed mod pins; `not_a_mod`; a failed read → today's preview, unchecked; Modrinth off → nothing read; nesting jars aren't refused over a nested library, the same jar without nesting is; a failed read may carry a library another download needs; no checks → today's preview; nothing written), `client/probe/PreviewJarChecksTest` (nested and provided ids, never a top-level mod's own); `DownloadPlannerTest`'s H2 cases and `PreviewDifferentialTest` unchanged |
| H3 (L5 part 3) | 563d0a8c | `PreviewScreen.downloadChecks`: the disclosure `rigtune.preview.note.downloads` moved there from `populate()` and shows only while a listed download wasn't checked (`downloadsNote`); its English now also names the version-range case. FakeModrinth's marked `ranged(HttpExchange, Response)` + one call in `handle`: a CDN file (200, `application/java-archive`) asked for with `bytes=a-b`, `a-` or `-n` → 206 with `Content-Range` (cut at the end), a start past the end → 416, anything else (several ranges) → the whole file. PreviewGameTest `downloadChecks`: network on, the real `HttpModrinthClient` and `RangeReader` against the fake, a temp mods folder, an update of Mod Menu (the real jar the fake serves) that a test pin rules out and an addition of Fabric API: Mod Menu under "Not changed" with exactly the line Apply's own planner gives after downloading the whole jar, Fabric API listed, `downloadsChecked`, no disclosure row; network off (GameTestNet): nothing read, the update listed, the addition unresolved, the disclosure row shown; the real preview (text-file candidates, not zips) shows the disclosure whenever a file is listed; game folder hashes unchanged. | `PreviewDownloadsNoteTest` (2), `FakeModrinthRangeTest` (3, incl. `RangeReader` against the fake), PreviewGameTest on 3 legs (+ one local 26.2 run, `-PgametestClasses=PreviewGameTest`, green) |
| H4 (RW-3) | 6b058427, 4c8a27f0, 47e7b477 | `core/history/StaleOps.find(ops, exists, loadedFrom, modIdOf, halfDone)`: per group, an enable whose download is gone is INSTALLED when its target exists (and no staged disable turns that jar off) or its mod is loaded from another jar, else GONE; an enable whose download is there but whose mod is loaded from a jar that is neither its target nor turned off by any staged disable is INSTALLED; never a half-done group; one entry per group, INSTALLED first. `Staging.dropStale(loadedFrom)` (under the lock, on the relocated plan so another instance's ops are left to the helper): unstages whole groups, retires their downloads, journals ABANDONED (installed another way) or DISCARDED (gone) → `StaleDrop(dropped, stale)`; `unstageLocked` gained an ABANDONED predicate (its public signature unchanged). `StaleGroups.drop` (nothing while pending.json is absent; origins from FabricLoader, top-level PATH origins only) / `status` ("RigTune dropped its pending change to %s: it is already installed (%s)." / "…: its download is gone.", one sentence per group, names sanitised). `RigTunePreLaunch.readState` counts only runnable ops (WARN only when some are) and logs one INFO for the stale ones; `warnOnce` skips their old failures and still marks that run logged (so they aren't replayed once the group is gone); from `Files.exists` and FabricLoader's origins; only when something looks stale does it list the mods folder and read `unfinished-groups.json` for the half-done groups (never a jar); a failure counts every op, as before. | `StaleOpsTest` (8), `StagingTest` (+5: the real DH group → ABANDONED ×2, the app's jar untouched, an unrelated group kept; the disabled-in-the-app leg; download gone → DISCARDED; loaded from another jar → download retired; half-done and runnable groups kept, busy lock → null), `StaleGroupsTest` (2), `PreLaunchStaleOpsTest` (3: the real instance → count 0, no replayed WARN, then or at the next start; a runnable group still counted and logged; a failed check counts everything) |
| H5 (L7) | 3f012d7c (wording 127c7ca9) | `Staging.discardPending()` → `Discard(dropped, keptGroup)` with `status()`; `discard()` delegates; `RealController.discardPending` returns `discard.status()`: `rigtune.status.discarded_with_kept` when a half-done group was kept. | `StagingTest` (+2: kept group → the new key with the dropped count; clean → today's key; busy → null) |
| H6 (2V, ws-g2) | 23dfdbde | Test only: the rule (`halfDoneGroups` in `dropQueuedUpdates`) is 0.4's. | `StagingTest.aHalfDoneGroupWithAQueuedBuildStaysWhileOthersAreDropped` (failed-rollback state); fails with the rule removed (hand mutation, below) |
| H7 (L9) | 4bede50f | `StagedProjects` + `disabledSha1s`, `enabledMods` (old 2-arg constructor kept), `withEnables`, `withDisabled`: `read()` folds every staged DISABLE_FILE whose jar is there and whose mod no staged enable brings back, by SHA-1. `DependencyResolver.disabledProjects()` (the installed versions whose file SHA-1 matches; no Modrinth call); `resolve` refuses a required dependency among them, `refuseDisabledRequirements(update)` an update's (`rigtune.download.needs_disabled`: "it needs %s, which is being turned off at the next restart"); `DownloadPlanner.updateMod` calls it after `checkUpdate`. `PreviewDownloads` folds the preview's own Disable items (Apply stages them before its downloads). | `DownloadPlannerTest` (+4: an addition and a transitive one refused; an update refused before downloading; a disable an enable brings back doesn't count; L10: an addition incompatible with the disabled mod still refused), `PreviewDisablesTest` (same batch: refused; without the disable: listed). Red first: with the four main files at HEAD the three refusal tests failed (`[add-a, add-b]`, `[update-a]`, the download listed) |
| H8 (2V, ws-a) | c6bd8ea7 | Test only. | `DownloadPlannerTest` (+2: an update that waited for a ticked addition refused when either version declares the other incompatible); both fail, and nothing else, when `checkUpdate` is given an empty batch (hand mutation, below) |
| H9 (RW-4) | b8ae3cf4 | `StagedChanges.pairUpdates`: a disable change without a mod id and with a group takes the mod id of the single enable in its group; used by `StagedChanges.of` (the staged path, incl. the legacy import's leftover ops) and `LegacyImport.applied`. | `LegacyImportRealUpdatesTest` (the real 0.1.0 last-apply.json + the seed's pending.json, old jars gone: UPDATED rows entityculling, modmenu, YACL, zoomify, distanthorizons, no DISABLED row, 12 rows; red first: only entityculling paired; a group with two enables keeps the disable without an id) |
| H10 (2V, ws-g3 L6) | ddb47719 | `HistoryScreen.failureText`: a staged change failed at `attempt >= MAX_ATTEMPTS` (only a half-applied group gets there without being abandoned) → `rigtune.history.failed_held`. | `HistoryHeldGroupTest` (a failed-rollback DH group through `HistoryModel.build` + `ApplyFailures.byOpId`: the new line, never "try 3 of 3"; red first; under the cap: "try 2 of 3") |
| H11 (2V, ws-g2) | e87c4f11 | `PreviewPlanner.withDisableRefusals(files → file name → why)`: all of one Apply's direct-child Disable items asked together, a refused one under "Not changed" (REFUSED, DisableGuard's text) and not folded as a staged disable. `RefusedDisables.previewRefusals(pendingFile, modsDir)` (DisableGuard.refusals over `ModsFolder.current`; none on an error, as Apply then disables) from `RealController.preview` (one line); `RefusedDisables.afterApply`: "%s mod(s) not disabled: another mod needs them, or another change of them is staged." (`rigtune.status.disables_refused`). | `PreviewDisablesTest` (+2), `RefusedDisablesTest` (2); `V05HooksTest`'s stub check still holds (nothing refused adds nothing) |

**Code review** (a code-reviewer subagent on origin/feat/v0.5.0..HEAD; findings through the coordinator, 0 H, 1 M, 6 L,
2 nits, all decided "fix"), fixed in 127c7ca9:
- M1: a read jar that nests others widened `provides` and silently left a nested-library range unjudged while
  `downloadsChecked` stayed true. Now `DryRunPlanner.Planned.complete` is false when a read jar's `depends`/`breaks` names
  an id in `nestedOrProvided` while some jar's nesting isn't known (it nests jars, or wasn't read); the preview is then
  unchecked and the disclosure line shows (`aDownloadThatNestsJarsIsNotRefusedOverALibraryItMayCarry` now asserts it;
  new `aNestingDownloadWhoseRangesNameNoNestedIdIsChecked`).
- L2: an end record counting fewer entries than the central directory holds (a wrapped 16-bit count) fails the read
  instead of reporting fabric.mod.json missing (`aWrappedEntryCountIsAFailureNotAMissingEntry`).
- L3: `Staging.dropStale` drops nothing when the mods folder can't be listed (`anUnlistableModsFolderDropsNothing`).
- L4: `DependencyResolver.disabledProjects()` leaves out every project a staged enable brings
  (`aProjectAStagedEnableBringsIsNeverTurnedOff`).
- L5: `RefusedDisables.afterApply` counts only items DisableGuard checked (a jar directly in the mods folder; the folder
  asked only when an item wasn't let through).
- L6: `rigtune.status.discarded_with_kept` says "changes already under way are finished or undone at the next restart".
- L7 (the coordinator's decision: no new history.json field): an ABANDONED change without a helper result reads "Not
  applied: dropped before it could run (for example, the mod was installed another way)"
  (`rigtune.history.not_applied_dropped`; `anAbandonedChangeWithoutAHelperResultSaysItWasDropped`).
- Test added: a correctly sized body under a wrong Content-Range (`aCorrectlySizedBodyWithAWrongContentRangeFails`).
- Nit "ProfileService.java:566 passes downloadsChecked through": that line rebuilds a profile preview with the 7-argument
  constructor, so it answers `downloadsChecked` false (the disclosure shows if a profile preview ever listed a download;
  profile switches are settings only). WS-P's file: reported to the coordinator, not changed here.
- Nit "StaleGroups.FOUND cleared at the start of drop()": not done, and said so to the coordinator: two rebuilds at launch
  can run their worker tasks at once, and a later drop clearing the map would lose the first drop's status line (the
  launch is exactly when RW-3's line matters); RealController calls `status` for every non-empty drop, so the map doesn't
  pile up. The reason is in the field's comment.

Hand mutations (the "test first" evidence for items whose rule already existed): removing the half-done filter from
`Staging.dropQueuedUpdates` fails `aHalfDoneGroupWithAQueuedBuildStaysWhileOthersAreDropped`; giving `checkUpdate` an
empty batch fails exactly the two H8 tests. Both restored from a copy, never committed.

## Deviations, residuals, UNVERIFIED
- **Files outside the PLAN's WS-H list** (APPROVED by the coordinator as ownership additions, for the PLAN's Amendments): `core/preview/{DownloadInputs,
  PreviewDownloads, ApplyPreview}` (L5's own preview plumbing: one optional component each, old constructors kept),
  `RealController.preview` (two expressions: `.withJarChecks(PreviewJarChecks.of(...))`, `.withDisableRefusals(...)`),
  `PreviewScreen.populate` (the disclosure row moved into `downloadChecks`, so it now comes after the "Modrinth is off"
  note), FakeModrinth's marked `ranged` method (+ its call and two imports), the `rigtune.download.needs_disabled` key
  (L9's refusal lives in the `rigtune.download.*` refusal family, which nobody else edits). New files: `RangeReader`,
  `FabricModJson`, `StaleOps`, `client/probe/PreviewJarChecks` and the tests.
- **Nested jars aren't read** (L5): a read covers the outer fabric.mod.json only. A declaration whose judgement depends on
  what a download nests is left to Apply (the conservative `provides`, above). So a download can still pass Preview and be
  refused by Apply on a nested library's range; the disclosure line isn't shown then (every listed download was read).
  Since the review (M1) the disclosure line then shows whenever such a range was left unjudged. Apply stays
  authoritative; README's known-limits text below says so.
- **Preview doesn't verify the SHA-512** (Apply does, on the whole file); a CDN answering a different file than the
  Modrinth metadata names would be judged on that file. The CDN files are immutable.
- **A range past the end is a 500 on cdn.modrinth.com, a 416 on FakeModrinth**: both are a failed read; the reader never
  asks one (it computes ranges from the total the first answer names).
- **RW-3's ABANDONED rows carry no specific reason in History**: History's reasons come from `last-apply.json`, which
  RigTune doesn't write at launch, and history.json gets no new field (the coordinator's decision): the status line says
  why, History shows the fixed line of L7 above.
- **RW-3's preLaunch count vs the rebuild**: preLaunch judges an enable staged without a mod id (0.1.0-era, an undo of an
  unreadable jar) by its files only; the rebuild may read its jar's id (`Staging.modIdOf`) and drop it for being loaded
  elsewhere. Then preLaunch counts it once more than the rebuild drops (the leftover toast says "1 change not applied" and
  the next rebuild's status line says it was dropped). Rare: 0.1.0 staged mod ids (the real seed has them).
- **RW-3 compares file names**, not full paths, for "loaded from the op's own target": a jar of the same name loaded from
  outside the mods folder counts as the target (the group is kept; the helper decides at exit): the safe direction.
- **RW-3 treats a jar any staged disable turns off** (not only the group's own) as going away, so a re-enable next to a
  separately staged disable of the loaded copy is kept (the helper's own duplicate check then decides, by group order).
- **L9 needs Modrinth's data for the disabled jar**: a disabled jar Modrinth doesn't know (not found by SHA-1 among the
  installed versions) can't be a Modrinth dependency and is ignored; `fabric.mod.json` `depends` on such a mod stay Apply's
  VersionPins' (which still counts the mod as present: unchanged).
- **2V's Apply count** counts every selected "Disable" item DisableGuard didn't allow; one outside the mods folder (never
  offered: the recommender gives no file to such a jar) would be counted too.
- `RealController.stagedChanged()` (WS-L2's hook): RW-3 doesn't need it (WS-K's stale-group dispatch already recounts
  the staged recommendations after a drop), so WS-H doesn't add it.
- Local Windows only: `StutterServiceTest.aFinishedBenchmarkReportsItsDhWorldGenCpu` failed once in a full local
  `:26.2:test` ("Failed to delete temp directory", a Windows file lock; WS-S's test, green in CI). Not debugged (PLAN "Local
  runs").
- UNVERIFIED: the L5 read against cdn.modrinth.com inside a running game (the live check ran the same class from a unit
  test on this PC; the game path is proven against FakeModrinth in CI). AC2H.6 (the seeded E2E `v010-dh-app-reinstalled`)
  is WS-E's release-tier scenario; it closes there.

## Docs (for the docs workstream)
- README privacy text: "With Modrinth on, Preview reads the start of each listed download's metadata (fabric.mod.json)
  from Modrinth's CDN (cdn.modrinth.com only, a few hundred KiB at most per file, nothing saved), to show what Apply would
  refuse. Nothing is downloaded until you press Apply."
- README known limits: "Preview checks a download's own fabric.mod.json against your installed mods as Apply does; a
  requirement of a library nested inside a download is only checked by Apply, and when Preview couldn't read a file (Modrinth
  off, the CDN or a network problem) it says each download is checked again when it arrives."
- CHANGELOG [0.5.0] Fixed: Preview shows a download Apply would refuse over another mod's version range, with Apply's own
  reason (L5); Discard pending says when it kept a change already under way (L7); an addition or update that needs a mod
  being turned off at the next restart is refused (L9); RigTune drops a pending change that can never run (its download
  gone, or the mod installed another way) at launch, instead of saying it'll be retried (RW-3); History pairs 0.1.0's
  updates as "Updated <mod>" (RW-4); a change the helper left half done no longer repeats "try 3 of 3" (2V); a refused
  "Disable" shows in Preview and in Apply's status (2V).
- DESIGN.md "Preview (0.3)": replace the download-time limit with the L5 paragraph (RangeReader: tail, central directory,
  entry; caps; allowed origin; Modrinth on; the conservative nesting rule; the disclosure as fallback). "Apply pipeline":
  the stale-group drop at rebuild (StaleOps) next to `dropQueuedUpdates`; "What earlier Applies staged": staged disables
  now count as absent for dependencies (L9), not for incompatibilities (L10). "History screen": the held-group wording.

## Footprint deltas
Against ws-k.md's per-leg baseline (run 36310249248), from this branch's final CI run 36335739716 (head d69afd68, which
also carries everything feat/v0.5.0 merged meanwhile: WS-L1 m1, WS-S early, WS-P2 and WS-T cores, WS-R, WS-P, the harness
deadlock fix):

| leg | renderThreadInitCpuMs | clientStartedWallMs | workerCpuMs5s | tickHookOnVsReference | v05RenderThreadResolve |
|---|---|---|---|---|---|
| 26.2 OpenGL | 110.7 (+28.5) | 52.6 (+16.2) | 187.9 (+52.4) | 1.447 (−0.034) | null |
| 26.3 OpenGL | 82.4 (+0.2) | 34.7 (+7.7) | 140.9 (−12.3) | 1.340 (−0.406) | null |
| 26.3 Vulkan | 113.7 (−6.3) | 40.8 (+0.9) | 198.7 (−2.0) | 1.605 (+0.070) | null |

Earlier runs of this branch, same order of legs (renderThreadInitCpuMs / clientStartedWallMs / workerCpuMs5s / tick ratio):
36322609327 (L5 only): 89.5 / 33.3 / 150.2 / 1.526, 109.6 / 44.6 / 169.3 / 1.496, 103.0 / 40.7 / 199.8 / 1.537;
36329157419: 110.8 / 41.2 / 181.0 / 1.558, 78.6 / 44.4 / 146.0 / 1.441, 109.8 / 41.3 / 171.7 / 1.574. Mixed signs within
ws-k.md's recorded runner spread (26.2 renderThreadInitCpuMs 63.5-112.9 on near-identical code); every value inside its
budget. WS-H adds no init work: its preLaunch code runs only when `pending.json` exists (never at a game test's launch:
the run folder is fresh), the L5 classes load on the first preview, and nothing new ticks.

## CI runs and screenshots looked at
- 36322609327 (H1-H3 + a merge): 8/8 jobs green. Downloaded `gametest-screenshots-26.2-OpenGL`, `-26.3-OpenGL`,
  `-26.3-Vulkan`; looked at `preview-l5-checked` (26.3 Vulkan: Fabric API listed, Mod Menu 21.0.0 refused with the
  pin's line, no disclosure), `preview-l5-modrinth-off` (26.3 OpenGL: the update listed, the unresolved addition, both
  notes), `preview-real` (26.2). Footprint JSON of all three legs.
- 36327062537 (H4-H8): `java` red: `V05HooksTest.theStubsAreIdentityAndNoOps` (the contracts' stub check called
  `StaleGroups.drop(null, …)`, which now reached FabricLoader); fixed in 4c8a27f0 (nothing is asked while nothing is
  staged). The game-test legs were green.
- 36329157419 (H4-H11 + a merge): 8/8 jobs green; 2205 unit tests on each node (3 skipped, 0 failed; `test-reports`).
  Looked at `preview-l5-checked` (26.2: Mod Menu 20.0.2), `preview-1280x720-scale2` (the canned preview: the disclosure now
  after the "Modrinth is off" note, fits), and the footprint JSON of the three legs.
- 36333683606 (the RW-3 warnOnce fix + ws-h.md): 8/8 green.
- 36335739716 (the review fixes + merges of WS-R and WS-P; head d69afd68, the final code head): 8/8 green; 2262 unit tests
  on each node (3 skipped, 0 failed). Looked at `preview-l5-modrinth-off` and `preview-l5-checked` (26.3 Vulkan) and the
  footprint JSON of the three legs.
- Local: `:26.2:runProductionClientGameTest -PgametestClasses=PreviewGameTest` under the game-test lock (2026-09-27
  23:18): green; its `preview-l5-*` screenshots looked at.

## AC table

| AC | status | evidence |
|---|---|---|
| AC2H.1 (Range reader over fixture jars; right bytes or a fallback; never over the caps; nothing on disk) | verified | `RangeReaderTest` (16), `FabricModJsonTest`; CI 36335739716 java job, both nodes |
| AC2H.1b (Apply's refusal line in Preview, a clean one without; the disclosure with Modrinth off / Range refused whenever downloads are listed, never otherwise; game-dir hash unchanged; H2 cases unchanged) | verified | `PreviewDownloadChecksTest`, `PreviewDownloadsNoteTest`, `DownloadPlannerTest` H2 cases unchanged; PreviewGameTest `downloadChecks` + `realPreviewWritesNothing` on 3 legs (36335739716) and locally; screenshots above |
| AC2H.1c (live Range check recorded; requests only to the allowed origin) | verified | "Code-deciding check" and "H1 live run" above (2026-09-27); `RangeReaderTest.anotherOriginIsNeverAsked` |
| AC2H.2 (L7: the kept group reported; the message differs; a clean discard keeps today's) | verified | `StagingTest.aDiscardThatKeepsAHalfDoneGroupSaysSo`, `aCleanDiscardKeepsTodaysMessage` |
| AC2H.4 (L9: an addition requiring a mod staged for disabling, pending.json and the same batch, refused with the message; L10 unchanged) | verified | `DownloadPlannerTest` L9 cases (red first), `PreviewDisablesTest` |
| AC2H.5 (RW-3: both triggers, the half-done group kept, unrelated groups kept; preLaunch counts only runnable ops) | verified | `StaleOpsTest`, `StagingTest` RW-3 cases, `StaleGroupsTest`, `PreLaunchStaleOpsTest` |
| AC2H.6 (the seeded E2E `v010-dh-app-reinstalled`) | closes in WS-E (release tier) | WS-H's side: the drop, its status line and the History rows are unit-tested on the same instance shape |
| AC2H.7 (RW-4: four Updated rows instead of eight on the real legacy import) | verified | `LegacyImportRealUpdatesTest` (red first) |
| AC2V.1 (the held line, never "try 3 of 3") | verified | `HistoryHeldGroupTest` (red first) |
| AC2V.3 (Preview lists a refused Disable under Not changed; Apply's status counts it) | verified | `PreviewDisablesTest` (refusal cases), `RefusedDisablesTest` |
| AC2V.4 (a half-done group with a queued build stays; others dropped) | verified (rule from 0.4) | `StagingTest.aHalfDoneGroupWithAQueuedBuildStaysWhileOthersAreDropped`; hand mutation |
| AC2V.5 (checkUpdate's batch branch alone) | verified | `DownloadPlannerTest` (2); hand mutation |
