| run | setup | gameplay / frames / avg / 1% low | spikes (hitches), per min | lost | causes (share of lost time) | tags | advice | facts | phase timing, enough data |
|---|---|---|---|---|---|---|---|---|---|
| A | control, G1 -Xmx4G, stand still 6 min; default options (VSync on, maxFps 120, inactivity AFK) | 352 s / 14166 frames / 40 FPS / 1% low 28 | 4 (4 hitches), 0.7/min | 235 ms | gc 3%, unknown 97% | afterTeleport 3 | - | gcOffset 16.8 ms, explicit 0, full 0, stalls 0, liveSet 12% | phase True, enough True |
| A2 | control, G1 -Xmx4G, stand still 150 s; VSync off, maxFps 260, inactivity minimized | 140 s / 381345 frames / 2719 FPS / 1% low 710 | 0 (0 hitches), 0.0/min | 0 ms | - | - | - | gcOffset 17.2 ms, explicit 0, full 0, stalls 0, liveSet 10% | phase True, enough False |
| A3 | control, G1 -Xmx4G, stand still 6.5 min (autosave window); VSync off, uncapped | 383 s / 1015855 frames / 2655 FPS / 1% low 714 | 1 (1 hitches), 0.2/min | 24 ms | unknown 100% | afterTeleport 1 | - | gcOffset 18.2 ms, explicit 0, full 0, stalls 0, liveSet 12% | phase True, enough False |
| B | forceGcEverySec=5, G1 -Xmx4G, 150 s; default options | 140 s / 7976 frames / 57 FPS / 1% low 20 | 28 (28 hitches), 12.0/min | 1507 ms | gc 100%, unknown 0% | - | stutter-gc-explicit | gcOffset 17.3 ms, explicit 29, full 0, stalls 0, liveSet 9% | phase True, enough True |
| C1 | product teleport script (-Drigtune.dev.stutterScript=teleport); default options | 50 s / 5403 frames / 108 FPS / 1% low 29 | 14 (13 hitches), 16.8/min | 285 ms | unknown 100% | afterTeleport 13 | - | gcOffset 19.8 ms, explicit 0, full 0, stalls 0, liveSet 12% | phase True, enough False |
| C2 | driver: 100 s still, tp, 30 s, save, 10 s; default options (AFK throttle) | 131 s / 7759 frames / 59 FPS / 1% low 30 | 0 (0 hitches), 0.0/min | 0 ms | - | - | - | gcOffset 17.1 ms, explicit 0, full 0, stalls 0, liveSet 10% | phase True, enough False |
| C3 | product teleport script; VSync off, uncapped, inactivity minimized | 52 s / 155692 frames / 2967 FPS / 1% low 433 | 12 (11 hitches), 13.7/min | 310 ms | gc 6%, unknown 94% | afterTeleport 12 | - | gcOffset 17.6 ms, explicit 0, full 0, stalls 0, liveSet 12% | phase True, enough False |
| C4 | driver: 100 s still, tp, 30 s, save, 10 s; VSync off, uncapped | 131 s / 372135 frames / 2850 FPS / 1% low 376 | 18 (14 hitches), 8.3/min | 533 ms | gc 0%, unknown 100% | afterTeleport 16 | - | gcOffset 17.1 ms, explicit 0, full 0, stalls 0, liveSet 10% | phase True, enough True |
| D | ZGC + forceGcEverySec=5, -Xmx4G, 150 s; default options | 140 s / 8082 frames / 58 FPS / 1% low 30 | 0 (0 hitches), 0.0/min | 0 ms | - | - | - | gcOffset 30.0 ms, explicit 29, full 0, stalls 0, liveSet 13% | phase True, enough False |
| D2 | ZGC + forceGcEverySec=5, -Xmx4G, 150 s; VSync off, uncapped, -Xlog:gc* | 140 s / 382315 frames / 2722 FPS / 1% low 730 | 0 (0 hitches), 0.0/min | 0 ms | - | - | - | gcOffset 23.1 ms, explicit 28, full 0, stalls 0, liveSet 13% | phase True, enough False |
| SM1 | S-M1 attempt 1 (@Redirect test mixin) | 32 s / 3832 frames / 119 FPS / 1% low 93 | 3 (3 hitches), 5.6/min | 53 ms | gc 12%, unknown 88% | afterTeleport 3 | - | gcOffset 17.2 ms, explicit 0, full 0, stalls 0, liveSet 11% | phase True, enough False |
| SM1b | S-M1 attempt 2 (mixin-config plugin removes the INVOKE target) | 32 s / 3819 frames / 119 FPS / 1% low 101 | 1 (1 hitches), 1.9/min | 17 ms | gc 30%, unknown 70% | afterTeleport 1 | - | gcOffset 17.1 ms, explicit 0, full 0, stalls 0, liveSet 11% | phase False, enough False |

