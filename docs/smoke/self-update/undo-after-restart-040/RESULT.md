# Undo after restart E2E: undo-after-restart-040

- Verdict: **PASS**
- Run: 20260926T160010Z UTC, MC 26.2, rigtune-0.4.0-dev+mc26.2.jar + fabric-api-0.161.0+26.2.jar + e2e-disable-me-1.0.0.jar in a fresh scratch instance
- RigTune: `rigtune-0.4.0-dev+mc26.2.jar` version 0.4.0-dev+mc26.2, sha256 `4018fe03ee2164724f144f77abc829b3b73ac25e941e38f5c3c6aeb8778a9a12`
- Added from the fake Modrinth: `e2e-added-1.0.0.jar` (project E2EAddMd); disabled: `e2e-disable-me-1.0.0.jar`
- B-M3, same instance: one Apply adds `e2e-first-1.0.0.jar` (project E2EFrst1), a second Apply adds `e2e-second-1.0.0.jar` (E2EScnd1); Undo this on the older one (entry 8c725f7e-434d-4ca6-a1c6-4ad542027638; through the undo screen: True; controller method: RigTuneController.undoPlanFor)
- Profiles (`--profile-switch profile`, plan review P-H1), on an instance of its own with `sodium-mc26.2-0.9.2-fabric.jar`: two switches in one start (`Battery` ; `Max FPS` ; entries ['86bdf09f-2ab6-4569-85fa-d829df4b3a4e', 'cd6da796-896b-4539-b85d-19656642db86']), a restart, then Undo last twice, and Undo all on a copy of the instance from after the switches
- SPEC amendment 2n (AC2n.2) is merged: profile-undo (the second Undo last on a key the first one staged) must pass and counts in the verdict like every phase.
- Client time: mod-apply 23 s, mod-undo 23 s, mod-check 19 s, entry-apply 23 s, entry-undo 34 s, entry-check 19 s, profile-apply 25 s, profile-undo 26 s, profile-check 19 s, profile-undo-all 24 s, profile-check-all 19 s

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
| the driver undid the last apply (two reverts after a restart) | PASS | error: None; undoOf: 0b4a41f2-8c06-428e-8c2e-e5f7eb24ee2e; plan: [{'description': 'Disable e2e-added-1.0.0.jar', 'action': 'REVERT', 'reason': None, 'needsRestart': True, 'changeIds': ['313beaa8-e6ff-4cac-85ff-0b35aad3a215'], 'opIds': []}, {'description': 'Re-enable e2e-disable-me-1.0.0.jar', 'action': 'REVERT', 'reason': None, 'needsRestart': True, 'changeIds': ['652e98f8-0d45-4b44-b2c5-a49319c205b5'], 'opIds': []}] |
| the added mod is disabled again | PASS | e2e-added-1.0.0.jar.disabled exists: True |
| the other mod is back | PASS | e2e-disable-me-1.0.0.jar exists: True |
| no pending.json, no leftover downloads | PASS | pending.json exists: False; *.rigtune-pending: [] |
| last-apply.json: both reversal ops OK | PASS | results: [('DISABLE_FILE', 'e2e-added-1.0.0.jar', 'OK'), ('ENABLE_FILE', 'e2e-disable-me-1.0.0.jar', 'OK')] |
| history.json: the undo APPLIED, the apply's changes REVERTED | PASS | apply changes: {'652e98f8-0d45-4b44-b2c5-a49319c205b5': 'REVERTED', '313beaa8-e6ff-4cac-85ff-0b35aad3a215': 'REVERTED'}; undo entries: ['0b4a41f2-8c06-428e-8c2e-e5f7eb24ee2e']; undo changes: [('disable', 'e2e-added-1.0.0.jar', 'APPLIED', '313beaa8-e6ff-4cac-85ff-0b35aad3a215'), ('enable', 'e2e-disable-me-1.0.0.jar', 'APPLIED', '652e98f8-0d45-4b44-b2c5-a49319c205b5')] |

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
| history.json: two new apply entries, the older adding e2e-first, both APPLIED | PASS | new entries: [('apply', '2026-09-26T16:01:46.497248200Z', [('file', 'enable', 'e2e-first-1.0.0.jar', 'APPLIED')]), ('apply', '2026-09-26T16:01:47.987843400Z', [('file', 'enable', 'e2e-second-1.0.0.jar', 'APPLIED')])] |

## B-M3: after Undo this on the older Apply (e2e-first) and a restart (helper done)

