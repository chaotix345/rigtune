# Self-update E2E: dev-v010-seeded-to-030-wsb

- Verdict: **PASS**
- Run: 20260925T191719Z UTC, MC 26.2, rigtune-0.1.0.jar + fabric-api-0.161.0+26.2.jar in a fresh scratch instance
- Old: `rigtune-0.1.0.jar` version 0.1.0, sha256 `8294d04a6b67e76dcff298366be38f85048ebf19a120baa9e8ed5b08b2e4b950`
- New (served by the fake Modrinth): `rigtune-0.3.0-dev+mc26.2.jar` version 0.3.0-dev+mc26.2, sha256 `d5b50dbcf982c5446b6167167d8bd5f8c218172a494f3628ff33577af6698f68`
- Seeded (plan review H-M2) from `<repo>\tools\e2e\seeds\v010-dh`: The user's 0.1.0 instance on 2026-09-26 (plan review H-M2): RigTune's Distant Horizons update group (disable fabric-26.2.jar = DH 3.3.0, enable its downloaded 3.3.2) failed once because DH's own updater held the jar, and DH's own 3.3.2 build waits in mods/update/. pending.json and last-apply.json are templated read-only copies (source.json); the jars are minimal fakes with the fabric.mod.json id distanthorizons.
- Fake jars: `mods/fabric-26.2.jar` (distanthorizons 3.3.0), `mods/DistantHorizons-3.3.2-26.2-fabric-neoforge.jar.rigtune-pending` (distanthorizons 3.3.2), `mods/update/DistantHorizons-3.3.2 - 26.2 neo/fabric-26.2.jar` (distanthorizons 3.3.2)
- Held open during the old version's exit: `mods/fabric-26.2.jar`. In the real failure a second process (DH's own updater) had fabric-26.2.jar open when the 0.1.0 helper tried to rename it. The harness keeps the fake jar open (Windows: no FILE_SHARE_DELETE, so the rename fails the same way) from the old version's launch until its helper is done, so the retry fails again and the group reaches the new version, as H-M2 requires.
- Rescan pressed because the report stayed offline (the startup lookup race, docs/v0.2/design/ws-g.md): update phase no, verify phase no
- Client time: update 24 s, verify 28 s

## After the old version applied the update and quit (helper done)

| check | result | detail |
|---|---|---|
| the client exited normally and the helper finished | PASS | gradle exit 0, helper finished: True |
| the driver applied the offered update | PASS | driver: ok=True error=None offered=rigtune-0.3.0-dev+mc26.2.jar |
| exactly one RigTune jar, the new one | PASS | rigtune*.jar in mods: ['rigtune-0.3.0-dev+mc26.2.jar']; bytes identical to the served jar: True |
| the old jar is disabled | PASS | rigtune-0.1.0.jar.disabled exists: True |
| pending.json holds only the carried-over group, one attempt more | PASS | ops (id, attempts): [('041919d9-18c9-4b04-89c4-f15e4e4a80c5', 2), ('db7f487d-6371-43c5-944e-c053428ad70d', 2)]; expected [('041919d9-18c9-4b04-89c4-f15e4e4a80c5', 2), ('db7f487d-6371-43c5-944e-c053428ad70d', 2)] |
| no leftover downloads but the carried-over group's | PASS | *.rigtune-pending: ['DistantHorizons-3.3.2-26.2-fabric-neoforge.jar.rigtune-pending']; expected ['DistantHorizons-3.3.2-26.2-fabric-neoforge.jar.rigtune-pending'] |
| last-apply.json: the update's two ops OK, the carried-over ops FAILED | PASS | results: [('DISABLE_FILE', 'fabric-26.2.jar', 'FAILED'), ('ENABLE_FILE', 'DistantHorizons-3.3.2-26.2-fabric-neoforge.jar', 'FAILED'), ('DISABLE_FILE', 'rigtune-0.1.0.jar', 'OK'), ('ENABLE_FILE', 'rigtune-0.3.0-dev+mc26.2.jar', 'OK')] |
| the helper ran from config/rigtune/helper copies | PASS | helper classpaths seen: [['<instance>\\config\\rigtune\\helper\\0-rigtune-0.1.0.jar', '<instance>\\config\\rigtune\\helper\\1-gson-2.14.0.jar']] |
| the old RigTune downloaded the jar from cdn.modrinth.com | PASS | download requests: [('cdn.modrinth.com', 200, 'chaotix345/rigtune/0.1.0 (github.com/chaotix345/rigtune)')] |
| the update's depends and breaks are no stricter (M12) | PASS | same or fewer entries: depends {'fabricloader': '>=0.19.5', 'minecraft': '~26.2', 'java': '>=25', 'fabric-api': '*'} breaks {} |

