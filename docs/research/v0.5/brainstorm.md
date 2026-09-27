# RigTune v0.5.0 — Feature Brainstorm Synthesis & P1 Selection

Status: synthesis of the 24-candidate v0.5 brainstorm, scored by 7 judge dimensions. This
document picks P1 (this cycle), ranks P2 (next best, deferred), and records what was cut and why.

## 1. Method

**Angles.** Candidates were generated from 6 player-type angles (`new-player`, `laptop`,
`power-user`, `multiplayer`, `modpack`, `reliability`) plus a `merge` bucket for ideas taken
near-verbatim from a single maintainer seed. Angle tags describe where an idea came from, not
a strict audience label — several `merge`-tagged candidates (e.g. C20, C07) are read most
naturally as power-user/enthusiast features.

**The 7 judges and weights.** Every candidate was scored 1–5 on seven dimensions, weighted and
summed to a max of 52.5:

| Dimension | Weight | What it asks |
|---|---|---|
| value-casual | 1.5 | Does a typical, non-technical player benefit? |
| value-enthusiast | 1.5 | Does the target power-user/modpack audience benefit? |
| verifiability | 2.0 | Can it be honestly checked on the one real PC + Linux CI? |
| pipeline-risk | 1.5 | Does it stay inside Apply→journal→Undo, or invent new mutation paths? |
| effort | 1.5 | Is it a believable 1–3 agent-day item? |
| compat | 1.5 | Does it respect the 0.1.x–0.4.x / downgrade guarantees? |
| novelty | 1.0 | Does it add a capability nothing else (in RigTune or the ecosystem) offers? |

(value-casual + value-enthusiast together weight player value at 3/52.5, the single largest
combined share, ahead of verifiability's 2.)

**How picks were made.** Candidates were sorted by weighted total; any candidate carrying a
`blockers` entry in the scoring pass was treated as disqualified from P1 regardless of score,
since a blocker means the effort/verifiability/compat claim in its own pitch cannot be honestly
met in this session. From the remaining ranking, P1 was built by walking down the list and
taking candidates until the ~12 agent-day P1 budget (4–6 features × ~1–3 days) was nearly spent,
deviating from strict rank order only where (a) a lower-ranked candidate served a player type
the higher-ranked set had already covered twice while a comparable-scoring alternative existed,
or (b) two picks would have concentrated edits on the same hotspot files with no comparable
alternative. Ties and near-ties were broken by verifiability and by whether a candidate carried
a blocker (a blocked candidate loses ties even against an equal raw score). See §4 for the
specific deviations and the reasoning for each.

## 2. Ranked table

All scores are out of 5 per dimension; Total is the weighted sum (max 52.5).

| Rank | ID | Title | casual | enthusiast | novelty | pipeline | effort | compat | verif | Total | Blockers |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | C20 | Stutter Doctor one-click fixes | 3 | 5 | 3 | 5 | 3 | 5 | 4 | 42.5 | — |
| 2 | C09 | Measured Try It | 3 | 5 | 4 | 5 | 2 | 5 | 4 | 42 | — |
| 3 | C16 | Per-server profile offers | 3 | 3 | 3 | 5 | 3 | 5 | 5 | 41.5 | — |
| 4 | C07 | Performance overlay HUD | 2 | 5 | 3 | 5 | 3 | 5 | 4 | 41 | — |
| 5 | C08 | Compare benchmark runs | 2 | 4 | 2 | 5 | 3 | 5 | 5 | 40.5 | — |
| 6 | C18 | Launch-time regression alerts | 3 | 3 | 2 | 5 | 4 | 5 | 4 | 40 | — |
| 7 | C19 | Mod-count-aware memory headroom advice | 3 | 2 | 1 | 5 | 4 | 5 | 5 | 39.5 | — |
| 8 | C04 | Crash-report helper: likely mod + safe disable | 4 | 4 | 4 | 5 | 1 | 4 | 4 | 39 | effort: Fabric 0.19.5 crash-report/latest.log layout on 26.2/26.3 UNVERIFIED against a real captured crash |
| 9 | C15 | Server Tune Card | 2 | 2 | 2 | 5 | 4 | 5 | 5 | 39 | — |
| 10 | C02 | First-time Apply trust flow | 5 | 1 | 2 | 5 | 3 | 3 | 5 | 37.5 | — |
| 10 | C10 | Compare profiles | 1 | 2 | 2 | 5 | 4 | 5 | 5 | 37.5 | — |
| 10 | C21 | Entity/redstone benchmark scene | 2 | 4 | 2 | 5 | 3 | 3 | 5 | 37.5 | — |
| 13 | C01 | iGPU awareness: wrong-GPU notice + VRAM caveat | 4 | 3 | 4 | 5 | 2 | 4 | 3 | 37 | — |
| 13 | C17 | Friend Benchmark Compare | 2 | 2 | 3 | 5 | 2 | 5 | 5 | 37 | — |
| 15 | C06 | "Go measure it" nudge after first Apply | 3 | 1 | 1 | 5 | 5 | 3 | 5 | 36.5 | — |
| 15 | C12 | Windows power-plan awareness on battery | 4 | 2 | 4 | 5 | 3 | 5 | 2 | 36.5 | verifiability: neither the real PC nor CI has a battery, so the on-battery gate can never fire live |
| 18 | C03 | Modpack recognition, tailored advice & pack-update notice | 3 | 3 | 3 | 5 | 2 | 4 | 3 | 34.5 | — |
| 18 | C05 | RigTune health & safety net (Doctor/preflight/crash correlation) | 2 | 3 | 4 | 5 | 1 | 4 | 4 | 34.5 | effort: 3 merged sub-features at an already-5-day self-estimate realistically exceed 5 agent-days |
| 18 | C23 | Jar-level optional-mod toggles in profiles | 3 | 3 | 2 | 3 | 3 | 3 | 5 | 34.5 | — |
| 21 | C11 | JVM experiment mode | 1 | 3 | 3 | 5 | 3 | 3 | 4 | 33.5 | — |
| 22 | C13 | Battery template: measured minutes-per-percent | 3 | 3 | 4 | 5 | 1 | 5 | 1 | 31.5 | effort + verifiability: the core measured-drain claim is unverifiable and unsimulatable on this hardware (no battery on the real PC or CI) |
| 22 | C14 | Multiplayer Stutter Doctor (ping correlation) | 2 | 3 | 3 | 5 | 2 | 3 | 3 | 31.5 | — |
| 22 | C24 | Community translation pipeline | 2 | 1 | 1 | 5 | 2 | 5 | 4 | 31.5 | effort: genuine native-speaker verification is external human input the autonomous team cannot manufacture in-session |
| 25 | C22 | Shader-pack profile switching | 3 | 4 | 2 | 1 | 2 | 2 | 4 | 28 | compat: an unrecognized `SET_SHADER_PACK` op throws `JsonParseException` on the whole of `pending.json` on a 0.4.0 downgrade — breaks constraint 6 outright, and `PATCH_PROPERTIES` can likely already patch Iris's config without a new op type |

## 3. P1 picks

Five features, ~12.5 agent-days total, spanning enthusiast/power-user, multiplayer, modpack and
new-player audiences.

### P1.1 — C20: Stutter Doctor one-click fixes (rank 1, 42.5)

- **Player problem.** Stutter Doctor already identifies likely causes (GC pressure, chunk-build
  dominance) and gives advice, but the player has to apply the fix by hand with no confirmation
  it actually helped this session.
- **On-device.** Where `StutterAdvisor`'s confidence gating for a fired item is strong, show a
  "Fix it" button that stages the corresponding setting change through the existing
  `RealController.apply` → journal path, then starts a new monitored session and shows the new
  session's GC%/spike-count next to the prior one via `StutterSummary`. No new capture path, no
  network.
- **Undoable & honest.** The fix is an ordinary journaled Apply, so History → Undo this/last/all
  already covers it. The "Fix it" button is offered only above `StutterAdvisor`'s strong-evidence
  threshold, and the before/after framing must read as "may be related" / measured comparison,
  never a diagnosis, per the honesty rule.
- **Compat.** No new file format; reuses the existing Apply/journal path and Stutter Doctor's own
  opt-in (default-off) session data, so 0.1.x–0.4.x and a 0.4.0 downgrade are untouched.
- **Verification plan.** Unit tests over `StutterAdvisor`'s confidence-gating fixtures confirm
  the button only appears above threshold. A client game test on the real PC forces a
  high-GC-pressure session (small heap), applies the offered fix, and confirms the second
  monitored session shows improved GC% within the existing `StutterMonitor` harness. Linux CI
  runs the same fixture-driven flow headless (no real GC-pressure hardware dependency, since the
  heap size is scripted).
- **Main risks.** Must not overstate causation; wording stays "may be related"/measured. Must
  refuse to offer a fix below strong confidence, to avoid selling a placebo change as a fix.
- **Open questions for the research/spec agent.** (1) Confirm the exact confidence threshold
  `StutterAdvisor` already uses for "strong" vs "moderate" evidence, and whether a new threshold
  constant is needed or an existing one is reused. (2) Decide how "improved" is phrased when the
  second session's GC% is noisier/worse than the first (must still say so honestly, not hide a
  regression). (3) Confirm the before/after comparison UI reuses `StutterScreen` rather than
  forking a new screen.
- **Why it beat the runners-up.** Highest weighted total in the whole set: strong on every axis
  (enthusiast value 5, pipeline-risk 5, compat 5) with no blocker, and it closes the loop on
  RigTune's most sophisticated existing diagnostic feature rather than adding a new subsystem.

### P1.2 — C09: Measured Try It (rank 2, 42)

- **Player problem.** Recommendations carry only a static Impact rating; a skeptical player who
  wants evidence has to run Measure manually before Apply, apply by hand, restart, and run
  Measure again themselves.
- **On-device.** A "Try it (measured)" action runs `BenchmarkSession` in Measure mode at current
  settings (context captured, a pair id minted), calls `RealController.apply(List.of(recommendation), entryId)`
  exactly as Apply does today, re-runs Measure with the paired id after any required restart, and
  shows `BenchmarkMath`'s gain%/noise-floor result with Keep (leaves it applied) or Revert (the
  existing Undo-this on that entryId).
- **Undoable & honest.** No new apply path: Keep leaves an ordinary applied journal entry, Revert
  calls the same Undo-this History already offers. The verdict is gain%/noise-floor phrasing,
  never a bare "faster" claim.
- **Compat.** No new file format or helper operation; it only sequences existing Measure, Apply
  and Undo-this calls, all of which already carry their own compatibility guarantees.
- **Verification plan.** On the real PC: run Try It on a recommendation that needs a restart and
  confirm the after-Measure only runs post-restart with matching paired context; run it on a
  same-session vanilla setting and confirm the gain% matches a manual Measure-before/after;
  confirm Revert restores the exact pre-Try-it file state byte-for-byte. CI covers the
  same-session pairing and Revert-byte-equality paths without a restart dependency.
- **Main risks.** A restart-needing recommendation makes this a two-launch flow; the UI must make
  the wait explicit. Must refuse to offer Try It while a benchmark or download is already running.
- **Open questions for the research/spec agent.** (1) **Effort risk flagged by the judges:** no
  pair-id / cross-restart correlation mechanism exists yet anywhere in `BenchmarkSession.java`
  (confirmed absent by grep) — this is new state, not reuse, and restart-spanning flows have
  historically been the costliest part of this codebase (the v0.4 self-update E2E work). The spec
  agent must scope a same-session-only v1 (no restart pairing) as a fallback if the
  cross-restart pairing can't be built and tested inside its allotted 1–3 days, and say explicitly
  which scope shipped. (2) Where the pair id and its "waiting for restart" state live (a small
  new file vs. an existing state file) and how it's invalidated if the player quits without
  restarting. (3) Whether "refuse while a benchmark or download is running" is a new guard or an
  existing one it can call.
