# Helper-kill E2E: r2-helper-kill

- Verdict: **PASS**
- Run: 20260928T011030Z UTC, MC 26.2, a fresh scratch instance with rigtune-0.5.0-dev+mc26.2.jar + fabric-api-0.161.0+26.2.jar + `e2e-kill-1.0.0.jar`, and `e2e-kill-1.1.0.jar` staged to replace it (group 7d1f3a52-0c4e-4b6a-9e21-00000000c001)
- RigTune: `rigtune-0.5.0-dev+mc26.2.jar` version 0.5.0-dev+mc26.2, sha256 `5b4753a88033d1b88da0f9607e699e5542e0fd8f6bf49990501322aad95f7a8b`
- Op 2 (`e2e-kill-1.1.0.jar.rigtune-pending`) held (an open handle) until the helper was killed 1.5 s after it recorded the group (`kill.json`)
- Client time: kill-first 29 s, kill-second 19 s, kill-check 19 s

## helper-kill: after a start and quit with the group staged, op 2 blocked, the helper killed during its retries

| check | result | detail |
|---|---|---|
| the first client exited normally | PASS | gradle exit 0 |
| the helper was killed during its retries (it wrote no results) | PASS | killed [5668]; the group in unfinished-groups.json when killed: True; gone after: True; last-apply.json after: False |
| unfinished-groups.json and pending.json still hold the group | PASS | record holds it: True; pending ops ['7d1f3a52-0c4e-4b6a-9e21-00000000c002', '7d1f3a52-0c4e-4b6a-9e21-00000000c003'] |
| the blocked op didn't happen | PASS | mods/: ['e2e-kill-1.0.0.jar', 'e2e-kill-1.1.0.jar.rigtune-pending', 'fabric-api-0.161.0+26.2.jar', 'rigtune-0.5.0-dev+mc26.2.jar'] |

## helper-kill: after the next start and quit (the next helper, op 2 unblocked)

| check | result | detail |
|---|---|---|
| the client exited normally and the helper finished | PASS | gradle exit 0, helper finished: True |
| the game started on what the killed helper left | PASS | error: None; loaded ['e2e-kill', 'fabric-advancement-api-v1', 'fabric-api', 'fabric-api-base', 'fabric-api-lookup-api-v1', 'fabric-biome-api-v1', 'fabric-block-api-v1', 'fabric-block-getter-api-v2', 'fabric-command-api-v2', 'fabric-content-registries-v0', 'fabric-convention-tags-v2', 'fabric-crash-report-info-v1', 'fabric-creative-tab-api-v1', 'fabric-data-attachment-api-v1', 'fabric-data-generation-api-v1', 'fabric-debug-api-v1', 'fabric-dimensions-v1', 'fabric-entity-events-v1', 'fabric-events-interaction-v0', 'fabric-game-rule-api-v1', 'fabric-item-api-v1', 'fabric-key-mapping-api-v1', 'fabric-lifecycle-events-v1', 'fabric-loot-api-v3', 'fabric-menu-api-v1', 'fabric-message-api-v1', 'fabric-model-loading-api-v1', 'fabric-networking-api-v1', 'fabric-object-builder-api-v1', 'fabric-particles-v1', 'fabric-permission-api-v1', 'fabric-recipe-api-v1', 'fabric-registry-sync-v0', 'fabric-renderer-api-v1', 'fabric-renderer-indigo', 'fabric-rendering-fluids-v1', 'fabric-rendering-v1', 'fabric-resource-conditions-api-v1', 'fabric-resource-loader-v0', 'fabric-resource-loader-v1', 'fabric-screen-api-v1', 'fabric-serialization-api-v1', 'fabric-sound-api-v1', 'fabric-tag-api-v1', 'fabric-transfer-api-v1', 'fabric-transitive-access-wideners-v1', 'fabricloader', 'java', 'minecraft', 'mixinextras', 'rigtune', 'rigtune-e2e-undo-driver'] |
| the next helper applied the whole group | PASS | last-apply: {'7d1f3a52-0c4e-4b6a-9e21-00000000c002': 'OK', '7d1f3a52-0c4e-4b6a-9e21-00000000c003': 'OK'} |
| mods/ holds the group's result | PASS | mods/: ['e2e-kill-1.0.0.jar.disabled', 'e2e-kill-1.1.0.jar', 'fabric-api-0.161.0+26.2.jar', 'rigtune-0.5.0-dev+mc26.2.jar'] |
| unfinished-groups.json no longer holds the group, pending.json is done | PASS | record holds it: False; pending ops [] |

## helper-kill: after the next start

| check | result | detail |
|---|---|---|
| the relaunched client exited normally | PASS | gradle exit 0 |
| the next start loads the updated mod | PASS | error: None; e2e-kill loaded: 1.1.0 (expected 1.1.0) |
| History shows the group applied as a whole | PASS | the entry's changes: {'7d1f3a52-0c4e-4b6a-9e21-00000000c011': 'APPLIED', '7d1f3a52-0c4e-4b6a-9e21-00000000c012': 'APPLIED'} |
| no .bad file, no crash report | PASS | .bad: []; crash-reports: [] |

## Files

- `catalog.json`
- `checks.json`
- `driver-kill-check.json`
- `driver-kill-first.json`
- `driver-kill-second.json`
- `e2e-kill-check-1-history.png`
- `e2e-kill-first-1-history.png`
- `e2e-kill-second-1-history.png`
- `e2e.log`
- `helper-after-kill-check.log`
- `helper-after-kill-first.log`
- `helper-after-kill-second.log`
- `helper-cmdlines-kill-first.txt`
- `helper-cmdlines-kill-second.txt`
- `history-after-kill-check.json`
- `history-after-kill-first.json`
- `history-after-kill-second.json`
- `kill.json`
- `last-apply-after-kill-check.json`
- `last-apply-after-kill-second.json`
- `latest-kill-check.filtered.log`
- `latest-kill-first.filtered.log`
- `latest-kill-second.filtered.log`
- `mods-after-kill-check.json`
- `mods-after-kill-first.json`
- `mods-after-kill-second.json`
- `redirect-probe.txt`
- `requests.jsonl`
- `seeded-history.json`
- `seeded-pending.json`
- `unfinished-groups-after-kill.json`
