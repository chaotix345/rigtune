# AC5.8 C re-run C1r (P5-C)

- Script finished: True; capture on at 7956, tp at session 22 s, save-all at session 52 s, Stutter Doctor opened at session 62 s (latest.log has 1 s resolution)
- Session: 62.8 s long, 51.9 s of gameplay, 5300 frames, avg 102.2 FPS, 1% low 29.8 FPS, collector G1, phase timing True
- Spikes: 12 ({'minor': 12, 'major': 0, 'severe': 0, 'freeze': 0}), hitches 10, lost 221.8 ms
- Causes (share of lost time): {'gc': 0.1, 'unknown': 0.9}
- Tags (spike counts): {'afterTeleport': 12, 'chunksLoading': 9}
- GC pauses in the JVM log: 134 (longest 18.58 ms); session start = capture-on line + 0.96 s (fitted so GC-noted spikes overlap a pause; latest.log has 1 s resolution)

## The 10 worst spikes (stutter.json `worst`)

| t (s) | ms | notes | in the 30 s after the tp | chunks-loading tag or chunk-load note | JVM pauses overlapping the spike |
|---|---|---|---|---|---|
| 22.2 | 33.8 | gc:low, render:low, afterTeleport:context | yes | no | Pause Young 7.57 ms ending at 22.15 s |
| 23.7 | 31.4 | gc:medium, render:low, afterTeleport:context, chunksLoading:context | yes | yes | Pause Young 10.58 ms ending at 23.72 s |
| 22.5 | 27.9 | gc:low, render:low, afterTeleport:context, chunksLoading:context | yes | yes | Pause Young 5.59 ms ending at 22.52 s |
| 10.6 | 27.9 | render:low, afterTeleport:context, chunksLoading:context | no | yes | - |
| 22.3 | 27.7 | render:low, afterTeleport:context, chunksLoading:context | yes | yes | Pause Remark 8.36 ms ending at 22.33 s |
| 21.9 | 27.4 | gc:low, render:low, afterTeleport:context | yes | no | Pause Young 5.96 ms ending at 21.95 s |
| 25.0 | 26.5 | render:low, afterTeleport:context, chunksLoading:context | yes | yes | - |
| 22.1 | 26.2 | gc:medium, render:low, afterTeleport:context | yes | no | Pause Young 7.57 ms ending at 22.15 s |
| 28.7 | 25.0 | render:low, afterTeleport:context, chunksLoading:context | yes | yes | - |
| 26.3 | 24.4 | render:low, afterTeleport:context, chunksLoading:context | yes | yes | - |

## AC5.8 C as amended (SPEC, fix-8b + P5C-F1 refinement 427f4a7)

| check | result |
|---|---|
| every hitch after the teleport carries "after teleport" | PASS (12 of 12 spikes carry it) |
| "chunks loading" from the first chunk load after the teleport on | PASS (first tagged post-teleport spike at 22.3 s; untagged before it: [21.9, 22.1, 22.2]; untagged after it: none; spikes outside the worst-10 list: 2, of them untagged: 0) |
| "N of M spikes happened while chunks were loading", chunk loads > 0 | PASS (9 of 12) |
| chunk loading/building claimed in ms only where measured | PASS (no chunk claims: the time stays not explained) |
| nothing claimed as GC without an overlapping pause (every worst spike vs the JVM's GC log) | PASS  |
| the save window is recorded | save-all at session 52 s (log excerpt); spikes tagged world save: 0 |
| the unexplained remainder is shown | PASS (not explained 0.9) |
