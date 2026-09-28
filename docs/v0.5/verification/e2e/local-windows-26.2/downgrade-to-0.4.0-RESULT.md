# Downgrade E2E: local-downgrade-to-0.4.0-v050

- Verdict: **PASS**
- Run: 20260927T164612Z UTC, MC 26.2, a fresh scratch instance with rigtune-0.4.0+mc26.2.jar + fabric-api-0.161.0+26.2.jar + `e2e-downgrade-off-1.0.0.jar` (for 0.3.0's own Apply)
- Old (started on 0.4's files): `rigtune-0.4.0+mc26.2.jar` version 0.4.0+mc26.2, sha256 `801cd3b8e91c6a27b819d64c5b433bea6d7ac99d4776cb853c873a9cafdd868a`
- New (reinstalled after it): `rigtune-0.5.0-dev+mc26.2.jar` version 0.5.0-dev+mc26.2, sha256 `a6e475a7750c23388704091552d527ff033d6981b8443a34123895424c71c1fd`
- "Written by 0.4" sets (plan review H-M1; `seeded.json`): `ws-a`, `ws-p`, `ws-b`, `ws-s`, `ws-w`, `ws-f`, `ws-l1` (**placeholder**), `ws-l2` (**placeholder**), `ws-s` (**placeholder**), `ws-s2` (**placeholder**), `ws-p` (**placeholder**), `ws-p2`, `ws-b` (**placeholder**), `ws-t` (**placeholder**), `ws-w` (**placeholder**), `ws-w2` (**placeholder**), `ws-f` (**placeholder**)
- Client time: downgrade-old 37 s, downgrade-new 26 s

## The released old version on files the new one wrote: History, Undo last, its own Apply, quit (helper done)

| check | result | detail |
|---|---|---|
| the client exited normally and the helper finished | PASS | gradle exit 0, helper finished: True |
| the driver ran the released 0.4.0 | PASS | error: None; loaded 0.4.0+mc26.2 |
| latest.log: no RigTune ERROR, stack trace or refusal of a newer file | PASS | none |
| History lists every entry the newer versions wrote (state OK) | PASS | state OK; 8 of 8 listed; missing []; unknown kinds [] |
| Undo last reverted the newest undoable entry, recorded by 0.4.0 | PASS | plan undoOf 6b3d8f21-2c4e-4d6f-9a71-000000000602 (expected 6b3d8f21-2c4e-4d6f-9a71-000000000602), problem None, items [('DISCARD_STAGED', 'Sodium: Chunk Updates: Deferred → ONE_FRAME_AHEAD', None)]; its undo entries of it: [[]]; planned but not undone: []; vanilla or mod changes skipped: [] |
| 0.4.0's own Apply staged and applied (disable e2e-downgrade-off-1.0.0.jar) | PASS | last-apply: ['OK']; e2e-downgrade-off-1.0.0.jar.disabled: True; journaled by it: 1 |
| the newer versions' staged ops (with projectId): applied by 0.4.0's helper, or dropped by its Undo last | PASS | ops (expected, last-apply, journal): {'0a4f3c1e-5b7d-4e2a-9c61-7d2f1b8e4a01': ('APPLIED', 'OK', ['APPLIED']), '5a2c7e10-1b3d-4c5e-8f60-000000000502': ('APPLIED', 'OK', ['APPLIED']), '5a2c7e10-1b3d-4c5e-8f60-000000000503': ('APPLIED', 'OK', ['APPLIED']), '6b3d8f21-2c4e-4d6f-9a71-000000000603': ('DISCARDED', None, ['DISCARDED']), '9e6a1c54-5f71-4092-ad04-000000000902': ('APPLIED', 'OK', ['APPLIED'])}; pending.json left: False |
| the files only the newer versions write are byte-identical | PASS | 3 file(s): ['server-profiles.json', 'stutter-fixes.json', 'tryit.json'] |
| no .bad file, no crash report | PASS | .bad: []; crash-reports: [] |

## The new version again, on what the old one left

| check | result | detail |
|---|---|---|
| the relaunched client exited normally | PASS | gradle exit 0 |
| 0.5.0-dev loaded from mods/ again | PASS | error: None; loaded 0.5.0-dev+mc26.2 from ['<instance>\\mods\\rigtune-0.5.0-dev+mc26.2.jar'] |
| latest.log: no RigTune ERROR, stack trace or refusal of a newer file | PASS | none |
| no .bad file, no crash report | PASS | .bad: []; crash-reports: [] |
| History lists every entry of history.json (state OK) | PASS | state OK; listed 10 of 10; unknown kinds [] |
| profiles.json still labels the switch entries the old version kept | PASS | expected {'c3e7a1f6-8d0b-4e2f-9b3c-7f5e6d349c17': 'Battery'}; labels now {'c3e7a1f6-8d0b-4e2f-9b3c-7f5e6d349c17': 'Battery', '7c4e9a32-3d5f-4e70-8b82-000000000702': 'Battery', '7c4e9a32-3d5f-4e70-8b82-000000000703': 'Far view'} |
| 0.5.0-dev read its own files back (none reset or moved to .bad) | PASS | 6 file(s) kept their items |

## Files

- `catalog.json`
- `checks.json`
- `driver-downgrade-new.json`
- `driver-downgrade-old.json`
- `e2e-downgrade-new-1-history.png`
- `e2e-downgrade-old-1-history.png`
- `e2e-downgrade-old-2-undo.png`
- `e2e-downgrade-old-3-history.png`
- `e2e.log`
- `helper-after-downgrade-new.log`
- `helper-after-downgrade-old.log`
- `helper-cmdlines-downgrade-old.txt`
- `history-after-downgrade-new.json`
- `history-after-downgrade-old.json`
- `last-apply-after-downgrade-new.json`
- `last-apply-after-downgrade-old.json`
- `latest-downgrade-new.filtered.log`
- `latest-downgrade-old.filtered.log`
- `mods-after-downgrade-new.json`
- `mods-after-downgrade-old.json`
- `pending-downgrade-old.json`
- `redirect-probe.txt`
- `requests.jsonl`
- `seeded.json`
