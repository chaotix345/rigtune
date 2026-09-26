# External review (2026-09-26): an outside model's read of the repo, and the coordinator's decisions

The user pointed another model at the repo and relayed its challenges twice (initial read, then a follow-up on the coordinator's proposed response). Each claim was checked against the code before deciding.

## 1. "Limited by GPU/CPU" overstates what RigTune knows. ACCEPTED.
- Verified: `core/hardware/TierCalculator.java:26-38` starts from `limiting = "gpu"` and only switches on a strictly lower tier, so a 5/5/5 machine reads "Tier 5/5 · limited by GPU" (`en_us.json:75` `rigtune.header.tier`). The CPU fallback gives an unrecognised CPU a tier from thread count and clock.
- Follow-up refinement (accepted): even a uniquely lowest tier is not a measured bottleneck, and a tie does not prove a balanced machine. Recognised hardware is estimated too: the honest distinction is "matched in the hardware table" vs "fallback estimate", not "estimated vs proven".
- Decision (SPEC 2, new defect): retire bottleneck language everywhere in the UI. The header shows "Estimated tier N/5 · lowest estimated component: CPU" (ties list every tied component: "CPU, GPU"); the component tiers stay in the details/tooltip with "(table match)" or "(fallback estimate from 16 threads)" per component. No "limited by" wording anywhere (LangCheck-visible key rename; tests assert the old wording is gone).

## 2. Benchmark results replacing the estimate need a relevance check. ACCEPTED.
- An old result must not stand in for the estimate after resolution, shaders, mods or relevant settings changed. Show the measured result WITH its scene/context, and mark it "needs a rerun" when the context no longer matches (resolution, fullscreen, shaders/pack, DH on/off, mod set, render/simulation distance, MC version).
- A benchmark measures performance under its test conditions; it does not identify a limiting component. The header never derives "bottleneck" from a benchmark.
- Decision: folded into SPEC 7 (benchmark history): the comparability key (context + mod-set hash) also drives the header's "last benchmark" line and its stale marker.

## 3. Keep validation claims separate. ACCEPTED.
- Low-end scenario tests verify that the rules produce the intended recommendations; session monitoring measures actual performance; neither proves a recommendation improved performance.
- Regression alerts need comparable conditions; otherwise the wording is "performance changed; cause unknown". Changes found in History between two runs are listed as "changes since then (may be related)", never as the cause.
- The controlled before/after benchmark mode is the stronger evidence; the UI and README say so.
- Decision: SPEC 7 wording rules; README "What has been verified" section (one real machine: Ryzen 7 7800X3D + RX 7800 XT; everything else is table-driven estimates plus scenario tests).

## 4. VSync advice is too broad. ACCEPTED.
- Verified: `rules/source/knowledge.json:496-497` recommends a refresh-rate FPS cap and VSync off whenever refresh >= 30 Hz and not on battery; the cap's reason claims it "keeps FreeSync/G-Sync active" although the display model knows nothing about variable refresh.
- Decision (SPEC 2, rules): the VSync-off recommendation becomes optional (`"defaultSelected": false`, unticked by default); both reasons are reworded to make no variable-refresh claim (e.g. the cap "avoids rendering frames your monitor can't show; with FreeSync/G-Sync it also keeps the frame rate inside the variable-refresh range"; VSync "Optional: turning VSync off lowers input lag but can cause tearing; leave it on if you see tearing").
- Acceptance depends on the GENERATED rules: `defaultSelected: false` must survive generation into rules-v2.json AND rules-v1.json (if v1 supports it; otherwise the v1 projection must be at least as conservative), checked by a Python test, RulesV1DifferentialTest (unticking is more conservative) and the pinned old-reader tests.

## 5. Conflict checking between separate Applies. ALREADY P0 (SPEC 2d); prioritised as the first functional fix.

## 6. Broader performance validation. PARTLY ACCEPTED.
- Can't test hardware we don't have. v0.4 adds low-end hardware fixtures to the recommender scenario tests (integrated-graphics laptop on battery, old 4-core CPU with 8 GB, unrecognised CPU/GPU), relies on the opt-in session monitor + regression alerts for real-play measurement on the user's own machine (busy bases, entities, redstone), and documents the one-machine validation limit in the README.
- An entity/redstone-heavy benchmark scene is deferred to v0.5 (scope).
