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

## benchmark
Owner: WS-B (RW-5..RW-9, RW-15, BH-1/2, L3; AC2B.5's DH run with the P5 agent).

## e2e
Owner: WS-E (3a-3c: self-update, undo and downgrade E2E per tier, compat040/compat030, tested bytes = published bytes).

## server
Owner: WS-E (3d LAN guest and Realms; AC3f.4's DH server note).

## battery
Owner: WS-E (3e: the battery flow with a simulated battery, the tmpfs OSHI leg).

## launcher
Owner: WS-L1, WS-L2, the P5 agent (4j: the simulated brand and `.index/` runs, the read-only repair-list check, AC4j.5's
user step).

## try-it
Owner: WS-T (AC6.16's A/A pairs, NOW and RESTART tries).

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

## smoke
Owner: the coordinator (3i's production smokes: AC3i.1-AC3i.3).

## real-instance
Owner: the coordinator (3j's read-only re-check of the user's instance against the seeded E2E predictions, AC3j.1, again
before tagging).
