# AC5.8 C re-run C1r2 (P5-C)

- Script finished: True; capture on at 8262, tp at session 22 s, save-all at session 52 s, Stutter Doctor opened at session 62 s (latest.log has 1 s resolution)
- Session: 62.8 s long, 51.8 s of gameplay, 5307 frames, avg 102.4 FPS, 1% low 29.8 FPS, collector G1, phase timing True
- Spikes: 8 ({'minor': 8, 'major': 0, 'severe': 0, 'freeze': 0}), hitches 8, lost 129.7 ms
- Causes (share of lost time): {'gc': 0.0, 'unknown': 1.0}
- Tags (spike counts): {'afterTeleport': 8, 'chunksLoading': 7}
- GC pauses in the JVM log: 142 (longest 13.95 ms); session start = capture-on line + 0.48 s (fitted so GC-noted spikes overlap a pause; latest.log has 1 s resolution)

## The 10 worst spikes (stutter.json `worst`)

| t (s) | ms | notes | in the 30 s after the tp | chunks-loading tag or chunk-load note | JVM pauses overlapping the spike |
|---|---|---|---|---|---|
| 10.5 | 30.5 | render:low, afterTeleport:context, chunksLoading:context | no | yes | - |
| 25.0 | 26.6 | render:low, afterTeleport:context, chunksLoading:context | yes | yes | Pause Young 8.34 ms ending at 24.97 s |
| 26.6 | 26.1 | gc:low, render:low, afterTeleport:context, chunksLoading:context | yes | yes | Pause Young 5.75 ms ending at 26.53 s |
| 29.0 | 24.1 | render:low, afterTeleport:context, chunksLoading:context | yes | yes | - |
| 22.6 | 23.7 | render:low, afterTeleport:context, chunksLoading:context | yes | yes | Pause Young 6.95 ms ending at 22.62 s |
| 26.2 | 23.1 | render:low, afterTeleport:context, chunksLoading:context | yes | yes | - |
| 26.4 | 21.4 | render:low, afterTeleport:context, chunksLoading:context | yes | yes | - |
| 22.0 | 20.9 | render:low, afterTeleport:context | yes | no | - |

## AC5.8 C as amended (SPEC, fix-8b + P5C-F1 refinement 427f4a7)

| check | result |
|---|---|
| every hitch after the teleport carries "after teleport" | PASS (8 of 8 spikes carry it) |
| "chunks loading" from the first chunk load after the teleport on | PASS (first tagged post-teleport spike at 22.6 s; untagged before it: [22.0]; untagged after it: none; spikes outside the worst-10 list: 0, of them untagged: 0) |
| "N of M spikes happened while chunks were loading", chunk loads > 0 | PASS (7 of 8) |
| chunk loading/building claimed in ms only where measured | PASS (no chunk claims: the time stays not explained) |
| nothing claimed as GC without an overlapping pause (every worst spike vs the JVM's GC log) | PASS  |
| the save window is recorded | save-all at session 52 s (log excerpt); spikes tagged world save: 0 |
| the unexplained remainder is shown | PASS (not explained 1.0) |