- **Why it beat the runners-up.** #2 score, and the single most-requested honesty upgrade in the
  seed list — it turns a static Impact rating into a real, per-PC number without inventing any
  new apply/undo mechanics. It was kept as P1 despite the flagged pairing-effort risk because the
  fallback (same-session-only v1) keeps it inside budget even if the stretch goal slips.

### P1.3 — C16: Per-server profile offers (rank 3, 41.5)

- **Player problem.** A player might want Quality on a small Realm with friends but Performance
  on a busy public SMP; Profiles only ever holds one active profile and never remembers a
  per-place preference, so switching is manual every session.
- **On-device.** A new state file maps the same HMAC-SHA256-keyed server/LAN/Realm identity
  `ServerLimitsStore` already computes to a saved profile id. On join, if a mapping exists and
  differs from the active profile, `NoticeCenter` surfaces a dismissible, low-priority notice with
  an action that runs the exact same `ProfileSwitch`/Apply path Profiles already uses. The mapping
  is written only when the player explicitly says "remember this for here."
- **Undoable & honest.** A switch taken from the offer is an ordinary Apply, journaled and fully
  covered by Undo this/last/all; it is refused while downloading or benchmarking, and it never
  auto-switches — only ever offers.
- **Compat.** New file only (`server-profiles.json`) under `config/rigtune` using `JsonStateFile`'s
  shared rules; 0.1.x–0.4.x never read or write it, and a downgrade to 0.4.0 simply loses the
  offer feature, which is the documented-safe shape.
- **Verification plan.** Unit tests on the new store (salt handling, pruning, corrupt file, HMAC
  keys, mirroring the existing `ServerLimitsStoreTest`) and on the offer decision logic (deleted/
  renamed profile → no offer; same profile already active → no offer). A client game test on the
  real PC (or a local dedicated server, works identically on Linux CI) joins a local dedicated
  server twice to confirm the save/notice/apply/undo round-trip.
- **Main risks.** Must not fire for singleplayer worlds or feel like nagging; a stale mapping to a
  deleted/renamed profile must fail closed rather than error.
- **Open questions for the research/spec agent.** (1) Confirm `BatteryNoticeSource`'s
  offer-never-switch pattern is reusable as-is for a server-keyed offer, or needs its own
  `NoticeSource` subtype. (2) Decide the exact UX for "remember this for here" — a checkbox on
  the profile-switch screen, or a follow-up prompt after a manual switch. (3) Pruning policy for
  `server-profiles.json` (how many entries, expiry) so it can't grow unbounded over a long-lived
  install.
- **Why it beat the runners-up.** Best verifiability score in the entire set (5/5) and one of the
  cleanest compat stories (a wholly new, isolated file); it is the strongest multiplayer-facing
  pick and the offer-never-switch pattern is already proven by the existing battery-profile
  feature, so pipeline risk is essentially zero.

### P1.4 — C18: Launch-time regression alerts (rank 6, 40)

- **Player problem.** On a big modpack, launch time creeping from 15s to 40s over updates is
  exactly the kind of thing a player wants flagged, but today's Tools screen only shows the last
  run, a median, and a bare "mod set changed" flag.
- **On-device.** Reuses the existing 30-run `startup-times.json` history and the exact
  noise-floor/regression math already built for `BenchmarkTrend` against this same data, phrased
  the same honest way: "Launch time is higher than usual (18.4s vs your usual ~10.2s); may be
  related to your mod set changing (94 → 131 mods) since your last launch."
- **Undoable & honest.** Advice-only; writes only to the existing `startup-times.json` file
  already capped at 16 KiB/30 runs. No Apply/Undo path needed. Wording stays "may be related",
  never a diagnosis.
- **Compat.** Purely additive to the existing 0.4 `startup-times.json` schema; no new persisted
  fields beyond what's already stored, and it never touches `pending.json`/`history.json`/the
  helper.
- **Verification plan.** Unit tests feed synthetic launch-time sequences (varying mod counts and
  durations) through the reused trend math against known thresholds. Manual check on the real PC:
  add/remove a batch of mods between two launches and confirm the alert text and magnitude. CI
  covers the math deterministically with no GPU/hardware dependency at all.
- **Main risks.** Few recorded launches make the baseline noisy; must require a minimum
  comparable-run count before claiming a regression, mirroring `BenchmarkTrend`'s own floor.
- **Open questions for the research/spec agent.** (1) Confirm the minimum-run floor `BenchmarkTrend`
  uses today and whether the same constant is appropriate for launch time (which has different
  natural variance than frame time). (2) Decide whether a Java/driver update in the same window
  should suppress or co-annotate the mod-set-change explanation, to avoid a misleading
  single-cause alert.
- **Why it beat the runners-up.** Cheapest credible pick in the top 10 (2 agent-days, judged
  1–2 by the effort reviewer) and the only modpack-facing candidate that close to the top of the
  ranking; it reuses proven regression math verbatim, so it carries close to zero pipeline or
  compat risk while giving modpack players — a core audience — something new.

### P1.5 — C02: First-time Apply trust flow (rank 10, 37.5)

- **Player problem.** A first-time user sees a wall of pre-checked, unfamiliar settings with no
  idea whether Apply is safe or reversible, and today's generic toast after that first, riskiest
  click gives no concrete confirmation of what changed or how to back out.
- **On-device.** Two one-time `settings.json` flags (`firstRunGuideShown`, `firstApplyDone`)
  follow the exact one-time-toast pattern `StartupNotices` already uses for the privacy notice.
  The first shows a small "Got it" panel above the recommendation list the first time a
  non-empty Report renders; the second, checked right after the first `apply()` call, replaces
  the normal toast with that journal entry's own `HistoryModel` rows plus a one-line
  Undo/History pointer.
- **Undoable & honest.** Writes only its own two booleans in `settings.json`; changes no default
  tick state and no Apply codepath. The confirmation screen reuses `HistoryModel`'s existing rows
  so it can never drift from what Undo actually reverts.
- **Compat.** Two new optional `settings.json` fields only; no rules or journal format touched, so
  0.1.x–0.4.x and a 0.4.0 downgrade are unaffected.
- **Verification plan.** Unit tests for each flag's persistence/one-shot behavior mirroring the
  existing privacy-toast test. A client game test on a fresh config dir asserts the pre-Apply
  panel appears once then stays gone; a first-Apply game test asserts the confirmation screen's
  text matches `HistoryModel`'s rows for that journal entry byte-for-byte. `LangCheckTest` covers
  the new strings. Fully deterministic on CI, no hardware dependency.
- **Main risks.** Screen space at the smallest supported layout (640×480, GUI scale 2) is tight,
  so the pre-Apply panel must collapse to a one-line banner; the confirmation screen must read
  correctly even when the first Apply is instant/vanilla-only, without implying a restart that
  isn't needed.
- **Open questions for the research/spec agent.** (1) Confirm the exact `StartupNotices`
  one-time-toast API surface to extend, and whether the two flags belong there or as new
  top-level `settings.json` keys. (2) Nail down the copy so it never implies a restart when the
  first Apply didn't need one. (3) Decide whether the pre-Apply panel and the post-Apply
  confirmation ship as one PR/feature or can be split if time runs short (the post-Apply
  confirmation alone is the higher-value half).
- **Why it beat the runners-up.** Highest value-casual score of any unblocked candidate (5/5) and
  it is the only new-player-facing pick near the top of the ranking; two comparable-scoring but
  redundant power-user picks (C07, C08 — see §4) were passed over specifically so the P1 set
  reaches new players, not just the enthusiast audience C20/C09 already serve.

**P1 total effort:** 2.5 + 3 + 2.5 + 2 + 2.5 ≈ 12.5 agent-days (self-estimated), against the
~12-day budget. C09's own estimate carries a flagged effort risk (see its open questions); its
same-session fallback keeps the set inside budget if the stretch goal slips.

## 4. Deviations from the pure ranking

- **C07 (rank 4, 41) and C08 (rank 5, 40.5) were not taken**, despite outscoring C18 and C02.
  Reason: both are power-user-audience candidates, and C20 and C09 (ranks 1–2) already serve that
  same audience at the top of the list; taking C07 and C08 as well would have put 4 of 5 P1 slots
  behind the enthusiast/power-user audience and left new players and modpack players unserved,
  contrary to "prefer a set that together serves several player types." C18 (modpack) and C02
  (new-player) are comparable-scoring alternatives (40 and 37.5, both inside the same rough tier)
  that reach audiences the top-5-by-score set would have missed entirely. C07 and C08 are carried
  forward at the top of P2 and are the first candidates to add back if the budget stretches.
- **C19 (rank 7, 39.5) was left out of P1** in favor of C18 (rank 6, 40) for the modpack slot: the
  two are close in score and near-identical in shape (both reuse an existing math/rules
  mechanism, both are 1–2 day items), but C18 scores higher and is more directly actionable advice
  (a concrete regression number vs. a generic "consider more headroom" line), so only one modpack
  pick was kept to leave room for the new-player pick. C19 is top of P2.
- **C04 (rank 8, 39) was not promoted to P1** despite scoring above C15, C02, C10 and C21: it
  carries a stated blocker (the Fabric 0.19.5 crash-report/`latest.log` layout on 26.2/26.3 is
  unverified against a real captured crash), which is exactly the kind of blocker the task
  instructions treat as a reason to deviate from raw rank. It stays at the top of P2 with a note
  that a short research spike (capture one real crash, confirm the file layout) would very likely
  clear it for a v0.5.1 cycle.
- **No hotspot conflict required dropping a pick.** C20, C02 and C09 all call
  `client/RealController.apply` and touch `core/history/Journal`, which is one of the hotspot
  files the task calls out by name. This was checked deliberately: all three only *call* the
  existing `apply(...)` entry point as consumers (none changes its signature or adds a new op
  type), so three parallel agent workstreams can build against it without fighting over the same
  lines. This is noted here rather than used to drop a pick, since no comparable-scoring
  alternative would have improved on it.
- **C05's blocker (effort, not a fundamental impossibility) was treated as a reason to defer
  rather than cut.** Its three merged sub-features (Doctor, preflight, crash-correlation) exceed
  the P1 per-feature budget as scoped, but each sub-piece is independently small and low-risk; it
  is kept in P2 with an explicit note to split it into 2–3 separate P1-sized items for a future
  cycle rather than build all three at once.

## 5. P2 — ranked, next best

1. **C07** — Performance overlay HUD: live GC/chunk/tick-attributed frame-time graph reusing
   Stutter Doctor's rings; zero new capture path, held back only for player-type balance.
2. **C08** — Compare benchmark runs: side-by-side run diff plus CSV/JSON export over existing
   `benchmarks.json`; clean and cheap, held back only for balance.
3. **C19** — Mod-count-aware memory headroom advice: one honest advice line behind the `requires`
   escape hatch; small, safe, redundant with C18's modpack slot this cycle.
4. **C15** — Server Tune Card: one-click, address-free "copy for this server" text block from a
   benchmark result; stateless, no schema touched.
5. **C04** — Crash-report helper: likely mod + safe disable; strong value, needs a short research
   spike on the real Fabric 0.19.5 crash-report format before it can be honestly scoped.
6. **C10** — Compare profiles: read-only key-by-key diff between two profiles/templates.
7. **C21** — Entity/redstone-heavy benchmark scene: a second scripted scene in the existing
   benchmark world for farm/contraption-heavy builds.
