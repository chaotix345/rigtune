# Self-update E2E: dev-v010-to-040

- Verdict: **PASS**
- Run: 20260926T031242Z UTC, MC 26.2, rigtune-0.1.0.jar + fabric-api-0.161.0+26.2.jar + e2e-legacy-1.0.0.jar in a fresh scratch instance
- Old: `rigtune-0.1.0.jar` version 0.1.0, sha256 `8294d04a6b67e76dcff298366be38f85048ebf19a120baa9e8ed5b08b2e4b950`
- New (served by the fake Modrinth): `rigtune-0.4.0-dev+mc26.2.jar` version 0.4.0-dev+mc26.2, sha256 `bd2c93ae44b9c48a3708f1d4b0bddeb7e1dd2311292f0cd8d7d4f69c6d03f0a4`
- Expected history: `legacy-import` (a 0.1.x old side)
- The old version also disabled `e2e-legacy-1.0.0.jar` in the same apply (a change that isn't RigTune's own, for the journal check)
- Rescan pressed because the report stayed offline (the startup lookup race, docs/v0.2/design/ws-g.md): update phase no, verify phase no
- Client time: update 30 s, verify 22 s

## After the old version applied the update and quit (helper done)

| check | result | detail |
|---|---|---|
| the client exited normally and the helper finished | PASS | gradle exit 0, helper finished: True |
| the driver applied the offered update | PASS | driver: ok=True error=None offered=rigtune-0.4.0-dev+mc26.2.jar |
| exactly one RigTune jar, the new one | PASS | rigtune*.jar in mods: ['rigtune-0.4.0-dev+mc26.2.jar']; bytes identical to the served jar: True |
| the old jar is disabled | PASS | rigtune-0.1.0.jar.disabled exists: True |
| the other mod the old version changed is disabled | PASS | (.disabled exists, jar exists): {'e2e-legacy-1.0.0.jar': (True, False)} |
| no pending.json | PASS | pending.json exists: False |
| no leftover downloads | PASS | *.rigtune-pending: [] |
| last-apply.json: the update's two ops, all OK | PASS | results: [('DISABLE_FILE', 'e2e-legacy-1.0.0.jar', 'OK'), ('DISABLE_FILE', 'rigtune-0.1.0.jar', 'OK'), ('ENABLE_FILE', 'rigtune-0.4.0-dev+mc26.2.jar', 'OK')] |
| the helper ran from config/rigtune/helper copies | PASS | helper classpaths seen: [['<instance>\\config\\rigtune\\helper\\0-rigtune-0.1.0.jar', '<instance>\\config\\rigtune\\helper\\1-gson-2.14.0.jar']] |
| the old RigTune downloaded the jar from cdn.modrinth.com | PASS | download requests: [('cdn.modrinth.com', 200, 'chaotix345/rigtune/0.1.0 (github.com/chaotix345/rigtune)')] |
| the update's depends and breaks are no stricter (M12) | PASS | same or fewer entries: depends {'fabricloader': '>=0.19.5', 'minecraft': '~26.2', 'java': '>=25', 'fabric-api': '*'} breaks {} |

## After the new version started on the same instance

| check | result | detail |
|---|---|---|
| the relaunched client exited normally | PASS | gradle exit 0 |
| the driver verified | PASS | error: None |
| the new RigTune is loaded from mods/ | PASS | loaded 0.4.0-dev+mc26.2 from ['<instance>\\mods\\rigtune-0.4.0-dev+mc26.2.jar'] |
| the goal is kept | PASS | goal: QUALITY |
| the apply result was shown (rigtune.json lastShownApply) | PASS | lastShownApply 2026-09-26T03:13:21.925180700Z vs last-apply.json finishedAt 2026-09-26T03:13:21.925180700Z |
| no further RigTune update is offered | PASS | online=True updateOffered=False |
| no crash report | PASS | crash-reports: [] |
| mods unchanged by the relaunch | PASS | unchanged |
| no new pending.json | PASS |  |
| history.json: one legacy import, without RigTune's own jars | PASS | history.json entries: 1; legacy-import entries: 1; RigTune changes: []; expected disables missing: []; imported changes: [[{'id': '148bba00-b537-4b7e-999b-e86b7e1efb41', 'type': 'file', 'action': 'disable', 'modId': 'e2e-legacy', 'file': 'e2e-legacy-1.0.0.jar', 'resultFile': 'e2e-legacy-1.0.0.jar.disabled', 'status': 'APPLIED', 'opId': '48215311-0f85-4f7b-8249-013685aa1e3c'}]] |

## Files

- `captured-helper.log`
- `captured-last-apply.json`
- `captured-pending.json`
- `captured-rigtune.json`
- `captured-rules-cache.json`
- `catalog.json`
- `checks.json`
- `driver-update.json`
- `driver-verify.json`
- `e2e-update-1-report.png`
- `e2e-update-2-staged.png`
- `e2e-verify-1-title.png`
- `e2e-verify-2-report.png`
- `e2e.log`
- `helper-after-update.log`
- `helper-after-verify.log`
- `helper-cmdlines-update.txt`
- `helper-dir.txt`
- `history-after-verify.json`
- `last-apply-after-update.json`
- `last-apply-after-verify.json`
- `latest-update.filtered.log`
- `latest-verify.filtered.log`
- `mods-after-update.json`
- `mods-after-verify.json`
- `pending-before-exit.json`
- `redirect-probe.txt`
- `report-update.txt`
- `report-verify.txt`
- `requests.jsonl`