| check | result | detail |
|---|---|---|
| the client exited normally and the helper finished | PASS | gradle exit 0, helper finished: True |
| the driver undid the older Apply only (one revert after a restart) | PASS | error: None; undoOf: 8c725f7e-434d-4ca6-a1c6-4ad542027638 (older 8c725f7e-434d-4ca6-a1c6-4ad542027638); via the undo screen: True; plan: [{'description': 'Disable e2e-first-1.0.0.jar', 'action': 'REVERT', 'reason': None, 'needsRestart': True, 'changeIds': ['e37e9ea6-8917-455e-9e51-098448f28c4b'], 'opIds': []}] |
| the older Apply's mod is disabled | PASS | e2e-first-1.0.0.jar.disabled exists: True; e2e-first-1.0.0.jar exists: False |
| the newer Apply's mod is still enabled | PASS | e2e-second-1.0.0.jar exists: True |
| no pending.json, no leftover downloads | PASS | pending.json exists: False; *.rigtune-pending: [] |
| last-apply.json: the reversal op OK | PASS | results: [('DISABLE_FILE', 'e2e-first-1.0.0.jar', 'OK')] |
| history.json: one undo of the older Apply, its change REVERTED, the newer APPLIED | PASS | undo entries of these applies: ['8c725f7e-434d-4ca6-a1c6-4ad542027638']; undo changes: [('disable', 'e2e-first-1.0.0.jar', 'APPLIED', 'e37e9ea6-8917-455e-9e51-098448f28c4b')]; older: {'e37e9ea6-8917-455e-9e51-098448f28c4b': 'REVERTED'}; newer: {'0e0d0a2e-b7bc-4793-b0f5-636c79c69a01': 'APPLIED'} |

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
| the driver switched 2 times | PASS | error: None; apply messages: ['Switched to Battery. Undo it in History.', 'Switched to Max FPS. Undo it in History.'] |
| history.json: two new apply entries of setting changes, applied (or discarded when replaced) | PASS | new entries: [('apply', [('vanilla.maxFps', '120', '60', 'APPLIED'), ('vanilla.particles', '0', '1', 'APPLIED'), ('vanilla.renderClouds', 'true', 'false', 'APPLIED'), ('vanilla.renderDistance', '12', '8', 'APPLIED'), ('vanilla.simulationDistance', '12', '6', 'APPLIED')]), ('apply', [('vanilla.enableVsync', 'true', 'false', 'APPLIED'), ('vanilla.maxFps', '60', '260', 'APPLIED'), ('vanilla.particles', '1', '0', 'APPLIED'), ('vanilla.renderClouds', 'false', 'true', 'APPLIED'), ('vanilla.renderDistance', '8', '12', 'APPLIED'), ('vanilla.simulationDistance', '6', '10', 'APPLIED')])] |
| history.json: each key's applied changes run from its value before the first switch to the last | PASS | 6 key(s) |
| the settings hold the last switch's values | PASS | 6 value(s) |
| no pending.json, no leftover downloads | PASS | pending.json exists: False; *.rigtune-pending: [] |
| last-apply.json: every op OK | PASS | statuses not OK: [] |
| profiles.json labels each switch entry | PASS | labels {'86bdf09f-2ab6-4569-85fa-d829df4b3a4e': 'Battery', 'cd6da796-896b-4539-b85d-19656642db86': 'Max FPS'}; expected {'86bdf09f-2ab6-4569-85fa-d829df4b3a4e': 'Battery', 'cd6da796-896b-4539-b85d-19656642db86': 'Max FPS'} |

## Profiles: after Undo last twice in the next start (helper done)

