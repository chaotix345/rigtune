# Status

HQ owns this file and rewrites it (not appends). Workers report to HQ instead of editing it. Last updated: 2026-11-11 by RigTune HQ.

## HQ
- Thread: <t3-thread://v2/bbbcce92-2183-4264-8848-ffb9dadcbe55/mcp:launch:2cab5c2f8a450a0caa41bc0c03a1456ead2851957b07681ca804a5f2e49f4ca0> (pinned, "RigTune HQ", since 8 Nov). Thread id: `mcp:launch:2cab5c2f8a450a0caa41bc0c03a1456ead2851957b07681ca804a5f2e49f4ca0` (workers `t3_thread_send` here). Project id: `951159fe-96cd-4205-939b-630793975b28`. Worktree: `C:\Users\Admin\.t3\worktrees\rigtune\chore-hq-20261108-1100` (HQ marker in its git dir, `C:\Dev\rigtune\.git\worktrees\chore-hq-20261108-1100\rigtune-hq`). The previous HQ (<t3-thread://v2/bbbcce92-2183-4264-8848-ffb9dadcbe55/mcp:launch:1a6f4b05932c9083d4ba76e4ee9cf8df5f6b97d3b474dec8790b8c3b8db68746>, 6-8 Nov) is retired; its marker is deleted.
- Jobs: daily `scheduled-task:902945c8-9117-4c26-b531-3ad79fdfcc87` (11:00, next 10 Nov 11:00); weekly `scheduled-task:d8d1822d-1aa2-47e7-9128-9d62a20a6d98` (Mon 16:00, next 16 Nov 16:00).
- Secrets: none on this PC. `MODRINTH_TOKEN` is a GitHub Actions secret only (release.yml). HQ has no Modrinth access, so moderation status, messages, comments and reports aren't visible to it.
- Low RAM on this PC: follow `C:\Users\Admin\Documents\ram-watch\README.md`. Check first with `powershell -ExecutionPolicy Bypass -File C:\Users\Admin\Documents\perfinfo.ps1`: kernel paged pool over 4 GB or nonpaged pool over 2 GB means a kernel leak, and Charlie's restart fixes it. Otherwise stop dev servers you don't need. Never kill the hidden session-0 Python processes. Ask Charlie before uninstalling anything or stopping services. Never disable a driver or service without first checking the device-class filter lists (on 10 Nov, disabling a Citrix USB filter killed all USB input at boot), and follow Charlie's rules for boot changes in the README (a restore point first, and an undo that works offline from Windows Recovery). The leaks so far: an Acronis driver (uninstalled 7 Nov), then Citrix Workspace leaking a token per process started (uninstalled 11 Nov, confirmed at about 0 per process afterwards).
- No earlier coordinator threads or scheduled tasks exist in T3: v0.1-v0.5.1 ran as Claude Code CLI sessions (docs/PROGRESS.md, now history).

