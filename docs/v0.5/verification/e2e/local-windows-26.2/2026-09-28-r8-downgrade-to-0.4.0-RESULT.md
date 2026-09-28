# Downgrade E2E: r8-downgrade-to-0.4.0

- Verdict: **PASS**
- Run: 20260928T032138Z UTC, MC 26.2, a fresh scratch instance with rigtune-0.4.0+mc26.2.jar + fabric-api-0.161.0+26.2.jar + `e2e-downgrade-off-1.0.0.jar` (for 0.3.0's own Apply)
- Old (started on 0.4's files): `rigtune-0.4.0+mc26.2.jar` version 0.4.0+mc26.2, sha256 `801cd3b8e91c6a27b819d64c5b433bea6d7ac99d4776cb853c873a9cafdd868a`
- New (reinstalled after it): `rigtune-0.5.0-dev+mc26.2.jar` version 0.5.0-dev+mc26.2, sha256 `571f15c30b1caa56bcff6acdea050e10df1a413913d164ec6af79ab29d7baa43`
- "Written by 0.4" sets (plan review H-M1; `seeded.json`): `ws-a`, `ws-p`, `ws-b`, `ws-s`, `ws-w`, `ws-f`, `ws-l1`, `ws-l2`, `ws-s`, `ws-s2` (**placeholder**), `ws-p`, `ws-p2`, `ws-b`, `ws-t` (**placeholder**), `ws-w`, `ws-w2` (**placeholder**), `ws-f`, `ws-h`
- Client time: downgrade-old 28 s, downgrade-new 19 s

## The released old version on files the new one wrote: History, Undo last, its own Apply, quit (helper done)

| check | result | detail |
|---|---|---|
| the client exited normally and the helper finished | PASS | gradle exit 0, helper finished: True |
| the driver ran the released 0.4.0 | PASS | error: None; loaded 0.4.0+mc26.2 |
| latest.log: no RigTune ERROR, stack trace or refusal of a newer file | PASS | none |
| History lists every entry the newer versions wrote (state OK) | PASS | state OK; 46 of 46 listed; missing []; unknown kinds [] |
| Undo last reverted the newest undoable entry, recorded by 0.4.0 | PASS | plan undoOf 6b3d8f21-2c4e-4d6f-9a71-000000000602 (expected 6b3d8f21-2c4e-4d6f-9a71-000000000602), problem None, items [('DISCARD_STAGED', 'Sodium: Chunk Updates: Deferred → ONE_FRAME_AHEAD', None)]; its undo entries of it: [[]]; planned but not undone: []; vanilla or mod changes skipped: [] |
| 0.4.0's own Apply staged and applied (disable e2e-downgrade-off-1.0.0.jar) | PASS | last-apply: ['OK']; e2e-downgrade-off-1.0.0.jar.disabled: True; journaled by it: 1 |
| the newer versions' staged ops (with projectId): applied by 0.4.0's helper, or dropped by its Undo last | PASS | ops (expected, last-apply, journal): {'0a4f3c1e-5b7d-4e2a-9c61-7d2f1b8e4a01': ('APPLIED', 'OK', ['APPLIED']), 'a1b2c3d4-5e6f-4a0b-8c1d-2e3f4a5b6c01': ('APPLIED', 'OK', []), 'a1b2c3d4-5e6f-4a0b-8c1d-2e3f4a5b6c03': ('APPLIED', 'OK', []), '6b3d8f21-2c4e-4d6f-9a71-000000000603': ('DISCARDED', None, ['DISCARDED']), '9e6a1c54-5f71-4092-ad04-000000000902': ('APPLIED', 'OK', ['APPLIED'])}; pending.json left: False |
| the files only the newer versions write are byte-identical | PASS | 3 file(s): ['server-profiles.json', 'stutter-fixes.json', 'tryit.json'] |
| no .bad file, no crash report | PASS | .bad: []; crash-reports: [] |

## The new version again, on what the old one left

| check | result | detail |
|---|---|---|
| the relaunched client exited normally | PASS | gradle exit 0 |
| 0.5.0-dev loaded from mods/ again | PASS | error: None; loaded 0.5.0-dev+mc26.2 from ['<instance>\\mods\\rigtune-0.5.0-dev+mc26.2.jar'] |
| latest.log: no RigTune ERROR, stack trace or refusal of a newer file | PASS | none |
| no .bad file, no crash report | PASS | .bad: []; crash-reports: [] |
| History lists every entry of history.json (state OK) | PASS | state OK; listed 48 of 48; unknown kinds [] |
| profiles.json still labels the switch entries the old version kept | PASS | expected {'c3e7a1f6-8d0b-4e2f-9b3c-7f5e6d349c17': 'Battery', '8ee686e5-f831-4836-80a2-33551c9bbe31': 'Battery'}; labels now {'c3e7a1f6-8d0b-4e2f-9b3c-7f5e6d349c17': 'Battery', 'ac70e7f0-6812-4bbb-b888-da253724da0b': 'Max FPS', '8ee686e5-f831-4836-80a2-33551c9bbe31': 'Battery'} |
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
