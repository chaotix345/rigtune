# "Written by 0.5" fixtures (docs/v0.5/SPEC.md 3b and X11)

Files RigTune 0.5 writes, committed by each feature workstream **from its own tests** ("seed, don't drive": the v0.4
H-M1 rule), so the released-jar harnesses (`tools/e2e/compat040.py` on every push, `compat030.py`) and the downgrade
E2E runs (`downgrade-050-to-040`, `downgrade-050-to-030`) read what 0.5 really writes. This README is WS-K's
(docs/v0.5/PLAN.md contracts item 17); each set folder and its `expect.json` belong to the set's workstream; the
placeholders are WS-E's.

## Sets

One folder per set, `src/test/resources/v050-written/<set>/`, named `ws-<id>` after its workstream. Until a workstream
commits its set, the tools use `placeholder/<set>/` (hand-written by WS-E in the same shapes, **not** output of RigTune
0.5) and report which sets were placeholders. The real set replaces the placeholder; the placeholder folder is then
deleted in the same commit.

| set | owner | files (as in `config/rigtune/`) | what they carry |
|---|---|---|---|
| `ws-l1` | WS-L1 | `settings.json` | `modFilesByRigTune` (the per-instance opt-in, 4e) |
| `ws-l2` | WS-L2 | `pending.json`, `awareness.json` | a held file group (4d); the P0.4 notice keys in `dismissed` |
| `ws-s` | WS-S | `stutter.json` | a session with `settingsAtStart`/`settingsAtEnd` and the `settingsChanged` tag (RW-11) |
| `ws-s2` | WS-S2 | `stutter-fixes.json`, `history.json`, `pending.json` | a staged and an applied stutter fix, their `apply` entries and the staged PATCH op (AC5.13) |
| `ws-p` | WS-P | `profiles.json`, `history.json` | a profile with a 1024 DH radius and `battery.previousProfile` set by PF-1; a baseline entry with `foldedEntryIds` and its profile labels (L8) |
| `ws-p2` | WS-P2 | `server-profiles.json` | one entry per kind (REMOTE, LAN_GUEST, REALM) (AC7.14) |
| `ws-b` | WS-B | `benchmarks.json` | runs with `context.worldFresh`, `dhGenerating` and `stagedAtStart` |
| `ws-t` | WS-T | `benchmarks.json`, `history.json`, `pending.json`, `tryit.json` | a `tryit-` pair, the try's `apply` entry, a staged op, the open try (AC6.11) |
| `ws-w` | WS-W | `awareness.json` | the options snapshot at exit (`optionsAtExit`, 4h) |
| `ws-w2` | WS-W2 | `awareness.json` | `acknowledgedStartupRegressions` (C18) |
| `ws-f` | WS-F | `awareness.json` | `firstrun.guide` in `dismissed` (C02) |
| `ws-h` | WS-H | `history.json`, `last-apply.json` | RW-20's relabel over the user's real instance: the legacy-import entry with the DH pair ABANDONED, and that helper run's two results ABANDONED "installed another way" |

Contracts WS-K landed for these (docs/v0.5/design/ws-k.md): the optional fields `JournalEntry.foldedEntryIds`,
`BenchmarkRecord.Context.worldFresh`/`dhGenerating`/`stagedAtStart`, `StutterReport.settingsAtStart`/`settingsAtEnd`,
`ClientSettings.modFilesByRigTune`, AwarenessStore's `acknowledgedStartupRegressions` and `optionsAtExit`; the shells of
`stutter-fixes.json`, `tryit.json` and `server-profiles.json`.

## Rules for a set (real or placeholder)

- **File names** exactly as in `config/rigtune/`, plus the set's `expect.json` (below). Nothing else in the folder.
- **Paths**: every absolute path (pending.json's `from`/`to`/`path`, `modsDir`, `configDir`, a history change's paths)
  starts with the token `${INSTANCE}` and uses `/`; the tools put the instance folder in (`tools/e2e/fixtures.TOKEN`).
  No user name, no machine path.
- **Times**: every timestamp (`at`, `createdAt`, `setAt`, `lastSeen`, `startedAt`, ...) is in the past, fixed in the file
  (the regeneration writes the test's fixed clock, never "now").
- **Ids** (entry, change, op, group, run, try ids) are fixed UUIDs, unique across all sets of both generations.
- A set's `history.json` entries and the files that point at them (`profiles.json` switches, `stutter-fixes.json`,
  `tryit.json`) refer only to entries in the same set.

## How the tools compose the sets (the downgrade E2E and compat040)

`tools/e2e/written.py` composes one instance's `config/rigtune/`: the `v040-written` sets first, then `v050-written`'s
(a 0.5 instance holds what 0.4 wrote too), each generation's sets in the table's order. Per file:

