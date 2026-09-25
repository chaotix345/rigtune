# Self-update E2E: dev-v020-to-030

- Verdict: **PASS**
- Run: 20260925T192759Z UTC, MC 26.2, rigtune-0.2.0+mc26.2.jar + fabric-api-0.161.0+26.2.jar in a fresh scratch instance
- Old: `rigtune-0.2.0+mc26.2.jar` version 0.2.0+mc26.2, sha256 `67275e232fe4de9f806dd6496f479d8385d8afabf9a6b93ffe909ce42f657de9`
- New (served by the fake Modrinth): `rigtune-0.3.0-dev+mc26.2.jar` version 0.3.0-dev+mc26.2, sha256 `9015bd10095acfb71232d656a0f427df167f9dd5f0abb17dbda3231685d48c19`
- Rescan pressed because the report stayed offline (the startup lookup race, docs/v0.2/design/ws-g.md): update phase no, verify phase no
- Client time: update 26 s, verify 27 s

## After the old version applied the update and quit (helper done)

| check | result | detail |
|---|---|---|
| the client exited normally and the helper finished | PASS | gradle exit 0, helper finished: True |
| the driver applied the offered update | PASS | driver: ok=True error=None offered=rigtune-0.3.0-dev+mc26.2.jar |
| exactly one RigTune jar, the new one | PASS | rigtune*.jar in mods: ['rigtune-0.3.0-dev+mc26.2.jar']; bytes identical to the served jar: True |
| the old jar is disabled | PASS | rigtune-0.2.0+mc26.2.jar.disabled exists: True |
| no pending.json | PASS | pending.json exists: False |
| no leftover downloads | PASS | *.rigtune-pending: [] |
| last-apply.json: the update's two ops, all OK | PASS | results: [('DISABLE_FILE', 'rigtune-0.2.0+mc26.2.jar', 'OK'), ('ENABLE_FILE', 'rigtune-0.3.0-dev+mc26.2.jar', 'OK')] |
| the helper ran from config/rigtune/helper copies | PASS | helper classpaths seen: [['<instance>\\config\\rigtune\\helper\\0-rigtune-0.2.0+mc26.2.jar', '<instance>\\config\\rigtune\\helper\\1-gson-2.14.0.jar']] |
| the old RigTune downloaded the jar from cdn.modrinth.com | PASS | download requests: [('cdn.modrinth.com', 200, 'chaotix345/rigtune/0.2.0+mc26.2 (github.com/chaotix345/rigtune)')] |
| the update's depends and breaks are no stricter (M12) | PASS | same or fewer entries: depends {'fabricloader': '>=0.19.5', 'minecraft': '~26.2', 'java': '>=25', 'fabric-api': '*'} breaks {} |

## After the new version started on the same instance

| check | result | detail |
|---|---|---|
| the relaunched client exited normally | PASS | gradle exit 0 |
| the driver verified | PASS | error: None |
| the new RigTune is loaded from mods/ | PASS | loaded 0.3.0-dev+mc26.2 from ['<instance>\\mods\\rigtune-0.3.0-dev+mc26.2.jar'] |
| the goal is kept | PASS | goal: QUALITY |
| the apply result was shown (rigtune.json lastShownApply) | PASS | lastShownApply 2026-09-25T19:28:34.838905600Z vs last-apply.json finishedAt 2026-09-25T19:28:34.838905600Z |
| no further RigTune update is offered | PASS | online=True updateOffered=False |
| no crash report | PASS | crash-reports: [] |
| mods unchanged by the relaunch | PASS | unchanged |
| no new pending.json | PASS |  |
| history.json: the old version's own update, both changes APPLIED | PASS | entries: ['apply']; changes: [('file', 'disable', 'rigtune-0.2.0+mc26.2.jar', 'APPLIED'), ('file', 'enable', 'rigtune-0.3.0-dev+mc26.2.jar', 'APPLIED')]; statuses unchanged by the relaunch: True |

## Files

- `captured-helper.log`
- `captured-last-apply.json`
- `captured-pending.json`
- `captured-rigtune.json`
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
- `history-after-update.json`
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