8. **C01** — iGPU awareness: wrong-GPU notice + VRAM caveat; real value for hybrid-graphics
   laptops/desktops, but the VRAM-threshold cutoff needs research before it can ship as a hard
   number.
9. **C17** — Friend Benchmark Compare: a small, bounded pasted code (not a share code) for
   comparing two friends' hardware/measured numbers offline.
10. **C06** — "Go measure it" nudge after the first Apply: a single one-time notice pointing at
    the existing Measure flow.
11. **C03** — Modpack recognition, tailored advice & pack-update notice: labels recommendations
    already covered by Fabulously Optimized/Additive using the already-shipped `upstream` rules
    field; live-pack recognition stays unverified beyond CI fixtures on this maintainer's dev
    instance.
12. **C05** — RigTune health & safety net: split into Doctor / preflight / crash-correlation
    before scheduling; each piece alone is a credible future P1 item.
13. **C23** — Jar-level optional-mod toggles in profiles: reuses the existing disable/enable
    machinery to let a profile switch also toggle companion mods.
14. **C11** — JVM experiment mode: labels a Measure-before/after pair as a JVM-argument
    experiment, extending the existing benchmark-pairing math to the one lever RigTune can't
    flip itself.
15. **C14** — Multiplayer Stutter Doctor (ping correlation): tags a stutter spike with elevated
    ping when the two overlap; the exact vanilla ping hook for 26.2/26.3 is unverified and needs
    a check before implementation.

## 6. Cut

- **C12** — Windows power-plan awareness on battery: the feature's entire trigger is "on battery
  plus stock Power Saver scheme," and neither the real PC nor Linux CI has a battery, so the live
  gate can never be exercised, only mocked. Cut for this session; revisit if a laptop test rig
  becomes available.
- **C13** — Battery template: measured minutes-per-percent: same root problem as C12 but worse —
  even the core measured claim (drain rate) can't be produced or checked at all on the stated
  hardware. Cut.
- **C22** — Shader-pack profile switching: its own compat story is wrong. `PendingActions.Type` is
  a plain Gson enum with no fallback, so an unrecognized `SET_SHADER_PACK` constant throws on the
  *whole* of `pending.json`, not just that op — breaking the hard "a 0.4.0 downgrade keeps
  working" constraint for any player with a pending shader change. Cut as scoped; a future
  redesign that patches Iris's shader-selection file through the existing `PATCH_PROPERTIES` op
  (which may already suffice) instead of adding a new enum constant could resurrect this cheaply.
- **C24** — Community translation pipeline: the seed's own bar ("verified... or explicitly marked
  unverified") is technically satisfiable with zero human dependency by marking every shipped
  language unverified, so the stated blocker is arguably softer than the judges scored it — but
  even so its weighted total (31.5) sits well below the P1/P2 cut line on player value and
  novelty alone, so it stays cut for v0.5.0 on merit, not just the blocker.

## 7. Fate of each seed idea

- **Hybrid-GPU / integrated-GPU detection** → became **C01** (iGPU awareness: wrong-GPU notice +
  VRAM caveat). P2 — real value, but the VRAM-threshold cutoff needs research before shipping.
- **Measured "Try it"** → became **C09** (Measured Try It, **P1**) and partly informed **C13**
  (Battery template measured minutes-per-percent, **Cut** — unverifiable on this hardware).
- **Stutter Doctor one-click fixes** → became **C20**, taken almost verbatim. **P1**, rank 1.
- **Per-server / per-world profiles** → became **C16** (offer, never auto-switch). **P1**, rank 3.
- **Crash-report helper** → became **C04**. **P2** — strong candidate, blocked this cycle only by
  an unverified Fabric 0.19.5 crash-report format that a short research spike should clear.
- **Entity- and redstone-heavy benchmark scene** → became **C21**, taken directly. **P2**.
- **Shader-pack profile switching** → became **C22**. **Cut** — its own new-op-type design breaks
  the 0.4.0-downgrade compatibility guarantee.
- **Jar-level optional-mod toggles in profiles** → became **C23**, taken directly. **P2**.
- **A lightweight performance overlay** → became **C07**, taken directly. **P2** — high-scoring,
  held back from P1 only to balance player-type coverage against C20/C09.
- **Modpack awareness** → became **C03** (recognition + upstream labelling + pack-update notice).
  **P2** — the core `upstream` rules data already ships; this is UI/matching work on top of it.
- **A community translation pipeline** → became **C24**. **Cut** — low weighted value even before
  accounting for its external-verification caveat.

## 8. Candidate details (full text)

### C01 — iGPU awareness: wrong-GPU notice + VRAM caveat
- **Angles:** new-player, laptop
- **Merged from:** Integrated-GPU trap warning (new-player); Hybrid-GPU notice: "you're running
  on the integrated GPU" (laptop); Low-VRAM / shared-memory iGPU guardrail on Preview (laptop)
- **Pitch:** When the active render GPU is classified integrated while OSHI also sees an idle
  discrete card, RigTune raises a top-priority notice with the exact Windows steps (and the
  running javaw.exe path) to switch; separately, whenever VRAM-keyed recommendations run on that
  same integrated GPU, Preview adds an honest caveat that the VRAM figure may not reflect what's
  actually usable.
- **Player problem:** Hybrid-graphics laptops and APU+dGPU desktops often get silently assigned to
  the weak GPU with no in-game explanation, and separately an integrated GPU's reported VRAM is
  often a misleading BIOS-reserved figure, so any recommendation keyed off it (render distance, DH
  quality) can be quietly wrong for exactly the players who need it right.