## Where it stands
- Released: **0.5.2** (7 Nov 2026) for MC 26.2 and 26.3: tag v0.5.2 at f15e8436, release run 42343609374. GitHub release https://github.com/chaotix345/rigtune/releases/tag/v0.5.2 has the main and sources jars for both versions. Modrinth has 0.5.2+mc26.2 (nFIvBLPX) and +mc26.3 (hhiDEjf1); release.yml's verify step matched each sha512, the CDN file, version number, game version, loader and type. Before that: 0.5.1 (29 Sep; Modrinth 5Zq8LVfK, FqlSEw70). Charlie ran the 0.5.2 check on his desktop (RX 7800 XT, 180 Hz, Modrinth App): all 9 steps passed. The Modrinth description is the 0.5.2 rewrite (PR #65; Charlie pasted it 7 Nov). **The Modrinth project is still "Under review"**: Charlie's dashboard (6 Nov) says it is in the moderation queue, with no moderator message ("we aim to review within a week, but some projects may face delays"). The public API returns 404 until it's approved. Submitted around 25 Sep.
- Rules: **r23 live on main** (9 Nov, PR #73; raw rules-v1.json and rules-v2.json both return revision 23). r23 fixes #68: when Distant Horizons is rendering on tier 5, render distance gets its own 12-chunk entry ("Distant Horizons already draws the far terrain cheaply, so 12 vanilla chunks is a good balance on this hardware; going past 12 costs frame rate…"). Tier 3 on a heap of 2 GB or less gets its own 8-chunk entry, so the tier reason no longer argues against the cap. The tier-5 reason now ends "the benchmark can fine-tune it". Values, impact and default ticks are unchanged: Opus and Codex reviews checked this, and a new r22-equivalence test now enforces it. Left as is: `ServerCap` can still append "The server sends at most N chunks." after a sentence naming more chunks (code-only, not a regression). r22 (7 Nov, PR #60) added three things:
  - turning off Distant Horizons' auto-updater where a launcher manages the mods (new `launcher-mods` flag, so 0.5.2+ only);
  - the chunk-loading stutter advice split: a general entry as in r21, plus a separate Sodium Chunk Updates entry only when that setting isn't Deferred;
  - the reworded tier-4 render distance reason.
  r21 (6 Nov, PR #55): Krypton conflicts with e4mc (its 0.3.x jars break joining singleplayer worlds with e4mc, which Fabulously Optimized and Additive ship); the unsupported Krypton vs Fast Noise / Material Rule Compiler conflict is gone from v2 (0.1.x keeps it via v1 overrides). The Monday rules bot had failed every week since 5 Oct (Actions may not create PRs; that setting stays off); PR #52 makes it push the branch and leave the PR to HQ.
- CI on main green all week (6 of 6 runs). Rules bot: working again. Its first run after PR #52's fix (9 Nov, 10:51 UTC, run 42604333229) succeeded with "no content change; keeping revision 23", so it pushed no branch and there was no PR to open. GitHub has started its 03:00 UTC Monday cron 5-7.5 h late every week (08:27-10:29 UTC, about 19:30-21:30 Sydney), so the 16:00 weekly review finds no branch and the next daily opens the PR. Snapshot canary: it skipped 26.4-snapshot-2 to -7 because `tools/add_mc_version.py` refused snapshots without their own Mod Menu build (fixed by PR #56). Expect the 11 Nov canary to fail on the real 26.4 breaks and comment on #49; that's the intended signal.
- Artifacts: retention set on every upload (PR #52: 7 days, release-files 30). 9 Nov: 1,250 (about 20 GB, from the 6-7 Nov CI burst), 1,244 of them expiring 13-15 Nov; 4 release-files from Sep expire 26-28 Dec. HQ deleted 22 (408 MB) with GitHub's 90-day default: a 6 Nov push run on `fix/0.5.2-ui-wording`, a branch cut before PR #52. Every workflow on main sets retention.
- Open issues: 1 (#49, the 26.4 canary). Closed 9 Nov: #68 (PR #73). Closed 6-7 Nov: #33 (PR #53); #35 #36 #38 #39 #43 (PR #58); #37 #40 #41 #42 #44 #45 #48 (PR #60); #46 #47 (PR #63). No open security alerts.
- Ecosystem (9 Nov): no MC 26.4 release or pre-release (still 26.4-snapshot-7, 3 Nov; no snapshot this week). Sodium and Iris have no 26.4 builds; Sodium and Sodium Extra put out 26.3 betas this week (the rules bot picks up availability). Fabric API 0.165.4+26.4 is beta, for snapshot-7; C2ME and Moonrise have 26.4 snapshot alphas. Fabric Loader 0.20.1 has been stable since 19 Oct; RigTune builds on 0.19.5 and needs `>=0.19.5`, so players on 0.20.x run it on a loader CI doesn't test (Backlog). 26.2 is being dropped by mods (Fabric API's last 26.2 build was 18 Sep). Not ready to port: 0.6.0 waits for the 26.4 release and Sodium/Iris.

## Goals (Charlie, 6 Nov)
- RigTune is a portfolio piece with steady downloads; getting the Modrinth listing public comes first. No deadline given.
- Charlie's time: about 15 minutes a week (approving releases, a ~15-minute laptop check per release). Budget: $0.
- RigTune stays under his GitHub name (chaotix345). GitHub CI stays on.

## Release plan (6 Nov)
1. **0.5.2**: released 7 Nov (see "Where it stands"). The next patch (0.5.3) only if a bug needs code; rules fixes ship on main without a release.
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
| 2026-11-09 | Modrinth oBN6pcGa | Not public yet (API 404); still under review |
| 2026-11-09 | GitHub release downloads (main jars, 26.2 + 26.3; change since 6 Nov) | 0.1.0: 455 (+1); 0.2.0: 449 + 73 (+1, +1); 0.3.0: 308 + 73 (+2, +2); 0.4.0: 130 + 124 (+2, +2); 0.5.0: 5 + 5 (+2, +2); 0.5.1: 4 + 4 (+2, +2); 0.5.2: 2 + 3 (new). Mostly CI E2E downloads. |
| 2026-11-09 | GitHub repo | 0 stars, 0 forks, 0 watchers (no change) |
| 2026-11-09 | Traffic, daily (views/unique) | 27 Oct 1/1; 31 Oct 6/3; 4 Nov 2/1; 6 Nov 1/1; other days 0. 14-day total 10 views (6 unique), down from 12 (7). Referrers: Google 1, github.com 1. |
| 2026-11-09 | Clones, daily (count/unique) | 26 Oct 3/3; 27-28 Oct 1/1 each; 1 Nov 1/1; 2 Nov 2/2; 4 Nov 5/3; 6 Nov 649/45; 7 Nov 477/51 (the 0.5.2 CI and workers). 14-day total 1,139 (97 unique). |
| 2026-11-09 | Issues (week from 2 Nov) | 1 opened (#68), 15 closed; 2 open |
| 2026-11-09 | Artifacts | 1,250 (~20 GB, mostly expiring 13-15 Nov), up from 253 after the purge |
| 2026-11-09 | Billing, Nov to date (gross, $0 net) | US$6.03 Actions storage (`nemesis-mod`, 17,943 GB-hours), up from US$5.61 on 6 Nov. No paid minutes. |

## Waiting on Charlie
| When | What | Notes |
|---|---|---|
| since 2026-09-28 | File the upstream Fabric API issue for the client game-test deadlock | Written, not filed: docs/v0.5/design/gametest-deadlock.md. Outward: needs his OK. Low priority. |

## Approved by Charlie
- 2026-09-27: per-call ns footprint budgets in tools/footprint-budgets.json may change (e.g. min(ceiling, 4 x max observed)); ceilings, CPU-ms, bytes, 0-allocation and leak checks stay strict (docs/PROGRESS.md v0.5.0).
- 2026-11-06: HQ merges the weekly rules PRs on its own review (docs/hq.md "Rules updates").
- 2026-11-06: patch releases (0.5.x) once the dry run passes, with a heads-up to Charlie first. Minor and major releases (0.6.0 on) still need his OK each time, as do Modrinth edits.
- 2026-11-06: HQ may delete the leftover v0.5 scratch branches and worktrees.
- 2026-11-07: rewrite the Modrinth description (docs/modrinth/body.md) for 0.5.2 (done in PR #65; Charlie pastes it in).
- 2026-11-06: RigTune stays under chaotix345; GitHub CI stays on.
- 2026-11-09: HQ makes the internal decisions and orchestrates on its own: which workers to start and when, backlog burns, reviews and merges (Charlie: "i'm authorising u to make those decisions and orchestrate urself"). Outward actions (releases beyond the pre-approved patch releases, Modrinth edits, comments or replies, contacting anyone, sign-ups, spend) still need his OK each time, per CLAUDE.md.

## Waiting on others
- Mojang: MC 26.4 release (weekly snapshots, no date). Sodium and Iris: 26.4 builds.

## Dated reminders
- 2026-11-12: check the 11 Nov snapshot canary run (Wed 05:00 UTC, 16:00 Sydney). It must build the newest 26.4 snapshot, not skip. It's expected to fail on the real 26.4 breaks and comment on #49; triage those breaks into #49.
- 2026-11-14: RAM check after the Citrix uninstall (11 Nov 01:31 restart). In `C:\Users\Admin\Documents\ram-watch\ram-watch.csv`, tokens were 5.9k → 8.3k and file objects 208k → 473k in the first 9 hours. Tokens should stay roughly flat overnight, and file objects should level off rather than climb every hour. If either keeps climbing, tell Charlie and follow the README (Crash Defender is the next suspect).
- 2026-11-16: billing and artifacts check, after the 6-7 Nov CI burst expires (13-15 Nov): the artifact count should be back near the low hundreds and November's storage should be growing slowly.
- 2026-11-16 (then weekly): Krypton's stale jars (astei/krypton #169). Once Krypton re-uploads jars with the #152 fix, remove the e4mc conflict from knowledge.json by hand (worker PR; PR #55 notes).
- 2026-11-20: if RigTune is still "Under review" on Modrinth (8 weeks), recommend to Charlie that he asks Modrinth's moderators or support (outward: his OK).

## Money
- None spent. GitHub Pro via Education (to 29 Oct 2028). RigTune's Actions storage was fully discounted ($0 net).

## Active work
| Thread | Branch | Owns | State |
|---|---|---|---|
None. Workers are on (Charlie, 9 Nov); HQ starts them as needed.

Also from PR #60's review: the 26.3 client game tests weren't run locally for #60 (CI ran them).

## Backlog
Internal-only, low-risk tasks for quota that would otherwise go unused (hq skill "Backlog and leftover quota"; Claude resets Mon ~08:00 Sydney, so Sunday is the burn day). Nothing under `rules/` (live on merge), nothing that publishes or releases; code fixes reach users only in a later release.
- PR #63 review nits, with failing-first tests: NetChanges counts from the first journal "before", so a file edited to C while the queue ends at A undercounts the launch toast (`NetChanges.java:44`); PendingNet's `SettingValues.same` ignores case and quotes (`PendingNet.java:35`).
- `FrameHookBudgetTest` fails when the PC is low on memory: make it reliable without loosening the ceilings (STATUS "Approved by Charlie", 27 Sep).
- review-12/13 lows (`docs/reviews/review-12.md`, `review-13.md`: R12FEAT-2..9, R12X-4/5, R12APPLY-4, R12REL-3/5/6, R12STUTTER-4/5/7, R13-2): triage which still apply on main and fix a few small ones per PR, one area at a time.
- Leftover v0.5 branches (`fix/v05-awareness`, `fix/v05-r11-ws-b`, `research/v05-ci`, `research/v05-verify`, `local/v05-battery`): compare each with main (`git cherry`, `git diff origin/main...<b>`) and report what isn't on main, with a keep or delete call. Report only; HQ deletes.
- Docs drift: check `CONTRIBUTING.md`, `docs/DESIGN.md` and `tools/MC_VERSIONS.md` against the current build and code, and fix what's stale (docs PR).
- Fabric Loader: RigTune builds and tests on 0.19.5, while 0.20.1 has been stable since 19 Oct. Find out whether CI should test the newer loader, or move to it, and what that costs (`gradle.properties`, the build workflows). Report first; no change to the `fabricloader` requirement without HQ.

## Housekeeping
- 6 Nov: the six remote `scratch/*` proof branches deleted (local copies remain). Kept, because they hold commits that aren't on main: remote and local `fix/v05-awareness`, `fix/v05-r11-ws-b`, `research/v05-ci`, `research/v05-verify`, local `local/v05-battery`. The weekly review checks each against main (`git cherry`, `git diff origin/main...<b>`) before deleting.
- Local unmerged branches can't be deleted from a thread (a user hook blocks `git branch -D`); they cost nothing, so they stay.
- Old worktrees `C:\Dev\Worktrees\rigtune-audit-verify` and `rigtune-realworld`: their branches are merged, but both have uncommitted files (AuditVerify*/RealWorld* tests; an edited ApplyExecutor.java) and broken links to the repo's old folder ("C:/Dev/Minecraft Setting Optimisation Mod"). Kept; don't delete without saving those files first.
- This PC ran short of memory on 6 Nov with four workers building: two Gradle runs died with out-of-memory errors and passed on retry. Charlie found the main cause on 7 Nov, a kernel memory leak in an Acronis driver, now uninstalled (see "HQ" for the RAM runbook). Since 8 Nov every local gate runs through the shared gate queue (2 at once across all projects, 3 GB free RAM to start), and up to 4 workers may run (docs/hq.md "The gate", "Workers").

## Recent changes
- 2026-11-09 PR #73 merged: **rules r23 live** (#68 render distance reason; raw v1 and v2 show 23). Reviewed by Opus and by Codex gpt-6.1-sol (high); both approved. A wording nit and an r22-equivalence test were added before the merge. The worker thread is settled, and both #68 worktrees are removed.
- 2026-11-09 Charlie handed HQ the internal decisions ("Approved by Charlie"). Workers back on; #68 worker started. Backlog burns run on Sundays.
- 2026-11-09 weekly review: numbers, ecosystem and Backlog (Fabric Loader item added). Housekeeping: deleted 22 stale 90-day artifacts (408 MB), removed the old HQ worktree `chore-rigtune-hq-setup`, deleted 19 local branches whose remotes were merged and gone, and settled the old HQ's two review subagent threads. docs/hq.md: the rules bot's real start time.
- 2026-11-09 hq skill update applied (Charlie, 8 Nov): every local Gradle gate runs through the shared gate queue as a background command; up to 4 workers, none started under 4 GB free RAM; worker briefs get the Approach and Shipping lines and short in-game screenshot and mock lines; the daily and weekly jobs gain backlog steps (both live tasks updated); "Backlog" section added. GitHub CI unchanged.
- 2026-11-08 HQ handed over to a new thread. New jobs created and bound to it; the old thread's two jobs deleted; release-guard marker moved.
- 2026-11-07 **0.5.2 released** (tag v0.5.2 at f15e8436, run 42343609374; Modrinth nFIvBLPX and hhiDEjf1). Charlie's check on his desktop passed all 9 steps. He pasted the new Modrinth description. Ops PR #66 was the first merge HQ waited on with a background poll.
- 2026-11-07 PRs #63, #64, #65 merged: #46 and #47 (net changes only, after a review round); release PR (mod_version 0.5.2, CHANGELOG, laptop check); Modrinth body rewrite (Charlie approved). The release dry run passed (run 42340370468). HQ lost about an hour three times waiting on auto-merges that never woke it; docs/hq.md "Merging" now says how to wait.
- 2026-11-07 PR #60 merged: the 0.5.2 logic batch (#37 #40 #41 #42 #44 #45 #48, #47 item 3). Rules r22 is live; raw v1 and v2 both show 22. A fresh review caught that r22 would drop the chunk-loading advice when chunk loading isn't measured; the worker fixed it before the merge. #46 (option 2) went to the same worker. The RAM runbook pointer was added to "HQ".
- 2026-11-07 PR #58 merged: 0.5.2 UI and wording fixes (#35 #36 #38 #39 #43, #47 items 1, 2, 4-8). Logic-batch PR #60 opened. docs/hq.md worker brief: new hard limit, no `rm` in Bash, no force branch delete, no `git restore` (Charlie, via Blindstamp HQ: workers stalled on approval prompts overnight).
- 2026-11-06 PRs #55, #56, #57: rules r21 live (Krypton/e4mc; stale Fast Noise conflict); the canary's add_mc_version fix and the 26.4 survey (docs/research/v0.6/mc-26.4.md: ~12 agent-hours); STATUS update. Artifact purge finished.
- 2026-11-06 PRs #52, #53, #54: artifact retention + the rules bot leaves the PR to HQ; #33 stutter evaluator fixed; STATUS/hq.md update. Charlie answered the setup questions (Goals, Approved by Charlie).
- 2026-11-06 PR #51: RigTune HQ set up (STATUS.md, docs/hq.md, CLAUDE.md, release guard hook, attribution off); PROGRESS.md marked as history.
- 2026-11-06 PR #50: rules r20 live (Krypton and Particle Core on 26.3, FO/Additive lists). HQ fixed a scenario test that pinned "Krypton has no 26.3 build". All five `bot/rules-update-*` branches deleted. Artifact purge started.
