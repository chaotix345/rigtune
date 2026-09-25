# Self-update end-to-end runs

The harness is `tools/e2e/` (how it works: tools/e2e/README.md). Each run makes a fresh scratch instance (fabric-api
0.161.0+26.2 and the old RigTune jar in `mods/`), redirects `api.modrinth.com`, `cdn.modrinth.com` and
`raw.githubusercontent.com` to a local fake Modrinth with JVM properties only, and drives a production 26.2 client
twice: the old version applies its "Update RigTune" recommendation and quits, the post-exit helper swaps the jars, then
the new version starts on the same instance. Each folder's `RESULT.md` has every check with its detail.

| run | installed → update | result | date |
|---|---|---|---|
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

Not covered yet (Phase 5, against the merged integration build): the `history.json` legacy import (`--expect-history`,
needs WS-B) and the end-to-end undo after a restart (plan review M14). Both runs used `0.2.0-dev` builds of this branch,
not the final integration jar.
