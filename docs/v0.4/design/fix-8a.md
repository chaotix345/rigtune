# fix-8a: review round 2 (review-8) apply/compat/logging fixes + Phase 5 P5A-F4, P5B-F6

Branch `fix/review-8a` (from `origin/feat/v0.4.0` @ e7c44b0). Findings: docs/reviews/review-8.md (AH-1, CR-1, JW-1,
SE-3, SE-4, CR-2), docs/v0.4/verification/P5A-FINDINGS.md (F4) and P5B-FINDINGS.md (F6). fix-8b takes the UI, stutter
and profile items. Each fix has a test that failed first (noted per item). No file format change, no new op type, no
pending.json/history.json/last-apply.json shape change; the only new file location is the helper's own record (CR-1).

## AH-1 (high): Undo/Discard pending no longer drop a group the helper was killed in
- `PartlyApplied.groups(ops, files, recorded)` also reads the helper's record (`UnfinishedGroups.recorded(configDir)`,
  now public, read-only and quiet): a group with a recorded rename in effect (exactly the recorded new name in the mods
  folder, the old name gone) and another file op left (an enable's target missing, a disable's jar still there) is half
  applied, whatever its `attempts`. The attempts > 0 path (failed rollback) is unchanged.
- `Staging.discard` keeps such a group (`Staging.unfinishedRenames()`, from the config folder the helper uses for this
  pending.json); `UndoService` passes the record to new `UndoPlanner.plan/planEntry/recheck` overloads, so Undo last,
  Undo this and the confirm-time recheck skip it with `waits_partly`. The old signatures delegate with no record.
- Why "left" includes an enable whose download is gone: the helper then fails the enable and rolls back the recorded
  disable, so keeping the group puts the old jar back; dropping it would leave the mod missing.
- Tests (red first, 7 failures on the old PartlyApplied): UndoSafetyTest (kill after op 1 of 2 with attempts 0, through
  the real Staging/UndoService/helper) `discardKeepsAnUpdateTheHelperWasKilledInAndTheNextExitFinishesIt`,
  `...RollsItBackWhenItCantFinish`, `undoLastOfAnUpdateTheHelperWasKilledInWaitsAndTheNextExitFinishesIt`;
  UndoPlannerTest `anUpdateAHelperWasKilledInWaitsByTheHelpersRecord` (plan, planEntry, recheck; without the record the
  group is still discarded); PartlyAppliedTest (8: record-based detection, stale `.disabled` copy without a record,
  recorded `.disabled.1` vs someone's `.disabled`, another op's record, all done, addition group, download gone, the
  failed-rollback path without a record). `TestExecutors.killedAt` (a Mover that throws an Error) for tests outside
  core/apply.

## CR-1 (high): the record survives a 0.3.0 client
- `config/rigtune/unfinished-groups.json` instead of `config/rigtune/helper/unfinished-groups.json`. 0.1.0-0.3.0's
  HelperLauncher empties `helper/`; nothing older touches other files in `config/rigtune/` (`git grep` of
  `Files.list|newDirectoryStream|Files.walk|delete` at the three tags). A dev-build record in `helper/` is read when the
  new file is missing and deleted at the next write (load marks it dirty; every run prunes once). HelperLauncher keeps
  the old name in `helper/` so it survives until then.
