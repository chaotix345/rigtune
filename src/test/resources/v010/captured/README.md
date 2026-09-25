# Files written by the released RigTune 0.1.0 (captured, not hand-written)

Captured by the self-update end-to-end run (`tools/e2e/self_update_e2e.py --capture-fixtures`, evidence in
`docs/smoke/self-update/v010-to-dev/`): the unmodified v0.1.0 jar (sha256 `8294d04a…`) in a production 26.2 client
set the goal to QUALITY, applied its own "Update RigTune" recommendation, and quit; the post-exit helper then applied
the update. `manifest.json` records the jars, the run and each file's sha256.

| file | written by 0.1.0 | when |
|---|---|---|
| `pending.json` | the game (`RealController.stage`) | copied just before quitting, because the helper deletes it: the update's group {DISABLE_FILE `rigtune-0.1.0.jar`, ENABLE_FILE of the `.rigtune-pending` download with `modId: rigtune`} |
| `last-apply.json` | the helper (`ApplyExecutor`) | after the helper applied both ops (`OK`) |
| `rigtune.json` | the game (`ClientState.save`) | after `setGoal(QUALITY)`; `lastShownApply` is absent (null) because 0.1.0 never showed a result |
| `rules-cache.json` | the game (`RemoteRulesFetcher`) | the remote rules as served (the repo's `rules/rules-v1.json`, revision 4) |
| `helper.log` | the helper | its whole output for that run |

The only edit: absolute paths of the scratch instance are replaced by `${INSTANCE}` (the instance root, the game
directory that holds `mods/` and `config/`), and the rest of each such path uses `/` instead of the Windows `\` it was
written with, so tests can run on Linux CI (Java on Windows accepts `/` too). Substitute your test's folder for the
token before parsing, JSON-escaped in the JSON files (on Windows each backslash doubled). Without the substitution the
0.2 helper refuses the ops as outside its folders (`ApplyExecutor.containmentProblem`) and the game drops them
(`PendingActions.relocated`). The untemplated originals are in the run's scratch folder; the E2E's second launch is
the proof that 0.2 reads the real files.
