# RigTune — coordinator progress log

Source of truth for resuming after context compaction. Update after every milestone.

## Goal
A Fabric mod for MC 26.2 (Java 25) that:
- detects hardware
- scans installed mods
- recommends mods to add, update, or remove, plus mod and vanilla settings, based on hardware
- auto-benchmarks to tune render distance toward a target FPS
- applies changes
- stays up to date through remote rules (auto-updated weekly by CI) and a live Modrinth API

The user gave full autonomy on 2026-09-24 and also approved creating and fully setting up a GitHub repo. Publishing to Modrinth was NOT asked for; offer it at the end.

## Environment
- Repo: C:/Dev/Minecraft Setting Optimisation Mod. Nested git repo; work branch `feat/rigtune-mvp`. Never commit to main.
- GitHub: https://github.com/chaotix345/rigtune (public).
  - Remote `main` = scaffold commit d8485a8, pushed as a base so PRs work.
  - Actions can create PRs.
- JDK: C:/Dev/Tools/jdk/jdk-25.0.4.1+1. Use `export JAVA_HOME=...` before `./gradlew`.
- User's instance: %APPDATA%/ModrinthApp/profiles/Fabric 26.2. READ-ONLY; never modify it.
- The harness's `isolation: worktree` fails (it resolves the empty C:/Dev repo). Create worktrees manually under C:/Dev/Worktrees/.

## Status
- [x] Research: toolchain.md, knowledge.md, mc-api.md in docs/research/
- [x] docs/DESIGN.md, docs/RULES_SCHEMA.md (the contract), core/model records
- [x] Scaffold builds: Loom 1.17-SNAPSHOT, MC 26.2, Fabric API 0.161.0+26.2, Mod Menu 20.0.2, Sodium localRuntime
- [x] A3 updater: MERGED. 39 Python tests. tools/update_rules.py and the build/update-rules/release workflows. Live smoke: FO 38 / Additive 50 mods at 26.3.
- [x] A2 apply: MERGED. 45 tests. core.modrinth, core.apply, core.benchmark.
- [x] A1 brain: MERGED. 60 tests, 21 mods, 6 obsolete. I fixed $refreshRateCap to snap to vanilla's multiples of 10.
- [x] Live updater run: rules revision 2 committed and pushed. CI green on GitHub.
- [x] A4 client: MERGED (58c6d6a, fast-forward). Build + runClientGameTest green. The UI screenshots look good.
- [ ] Review round 1: 18 findings in docs/reviews/review-1.md (1 critical, 3 high, 8 medium, 6 low). Fixes running in parallel:
  - F1: MERGED (5 commits). Findings #7, #8, #9, #14, #15, #16 plus the FPS-cap fix; the singleplayer RD cap bug is also fixed.
  - F2: MERGED (11 commits). Findings #1-#6, #10, #12 (Java), #13, #17. 208 unit tests green after the merge (291b561).
  - F3: MERGED. Findings #11, #12 (Python), #18. 61 Python tests.
  - All three merged. runClientGameTest on merged mvp PASSED (benchmark uncapped: RD 8, 1% low 582 vs target 170). README screenshots updated.
  - Re-review DONE (docs/reviews/review-2.md): all 18 FIXED; 7 new (N1, N2 medium; N3 low-medium; N4-N7 low).
- [x] Review round 2 fixes: MERGED (1aa6687). N1-N7 fixed, 238 tests. Rules revision 4: Distant Horizons caps vanilla RD at 12; tier 4/5 RD raises are medium and unticked.
- [ ] NEXT: merge test/production-smoke once its agent finishes, then run runClientGameTest (check the Discard button and the new toasts in the screenshots), then PR -> main, merge, tag v0.1.0.
- [ ] Production smoke: RUNNING in C:/Dev/Worktrees/rigtune-prod, branch test/production-smoke. It adds a runProductionSmoke task with the user's 43 mods (copied to scratchpad/usermods) and writes docs/smoke/ with screenshots and a report dump.
- [x] Rules triage: MERGED (rules revision 3; +renderscale, structure-layout-optimizer, zfastnoise, zmaterial-rule-compiler, asynclogger; reviewIgnore; beta/alpha update filter). Java 107 / Python 46 green.
  - Lesson: ALWAYS rerun `./gradlew test` after regenerating rules. The scenario tests read the bundled rules, and b291ef4 broke CI this way.
- Note: I added `"timeout": 5` to the user's global pwsh Stop hook, with their approval. It hung waiting on stdin.
- [ ] After A4: merge feat/client; full build + runClientGameTest; view the screenshots.
- [ ] Dev run with the user's mod set copied (not moved) into run/mods to sanity-check recommendations against the real setup.
- [ ] Code review (opus code-reviewer) and fixes; README with screenshots; icon.
- [ ] CI green on GitHub; PR feat/rigtune-mvp → main; merge; tag v0.1.0 so release.yml publishes the jar.
- [ ] Final report to the user (and offer Modrinth publishing and a 26.3 port).

## Follow-ups noted
- Modrinth updates: don't offer beta/alpha updates over a release install (send version_types or filter by the current version's type).
- 26.3 port: SDL replaced GLFW in 26.3, per toolchain.md §6.

## Decisions
- Name: RigTune, mod id `rigtune`, package io.github.chaotix345.rigtune, MIT license.
- Target MC 26.2 + Fabric first.
- Mod file changes are applied by a post-exit helper JVM (Windows file locks, and duplicate mod ids crash).

## Log
- 2026-09-24: research done; scaffold; A1–A4 launched; A2 and A3 merged; GitHub repo created and branch pushed.
- Game tests: run only ONE Minecraft client at a time (the user saw a Not Responding window when two agents ran them in parallel).
