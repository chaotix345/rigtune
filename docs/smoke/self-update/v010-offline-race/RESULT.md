# Self-update E2E: v010-to-dev

- Verdict: **FAIL**
- Run: 20260925T015908Z UTC, MC 26.2, rigtune-0.1.0.jar + fabric-api-0.161.0+26.2.jar in a fresh scratch instance
- Old: `rigtune-0.1.0.jar` version 0.1.0, sha256 `8294d04a6b67e76dcff298366be38f85048ebf19a120baa9e8ed5b08b2e4b950`
- New (served by the fake Modrinth): `rigtune-0.2.0-dev+mc26.2.jar` version 0.2.0-dev+mc26.2, sha256 `793e85787d5c705ac7e101f2e0296a1d6e02d7200832b498be2c6163fd09e551`
- Client time: update phase 202 s, verify phase None s

## After the old version applied the update and quit (helper done)

| check | result | detail |
|---|---|---|
| the client exited normally and the helper finished | PASS | gradle exit 0, helper finished: True |
| the driver applied the offered update | **FAIL** | driver: ok=False error=no update:rigtune recommendation within 180 s (report online=false) offered=None |
| exactly one RigTune jar, the new one | **FAIL** | rigtune*.jar in mods: ['rigtune-0.1.0.jar']; bytes identical to the served jar: False |
| the old jar is disabled | **FAIL** | rigtune-0.1.0.jar.disabled exists: False |
| no pending.json | PASS | pending.json exists: False |
| no leftover downloads | PASS | *.rigtune-pending: [] |
| last-apply.json: the update's two ops, all OK | **FAIL** | results: [] |
| the helper ran from config/rigtune/helper copies | **FAIL** | helper classpaths seen: none |
| the old RigTune downloaded the jar from cdn.modrinth.com | **FAIL** | download requests: [] |
| the update's depends and breaks are no stricter (M12) | PASS | same or fewer entries: depends {'fabricloader': '>=0.19.5', 'minecraft': '~26.2', 'java': '>=25', 'fabric-api': '*'} breaks {} |

## After the new version started on the same instance

| check | result | detail |
|---|---|---|
| (not run) | | |

## Files

- `captured-rigtune.json`
- `captured-rules-cache.json`
- `catalog.json`
- `checks.json`
- `driver-update.json`
- `e2e.log`
- `helper-cmdlines.txt`
- `helper-dir.txt`
- `latest-update.filtered.log`
- `mods-after-update.json`
- `redirect-probe.txt`
- `report-update.txt`
- `requests.jsonl`
