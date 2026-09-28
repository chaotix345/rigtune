# AC5.14: the stutter fixes' calibration run (WS-S2, 2026-09-28)

The code-deciding real run of C20 (docs/v0.5/SPEC.md 5, AC5.14; sf §5.3): on the dev PC, the Sodium fix is offered from
real stutter, applied, restarted, compared and undone. Result: **the offer fired (twice) on the bundled r17 thresholds, so
they stand unchanged; the comparison said "no clear change"** (hitches 34.08 → 30.55 a minute, φ 1.930, p 0.230 for
"less").

## Setup
- PC: AMD Radeon RX 7800 XT (Windows 11), Minecraft 26.2 production client (the e2e harness, `:26.2:e2eClient`), 4 GB heap
  (`-Xmx4G`), G1; mods: RigTune 0.5.0-dev (this branch), Fabric API 0.161.0, Sodium 0.9.2; nothing else.
- options.txt: render distance 12, simulation distance 8, vsync off, max FPS 260, inactivity limit "minimized",
  windowed 1280x720. sodium-options.json: Chunk Updates = Immediate (`ZERO_FRAMES`). Network off, the monitor on.
- The driver: `DevFixCalibration` (`-Drigtune.dev.stutterScript=fixcalibrate`, inert otherwise). Each launch plays one
  step, decided by stutter-fixes.json: RigTune's benchmark world, a teleport into never-generated terrain every 20 s
  (`/tp` to base + 3000·k on both axes), then:
  - **before**: 390 s of play, open the Stutter Doctor, log the analysis, the evidence and the offer, press the offered
    Sodium fix (the same `applyStutterFix` the preview's Apply calls), quit (the helper applies the staged change at the
    exit);
  - **after**: 450 s of play (60 s longer, so the gameplay reaches the after target), leave the world (the session ends
    and is compared), log the record, then Undo this on its entry, quit.
- Each launch holds the machine-wide game-test lock; the logs are in `runs.log` (the driver's lines, the Stutter Doctor's
  summary and the saved sessions of every launch).

## The run (attempt 2: before2, after2)
| | before (Immediate) | after (Deferred) |
|---|---|---|
| session / gameplay (idle) | 397.6 s / 380.3 s (none) | 451.8 s / 441.9 s (none) |
| hitches (spikes) | 216 (253) | 225 (261) |
| hitches a minute | 34.08 | 30.55 |
| time lost | 6,696.5 ms (1,056.5 ms a minute) | 6,929.6 ms (940.9 ms a minute) |
| 60-s bins: count, mean, variance | 7, 30.86, 47.14 | 8, 28.13, 64.98 |
| claimed shares (the evidence) | chunk building 92.5 %, GC 6.9 %, unknown 0.6 % | chunk building 0.8 %, GC 7.0 %, unknown 92.2 % |
| spikes each cause dominated | chunkBuild 235, gc 18 | chunkBuild 2, gc 18, unknown 241 |
| tags | after teleport 91.3 %, chunks loading 15.0 % | after teleport 86.6 %, chunks loading 9.2 % |

- **The offer** (before): advice `stutter-sodium-defer` fired, evidence TRUE (chunk building 92 % ≥ 40 %, 235 spikes it
  dominated ≥ 5), `FixGate`'s floor held (a monitor session, 216 ≥ 8 hitches, 380 s ≥ 300 s): Offer ZERO_FRAMES →
  ALWAYS, at the next restart. Applied: one `apply` entry with a staged PATCH_JSON op; "Fix staged: … play at least 6:20".
- **The helper** patched Sodium's file at the exit (the next launch read Chunk Updates = ALWAYS); the record went staged →
  measuring at the first session that started with the target, and the 441.9 s session reached the after target
  (clamp(380.3, 300, 1200)) at once.
- **The comparison** (`FixComparison`): φ = 1.930 (pooled over the bins), effective counts 216/φ → 112 and 225/φ → 117
  (half up), p0 = 441.9 / 822.2 = 0.5375, n = 229: pLess = 0.2297, pMore = 0.8085. The after rate is 0.896 of the before
  rate (not ≤ 2/3) and neither p is ≤ 0.05: **no clear change** (`SAME`). An independent recomputation (Python, exact
  binomial with `math.comb`) gives the same p-values to every printed digit.
- **Undo**: the block's "Undo this change…" path (`undoPlanFor` + `undo`): "Undone: 0 now, 1 after a restart"; the helper
  reverted Sodium's file at the exit (Chunk Updates = ZERO_FRAMES again), history.json holds the apply entry with its
  change REVERTED and the undo entry APPLIED, so the record reads "undone" at its next update (FixTracker: REVERTED →
  undone, from compared too). `stutter-fixes.after-compared.json` is the file as the comparison left it.

## What it decides
- **Thresholds**: unchanged. The Sodium seed's evidence (`chunkBuild` ≥ 40 % claimed and `causeSpikesAtLeast`
  chunkBuild 5) fired with a wide margin both times (70 % / 184 spikes in before1, 92 % / 235 in before2), and not in
  the after sessions (0 % / 0 and 0.8 % / 2 dominated spikes): with Deferred, no spike waits on chunk building any more.
  No follow-up for WS-R.
- **What the player sees**: the fix moved the stutter's cause (chunk building → not explained), not the stutter. Under
  this driver most hitches come right after a teleport into new terrain (87-91 % tagged "after teleport"), which Deferred
  doesn't remove. That is why the comparison uses outcomes only, never cause shares (sf §1.6): a cause-share comparison
  would have claimed a 92 % → 1 % improvement. The verdict says "no clear change" with both rates, as designed.
- The render-distance fix's real run (against a local server) wasn't done: that path is covered by CI only
  (StutterFixGameTest on 3 legs), as AC5.14 allows.

## Attempt 1 (before1, after1): what it found
- before1: the offer fired (chunk building 70 %, 184 spikes it dominated, 177 hitches in 357 s) and was applied.
- after1 ran a stale RigTune jar left in the calibration instance (built before the merge of WS-S's RW-17/RW-18 and the
  L13 change), and the game window lost focus while it played (someone used the PC): 127.3 s of gameplay in a 391.5 s
  session (unfocused frames are excluded, as in play). The record counted it (after 127.3 s < target 357 s, still
  measuring), and the old driver undid the fix anyway, so the attempt ended without a comparison
  (`stutter-fixes.attempt1.json`). Fixed in the driver (L13): the after step plays 60 s longer, and while the record
  still measures it quits without the Undo, so the next launch plays on. Attempt 2 ran the current jar from a fresh
  record.

## UNVERIFIED
- One PC, one driver: the verdict is this rig's under teleport play; real play with less teleporting may differ (the
  comparison is a measurement, not proof, as the UI says).
- 26.3 and Vulkan: not run for real (CI covers the flow on 26.2 GL, 26.3 GL and 26.3 Vulkan with injected analyses).
