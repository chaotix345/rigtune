# RigTune — coordinator progress log

Source of truth for resuming after context compaction. Update after every milestone.

## Goal
A Fabric mod for MC 26.2 (Java 25) that:
- detects hardware
- scans installed mods
- recommends mods to add, update, or remove, plus mod and vanilla settings, based on hardware
- auto-benchmarks to tune render distance and quality toward a target FPS
- applies changes
- stays up to date through remote rules and a live Modrinth API

The user gave full autonomy (2026-09-24). Publishing (GitHub repo, Modrinth listing) is outward-facing, so ask the user before doing it.

## Environment
- Repo: C:\Dev\Minecraft Setting Optimisation Mod. Nested git repo, branch `feat/rigtune-mvp`; main is unborn.
- JDK: portable Temurin 25 in C:\Dev\Tools\jdk\ (no system Java).
- User's instance: %APPDATA%\ModrinthApp\profiles\Fabric 26.2. READ-ONLY; never modify it.
- MC 26.2 jar: %APPDATA%\ModrinthApp\meta\versions\26.2-0.19.5\26.2-0.19.5.jar
- gh is logged in as chaotix345. Nothing published.

## Status
- [x] Feasibility research (see memory: minecraft-optimiser-mod-research)
- [x] Research: toolchain -> docs/research/toolchain.md
- [ ] Research: MC 26.2 and Sodium API surface -> docs/research/mc-api.md (agent running)
- [x] Research: tuning knowledge and rules sources -> docs/research/knowledge.md
- [x] Design doc -> docs/DESIGN.md; rules contract -> docs/RULES_SCHEMA.md; shared records in core/model
- [x] Scaffold builds (`./gradlew build` OK with Loom 1.17-SNAPSHOT, MC 26.2, Fabric API 0.161.0+26.2, Mod Menu 20.0.2 compileOnly, Sodium localRuntime)
- [ ] Phase 1, parallel (manual worktrees; the harness's `isolation: worktree` fails because it resolves the empty C:\Dev repo):
  - [ ] A1 brain: core.hardware, core.rules, core.recommend, rules/source/knowledge.json. Worktree C:\Dev\Worktrees\rigtune-brain, branch feat/core-brain
  - [ ] A2 apply: core.modrinth, core.apply, core.benchmark. Worktree C:\Dev\Worktrees\rigtune-apply, branch feat/core-apply
  - [ ] A3 updater: tools/update_rules.py and tests, .github workflows. Worktree C:\Dev\Worktrees\rigtune-updater, branch feat/core-updater
- [ ] Merge phase 1 into feat/rigtune-mvp; run the updater for real to generate rules-v1.json
- [ ] Phase 2, A4 client integration (needs mc-api.md): probe, screens, benchmark controller, entrypoints, helper launch at exit, Mod Menu, client gametest
- [ ] Verification: unit tests, runClientGameTest with screenshots, a dev client run with the user's mod set copied into run/mods
- [ ] Code review (opus), fixes, README, icon
- [ ] GitHub: create public repo chaotix345/rigtune (user approved 2026-09-24), push, CI green, PR → main, merge, tag v0.1.0 release
- [ ] Final report to user

## Decisions
- Name: RigTune, mod id `rigtune` (no Modrinth collision as of 2026-09-24).
- Target MC 26.2 + Fabric first; 26.3 is a stretch goal.

## Log
- 2026-09-24: repo initialised; research agents launched.
