# AC5.8 C re-run C3r (P5-C)

- Script finished: True; capture on at 8070, tp at session 23 s, save-all at session 53 s, Stutter Doctor opened at session 63 s (latest.log has 1 s resolution)
- Session: 63.4 s long, 52.6 s of gameplay, 137699 frames, avg 2619.9 FPS, 1% low 332.2 FPS, collector G1, phase timing True
- Spikes: 14 ({'minor': 14, 'major': 0, 'severe': 0, 'freeze': 0}), hitches 13, lost 343.1 ms
- Causes (share of lost time): {'gc': 0.05, 'chunkBuild': 0.07, 'unknown': 0.89}
- Tags (spike counts): {'afterTeleport': 14, 'chunksLoading': 11}
- GC pauses in the JVM log: 167 (longest 15.65 ms); session start = capture-on line + 0.57 s (fitted so GC-noted spikes overlap a pause; latest.log has 1 s resolution)

## The 10 worst spikes (stutter.json `worst`)

| t (s) | ms | notes | in the 30 s after the tp | chunks-loading tag or chunk-load note | JVM pauses overlapping the spike |
|---|---|---|---|---|---|
| 23.3 | 33.5 | render:low, afterTeleport:context, chunksLoading:context | yes | yes | Pause Young 9.75 ms ending at 23.22 s; Pause Young 9.42 ms ending at 23.25 s |
| 29.9 | 29.0 | render:low, afterTeleport:context, chunksLoading:context | yes | yes | - |
| 25.7 | 28.8 | render:low, afterTeleport:context, chunksLoading:context | yes | yes | - |
| 26.8 | 28.2 | render:low, afterTeleport:context, chunksLoading:context | yes | yes | Pause Young 6.01 ms ending at 26.75 s |
| 10.7 | 27.4 | render:low, afterTeleport:context | no | no | - |
| 26.4 | 23.9 | chunkBuild:medium, afterTeleport:context, chunksLoading:context | yes | yes | Pause Remark 3.17 ms ending at 26.34 s; Pause Cleanup 0.04 ms ending at 26.39 s |
| 26.7 | 22.9 | gc:low, render:low, afterTeleport:context, chunksLoading:context | yes | yes | Pause Young 6.01 ms ending at 26.75 s |
| 23.7 | 22.5 | render:low, afterTeleport:context, chunksLoading:context | yes | yes | Pause Young 6.45 ms ending at 23.65 s |
| 27.4 | 22.3 | render:low, afterTeleport:context, chunksLoading:context | yes | yes | - |
| 10.1 | 22.3 | render:low, afterTeleport:context | no | no | - |

## AC5.8 C as amended (SPEC, fix-8b + P5C-F1 refinement 427f4a7)

| check | result |
|---|---|
| every hitch after the teleport carries "after teleport" | PASS (14 of 14 spikes carry it) |
| "chunks loading" from the first chunk load after the teleport on | PASS (first tagged post-teleport spike at 23.3 s; untagged before it: none; untagged after it: none; spikes outside the worst-10 list: 4, of them untagged: 1 (no timestamp in stutter.json)) |
| "N of M spikes happened while chunks were loading", chunk loads > 0 | PASS (11 of 14) |
| chunk loading/building claimed in ms only where measured | PASS (claims {'chunkBuild': 0.07} with notes ['chunkBuild:medium']) |
| nothing claimed as GC without an overlapping pause (every worst spike vs the JVM's GC log) | PASS  |
| the save window is recorded | save-all at session 53 s (log excerpt); spikes tagged world save: 0 |
| the unexplained remainder is shown | PASS (not explained 0.89) |
