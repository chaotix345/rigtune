# Stutter Doctor dev script: stutter-script-26.3-70d744

- MC 26.3; verdict **PASS**
- Facts: `{"tpSession": 23, "spikes": {"minor": 0, "major": 1, "severe": 11, "freeze": 0}, "tags": {"cpuContention": 7, "afterTeleport": 4, "chunksLoading": 7}, "causes": {"gc": 0.96, "tick": 0.01, "unknown": 0.03}, "gcPauses": 92, "gcOffsetSeconds": 0.3, "collector": "G1", "avgFps": 21.0}`

| check | result | detail |
|---|---|---|
| the client exited normally | PASS | gradle exit 0 |
| the dev script finished (monitor session saved) | PASS | PASSED line: True; monitor sessions in stutter.json: 1 |
| the spikes after the teleport carry "after teleport" | PASS | tp at session 23 s; listed spikes in its window: [26.2, 31.4]; tagged after teleport: [26.2, 31.4] (session total 4) |
| "chunks loading" from the first chunk load after the teleport on | PASS | tagged: 7; first at 26.2 s; untagged after it: [] |
| no GC milliseconds claimed without an overlapping pause | PASS | 92 GC pauses in the JVM log; GC-noted spikes [10.7, 21.1, 15.9, 36.7, 31.4, 26.2, 41.9, 47.1, 57.6, 62.8]; without a pause: [] (capture start = its log line + 0.30 s) |
| the unexplained remainder is shown | PASS | causes {'gc': 0.96, 'tick': 0.01, 'unknown': 0.03} |
