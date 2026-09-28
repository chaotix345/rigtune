# Stutter Doctor dev script: stutter-script-26.2-bbbb13

- MC 26.2; verdict **PASS**
- Facts: `{"tpSession": 27, "spikes": {"minor": 0, "major": 0, "severe": 15, "freeze": 0}, "tags": {"worldSave": 1, "cpuContention": 14, "afterTeleport": 6, "chunksLoading": 12}, "causes": {"gc": 0.86, "chunkLoad": 0.01, "unknown": 0.13}, "gcPauses": 91, "gcOffsetSeconds": 0.76, "collector": "G1", "avgFps": 16.1}`

| check | result | detail |
|---|---|---|
| the client exited normally | PASS | gradle exit 0 |
| the dev script finished (monitor session saved) | PASS | PASSED line: True; monitor sessions in stutter.json: 1 |
| the spikes after the teleport carry "after teleport" | PASS | tp at session 27 s; listed spikes in its window: [31.5]; tagged after teleport: [31.5] (session total 6) |
| "chunks loading" from the first chunk load after the teleport on | PASS | tagged: 12; first at 31.5 s; untagged after it: [] |
| no GC milliseconds claimed without an overlapping pause | PASS | 91 GC pauses in the JVM log; GC-noted spikes [26.4, 21.0, 19.8, 15.7, 31.5, 57.9, 10.5, 63.1, 52.5, 47.3]; without a pause: [] (capture start = its log line + 0.76 s) |
| the unexplained remainder is shown | PASS | causes {'gc': 0.86, 'chunkLoad': 0.01, 'unknown': 0.13} |
