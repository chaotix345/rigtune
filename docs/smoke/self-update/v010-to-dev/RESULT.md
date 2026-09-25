# Self-update E2E: v010-to-dev

- Verdict: **PASS**
- Run: 20260925T020701Z UTC, MC 26.2, rigtune-0.1.0.jar + fabric-api-0.161.0+26.2.jar in a fresh scratch instance
- Old: `rigtune-0.1.0.jar` version 0.1.0, sha256 `8294d04a6b67e76dcff298366be38f85048ebf19a120baa9e8ed5b08b2e4b950`
- New (served by the fake Modrinth): `rigtune-0.2.0-dev+mc26.2.jar` version 0.2.0-dev+mc26.2, sha256 `793e85787d5c705ac7e101f2e0296a1d6e02d7200832b498be2c6163fd09e551`
- Client time: update phase 22 s, verify phase 20 s
- Rescan pressed because the report stayed offline (the startup lookup race, docs/v0.2/design/ws-g.md): update phase no, verify phase no

## After the old version applied the update and quit (helper done)

| check | result | detail |
|---|---|---|
| the client exited normally and the helper finished | PASS | gradle exit 0, helper finished: True |
| the driver applied the offered update | PASS | driver: ok=True error=None offered=rigtune-0.2.0-dev+mc26.2.jar |
| exactly one RigTune jar, the new one | PASS | rigtune*.jar in mods: ['rigtune-0.2.0-dev+mc26.2.jar']; bytes identical to the served jar: True |
| the old jar is disabled | PASS | rigtune-0.1.0.jar.disabled exists: True |
| no pending.json | PASS | pending.json exists: False |
| no leftover downloads | PASS | *.rigtune-pending: [] |
| last-apply.json: the update's two ops, all OK | PASS | results: [('DISABLE_FILE', 'rigtune-0.1.0.jar', 'OK'), ('ENABLE_FILE', 'rigtune-0.2.0-dev+mc26.2.jar', 'OK')] |
| the helper ran from config/rigtune/helper copies | PASS | helper classpaths seen: [['<instance>\\config\\rigtune\\helper\\0-rigtune-0.1.0.jar', '<instance>\\config\\rigtune\\helper\\1-gson-2.14.0.jar']] |
| the old RigTune downloaded the jar from cdn.modrinth.com | PASS | download requests: [('cdn.modrinth.com', 200, 'chaotix345/rigtune/0.1.0 (github.com/chaotix345/rigtune)')] |
| the update's depends and breaks are no stricter (M12) | PASS | same or fewer entries: depends {'fabricloader': '>=0.19.5', 'minecraft': '~26.2', 'java': '>=25', 'fabric-api': '*'} breaks {} |

## After the new version started on the same instance

| check | result | detail |
|---|---|---|
| the relaunched client exited normally | PASS | gradle exit 0 |
| the driver verified | PASS | error: None |
| the new RigTune is loaded from mods/ | PASS | loaded 0.2.0-dev+mc26.2 from ['<instance>\\mods\\rigtune-0.2.0-dev+mc26.2.jar'] |
| the goal is kept | PASS | goal: QUALITY |
| the apply result was shown (rigtune.json lastShownApply) | PASS | lastShownApply 2026-09-25T02:07:33.180641400Z vs last-apply.json finishedAt 2026-09-25T02:07:33.180641400Z |
| no further RigTune update is offered | PASS | online=True updateOffered=False |
| no crash report | PASS | crash-reports: [] |
| mods unchanged by the relaunch | PASS | unchanged |
| no new pending.json | PASS |  |

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
- `helper-cmdlines.txt`
- `helper-dir.txt`
- `latest-update.filtered.log`
- `latest-verify.filtered.log`
- `mods-after-update.json`
- `mods-after-verify.json`
- `redirect-probe.txt`
- `report-update.txt`
- `report-verify.txt`
- `requests.jsonl`
