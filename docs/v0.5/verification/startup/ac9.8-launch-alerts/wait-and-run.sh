#!/bin/bash
# Retries launches.sh every 2 minutes while the game-test lock is held (at most 45 minutes), per PLAN "Local runs".
SP="C:/Users/Admin/AppData/Local/Temp/claude/C--Dev-Minecraft-Setting-Optimisation-Mod/590d2d3e-58b4-418a-a809-0e625214088f/scratchpad/ws-w2"
for try in $(seq 1 23); do
  bash "$SP/ac98/launches.sh" "$@"; rc=$?
  [ $rc -ne 75 ] && exit $rc
  echo "$(date -Is) lock held; retry $try in 2 min"; sleep 120
done
echo "gave up after 45 minutes"; exit 75
