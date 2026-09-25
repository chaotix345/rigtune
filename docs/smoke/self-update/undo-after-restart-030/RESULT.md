# Undo after restart E2E: undo-after-restart-030

- Verdict: **PASS**
- Run: 20260925T230639Z UTC, MC 26.2, rigtune-0.3.0-dev+mc26.2.jar + fabric-api-0.161.0+26.2.jar + e2e-disable-me-1.0.0.jar in a fresh scratch instance
- RigTune: `rigtune-0.3.0-dev+mc26.2.jar` version 0.3.0-dev+mc26.2, sha256 `17cfe5b84fc59479a1c241a8e70ffb0f8fad1ffd51215754ceae0835fd910b5f`
- Added from the fake Modrinth: `e2e-added-1.0.0.jar` (project E2EAddMd); disabled: `e2e-disable-me-1.0.0.jar`
- B-M3, same instance: one Apply adds `e2e-first-1.0.0.jar` (project E2EFrst1), a second Apply adds `e2e-second-1.0.0.jar` (E2EScnd1); Undo this on the older one (entry 5617e4a7-eea2-4ffd-86cb-5d7e9cf51d17; through the undo screen: True; controller method: RigTuneController.undoPlanFor)
- Client time: mod-apply 24 s, mod-undo 23 s, mod-check 19 s, entry-apply 22 s, entry-undo 22 s, entry-check 24 s

## After 0.2 applied {add e2e-added from Modrinth, disable e2e-disable-me} and quit (helper done)

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
| the driver undid the last apply (two reverts after a restart) | PASS | error: None; undoOf: b05c5122-7196-46bb-b1be-5f73452675bf; plan: [{'description': 'Disable e2e-added-1.0.0.jar', 'action': 'REVERT', 'reason': None, 'needsRestart': True, 'changeIds': ['a472268a-cc79-4878-823f-3e652d7744cc'], 'opIds': []}, {'description': 'Re-enable e2e-disable-me-1.0.0.jar', 'action': 'REVERT', 'reason': None, 'needsRestart': True, 'changeIds': ['685d05e9-756c-4312-a8ea-727fbcecf8b7'], 'opIds': []}] |
| the added mod is disabled again | PASS | e2e-added-1.0.0.jar.disabled exists: True |
| the other mod is back | PASS | e2e-disable-me-1.0.0.jar exists: True |
| no pending.json, no leftover downloads | PASS | pending.json exists: False; *.rigtune-pending: [] |
| last-apply.json: both reversal ops OK | PASS | results: [('DISABLE_FILE', 'e2e-added-1.0.0.jar', 'OK'), ('ENABLE_FILE', 'e2e-disable-me-1.0.0.jar', 'OK')] |
| history.json: the undo APPLIED, the apply's changes REVERTED | PASS | apply changes: {'685d05e9-756c-4312-a8ea-727fbcecf8b7': 'REVERTED', 'a472268a-cc79-4878-823f-3e652d7744cc': 'REVERTED'}; undo entries: ['b05c5122-7196-46bb-b1be-5f73452675bf']; undo changes: [('disable', 'e2e-added-1.0.0.jar', 'APPLIED', 'a472268a-cc79-4878-823f-3e652d7744cc'), ('enable', 'e2e-disable-me-1.0.0.jar', 'APPLIED', '685d05e9-756c-4312-a8ea-727fbcecf8b7')] |

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
| history.json: two new apply entries, the older adding e2e-first, both APPLIED | PASS | new entries: [('apply', '2026-09-25T23:08:13.738157Z', [('file', 'enable', 'e2e-first-1.0.0.jar', 'APPLIED')]), ('apply', '2026-09-25T23:08:15.226829100Z', [('file', 'enable', 'e2e-second-1.0.0.jar', 'APPLIED')])] |

## B-M3: after Undo this on the older Apply (e2e-first) and a restart (helper done)

| check | result | detail |
|---|---|---|
| the client exited normally and the helper finished | PASS | gradle exit 0, helper finished: True |
| the driver undid the older Apply only (one revert after a restart) | PASS | error: None; undoOf: 5617e4a7-eea2-4ffd-86cb-5d7e9cf51d17 (older 5617e4a7-eea2-4ffd-86cb-5d7e9cf51d17); via the undo screen: True; plan: [{'description': 'Disable e2e-first-1.0.0.jar', 'action': 'REVERT', 'reason': None, 'needsRestart': True, 'changeIds': ['c8c69b25-3a84-465c-a8d1-4b282c4a87ac'], 'opIds': []}] |
| the older Apply's mod is disabled | PASS | e2e-first-1.0.0.jar.disabled exists: True; e2e-first-1.0.0.jar exists: False |
| the newer Apply's mod is still enabled | PASS | e2e-second-1.0.0.jar exists: True |
| no pending.json, no leftover downloads | PASS | pending.json exists: False; *.rigtune-pending: [] |
| last-apply.json: the reversal op OK | PASS | results: [('DISABLE_FILE', 'e2e-first-1.0.0.jar', 'OK')] |
| history.json: one undo of the older Apply, its change REVERTED, the newer APPLIED | PASS | undo entries of these applies: ['5617e4a7-eea2-4ffd-86cb-5d7e9cf51d17']; undo changes: [('disable', 'e2e-first-1.0.0.jar', 'APPLIED', 'c8c69b25-3a84-465c-a8d1-4b282c4a87ac')]; older: {'c8c69b25-3a84-465c-a8d1-4b282c4a87ac': 'REVERTED'}; newer: {'365ba102-9427-4021-b74a-8273d9040404': 'APPLIED'} |

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

## Files

- `catalog.json`
- `checks.json`
- `driver-entry-apply.json`
- `driver-entry-check.json`
- `driver-entry-undo.json`
- `driver-mod-apply.json`
- `driver-mod-check.json`
- `driver-mod-undo.json`
- `e2e-entry-apply-2-after.png`
- `e2e-entry-check-1-undo.png`
- `e2e-entry-undo-1-plan.png`
- `e2e-entry-undo-2-after.png`
- `e2e-mod-apply-1-report.png`
- `e2e-mod-apply-2-after.png`
- `e2e-mod-check-1-undo.png`
- `e2e-mod-undo-1-plan.png`
- `e2e-mod-undo-2-after.png`
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
- `history-after-entry-apply.json`
- `history-after-entry-check.json`
- `history-after-entry-undo.json`
- `history-after-mod-apply.json`
- `history-after-mod-check.json`
- `history-after-mod-undo.json`
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
- `mods-after-entry-apply.json`
- `mods-after-entry-check.json`
- `mods-after-entry-undo.json`
- `mods-after-mod-apply.json`
- `mods-after-mod-check.json`
- `mods-after-mod-undo.json`
- `pending-entry-apply.json`
- `pending-entry-undo.json`
- `pending-mod-apply.json`
- `pending-mod-undo.json`
- `redirect-probe.txt`
- `requests.jsonl`
