# Stutter Doctor dev script: stutter-script-26.2-4e2b37

- MC 26.2; verdict **FAIL**
- Facts: `{"tpSession": 26, "spikes": {"minor": 0, "major": 0, "severe": 6, "freeze": 1}, "tags": {"cpuContention": 7, "afterTeleport": 5, "chunksLoading": 6}, "causes": {"gc": 0.78, "unknown": 0.22}, "gcPauses": 71, "gcOffsetSeconds": 0.31, "collector": "G1", "avgFps": 10.8}`

| check | result | detail |
|---|---|---|
| the client exited normally | PASS | gradle exit 0 |
| the dev script finished (monitor session saved) | PASS | PASSED line: True; monitor sessions in stutter.json: 1 |
| the spikes after the teleport carry "after teleport" | PASS | tp at session 26 s (world entry 6 s); tagged near the tp: [31.5, 36.7]; untagged inside its window: []; tagged outside both windows: [] |
| "chunks loading" from the first chunk load after the teleport on | **FAIL** | tagged: 6; first at 26.4 s; untagged after it: [36.7] |
| no GC milliseconds claimed without an overlapping pause | PASS | 71 GC pauses in the JVM log; GC-noted spikes [26.4, 21.1, 15.8, 10.6, 31.5, 36.7]; without a pause: [] (capture start = its log line + 0.31 s) |
| the unexplained remainder is shown | PASS | causes {'gc': 0.78, 'unknown': 0.22} |