- **How it works:** `GpuClassifier.classify`'s `integratedHeuristic` already flags the active
  `GpuInfo` as integrated; when `HardwareProbe.probeSlow`'s OSHI card list also has a
  non-integrated card with materially more VRAM (reusing `matchVram`'s normalise/scoring), fire a
  new top `NoticePriority` slot with Settings > System > Display > Graphics steps naming the
  discrete card and `ProcessHandle.current().info().command()`. Independently, when `vramMb` on
  that integrated card is below a small threshold or -1, set a `HardwareProfile.flags` entry
  ('igpu-vram-uncertain') that Preview and the tier tooltip read to append one caveat line next to
  any recommendation whose rule used vram.
- **Builds on:** `HardwareProbe.probeFast/probeSlow/matchVram`, `GpuClassifier.classify/integratedHeuristic`,
  `HardwareProfile.flags`, `core/notice/NoticeBoard` + `NoticePriority`, `client/notice/NoticeSource`,
  `core/launcher/LauncherAdvice`'s step pattern, the Preview dry-run screen
- **Undo & safety:** Advice-only throughout: the notice and the caveat are dismissible/display text
  with no Apply, staging or journal involvement; the only persisted state is a dismiss flag in
  `awareness.json` and a `HardwareProfile` flag, both following the existing optional-field,
  fail-closed pattern.
- **Verification plan:** Real PC: force Minecraft onto the maintainer's own integrated Radeon via
  Windows' per-app GPU override (with the real RX 7800 XT idle) to confirm the notice fires with
  real renderer strings and clears when switched back; unit tests feed `GpuClassifier`/`HardwareProbe`
  synthetic OSHI-shaped multi-card lists for both the notice-selection and the VRAM-threshold logic
  (impossible on Linux CI's single llvmpipe/lavapipe device, which never reports two cards, but the
  software-tier path is itself tested there to confirm no collision with the caveat).
- **Compat notes:** New `NoticePriority` slot plus one new `HardwareProfile` flag only, no
  rules-schema change; if either signal is later expressed as a rules condition it needs `requires`
  per docs/RULES_SCHEMA.md so 0.1.x–0.4.x ignore it safely.
- **Risks:** OSHI's card naming/VRAM reporting is unreliable enough that a wrong read could
  misfire on a genuinely single-GPU PC or under-warn on a real hybrid rig; must fail closed (say
  nothing) whenever confidence is short of `GpuClassifier`'s own bar, and the VRAM threshold is
  currently a guess that needs research before shipping as a hard cutoff.
- **Effort:** 3.5 days. **Seed:** Hybrid-GPU / integrated-GPU detection.

### C02 — First-time Apply trust flow
- **Angles:** new-player
- **Merged from:** Before-you-Apply trust panel; First-Apply confirmation and Undo pointer
- **Pitch:** The first time a new player opens RigTune, a small dismissible panel says only ticked
  items change, Preview shows every file first, and Undo reverts anything; the moment their
  first-ever Apply finishes, a short screen lists exactly what changed and says History → Undo
  reverts any one of them, the last one, or everything.
- **Player problem:** A first-time user sees a wall of pre-checked, unfamiliar settings with no
  idea whether Apply is safe or reversible, and today's generic toast after that first, riskiest
  click gives no concrete confirmation of what changed or how to back out.
- **How it works:** Two one-time `settings.json` flags (`firstRunGuideShown`, `firstApplyDone`)
  follow the exact one-time-toast pattern `StartupNotices` already uses for the privacy notice:
  the first shows a small 'Got it' panel above the recommendation list the first time a non-empty
  Report renders; the second, checked right after the first `apply()` call, replaces the normal
  toast with that journal entry's own `HistoryModel` rows (the same text History already computes)
  plus a one-line Undo/History pointer.
- **Builds on:** `client/ClientSettings` + `client/StartupNotices`, `client/ui/RigTuneScreen`,
  `client/RealController.apply`, `core/history/Journal` + `core/history/HistoryModel`
- **Undo & safety:** Writes only its own two booleans in `settings.json`; changes no default tick
  state and no Apply codepath, and the confirmation screen reuses `HistoryModel`'s existing rows
  so it can never drift from what Undo actually reverts.
- **Verification plan:** Unit tests for each flag's persistence/one-shot behavior mirroring the
  existing privacy-toast test; a client gametest on a fresh config dir asserts the pre-Apply panel
  appears once then stays gone, and a first-Apply gametest asserts the confirmation screen's text
  matches HistoryModel's rows for that journal entry byte-for-byte; LangCheckTest covers the new
  strings.
- **Compat notes:** Two new optional settings.json fields only; no rules or journal format
  touched, so 0.1.x–0.4.x and a 0.4.0 downgrade are unaffected.
- **Risks:** Screen space at the smallest supported layout (640x480 GUI scale 2) is tight, so the
  pre-Apply panel must collapse to a one-line banner; the confirmation screen must read correctly
  even when the first apply is instant/vanilla-only, without implying a restart that isn't needed.
- **Effort:** 2.5 days. **Seed:** none.

### C03 — Modpack recognition, tailored advice & pack-update notice
- **Angles:** new-player, modpack
- **Merged from:** "You're on a curated pack" recognition (new-player); Modpack recognition and
  tailored advice (modpack); Pack update notice: what changed in your pack (modpack)
- **Pitch:** RigTune names the curated pack you're on (Fabulously Optimized, Additive), marks
  which recommendations it already ships versus what you bolted on top, and later tells you what
  changed in the pack itself when the weekly rules refresh updates its mod list.
- **Player problem:** On a curated modpack, RigTune's short, honest recommendation list reads as
  broken rather than as 'this pack already covers it', and updating the instance gives no idea
  what the pack's maintainers actually changed.
- **How it works:** Verified live in rules/rules-v2.json: every rule already carries a per-mod
  `upstream.fabulouslyOptimized`/`upstream.additive` boolean, and a top-level
  `upstream.fabulouslyOptimized.slugs`/`additive.slugs` array is already shipped, built weekly by
  tools/update_rules.py. Compare `ModScanner.scan()`'s `InstalledMod` ids against each pack's slug
  list (containment ratio) to show 'Looks like Fabulously Optimized' and use each rule's own
  per-mod upstream flag to mark it 'already in your pack' vs new; store the current slug list in
  awareness.json and, when a later rules update changes it, raise a WHATS_NEW-tier notice naming
  what was added/removed.
- **Builds on:** `core/rules/RulesDocument.upstream`, `tools/update_rules.py`,
  `client/probe/ModScanner.scan()/InstalledMod`, `core/recommend/Recommender`,
  `core/awareness/WhatsNew` + `client/notice/WhatsNewNoticeSource`, `core/notice/NoticeBoard`
- **Undo & safety:** Read-only classification and dismissible notice; the only persisted state is
  the pack label/slug snapshot in the existing awareness.json, no Apply or file write involved.
- **Verification plan:** Unit tests feed synthetic mod-id sets against the real, checked-in
  FO/Additive slug lists for the containment threshold; a client gametest loads a scripted
  FO-like mod set and asserts both the recognition banner and, on a second fixture rules document
  with a changed slug list, the update notice; the maintainer's own ~50-mod dev instance isn't
  itself FO/Additive, so live-pack recognition on a real curated install stays unverified beyond
  CI fixtures.
- **Compat notes:** Reuses the already-shipped, optional `upstream` rules-v2 field (absent-safe for
  older readers) and adds only optional awareness.json fields, so 0.1.x–0.4.x compatibility and a
  0.4.0 downgrade are untouched.
- **Risks:** One contributing draft assumed the upstream field still needed to be built; it is
  already live, so scope should focus on the matching/UI, not new rules-authoring; modpacks fork
  and rename often, so a stale signature should default to no banner rather than a wrong one, and
  a pack that churns weekly needs the update notice throttled to meaningful diffs.
- **Effort:** 4.5 days. **Seed:** Modpack awareness.

### C04 — Crash-report helper: likely mod + safe disable
- **Angles:** modpack, reliability
- **Merged from:** Crash-report helper (modpack); Likely-crash mod finder with safe disable
  (reliability)
- **Pitch:** On the launch right after a crash, RigTune reads the newest crash report and
  latest.log on-device, and only when stack-frame evidence for one still-installed mod is a clear
  plurality does it say that mod is 'likely involved', with one click to stage its disable through
  the existing undoable machinery plus a prefilled Report a problem.
- **Player problem:** A modpack crash dumps a wall of stack trace the player can't read; today
  they must guess which of dozens of mods to blame and manually disable candidates one at a time
  with no safety net.
- **How it works:** `RigTunePreLaunch.onPreLaunch()` gains one bounded step: list crash-reports/,
  read only the newest file plus latest.log up to a size cap, and count stack-frame hits against a
  small curated package-prefix-to-modId table for mods still loaded (cross-checked via
  ModScanner). A NoticeCenter source fires only above a clear-plurality threshold and only if that
  mod is still active, offering the existing 'Disable X' path and an IssueLink-prefilled Report a
  problem; the only new state is a small marker recording which crash file was already looked at.
- **Builds on:** `client/RigTunePreLaunch`, `client/probe/ModScanner`, `client/undo/DisableGuard` +
  `core/history/FolderCheck`, `core/history/Journal` + `core/history/UndoPlanner`,
  `core/report/IssueLink` + `core/report/ShareReport` + `MarkdownSafe`, `client/notice/NoticeCenter`
- **Undo & safety:** The only file-changing action is the existing `DISABLE_FILE` Apply → journal
  → Undo path, unchanged; the crash text itself is read on-device only and never leaves the device
  unless the player presses Report a problem.
- **Verification plan:** On the real PC: trigger three real crashes on 26.2 (a test mod throwing
  from render/tick, a deliberately tiny -Xmx, a mixin conflict) and confirm only the injected mod
  is named and Undo reverts the disable; Linux CI writes synthetic crash-reports/*.txt fixtures
  (huge, non-UTF-8, ambiguous multi-mod) to exercise the size cap and the 'no clear plurality ->
  name nobody' case offline.
- **Compat notes:** Adds no new pending.json op, no new helper operation, and works fully offline;
  the exact crash-report/latest.log text layout for Fabric Loader 0.19.5 on 26.2/26.3 is
  UNVERIFIED in this session and must be checked against a real captured crash before
  implementation.
- **Risks:** A package appearing in a stack trace doesn't prove that mod's own code is at fault
  (it can crash inside another mod's mixin or be an innocent bystander), the single biggest
  honesty risk, mitigated by firing only on a clear plurality and always saying 'likely, based on
  N stack frames' rather than naming a cause.
- **Effort:** 3.5 days. **Seed:** Crash-report helper.

### C05 — RigTune health & safety net (Doctor, preflight, crash correlation)
- **Angles:** reliability
- **Merged from:** RigTune Doctor; Safer Apply preflight; Last-launch crash correlation notice
- **Pitch:** A read-only 'Check RigTune...' view in Tools surfaces RigTune's own health signals (a
  stuck apply lock, a corrupted state file, an unfinished helper group) in plain English; the same
  lock probe backs a Preview-time warning when the mods folder sits inside OneDrive or disk space
  looks tight; and if Minecraft crashed right after a RigTune change, a dismissible notice names
  that one change with a direct Undo-this link.
- **Player problem:** When RigTune's own state goes wrong, or an Apply is about to hit a slow retry
  path, or a crash follows a change the player made, today the only trace is a WARN line in
  latest.log or a terse History reason the player never connects to what just happened.
- **How it works:** A `doctor()` call probes `ApplyLock.acquire(path, Duration.ZERO)` exactly as
  `RigTunePreLaunch` already does, reads each `JsonStateFile.load()` result for
  MOVED_ASIDE/UNREADABLE/NEWER states, and lists `PartlyApplied`/`UnfinishedGroups` entries and
  orphaned marker files, all as 'fact + age' rows with a safe next step. The same lock probe plus
  an `InstanceDirs` path check (a literal 'OneDrive' segment) and a `FileStore` usable-space check
  against `DownloadPlanner`'s staged sizes add advisory lines to `PreviewScreen`. Separately,
  `RigTunePreLaunch` compares a new crash file's mtime against the previous launch's `StartupTimes`
  window and, if a Journal entry falls in that same window, offers `UndoPlanner.planEntry` for it
  once.
- **Builds on:** `core/apply/ApplyLock`, `core/history/Journal.state()`, `core/store/JsonStateFile`,
  `core/history/PartlyApplied` + `UnfinishedGroups`, `client/ui/ToolsScreen`,
  `client/ui/PreviewScreen` + `core/preview/PreviewPlanner`, `core/apply/InstanceDirs`,
  `core/modrinth/DownloadPlanner`, `client/RigTunePreLaunch`, `client/footprint/StartupTimes`,
  `core/history/UndoPlanner.planEntry`, `client/notice/NoticeCenter`
- **Undo & safety:** Entirely read-only except the crash-correlation notice's single action, which
  is the existing 'Undo this' path (confirm screen -> journal); the only new persisted state is a
  small 'already offered for crash at time T' marker in RigTune's own state file.
- **Verification plan:** Unit tests per JsonStateFile.State value and per PartlyApplied/
  UnfinishedGroups fixture; a client gametest hand-corrupts stutter.json and asserts the doctor
  screen names it; on the real PC, kill ApplyHelper mid-run to confirm 'stuck' only reports while
  the OS lock is genuinely held, and stage a change before a forced crash to confirm the
  correlation notice and its Undo round-trip; a stubbed low-space/OneDrive-path FileStore covers
  the preflight lines on Linux CI.
- **Compat notes:** Nothing new is persisted beyond the one small crash-notice marker, never
  pending.json or history.json, so every 0.1.x–0.4.x and 0.4.0-downgrade guarantee holds
  trivially; FileStore.getUsableSpace's exact JDK 25 signature needs a docs check before
  implementation.
- **Risks:** Must avoid over-alarming players about a normal, self-healing transient state by
  phrasing rows as facts with ages, not verdicts; the hardcoded 'OneDrive' string is
  Windows/English-specific; crash correlation is not causation and must stay 'may be related',
  opt-in, and capped to once per distinct journal entry.
- **Effort:** 5 days. **Seed:** none.

### C06 — "Go measure it" nudge after the first Apply
- **Angles:** new-player
- **Merged from:** none
- **Pitch:** Right after a new player's first Apply, one small notice reminds them the picks were
  estimates and points straight at Tools -> Benchmark -> Measure for the real number on their own
  PC.
- **Player problem:** A new player has no reason to know RigTune's tier and settings are estimates
  until measured, so most first-timers never discover the benchmark or get the honest, measured
  confirmation the mod is built around.
- **How it works:** A new NoticeSource fires once, gated by a one-time flag set right after the
  first-ever Apply completes, pointing at BenchmarkMenuScreen's Measure mode with copy such as
  'Your new settings are estimates for your hardware -- run Measure to see the real number'; it
  stays silent if a Measure run already exists for this scene, and dismissing or running Measure
  clears it for good.
- **Builds on:** `core/notice/NoticeBoard` + `NoticePriority` + `client/notice/NoticeSource`,
  `client/ui/ToolsScreen` -> `BenchmarkMenuScreen`, `core/benchmark/BenchmarkHistory`
- **Undo & safety:** Advice-only notice; the only new state is a dismiss/shown flag alongside
  other notices' storage, and it never launches or auto-runs the benchmark itself.
- **Verification plan:** A client gametest drives a first Apply on a fresh profile and asserts the
  notice appears once, then confirms it's gone after dismissing it or completing a Measure run
  recorded in benchmarks.json; unit test the one-time gating logic directly.
- **Compat notes:** One new notice source and one new settings/awareness field only; no change to
  Apply, Undo, journal or benchmark file formats.
- **Risks:** Distinct from Measured Try It (C09): this is a small, purely informational pointer at
  the existing manual Measure flow and should not be built as a substitute if the team also wants
  that larger feature.
- **Effort:** 1 day. **Seed:** none.

### C07 — Performance overlay HUD
- **Angles:** power-user
- **Merged from:** none
- **Pitch:** A keybind-toggled in-game overlay graphs frame time live, with GC/chunk/tick colour
  bands and spike markers pulled straight from Stutter Doctor's own attribution, so a power user
  can watch cause-and-effect while they play.
- **Player problem:** Enthusiasts already run F3/spark, but Stutter Doctor's own evidence (GC%,
  chunk-building attribution) is locked inside a post-session report, with no live view
  correlating a visible hitch with its measured cause.
- **How it works:** A new render-phase overlay reads the existing FrameRing/StutterRings via a
  small poll (no new capture path) and draws a scrolling frame-time graph plus spike markers
  labelled with Attributor's cause when the session monitor is on; a new keybind toggles it, off
  by default and independent of Stutter Doctor's own switch, drawing from a fixed float[] backing
  array with zero per-frame allocation.
- **Builds on:** `client/stutter/StutterMonitor.java`, `core/stutter/StutterRings.java`,
  `core/stutter/FrameRing.java`, `core/stutter/Attributor.java`, `core/stutter/SpikeDetector.java`,
  `client/mixin/DebugScreenOverlayMixin.java`
- **Undo & safety:** Advice/observation-only: draws pixels and writes/reverts nothing under
  RealController.apply, so no journal entry or Undo is needed; opt-in exactly like the session
  monitor it piggybacks on.
- **Verification plan:** On the real PC, toggle the overlay during a session with forced full GCs
  and confirm markers align with StutterScreen's own report for the same session; measure the
  overlay's own per-frame cost and add it as its own opt-in row in tools/footprint-budgets.json,
  verified on Linux CI (Mesa llvmpipe) with monitor+overlay both on and both off.
- **Compat notes:** New keybind and opt-in draw call only, adds no new file, rules field, or
  helper-visible data, so 0.1.x–0.4.x and a 0.4.0 downgrade are unaffected.
- **Risks:** Rendering markers cheaply without per-frame allocation is the main engineering risk;
  scope stays capped to reusing existing ring data rather than a new sampling path.
- **Effort:** 2.5 days. **Seed:** A lightweight performance overlay.

### C08 — Compare benchmark runs
- **Angles:** power-user
- **Merged from:** none
- **Pitch:** A 'Compare...' view in Benchmark history puts any two runs side by side, every knob
  and measured number, differences highlighted, plus a one-click CSV/JSON export for players who
  want to chart it themselves.
- **Player problem:** BenchmarkTrend only ever compares the latest run against its own noise
  floor; a power user who wants to line up an old run against today's, or pull raw numbers into a
  spreadsheet, has no way to do either without opening benchmarks.json by hand.
- **How it works:** A new CompareScreen reads two BenchmarkRecord entries and renders every field
  of Result, KnobResult, Cost and Context next to each other, marking a row when
  Context.sameConditions() is false; an Export button serialises the visible on-device records to
  CSV via a small new writer using MarkdownSafe-style escaping, writing only into
  config/rigtune/ and never sending anything anywhere.
- **Builds on:** `core/benchmark/BenchmarkHistory.java`, `core/benchmark/BenchmarkRecords.java`,
  `core/benchmark/BenchmarkRecord.java`, `core/benchmark/BenchmarkTrend.java`,
  `core/benchmark/BenchmarkMath.java`, `core/report/MarkdownSafe.java`
- **Undo & safety:** Read-only over existing benchmarks.json; the export writes one new file with
  no gameplay or mod-file effect, needing no journal entry, matching Copy report/Copy summary
  today.
- **Verification plan:** Unit tests compare a golden pair of BenchmarkRecord fixtures and assert
  every highlighted-difference row; a game test runs two real Measure sweeps on the dev PC and
  checks Compare's highlighted rows match the actual changed settings; export is checked
  byte-for-byte against a golden CSV.
- **Compat notes:** Adds a new screen and export helper only, reading benchmarks.json exactly as
  BenchmarkHistory already does and writing no new field into any existing state file.
- **Risks:** Runs from different scenes/versions/resolutions are still legal to compare (flagged
  as different conditions); the UI must never imply a direct performance claim across
  incomparable runs.
- **Effort:** 2 days. **Seed:** none.

### C09 — Measured Try It
- **Angles:** power-user
- **Merged from:** none
- **Pitch:** Turn any single recommendation into one button that applies it, benchmarks before and
  after under identical conditions, and shows the real 1% low delta with Keep/Revert, so the
  Impact rating stops being a guess and becomes a number from the player's own PC.
- **Player problem:** Recommendations carry only a static Impact rating; a power user who wants
  evidence has to run Measure manually before Apply, apply by hand, restart, and run Measure again
  themselves.
- **How it works:** A 'Try it (measured)' action runs BenchmarkSession in Measure mode at current
  settings (context captured, pairId minted), calls RealController.apply(List.of(recommendation),
  entryId) exactly as Apply does today, re-runs Measure with the paired id, and shows
  BenchmarkMath's gain%/noise-floor result with Keep (leaves it applied) or Revert (the existing
  Undo-this on that entryId); no new apply path is added.
- **Builds on:** `core/benchmark/BenchmarkSession.java`, `core/benchmark/BenchmarkRecord.java`,
  `core/benchmark/BenchmarkMath.java`, `client/RealController.java apply(List<Recommendation>, String entryId)`,
  `core/undo/UndoPlanner.java`, `core/model/Journal.java`
- **Undo & safety:** Uses the existing journal/Undo path unchanged: Keep leaves an ordinary applied
  entry, Revert calls the same Undo-this History already offers, so no new undo logic is
  introduced.
- **Verification plan:** On the real PC, run Try It on a recommendation needing a restart and
  confirm after-Measure only runs post-restart with matching paired context; run it on a
  same-session vanilla setting and confirm the gain% matches a manual Measure-before/after;
  confirm Revert restores the exact pre-Try-it file state byte-for-byte.
- **Compat notes:** Introduces no new file format or helper operation; it sequences existing
  Measure, Apply and Undo-this calls, so every existing compatibility guarantee for those three
  paths already covers it.
- **Risks:** A recommendation needing a restart makes this a two-launch flow; the UI must make the
  wait explicit, and the feature must refuse to offer Try It while a benchmark or download is
  already running.
- **Effort:** 3 days (judged: realistically 3–5, no pairId mechanism exists today). **Seed:**
  Measured "Try it".

### C10 — Compare profiles
- **Angles:** power-user
- **Merged from:** none
- **Pitch:** A 'Compare...' button in Profiles lines up two profiles or templates key by key, so a
  power user can see exact setting values before switching, not just a name.
- **Player problem:** Profiles switches whole setups in one click and Preview shows what an Apply
  would change, but there is no way to look at two profiles' full value tables side by side before
  committing to either one.
- **How it works:** A new CompareProfilesScreen resolves two profile/template ids through the same
  EffectiveSettings/ProfileTemplates layering Preview already uses and renders one row per
  ShareKeys.MANAGED key, greying out identical rows and bolding differing ones; it applies
  nothing, so it needs no PreviewPlanner path of its own.
- **Builds on:** `core/profile/ProfileStore.java`, `core/profile/ProfileTemplates.java`,
  `core/profile/EffectiveSettings.java`, `core/profile/ShareKeys.java`, `core/profile/ProfileView.java`
- **Undo & safety:** Read-only: resolves and displays two value maps, applies and stages nothing,
  so no journal entry, Undo state, or download side effect is involved.
- **Verification plan:** Unit test comparing two golden ShareKeys value maps against hand-computed
  diff expectations; on the real PC, compare Max FPS against Battery and confirm every highlighted
  row matches README's documented per-template values; compare a saved profile against 'My
  settings' after one vanilla-setting change and confirm exactly that row is highlighted.
- **Compat notes:** Read-only screen over existing ProfileStore/ProfileTemplates data; adds no new
  field to profiles.json and no new share-code content.
- **Risks:** Thread counts are local-only per-PC, so an imported profile compared against a local
  one must show those rows as 'not part of this profile' rather than a misleading blank-vs-value
  diff.
- **Effort:** 1.5 days. **Seed:** none.

### C11 — JVM experiment mode
- **Angles:** power-user
- **Merged from:** none
- **Pitch:** Label a pair of Measure runs as a JVM-arguments experiment (before/after a launcher
  flag change) and get the same honest gain%/noise-floor verdict Measure already gives for
  in-game settings, applied to the one lever RigTune can't flip itself.
- **Player problem:** The JVM & memory screen shows flag changes mostly move memory, not FPS, but a
  player who wants to verify that for their own PC has to remember to run Measure by hand before
  and after editing launcher flags and eyeball two numbers themselves.
- **How it works:** Extends the existing Measure-before/after pairing with the already-probed
  JvmSnapshot recorded alongside each paired BenchmarkRecord's Context; JVM & memory's screen gets
  a 'Measure before you change your Java arguments' button starting the paired Measure, and after
  a restart with different launcher flags, offers 'Measure after' once the JVM snapshot changed,
  then shows BenchmarkMath's gain% with the same honesty rules Benchmark history already applies.
- **Builds on:** `core/jvm/JvmSnapshot.java`, `core/jvm/JvmFacts.java`, `core/jvm/JvmReport.java`,
  `core/benchmark/BenchmarkRecord.java`, `core/benchmark/BenchmarkMath.java`,
  `core/benchmark/BenchmarkTrend.java`
- **Undo & safety:** Advice/measurement-only, consistent with the non-goal that RigTune never
  changes launcher RAM or Java args itself; nothing here goes through RealController.apply or the
  journal.
- **Verification plan:** On the real PC, reproduce README's own G1-vs-Aikar's-flags comparison end
  to end and confirm the reported verdict matches the already-published conclusion; confirm the
  feature refuses to pair two runs whose JVM snapshot didn't actually change.
- **Compat notes:** Adds one more optional field to BenchmarkRecord.Context the same way
  modSetHash/journalCursor were added in 0.4, so 0.1.x–0.4.x readers and a 0.4.0 downgrade are
  unaffected.
- **Risks:** Must not cross the 'never sell GC flags as FPS gains' line; the verdict has to
  foreground memory/GC-pause differences honestly rather than implying a flag change will help
  FPS.
- **Effort:** 2.5 days. **Seed:** none.

### C12 — Windows power-plan awareness on battery
- **Angles:** laptop
- **Merged from:** none
- **Pitch:** RigTune reads the active Windows power plan via powercfg and, only when it's
  confidently the stock 'Power saver' scheme while on battery, offers a notice with the exact
  Settings steps to switch to Balanced, never claiming a hard limit.
- **Player problem:** Laptop players get throttled by Windows' own power-plan choice independent of
  anything RigTune's Battery profile controls, and most players have never opened Power & battery
  settings.
- **How it works:** A new async, timeout-guarded probe (mirroring JvmProbe's pattern) runs
  `powercfg /getactivescheme` through ProcessBuilder with a byte cap, parses the GUID, and matches
  it against Windows' documented stable default GUIDs (an unrecognised custom-plan GUID is treated
  as unknown, fail-closed); combined with the already-probed HardwareProfile.onBattery, a new
  NoticePriority fires only for the known Power-saver GUID on battery.
- **Builds on:** JvmProbe's async-probe-with-timeout pattern, HardwareProfile.onBattery/hasBattery,
  core/apply/HelperLauncher's process-spawning precedent, core/notice/NoticePriority
- **Undo & safety:** Read-only and advice-only: RigTune never runs `powercfg /setactive` itself,
  only shows the Settings page and steps; nothing to journal or undo.
- **Verification plan:** Verified live in-session that `powercfg /getactivescheme`/`/list` return
  the assumed 'Power Scheme GUID: <guid> (<name>)' format; unit-test the GUID parser against
  captured real output including a custom-named scheme; a game test injects onBattery=true plus a
  fake Power-saver/unknown GUID reader to confirm fire/no-fire. The real PC and CI both lack a
  battery, so the on-battery gate itself can't fire live on either.
- **Compat notes:** A brand-new probe and NoticePriority entry, additive only; kept as a hardcoded
  Java-side check rather than a rules condition to avoid any schema risk for 0.1.x–0.4.x.
- **Risks:** Spawning powercfg.exe could be flagged by AV heuristics or fail on locked-down/managed
  PCs, so it must fail silently rather than block startup; matching must be GUID-only since the
  parenthesised scheme name is localised.
- **Effort:** 3 days. **Seed:** none.

### C13 — Battery template: measured minutes-per-percent before/after
- **Angles:** laptop
- **Merged from:** none
- **Pitch:** Applying the Battery profile template runs two short back-to-back sweeps, one at
  current settings, one at Battery's, and shows measured FPS and battery-drain-rate (minutes per
  1% charge) side by side before Keep/Revert, all through the existing journal.
- **Player problem:** The Battery profile applies fixed setting caps on faith today; a laptop
  player wants to know how much longer their battery will actually last, not just that FPS is
  capped.
- **How it works:** Reuses BenchmarkController's sweep mechanics for the FPS half; for drain rate,
  samples OSHI's PowerSource capacity at the start and end of each short sweep to get
  percent-delta over elapsed time, converted to minutes-per-1%; the whole thing is gated behind
  ProfileSwitch's existing apply-through-journal path so Keep/Revert is the same Undo mechanism
  profiles already use.
- **Builds on:** `core/profile/ProfileTemplates.TemplateId.BATTERY`, `PowerWatcher.realBatteries`,
  `ProfileSwitch -> RealController.apply -> journal`, existing BenchmarkController sweep flow
- **Undo & safety:** Entirely through the existing profile-switch Apply path, so 'Undo this/last'
  reverts it exactly as today; sweeps themselves already restore the player's real settings
  afterward.
- **Verification plan:** Needs a real battery to measure drain rate meaningfully, UNVERIFIED on the
  maintainer's real PC (no battery) and impossible on CI; unit/game-tests inject a fake
  PowerSource (the seam PowerWatcherTest already uses) with a scripted capacity curve to test the
  math and keep/revert flow end-to-end, shipping the real-hardware measurement itself explicitly
  labeled unverified until a laptop tester confirms it.
- **Compat notes:** New UI/state only in the existing profile-switch flow; no new managed
  ShareKeys and no change to older profiles.json readers.
- **Risks:** A 2-3 minute drain measurement is noisy and battery percent often only moves in whole
  points, so a short sweep may show 0% delta; must detect and honestly say 'not enough change to
  measure' rather than fabricate a rate from noise.
- **Effort:** 4 days. **Seed:** Measured "Try it".

### C14 — Multiplayer Stutter Doctor: is it your PC or the connection?
- **Angles:** multiplayer
- **Merged from:** none
- **Pitch:** Stutter Doctor already times a 'packets' phase per frame; sampling the local player's
  own reported ping into a small ring lets a spike honestly say 'ping was elevated (180ms) around
  this spike' instead of only ever pointing at GC/chunks/render.
- **Player problem:** On a laggy server, players blame their PC or apply mods when the real cause
  is the connection, and today's Stutter Doctor tags never mention network conditions even though
  it already attributes a chunk-load phase from packet handling.
- **How it works:** Sample the vanilla tab-list ping (updated a few times a second, not per frame)
  into a small ring alongside the existing GC/sample rings; when a spike's window overlaps an
  elevated-latency sample, add a new correlational tag with wording 'may be related to a slower
  connection (ping was Nms)', shown only when kind != SINGLEPLAYER, off by default with the rest
  of the monitor and zero new per-frame allocation.
- **Builds on:** `core/stutter/Attributor.java`, `client/stutter/StutterMonitor` + `StutterHooks`,
  `client/mixin/ClientPacketListenerMixin` + `ClientPacketListenerAccessor`,
  `client/server/ServerLimitsTracker` (Kind), `core/stutter/StutterAdvisor/StutterSummary`
- **Undo & safety:** Advice-only: no setting or file is touched; the new field is an optional
  addition to Attributor.Context and stutter.json's summary, so 0.1.x–0.4.x readers and a 0.4.0
  downgrade are unaffected.
- **Verification plan:** Unit tests on Attributor with synthetic latency samples mirroring existing
  tag tests; a client game test against a local loopback dedicated server with an injected fake
  high-latency value for a deterministic tag; manual sanity check joining a real public server for
  wording/no-crash (loopback latency is too low to exercise the elevated path for real).
- **Compat notes:** Purely additive and opt-in, riding the existing stutterMonitor off-by-default
  switch; no rules schema change, no new helper operation.
- **Risks:** The exact vanilla hook for the local player's own tab-list latency is UNVERIFIED
  against 26.2/26.3 source and needs a check before implementation; wording must go through the
  same honesty check as tier estimates so it never reads as 'the server is bad'.
- **Effort:** 2.5 days. **Seed:** none.

### C15 — Server Tune Card
- **Angles:** multiplayer
- **Merged from:** none
- **Pitch:** A one-click 'Copy for this server' on the benchmark result screen, shown only while
  connected to a real server/Realm/LAN, turns a Tune/Measure run into a short, address-free text
  block a player can paste into their server's Discord.
- **Player problem:** Groups of friends on the same SMP or Realm have no easy, offline way to
  compare notes on what render/sim distance and FPS target actually works there.
- **How it works:** When BenchmarkResultScreen shows a result taken while ServerLimitsTracker's
  live kind isn't SINGLEPLAYER, a button formats a short Markdown-safe block (kind, the server's
  view/sim distance cap, tuned render/sim distance, target FPS, measured 1% low, MC version, never
  a server name or address) and copies it to the clipboard.
- **Builds on:** `client/ui/BenchmarkResultScreen`, `core/benchmark/BenchmarkSession` +
  `BenchmarkRecord`, `client/server/ServerLimitsTracker.liveState()`, `core/report/ShareReport` +
  `MarkdownSafe`
- **Undo & safety:** Read-only and stateless: it only formats and copies existing in-memory
  benchmark data, identical in kind to Copy report/Copy summary today.
- **Verification plan:** Unit tests on the formatter cover each Kind and the address-free property
  with a golden-text test; a client game test runs Tune against a local loopback dedicated server
  and asserts the button appears with no address/server-name substring in its text.
- **Compat notes:** No new file, schema change, or helper operation; purely a new UI action on an
  existing screen.
- **Risks:** Mainly a wording/localisation task; the main risk is scope creep toward a full
  share-code, avoided by keeping it copy-only with no import/apply path.
- **Effort:** 1.5 days. **Seed:** none.

### C16 — Per-server profile offers
- **Angles:** multiplayer
- **Merged from:** none
- **Pitch:** RigTune remembers which Performance Profile a player picked for a given server/world,
  hashed the same privacy-preserving way server-limits.json already is, and offers (never
  auto-switches) it next time they join.
- **Player problem:** A player might want Quality on a small Realm with friends but Performance on
  a busy public SMP; Profiles only ever holds one active profile and never remembers a per-place
  preference, so switching is manual every session.
- **How it works:** A new state file maps the same HMAC-SHA256-keyed server/LAN/Realm identity
  ServerLimitsStore already computes to a saved profile id; on join, if a mapping exists and
  differs from the active profile, NoticeCenter surfaces a dismissible, low-priority notice with an
  action that runs the exact same ProfileSwitch/Apply path Profiles already uses. The mapping is
  only ever written when the player explicitly says 'remember this for here'.
- **Builds on:** `core/server/ServerLimitsStore`, `core/profile/ProfileService/ProfileSwitch/ActiveProfile/ProfileStore`,
  `client/server/ServerLimitsTracker`, `core/notice/NoticeBoard` + `NoticePriority` and
  `client/notice/BatteryNoticeSource`'s offer-never-switch pattern
- **Undo & safety:** A switch from the offer is an ordinary Apply, journaled and fully covered by
  Undo this/last/all; refused while downloading or benchmarking; never auto-switches.
- **Verification plan:** Unit tests on the new store (salt handling, pruning, corrupt file, HMAC
  keys, mirroring ServerLimitsStoreTest) and on the offer decision logic (deleted/renamed profile
  -> no offer, same profile already active -> no offer); a client game test joining a local
  dedicated server twice to confirm the save/notice/apply/undo round-trip.
- **Compat notes:** New file only (server-profiles.json) under config/rigtune using
  JsonStateFile's shared rules; 0.1.x–0.4.x never read or write it, and a downgrade to 0.4.0
  simply loses the offer feature.
- **Risks:** Needs care so the offer can't fire for singleplayer worlds or feel like nagging, and
  a stale mapping to a deleted/renamed profile must fail closed rather than error.
- **Effort:** 2.5 days. **Seed:** Per-server / per-world profiles.

### C17 — Friend Benchmark Compare
- **Angles:** multiplayer
- **Merged from:** none
- **Pitch:** A small pasted code, not a share code, lets two friends compare hardware tier,
  render/sim distance and measured 1% lows side by side entirely offline, so a group deciding on
  shared server settings can see who is the constraint.
- **Player problem:** Friends on the same server/Realm have no in-game way to compare 'what did
  you get' beyond typing numbers in chat, despite RigTune already measuring exactly the numbers
  that matter.
- **How it works:** 'Copy for a friend' encodes a small bounded record (hardware tier and basis, MC
  version, scene kind, render/sim distance, target FPS, measured 1% low, small integers only, no
  free text) as a short prefixed code with a CRC, following the same strict-decode discipline as
  RT1 codes; 'Paste a friend's result' decodes it locally and shows a read-only two-column
  comparison with a note that different hardware isn't directly comparable.
- **Builds on:** `core/benchmark/BenchmarkRecord/BenchmarkHistory/BenchmarkTrend`, core/model
  TierBasis/HardwareProfile tier fields, `core/profile/ShareCode`'s strict bounded-decode pattern,
  `client/ui/ProfileImportScreen`'s paste-then-preview pattern
- **Undo & safety:** Nothing is applied or written: importing a friend's code only fills a
  read-only comparison view in memory, so there is no journal entry and nothing to undo; decoding
  is bounded, allowlisted and previewed per constraint 7.
- **Verification plan:** A fuzz test on the new decoder in the style of ShareCodeFuzzTest (byte
  flips plus random bodies must never crash, only accept-or-reject cleanly); unit tests for golden
  record pairs against expected table rows; a client game test opening the paste screen with a
  hand-built valid and a corrupted code.
- **Compat notes:** A brand-new code prefix/format, not an extension of ShareKeys' frozen v1
  table, needing no rules-schema or share-code-version change; no new file is written at all.
- **Risks:** Scope risk of drifting into a second, competing share-code format if not kept smaller
  than ProfileTemplates' keyset; wording must avoid a 1% low comparison across very different
  GPUs reading as a ranking or as 'X limits your PC'.
- **Effort:** 3 days. **Seed:** none.

### C18 — Launch-time regression alerts
- **Angles:** modpack
- **Merged from:** none
- **Pitch:** RigTune's launch-time trend stops just showing 'your mod set changed' and starts
  saying, with the same rigor as its FPS regression alerts, how much slower launch got and that it
  may be related to the mod-set change in between.
- **Player problem:** On a big modpack, launch time creeping from 15s to 40s over updates is
  exactly the kind of thing a player wants flagged, but today's Tools screen only shows the last
  run, a median, and a bare 'mod set changed' flag.
- **How it works:** Reuses the existing 30-run startup-times.json history and the exact
  noise-floor/regression math already built for BenchmarkTrend against this same data, phrased the
  same honest way: 'Launch time is higher than usual (18.4s vs your usual ~10.2s); may be related
  to your mod set changing (94 -> 131 mods) since your last launch.'
- **Builds on:** `core/footprint/StartupTimesStore`, `client/footprint/StartupTimes`, the
  regression-math pattern already shipped for benchmark/BenchmarkTrend
- **Undo & safety:** Advice-only; writes only to the existing startup-times.json file already
  capped at 16 KiB/30 runs, no Apply/Undo path needed.
- **Verification plan:** Unit tests feed synthetic launch-time sequences (varying mod counts and
  durations) through the reused trend math against known thresholds; manual check on the real PC
  adding/removing a batch of mods between two launches and confirming the alert text and
  magnitude.
- **Compat notes:** Purely additive to the existing 0.4 startup-times.json schema, no new
  persisted fields beyond what's already stored, doesn't touch pending.json/history.json/the
  helper.
- **Risks:** Few recorded launches make the baseline noisy; must require a minimum
  comparable-run count before claiming a regression, mirroring BenchmarkTrend's own floor.
- **Effort:** 2 days. **Seed:** none.

### C19 — Mod-count-aware memory headroom advice
- **Angles:** modpack
- **Merged from:** none
- **Pitch:** JVM & memory advice gets one more, honestly-worded line for very large mod counts:
  heap tier advice assumes a lighter mod set, and 100+ mods likely want more headroom than the
  tier alone suggests.
- **Player problem:** RigTune's memory advice is driven by hardware tier alone, but a 32GB machine
  on a 150-mod pack has very different heap and non-heap pressure than the same machine on 20
  mods, and nothing accounts for that difference today.
- **How it works:** Add a coarse on-device fact ('modcount-100plus', counting only top-level jars
  the way StartupTimes already does) to HardwareProfile.flags, following the exact precedent of
  the existing sodium-workaround/jvm- flag tokens; a new advice rule gated by
  `requires: ["modcount"]` (the same escape-hatch pattern as stutter-doctor/jvm-flags) adds a
  launcher-aware note, never a GC flag.
- **Builds on:** `client/probe/ModScanner`, core/rules Condition.flags + `requires` mechanism,
  `client/jvm/JvmService`, `client/ui/LauncherLines/LauncherAdvice`
- **Undo & safety:** Advice-only (info/warning kind), no Apply or Undo needed, identical in shape
  to existing ram-*/jvm-* advice entries.
- **Verification plan:** Unit tests over the rules Condition evaluator confirm 0.2.0/0.3.0 (which
  don't know 'modcount') stay UNKNOWN/fail-closed; a scenario test with a large synthetic mod
  list; on the real PC, check the note appears only past the threshold using a padded copy of the
  existing 50-mod test instance.
- **Compat notes:** New rule entries live entirely behind `requires`, the documented safe pattern
  for a v2-only fact; 0.1.x–0.3.x skip the rule outright; no new state file.
- **Risks:** Raw mod count is a blunt proxy for actual memory pressure; wording must stay a rough
  estimate ('consider more headroom'), never a diagnosis of what's using the memory.
- **Effort:** 2 days. **Seed:** none.

### C20 — Stutter Doctor one-click fixes
- **Angles:** merge
- **Merged from:** none
- **Pitch:** Turn Stutter Doctor's fired advice into one-click, undoable changes with a
  before/after session comparison, offered only when the evidence behind that advice is strong.
- **Player problem:** Stutter Doctor already identifies likely causes (GC pressure, chunk-building
  dominance) and gives advice, but the player must apply the fix manually themselves with no
  confirmation it actually helped this session.
- **How it works:** Where StutterAdvisor's confidence gating for a fired advice item is strong,
  offer a 'Fix it' button next to it that stages the corresponding setting change through the
  existing RealController.apply -> journal path, then starts a new monitored session and shows the
  new session's GC%/spike-count next to the prior one via StutterSummary.
- **Builds on:** `core/stutter/StutterAdvisor`, `core/stutter/Attributor`,
  `core/stutter/StutterSummary`, `client/RealController.apply`, `core/history/Journal`,
  `client/ui/StutterScreen`
- **Undo & safety:** Every fix goes through the ordinary Apply -> journal -> Undo path, so History
  covers it like any other change; the monitor itself stays opt-in per Stutter Doctor's existing
  default-off gating.
- **Verification plan:** Unit tests over StutterAdvisor's confidence gating fixtures confirm the
  button only appears above threshold; a client game test forces a high-GC-pressure session (small
  heap), applies the offered fix, and confirms a second monitored session shows improved GC%
  within the existing StutterMonitor test harness.
- **Compat notes:** No new file format; reuses the existing Apply/journal path and Stutter
  Doctor's own opt-in session data, so 0.1.x–0.4.x compatibility (Stutter Doctor is itself 0.4+)
  is untouched.
- **Risks:** Must not overstate causation; wording has to stay 'may be related'/measured per the
  honesty rule, and must refuse to offer a fix when confidence is anything short of strong, to
  avoid selling a placebo change as a fix.
- **Effort:** 2.5 days. **Seed:** Stutter Doctor one-click fixes.

### C21 — Entity- and redstone-heavy benchmark scene
- **Angles:** merge
- **Merged from:** none
- **Pitch:** Add a second scripted scene to the existing benchmark world, dense entities and
  redstone contraptions, so Tune/Measure results also cover workloads the flat/build scene doesn't
  stress.
- **Player problem:** Today's benchmark scene(s) stress render/chunk load but a farm- or
  redstone-heavy world behaves very differently (entity ticking, block-update storms); players
  with those builds get benchmark numbers that don't reflect their real bottleneck.
- **How it works:** A new BenchmarkScene variant inside the existing dedicated benchmark world
  (never the player's real world) is pre-built with a fixed layout of mob-cap-friendly entities
  and a scripted redstone clock/contraption; BenchmarkController's existing scene-selection and
  sweep mechanics drive it unchanged, and BenchmarkRecord's Context gains an optional scene-id
  field following the same extension pattern already used for other Context fields.
- **Builds on:** `core/benchmark/BenchmarkSession`, `core/benchmark/BenchmarkController`,
  `core/benchmark/BenchmarkRecord` (Context), the dedicated benchmark world's existing
  scene-loading mechanism
- **Undo & safety:** Benchmark scenes already run in a dedicated, disposable world and restore the
  player's real settings afterward per existing Measure semantics; nothing here touches
  Apply/Undo since it only adds a scene to measure against.
- **Verification plan:** Unit tests on the new scene's fixed entity/redstone counts (deterministic
  layout, same style as existing scene fixtures); on the real PC, run Measure against the new
  scene and confirm frame-time sensitivity differs meaningfully from the existing scene(s); Linux
  CI runs the same scene headless under Xvfb/llvmpipe to confirm it loads without a real GPU.
- **Compat notes:** Adds one optional Context field (scene id) following the same pattern already
  used for modSetHash/journalCursor, so 0.1.x–0.4.x readers of benchmarks.json are unaffected and
  a 0.4.0 downgrade still reads every older run.
- **Risks:** A redstone/entity scene's frame time is more sensitive to tick-rate variance than
  render-bound scenes, so BenchmarkMath's noise-floor math needs its own validation pass to avoid
  false regression alerts.
- **Effort:** 2.5 days. **Seed:** Entity- and redstone-heavy benchmark scene, in the benchmark
  world only.

### C22 — Shader-pack profile switching
- **Angles:** merge
- **Merged from:** none
- **Pitch:** Let a Performance Profile also carry an Iris shader-pack choice, applied through a
  new, clearly separate helper operation older RigTune helpers never read, with shader values kept
  out of RT1 share codes entirely.
- **Player problem:** Profiles switch mod settings and vanilla video settings, but a player who
  swaps between no-shaders for competitive play and a shader pack for building has to do that step
  manually and separately every time, outside RigTune entirely.
- **How it works:** A new SET_SHADER_PACK pending-op writes Iris's shader-pack-selection config
  file, staged and applied by ApplyHelper exactly like every other file op but under a new
  op-type name a 0.1.0-0.4.0 helper (which switches on a fixed, older op-type enum) never
  encounters, satisfying the 'lives where old helpers never read it' compatibility rule;
  ShareKeys' managed key table explicitly excludes the shader-pack field so RT1 codes stay
  unaffected.
- **Builds on:** `core/apply/RealController.apply` + pending.json's op-type dispatch,
  `core/apply/ApplyHelper`, `core/profile/ProfileStore/ProfileTemplates`, `core/profile/ShareKeys`
- **Undo & safety:** Goes through the same Stage -> ApplyHelper -> Journal -> Undo pipeline as
  every other profile field; Undo this/last/all restores the prior shader-pack selection exactly
  like any other tracked file.
- **Verification plan:** Unit tests confirm a 0.4.0-shaped pending.json reader safely
  ignores/rejects the new op type rather than crashing; on the real PC, verify against at least
  two popular packs actually installed (e.g. Complementary and BSL) that switching profiles
  changes the active pack and Undo restores the prior one; Linux CI covers the op-type dispatch
  and journal round-trip without needing a shader-capable GPU.
- **Compat notes:** New op type plus one new profile field, both additive; explicitly excluded
  from ShareKeys so the frozen share-code v1 table is untouched.
- **Risks:** Iris's on-disk shader-selection format could differ between versions/packs, so the
  write path needs allowlisted, previewed handling rather than a blind file copy, and must be
  verified against two real packs before shipping.
- **Effort:** 3 days. **Seed:** Shader-pack profile switching.

### C23 — Jar-level optional-mod toggles in profiles
- **Angles:** merge
- **Merged from:** none
- **Pitch:** Let a saved Profile also remember which optional companion mods (Iris, DH, etc.) are
  enabled or disabled, using the exact existing disable/enable staging machinery, so switching
  profiles can turn a mod on/off as part of the same one-click switch.
- **Player problem:** Profiles switch settings but not which optional mods are active; a player
  who wants shaders only in a Quality profile and never in a Competitive profile has to manually
  enable/disable the jar themselves outside Profiles today.
- **How it works:** Extend ProfileTemplates/ProfileStore's managed-key set with an optional list
  of {modId, enabled} pairs; when a profile switch runs through RealController.apply, any mismatch
  between the target profile's list and currently-enabled jars becomes the exact same
  DISABLE_FILE/ENABLE_FILE pending ops the manual 'Disable X' flow already uses, needing no new op
  type at all.
- **Builds on:** `core/profile/ProfileStore/ProfileTemplates`, `client/undo/DisableGuard`,
  `core/history/FolderCheck`, `core/apply/RealController.apply`
- **Undo & safety:** Reuses the existing disable/enable op type exactly, so Undo this/last/all
  already covers a profile-triggered toggle identically to a manual one.
- **Verification plan:** Unit tests confirm a profile switch's computed diff against currently-
  enabled jars matches expectations from golden fixture profiles; a client game test switches
  between two fixture profiles with different mod-toggle lists and confirms the right jars end up
  enabled/disabled and Undo restores the prior set.
- **Compat notes:** New optional field in profiles.json only, using the existing disable/enable op
  type (no new op), so 0.1.x–0.4.x readers and a 0.4.0 downgrade are unaffected; excluded from RT1
  share codes unless the target mod is already part of ShareKeys' managed set.
- **Risks:** Toggling a mod off mid-session still needs a restart like any manual disable, so the
  profile-switch UI must say so; must fail closed and leave alone any mod RigTune didn't stage
  itself.
- **Effort:** 2 days. **Seed:** Jar-level optional-mod toggles in profiles, using the existing
  disable/enable machinery.

### C24 — Community translation pipeline
- **Angles:** merge
- **Merged from:** none
- **Pitch:** Open en_us.json's key set to community-contributed language files with a lightweight
  verification flag, so a language ships either 'verified by a native speaker' or honestly marked
  unverified rather than silently shipping unreviewed strings as if checked.
- **Player problem:** Every UI string is already localised through en_us.json, but non-English
  players have no way to know whether the translation they're reading was ever checked by a
  fluent speaker, and there's no path for the community to contribute or improve one.
- **How it works:** A small new tools/translation_status.json records each shipped language's
  status (verified/unverified) and reviewer credit; LangCheckTest, which already enforces full key
  coverage per language, gets one more assertion that every shipped lang/xx_xx.json has a
  corresponding status entry, and the in-game language list gets one honest badge per language
  reflecting that status.
- **Builds on:** LangCheckTest, the existing lang/*.json file set, tools/update_rules.py's
  weekly-job pattern as a template for a new, separate status job
- **Undo & safety:** Read-only/display-only feature (a badge next to a language name); adds no
  Apply/Undo path since it changes no gameplay setting.
- **Verification plan:** LangCheckTest gains a case asserting every lang file has a status entry
  and fails closed if one is missing, exactly like its existing key-coverage gate; a game test
  opens the language list and asserts the badge text for a fixture verified and a fixture
  unverified language.
- **Compat notes:** New standalone file, no rules-schema or save-file change at all, so every
  0.1.x–0.4.x compatibility guarantee is trivially untouched.
- **Risks:** 'Verified' must mean an actual named reviewer confirmed it, not just that CI's
  key-coverage check passed, and the UI must not conflate the two; scope for v0.5.0 should be the
  mechanism plus at least one real verified language, the rest explicitly marked unverified.
- **Effort:** 2 days. **Seed:** A community translation pipeline, with at least one language
  verified by a native speaker or explicitly marked unverified.

## 9. Critic's notes

Adversarial pass over the 5 P1 picks against the actual `feat/v0.5.0` code (not the pitch text),
looking for a hidden blocker, a compat trap, a footprint/undo gap, or an overlap the pitch didn't
account for. Nothing found here rises to the "disqualifying blocker" bar this document itself uses
for C04/C05/C12/C13/C22/C24 (an effort/verifiability/compat claim in the pitch that plainly cannot
be met), so §9.6 recommends against swapping any P1 pick. All five findings below are scoping/
accuracy corrections the spec agent should read before writing an implementation plan.

### 9.1 P1.1 — C20: Stutter Doctor one-click fixes

- **Severity: High.** The pitch's "how it works" reads: "Where `StutterAdvisor`'s confidence
  gating for a fired item is strong, show a 'Fix it' button." No such gating exists.
  `StutterAdvisor.Fired` (`core/stutter/StutterAdvisor.java:29-42`) carries only `kind`
  (info/warning/critical) and an `Impact` derived from it; `RulesDocument.AdviceRule`
  (`core/rules/RulesDocument.java:213-222`) has no confidence-like field either — only `requires`,
  `when`, `impact`, `title`, `text`, `kind`. The only `Confidence` type in the codebase is
  `Attributor.Confidence` (`core/stutter/Attributor.java:53-68`, HIGH/MEDIUM/LOW): a per-spike,
  per-cause attribution-strength value that `StutterSummary` aggregates into "likely causes" shares
  — a different axis entirely from "should this advice item offer a Fix it button." Checked against
  the shipped rules: none of the five live `stutterAdvice` entries in `rules/rules-v2.json`
  (`ram-stutter-gc-heap`, `stutter-gc-explicit`, `stutter-sodium-defer`, `stutter-dh-threads`,
  `stutter-chunk-loading`) use `kind: critical` — they are all `warning` or `info` — so a naive
  `kind == "critical"` proxy would mean the button never appears for any of today's rules.
  The doc's own open question #1 half-admits this ("Confirm the exact confidence threshold
  StutterAdvisor already uses... and whether a new threshold constant is needed"), but the "how it
  works"/"Compat"/"Why it beat the runners-up" sections all present the mechanism as already there
  and the feature as adding "no new subsystem" — that framing is not supported by the code.
  **Consequence:** whichever way this is resolved (reusing `kind`, adding a new rule-level field, or
  a client-side heuristic over `Attribution.claims()`), it is new design work not costed into the
  2.5-day estimate, and if it becomes a new `AdviceRule` field it needs the `requires`/never-
  restrict-an-existing-rule handling `docs/RULES_SCHEMA.md:156-164,319` requires for exactly this
  situation — which the pitch's Compat section doesn't mention.
- **Suggestion:** resolve open question #1 concretely before implementation starts — pick one of
  (a) gate on `kind == "critical"` and promote at least one stutterAdvice rule to `critical` in the
  same cycle, or (b) define a new, `requires`-gated confidence signal and cost the schema work in.
  Either way, note this is new state, not "no new subsystem."

### 9.2 P1.2 — C09: Measured Try It

- **Severity: High, but it cuts the other way — the pitch under-scopes the reuse, not the
  blocker.** Open question #1 states: "no pair-id / cross-restart correlation mechanism exists yet
  anywhere in `BenchmarkSession.java` (confirmed absent by grep) — this is new state, not reuse."
  True of that one file; false of the feature area as a whole. `BenchmarkRecord` already has
  `phase` ("before"/"after") and `pairId` fields with the exact semantics C09 wants
  (`core/benchmark/BenchmarkRecord.java:10,16-17`); `BenchmarkRequest` already carries a `pairId`
  (`core/benchmark/BenchmarkRequest.java:4-5`); `BenchmarkRecords.phase()`
  (`core/benchmark/BenchmarkRecords.java:16-21`) already decides before-vs-after by checking
  history; `BenchmarkHistory.before()`/`openBefore()` (`core/benchmark/BenchmarkHistory.java:152-
  165`) already look the pairing up from persisted `benchmarks.json` — which is exactly what makes
  it survive a restart today: it's read off disk, not held in memory. `BenchmarkMenuScreen`
  (`client/ui/BenchmarkMenuScreen.java:81-90`) already auto-continues an open `pairId` on its
  "Measure after" button. None of this is new: the same shape exists verbatim back in the v0.2
  snapshot (`src/test/java/.../v020/core/benchmark/BenchmarkHistory.java:152-168`).
  **Consequence:** two risks in opposite directions. (a) If the spec/build agent isn't pointed at
  this, they may build a second, parallel pairing mechanism — exactly what open question #2
  speculates ("a small new file vs. an existing state file") — producing two divergent notions of
  "a paired benchmark run" over the same `benchmarks.json`, which is the kind of duplicated
  mutation path this document is otherwise careful to avoid. (b) The real remaining scope (mint/
  reuse a pairId for a specific `Recommendation`, run `RealController.apply(List.of(rec), entryId)`
  between the two Measures, offer Keep/Revert against that `entryId`) is smaller than the "3-5 day,
  no pairId mechanism exists" framing suggests, so the cross-restart stretch goal the doc treats as
  the at-risk part is very likely achievable in-budget, not a fallback-to-same-session-only case.
- **Suggestion:** correct the open question before handoff: point the implementer at
  `BenchmarkRecord`/`BenchmarkRequest`/`BenchmarkRecords`/`BenchmarkHistory`/`BenchmarkMenuScreen`'s
  existing pairId flow, scope C09 as wiring an Apply step into that existing pair, and re-open
  whether the same-session-only v1 fallback is still needed at all.

### 9.3 P1.3 — C16: Per-server profile offers

- **Severity: Medium — discoverability, not correctness.** `NoticeBoard` shows exactly one notice
  inline at a time, ordered by `NoticePriority`'s declaration order
  (`core/notice/NoticePriority.java:4-11`: `BATTERY_OFFER, SERVER_LIMIT, BENCHMARK_REGRESSION,
  HARDWARE_CHANGED, WHATS_NEW, BENCHMARK_STALE`); `NoticeBoard.select()`
  (`core/notice/NoticeBoard.java:37-51`) sorts and `RigTuneScreen` renders only
  `notices.at(noticeIndex)` on the visible line, with every other pending notice sitting behind an
  explicit "+N more" button (`client/ui/RigTuneScreen.java:394-441`). C16's own pitch names its
  target scenario as "Performance on a busy public SMP" — but a busy public SMP is exactly the kind
  of server most likely to already cap view distance and so fire the existing, higher-priority
  `ServerLimitNoticeSource` (`client/notice/ServerLimitNoticeSource.java`) on the very same join.
  Since C16 describes its own notice as "low-priority," it would typically slot in below
  `SERVER_LIMIT` and so default to the "+N more" tier rather than the visible line, on the servers
  where the feature matters most.
  **Suggestion:** state where the new priority slot sits relative to `SERVER_LIMIT` explicitly, and
  consider offering the profile switch as a follow-up after the server-limit notice is dismissed/
  acted on rather than a same-slot competitor.
- **Severity: Low — phrasing.** "The same HMAC-SHA256-keyed server/LAN/Realm identity
  `ServerLimitsStore` already computes" overstates what's reusable: `ServerLimitsStore`'s salt and
  key derivation are private/package-private and generated once per file
  (`core/server/ServerLimitsStore.java:38,125-133,148-150`), so a new `server-profiles.json` store
  cannot literally produce the same hash for the same address without a new public accessor on
  `ServerLimitsStore` (itself a compat-relevant class). It would instead independently re-implement
  the same HMAC-SHA256 scheme with its own new salt — functionally fine for the feature's own needs
  (self-consistent across the player's own repeat joins), but it is "the same technique," not "the
  same identity value," and the open questions section should say so rather than imply a shared key.

### 9.4 P1.4 — C18: Launch-time regression alerts

- **Severity: Medium.** "Reuses the exact noise-floor/regression math already built for
  `BenchmarkTrend` against this same data" overstates the reuse. `BenchmarkTrend.assess()`
  (`core/benchmark/BenchmarkTrend.java:237-261`) — the actual regression/`Kind` classification
  entry point — is hardwired to `BenchmarkRecord` and to `BenchmarkTrend.comparable()`/
  `differences()` (same file, lines 128-172), which compare `mcVersion`/`scene`/render-distance/
  `Context` (resolution, shaders, Distant Horizons, protocol). `StartupTimesStore.Run`
  (`core/footprint/StartupTimesStore.java:23-25`: `at, ms, mcVersion, rigtuneVersion, mods,
  modSetHash`) has no equivalent of most of that comparability model. Only the low-level pure
  functions — `median()`, `mad()`, `noiseFloorPercent()` (same file, lines 188-214), all operating
  on `double...` — are genuinely drop-in reusable; `assess()` itself is not.
  **Consequence:** a parallel, smaller `Kind`/`Assessment`-shaped classifier still has to be written
  for launch times (comparable-by probably just `mcVersion`), which is real new code, not literally
  "the exact... math." The 2-day estimate is probably still achievable using the reusable
  primitives, but "carries close to zero pipeline or compat risk" should read "adapts, not reuses
  verbatim," so the spec agent doesn't plan a one-line delegation to `BenchmarkTrend.assess()` that
  won't compile against `Run`.
- **Suggestion:** scope the launch-time classifier explicitly as new (small) code built on
  `BenchmarkTrend`'s reusable primitives, not as a call into `assess()`.

### 9.5 P1.5 — C02: First-time Apply trust flow

- **No blocker found.** `StartupNotices.takePrivacyNotice`/`PrivacyToast`
  (`client/StartupNotices.java:13-54`) is exactly the one-time-toast pattern the pitch describes, and
  `HistoryModel.Entry`/`Change` (`core/history/HistoryModel.java:63-108`) already carry everything a
  confirmation screen needs to stay byte-for-byte in sync with what Undo reverts. This is the
  cleanest of the five pitches against the code.
- **Cross-cutting note (touches C02, C09 and C16 alike):** the product description lists "reduced
  accessibility (Tab/Narrator/high contrast)" as an existing, already-shipped limitation. Three of
  the five P1 picks (C02's two new screens, C09's Try-it/Keep-Revert flow, C16's offer notice) add
  new UI surface, growing what that limitation applies to; none of the five pitches' "Risks"
  sections mention it. Worth one line in §3 acknowledging the accessibility debt these add, rather
  than treating each new screen as risk-free.

### 9.6 Swap recommendation

**None of the 5 P1 picks should be swapped out.** Nothing found here meets this document's own bar
for a disqualifying blocker (compare C22's Gson-enum-crash-on-downgrade finding, or C12/C13's
no-battery unverifiability) — every issue above is fixable by correcting a claim or adding modest,
in-budget scope, not by abandoning the pick. The most material corrections are to C20 (§9.1: the
"confidence gating" it's meant to build on doesn't exist yet and needs a concrete decision before a
spec can be written) and C09 (§9.2: the opposite problem — its flagged effort risk is based on a
grep that was scoped to the wrong file, and the real mechanism it needs already exists and already
crosses restarts). If C20's confidence-signal design spike fails to land a concrete answer inside
its first day and the feature has to slip, C08 (Compare benchmark runs, P2 rank 2, §5) is the
cleanest swap-in: next-highest score, no comparable design ambiguity in its own pitch, and (per §4)
was held back from P1 only for player-type balance, not for any weakness of its own.
