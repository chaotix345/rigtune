# Self-update end-to-end runs

The harness is `tools/e2e/` (how it works: tools/e2e/README.md). Each run makes a fresh scratch instance (fabric-api
0.161.0+26.2 and the old RigTune jar in `mods/`), redirects `api.modrinth.com`, `cdn.modrinth.com` and
`raw.githubusercontent.com` to a local fake Modrinth with JVM properties only, and drives a production 26.2 client
twice: the old version applies its "Update RigTune" recommendation and quits, the post-exit helper swaps the jars, then
the new version starts on the same instance. Each folder's `RESULT.md` has every check with its detail.

## v0.3 (WS-H dry runs; Phase 5 repeats them on the release candidate as `final-*` and `undo-after-restart-030`)

Commands: tools/e2e/README.md "v0.3 runs". The `-merged` runs are the current ones: `rigtune-0.3.0-dev+mc26.2.jar`
(sha256 `dbb74674…05ff2`) built from test/e2e-v03 @ 4520cc9, which has feat/v0.3.0 @ 6321696 merged (WS-0, WS-A,
WS-B, WS-C, WS-D, WS-E, WS-F, WS-V), with the undo driver calling `undoPlanFor` and the entry `UndoScreen` directly. The
runs without a suffix are the earlier round on test/e2e-v03 @ ee7f8ea (WS-0, WS-A and WS-F only; jar `9015bd10…8c19`),
kept to show the state before WS-B.

