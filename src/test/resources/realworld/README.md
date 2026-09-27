# Real-world fixtures (docs/v0.5/PLAN.md WS-L1, amendment PLAN-11)

Records from the user's own instance, templated so a test can use them without ever reading the live instance
(`%APPDATA%\ModrinthApp` is read-only for RigTune's agents and never read by a test). WS-L1 owns this folder and
`RealWorldUndoTest`; WS-L2 reads it (`RealWorldFixTest`, the helper's crash-replay cases, `LauncherRepairTest`), through
`core/history/RealWorldFixtures` in the test sources.

## Rules

- **File names only.** Every absolute path is written `${INSTANCE}/...` with `/` (the same token as
  `src/test/resources/v050-written/README.md`); a test puts its own temporary instance in (`RealWorldFixtures.text`). No
  user name, no machine path, no instance name. `RealWorldFixturesTest` fails on any of them.
- **As recorded.** Contents are the records' own (ids, times, messages), only the paths templated. Nothing is made up; what
  isn't a copy says so below.
- **One folder per capture**, named by its date. The generator is `make_realworld.py` in the WS-L1 scratch folder (read-only
  copies in, this folder out); it is not needed to use the fixtures.

## `2026-09-27/`: RigTune 0.1.0's apply, then 0.4.0's first start (docs/research/v0.5/real-world-2026-09-27.md)

The user's Modrinth App 0.21.5 instance (Fabric, MC 26.2) after RigTune 0.1.0 applied 7 additions, 4 updates and a staged
Distant Horizons update on 2026-09-24/25, the user repaired the app's list by hand, and RigTune 0.4.0's helper ran 0.1.0's
leftover DH group at the exit of 0.4.0's first session (2026-09-27 01:08 UTC). Copied read-only at 11:15 local time.

| file | what it is | source |
|---|---|---|
| `config/rigtune/history.json` | the legacy-import entry (0.1.0's apply, 17 file changes, all APPLIED after 0.4.0's helper run: the DH pair included, RW-1; the disable of `fabric-26.2.jar` has no `resultFile`, RW-14) | copy (file names only already) |
| `config/rigtune/last-apply.json` | 0.4.0's helper result: both DH ops `SKIPPED_ALREADY_DONE` | copy, paths templated |
| `config/rigtune/helper.log` | that helper run's log | copy, the plan's path templated |
| `seeded/pending-0.1.0.json` | 0.1.0's `pending.json` before that run (the DH group, attempt 1) | docs/smoke/self-update/final-v010-seeded-to-040/seeded-pending.json, `<instance>` templated |
| `mods-listing.txt` | the instance's `mods/` file names at the copy | copy |
| `mod-ids.json` | the mod id each jar RigTune's records name declares (its `fabric.mod.json`) | read from the jars by rw's `RealWorld20260927Test` |

What the tests find in it (AC4c.2, AC4c.3, AC4f.1, AC4g.1): under RIGTUNE, Undo would disable 7 added jars and swap Entity
Culling 1.11.2 back to 1.11.1 (the Mod Menu, YACL, Zoomify and DH groups are skipped: their old copies are gone); under
LAUNCHER (the Modrinth App) Undo stages no file op at all; RW-14 skips the DH group's disable (no `resultFile`), so rw's
variant D (the app's own `fabric-26.2.jar.disabled` in the folder) no longer swaps the app's DH 3.3.2 back.
