# Undo after restart E2E: dev-undo-after-restart-040-profiles

- Verdict: **FAIL**
- Run: 20260926T042750Z UTC, MC 26.2, rigtune-0.4.0-dev+mc26.2.jar + fabric-api-0.161.0+26.2.jar + e2e-disable-me-1.0.0.jar in a fresh scratch instance
- RigTune: `rigtune-0.4.0-dev+mc26.2.jar` version 0.4.0-dev+mc26.2, sha256 `2083302f49794dc3b854f53999d1f58aaa5002b85bfaa68e3b8ef972943e1c57`
- Added from the fake Modrinth: `e2e-added-1.0.0.jar` (project E2EAddMd); disabled: `e2e-disable-me-1.0.0.jar`
- B-M3, same instance: one Apply adds `e2e-first-1.0.0.jar` (project E2EFrst1), a second Apply adds `e2e-second-1.0.0.jar` (E2EScnd1); Undo this on the older one (entry f7270d15-4330-43cf-9eca-a93fe8e4cbf7; through the undo screen: True; controller method: RigTuneController.undoPlanFor)
- Profiles (`--profile-switch settings`, plan review P-H1), on an instance of its own with `sodium-mc26.2-0.9.2-fabric.jar`: two switches in one start (`stand-in A` {'vanilla.renderDistance': '6', 'vanilla.maxFps': '90', 'sodium.performance.chunk_builder_threads': '2', 'sodium.performance.use_fog_occlusion': 'false'}; `stand-in B` {'vanilla.renderDistance': '10', 'vanilla.maxFps': '60', 'sodium.performance.chunk_builder_threads': '4'}; entries ['132ec688-1308-48b1-b0a3-e459bf799010', '6c2ae673-d146-427c-be91-4486b8c6783f']), a restart, then Undo last twice, and Undo all on a copy of the instance from after the switches
- Known until SPEC amendment 2n merges (WS-A; AC2n.2): profile-undo is expected to FAIL. The second Undo last skips the Sodium key the first one staged ("You changed it since (it's now 4)": UndoPlanner compares with the file, not the pending staged value), so it ends at the first switch's value. (Line added to this file by hand after the run, as the harness now writes it.)
- Client time: mod-apply 30 s, mod-undo 23 s, mod-check 18 s, entry-apply 22 s, entry-undo 22 s, entry-check 18 s, profile-apply 26 s, profile-undo 27 s, profile-check None s, profile-undo-all 24 s, profile-check-all 18 s

## After RigTune applied {add e2e-added from Modrinth, disable e2e-disable-me} and quit (helper done)

| check | result | detail |
|---|---|---|
| the client exited normally and the helper finished | PASS | gradle exit 0, helper finished: True |
| the driver applied the mod changes | PASS | error: None |
| the added mod is in mods (the served bytes) | PASS | e2e-added-1.0.0.jar exists: True |
| the other mod is disabled | PASS | e2e-disable-me-1.0.0.jar.disabled exists: True |
| no pending.json, no leftover downloads | PASS | pending.json exists: False; *.rigtune-pending: [] |
| last-apply.json: both ops OK | PASS | results: [('DISABLE_FILE', 'e2e-disable-me-1.0.0.jar', 'OK'), ('ENABLE_FILE', 'e2e-added-1.0.0.jar', 'OK')] |
| history.json: one apply entry, both changes APPLIED | PASS | apply entries: 1; changes: [('file', 'disable', 'e2e-disable-me-1.0.0.jar', 'APPLIED'), ('file', 'enable', 'e2e-added-1.0.0.jar', 'APPLIED')] |

## After Undo last apply and a restart (helper done)

