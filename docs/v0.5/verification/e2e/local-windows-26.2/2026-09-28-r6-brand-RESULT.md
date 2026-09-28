# Launcher-brand E2E (AC4j.3): r6-brand

- Verdict: **PASS**
- Run: 20260928T024302Z UTC, MC 26.2, a fresh scratch instance with rigtune-0.5.0-dev+mc26.2.jar + fabric-api-0.161.0+26.2.jar + sodium-mc26.2-0.9.2-fabric.jar, `-Dminecraft.launcher.brand=theseus`
- RigTune: `rigtune-0.5.0-dev+mc26.2.jar` version 0.5.0-dev+mc26.2, sha256 `571f15c30b1caa56bcff6acdea050e10df1a413913d164ec6af79ab29d7baa43`
- Staged before the first start: v040-written's `ws-a` set (0.4's own file group)
- Client time: brand-apply 32 s, brand-cancel 23 s

## AC4j.3, brand theseus: after Apply everything and quit (helper done)

| check | result | detail |
|---|---|---|
| the client exited normally and the helper finished | PASS | gradle exit 0, helper finished: True |
| the driver applied everything the report selects | PASS | error: None; 1 rows: ['set:vanilla.maxFps']; message: Applied 1 setting(s). Restart Minecraft to finish applying 1 change(s). If the Modrinth App syncs game settings, it copies these to your other synced instances. |
| mods/ byte-identical | PASS | unchanged |
| the settings changed | PASS | journal {'vanilla.maxFps': 'APPLIED'}; options.txt {'vanilla.maxFps': (None, 'maxFps:170')} |
| the staged file group is held (helper.log, pending.json) | PASS | helper.log: Held 1 operation(s) of mod-file changes; held ops still pending: 1 of 1 |

## AC4j.3: after the held notice's Cancel them in the next start

| check | result | detail |
|---|---|---|
| the client exited normally | PASS | gradle exit 0 |
| the held notice is shown with Cancel them | PASS | error: None; notice: 1 mod change(s) from an earlier Apply are waiting: Modrinth App manages this instance's mods.; actions ['cancel', 'apply'] |
| no mod-file op left in pending.json | PASS | left: [] |
| the download is superseded and nothing else in mods/ changed | PASS | superseded: {'e2e-seed-1.0.0.jar.rigtune-pending': True}; other changes: []; helper runs at exit: 0 |
| History marks the cancelled changes DISCARDED | PASS | statuses by op id: {'0a4f3c1e-5b7d-4e2a-9c61-7d2f1b8e4a01': 'DISCARDED'} |
| no .bad file, no crash report | PASS | .bad: []; crash-reports: [] |

## Files

- `catalog.json`
- `checks.json`
- `driver-brand-apply.json`
- `driver-brand-cancel.json`
- `e2e-brand-apply-1-rigtune.png`
- `e2e-brand-cancel-1-notice.png`
- `e2e-brand-cancel-2-after.png`
- `e2e.log`
- `helper-after-brand-apply.log`
- `helper-after-brand-cancel.log`
- `helper-cmdlines-brand-apply.txt`
- `history-after-brand-apply.json`
- `history-after-brand-cancel.json`
- `latest-brand-apply.filtered.log`
- `latest-brand-cancel.filtered.log`
- `mods-after-brand-apply.json`
- `mods-after-brand-cancel.json`
- `redirect-probe.txt`
- `requests.jsonl`