**A** worst spikes (t s, ms, baseline ms, notes):
- 159.1 s, 195.3 ms (base 33.3): 
- 9.9 s, 39.4 ms (base 8.3): render:low, afterTeleport:context
- 11.1 s, 34.6 ms (base 8.3): gc:medium, render:low, afterTeleport:context
- 10.4 s, 24.4 ms (base 8.3): render:low, afterTeleport:context

**A2** worst spikes (t s, ms, baseline ms, notes):
- GC log: 136 pauses in the JVM, longest 17.2 ms; capture started at JVM uptime ~19 s (1 s log precision)

**A3** worst spikes (t s, ms, baseline ms, notes):
- 11.6 s, 24.8 ms (base 0.5): render:low, afterTeleport:context
- GC log: 358 pauses in the JVM, longest 14.7 ms; capture started at JVM uptime ~23 s (1 s log precision)

**B** worst spikes (t s, ms, baseline ms, notes):
- 86.1 s, 89.2 ms (base 33.3): gc:high:FULL:EXPLICIT
- 70.9 s, 88.8 ms (base 33.3): gc:high:FULL:EXPLICIT
- 121.5 s, 86.8 ms (base 33.3): gc:high:FULL:EXPLICIT
- 65.9 s, 86.8 ms (base 33.3): gc:high:FULL:EXPLICIT
- 136.6 s, 85.1 ms (base 33.3): gc:high:FULL:EXPLICIT
- 81.0 s, 85.0 ms (base 33.3): gc:high:FULL:EXPLICIT
- 116.4 s, 84.6 ms (base 33.3): gc:high:FULL:EXPLICIT
- 91.1 s, 84.4 ms (base 33.3): gc:high:FULL:EXPLICIT
- 146.8 s, 84.3 ms (base 33.3): gc:high:FULL:EXPLICIT
- 111.4 s, 84.1 ms (base 33.3): gc:high:FULL:EXPLICIT

**C1** worst spikes (t s, ms, baseline ms, notes):
- 20.1 s, 65.9 ms (base 8.3): render:low, afterTeleport:context
- 23.6 s, 33.9 ms (base 8.3): render:low, afterTeleport:context
- 20.7 s, 31.2 ms (base 8.3): render:low, afterTeleport:context
- 20.6 s, 30.7 ms (base 8.3): render:low, afterTeleport:context
- 28.6 s, 29.3 ms (base 8.3): render:low, afterTeleport:context
- 25.0 s, 28.2 ms (base 8.3): render:low, afterTeleport:context
- 30.0 s, 27.0 ms (base 8.3): render:low
- 24.3 s, 25.4 ms (base 8.3): render:low, afterTeleport:context
- 21.4 s, 22.9 ms (base 8.3): render:low, afterTeleport:context
- 20.7 s, 22.5 ms (base 8.3): render:low, afterTeleport:context

**C2** worst spikes (t s, ms, baseline ms, notes):

