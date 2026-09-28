# PLACEHOLDER "written by 0.5" fixtures (WS-E)

Hand-written by WS-E so compat040, compat030 and the downgrade E2E run before the features land. They follow the
shapes of docs/v0.5/SPEC.md (the compatibility table, C1) and WS-K's contracts. **They are not output of RigTune 0.5.**
Each workstream commits its real set from its own tests as `../<set>/`; the tools then use it instead of
`placeholder/<set>/`. The placeholder folder is deleted in that same commit (../README.md, "Sets").

Where a file's format is its owner's and not yet defined (`stutter-fixes.json`, `tryit.json`, `server-profiles.json`),
the placeholder is a shape stand-in marked `"placeholder"`. 0.4.0 never opens those files: compat040 checks they are
unchanged, and the downgrade E2E checks they are byte-identical.

| set | files | what it stands in for |
|---|---|---|
| `ws-s2` | history.json, pending.json, stutter-fixes.json | an applied stutter fix (`vanilla.renderDistance`) and a staged one (a Sodium PATCH_JSON op) |
| `ws-t` | benchmarks.json, history.json, pending.json, tryit.json | a `tryit-` pair, the try's staged Apply and its PATCH_JSON op, the open try |

A few details:
- Every entry is newer than the v0.4 sets'. The newest (ws-s2's staged fix) is the one a downgraded 0.4.0's Undo last picks.
- Two keys the v0.4 ws-p switch set are changed again here: `vanilla.renderDistance` and Sodium's chunk builder threads. So compat030 also sees the planner skip changes that a later Apply changed again.
- Ids are fixed UUIDs, unique across both generations.