| check | result | detail |
|---|---|---|
| the client exited normally and the helper finished | PASS | gradle exit 0, helper finished: True |
| the driver undid the last apply (two reverts after a restart) | PASS | error: None; undoOf: 393d685d-3530-49d7-b1a1-e92bc00ff272; plan: [{'description': 'Disable e2e-added-1.0.0.jar', 'action': 'REVERT', 'reason': None, 'needsRestart': True, 'changeIds': ['c02e3f69-8ce7-4518-9281-081c38eb8b71'], 'opIds': []}, {'description': 'Re-enable e2e-disable-me-1.0.0.jar', 'action': 'REVERT', 'reason': None, 'needsRestart': True, 'changeIds': ['c6b8bb66-176e-4eb8-8e8f-dc491b58d1fb'], 'opIds': []}] |
| the added mod is disabled again | PASS | e2e-added-1.0.0.jar.disabled exists: True |
| the other mod is back | PASS | e2e-disable-me-1.0.0.jar exists: True |
| no pending.json, no leftover downloads | PASS | pending.json exists: False; *.rigtune-pending: [] |
| last-apply.json: both reversal ops OK | PASS | results: [('DISABLE_FILE', 'e2e-added-1.0.0.jar', 'OK'), ('ENABLE_FILE', 'e2e-disable-me-1.0.0.jar', 'OK')] |
| history.json: the undo APPLIED, the apply's changes REVERTED | PASS | apply changes: {'c6b8bb66-176e-4eb8-8e8f-dc491b58d1fb': 'REVERTED', 'c02e3f69-8ce7-4518-9281-081c38eb8b71': 'REVERTED'}; undo entries: ['393d685d-3530-49d7-b1a1-e92bc00ff272']; undo changes: [('disable', 'e2e-added-1.0.0.jar', 'APPLIED', 'c02e3f69-8ce7-4518-9281-081c38eb8b71'), ('enable', 'e2e-disable-me-1.0.0.jar', 'APPLIED', 'c6b8bb66-176e-4eb8-8e8f-dc491b58d1fb')] |

## After the next start

| check | result | detail |
|---|---|---|
| the client exited normally | PASS | gradle exit 0 |
| the driver checked | PASS | error: None |
| the undone mods are as before the apply | PASS | e2e-disable-me loaded: True; e2e-added loaded: False |
| nothing left to undo | PASS | undoable items: 0 |
| no crash report | PASS | crash-reports: [] |
| mods unchanged by the relaunch | PASS | unchanged |
| history.json statuses unchanged | PASS | unchanged |
| no pending.json, no leftover downloads | PASS | pending.json exists: False; *.rigtune-pending: [] |

## B-M3: after two Applies in one start, each adding a mod (e2e-first, then e2e-second), and quit (helper done)

| check | result | detail |
|---|---|---|
| the client exited normally and the helper finished | PASS | gradle exit 0, helper finished: True |
| the driver applied twice, one mod each | PASS | error: None; apply messages: ['Downloading 1 mod change(s)…', 'Downloading 1 mod change(s)…'] |
| both added mods are in mods (the served bytes) | PASS | served bytes in mods: {'e2e-first-1.0.0.jar': True, 'e2e-second-1.0.0.jar': True} |
| no pending.json, no leftover downloads | PASS | pending.json exists: False; *.rigtune-pending: [] |
| last-apply.json: both enables OK | PASS | results: [('ENABLE_FILE', 'e2e-first-1.0.0.jar', 'OK'), ('ENABLE_FILE', 'e2e-second-1.0.0.jar', 'OK')] |
| history.json: two new apply entries, the older adding e2e-first, both APPLIED | PASS | new entries: [('apply', '2026-09-26T04:29:29.108352700Z', [('file', 'enable', 'e2e-first-1.0.0.jar', 'APPLIED')]), ('apply', '2026-09-26T04:29:30.597558500Z', [('file', 'enable', 'e2e-second-1.0.0.jar', 'APPLIED')])] |

## B-M3: after Undo this on the older Apply (e2e-first) and a restart (helper done)

| check | result | detail |
|---|---|---|
| the client exited normally and the helper finished | PASS | gradle exit 0, helper finished: True |
| the driver undid the older Apply only (one revert after a restart) | PASS | error: None; undoOf: f7270d15-4330-43cf-9eca-a93fe8e4cbf7 (older f7270d15-4330-43cf-9eca-a93fe8e4cbf7); via the undo screen: True; plan: [{'description': 'Disable e2e-first-1.0.0.jar', 'action': 'REVERT', 'reason': None, 'needsRestart': True, 'changeIds': ['d135a053-9164-4402-9da6-a8ca9fc31c16'], 'opIds': []}] |
| the older Apply's mod is disabled | PASS | e2e-first-1.0.0.jar.disabled exists: True; e2e-first-1.0.0.jar exists: False |
| the newer Apply's mod is still enabled | PASS | e2e-second-1.0.0.jar exists: True |
| no pending.json, no leftover downloads | PASS | pending.json exists: False; *.rigtune-pending: [] |
| last-apply.json: the reversal op OK | PASS | results: [('DISABLE_FILE', 'e2e-first-1.0.0.jar', 'OK')] |
| history.json: one undo of the older Apply, its change REVERTED, the newer APPLIED | PASS | undo entries of these applies: ['f7270d15-4330-43cf-9eca-a93fe8e4cbf7']; undo changes: [('disable', 'e2e-first-1.0.0.jar', 'APPLIED', 'd135a053-9164-4402-9da6-a8ca9fc31c16')]; older: {'d135a053-9164-4402-9da6-a8ca9fc31c16': 'REVERTED'}; newer: {'44d8a0f0-e99b-47d0-aa8c-5c6b9c08a1d3': 'APPLIED'} |

