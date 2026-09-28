# AC3f.1: the Stutter Doctor's dev script on 26.3 (and 26.2) in CI (WS-E)

**How:**
- `tools/e2e/stutter_run.py` makes a fresh instance: the jar under test, fabric-api and Sodium.
  - It runs the product's own `-Drigtune.dev.stutterScript=teleport`: monitor on, benchmark world, 20 s still, `tp @a 200000 200 200000` to never-generated terrain, 30 s, save-all, 10 s, the Stutter Doctor, leave, quit.
  - Also `-Drigtune.dev.forceGcEverySec=5` and `-Xlog:gc,safepoint`.
  - One start through Loom's `e2eClient`, under Xvfb (llvmpipe), in `tools/ci/offline.sh`'s namespace (no network).
- `evaluate()` ports v0.4's local evaluator (`docs/v0.4/verification/p5c/tools/stut_eval.py`) and judges qualitatively. llvmpipe's milliseconds aren't a GPU's, so they aren't judged. The checks:
  - the script finished and saved its monitor session;
  - "after teleport" is on every listed spike in the product's teleport window;
  - "chunks loading" is on every spike in that window from the first chunk load to the last one;
  - no GC milliseconds are claimed without an overlapping JVM pause (the capture start is fitted to 10 ms, as in v0.4);
  - the unexplained remainder is shown.
- The job is e2e.yml's `stutter-script`, release tier, one per node, on the caller's jar. It ran on the scratch branch `scratch/ws-e-battery-oshi` (e2e.yml with only this leg and battery-oshi on).

| run | node | result | tp (session s) | spikes | tags | causes (share of lost time) | GC-noted spikes without a pause |
|---|---|---|---|---|---|---|---|
| [36362848495](https://github.com/chaotix345/rigtune/actions/runs/36362848495) | 26.2 | 4/6 (see below) | 27 | 16 | afterTeleport 4, chunksLoading 13, worldSave 1, cpuContention 14 | gc 0.85, unknown 0.15 | none of 9 |
| 36362848495 | 26.3 | 4/6 (see below) | 24 | 12 | afterTeleport 3, chunksLoading 9, cpuContention 9 | gc 0.85, chunkBuild 0.01, unknown 0.14 | none of 9 |
| [36363923151](https://github.com/chaotix345/rigtune/actions/runs/36363923151) | 26.2 | **PASS 6/6** | 27 | | afterTeleport 6, chunksLoading 12 | gc 0.86, chunkLoad 0.01, unknown 0.13 | none of 10 |
| 36363923151 | 26.3 | **PASS 6/6** | 23 | | afterTeleport 4, chunksLoading 7 | gc 0.96, tick 0.01, unknown 0.03 | none of 10 |
| [36431601035](https://github.com/chaotix345/rigtune/actions/runs/36431601035) | 26.2 | 5/6 as run; PASS 6/6 under the corrected criterion | 26 | 7 | afterTeleport 5, chunksLoading 6 | gc 0.78, unknown 0.22 | none of 6 |

Evidence per run and node: `run-<id>/mc<node>/` holds `RESULT.md`, `stutter.json` and `log-excerpt.txt`. The GC logs are in the runs' `stutter-script-<node>` artifacts.

**The first run's two FAILs came from the criterion, not the product.**
- The evaluator carried over v0.4's 30 s view: every listed spike up to 30 s after the tp had to carry "after teleport", and every spike after the first chunk load had to carry "chunks loading".
- The product tags only `StutterAnalyzer.TELEPORT_WINDOW`: 10 s from the position jump. In v0.4's GPU runs every spike fell in the first 10 s, so the two views agreed there.
- Here, forced full GCs every 5 s hit on llvmpipe: explicit full GCs of 260-500 ms. They put spikes 10-30 s after the tp, outside the window, and the product rightly leaves them untagged.
- The criterion now uses the product's window (6156f948). Re-evaluated with it, the first run's evidence passes too. Its RESULT.md is kept as written.
- Review round 2 tightened it for the log's whole-second stamps (±1 s):
  - every listed spike surely inside the window (tp + 1 .. tp + 9 s) must carry "after teleport";
  - at least one tagged spike must lie within tp - 1 .. tp + 11 s;
  - no tagged spike may lie outside that and the world entry's own window. Entering the world counts as a teleport to the product: C1r's spike at 10.6 s carries the tag too.
  - "Chunks loading" is judged in the wide window.
  - All four CI evaluations and v0.4's C1r pass under this criterion (re-evaluated locally from the recorded files).

**The release PR's 26.2 run (36431601035) failed "chunks loading" at the window's far edge. The criterion was wrong, not the product.**
- The product tags a spike "chunks loading" only when the client loaded chunks within `Attributor.CHUNK_NEAR` (250 ms) of it.
- The run's spikes are the forced full GCs, about 5 s apart: 26.4 s and 31.5 s after capture start carried the tag; 36.7 s (tp + 10.7 s, still "after teleport") didn't.
- The frame baseline had dropped from about 165 ms to about 50 ms by then, which fits loading having ended.
- The earlier passing 26.2 run (36363923151) also had its last loading spike at 31.5 s; it just had no forced GC before tp + 11 s.
- Nothing in the tagging path changed since that run. The candidate records gained `C_GAMEPLAY` at the end, with `C_CHUNKS` and the frame phase words unchanged. The 26.3 leg of the same run passed.
- The criterion now asks for the tag on every spike between the first and the last chunk-loading spike in the window. An untagged spike in the middle of loading still fails (`test_stutter_run.py`). All recorded runs and v0.4's C1r pass under it.
- The run's RESULT.md is kept as written.

**What this doesn't show (UNVERIFIED):**
- 26.3's millisecond numbers on a real GPU. The local 26.3 client crashes natively (SPEC X9).
- How the causes split under normal play. Here the forced GCs dominate the lost time by design.