**C3** worst spikes (t s, ms, baseline ms, notes):
- 31.1 s, 41.1 ms (base 0.3): render:low, afterTeleport:context
- 11.0 s, 29.8 ms (base 0.4): render:low, afterTeleport:context
- 25.9 s, 27.7 ms (base 0.3): render:low, afterTeleport:context
- 11.3 s, 27.1 ms (base 0.4): render:low, afterTeleport:context
- 23.0 s, 26.8 ms (base 0.3): gc:medium, render:low, afterTeleport:context
- 23.0 s, 26.2 ms (base 0.3): gc:medium, render:low, afterTeleport:context
- 29.9 s, 25.3 ms (base 0.3): render:low, afterTeleport:context
- 27.4 s, 23.8 ms (base 0.3): render:low, afterTeleport:context
- 26.6 s, 22.9 ms (base 0.3): render:low, afterTeleport:context
- 22.6 s, 21.7 ms (base 0.4): render:low, afterTeleport:context

**C4** worst spikes (t s, ms, baseline ms, notes):
- 100.7 s, 85.9 ms (base 0.3): render:low, afterTeleport:context
- 101.3 s, 43.3 ms (base 0.4): render:low, afterTeleport:context
- 100.6 s, 41.1 ms (base 0.3): render:low, afterTeleport:context
- 102.5 s, 36.2 ms (base 0.3): render:low, afterTeleport:context
- 104.9 s, 31.7 ms (base 0.3): gc:low, render:low, afterTeleport:context
- 100.6 s, 28.1 ms (base 0.3): render:low, afterTeleport:context
- 100.8 s, 27.6 ms (base 0.3): render:low, afterTeleport:context
- 101.2 s, 25.8 ms (base 0.3): render:low, afterTeleport:context
- 104.1 s, 25.3 ms (base 0.3): render:low, afterTeleport:context
- 108.6 s, 24.9 ms (base 0.3): render:low, afterTeleport:context
- GC log: 228 pauses in the JVM, longest 11.8 ms; capture started at JVM uptime ~17 s (1 s log precision)
  - GC-claimed spike at 104.9 s: -Xlog pauses within 2 s: 103.202 s 5.51 ms Pause Young (Mixed) (G1 Evacuation Pause) 726M->404M(782M), 103.721 s 4.66 ms Pause Young (Concurrent Start) (G1 Evacuation Pause) 738M->418M(786M), 103.871 s 3.02 ms Pause Remark 502M->502M(786M), 103.917 s 0.04 ms Pause Cleanup 522M->522M(786M), 104.275 s 5.79 ms Pause Young (Prepare Mixed) (G1 Evacuation Pause) 742M->433M(796M), 104.677 s 4.20 ms Pause Young (Mixed) (G1 Evacuation Pause) 753M->430M(798M), 105.232 s 3.75 ms Pause Young (Concurrent Start) (G1 Evacuation Pause) 754M->439M(798M), 105.378 s 2.58 ms Pause Remark 510M->510M(798M), 105.428 s 0.04 ms Pause Cleanup 577M->577M(798M), 105.741 s 5.72 ms Pause Young (Prepare Mixed) (G1 Evacuation Pause) 753M->455M(802M), 106.102 s 6.36 ms Pause Young (Mixed) (G1 Evacuation Pause) 759M->451M(820M), 106.558 s 4.80 ms Pause Young (Concurrent Start) (G1 Evacuation Pause) 773M->465M(820M), 106.707 s 2.22 ms Pause Remark 565M->565M(820M), 106.757 s 0.03 ms Pause Cleanup 587M->587M(820M)

**D** worst spikes (t s, ms, baseline ms, notes):
- GC log: 0 pauses in the JVM, longest 0.0 ms; capture started at JVM uptime ~19 s (1 s log precision)

**D2** worst spikes (t s, ms, baseline ms, notes):
- GC log: 0 pauses in the JVM, longest 0.0 ms; capture started at JVM uptime ~19 s (1 s log precision)

**SM1** worst spikes (t s, ms, baseline ms, notes):
- 10.2 s, 30.8 ms (base 8.3): gc:low, render:low, afterTeleport:context
- 11.1 s, 24.3 ms (base 8.3): render:low, afterTeleport:context
- 10.7 s, 22.8 ms (base 8.3): gc:medium, render:low, afterTeleport:context

**SM1b** worst spikes (t s, ms, baseline ms, notes):
- 10.2 s, 25.1 ms (base 8.5): gc:medium, afterTeleport:context
