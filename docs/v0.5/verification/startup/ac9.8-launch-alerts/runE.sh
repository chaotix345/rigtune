#!/bin/bash
# AC9.8 run E, with the final code (StartupTrend with RW-19 and the streak rule): a fresh launch history in the worktree's dev
# run dir (the earlier one moved to <scratch>/ws-w2/ac98/before-runE/), 8 unchanged launches, then the heavier batch, then
# the Got it that the notice's button writes (awareness.json's acknowledgedStartupRegressions, the key of that launch) and
# 2 more launches with the batch. Each part takes the game-test lock and releases it (wait-and-run.sh).
SP="C:/Users/Admin/AppData/Local/Temp/claude/C--Dev-Minecraft-Setting-Optimisation-Mod/590d2d3e-58b4-418a-a809-0e625214088f/scratchpad/ws-w2"
RUN="C:/Dev/Worktrees/rigtune-launchalert/versions/26.2/run/config/rigtune"
bash "$SP/ac98/wait-and-run.sh" runE1 base base base base base base base base heavy || exit $?
python3 - "$RUN" <<'EOF'
import json, sys, pathlib
d = pathlib.Path(sys.argv[1])
latest = json.loads((d / "startup-times.json").read_text(encoding="utf-8"))["runs"][-1]
key = "startup.regression." + latest["at"]
a = json.loads((d / "awareness.json").read_text(encoding="utf-8"))
a.setdefault("acknowledgedStartupRegressions", []).append(key)
(d / "awareness.json").write_text(json.dumps(a, indent=2), encoding="utf-8", newline="\n")
print("acknowledged (as Got it writes it):", key)
EOF
bash "$SP/ac98/wait-and-run.sh" runE2 heavy heavy
