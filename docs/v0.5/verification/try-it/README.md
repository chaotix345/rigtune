# Try it (C09): AC6.16's dev-PC runs

Owner: WS-T. The code-deciding run of docs/v0.5/SPEC.md AC6.16, on the dev PC (Windows 11, AMD Radeon RX 7800 XT),
under the game-test lock, in the worktree's 26.2 development client (`:26.2:runClient`, Fabric Loader 0.19.5, Sodium
0.9.2+mc26.2, 54 mods loaded, the default 854x480 window, render distance 12, simulation distance 12). Not the game-test
harness: its tick sync makes 1 % lows meaningless. A development-only driver, `client/tryit/TryItDevRun` (loaded only
when the environment variable `RIGTUNE_DEV_TRYIT` is set), runs each part and logs every step with the prefix
"Try it dev:"; the lines are kept here next to this file. The player's own Minecraft wasn't running during any of the
runs (its instance log was last written at 09:46 that day).

## A/A: 5 Measure pairs with nothing changed (2026-09-28, 12:50-12:58 local)

`RIGTUNE_DEV_TRYIT=aa`, built from `feat/v05-try-it` at 91441f58 plus the driver. From the title screen: one warm-up
Measure run (the first run in a new benchmark world is left out of the trend, `worldFresh`), then 5 Measure pairs in the
benchmark world, each a before and an after run with nothing changed in between (the benchmark world opens and closes for
every run, about 45 s each). Each pair is judged exactly as Try it judges a try (`TryItVerdict.of`, `MIN_CV` 0.025, the
trend floor from the earlier comparable runs). Log: `aa-2026-09-28.log`.

| pair | before: 1 % low (CV) | after: 1 % low (CV) | 1 % low | average | floor | verdict |
|---|---|---|---|---|---|---|
| 0 | 344.6 (0.003) | 349.8 (0.008) | +1.5 % | -1.4 % | 5.0 % | no clear change |
| 1 | 331.0 (0.073) | 356.8 (0.035) | +7.8 % | +8.1 % | 14.7 % | no clear change |
| 2 | 794.4 (0.706) | 1178.3 (0.059) | +48.3 % | +1.3 % | 141.1 % | no clear change |
| 3 | 1254.0 (0.002) | 1307.9 (0.018) | +4.3 % | +0.4 % | 13.1 % | no clear change |
| 4 | 1398.8 (0.025) | 1329.9 (0.037) | -4.9 % | +0.3 % | 122.5 % | no clear change |

Warm-up: 1 % low 295.4 FPS, average 2009.5, CV 0.12.

**Result: 5 of 5 pairs within the floor; `MIN_CV` stays 0.025.** Without the trend floor (2 x the larger CV, at least
2 x `MIN_CV`) the floors would be 5.0, 14.7, 141.1, 5.0 and 7.5 %: still 5 of 5 within.

What the numbers also show:
- **The 1 % lows climb through a session** (295 -> about 1300-1400 FPS over 11 runs) while the average barely moves
  after the first pair: each run reopens the benchmark world, and the first runs pay for chunk building and JIT warm-up.
  A pair's two runs are back to back, so within a pair the drift is small (pair 1's +7.8 % is the largest, inside its
  14.7 % floor); across sessions it is not, which is why a try compares its own pair only, never an older run.
- **One hitch is caught by the CV**: pair 2's before run has CV 0.71 (one stall inside the run); its floor (141 %) says
  the pair can't tell anything. Pairs 1 and 2 would also carry the NOISY caveat (a CV over 5 %; the driver logs the
  causes, not the caveats).
- **The trend floor is large here** (13.1 % and 122.5 % for pairs 3 and 4): the earlier comparable runs include the
  climbing lows and pair 2's hitch. On a real try the trend floor only ever widens the floor (it's a max), so it errs
  toward "no clear change".

## NOW: one try in the player's own world against a manual pair (13:34-13:37 and 13:50-13:54)

