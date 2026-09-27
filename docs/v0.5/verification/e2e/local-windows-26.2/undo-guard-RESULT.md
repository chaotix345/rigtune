# Undo after restart E2E: local-undo-guard

- Verdict: **PASS**
- Run: 20260927T171858Z UTC, MC 26.2, rigtune-0.5.0-dev+mc26.2.jar + fabric-api-0.161.0+26.2.jar + e2e-disable-me-1.0.0.jar in a fresh scratch instance
- RigTune: `rigtune-0.5.0-dev+mc26.2.jar` version 0.5.0-dev+mc26.2, sha256 `a6e475a7750c23388704091552d527ff033d6981b8443a34123895424c71c1fd`
- Added from the fake Modrinth: `e2e-added-1.0.0.jar` (project E2EAddMd); disabled: `e2e-disable-me-1.0.0.jar`
- B-M3, same instance: one Apply adds `e2e-first-1.0.0.jar` (project E2EFrst1), a second Apply adds `e2e-second-1.0.0.jar` (E2EScnd1); Undo this on the older one (entry 095cdf82-d9fb-4eeb-97fd-49e49e8d7fc5; through the undo screen: True; controller method: RigTuneController.undoPlanFor)
- Client time: mod-apply 33 s, mod-undo 26 s, mod-check 22 s, entry-apply 27 s, entry-undo 31 s, entry-check 21 s, guard-apply 24 s

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
| the driver undid the last apply (two reverts after a restart) | PASS | error: None; undoOf: 2361132c-df89-453c-be01-2f307a6ec8a4; plan: [{'description': 'Disable e2e-added-1.0.0.jar', 'action': 'REVERT', 'reason': None, 'needsRestart': True, 'changeIds': ['5e4ab0ab-5605-4e40-b83e-e0baf9aecfa7'], 'opIds': []}, {'description': 'Re-enable e2e-disable-me-1.0.0.jar', 'action': 'REVERT', 'reason': None, 'needsRestart': True, 'changeIds': ['7882f275-577f-4bfd-b5b7-1c6b27a4c97a'], 'opIds': []}] |
| the added mod is disabled again | PASS | e2e-added-1.0.0.jar.disabled exists: True |
| the other mod is back | PASS | e2e-disable-me-1.0.0.jar exists: True |
| no pending.json, no leftover downloads | PASS | pending.json exists: False; *.rigtune-pending: [] |
| last-apply.json: both reversal ops OK | PASS | results: [('DISABLE_FILE', 'e2e-added-1.0.0.jar', 'OK'), ('ENABLE_FILE', 'e2e-disable-me-1.0.0.jar', 'OK')] |
| history.json: the undo APPLIED, the apply's changes REVERTED | PASS | apply changes: {'7882f275-577f-4bfd-b5b7-1c6b27a4c97a': 'REVERTED', '5e4ab0ab-5605-4e40-b83e-e0baf9aecfa7': 'REVERTED'}; undo entries: ['2361132c-df89-453c-be01-2f307a6ec8a4']; undo changes: [('disable', 'e2e-added-1.0.0.jar', 'APPLIED', '5e4ab0ab-5605-4e40-b83e-e0baf9aecfa7'), ('enable', 'e2e-disable-me-1.0.0.jar', 'APPLIED', '7882f275-577f-4bfd-b5b7-1c6b27a4c97a')] |

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
| history.json: two new apply entries, the older adding e2e-first, both APPLIED | PASS | new entries: [('apply', '2026-09-27T17:20:57.968185400Z', [('file', 'enable', 'e2e-first-1.0.0.jar', 'APPLIED')]), ('apply', '2026-09-27T17:20:59.460389Z', [('file', 'enable', 'e2e-second-1.0.0.jar', 'APPLIED')])] |

## B-M3: after Undo this on the older Apply (e2e-first) and a restart (helper done)

| check | result | detail |
|---|---|---|
| the client exited normally and the helper finished | PASS | gradle exit 0, helper finished: True |
| the driver undid the older Apply only (one revert after a restart) | PASS | error: None; undoOf: 095cdf82-d9fb-4eeb-97fd-49e49e8d7fc5 (older 095cdf82-d9fb-4eeb-97fd-49e49e8d7fc5); via the undo screen: True; plan: [{'description': 'Disable e2e-first-1.0.0.jar', 'action': 'REVERT', 'reason': None, 'needsRestart': True, 'changeIds': ['566807ad-70d0-487b-9321-47d5145ba9dc'], 'opIds': []}] |
| the older Apply's mod is disabled | PASS | e2e-first-1.0.0.jar.disabled exists: True; e2e-first-1.0.0.jar exists: False |
| the newer Apply's mod is still enabled | PASS | e2e-second-1.0.0.jar exists: True |
| no pending.json, no leftover downloads | PASS | pending.json exists: False; *.rigtune-pending: [] |
| last-apply.json: the reversal op OK | PASS | results: [('DISABLE_FILE', 'e2e-first-1.0.0.jar', 'OK')] |
| history.json: one undo of the older Apply, its change REVERTED, the newer APPLIED | PASS | undo entries of these applies: ['095cdf82-d9fb-4eeb-97fd-49e49e8d7fc5']; undo changes: [('disable', 'e2e-first-1.0.0.jar', 'APPLIED', '566807ad-70d0-487b-9321-47d5145ba9dc')]; older: {'566807ad-70d0-487b-9321-47d5145ba9dc': 'REVERTED'}; newer: {'789d7c3c-b5c5-4350-8d03-5349d098b119': 'APPLIED'} |

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

