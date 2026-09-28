# Stutter Doctor dev script: stutter-script-26.3-29fb59

- MC 26.3; verdict **FAIL**
- Facts: `{"tpSession": 24, "spikes": {"minor": 0, "major": 0, "severe": 11, "freeze": 1}, "tags": {"cpuContention": 9, "afterTeleport": 3, "chunksLoading": 9}, "causes": {"gc": 0.85, "chunkBuild": 0.01, "unknown": 0.14}, "gcPauses": 80, "gcOffsetSeconds": 0.5, "collector": "G1", "avgFps": 17.4}`

| check | result | detail |
|---|---|---|
| the client exited normally | PASS | gradle exit 0 |
| the dev script finished (monitor session saved) | PASS | PASSED line: True; monitor sessions in stutter.json: 1 |
| the spikes after the teleport carry "after teleport" | **FAIL** | tp at session 24 s; listed spikes in the 30 s after it: 5; tagged after teleport: 3 |
| "chunks loading" from the first chunk load after the teleport on | **FAIL** | tagged: 9; first at 31.6 s; untagged after it: [52.6] |
| no GC milliseconds claimed without an overlapping pause | PASS | 80 GC pauses in the JVM log; GC-noted spikes [21.0, 15.8, 10.6, 31.6, 47.4, 42.1, 52.6, 36.9, 63.1]; without a pause: [] (capture start = its log line + 0.50 s) |
| the unexplained remainder is shown | PASS | causes {'gc': 0.85, 'chunkBuild': 0.01, 'unknown': 0.14} |
