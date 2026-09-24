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
- [ ] Research: toolchain -> docs/research/toolchain.md
- [ ] Research: MC 26.2 and Sodium API surface -> docs/research/mc-api.md
- [ ] Research: tuning knowledge and rules sources -> docs/research/knowledge.md
- [ ] Design doc -> docs/DESIGN.md
- [ ] Implementation plan -> docs/PLAN.md
- [ ] Implementation
- [ ] Verification (unit tests, dev client run, client gametest)
- [ ] Review and final report

## Decisions
- Name: RigTune, mod id `rigtune` (no Modrinth collision as of 2026-09-24).
- Target MC 26.2 + Fabric first; 26.3 is a stretch goal.

## Log
- 2026-09-24: repo initialised; research agents launched.