## AC3f.7: after a pinned update, an addition and an update the staged addition declares incompatible (helper done)

| check | result | detail |
|---|---|---|
| the client exited normally and the helper finished | PASS | gradle exit 0, helper finished: True |
| the driver ran its three Applies | PASS | error: None; statuses ['Download failed: Update RigTune E2E test mod e2e-pin-target: RigTune E2E test mod e2e-pinner, which is installed, needs RigTune E2E test mod e2e-pin-target 1.0.x, not 2.0.0', 'Restart Minecraft to finish applying 1 change(s).', 'Download failed: Update RigTune E2E test mod e2e-rev-target: Modrinth marks e2e-rev-add, which is waiting for a restart, as incompatible with e2e-rev-target Restart Minecraft to finish applying 1 change(s).'] |
| the pinned update is refused with the pin (WS-G1) | PASS | Download failed: Update RigTune E2E test mod e2e-pin-target: RigTune E2E test mod e2e-pinner, which is installed, needs RigTune E2E test mod e2e-pin-target 1.0.x, not 2.0.0 |
| the update the staged addition declares incompatible is refused (2d's reverse check) | PASS | Download failed: Update RigTune E2E test mod e2e-rev-target: Modrinth marks e2e-rev-add, which is waiting for a restart, as incompatible with e2e-rev-target Restart Minecraft to finish applying 1 change(s). |
| RigTune read the staged version back from Modrinth | PASS | GET /v2/versions naming E2ERevAV: 1 |
| only the addition went in; the refused updates changed nothing | PASS | mods/: ['e2e-added-1.0.0.jar.disabled', 'e2e-disable-me-1.0.0.jar', 'e2e-first-1.0.0.jar.disabled', 'e2e-pin-target-1.0.0.jar', 'e2e-pinner-1.0.0.jar', 'e2e-rev-add-1.0.0.jar', 'e2e-rev-target-1.0.0.jar', 'e2e-second-1.0.0.jar', 'fabric-api-0.161.0+26.2.jar', 'rigtune-0.5.0-dev+mc26.2.jar']; last-apply: {'e2e-rev-add': 'OK'}; journal changes of ['e2e-rev-target', 'e2e-pin-target']: [] |

## Files

- `catalog.json`
- `checks.json`
- `driver-entry-apply.json`
- `driver-entry-check.json`
- `driver-entry-undo.json`
- `driver-guard-apply.json`
- `driver-mod-apply.json`
- `driver-mod-check.json`
- `driver-mod-undo.json`
- `e2e-entry-apply-2-after.png`
- `e2e-entry-check-1-undo.png`
- `e2e-entry-undo-1-plan.png`
- `e2e-entry-undo-2-after.png`
- `e2e-guard-apply-2-after.png`
- `e2e-mod-apply-1-report.png`
- `e2e-mod-apply-2-after.png`
- `e2e-mod-check-1-undo.png`
- `e2e-mod-undo-1-plan.png`
- `e2e-mod-undo-2-after.png`
- `e2e.log`
- `helper-after-entry-apply.log`
- `helper-after-entry-check.log`
- `helper-after-entry-undo.log`
- `helper-after-guard-apply.log`
- `helper-after-mod-apply.log`
- `helper-after-mod-check.log`
- `helper-after-mod-undo.log`
- `helper-cmdlines-entry-apply.txt`
- `helper-cmdlines-entry-undo.txt`
- `helper-cmdlines-guard-apply.txt`
- `helper-cmdlines-mod-apply.txt`
- `helper-cmdlines-mod-undo.txt`
- `history-after-entry-apply.json`
- `history-after-entry-check.json`
- `history-after-entry-undo.json`
- `history-after-guard-apply.json`
- `history-after-mod-apply.json`
- `history-after-mod-check.json`
- `history-after-mod-undo.json`
- `last-apply-after-entry-apply.json`
- `last-apply-after-entry-check.json`
- `last-apply-after-entry-undo.json`
- `last-apply-after-guard-apply.json`
- `last-apply-after-mod-apply.json`
- `last-apply-after-mod-check.json`
- `last-apply-after-mod-undo.json`
- `latest-entry-apply.filtered.log`
- `latest-entry-check.filtered.log`
- `latest-entry-undo.filtered.log`
- `latest-guard-apply.filtered.log`
- `latest-mod-apply.filtered.log`
- `latest-mod-check.filtered.log`
- `latest-mod-undo.filtered.log`
- `mods-after-entry-apply.json`
- `mods-after-entry-check.json`
- `mods-after-entry-undo.json`
- `mods-after-guard-apply.json`
- `mods-after-mod-apply.json`
- `mods-after-mod-check.json`
- `mods-after-mod-undo.json`
- `pending-entry-apply.json`
- `pending-entry-undo.json`
- `pending-mod-apply.json`
- `pending-mod-undo.json`
- `redirect-probe.txt`
- `requests.jsonl`
