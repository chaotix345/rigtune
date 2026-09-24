# RigTune — coordinator progress log

Source of truth for resuming after context compaction. Update and commit after every milestone. After a compaction, reread this file before acting.

## v0.2.0 (started 2026-09-25)

Brief: the user's v0.2.0 prompt (full autonomy: research → release, including GitHub merges, tags and releases, and Modrinth publishing once a token exists). Scope: P0 1–5, P1 6–10, P2 11–13 (see docs/v0.2/SPEC.md once written).

### Environment (verified 2026-09-25)
- Integration branch: `feat/v0.2.0` (from main @ 2cf4317, tag v0.1.0). Feature branches merge into it; one final PR to main.
- Baseline on main: `./gradlew build` OK, 238 Java tests, 61 Python tests.
- `gh` is authenticated as chaotix345 (repo scope). Actions may create PRs (can_approve_pull_request_reviews=true). main isn't branch-protected: merge only with green CI anyway.
- Modrinth: the user created two PATs (2026-09-25), stored as Windows USER env vars (read via pwsh [Environment]::GetEnvironmentVariable(name,"User"); never print them): MODRINTH_TOKEN = setup token (project and version create/read/write), MODRINTH_CI_TOKEN = CI token (read projects, create and read versions, expires 2050). The CI token is set as the GitHub repo secret MODRINTH_TOKEN. A PreToolUse hook blocks curl with Authorization headers, so do not bypass it. The user approved on 2026-09-25: (1) a committed tools/modrinth_project.py that reads MODRINTH_TOKEN from the user env itself, so the token never goes on a command line, and talks only to api.modrinth.com; (2) uploading the identical v0.1.0 jar to Modrinth as an older version.
- MC versions (Modrinth tag API, 2026-09-25): 26.3 release 2026-09-15; 26.4 is only `26.4-snapshot-1` (2026-09-22), so it's out of scope.
- 2026-09-25: with the user's explicit approval, I copied the released rigtune-0.1.0.jar (sha256 8294d04a…) into the real instance's mods/. That was the ONLY write there; the instance stays read-only otherwise. Once they have played, read (never write) `logs/latest.log` and `config/rigtune/*` there.

### Status
- [x] Phase 0: orient. Docs read; baseline green.
- [ ] Phase 1: research (docs/research/v0.2/, one owner each, running in the main checkout, no game launches):
  - r-multiversion → multi-version.md (opus; prototype in scratchpad/r-multiversion/proto)
  - r-apidiff → api-diff.md
  - r-modrinth → modrinth.md
  - r-dhiris → dh-iris.md
  - r-benchmark → benchmark.md
  - r-triage → triage.md
- [ ] Phase 2: SPEC.md, DESIGN/RULES_SCHEMA updates, PLAN.md, independent plan review
- [ ] Phase 3: multi-version build restructure, alone; merge into feat/v0.2.0; CI green for every version
- [ ] Phase 4: features in manual worktrees (C:/Dev/Worktrees/rigtune-<name>); one game-test runner at a time
- [ ] Phase 5: verification (unit + game tests per version, production smoke 26.2 with a copy of the user's mods, 26.3 representative set, self-update E2E)
- [ ] Phase 6: two review rounds → docs/reviews/review-3.md, review-4.md
- [ ] Phase 7: PR to main, CI green, merge, bump, CHANGELOG, tag v0.2.0, release assets, Modrinth, rules v1+v2 live, update-rules run
- [ ] Phase 8: README, PROGRESS, memory, cleanup, final report

### Agents
| name | branch | worktree | status |
|---|---|---|---|
| (research agents write in the main checkout; no branches) | | | |

## Lessons (carried over from v0.1.0; don't relearn)
- The Bash tool is Git Bash. Use absolute paths; `cd` inside a command changes the session's working directory.
- Don't use Python string literals with Windows backslashes (they mangled this file once).
- JDK: `export JAVA_HOME="C:/Dev/Tools/jdk/jdk-25.0.4.1+1"` before every `./gradlew`. MC 26.x is unobfuscated: plugin `net.fabricmc.fabric-loom`, no mappings, `localRuntime`, Mojang names.
- The Agent tool's `isolation: worktree` fails here (it resolves the stray C:/Dev repo). Create worktrees manually: `git worktree add C:/Dev/Worktrees/rigtune-<name> -b <branch>`.
- Commit docs and research before creating worktrees. Give each research file exactly one owner, and forbid forks that write the same file.
- ONE Minecraft client at a time across all agents. Before a launch, check for `fabric.client.gametest` java processes. Kill only your own orphans (command line contains your worktree path). Never touch other java processes (e.g. the user's fabric-server-launcher).
- Always rerun `./gradlew test` after regenerating rules (scenario tests read the bundled rules; skipping this broke CI once).
- Fabric client game-test harness: it resets render distance to 5; its tick sync makes 1% lows unrepresentative; it deadlocks on world exit with Xaero's World Map or Distant Horizons loaded (harness issue); `runClientGameTest` wipes its run dir.
- 26.2 API renames are in docs/research/mc-api.md. OSHI reports a fake "System Battery" on desktops; the probe filters it out.
- The user's `fabric-26.2.jar` in their instance is Distant Horizons 3.3.0.

## Decisions
- Name RigTune, mod id `rigtune`, package io.github.chaotix345.rigtune, MIT.
- Mod file changes are applied by a post-exit helper JVM (Windows file locks; duplicate mod ids crash Fabric).
- v0.2.0: 26.2 + 26.3 only (26.4 not stable).

## Log
- 2026-09-24: v0.1.0 built, reviewed twice, merged (#1, 2cf4317), tagged and released.
- 2026-09-25: v0.2.0 started; integration branch feat/v0.2.0; Phase 1 research launched (6 agents).
