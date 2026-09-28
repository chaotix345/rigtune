# `2026-09-28/`: the same instance, re-read after 0.4.0's evening session (RW-20)

The user's Modrinth App instance (Fabric, MC 26.2), copied read-only on 2026-09-28 for the re-read
`real-world-2026-09-28.md` (RW-17..RW-20; its row RW-20 and the SPEC amendment "real world RW-20", 4f). Same rules as
this folder's top README (WS-L1's): file names only (`${INSTANCE}/...`), contents as recorded.

| file | what it is | source |
|---|---|---|
| `rigtune/history.json` | the legacy-import entry: 0.1.0's apply, 17 file changes, all APPLIED; the DH pair (disable `fabric-26.2.jar`, enable `DistantHorizons-3.3.2-26.2-fabric-neoforge.jar`) only because 0.4.0's helper reported both `SKIPPED_ALREADY_DONE` (RW-1) | copy (file names only already); unchanged since 2026-09-27 11:08:48 local |
| `rigtune/last-apply.json` | that helper run (2026-09-27 01:08:48 UTC): both DH ops `SKIPPED_ALREADY_DONE`, no `resultPath` | copy, paths templated |

The instance has no pending.json and no unfinished-groups.json. Templated by WS-H's `make_rw20_fixtures.py` (scratch
folder), which refuses to write while any machine path or name is left. The contents equal the 2026-09-27 capture's
`rigtune/history.json` and `rigtune/last-apply.json` (nothing moved in between). Used by `SkippedClaimsTest`,
`HistoryStartupRw20Test` and `V050WrittenWsHTest` (RW-20: the DH pair becomes ABANDONED "installed another way").