## B-M3: after the next start

| check | result | detail |
|---|---|---|
| the client exited normally | PASS | gradle exit 0 |
| the driver checked | PASS | error: None |
| only the older Apply's mod is off | PASS | loaded: e2e-first False, e2e-second True, e2e-disable-me True |
| nothing left to undo on the older Apply | PASS | undoable items: 0; plan problem: None; method: RigTuneController.undoPlanFor |
| no crash report | PASS | crash-reports: [] |
| mods unchanged by the relaunch | PASS | unchanged |
| history.json statuses unchanged | PASS | unchanged |
| no pending.json, no leftover downloads | PASS | pending.json exists: False; *.rigtune-pending: [] |

## Profiles (P-H1): after two switches in one start and quit (helper done)

| check | result | detail |
|---|---|---|
| the client exited normally | PASS | gradle exit 0, helper finished: True |
| the driver switched 2 times | PASS | error: None; apply messages: ['Applied 2 setting(s). Restart Minecraft to finish applying 2 change(s).', 'Applied 2 setting(s). Restart Minecraft to finish applying 2 change(s).'] |
| history.json: two new apply entries of setting changes, applied (or discarded when replaced) | PASS | new entries: [('apply', [('vanilla.maxFps', '120', '90', 'APPLIED'), ('vanilla.renderDistance', '12', '6', 'APPLIED'), ('sodium.performance.chunk_builder_threads', '0', '2', 'APPLIED'), ('sodium.performance.use_fog_occlusion', 'true', 'false', 'APPLIED')]), ('apply', [('vanilla.maxFps', '90', '60', 'APPLIED'), ('vanilla.renderDistance', '6', '10', 'APPLIED'), ('sodium.performance.chunk_builder_threads', '2', '4', 'APPLIED')])] |
| history.json: each switch changed exactly its settings | PASS | changed: [{'vanilla.maxFps': '90', 'vanilla.renderDistance': '6', 'sodium.performance.chunk_builder_threads': '2', 'sodium.performance.use_fog_occlusion': 'false'}, {'vanilla.maxFps': '60', 'vanilla.renderDistance': '10', 'sodium.performance.chunk_builder_threads': '4'}]; expected [{'vanilla.renderDistance': '6', 'vanilla.maxFps': '90', 'sodium.performance.chunk_builder_threads': '2', 'sodium.performance.use_fog_occlusion': 'false'}, {'vanilla.renderDistance': '10', 'vanilla.maxFps': '60', 'sodium.performance.chunk_builder_threads': '4'}] |
| history.json: each key's applied changes run from its value before the first switch to the last | PASS | 4 key(s) |
| the settings hold the last switch's values | PASS | 4 value(s) |
| no pending.json, no leftover downloads | PASS | pending.json exists: False; *.rigtune-pending: [] |
| last-apply.json: every op OK | PASS | statuses not OK: [] |

## Profiles: after Undo last twice in the next start (helper done)

