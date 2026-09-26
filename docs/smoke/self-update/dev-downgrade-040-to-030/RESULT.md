# Downgrade E2E: dev-downgrade-040-to-030

- Verdict: **PASS**
- Run: 20260926T043205Z UTC, MC 26.2, a fresh scratch instance with rigtune-0.3.0+mc26.2.jar + fabric-api-0.161.0+26.2.jar + `e2e-downgrade-off-1.0.0.jar` (for 0.3.0's own Apply)
- Old (started on 0.4's files): `rigtune-0.3.0+mc26.2.jar` version 0.3.0+mc26.2, sha256 `5717f65cb90c71aaeda844b7bd56e3ce9255e83f44418af0cfc6a589050cd7e9`
- New (reinstalled after it): `rigtune-0.4.0-dev+mc26.2.jar` version 0.4.0-dev+mc26.2, sha256 `2083302f49794dc3b854f53999d1f58aaa5002b85bfaa68e3b8ef972943e1c57`
- "Written by 0.4" sets (plan review H-M1; `seeded.json`): `ws-a` (**placeholder**), `ws-p` (**placeholder**), `ws-b` (**placeholder**), `ws-s` (**placeholder**), `ws-w` (**placeholder**), `ws-f` (**placeholder**)
- Client time: downgrade-old 31 s, downgrade-new 19 s

## The released old version on files the new one wrote: History, Undo last, its own Apply, quit (helper done)

| check | result | detail |
|---|---|---|
| the client exited normally and the helper finished | PASS | gradle exit 0, helper finished: True |
| the driver ran the released 0.3.0 | PASS | error: None; loaded 0.3.0+mc26.2 |
| latest.log: no RigTune ERROR, stack trace or refusal of a newer file | PASS | none |
| History lists every entry 0.4 wrote (state OK) | PASS | state OK; 3 of 3 listed; missing []; unknown kinds [] |
| Undo last reverted the newest undoable entry, recorded by 0.3.0 | PASS | plan: {'undoOf': 'c3e7a1f6-8d0b-4e2f-9b3c-7f5e6d349c17', 'problem': None, 'items': ['REVERT Render Distance: 8 → 12', 'REVERT Max Framerate: 60 → 120']}; expected undoOf c3e7a1f6-8d0b-4e2f-9b3c-7f5e6d349c17; 0.3.0 undo entries of it: [[('vanilla.renderDistance', 'APPLIED'), ('vanilla.maxFps', 'APPLIED')]]; its changes still applied or staged: [] |
| 0.3.0's own Apply staged and applied (disable e2e-downgrade-off-1.0.0.jar) | PASS | last-apply: ['OK']; e2e-downgrade-off-1.0.0.jar.disabled: True; journaled by 0.3.0: 1 |
| 0.4's staged ops (with projectId) applied by 0.3.0's helper | PASS | ops ['0a4f3c1e-5b7d-4e2a-9c61-7d2f1b8e4a01'] -> ['OK']; their journal changes ['APPLIED']; pending.json left: False |
| the files only 0.4 writes are byte-identical | PASS | 5 file(s): ['awareness.json', 'profiles.json', 'server-limits.json', 'startup-times.json', 'stutter.json'] |
| no .bad file, no crash report | PASS | .bad: []; crash-reports: [] |

## The new version again, on what the old one left

| check | result | detail |
|---|---|---|
| the relaunched client exited normally | PASS | gradle exit 0 |
| 0.4 loaded from mods/ again | PASS | error: None; loaded 0.4.0-dev+mc26.2 from ['<instance>\\mods\\rigtune-0.4.0-dev+mc26.2.jar'] |
| latest.log: no RigTune ERROR, stack trace or refusal of a newer file | PASS | none |
| no .bad file, no crash report | PASS | .bad: []; crash-reports: [] |
| History lists every entry of history.json (state OK) | PASS | state OK; listed 5 of 5; unknown kinds [] |
| profiles.json still labels the switch entries 0.3.0 kept | PASS | expected {'c3e7a1f6-8d0b-4e2f-9b3c-7f5e6d349c17': 'Battery'}; labels now {'c3e7a1f6-8d0b-4e2f-9b3c-7f5e6d349c17': 'Battery'} |
| 0.4 read its own files back (none reset or moved to .bad) | PASS | 5 file(s) kept their items |

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
