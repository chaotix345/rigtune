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
(filled in per task)

## Deviations, residuals, UNVERIFIED
(filled in)

## Docs (for the docs workstream)
(filled in)

## Footprint deltas
(filled in from this branch's CI run against ws-k.md's per-leg baseline)

## CI runs and screenshots looked at
(filled in)

## AC table
(filled in)
