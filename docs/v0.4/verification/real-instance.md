# AC2i.1: read-only check of the real instance (Phase 5)

Checked 2026-09-26 ~20:50 AEST by P5-B. Only read access; nothing under `%APPDATA%/ModrinthApp` was written, renamed or deleted. The mods folder and 3 config files were copied into the verifier's scratch dir for the 26.2 smoke.

## Has the user played since 2026-09-25 09:08? **No.**

- `logs/latest.log` spans one session, `[09:03:25] Loading Minecraft 26.2 with Fabric Loader 0.19.5` (163 mods) to `[09:08:59] DeleteOnUnlock …`, last written 2026-09-25 09:08:59.78 +10:00. The newest rotated log is `2026-09-24-1.log.gz` (written 2026-09-25 09:03:25, at the start of that session). `launcher_log.txt` was last written 09:09:00 and ends `# Process exited with status: exit code: 0`.
- `find <instance> -maxdepth 3 -newermt "2026-09-25 09:10:00"` finds nothing. The newest files are RigTune's helper output at 09:09:04 (`config/rigtune/pending.json`, `last-apply.json`, `helper.log`) and `mods/` (09:09:04).
- RigTune is still **0.1.0**: `mods/rigtune-0.1.0.jar` sha256 `8294d04a…4b950`, the released jar. Its helper copies are `config/rigtune/helper/0-rigtune-0.1.0.jar` and `1-gson-2.14.0.jar`. There's no `history.json` and no v0.2+ file, so no newer RigTune has ever run here. Modrinth still has the project in review, so 0.1.0 isn't offered an update in the wild.

## State vs the seeded E2E predictions

The seed `tools/e2e/seeds/v010-dh/` was read from this instance on 2026-09-25 18:27 UTC. Today's files are **byte-identical** to it:

| file | sha256 now | seed `source.json` |
|---|---|---|
| `config/rigtune/pending.json` (1058 B) | `5e963d6f…dbc12d` | same |
| `config/rigtune/last-apply.json` (9728 B) | `9b55af75…782d83` | same |

- **RigTune's 0.1.0 DH group is still pending**. It holds `DISABLE_FILE fabric-26.2.jar` (op `041919d9…`) and `ENABLE_FILE DistantHorizons-3.3.2-26.2-fabric-neoforge.jar.rigtune-pending` (op `db7f487d…`, modId `distanthorizons`), both `attempts: 1`. `helper.log` shows the 0.1.0 helper's run at 2026-09-24T23:09:04Z: 15 ops OK, then `FAILED DISABLE_FILE: Gave up after 10 attempt(s): … fabric-26.2.jar -> fabric-26.2.jar.disabled: The process cannot access the file because it is being used by another process`, and the enable was skipped as dependent. The download `DistantHorizons-3.3.2-26.2-fabric-neoforge.jar.rigtune-pending` (27,702,766 B) is still in `mods/`.
- **DH's own update is still queued**. latest.log has `Attempting to auto update Distant Horizons` (09:03:53) and `Distant Horizons successfully updated. It will apply on game's relaunch` (09:03:56). At exit it logged `DeleteOnUnlock running, old jar file at […\mods\fabric-26.2.jar] should be deleted after Minecraft's JVM shutdown has completed.` (09:08:59). The old jar was never deleted: `mods/fabric-26.2.jar` (DH 3.3.0, 27,710,690 B, mtime 2026-09-18) is still there. DH's 3.3.2 build is `mods/update/DistantHorizons-3.3.2 - 26.2 neo/fabric-26.2.jar` (27,702,766 B, 09:03:56).
- Because nothing ran, **none of the seeded predictions can be compared with reality yet**. The E2E runs predict:
  - **The next launch on 0.1.0**, as seeded in dev-v010-seeded-to-040: the 0.1.0 helper retries the group at exit (`attempts` 2). Whether it succeeds depends on whether DH's DeleteOnUnlock or DH's own update handling holds `fabric-26.2.jar` again. In the harness the jar was held open, so the retry fails.
  - **The first 0.4.0 start** after a RigTune self-update ([dev-v010-seeded-to-040](../../smoke/self-update/dev-v010-seeded-to-040/RESULT.md): PASS). The carried-over group is dropped with "Cancelled RigTune's pending change to Distant Horizons: it has an update of its own waiting in mods/update.". The legacy import marks both ops `DISCARDED`, and one WARN line appears per failed op ("restart attempt 2 of 3"). No helper runs at exit, the installed and queued DH jars stay untouched, and the only change to `mods/` is the download renamed to `.rigtune-superseded`.
- Other facts from the log: the hardware line reads `AMD Ryzen 7 7800X3D … RX 7800 XT, driverVersion=3.3.0 Core Profile Context 26.8.1.260810, OPENGL, vramMb=16368, totalRamMb=31849, maxHeapMb=6144, 2560x1440 @ 180`, with the flag `sodium-workaround:AMD_GAME_OPTIMIZATION_BROKEN`. The launcher is the Modrinth App (`com.modrinth.theseus.MinecraftLaunch`), and `rigtune.json` is `{"goal": "BALANCED"}`.

## Verdict

AC2i.1 **done**: the read-only check was made and nothing is new. The in-the-wild comparison waits on the user playing (and on Modrinth approval for the self-update path). The next read, in Phase 7, should look for:
1. A newer latest.log, and its `RigTune's helper` lines (0.1.0 wording).
2. Whether `fabric-26.2.jar` became `.disabled` or was deleted by DH.
3. Whether `mods/update/` was consumed.
4. `pending.json` `attempts`.