- **`history.json`**: the `entries` of every set, merged and sorted by `at`; entry ids must be unique across sets; every
  set's `formatVersion` must be the same (a differing one is a format bump, never merged).
- **`pending.json`**: one plan with every set's `ops` (op ids unique across sets); the first set's other fields.
- **`awareness.json`**: the union: `dismissed`, `acknowledgedRegressions`, `acknowledgedStartupRegressions` and every
  other array as the union of the sets' values (exact duplicates once); objects merged key by key.
- **`benchmarks.json`**: `runs` concatenated (run ids unique across sets).
- **`profiles.json`**: one baseline, the latest set's: ProfileStore keeps only one, and a new baseline replaces the
  older. The other `profiles` are merged by `id` (the later set's copy wins); the rest is deep-merged as below.
- **Any other file two sets provide**: deep-merged (objects key by key, lists without exact duplicates, a scalar from the
  later set, each such override reported as a conflict). A differing `formatVersion` or `schemaVersion` stops the run.
- **A file only one set provides** keeps its bytes (the `${INSTANCE}` token filled in).

After a downgrade, the files only 0.5 writes (`stutter-fixes.json`, `tryit.json`, `server-profiles.json`) must be
byte-identical, and what 0.4 must still hold (sessions, runs, servers, dismissals, acknowledgements) is checked by id.

## `expect.json`: what 0.4.0's own classes must do with the set

compat040 (WS-E's `tools/e2e/compat/Compat040.java`) is a data-driven interpreter: it runs each check with the released
0.4.0 jar's own class on that set's files alone (a spare copy for the checks that write), and fails on any mismatch, on a
check kind it doesn't know, or on a check of a file the set doesn't hold. One file per set:

```json
{
  "set": "ws-t",
  "checks": [
    {"class": "Journal", "file": "history.json", "state": "OK", "entries": 2},
    {"class": "HistoryModel", "file": "history.json", "entries": 2, "unknownKinds": 0},
    {"class": "UndoPlanner", "file": "history.json", "undoThis": "3f0c6a8e-0000-4000-8000-000000000001", "problems": 0},
    {"class": "BenchmarkHistory", "file": "benchmarks.json", "runs": 2, "noBad": true},
    {"class": "PendingActions", "file": "pending.json", "ops": 1},
    {"class": "ApplyHelper", "file": "pending.json", "appliesGroup": "g-3f0c6a8e"},
    {"class": "AwarenessStore", "file": "awareness.json", "keeps": ["acknowledgedStartupRegressions"]},
    {"class": "Unread", "file": "tryit.json", "unchanged": true}
  ]
}
```

- `class`: 0.4.0's class that reads the file (Journal, HistoryModel, UndoPlanner, BenchmarkHistory, PendingActions,
  ApplyHelper, ClientSettings, StutterStore, StutterSummary (the Copy summary renders every session), AwarenessStore,
  ProfileStore, ServerLimitsStore, RestoreMarker, StartupTimesStore, ApplyResult (last-apply.json: `results`, `unchanged`
  (0.4.0's restart reconcile moves no journal status), `reasons` (History's failures with a reason))), or
  `Unread` for a file 0.4.0 never opens.
- `file`: the file under `config/rigtune/` it reads.
- Expectations (each optional; at least one per check): `state` (the load state, `OK`), `entries`/`runs`/`ops`/
  `sessions` (counts after loading), `unknownKinds` (HistoryModel rows of a kind 0.4.0 doesn't know), `noBad` (no
  `<file>.bad` appears), `undoThis` (Undo this on that entry id plans without a problem) with `problems` (the plan's
  problem count), `appliesGroup` (0.4.0's helper applies that group at its exit, as it does a held group, AC4d.3),
  `keeps` (top-level fields a 0.4.0 rewrite of the file keeps; for ClientSettings, a 0.4.0 save keeps them with their values), `dismissedContains` (AwarenessStore: these keys are still dismissed, in 0.4.0's view and in the file, after 0.4.0 dismisses 10 more on a copy), `unchanged` (the file is byte-identical after 0.4.0 ran).
- A new check kind is added to the interpreter by WS-E first (through the coordinator after WS-E has merged).

## One regeneration switch

The tests that write a set compare by default: they build the files and assert they equal the committed ones. With
`RIGTUNE_REGENERATE_FIXTURES=1` in the environment, they write into the set instead (the same folder, the same names).
The coordinator runs the switch once in Phase 5 on the release candidate, then compat040/compat030 again; a workstream
runs it only for its own set, and commits the result together with the change that caused it.