| run | installed → update | result | date |
|---|---|---|---|
| [dev-v020-to-030-merged](dev-v020-to-030-merged/RESULT.md) | the released `rigtune-0.2.0+mc26.2.jar` (sha256 `67275e23…7de9`, the v0.2.0 GitHub release asset) → 0.3.0-dev; `--expect-history own-update` | PASS (20/20) | 2026-09-26 |
| [dev-v010-to-030-merged](dev-v010-to-030-merged/RESULT.md) | the released `rigtune-0.1.0.jar` (sha256 `8294d04a…b950`) → 0.3.0-dev; `--legacy-disable --expect-history` | PASS (21/21) | 2026-09-26 |
| [dev-v010-seeded-to-030-merged](dev-v010-seeded-to-030-merged/RESULT.md) | plan review H-M2: as above, seeded from the user's real 0.1.0 `pending.json`/`last-apply.json` (templated) with fake DH jars (`--seed tools/e2e/seeds/v010-dh`) | PASS (26/26) | 2026-09-26 |
| [dev-undo-after-restart-030-merged](dev-undo-after-restart-030-merged/RESULT.md) | M14 (Undo last after a restart), then plan review B-M3 on the same instance (two Applies, Undo this on the older, restart) | PASS (43/43) | 2026-09-26 |
| [dev-v020-to-030](dev-v020-to-030/RESULT.md) | earlier round, 0.2.0 → dev | PASS (20/20) | 2026-09-26 |
| [dev-v010-to-030](dev-v010-to-030/RESULT.md) | earlier round, 0.1.0 → dev | PASS (21/21) | 2026-09-26 |
| [dev-v010-seeded-to-030](dev-v010-seeded-to-030/RESULT.md) | earlier round, seeded | FAIL (25/26): no 3e WARN line (WS-B wasn't merged) | 2026-09-26 |
| [dev-undo-after-restart-030](dev-undo-after-restart-030/RESULT.md) | earlier round, undo | FAIL (31/35): M14 22/22, entry-apply 6/6; entry-undo 3/7 (no per-entry API before WS-B) | 2026-09-26 |

What the seeded run shows: the 0.1.0 helper retried the user's DH group while the fake DH jar was held open (as DH's own
updater held the real one) and failed it again (attempts 2), while the self-update itself applied. On the first
0.3.0-dev start WS-A's 3a (RigTune's update of a loaded mod with its own update queued) dropped the group with
"Cancelled RigTune's pending change to Distant Horizons: it has an update of its own waiting in mods/update.", the
legacy import journaled both changes and marked them `DISCARDED`, latest.log has WS-B's line for each failed op ("RigTune
could not apply a change at the last exit (attempt 2 of 3; it's retried at the next exit): DISABLE_FILE fabric-26.2.jar:
..." and the ENABLE_FILE of DistantHorizons-3.3.2), no helper ran at exit, `mods/` didn't change at exit, and during the
session the only change was RigTune's DH download becoming `.rigtune-superseded`.

What the undo run's per-entry part shows (B-M3): two Applies in one start are journaled as two `apply` entries; Undo
this on the older goes through `undoPlanFor` and the entry Undo screen's button, a plan of one revert needing a restart;
after the restart only `e2e-first` is disabled, the journal has one `undo` of the older entry (its change `REVERTED`),
the newer entry's change stays `APPLIED`, and `undoPlanFor` on the older entry then has nothing left.

## v0.2

| run | installed → update | result | date |
|---|---|---|---|
| [final-v010-to-020](final-v010-to-020/RESULT.md) | the released `rigtune-0.1.0.jar` (sha256 `8294d04a…`, unmodified) → the merged integration build (feat/v0.2.0 @ 5f57eee, `rigtune-0.2.0-dev+mc26.2.jar`); 0.1.0 also disabled a test mod in the same apply; `--expect-history` | PASS (21/21 checks) | 2026-09-25 (Phase 5, final) |
| [undo-after-restart](undo-after-restart/RESULT.md) | plan review M14 on the same integration build: one Apply adds a mod from the fake Modrinth and disables another → quit → helper → Undo last apply (the confirmation screen's button) → quit → helper → next start | PASS (22/22 checks) | 2026-09-25 (Phase 5) |
| [v010-to-dev](v010-to-dev/RESULT.md) | the released `rigtune-0.1.0.jar` (sha256 `8294d04a…`, unmodified) → `rigtune-0.2.0-dev+mc26.2.jar` built from `feat/self-update-e2e` | PASS (19/19 checks) | 2026-09-25 (Wave A) |
| [dev-to-dev](dev-to-dev/RESULT.md) | `rigtune-0.2.0-dev.1+mc26.2.jar` → `rigtune-0.2.0-dev.2+mc26.2.jar`, same sources (plan review M12: the 0.2 helper applies its own successor) | PASS (19/19 checks) | 2026-09-25 (Wave A) |
| [v010-offline-race](v010-offline-race/RESULT.md) | as v010-to-dev, an earlier run without the driver's Rescan fallback | FAIL: 0.1.0 made no Modrinth lookups (see below) | 2026-09-25 |
| [redirect-proof.txt](redirect-proof.txt) | no game: the JVM properties and the fake server, checked with a small Java program | OK | 2026-09-25 |

The kept runs are the last ones, on the branch's final code (Rescan fallback in, not needed in either run). Before
them, v0.1.0 → dev passed 4 times out of 5 and dev → dev 4 out of 4; the one failure is kept as `v010-offline-race`.
There, 0.1.0 fetched the rules but never called `/v2/version_files`, and its report stayed "Offline" for 180 s. The
cause is a startup race in RigTune's `RealController` (0.1.0, and 0.2 so far). The Modrinth lookups run when the
hardware/mod scan finishes, but only if the rules are already loaded; after that, only when the remote rules beat the
local ones. So if the scan finishes first and the remote rules have the same revision as the bundled or cached ones,
nothing looks anything up until the player presses Rescan. Reported to the coordinator for WS-A (`loadRules`). The
driver now presses Rescan once after 20 s offline, as a player would, and `RESULT.md` says whether it did.

What the v0.1.0 run shows (SPEC item 5, AC5.1, AC5.2):
- 0.1.0 matched its own jar by SHA-1 (`POST /v2/version_files` + `/v2/version_files/update`) and offered "Update RigTune"
  ("Version 0.2.0-dev+mc26.2 is available (you have 0.1.0)", ticked): `e2e-update-1-report.png`, `report-update.txt`.
- It downloaded the jar from `cdn.modrinth.com` with its own User-Agent, verified it, and staged {DISABLE_FILE
  `rigtune-0.1.0.jar`, ENABLE_FILE `rigtune-0.2.0-dev+mc26.2.jar`} in one group: `e2e-update-2-staged.png`,
  `captured-pending.json`, `requests.jsonl`.
- Quitting started the helper from `config/rigtune/helper/0-rigtune-0.1.0.jar` and `1-gson-2.14.0.jar`
  (`helper-cmdlines.txt`); it renamed the running version's own jar on Windows and enabled the new one, whose file name
  differs (`captured-helper.log`, `captured-last-apply.json`: both OK).
- After the helper: exactly one `rigtune*.jar` in mods (the new one, byte-identical to the served file),
  `rigtune-0.1.0.jar.disabled`, no pending.json, no leftover download.
- 0.2 then started from `mods/`, kept the goal QUALITY that 0.1.0 had saved, showed the apply toast ("RigTune applied 2
  change(s)", `e2e-verify-1-title.png`) and recorded it in `rigtune.json`, and offered no further update.

## Phase 5, on the merged integration build (feat/v0.2.0 @ 5f57eee)

`final-v010-to-020` repeats the v0.1.0 run against the integration jar. The 0.1.0 driver also disabled a test mod
(`e2e-legacy`) in the same apply as the update. 0.2's legacy import only records changes that aren't RigTune's own, so
a plain self-update leaves no `legacy-import` entry at all. 0.2 then wrote `history.json` with exactly one
`legacy-import` entry, holding that disable as `APPLIED` (with `resultFile` `e2e-legacy-1.0.0.jar.disabled` and
`modId` read from the jar), and nothing of RigTune's own jars (`history-after-verify.json`). The toast said "RigTune
applied 3 change(s)".

`undo-after-restart` (plan review M14), three real starts of 0.2 on one instance:
- Start 1: one Apply adds `e2e-added` (downloaded from the fake `cdn.modrinth.com` through DependencyResolver,
  DownloadPlanner and the SHA-512 check) and disables `e2e-disable-me`. The helper applies both, and `history.json` has
  one `apply` entry with both file changes `APPLIED`.
- Start 2: "Undo last apply". The plan has two reverts that need a restart; `e2e-mod-undo-1-plan.png` is the
  confirmation screen. The driver presses the screen's Undo button, which reports "Undone: 0 now, 2 after a restart, 0
  cancelled, 0 skipped" (`e2e-mod-undo-2-after.png`). The helper disables `e2e-added` and re-enables `e2e-disable-me`.
  `history.json` has one `undo` entry whose two changes are `APPLIED`, each `reverts` an apply change, and the apply's
  changes are `REVERTED`.
- Start 3: `e2e-disable-me` is loaded again and `e2e-added` isn't. The undo screen says "Nothing to undo."
  (`e2e-mod-check-1-undo.png`). The mods folder and the history statuses are unchanged.

The Add and Disable rows come from the driver rather than from the report: M14 is about the journal and the post-exit
undo pipeline across real restarts, not about which mods the rules suggest.