## After the new version started on the same instance

| check | result | detail |
|---|---|---|
| the relaunched client exited normally | PASS | gradle exit 0 |
| the driver verified | PASS | error: None |
| the new RigTune is loaded from mods/ | PASS | loaded 0.3.0-dev+mc26.2 from ['<instance>\\mods\\rigtune-0.3.0-dev+mc26.2.jar'] |
| the goal is kept | PASS | goal: QUALITY |
| the apply result was shown (rigtune.json lastShownApply) | PASS | lastShownApply 2026-09-25T19:17:59.186898900Z vs last-apply.json finishedAt 2026-09-25T19:17:59.186898900Z |
| no further RigTune update is offered | PASS | online=True updateOffered=False |
| no crash report | PASS | crash-reports: [] |
| no new pending.json | PASS |  |
| history.json: one legacy import, without RigTune's own jars | PASS | history.json entries: 1; legacy-import entries: 1; RigTune changes: []; expected disables missing: []; imported changes: [[{'id': '86bb4f9e-c556-4945-8789-7838c9998b1a', 'type': 'file', 'action': 'disable', 'modId': 'distanthorizons', 'file': 'fabric-26.2.jar', 'status': 'DISCARDED', 'opId': '041919d9-18c9-4b04-89c4-f15e4e4a80c5', 'group': '7b8043de-f5c3-4881-8309-27265240f39e'}, {'id': 'ba473c04-78cd-4ab2-8784-5fc38f1c200b', 'type': 'file', 'action': 'enable', 'modId': 'distanthorizons', 'file': 'DistantHorizons-3.3.2-26.2-fabric-neoforge.jar', 'status': 'DISCARDED', 'opId': 'db7f487d-6371-43c5-944e-c053428ad70d', 'group': '7b8043de-f5c3-4881-8309-27265240f39e'}]] |
| the carried-over group is dropped | PASS | pending.json absent; carried-over ops still in it: [] |
| the drop is announced (status notice) | PASS | statuses seen: [('rigtune.status.queued_update_dropped', 'rigtune.status.queued_update_dropped'), ('rigtune.status.queued_update_dropped', "Cancelled RigTune's pending update of Distant Horizons: it has an update of its own waiting in mods/update.")] |
| history.json: the carried-over changes are DISCARDED | PASS | statuses by op id: {'041919d9-18c9-4b04-89c4-f15e4e4a80c5': ['DISCARDED'], 'db7f487d-6371-43c5-944e-c053428ad70d': ['DISCARDED']} |
| latest.log: a WARN line per failed op, with its attempt (3e) | PASS | ops with a line of their own: 2 of 2; failed ops: [('DISABLE_FILE', ['fabric-26.2.jar']), ('ENABLE_FILE', ['DistantHorizons-3.3.2-26.2-fabric-neoforge.jar', 'DistantHorizons-3.3.2-26.2-fabric-neoforge.jar.rigtune-pending'])]; WARN lines with an attempt: ["[05:18:09] [main/WARN]: RigTune could not apply a change at the last exit (attempt 2 of 3; it's retried at the next exit): DISABLE_FILE fabric-26.2.jar: Gave up after 10 attempt(s): java.nio.file.FileSystemException: fabric-26.2.jar -> fabric-26.2.jar.disabled: The process cannot access the file because it is being used by another process", "[05:18:09] [main/WARN]: RigTune could not apply a change at the last exit (attempt 2 of 3; it's retried at the next exit): ENABLE_FILE distanthorizons (DistantHorizons-3.3.2-26.2-fabric-neoforge.jar): Not applied because disabling fabric-26.2.jar failed"] |
| nothing in mods/ changes at exit | PASS | helper runs at exit: 0; mods/ at quit -> after exit: unchanged |
| the installed and queued jars are untouched, RigTune's build not enabled | PASS | unchanged: {'fabric-26.2.jar': True, 'update/DistantHorizons-3.3.2 - 26.2 neo/fabric-26.2.jar': True}; RigTune's build enabled: [] |
| during the session mods/ changed only by retiring the dropped download | PASS | removed ['DistantHorizons-3.3.2-26.2-fabric-neoforge.jar.rigtune-pending'], added ['DistantHorizons-3.3.2-26.2-fabric-neoforge.jar.rigtune-superseded'], changed [] |

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
- `helper-cmdlines-verify.txt`
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
- `seeded-last-apply.json`
- `seeded-pending.json`