- Residual, pinned by a test and written up in ws-g3.md "Review-8 follow-up": 0.3.0's own helper can't see the record.
  It finishes the group if its enable works; if the enable fails it drops the "already gone" disable from pending.json,
  so neither it nor the new helper after an upgrade can put the old jar back (0.3.0's pre-0.4 behaviour). What the move
  fixes: a group 0.3.0 refuses or doesn't run keeps its record, and the new helper rolls it back after the upgrade.
- Tests: ApplyGroupsTest `theRecordLivesOutsideTheHelperFolder`, `aRecordADevBuildLeftInTheHelperFolderIsStillReadAndThenMoved`,
  `aStaleRecordInTheHelperFolderIsDroppedWhenTheNewOneExists`; HelperCompat030Test (new verbatim pinned
  `v030/core/apply/HelperLauncher.java`, its cleanup run by reflection)
  `aGroupTheNewHelperWasKilledInIsStillRolledBackAfterA030ClientRanItsHelper` (red with the old location) and the
  residual pin `a030HelperThatCantFinishAGroupItCantSeeLeavesTheOldJarDisabled`.
- Manual check: none needed beyond the pinned 0.3.0 code (HelperLauncher.helperClasspath is what 0.3.0 runs at every
  helper launch). The released-jar harness doesn't read the record.

## JW-1 (medium): log lines name config files under config/rigtune
- New `core/apply/LogSafe` (JDK only, so helper-safe): `name(file)` = path under `config/rigtune` ("awareness.json",
  "helper/unfinished-groups.json") or the bare file name; `error(e, files...)` = "Type: message" with the files' folders
  and `user.home` cut out (an exception's own message and stack trace print full paths, so these lines no longer pass
  the exception); `text(s)` for untrusted text (SE-4).
- Fixed: every JsonStateFile line (9), StateStore, and the v0.4-added lines that printed an absolute config path:
  TrendService (awareness.json), ProfileService and DisableGuard (pending.json), StagedProjects, Staging.createJournal
  (history.json), UnfinishedGroups (helper.log), ModJars' v0.4 debug lines (mods paths). Found with
  `git diff v0.3.0 HEAD -- src/main/java src/client/java | grep '^+.*LOGGER\.'`.
- Left as they were (pre-0.4 lines, outside this finding): Staging's older lines (pending.json/history.json/apply lock),
  UndoService, HistoryStartup, ModJars.readModId/queuedUpdates warnings.
- Tests: JsonStateFileLogTest (5, red first: each failure path, captured RigTune logger, no temp-folder or home path in
  any line or stack trace) via the new test helper `core/LogCapture` (a Log4j appender); LogSafeTest (4).

## SE-3 (low): Modrinth file names with text-direction or invisible characters are refused
- `requireJarName`/`resolveJar` (every Modrinth-supplied name: DownloadPlanner, DryRunPlanner, HttpModrinthClient,
  RealController's download target) refuse any code point `LogSafe.hidden` flags: Cc (C0 and C1 controls), Cf (bidi
  overrides and isolates, LRM/RLM, zero-width space/joiners, BOM, word joiner), lone surrogates, U+2028/2029. `quote()`
  escapes the same, so the refusal message never shows them raw. Letters, emoji (surrogate pairs) and accented names pass.
- `isSafeJarName`, the helper's check of every enable target (`ApplyExecutor.containmentProblem`), is unchanged on
  purpose: it also covers an Undo re-enabling a jar the player named themselves (an emoji's zero-width joiner, say), which
  must stay undoable.
- Test: SafeFileNamesTest `rejectsTextDirectionAndInvisibleCharacters` (red first).

## SE-4 (low): DownloadPlanner's log lines can't be forged
- The jar's mod id, the Modrinth file name, recommendation ids and refusal text go through `LogSafe.text` (controls,
  format characters and separators escaped as backslash-u plus 4 hex digits, capped at 200 characters); files by name;
  errors as `LogSafe.error` (a stack trace only for an unexpected RuntimeException, not for an IOException or a planner
  refusal).
- Test: DownloadPlannerTest `aJarsModIdIsLoggedWithoutControlCharacters` (a dependency jar whose fabric.mod.json id holds
  a newline and an ANSI escape; red first).

## CR-2 (low): a settings rule has value or min/max, never both
- `validate_knowledge` refuses a settings rule with both (clients read only `value`, so the clamp and its `when` were
  silently lost and `restrictive_problems` skipped its legacy check) or neither, with or without `requires`, as
  `template_setting_problems` already does for templates. `restrictive_problems` now checks the `when` of any rule with
  min/max.
- Test: test_rules_v04 `test_a_settings_rule_has_value_or_min_max_never_both` (red first). Output unchanged: the old and
  the new updater regenerating the current knowledge.json into two scratch dirs gave identical files ("no content change;
  keeping revision 15"), equal to the committed ones.

## P5A-F4 (low): network lookups on their own threads
- `Probes.NETWORK` (2 daemon threads, "RigTune network") runs the Modrinth lookup (`OnlineLookupGate.Lookup.start(client,
  executor)`, which RealController.fetchOnline calls; the lookup also finds RigTune's own update) and the Apply downloads
  (RealController.startDownloads). `Probes.EXECUTOR` keeps History loads, Undo plans, report rebuilds, stutter IO,
  awareness commits and the other short local work. Rules fetches (RULES_EXECUTOR), previews (PreviewScreen) and settings
  saves (SettingsSaver) already had their own threads. Ordering is unchanged: the lookup gate still allows one lookup per
  scan/slug set, `downloading` still allows one download batch, and the continuations still hop to the render thread.
- Test: NetworkExecutorTest (injected executors): two lookups wait inside a stuck Modrinth on both network threads while a
  History-style load on a 2-thread worker pool completes; then both lookups finish. `Probes.NETWORK` is its own daemon
  pool. Red first = the new API didn't compile against the old code (the old code ran the lookup on `Probes.EXECUTOR`).

## P5B-F6 (low): LambDynamicLights' reason follows the estimated tier
- knowledge.json: "...which costs CPU time. On this PC's estimated tier, disabling it can win back some frame rate; ..."
  (was "CPU time that entry-level hardware is short of", shown on a 7800X3D whose tier only memory lowered). Rules
  revision 16, generated once: rules-v2.json and the bundled copy change only in that text and revision/generatedAt;
  rules-v1.json (the mod is `v1: false`) only in revision/generatedAt; REVIEW.md unchanged; check_rules_v1.py passes.
- Test: test_generated_rules `test_lambdynamiclights_reason_follows_the_estimated_tier` (red first).
- Not changed (same pattern, outside F6): tier-keyed settings reasons that name a component ("entry-level CPU",
  "entry-level graphics", "entry-level hardware" for renderDistance/simulationDistance/clouds/DH/Iris shadows). They are
  in rules-v1.json too; rewording them is a separate content decision.

## Evidence
- `./gradlew build` (both 26.2 and 26.3): 1807 tests each, 0 failed (before the merge of origin/feat/v0.4.0, which
  brought only docs).
- `python -m unittest discover -s tools/tests`: 326 OK; `tools/e2e/tests`: 216 OK.
- Released-jar harness: `python tools/e2e/compat030.py --old-jar rigtune-0.3.0+mc26.2.jar` (sha256 5717f65c...cd7e9) on
  rules revision 16: RESULT PASS 9/9.
