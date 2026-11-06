# Status

HQ owns this file and rewrites it (not appends). Workers report to HQ instead of editing it. Last updated: 2026-11-06 by RigTune HQ (setup).

## HQ
- Thread: <t3-thread://v2/bbbcce92-2183-4264-8848-ffb9dadcbe55/mcp:launch:1a6f4b05932c9083d4ba76e4ee9cf8df5f6b97d3b474dec8790b8c3b8db68746> (pinned, "RigTune HQ"). Thread id: `mcp:launch:1a6f4b05932c9083d4ba76e4ee9cf8df5f6b97d3b474dec8790b8c3b8db68746` (workers `t3_thread_send` here). Project id: `951159fe-96cd-4205-939b-630793975b28`. Worktree: `C:\Users\Admin\.t3\worktrees\rigtune\chore-rigtune-hq-setup` (HQ marker in its git dir, `C:\Dev\rigtune\.git\worktrees\chore-rigtune-hq-setup\rigtune-hq`).
- Jobs: daily `scheduled-task:command:mcp:622a6084-df2e-4395-9626-220b8f75496b:schedule-task:rigtune-daily-20261106` (11:00, next 7 Nov); weekly `scheduled-task:command:mcp:622a6084-df2e-4395-9626-220b8f75496b:schedule-task:rigtune-weekly-20261106` (Mon 16:00, next 9 Nov).
- Secrets: none on this PC. `MODRINTH_TOKEN` is a GitHub Actions secret only (release.yml). HQ has no Modrinth access, so moderation status, messages, comments and reports aren't visible to it.
- No earlier coordinator threads or scheduled tasks exist in T3: v0.1-v0.5.1 ran as Claude Code CLI sessions (docs/PROGRESS.md, now history).

