# AC5.8 C re-run on the release candidate (P5-C, 2026-09-27)

After fix/review-8b (the never-claiming "chunks loading" tag, P5A-F2). RC: feat/v0.4.0 @ b27f33fa, built in test/p5-final
@ 23be54d1, `rigtune-0.4.0-dev+mc26.2.jar` sha256 `4018fe03…9a12`. Same machine and setup as P5-A's C runs
(`../README.md`): MC 26.2, Fabric API 0.161.0+26.2, Sodium 0.9.2 (default options: Chunk Updates = Deferred), RD 12,
SD 8, 1280×720, G1 `-Xmx4G`.

**How.** One production client per run under the game-test lock (`../../p5c/tools/stut-run.sh`: a **fresh instance per
run**, so 200000,200,200000 is never-generated terrain; Loom `e2eClient`, the undo driver inert), the product's own
`-Drigtune.dev.stutterScript=teleport` (monitor on, benchmark world, 20 s still, `tp @a 200000 200 200000`, 30 s,
save-all, 10 s, Stutter Doctor, leave, quit) and `-Xlog:gc,safepoint` with wall-clock + uptime decorators. Evaluation:
`../../p5c/tools/stut_eval.py` (per run: `<run>-eval.md`, `-summary.txt` = the Copy summary, `-stutter.json`,
`-log-excerpt.txt`, `-gc.log`).

**GC check method.** A spike's `t` is seconds from the capture start; latest.log has 1 s resolution, so the script fits
the sub-second capture-start offset (10 ms steps, 0-1.2 s) that lines up the GC-noted spikes with the JVM's pauses, then
lists, for every worst spike, the pauses overlapping `[t − ms, t]` (±50 ms for `t`'s rounding). Every GC-noted spike
overlaps a real pause at one offset in all three runs (0.96 s, 0.48 s, 0.57 s).

## Results against AC5.8 C as amended (SPEC: fix-8b, refined after P5C-F1 in 427f4a7)

| run | options | spikes | after teleport | chunks loading | untagged, and where | causes | GC claims | verdict |
|---|---|---|---|---|---|---|---|---|
| [C1r](C1r-eval.md) | vanilla defaults (VSync, 120 FPS cap) | 12 (10 hitches) | 12/12 | 9/12 | 21.9, 22.1, 22.2 s: the first ~0.3 s after the tp, before the first chunk load; every spike from 22.3 s on tagged (the 2 unlisted spikes are tagged, by count) | GC 10 %, not explained 90 % | 5, each on an overlapping pause (5.6-10.6 ms) | **PASS** |
| [C1r2](C1r2-eval.md) (repeat) | vanilla defaults | 8 (8) | 8/8 | 7/8 | 22.0 s: the first spike after the tp; every later one tagged (all 8 spikes listed) | not explained 100 % (the one GC claim rounds to 0 %) | 1, on a 5.75 ms pause | **PASS** |
| [C3r](C3r-eval.md) | uncapped (VSync off, 260 = unlimited, 2,620 FPS) | 14 (13) | 14/14 | 11/14 | 10.1, 10.7 s (world entry, before the tp) + 1 of the 4 spikes outside the worst-10 list (no timestamp in stutter.json); all 8 listed post-teleport spikes tagged | GC 5 %, chunk building 7 % (`chunkBuild:medium`: measured backlog evidence), not explained 89 % | 1, on a 6.01 ms pause | **PASS** (for every timestamped spike) |

Every run: the save-all ran at session ≈ 52-53 s with no spike in it (no world-save tag); the report shows the
unexplained remainder; phase timing ok (`timers seen 1111111`); calibrated GC offset 18.0 / 17.3 / 17.4 ms. The save-all's window is in each
log excerpt (`Dev stutter: save-all` followed by the server's "Saving chunks …" / "All chunks are saved" lines, same second).
No chunk loading was claimed in milliseconds (no packets-phase excess with chunk loads on these frames), and chunk
building only in C3r with backlog evidence (Attributor claims `chunkBuild` only with `deferModeWaits` or a Sodium
backlog).

**Verdict: AC5.8 C PASS on the RC.** Every post-teleport hitch carries "after teleport"; "chunks loading" is carried from
the first chunk load after the teleport on (P5C-F1: the ~0.3 s before any chunk arrives has no loads to tag, now in
the SPEC's wording); nothing claimed as GC without an overlapping pause; save window recorded; remainder shown. Caveat:
in C3r one untagged spike outside the worst-10 list can't be placed in time (stutter.json keeps only the 10 worst);
C1r and C1r2 have complete per-spike data. 26.3 not run (vanilla 26.3's native startup crash on most local launches;
P5-A's C runs were 26.2 too).
