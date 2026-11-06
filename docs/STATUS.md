# Status

HQ owns this file and rewrites it (not appends). Workers report to HQ instead of editing it. Last updated: 2026-11-07 by RigTune HQ.

## HQ
- Thread: <t3-thread://v2/bbbcce92-2183-4264-8848-ffb9dadcbe55/mcp:launch:1a6f4b05932c9083d4ba76e4ee9cf8df5f6b97d3b474dec8790b8c3b8db68746> (pinned, "RigTune HQ"). Thread id: `mcp:launch:1a6f4b05932c9083d4ba76e4ee9cf8df5f6b97d3b474dec8790b8c3b8db68746` (workers `t3_thread_send` here). Project id: `951159fe-96cd-4205-939b-630793975b28`. Worktree: `C:\Users\Admin\.t3\worktrees\rigtune\chore-rigtune-hq-setup` (HQ marker in its git dir, `C:\Dev\rigtune\.git\worktrees\chore-rigtune-hq-setup\rigtune-hq`).
- Jobs: daily `scheduled-task:command:mcp:622a6084-df2e-4395-9626-220b8f75496b:schedule-task:rigtune-daily-20261106` (11:00, next 7 Nov); weekly `scheduled-task:command:mcp:622a6084-df2e-4395-9626-220b8f75496b:schedule-task:rigtune-weekly-20261106` (Mon 16:00, next 9 Nov).
- Secrets: none on this PC. `MODRINTH_TOKEN` is a GitHub Actions secret only (release.yml). HQ has no Modrinth access, so moderation status, messages, comments and reports aren't visible to it.
- No earlier coordinator threads or scheduled tasks exist in T3: v0.1-v0.5.1 ran as Claude Code CLI sessions (docs/PROGRESS.md, now history).