## Where it stands
- Released: **0.5.1** (29 Sep) for MC 26.2 and 26.3, on GitHub; Modrinth versions 0.5.1+mc26.2 (5Zq8LVfK) and +mc26.3 (FqlSEw70) uploaded and listed, but **the Modrinth project still isn't public**: the public API returns 404 for `oBN6pcGa` on 6 Nov, 5+ weeks after submission. Why is unknown (moderation queue, or a moderator message nobody has read).
- Rules: **r20 live on main** (6 Nov, PR #50; raw rules-v1.json and rules-v2.json both return revision 20). The Monday rules bot had failed every week since 5 Oct (Actions may not create PRs; that setting stays off); PR #52 makes it push the branch and leave the PR to HQ. Known rules problem in the live rules: Krypton is offered alongside e4mc (Fabulously Optimized ships it), which breaks joining singleplayer worlds; fix in progress.
- CI on main green (last runs 29 Sep). Snapshot canary: failed 28 Oct on 26.4-snapshot-6 (real compile breaks, #49), then "passed" 4 Nov by wrongly skipping snapshot-7: the canary is blind until the worker fix lands.
- Artifacts: 8,863 (~138 GB, 90-day default) being deleted on 6 Nov; retention-days fix in progress.
- Open issues: 16 (#33, #35-#49). Nothing closed since 29 Sep. No open security alerts.
- Ecosystem (6 Nov): no MC 26.4 release or pre-release yet (26.4-snapshot-7 on 3 Nov); Sodium and Iris have no 26.4 builds; Fabric API is beta-only for 26.4; Mod Menu alpha. 26.2 is being dropped by mods (Fabric API's last 26.2 build was 18 Sep).

## Release plan (proposed 6 Nov; needs Charlie's OK)
1. **0.5.2, fix batch** (recommended next, ~2-3 worker-days plus a release run): the 0.5.1 field-test fixes #35-#48 (UI and wording now; logic batch #37, #40-#42, #44-#46, #48 after it), #33 evaluator, the canary and retention fixes, rules r20+. Same MC versions (26.2, 26.3). Ship once both fix batches merge and main is green; target the week of 23 Nov.
2. **0.6.0, MC 26.4** when 26.4 is released and Sodium and Iris support it (not before; DESIGN.md says no pre-release nodes). Includes the 26.4 port (size from the #49 survey), the v0.6 backlog in docs/PROGRESS.md (preLaunch history reconcile off the render thread, review-12/13 lows, STUTTER-9/10/11, PERF-7, the AC5.14 re-run), and a decision on dropping 26.2.

## Numbers
| Date | Source | Number |
|---|---|---|
| 2026-11-06 | Modrinth oBN6pcGa | not public (API 404); downloads and followers not visible |
| 2026-11-06 | GitHub release downloads (main jars) | 0.1.0: 454; 0.2.0: 448 + 72; 0.3.0: 306 + 71; 0.4.0: 128 + 122; 0.5.0: 3 + 3; 0.5.1: 2 + 2 (26.2 + 26.3). Mostly CI: the E2E downloads old release jars on every run, so real installs are unknown. |
| 2026-11-06 | GitHub repo | 0 stars, 0 forks, 0 watchers |
| 2026-11-06 | Traffic (14 days) | 12 views (7 unique), 20 clones (12 unique); referrer Google 3 views |
| 2026-11-06 | Issues | 16 open; 0 closed since 29 Sep |
| 2026-11-06 | Billing (Actions storage, gross, all discounted to $0 net) | Sep US$3.40 (`nemesis-mod`), Oct US$31.89 (`rigtune`, 94,900 GB-hours), Nov to date US$5.61 (`nemesis-mod`). No paid minutes. |

## Waiting on Charlie
| When | What | Notes |
|---|---|---|
| 2026-11-06 | The setup questions (goal, weekly time, budget, pre-approvals, GitHub name, CI) | In the HQ setup report. |
| 2026-11-06 | Modrinth: is the project stuck in review, or is there a moderator message? | Check modrinth.com > Dashboard > Projects > RigTune and Notifications, or give HQ a read-only Modrinth token (`request_secret`, saved as a named file). |
| since 2026-09-28 | File the upstream Fabric API issue for the client game-test deadlock | Written, not filed: docs/v0.5/design/gametest-deadlock.md. Outward: needs his OK. Low priority. |

## Approved by Charlie
- 2026-09-27: per-call ns footprint budgets in tools/footprint-budgets.json may change (e.g. min(ceiling, 4 x max observed)); ceilings, CPU-ms, bytes, 0-allocation and leak checks stay strict (docs/PROGRESS.md v0.5.0).
- Nothing else standing: every release, tag and Modrinth change needs his OK.

## Waiting on others
- Mojang: MC 26.4 release (weekly snapshots, no date). Sodium and Iris: 26.4 builds.

## Dated reminders
- 2026-11-07: finish the artifact purge (started 6 Nov; it will stop at the 2-hour background limit with ~6,000 of 8,859 done). Check `gh api repos/chaotix345/rigtune/actions/artifacts --jq .total_count`; delete what's left that is older than 7 days and not `release-files`: `gh api --paginate repos/chaotix345/rigtune/actions/artifacts --jq '.artifacts[]|select(.name!="release-files" and (.created_at < "<today-7d>"))|.id' | while read id; do gh api -X DELETE repos/chaotix345/rigtune/actions/artifacts/$id; done` (stay under 5,000 API calls an hour).
- 2026-11-09: first rules bot run under HQ (Mon 14:00); the weekly review opens its PR (docs/hq.md "Rules updates"). With PR #52 merged, the bot run should succeed with a "HQ opens the PR" notice.
- 2026-11-11: snapshot canary (Wed 05:00 UTC, 16:00 Sydney): it must build the newest 26.4 snapshot, not skip, once the canary fix is merged.
- 2026-11-13: billing check: November's `rigtune`/`nemesis-mod` storage stopped growing after the purge.

## Money
- None spent. GitHub Pro via Education (to 29 Oct 2028). RigTune's Actions storage was fully discounted ($0 net).

## Active work
| Thread | Branch | Owns | State |
|---|---|---|---|
| <t3-thread://v2/bbbcce92-2183-4264-8848-ffb9dadcbe55/mcp:launch:e287e2d9555d14ef290311b7759623393f5946914c15325ba1791a27461be651> | ci/artifact-retention-and-rules-bot | .github/workflows/*, tools/tests/test_ci_workflow.py | PR #52 reviewed, auto-merge queued |
| <t3-thread://v2/bbbcce92-2183-4264-8848-ffb9dadcbe55/mcp:launch:4e7a726b427f49de14f11976135183130f1d077297e28cf69a227334899bb912> | fix/snapshot-canary-49 | tools/snapshot_canary.py + tests/fixtures, docs/research/v0.6/ | running |
| <t3-thread://v2/bbbcce92-2183-4264-8848-ffb9dadcbe55/mcp:launch:13b68de6ef08e087e34e77af0ddbf1f4626c06d2cb8c91ab484f2c0f8d749839> | fix/stutter-e2e-33 | tools/e2e/stutter_run.py + its tests | PR #53 reviewed, auto-merge queued |
| <t3-thread://v2/bbbcce92-2183-4264-8848-ffb9dadcbe55/mcp:launch:d392cdcae50606b32dee4893cc363c1631763980c0c5f659d6eca8d865a43d61> | fix/0.5.2-ui-wording | src/ UI screens, en_us.json (#35 #36 #38 #39 #43 #47) | running |
| <t3-thread://v2/bbbcce92-2183-4264-8848-ffb9dadcbe55/mcp:launch:b7c20f9590d123c0a74874b6a8ff3be4c8ec112497022bf57f70716e36af820c> | fix/rules-krypton-conflicts | rules/, rules scenario tests | running (Krypton vs e4mc; stale Krypton vs Fast Noise conflict) |

Next worker after the UI batch merges: the 0.5.2 logic batch (#37, #40, #41, #42, #44, #45, #46, #48; it also touches en_us.json and rules/source/knowledge.json).

## Housekeeping
- Local branches in C:\Dev\rigtune from v0.5 (fix/, research/, scratch/, local/, tmp/, throwaway/) and old worktrees `C:\Dev\Worktrees\rigtune-audit-verify` and `rigtune-realworld`; remote `scratch/*`, `research/v05-*`, `fix/v05-*` branches and the scratch workflows they register. The weekly review removes what is merged or provably superseded and lists the rest for Charlie.
- This PC ran short of memory on 6 Nov with four workers building (two Gradle runs died with out-of-memory errors and passed on retry): keep at most two Gradle-building workers at once.

## Recent changes
- 2026-11-06 PR #51: RigTune HQ set up (STATUS.md, docs/hq.md, CLAUDE.md, release guard hook, attribution off); PROGRESS.md marked as history.
- 2026-11-06 PR #50: rules r20 live (Krypton and Particle Core on 26.3, FO/Additive lists). HQ fixed a scenario test that pinned "Krypton has no 26.3 build". All five `bot/rules-update-*` branches deleted. Artifact purge started.
