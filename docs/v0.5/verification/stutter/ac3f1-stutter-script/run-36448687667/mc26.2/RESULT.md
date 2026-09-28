# Stutter Doctor dev script: stutter-script-26.2-7f18e3

- MC 26.2; verdict **FAIL**
- Facts: `{"tpSession": 31, "spikes": {"minor": 0, "major": 0, "severe": 30, "freeze": 1}, "tags": {"cpuContention": 31, "afterTeleport": 11, "chunksLoading": 30}, "causes": {"gc": 0.53, "chunkLoad": 0.0, "tick": 0.02, "unknown": 0.44}, "gcPauses": 93, "gcOffsetSeconds": 0.5, "collector": "G1", "avgFps": 13.2}`

| check | result | detail |
|---|---|---|
| the client exited normally | PASS | gradle exit 0 |
| the dev script finished (monitor session saved) | PASS | PASSED line: True; monitor sessions in stutter.json: 1 |
| the spikes after the teleport carry "after teleport" | PASS | tp at session 31 s (world entry 11 s); tagged near the tp: [31.8]; untagged inside its window: []; tagged outside both windows: [] |
| "chunks loading" from the first chunk load after the teleport on | **FAIL** | tagged: 30; first at None s, last at None s; untagged between them: [] |
| no GC milliseconds claimed without an overlapping pause | PASS | 93 GC pauses in the JVM log; GC-noted spikes [26.7, 21.2, 15.9, 58.1, 63.4, 68.7, 31.8, 47.5, 52.8]; without a pause: [] (capture start = its log line + 0.50 s) |
| the unexplained remainder is shown | PASS | causes {'gc': 0.53, 'chunkLoad': 0.0, 'tick': 0.02, 'unknown': 0.44} |
