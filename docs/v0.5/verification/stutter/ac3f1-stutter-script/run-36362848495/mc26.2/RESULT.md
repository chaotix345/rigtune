# Stutter Doctor dev script: stutter-script-26.2-ff877b

- MC 26.2; verdict **FAIL**
- Facts: `{"tpSession": 27, "spikes": {"minor": 0, "major": 4, "severe": 12, "freeze": 0}, "tags": {"worldSave": 1, "cpuContention": 14, "afterTeleport": 4, "chunksLoading": 13}, "causes": {"gc": 0.85, "unknown": 0.15}, "gcPauses": 94, "gcOffsetSeconds": 0.56, "collector": "G1", "avgFps": 16.9}`

| check | result | detail |
|---|---|---|
| the client exited normally | PASS | gradle exit 0 |
| the dev script finished (monitor session saved) | PASS | PASSED line: True; monitor sessions in stutter.json: 1 |
| the spikes after the teleport carry "after teleport" | **FAIL** | tp at session 27 s; listed spikes in the 30 s after it: 6; tagged after teleport: 4 |
| "chunks loading" from the first chunk load after the teleport on | **FAIL** | tagged: 13; first at 26.3 s; untagged after it: [57.7] |
| no GC milliseconds claimed without an overlapping pause | PASS | 94 GC pauses in the JVM log; GC-noted spikes [26.3, 21.1, 15.7, 10.6, 57.7, 52.5, 42.0, 36.8, 47.2]; without a pause: [] (capture start = its log line + 0.56 s) |
| the unexplained remainder is shown | PASS | causes {'gc': 0.85, 'unknown': 0.15} |