## Where it stands
- Released: **0.5.1** (29 Sep) for MC 26.2 and 26.3, on GitHub; Modrinth versions 0.5.1+mc26.2 (5Zq8LVfK) and +mc26.3 (FqlSEw70) uploaded. **The Modrinth project is still "Under review"**: Charlie's dashboard (6 Nov) says it is in the moderation queue, with no moderator message ("we aim to review within a week, but some projects may face delays"). The public API returns 404 until it's approved. Submitted around 25 Sep.
- Rules: **r21 live on main** (6 Nov, PR #55; raw rules-v1.json and rules-v2.json both return revision 21). r21: Krypton conflicts with e4mc (its 0.3.x jars break joining singleplayer worlds with e4mc, which Fabulously Optimized and Additive ship); the unsupported Krypton vs Fast Noise / Material Rule Compiler conflict is gone from v2 (0.1.x keeps it via v1 overrides). The Monday rules bot had failed every week since 5 Oct (Actions may not create PRs; that setting stays off); PR #52 makes it push the branch and leave the PR to HQ.
- CI on main green. Snapshot canary: it skipped 26.4-snapshot-2 to -7 because `tools/add_mc_version.py` refused snapshots without their own Mod Menu build (fixed by PR #56). Expect the 11 Nov canary to fail on the real 26.4 breaks and comment on #49; that's the intended signal.
- Artifacts: retention set on every upload (PR #52: 7 days, release-files 30). Purge done 6 Nov: 253 left (4 release-files from Sep, 249 expiring within 7 days), from 8,863 (~138 GB).
- Open issues: 10 (#37, #40-#42, #44-#49). Closed 6-7 Nov: #33 (PR #53); #35 #36 #38 #39 #43 (PR #58). PR #60 closes #37 #40 #41 #42 #44 #45 #48. No open security alerts.
- Ecosystem (6 Nov): no MC 26.4 release or pre-release yet (26.4-snapshot-7 on 3 Nov); Sodium and Iris have no 26.4 builds; Fabric API is beta-only for 26.4; Mod Menu alpha. 26.2 is being dropped by mods (Fabric API's last 26.2 build was 18 Sep).

## Goals (Charlie, 6 Nov)
- RigTune is a portfolio piece with steady downloads; getting the Modrinth listing public comes first. No deadline given.
- Charlie's time: about 15 minutes a week (approving releases, a ~15-minute laptop check per release). Budget: $0.
- RigTune stays under his GitHub name (chaotix345). GitHub CI stays on.

## Release plan (6 Nov)
1. **0.5.2, fix batch** (next, ~2-3 worker-days plus a release run; pre-approved as a patch): the 0.5.1 field-test fixes #35-#48 (UI and wording merged 7 Nov, PR #58; logic batch PR #60 with rules r22; #46 as a small follow-up), the #33 evaluator, canary and retention fixes, rules r21+. Same MC versions (26.2, 26.3). Ship once both fix batches merge, main is green and the dry run passes; give Charlie a heads-up and the laptop-check kit first. Target the week of 23 Nov.
2. **0.6.0, MC 26.4** (needs Charlie's OK) when 26.4 is released and Sodium and Iris support it (DESIGN.md: no pre-release nodes). The port is ~12 agent-hours: ~4 h compile port possible now, ~8 h after 26.4 pre-releases and Sodium/Iris builds (docs/research/v0.6/mc-26.4.md). Plus the v0.6 backlog in docs/PROGRESS.md (preLaunch history reconcile off the render thread, review-12/13 lows, STUTTER-9/10/11, PERF-7, the AC5.14 re-run) and a decision on dropping 26.2.

## Numbers
| Date | Source | Number |
|---|---|---|
| 2026-11-06 | Modrinth oBN6pcGa (Charlie's screenshot; the API is 404 until approved) | 20 downloads, 1 follower; under review |
| 2026-11-06 | GitHub release downloads (main jars) | 0.1.0: 454; 0.2.0: 448 + 72; 0.3.0: 306 + 71; 0.4.0: 128 + 122; 0.5.0: 3 + 3; 0.5.1: 2 + 2 (26.2 + 26.3). Mostly CI: the E2E downloads old release jars on every run, so real installs are unknown. |
| 2026-11-06 | GitHub repo | 0 stars, 0 forks, 0 watchers |
| 2026-11-06 | Traffic (14 days) | 12 views (7 unique), 20 clones (12 unique); referrer Google 3 views |
| 2026-11-06 | Issues | 16 open; 0 closed since 29 Sep |
| 2026-11-06 | Billing (Actions storage, gross, all discounted to $0 net) | Sep US$3.40 (`nemesis-mod`), Oct US$31.89 (`rigtune`, 94,900 GB-hours), Nov to date US$5.61 (`nemesis-mod`). No paid minutes. |

## Waiting on Charlie
| When | What | Notes |
|---|---|---|
| since 2026-09-28 | File the upstream Fabric API issue for the client game-test deadlock | Written, not filed: docs/v0.5/design/gametest-deadlock.md. Outward: needs his OK. Low priority. |

## Approved by Charlie
- 2026-09-27: per-call ns footprint budgets in tools/footprint-budgets.json may change (e.g. min(ceiling, 4 x max observed)); ceilings, CPU-ms, bytes, 0-allocation and leak checks stay strict (docs/PROGRESS.md v0.5.0).
- 2026-11-06: HQ merges the weekly rules PRs on its own review (docs/hq.md "Rules updates").
- 2026-11-06: patch releases (0.5.x) once the dry run passes, with a heads-up to Charlie first. Minor and major releases (0.6.0 on) still need his OK each time, as do Modrinth edits.
- 2026-11-06: HQ may delete the leftover v0.5 scratch branches and worktrees.
- 2026-11-06: RigTune stays under chaotix345; GitHub CI stays on.

## Waiting on others
- Mojang: MC 26.4 release (weekly snapshots, no date). Sodium and Iris: 26.4 builds.

## Dated reminders
- 2026-11-09: first rules bot run under HQ (Mon 14:00); the weekly review opens its PR (docs/hq.md "Rules updates"). With PR #52 merged, the bot run should succeed with a "HQ opens the PR" notice.
- 2026-11-11: snapshot canary (Wed 05:00 UTC, 16:00 Sydney): it must build the newest 26.4 snapshot, not skip, once the canary fix is merged.
- 2026-11-13: billing check: November's `rigtune`/`nemesis-mod` storage stopped growing after the purge.
- 2026-11-16 (then weekly): Krypton's stale jars (astei/krypton #169). Once Krypton re-uploads jars with the #152 fix, remove the e4mc conflict from knowledge.json by hand (worker PR; PR #55 notes).
- 2026-11-20: if RigTune is still "Under review" on Modrinth (8 weeks), recommend to Charlie that he asks Modrinth's moderators or support (outward: his OK).

## Money
- None spent. GitHub Pro via Education (to 29 Oct 2028). RigTune's Actions storage was fully discounted ($0 net).

## Active work
| Thread | Branch | Owns | State |
|---|---|---|---|
| <t3-thread://v2/bbbcce92-2183-4264-8848-ffb9dadcbe55/mcp:launch:6fc90fe3b09e4061f1103461d1a993df4d94627ecb8e4b65d4b614dcc2818b77> | fix/0.5.2-logic | src/ logic and tests, en_us.json, rules/source/knowledge.json + generated rules, docs/research/knowledge.md, docs/RULES_SCHEMA.md | PR #60 (7 Nov; rules r22, live on merge): gate passed for the worker; HQ review and gate under way; worker adding the `launcher-mods` flag to RULES_SCHEMA.md |

#46 (staged changes to the same key, "applied 4 changes" for no net change): HQ chose the worker's option 2 on 7 Nov. Count net changes in the launch toast and hide "Discard pending" when the queue nets to nothing; the staging model, History and Undo stay as documented (audit M1, "2n"). It goes to the same worker as a new task once #60 merges. Then the 0.5.2 release (a pre-approved patch): dry run, a heads-up to Charlie with the laptop-check kit, then tag from HQ.

## Housekeeping
- 6 Nov: the six remote `scratch/*` proof branches deleted (local copies remain). Kept, because they hold commits that aren't on main: remote and local `fix/v05-awareness`, `fix/v05-r11-ws-b`, `research/v05-ci`, `research/v05-verify`, local `local/v05-battery`. The weekly review checks each against main (`git cherry`, `git diff origin/main...<b>`) before deleting.
- Local unmerged branches can't be deleted from a thread (a user hook blocks `git branch -D`); they cost nothing, so they stay.
- Old worktrees `C:\Dev\Worktrees\rigtune-audit-verify` and `rigtune-realworld`: their branches are merged, but both have uncommitted files (AuditVerify*/RealWorld* tests; an edited ApplyExecutor.java) and broken links to the repo's old folder ("C:/Dev/Minecraft Setting Optimisation Mod"). Kept; don't delete without saving those files first.
- This PC ran short of memory on 6 Nov with four workers building (two Gradle runs died with out-of-memory errors and passed on retry): keep at most two Gradle-building workers at once.

## Recent changes
- 2026-11-07 PR #58 merged: 0.5.2 UI and wording fixes (#35 #36 #38 #39 #43, #47 items 1, 2, 4-8). Logic-batch PR #60 opened. docs/hq.md worker brief: new hard limit, no `rm` in Bash, no force branch delete, no `git restore` (Charlie, via Blindstamp HQ: workers stalled on approval prompts overnight).
- 2026-11-06 PRs #55, #56, #57: rules r21 live (Krypton/e4mc; stale Fast Noise conflict); the canary's add_mc_version fix and the 26.4 survey (docs/research/v0.6/mc-26.4.md: ~12 agent-hours); STATUS update. Artifact purge finished.
- 2026-11-06 PRs #52, #53, #54: artifact retention + the rules bot leaves the PR to HQ; #33 stutter evaluator fixed; STATUS/hq.md update. Charlie answered the setup questions (Goals, Approved by Charlie).
- 2026-11-06 PR #51: RigTune HQ set up (STATUS.md, docs/hq.md, CLAUDE.md, release guard hook, attribution off); PROGRESS.md marked as history.
- 2026-11-06 PR #50: rules r20 live (Krypton and Particle Core on 26.3, FO/Additive lists). HQ fixed a scenario test that pinned "Krypton has no 26.3 build". All five `bot/rules-update-*` branches deleted. Artifact purge started.
