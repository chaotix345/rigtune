# PLACEHOLDER "written by 0.4" fixtures (WS-H)

Hand-written by WS-H in the shapes of docs/v0.4/SPEC.md "Shared contracts" C1 and the compatibility promise, so the
released-jar compatibility harness (`tools/e2e/compat030.py`, AC3.3) and the `downgrade-040-to-030` run (AC3.2) work
before the features land (plan review H-M1). **They are not output of RigTune 0.4.** Each feature workstream commits
its real set, written by its own tests, as `src/test/resources/v040-written/<set>/` (same file names as in
`config/rigtune/`); the tools then use it instead of `placeholder/<set>/`, and report which sets were placeholders.
Phase 5 regenerates the real sets from the release candidate.

| set | owner | files | what they carry |
|---|---|---|---|
| `ws-a` | WS-A | `pending.json`, `history.json` | an ENABLE_FILE op with `projectId`; a staged Apply whose change has `modName` (and an applied disable with `modName`) |
| `ws-p` | WS-P | `profiles.json`, `history.json` | a profile switch: an `apply` entry of setting changes, labelled in `profiles.json` `switches` by its entry id |
| `ws-b` | WS-B | `benchmarks.json` | a run with `context.modSetHash` and `context.journalCursor` |
| `ws-s` | WS-S | `stutter.json`, `settings.json` | a stutter session; `settings.json` with `stutterMonitor` |
| `ws-w` | WS-W | `awareness.json`, `server-limits.json` | the change-awareness state; a server's limits |
| `ws-f` | WS-F | `startup-times.json` | startup runs (item 13) |

Rules for a set (real or placeholder):
- File names as in `config/rigtune/`. Only `history.json` may come from more than one set: the tools merge the
  `entries` of every set's `history.json` by `at` (entry ids must be unique across sets).
- Absolute paths (pending.json's `from`/`to`/`path`, `modsDir`, `configDir`) start with `${INSTANCE}` and use `/`,
  as in tools/e2e/seeds (`fixtures.TOKEN`); the tools put the instance folder in.
- The downgrade run makes the instance match the journal: a test mod jar for every pending `ENABLE_FILE` `from`
  (`modId` from the op), the jar (or its `resultFile`) for every applied file change, and `options.txt` holding the
  latest applied value of every `vanilla.*` setting change.
- Entry and change ids are fixed UUIDs, `at` times are in the past; `profiles.json` switches point at entries in the
  set's own `history.json`.
