# P5-C findings (release-candidate finals, 2026-09-27)

Release candidate: `test/p5-final` @ 23be54d1 = `origin/feat/v0.4.0` @ b27f33fa ("Merge fix/review-8b") + test-only
commits (e2e driver/harness text); product code identical to b27f33fa. `./gradlew build` → `rigtune-0.4.0-dev+mc26.2.jar`
sha256 `4018fe03ee2164724f144f77abc829b3b73ac25e941e38f5c3c6aeb8778a9a12`, `rigtune-0.4.0-dev+mc26.3.jar`
`568aa0b4ba7e6e4c7b72a75425c3e0a786415b9e4b6d71bdeed5613319314b85`. Runs and results: `p5c/README.md`.

| id | severity | status |
|---|---|---|
| P5C-F1 | low | **decided, no product change**: the coordinator refined SPEC AC5.8 C (427f4a7): "chunks loading" from the first chunk load after the teleport on. Against that text C1r, C1r2 and C3r PASS (`stutter/C-rerun/README.md`) |

## P5C-F1 (low): the spikes in the first ~0.3 s after the teleport carry "after teleport" but not "chunks loading" (AC5.8 C)
- **Repro:** 26.2, RC jar, Sodium 0.9.2, fresh instance (never-generated terrain), RD 12, G1 -Xmx4G, vanilla default
  options (VSync on, 120 FPS cap), `-Drigtune.dev.stutterScript=teleport` (run `C1r`).
- **Result:** 12 spikes, all "after teleport"; **9 of 12** "while chunks were loading". The 3 untagged spikes are at
  21.9, 22.1 and 22.2 s, i.e. the first ~0.3 s after the `tp` (logged at session ≈ 21-22 s); every spike from 22.3 s on
  carries the tag. Each of the three is GC-noted (low/medium) and overlaps a real G1 pause (Pause Young 5.96/7.57 ms in
  the JVM's `-Xlog:gc`), plus "rendering (low)"; nothing is claimed that wasn't measured and the remainder (90 %) is shown.
  The uncapped run `C3r` (2,620 FPS): 14 spikes, 11 tagged; the 3 untagged include the two world-entry spikes at 10.1
  and 10.7 s (before the teleport); all 8 post-teleport spikes in the worst-10 list carry the tag (the 4 spikes outside
  that list have no timestamps in stutter.json, so one untagged spike there may be post-teleport). The repeat `C1r2`
  (default options): 8 spikes, all "after teleport", 7 tagged; the untagged one (22.0 s) is the first spike after the
  teleport, every later one is tagged (all 8 spikes are in the worst list, so the data is complete).
- **Why:** by design, `chunksLoading` = client chunk loads within `Attributor.CHUNK_NEAR` (250 ms) of the spike. The
  frames that process the teleport itself come before the first chunk of the new area arrives, so they have no chunk
  loads within 250 ms.
- **Against the amended text** (fix-8b.md: "the teleport script's hitches in the 30 s after the teleport carry the tags
  'after teleport' and 'chunks loading'"): not met literally for those first frames (C1r 9/12); met from the first chunk
  load on. Every other part passes in both runs (tags present with chunk loads > 0, no GC claim without an overlapping
  pause, chunk building claimed only with measured backlog evidence (C3r `chunkBuild:medium`, 7 %), save window
  recorded, remainder shown).
- **Options (not applied; no product-code changes here):** (a) amend AC5.8 C to "the hitches from the first chunk load
  after the teleport on"; or (b) have the tag also look ahead a little further after a teleport event (e.g. chunk loads
  within 1 s after a spike that is within the "after teleport" window), which stays correlational and never claims ms.
- **Evidence:** `stutter/C-rerun/` (`C1r-*`, `C3r-*`: eval tables, summaries, stutter.json, log excerpts, GC logs).