| check | result | detail |
|---|---|---|
| the client exited normally | PASS | gradle exit 0, helper finished: True |
| the driver undid the newer switch, then the older (Undo last twice) | PASS | error: None; plans (undoOf, items, problem): [('6c2ae673-d146-427c-be91-4486b8c6783f', 3, None), ('132ec688-1308-48b1-b0a3-e459bf799010', 3, None)]; expected ['6c2ae673-d146-427c-be91-4486b8c6783f', '132ec688-1308-48b1-b0a3-e459bf799010'] |
| history.json: the switches' changes REVERTED by undo entries, all applied | **FAIL** | switch changes: [('vanilla.maxFps', 'REVERTED'), ('vanilla.renderDistance', 'REVERTED'), ('sodium.performance.chunk_builder_threads', 'APPLIED'), ('sodium.performance.use_fog_occlusion', 'REVERTED'), ('vanilla.maxFps', 'REVERTED'), ('vanilla.renderDistance', 'REVERTED'), ('sodium.performance.chunk_builder_threads', 'REVERTED')]; undo entries: ['6c2ae673-d146-427c-be91-4486b8c6783f', '132ec688-1308-48b1-b0a3-e459bf799010']; undo changes: [('vanilla.maxFps', '90', 'APPLIED', '999bb192-e4af-4636-afce-f2db51453a04'), ('vanilla.renderDistance', '6', 'APPLIED', '445c032e-d091-4e18-aa14-3f3a9fd4ff8e'), ('sodium.performance.chunk_builder_threads', '2', 'APPLIED', '3d7a9359-bb97-4dbf-9304-bec22f6d1840'), ('vanilla.maxFps', '120', 'APPLIED', '79590e96-20d3-44ce-ac6a-98342691302b'), ('vanilla.renderDistance', '12', 'APPLIED', '276cec46-f4dd-4081-b636-3f6e6a7f283f'), ('sodium.performance.use_fog_occlusion', 'true', 'APPLIED', '98c4288c-9a3d-429b-a9ad-5f25bbbb34c1')] |
| every key is back at its value before the first switch | **FAIL** | (now, before the switches) where they differ: {'sodium.performance.chunk_builder_threads': ('2', '0')} |
| no pending.json, no leftover downloads | PASS | pending.json exists: False; *.rigtune-pending: [] |
| last-apply.json: every op OK | PASS | statuses not OK: [] |

## Profiles: after the next start

| check | result | detail |
|---|---|---|
| (not run) | | |

## Profiles, on a copy from after the switches: after Undo all (helper done)

| check | result | detail |
|---|---|---|
| the client exited normally | PASS | gradle exit 0, helper finished: True |
| the driver undid everything (Undo all) | PASS | error: None; plans (undoOf, items, problem): [('all', 4, None)]; expected ['all'] |
| history.json: the switches' changes REVERTED by undo entries, all applied | PASS | switch changes: [('vanilla.maxFps', 'REVERTED'), ('vanilla.renderDistance', 'REVERTED'), ('sodium.performance.chunk_builder_threads', 'REVERTED'), ('sodium.performance.use_fog_occlusion', 'REVERTED'), ('vanilla.maxFps', 'REVERTED'), ('vanilla.renderDistance', 'REVERTED'), ('sodium.performance.chunk_builder_threads', 'REVERTED')]; undo entries: ['all']; undo changes: [('vanilla.maxFps', '90', 'APPLIED', '999bb192-e4af-4636-afce-f2db51453a04'), ('vanilla.maxFps', '120', 'APPLIED', '79590e96-20d3-44ce-ac6a-98342691302b'), ('vanilla.renderDistance', '6', 'APPLIED', '445c032e-d091-4e18-aa14-3f3a9fd4ff8e'), ('vanilla.renderDistance', '12', 'APPLIED', '276cec46-f4dd-4081-b636-3f6e6a7f283f'), ('sodium.performance.chunk_builder_threads', '2', 'APPLIED', '3d7a9359-bb97-4dbf-9304-bec22f6d1840'), ('sodium.performance.chunk_builder_threads', '0', 'APPLIED', '964aca3d-8d81-478c-b349-093cbd2dbf6d'), ('sodium.performance.use_fog_occlusion', 'true', 'APPLIED', '98c4288c-9a3d-429b-a9ad-5f25bbbb34c1')] |
| every key is back at its value before the first switch | PASS | 4 key(s) |
| no pending.json, no leftover downloads | PASS | pending.json exists: False; *.rigtune-pending: [] |
| last-apply.json: every op OK | PASS | statuses not OK: [] |

## Profiles, on that copy: after the next start

| check | result | detail |
|---|---|---|
| the client exited normally | PASS | gradle exit 0, helper finished: True |
| the driver checked | PASS | error: None |
| the game runs with every key at its value before the first switch | PASS | 4 key(s) |
| nothing left to undo on either switch | PASS | undoable items: {'132ec688-1308-48b1-b0a3-e459bf799010': 0, '6c2ae673-d146-427c-be91-4486b8c6783f': 0}; plan problems: {'132ec688-1308-48b1-b0a3-e459bf799010': None, '6c2ae673-d146-427c-be91-4486b8c6783f': None} |
| no crash report | PASS | crash-reports: [] |
| mods unchanged by the relaunch | PASS | unchanged |
| history.json statuses unchanged | PASS | unchanged |
| no pending.json, no leftover downloads | PASS | pending.json exists: False; *.rigtune-pending: [] |

