# Self-update end-to-end runs

The harness is `tools/e2e/` (how it works: tools/e2e/README.md). Each run makes a fresh scratch instance (fabric-api
0.161.0+26.2 and the old RigTune jar in `mods/`), redirects `api.modrinth.com`, `cdn.modrinth.com` and
`raw.githubusercontent.com` to a local fake Modrinth with JVM properties only, and drives a production 26.2 client
twice: the old version applies its "Update RigTune" recommendation and quits, the post-exit helper swaps the jars, then
the new version starts on the same instance. Each folder's `RESULT.md` has every check with its detail.

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