| check | result | detail |
|---|---|---|
| the client exited normally | PASS | gradle exit 0, helper finished: True |
| the driver undid the newer switch, then the older (Undo last twice) | PASS | error: None; plans (undoOf, items, problem): [('cd6da796-896b-4539-b85d-19656642db86', 6, None), ('86bdf09f-2ab6-4569-85fa-d829df4b3a4e', 5, None)]; expected ['cd6da796-896b-4539-b85d-19656642db86', '86bdf09f-2ab6-4569-85fa-d829df4b3a4e'] |
| history.json: the switches' changes REVERTED by undo entries, all applied | PASS | switch changes: [('vanilla.maxFps', 'REVERTED'), ('vanilla.particles', 'REVERTED'), ('vanilla.renderClouds', 'REVERTED'), ('vanilla.renderDistance', 'REVERTED'), ('vanilla.simulationDistance', 'REVERTED'), ('vanilla.enableVsync', 'REVERTED'), ('vanilla.maxFps', 'REVERTED'), ('vanilla.particles', 'REVERTED'), ('vanilla.renderClouds', 'REVERTED'), ('vanilla.renderDistance', 'REVERTED'), ('vanilla.simulationDistance', 'REVERTED')]; undo entries: ['cd6da796-896b-4539-b85d-19656642db86', '86bdf09f-2ab6-4569-85fa-d829df4b3a4e']; undo changes: [('vanilla.enableVsync', 'true', 'APPLIED', '331511be-9b82-4824-81bf-c7311d786cef'), ('vanilla.maxFps', '60', 'APPLIED', '24ff189e-c5da-4d1b-8c9d-68b6ae7e06df'), ('vanilla.particles', '1', 'APPLIED', 'a8aff6c9-3ac5-4f45-b934-f6549ccb7e9e'), ('vanilla.renderClouds', 'false', 'APPLIED', 'b7ba24a5-0559-4432-bd0c-ecbb0bbdefc1'), ('vanilla.renderDistance', '8', 'APPLIED', '87d07159-006b-4862-8234-50b2f1792f2f'), ('vanilla.simulationDistance', '6', 'APPLIED', 'b4bd76fd-d32d-4348-a26a-f7178bc08ccb'), ('vanilla.maxFps', '120', 'APPLIED', 'f00d1cc4-7514-4de1-9779-ceb34cef613f'), ('vanilla.particles', '0', 'APPLIED', '4d61c0ea-fb75-4d81-8bc2-6111105c9103'), ('vanilla.renderClouds', 'true', 'APPLIED', '6cb16c93-a90d-40e6-b79e-041680608122'), ('vanilla.renderDistance', '12', 'APPLIED', '84f54124-94de-4e56-a77a-c8a7da172331'), ('vanilla.simulationDistance', '12', 'APPLIED', 'a5f42a64-e7a7-487a-a785-bb1979981dfc')] |
| every key is back at its value before the first switch | PASS | 6 key(s) |
| no pending.json, no leftover downloads | PASS | pending.json exists: False; *.rigtune-pending: [] |
| last-apply.json: every op OK | PASS | statuses not OK: [] |
| profiles.json still labels each switch entry | PASS | labels {'86bdf09f-2ab6-4569-85fa-d829df4b3a4e': 'Battery', 'cd6da796-896b-4539-b85d-19656642db86': 'Max FPS'}; expected {'86bdf09f-2ab6-4569-85fa-d829df4b3a4e': 'Battery', 'cd6da796-896b-4539-b85d-19656642db86': 'Max FPS'} |

## Profiles: after the next start

| check | result | detail |
|---|---|---|
| the client exited normally | PASS | gradle exit 0, helper finished: True |
| the driver checked | PASS | error: None |
| the game runs with every key at its value before the first switch | PASS | 6 key(s) |
| nothing left to undo on either switch | PASS | undoable items: {'86bdf09f-2ab6-4569-85fa-d829df4b3a4e': 0, 'cd6da796-896b-4539-b85d-19656642db86': 0}; plan problems: {'86bdf09f-2ab6-4569-85fa-d829df4b3a4e': None, 'cd6da796-896b-4539-b85d-19656642db86': None} |
| no crash report | PASS | crash-reports: [] |
| mods unchanged by the relaunch | PASS | unchanged |
| history.json statuses unchanged | PASS | unchanged |
| no pending.json, no leftover downloads | PASS | pending.json exists: False; *.rigtune-pending: [] |

## Profiles, on a copy from after the switches: after Undo all (helper done)

