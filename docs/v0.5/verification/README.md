# v0.5 verification evidence

One section per area (docs/v0.5/PLAN.md contracts item 18). Each area's owner edits only its own section: what was
checked, how (unit, game test, CI run id, real run), the date, and where the evidence is (`docs/v0.5/verification/<area>/`
files, CI artifact names). UNVERIFIED items are listed with their reason. PROGRESS.md links here.

## ci
Owner: ws-ci (then the coordinator's streaks). SPEC 1a-1g: no live network, FakeModrinth, caches and pins, the frame-hook
gates, the flakes, "Stream N cancelled", the 5-run streaks (`ci-streak.md`).

## footprint
Owner: ws-ci, WS-K (the X4 render-thread flag), the 1h checkpoint after Wave B. Per-leg numbers per streak.

## stutter
Owner: WS-S (NEW-1's measurement, SD-*, RW-10/11, the DH bucket), WS-S2 (AC5.14's calibration run: `stutter-fixes/`).
- `new1-generational-shenandoah/`: NEW-1's code-deciding run (AC2S.11): generational Shenandoah's notifications don't tell young from global/old cycles, so its live set is unmeasured (WS-S, 2026-09-27).
- `stutter-fixes/`: AC5.14's calibration run (real, 26.2, RX 7800 XT, the teleport driver): the Sodium fix was offered from real stutter (chunk building 92 %, 235 spikes it dominated), applied, restarted, compared and undone; verdict "no clear change" (34.08 → 30.55 hitches a minute, φ 1.930, p 0.230); the r17 thresholds stand (WS-S2, 2026-09-28).

## benchmark
Owner: WS-B (RW-5..RW-9, RW-15, BH-1/2, L3; AC2B.5's DH run with the P5 agent).

## e2e
Owner: WS-E (3a-3c: self-update, undo and downgrade E2E per tier, compat040/compat030, tested bytes = published bytes).
`e2e/README.md`: the release dry run 36296717280 (16/16) and the local Windows runs (helper-kill, the undo scenario with
guard-apply, the downgrades with v050 sets; `e2e/local-windows-26.2/`). compat040/compat030 run in build.yml's java job
(green in 36363179808). The Stutter Doctor dev-script leg (AC3f.1) is recorded under `stutter/ac3f1-stutter-script/`.

## server
Owner: WS-E (3d LAN guest and Realms; AC3f.4's DH server note).
`server/README.md`: LanGuestGameTest (LAN list join, restart, Realms via RealmsConnect), local and CI 36363179808 on 3
legs; what the Realms block proves; UNVERIFIED items. AC3f.4 (the DH note): not run yet.

## battery
Owner: WS-E (3e: the battery flow with a simulated battery, the tmpfs OSHI leg).
`battery/README.md`: BatteryFlowGameTest on 3 legs (36363179808) and locally; battery-oshi on both nodes (scratch runs
36362848495, 36363923151); AC3e.3's unit cases.

## launcher
Owner: WS-L1, WS-L2, the P5 agent (4j: the simulated brand and `.index/` runs, the read-only repair-list check, AC4j.5's
user step).

## try-it
Owner: WS-T (AC6.16's A/A pairs, NOW and RESTART tries).
- WS-T's code-deciding run (AC6.16; 2026-09-28, this PC, the worktree's 26.2 development client with Sodium 0.9.2, under
  the game-test lock, the player's game not running; `try-it/`): 5 A/A Measure pairs in the benchmark world all within
  the floor, so `MIN_CV` stays 0.025; a NOW try (render distance 12 -> 10 where the player stands) after a warm-up run
  matched a manual Measure pair's sign (-1.9 % against -0.9 %, floor 24.7 %), while one started 20 s after joining did
  not (a slow, uneven before run: +59 %, held to "no clear change" by its 65.5 % floor; a residual and a known-limit
  line); a RESTART try of Sodium's defer mode across real restarts, Revert and a second restart left the old value in
  `sodium-options.json` and History with the apply, the undo and REVERTED. The driver is `client/tryit/TryItDevRun`
  (`RIGTUNE_DEV_TRYIT`); details and logs in `try-it/README.md`.
- WS-T (CI, every push): `TryItGameTest` blocks 1-7 on the three legs (network off; no FPS number or live verdict kind
  asserted), `A11yGameTest.walkTryIt`; screenshots `tryit-*` and `a11y-*tryit*` in each leg's `gametest-screenshots-*`
  artifact. Run ids and what was looked at: docs/v0.5/design/ws-t.md.

## server-profiles
Owner: WS-P2, the P5 agent (AC7.16's real JOIN against a vanilla server on a non-default port).

## first-apply
Owner: WS-F, the P5 agent (AC8.17's fresh-instance run at three sizes, the 26.3 Apply half).
- WS-F (CI, every push): `FirstApplyGameTest` on the three legs drives the real flow from a fresh run dir (the guide at
  1280×720/854×480/640×480 scale 2, How it works, NoticeScreen, the first Apply's confirmation against History's rows,
  Undo this Apply, History…, Done/Esc, Got it, the list-height check against 0.4.0's measured 76 px); `A11yGameTest`'s
  `walkFirstApply`/`walkHowItWorks`. Screenshots: `firstapply-*` and `a11y-*first-apply*`/`a11y-*how-it-works*` in each
  leg's `gametest-screenshots-*` artifact. Details, run ids and what was looked at: docs/v0.5/design/ws-f.md.
- The 0.4.0 list-height baseline (AC8.18): measured once locally on a throwaway branch from `v0.4.0` (ws-f.md, "The 0.4.0
  list-height baseline").
- AC8.17 (the dev-PC fresh-instance run, one Narrator pass, the 26.3 Apply half, the read-only copy of the real
  instance's config/rigtune): Phase 5's P5 agent, results in docs/smoke/first-apply/.

## startup
Owner: WS-W (2L's performance-counter advice, AC2L.5), WS-W2 (AC9.8's launches).
- WS-W's code-deciding run (2026-09-28, this PC, Windows 11 with the counters off, the branch's 26.2 production client,
  not yet the RC jar): the detection read Perflib off and PerfOS's REG_SZ as unusual; CrashReportMixin measured the
  crash-report setup at 3135 ms and Tools showed the advice with "3.1 s"; the Perflib key exported before and after is
  byte-identical. Details in docs/v0.5/design/ws-w.md, "Evidence" (W13). AC2L.5 itself is Phase 5's (RC jar).
- WS-W2's AC9.8 (2026-09-28, this PC, the branch's 26.2 production client on the worktree's dev run dir, under the
  game-test lock; `startup/ac9.8-launch-alerts/`): unchanged launches never raised the notice (runs A, B, E); after adding
  the player's heavier mods, launch 9 of run E was SLOWER +46.8 % with the crash-report setup left out (RW-19) and the
  mod-count cause (7 → 46 mods), shown on the notice line and in Tools; after its Got it, two more slow launches were the
  same streak and raised no second notice. The player's 44 real launches: raw 0 SLOWER; with the setup left out, the 5
  launches of the real 09-20..09-24 slowdown and nothing else (two notices with the streak rule).

## smoke
Owner: the coordinator (3i's production smokes: AC3i.1-AC3i.3).

## real-instance
Owner: the coordinator (3j's read-only re-check of the user's instance against the seeded E2E predictions, AC3j.1, again
before tagging).