`RIGTUNE_DEV_TRYIT=now:tryit-now`, built from 35a3a20b (the phase 2 review's chain). The driver opens the singleplayer
save `tryit-now` (the player stands where the save left them), waits 20 s, and starts the real chain:
Try it on render distance 12 -> 10 in the CURRENT scene (Start, the before run, the apply, the after run, the verdict,
no button pressed in between). Then History's Undo this reverts it, and a manual Measure pair measures the same change at
the same spot (before at 12, after at 10). The second run (`now:tryit-now:warm`, built from c3e9ef1d plus the driver's
`warm` option) first runs one plain Measure run there, as a player who has been in the world a while would have.

| run | try: before -> after 1 % low (CV) | try: 1 % low, average | floor | verdict | manual pair: 1 % low (CV) | manual: 1 % low, average |
|---|---|---|---|---|---|---|
| 1 (20 s after joining) | 432.9 (0.33) -> 688.7 (0.006) | +59.1 %, +15.7 % | 65.5 % | no clear change (NOISY, SCENE) | 715.0 (0.028) -> 705.7 (0.053) | -1.3 %, -1.0 % |
| 2 (after a warm-up run) | 554.0 (0.004) -> 543.3 (0.064) | -1.9 %, +4.1 % | 24.7 % | no clear change (NOISY, SCENE) | 550.1 (0.007) -> 545.0 (0.015) | -0.9 %, -0.4 % |

Logs: `now-2026-09-28-1.log`, `now-2026-09-28-2-warm.log`. No "you moved" in either (the player didn't move; each spot
was taken as its run started, review M6).

**Result.** Run 2 meets AC6.16: the try's sign matches the manual pair's (both lower 1 % lows, -1.9 % against -0.9 %),
and the difference (1.0 point) is well inside the try's floor (24.7 %). Run 1 doesn't match the sign: the try's before
run started 20 s after the world opened and was slow and uneven (CV 0.33, the lows about 40 % under every later run's;
most likely chunks still being built and the JIT warming up, as in the A/A's first runs), so the after run looked +59 %
better. The floor held it: 2 x the before run's CV gives 65.5 %, so the verdict was "no clear change" with the NOISY
caveat, not "better". What this decides:
- **No code change to the verdict.** The CV-based floor is what keeps a cold before run from reading as a gain, and it
  did.
- **A residual (for the coordinator):** a cold start whose before run is slow but *steady* (a low CV) would not be caught
  by the CV. Nothing in 0.5 checks how long the player has been in the world before Start. The README's known limits
  should say "play a minute first" (the Docs text in docs/v0.5/design/ws-t.md); a later version could wait for the world
  to settle, or run a warm-up pass, before a CURRENT-scene before run.
- The benchmark world is the fairer scene (the A/A above: every pair within the floor).

## RESTART: a Sodium key across real restarts, Revert, a second restart (13:54-14:05)

`RIGTUNE_DEV_TRYIT=restart1`, then `restart2`, then `restart3`: three separate launches of the development client (each a
new game session), built from c3e9ef1d. Logs: `restart1-2026-09-28.log`, `restart2-2026-09-28.log`,
`restart3-2026-09-28.log`.

1. **restart1**: Try it on Sodium's chunk-build defer mode, ALWAYS -> ONE_FRAME (a RESTART key: the benchmark world). The
   before run, then AWAITING_RESTART (the PATCH_JSON op staged in pending.json, the change journaled STAGED under the
   try's entry `e8a61f72-...`); the game quits and RigTune's apply helper starts for pending.json.
2. **restart2**: after the restart the try is READY, not the same session, and `sodium-options.json` holds ONE_FRAME (the
   helper applied it at the exit). Measure now runs the after run in the benchmark world: RESULT, no clear change
   (1 % lows 291.9 -> 291.9, average +1.7 %, floor 113.6 %: the trend floor, from the earlier benchmark-world runs
   with the A/A session's climbing lows among them), with
   the SESSIONS and SCENE caveats. Revert (History's Undo this, confirmed on UndoScreen): REVERT_PENDING, the reverse op
   staged; Done closes the try as reverted; the game quits and the helper starts again.
3. **restart3**: `sodium-options.json` holds ALWAYS again (the old value); no try open; History's newest entries: the
   apply `e8a61f72-...` with its change REVERTED, and the undo `1ed27612-...` (undoOf `e8a61f72-...`) ONE_FRAME -> ALWAYS
   APPLIED; tryit.json's `recent` starts with the defer mode, REVERTED.

**Result: AC6.16's RESTART part holds** (the old value in `sodium-options.json`; History showing the apply, the undo and
REVERTED).
