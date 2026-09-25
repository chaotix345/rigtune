# WS-H: self-update E2E for 0.3 (design notes)

Plan: docs/v0.3/plans/ws-h.md. Harness: tools/e2e (README "v0.3 runs"). Evidence: docs/smoke/self-update/dev-*-030.

## What runs

| scenario | command core | dry run on 0.3.0-dev | Phase 5 name |
|---|---|---|---|
| 0.2.0 → new | `--old-jar rigtune-0.2.0+mc26.2.jar --expect-history own-update` | PASS 20/20 | final-v020-to-030 |
| 0.1.0 → new | `--old-jar rigtune-0.1.0.jar --legacy-disable --expect-history` | PASS 21/21 | final-v010-to-030 |
| seeded 0.1.0 → new (H-M2) | `... --seed tools/e2e/seeds/v010-dh --expect-history` | 25/26 (3e WARN missing); PASS 26/26 with WS-B merged locally (`-wsb`) | final-v010-seeded-to-030 |
| undo after restart + per entry (M14, B-M3) | `--scenario undo` | 31/35 (M14 22/22, entry-apply 6/6, entry-undo 3/7 needs WS-B); PASS 43/43 with WS-B merged locally (`-wsb`) | undo-after-restart-030 |

Released jars (GitHub release assets; the harness checks `--old-sha256`): `rigtune-0.1.0.jar`
`8294d04a6b67e76dcff298366be38f85048ebf19a120baa9e8ed5b08b2e4b950`; `rigtune-0.2.0+mc26.2.jar`
`67275e232fe4de9f806dd6496f479d8385d8afabf9a6b93ffe909ce42f657de9`.

## Decisions

- **H-M1: no build.gradle change.** `compileE2eJava -Pe2e.oldJar=<0.2.0 jar>` compiles the unchanged driver; the source
  set's classpath doesn't see this repo's sources (a missing jar fails with "package ... does not exist"). The driver
  compiled against 0.2.0 runs on 0.2.0 (update) and 0.3.0-dev (verify). WS-0's CI now compiles it against both
  released jars (H-M3). The driver's v0.3 additions (status lines, mods listing at quit) use only API 0.1.0 has.
- **0.2.x old side, `--expect-history own-update`.** 0.2.0 journals its own update as one `apply` entry (disable old,
  enable new) that its helper marks `APPLIED`. The check wants exactly that entry, no legacy import, and every status
  as it was before the relaunch (the new version reads 0.2.0's journal without rewriting it).
- **H-M2 seed = data.** `tools/e2e/seeds/v010-dh/{pending,last-apply}.json` are templated copies of the user's real
  0.1.0 files (read-only; `make_seed.py`; every spelling of the instance folder, inside messages too, becomes
  `${INSTANCE}`; `source.json` has the originals' sha256), and `seed.json` lists the fake DH jars. Committed so Phase 5
  never reads `%APPDATA%` again.
- **Why the harness holds the DH jar open.** The user's group can only reach 0.3.0 if it survives 0.1.0's exit, where
  the 0.1.0 helper retries it. In the real failure a second process (DH's own updater) had `fabric-26.2.jar` open. The
  harness keeps the fake jar open from the 0.1.0 launch until its helper is done (Windows: no FILE_SHARE_DELETE, so
  the rename fails with the same "being used by another process"), the retry fails, attempts go 1 → 2, and the group
  is carried over while the self-update itself applies. Without that the fake DH group simply applies under 0.1.0.
- **"Nothing in mods/ changes at exit"** is checked as: no ApplyHelper process during or after the 0.3.0 launch, and the
  recursive `mods/` listing after exit equals the driver's listing when it quit (before RigTune's stop hooks). The
  drop's retiring of RigTune's DH download (`.rigtune-pending` → `.rigtune-superseded`) happens during the session and
  is checked separately as the only change.
- **3e WARN check.** Each FAILED op of the last-apply.json the new version read needs a `/WARN]` line of its own
  (bipartite matching; identical lines count once) with `attempt N of 3`, the op type and one of the op's own file
  names. A mod id alone doesn't count: one group's ops share it, and the enable's reason names the disable's file. WS-B's
  format ("RigTune could not apply a change at the last exit (attempt 2 of 3; ...): DISABLE_FILE fabric-26.2.jar: ...")
  passes (`-wsb`).
- **B-M3 in the undo scenario.** AC4.2's single evidence folder holds both: after M14's three starts, the same instance
  gets two Applies (`e2e-first`, then `e2e-second`), Undo this on the older, a restart, and a check start. Until WS-B
  merges, the driver finds the per-entry plan by shape (a public `(String) -> UndoPlan` method on RigTuneController or
  the controller) and UndoScreen's entry constructor `(Screen, RigTuneController, String)` by reflection; without the
  constructor it calls `undo(plan)` (still the post-exit helper path B-M3 is about). It prefers WS-B's name
  `undoPlanFor` and refuses an ambiguous shape match. Once WS-B is merged into feat/v0.3.0, switch to the direct calls
  (`controller.undoPlanFor(id)`, `new UndoScreen(parent, controller, id)`) so CI's `compileE2eUndoJava` guards them. With
  WS-B's branch merged locally the reflection found both and the whole scenario passed (43/43).
- **Lock.** owner.txt now carries PLAN's `agent:`/`worktree:`/`started:` lines (plus `run:`); release is `owner.txt`
  then `rmdir`, only for this run's lock. The dry runs were started through a wrapper that also releases a lock left
  by a killed script, only if owner.txt names this worktree.
- **helper.log per launch.** HelperLauncher's `Redirect.to` empties helper.log at every helper start, so the wait
  compares the file's (mtime, size) with its state before the launch and only reads it when it changed (six launches
  share the undo instance).
- **Self-review** (code-reviewer subagent, 0 high, 3 medium, 6 low): all fixed in 852d73a (helper.log truncation, the
  WARN matching, make_seed refusing a destination inside the source, exact notice key, every journal record of a
  carried op, whole-value paths with spaces, a lock holding other files is left whole, `--seed` only for self-update,
  entry-check needs the method and no plan problem, `undoPlanFor` preferred).

## Blocked / waiting

- On test/e2e-v03 (without WS-B) the seeded run's 3e check and the undo run's entry-undo/entry-check fail; both pass
  on a local merge with origin/feat/history 39eca5b (`-wsb` evidence; that merge isn't pushed).
- 3a (WS-A, merged at c250b7a): re-run on test/e2e-v03 @ ee7f8ea; the narrowed rule (RigTune's update of a loaded mod
  with its own update queued) still drops the carried-over DH group, with the notice "Cancelled RigTune's pending change
  to Distant Horizons: ..." (key `rigtune.status.queued_update_dropped`); all four runs gave the same counts as before.

## Phase 5

Build the release candidate's 26.2 jar, then run the four commands of tools/e2e/README.md "v0.3 runs" with the `final-*`
/ `undo-after-restart-030` names under the lock. Each run takes 1-4 minutes of client time.
