# WS-H: self-update E2E for 0.4 (design notes)

Plan: docs/v0.4/plans/ws-h.md. Harness: tools/e2e (README "v0.4 runs"). Evidence: docs/smoke/self-update/dev-*-040*.
Scope: PLAN's WS-H section, widened by the coordinator after the plan review (H-M1, P-H1, X-L3): the downgrade run,
the released-jar compatibility harness, and the two-switch profile case.

## What runs

| scenario | command core | dry run (0.4.0-dev) | Phase 5 name |
|---|---|---|---|
| 0.3.0 → new | `--old-jar rigtune-0.3.0+mc26.2.jar --expect-history auto` | PASS 20/20 | final-v030-to-040 |
| 0.2.0 → new | `--old-jar rigtune-0.2.0+mc26.2.jar --expect-history auto` | PASS 20/20 | final-v020-to-040 |
| 0.1.0 → new | `--old-jar rigtune-0.1.0.jar --legacy-disable --expect-history auto` | PASS 21/21 | final-v010-to-040 |
| seeded 0.1.0 → new (H-M2) | `... --seed tools/e2e/seeds/v010-dh --expect-history auto` | PASS 26/26 | final-v010-seeded-to-040 |
| undo after restart + per entry (M14, B-M3) | `--scenario undo` | PASS 43/43 | undo-after-restart-040 |
| + two profile switches (P-H1) | `--scenario undo --profile-switch settings` (stand-in) | 69/71: M14/B-M3 43/43, switches 8/8, Undo all + check 14/14, **Undo last twice 4/6 (SPEC 2n, expected)** | same run, `--profile-switch profile` |
| downgrade (AC3.2) | `--scenario downgrade --old-jar rigtune-0.3.0+mc26.2.jar` | PASS 16/16 (placeholder sets) | downgrade-040-to-030 |
| released-jar harness (AC3.3) | `python tools/e2e/compat030.py --old-jar rigtune-0.3.0+mc26.2.jar` | PASS 9/9 locally and in CI (placeholder sets) | every CI run |

Evidence folders: `dev-v030-to-040`, `dev-v020-to-040`, `dev-v010-to-040`, `dev-v010-seeded-to-040`,
`dev-undo-after-restart-040` (jar `bd2c93ae44b9c48a3708f1d4b0bddeb7e1dd2311292f0cd8d7d4f69c6d03f0a4`: feat/v0.4.0 @ 07f8fe3
built with `-Pmod_version=0.4.0-dev`), `dev-undo-after-restart-040-profiles` and `dev-downgrade-040-to-030` (jar
`2083302f49794dc3b854f53999d1f58aaa5002b85bfaa68e3b8ef972943e1c57`: after WS-0 merged, `mod_version=0.4.0-dev`). Both
builds are 0.3.0's product code with the dev version (WS-0 only touched the snapshot blocks). PLAN says `dev-*-v04`;
the folders are named like v0.3's dev runs, and the final runs use SPEC AC3.1's `final-*-040`.

Released jars (GitHub release assets; sha256 equal to the release's asset digest; pinned in `RELEASED` and in CI):
`rigtune-0.1.0.jar` `8294d04a6b67e76dcff298366be38f85048ebf19a120baa9e8ed5b08b2e4b950`; `rigtune-0.2.0+mc26.2.jar`
`67275e232fe4de9f806dd6496f479d8385d8afabf9a6b93ffe909ce42f657de9`; `rigtune-0.3.0+mc26.2.jar`
`5717f65cb90c71aaeda844b7bd56e3ce9255e83f44418af0cfc6a589050cd7e9`.

## Decisions

- **0.3.0 as an old side needs no driver change.** `compileE2eJava -Pe2e.oldJar=<0.3.0 jar>` compiles the unchanged
  self-update driver. CI's driver step gains the 0.3.0 download, its sha256 line, that compile, the downgrade driver's
  compile and the compat harness (all inside that one step).
- **The old side's journal comes from its version** (`e2e_checks.history_expectation`): 0.1.x → the new version's
  `legacy-import`; 0.2.0 and later → their own `apply` entry of the update (0.3.0's journal is 0.2.0's format).
  `--expect-history auto` resolves it; a mismatching explicit value is refused; the v0.3 spellings still work.
  `own-update` with `--legacy-disable` also expects the test mod's disable in that entry.
- **Released jars pinned in the harness too** (`RELEASED`; a test keeps it equal to build.yml's pins): a jar whose
  fabric.mod.json names a released version must have the release's bytes.
- **Version-neutral names**; the 3e WARN pattern is a constant; a check detail with RigTune's arrow no longer aborts a
  run on a cp1252 console (it did, once: the evidence of that run was lost; `log()` now writes e2e.log first).
- **"Written by 0.4" sets (H-M1: seed, don't drive).** `src/test/resources/v040-written/<set>/` per owner (ws-a, ws-p,
  ws-b, ws-s, ws-w, ws-f), files named as in `config/rigtune/`; WS-H's hand-written placeholders (SPEC C1 shapes, a
  README saying so) live in `placeholder/<set>/`, so a real set replaces its placeholder without an add/add conflict.
  `written.py` merges every set's history.json entries by `at` (unique ids, one formatVersion, kept as is so a bump
  reaches 0.3.0), fills `${INSTANCE}` paths, copies other files byte for byte, and derives what the instance must hold
  (a jar per staged download and applied file change, options.txt values of applied vanilla changes). Every report
  names the sets and marks placeholders. Convention sent to the coordinator for the feature workstreams.
