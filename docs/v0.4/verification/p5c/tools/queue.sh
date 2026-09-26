#!/bin/bash
# usage: queue.sh <job file>; one line per job: "<script> <args...>" (e2e-one.sh / stut-run.sh, relative to scratch).
# Each job takes and releases the lock itself (one client per hold). Lock held by someone else (exit 3) -> retry every
# 120 s, give up after 45 min. 20 s pause between jobs.
S=C:/Users/Admin/AppData/Local/Temp/claude/C--Dev-Minecraft-Setting-Optimisation-Mod/32b9ff53-5b6c-44d7-bc00-195e4f1a3166/scratchpad/p5c
while IFS= read -r line <&3; do
  [ -z "$line" ] && continue
  case "$line" in \#*) continue ;; esac
  waited=0
  while true; do
    eval "bash $S/$line" < /dev/null; rc=$?
    [ $rc -ne 3 ] && break
    [ $waited -ge 2700 ] && { echo "$(date -Iseconds) queue: gave up waiting for the lock ($line)" >> $S/progress.log; break; }
    echo "$(date -Iseconds) queue: lock held, retry in 120 s ($line)" >> $S/progress.log
    sleep 120; waited=$((waited+120))
  done
  echo "$line => rc=$rc" >> $S/logs/queue-results.log
  sleep 20
done 3< "$1"
echo "$(date -Iseconds) queue $1 finished" >> $S/progress.log