| check | result | detail |
|---|---|---|
| the client exited normally | PASS | gradle exit 0, helper finished: True |
| the driver undid everything (Undo all) | PASS | error: None; plans (undoOf, items, problem): [('all', 6, None)]; expected ['all'] |
| history.json: the switches' changes REVERTED by undo entries, all applied | PASS | switch changes: [('vanilla.maxFps', 'REVERTED'), ('vanilla.particles', 'REVERTED'), ('vanilla.renderClouds', 'REVERTED'), ('vanilla.renderDistance', 'REVERTED'), ('vanilla.simulationDistance', 'REVERTED'), ('vanilla.enableVsync', 'REVERTED'), ('vanilla.maxFps', 'REVERTED'), ('vanilla.particles', 'REVERTED'), ('vanilla.renderClouds', 'REVERTED'), ('vanilla.renderDistance', 'REVERTED'), ('vanilla.simulationDistance', 'REVERTED')]; undo entries: ['all']; undo changes: [('vanilla.maxFps', '60', 'APPLIED', '24ff189e-c5da-4d1b-8c9d-68b6ae7e06df'), ('vanilla.maxFps', '120', 'APPLIED', 'f00d1cc4-7514-4de1-9779-ceb34cef613f'), ('vanilla.particles', '1', 'APPLIED', 'a8aff6c9-3ac5-4f45-b934-f6549ccb7e9e'), ('vanilla.particles', '0', 'APPLIED', '4d61c0ea-fb75-4d81-8bc2-6111105c9103'), ('vanilla.renderClouds', 'false', 'APPLIED', 'b7ba24a5-0559-4432-bd0c-ecbb0bbdefc1'), ('vanilla.renderClouds', 'true', 'APPLIED', '6cb16c93-a90d-40e6-b79e-041680608122'), ('vanilla.renderDistance', '8', 'APPLIED', '87d07159-006b-4862-8234-50b2f1792f2f'), ('vanilla.renderDistance', '12', 'APPLIED', '84f54124-94de-4e56-a77a-c8a7da172331'), ('vanilla.simulationDistance', '6', 'APPLIED', 'b4bd76fd-d32d-4348-a26a-f7178bc08ccb'), ('vanilla.simulationDistance', '12', 'APPLIED', 'a5f42a64-e7a7-487a-a785-bb1979981dfc'), ('vanilla.enableVsync', 'true', 'APPLIED', '331511be-9b82-4824-81bf-c7311d786cef')] |
| every key is back at its value before the first switch | PASS | 6 key(s) |
| no pending.json, no leftover downloads | PASS | pending.json exists: False; *.rigtune-pending: [] |
| last-apply.json: every op OK | PASS | statuses not OK: [] |
| profiles.json still labels each switch entry | PASS | labels {'86bdf09f-2ab6-4569-85fa-d829df4b3a4e': 'Battery', 'cd6da796-896b-4539-b85d-19656642db86': 'Max FPS'}; expected {'86bdf09f-2ab6-4569-85fa-d829df4b3a4e': 'Battery', 'cd6da796-896b-4539-b85d-19656642db86': 'Max FPS'} |

## Profiles, on that copy: after the next start

| check | result | detail |
|---|---|---|
| the client exited normally | PASS | gradle exit 0, helper finished: True |
| the driver checked | PASS | error: None |
| the game runs with every key at its value before the first switch | PASS | 6 key(s) |
| nothing left to undo on either switch | PASS | undoable items: {'86bdf09f-2ab6-4569-85fa-d829df4b3a4e': 0, 'cd6da796-896b-4539-b85d-19656642db86': 0}; plan problems: {'86bdf09f-2ab6-4569-85fa-d829df4b3a4e': None, 'cd6da796-896b-4539-b85d-19656642db86': None} |
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
- `driver-profile-check.json`
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
- `e2e-profile-check-1-history.png`
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
- `history-after-profile-check.json`
- `history-after-profile-undo-all.json`
- `history-after-profile-undo.json`
- `last-apply-after-entry-apply.json`
- `last-apply-after-entry-check.json`
- `last-apply-after-entry-undo.json`
- `last-apply-after-mod-apply.json`
- `last-apply-after-mod-check.json`
- `last-apply-after-mod-undo.json`
- `latest-entry-apply.filtered.log`
- `latest-entry-check.filtered.log`
- `latest-entry-undo.filtered.log`
- `latest-mod-apply.filtered.log`
- `latest-mod-check.filtered.log`
- `latest-mod-undo.filtered.log`
- `latest-profile-apply.filtered.log`
- `latest-profile-check-all.filtered.log`
- `latest-profile-check.filtered.log`
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
- `mods-after-profile-check.json`
- `mods-after-profile-undo-all.json`
- `mods-after-profile-undo.json`
- `options-after-profile-apply.txt`
- `options-after-profile-check-all.txt`
- `options-after-profile-check.txt`
- `options-after-profile-undo-all.txt`
- `options-after-profile-undo.txt`
- `pending-entry-apply.json`
- `pending-entry-undo.json`
- `pending-mod-apply.json`
- `pending-mod-undo.json`
- `profile-plan.json`
- `profiles-after-profile-apply.json`
- `profiles-after-profile-check-all.json`
- `profiles-after-profile-check.json`
- `profiles-after-profile-undo-all.json`
- `profiles-after-profile-undo.json`
- `redirect-probe.txt`
- `requests.jsonl`
- `sodium-options-after-profile-apply.json`
- `sodium-options-after-profile-check-all.json`
- `sodium-options-after-profile-check.json`
- `sodium-options-after-profile-undo-all.json`
- `sodium-options-after-profile-undo.json`