## Files

- `catalog.json`
- `checks.json`
- `driver-entry-apply.json`
- `driver-entry-check.json`
- `driver-entry-undo.json`
- `driver-mod-apply.json`
- `driver-mod-check.json`
- `driver-mod-undo.json`
- `driver-profile-apply.json`
- `driver-profile-check-all.json`
- `driver-profile-undo-all.json`
- `driver-profile-undo.json`
- `e2e-entry-apply-2-after.png`
- `e2e-entry-check-1-undo.png`
- `e2e-entry-undo-1-plan.png`
- `e2e-entry-undo-2-after.png`
- `e2e-mod-apply-1-report.png`
- `e2e-mod-apply-2-after.png`
- `e2e-mod-check-1-undo.png`
- `e2e-mod-undo-1-plan.png`
- `e2e-mod-undo-2-after.png`
- `e2e-profile-apply-1-history.png`
- `e2e-profile-apply-2-after.png`
- `e2e-profile-check-all-1-history.png`
- `e2e-profile-undo-1-plan.png`
- `e2e-profile-undo-2-after.png`
- `e2e-profile-undo-2-plan.png`
- `e2e-profile-undo-all-1-plan.png`
- `e2e-profile-undo-all-2-after.png`
- `e2e.log`
- `helper-after-entry-apply.log`
- `helper-after-entry-check.log`
- `helper-after-entry-undo.log`
- `helper-after-mod-apply.log`
- `helper-after-mod-check.log`
- `helper-after-mod-undo.log`
- `helper-after-profile-apply.log`
- `helper-after-profile-check-all.log`
- `helper-after-profile-undo-all.log`
- `helper-after-profile-undo.log`
- `helper-cmdlines-entry-apply.txt`
- `helper-cmdlines-entry-undo.txt`
- `helper-cmdlines-mod-apply.txt`
- `helper-cmdlines-mod-undo.txt`
- `helper-cmdlines-profile-apply.txt`
- `helper-cmdlines-profile-undo-all.txt`
- `helper-cmdlines-profile-undo.txt`
- `history-after-entry-apply.json`
- `history-after-entry-check.json`
- `history-after-entry-undo.json`
- `history-after-mod-apply.json`
- `history-after-mod-check.json`
- `history-after-mod-undo.json`
- `history-after-profile-apply.json`
- `history-after-profile-check-all.json`
- `history-after-profile-undo-all.json`
- `history-after-profile-undo.json`
- `last-apply-after-entry-apply.json`
- `last-apply-after-entry-check.json`
- `last-apply-after-entry-undo.json`
- `last-apply-after-mod-apply.json`
- `last-apply-after-mod-check.json`
- `last-apply-after-mod-undo.json`
- `last-apply-after-profile-apply.json`
- `last-apply-after-profile-check-all.json`
- `last-apply-after-profile-undo-all.json`
- `last-apply-after-profile-undo.json`
- `latest-entry-apply.filtered.log`
- `latest-entry-check.filtered.log`
- `latest-entry-undo.filtered.log`
- `latest-mod-apply.filtered.log`
- `latest-mod-check.filtered.log`
- `latest-mod-undo.filtered.log`
- `latest-profile-apply.filtered.log`
- `latest-profile-check-all.filtered.log`
- `latest-profile-undo-all.filtered.log`
- `latest-profile-undo.filtered.log`
- `mods-after-entry-apply.json`
- `mods-after-entry-check.json`
- `mods-after-entry-undo.json`
- `mods-after-mod-apply.json`
- `mods-after-mod-check.json`
- `mods-after-mod-undo.json`
- `mods-after-profile-apply.json`
- `mods-after-profile-check-all.json`
- `mods-after-profile-undo-all.json`
- `mods-after-profile-undo.json`
- `options-after-profile-apply.txt`
- `options-after-profile-check-all.txt`
- `options-after-profile-undo-all.txt`
- `options-after-profile-undo.txt`
- `pending-entry-apply.json`
- `pending-entry-undo.json`
- `pending-mod-apply.json`
- `pending-mod-undo.json`
- `profile-plan.json`
- `redirect-probe.txt`
- `requests.jsonl`
- `sodium-options-after-profile-apply.json`
- `sodium-options-after-profile-check-all.json`
- `sodium-options-after-profile-undo-all.json`
- `sodium-options-after-profile-undo.json`
