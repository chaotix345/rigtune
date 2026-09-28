# Stale-group E2E (AC2H.6): r4-app-reinstalled

- Verdict: **PASS**
- Run: 20260928T014859Z UTC, MC 26.2, a fresh scratch instance with rigtune-0.5.0-dev+mc26.2.jar + fabric-api-0.161.0+26.2.jar, seeded
- RigTune: `rigtune-0.5.0-dev+mc26.2.jar` version 0.5.0-dev+mc26.2, sha256 `5b4753a88033d1b88da0f9607e699e5542e0fd8f6bf49990501322aad95f7a8b`
- Seeded from `<repo>\tools\e2e\seeds\v010-dh-app-reinstalled`: The real instance's shape after the Modrinth App reinstalled Distant Horizons (docs/v0.5/SPEC.md AC2H.6, WS-H's RW-3): the same 0.1.0 pending.json and last-apply.json as v010-dh (RigTune's DH update group: disable fabric-26.2.jar, enable its downloaded 3.3.2), but DH 3.3.2 sits at the group's target name, RigTune's download is gone, there is no mods/update, and fabric-26.2.jar is removed. The group can never run: the new version drops it at launch as already installed (ABANDONED).
- Fake jars: `mods/DistantHorizons-3.3.2-26.2-fabric-neoforge.jar` (distanthorizons 3.3.2)
- Client time: stale-check 28 s

## AC2H.6: the new version's first start on the seeded state, and its exit

| check | result | detail |
|---|---|---|
| the client exited normally | PASS | gradle exit 0 |
| the stale group is dropped | PASS | driver ok: True; carried-over ops still in pending.json: [] |
| the drop is announced (status line) | PASS | statuses seen: [(['rigtune.status.stale_installed'], 'rigtune.status.stale_installed'), (['rigtune.status.stale_installed'], 'RigTune dropped its pending change to Distant Horizons: it is already installed (DistantHorizons-3.3.2-26.2-fabric-neoforge.jar).')] |
| History marks the dropped changes ABANDONED or DISCARDED | PASS | statuses by op id: {'041919d9-18c9-4b04-89c4-f15e4e4a80c5': ['ABANDONED'], 'db7f487d-6371-43c5-944e-c053428ad70d': ['ABANDONED']} |
| latest.log: counted as never runnable, not as leftover to retry | PASS | never-run lines: ['[11:49:15] [main/INFO]: 2 staged RigTune change(s) can never run (the download is gone, or the mod is installed another way); RigTune drops them once the game has started', '[11:49:24] [RigTune worker/INFO]: Unstaged 2 RigTune change(s) that can never run: [Stale[opId=db7f487d-6371-43c5-944e-c053428ad70d, why=INSTALLED, modId=distanthorizons, file=DistantHorizons-3.3.2-26.2-fabric-neoforge.jar, installedAs=DistantHorizons-3.3.2-26.2-fabric-neoforge.jar]]']; will-be-retried lines: [] |
| no helper at exit, mods/ unchanged | PASS | helper runs: 0; mods/: unchanged |
| no .bad file, no crash report | PASS | .bad: []; crash-reports: [] |

## Files

- `catalog.json`
- `checks.json`
- `driver-stale-check.json`
- `e2e-stale-check-1-history.png`
- `e2e.log`
- `helper-cmdlines-stale-check.txt`
- `history-after-stale-check.json`
- `last-apply-after-stale-check.json`
- `latest-stale-check.filtered.log`
- `mods-after-stale-check.json`
- `redirect-probe.txt`
- `requests.jsonl`
- `seeded-last-apply.json`
- `seeded-pending.json`
