# Stutter Doctor dev script: stutter-script-26.2-6ca073

- MC 26.2; verdict **FAIL**
- Facts: `{"tpSession": 24, "spikes": {"minor": 0, "major": 0, "severe": 3, "freeze": 0}, "tags": {"cpuContention": 3, "afterTeleport": 1, "chunksLoading": 3}, "causes": {"gc": 0.98, "unknown": 0.02}, "gcPauses": 94, "gcOffsetSeconds": 0.5, "collector": "G1", "avgFps": 10.8}`

| check | result | detail |
|---|---|---|
| the client exited normally | PASS | gradle exit 0 |
| the dev script finished (monitor session saved) | PASS | PASSED line: True; monitor sessions in stutter.json: 1 |
| the spikes after the teleport carry "after teleport" | **FAIL** | tp at session 24 s (world entry 4 s); tagged near the tp: []; untagged inside its window: []; tagged outside both windows: [] |
| "chunks loading" from the first chunk load after the teleport on | **FAIL** | tagged: 3; first at None s, last at None s; untagged between them: [] |
| no GC milliseconds claimed without an overlapping pause | PASS | 94 GC pauses in the JVM log; GC-noted spikes [21.0, 15.8, 10.5]; without a pause: [] (capture start = its log line + 0.50 s) |
| the unexplained remainder is shown | PASS | causes {'gc': 0.98, 'unknown': 0.02} |