- **Released-jar harness without Gradle.** `Compat030.java` is a single-file program (`java -cp <jars> Compat030.java`)
  compiled at launch against the released jar, Gson 2.14.0, fabric-loader and slf4j found in the Gradle cache by
  `compat030.py`; it never sees this repository's classes, and build.gradle isn't involved. It checks AC3.3 plus
  PendingActions/ClientSettings and that reading changes no file. A formatVersion 2 history and an unknown op type
  make it fail (checked by hand). RulesLoader: counts with and without 0.4's new top-level sections (the SPEC's
  LegacyRulesParseTest method, here with the released classes).
- **Downgrade driver compiled against the released 0.3.0** (new source set `e2eDowngrade`, `-Pe2e.oldJar`; compiling it
  against 0.1.0 fails, proving the isolation). It runs on 0.3.0 (History, Undo last, its own Apply) and on 0.4
  (History). The instance: the composed sets, their implied jars and options, a test mod for 0.3.0's Apply; the fake
  Modrinth knows only 0.3.0. 0.4 is "reinstalled" by swapping the jars in mods/. The 0.4-only files must be
  byte-identical after 0.3.0; after 0.4 starts again, `written.KEPT` lists what must survive (stutter sessions, startup
  runs, server limits, awareness decisions, benchmark runs; 0.4 may add). Log checks read the session's
  `latest.log` plus any `logs/*.log.gz` rotated during the launch.
- **Profile part (P-H1): two switches before one restart, on an instance of its own with Sodium** (from the Gradle
  cache), so staged config keys are covered: `profile-apply` (two switches, helper at exit), then Undo last twice +
  a check start, and on a copy of the instance from after the switches, Undo all + a check start. The checks read
  keys and values from the journal: each key's APPLIED changes must chain from its value before the first switch to
  the last switch's value, which accepts both v0.3's apply-both and 0.4's same-key replacement (older change
  DISCARDED, newer from the file's value) and rejects P-H1's wrong `before`. Mode `settings` is the stand-in (render
  distance, FPS cap, Sodium chunk threads staged by both, fog occlusion by the first); mode `profile` calls WS-P's API
  in `UndoDriver.switchProfile` (a stub that throws until then) and checks both `profiles.json` labels.
- **Lock (X-L3).** The five self-update/undo dry runs went through a runner that retried every 120 s on exit 3 and
  paused 150 s after each run (the lock was held by another session, r-jvm, most of that hour); the later pair ran
  under one hold (`--lock none` inside, released in the same script). README gives Phase 5 the same `pair` pattern.

## Findings

- **SPEC amendment 2n (product bug, found by the profile part):** after two switches and a restart, the second Undo
  last in the same start SKIPs the Sodium key the first Undo last staged ("You changed it since (it's now 4)"):
  UndoPlanner compares with the file, not the pending staged value, so the key ends at the first switch's value.
  Undo all is fine. The coordinator made it amendment 2n (WS-A); the phase stays as is, marked "expected to fail
  until 2n merges" (README, RESULT.md), as AC2n.2's evidence.
- 0.3.0 starting on a 0.4 staged Apply shows "RigTune: 1 change(s) not applied - They'll be retried when you exit" (its
  pending-at-startup toast; the op was never attempted, attempts 0). Its helper then applies the op. Wording only.
- The first status line the seeded run records is the raw key `rigtune.status.queued_update_dropped` for one tick,
  then the translated text (same in v0.3's evidence): the notice is set before the language loads.

## Status and what's left for Phase 5

- The five self-update/undo scenarios pass on 0.4.0-dev; the downgrade run and the compat harness pass on the
  placeholder sets; the profile part passes except the 2n phase.
- Before the final runs: the real `v040-written/<set>/` folders (each feature workstream; Phase 5 regenerates them from
  the RC), WS-P's call in `UndoDriver.switchProfile` (and `profile_labels` if profiles.json differs from C1), 2n merged.
  Then README "v0.4 runs": three `pair` holds and `compat030.py`.
- UNVERIFIED: the profile mode (`--profile-switch profile`) has never run (no Profiles API yet); the downgrade and
  compat checks have only seen placeholder sets; the SPEC's History screenshot showing "Profile: Battery" needs the
  real ws-p set / WS-P (the harness screenshots History in every relevant phase).
