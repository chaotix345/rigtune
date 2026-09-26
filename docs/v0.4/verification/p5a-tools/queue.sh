#!/bin/bash
# usage: queue.sh <job file>. Lines: "client <mc> <instance name> <label> <args file> [timeout]" | "sh <command...>" | "gt <label> <gradle args...>"
# Each client/gt job takes the lock itself and releases it when it ends; lock held by someone else -> retry every 120 s (max 45 min).
S=C:/Users/Admin/AppData/Local/Temp/claude/C--Dev-Minecraft-Setting-Optimisation-Mod/32b9ff53-5b6c-44d7-bc00-195e4f1a3166/scratchpad/p5a
jobs=$1
while IFS= read -r line <&3; do
  [ -z "$line" ] && continue
  case "$line" in \#*) continue ;; esac
  set -- $line
  kind=$1; shift
  if [ "$kind" = "sh" ]; then echo "$(date -Iseconds) queue sh: $*" >> $S/progress.log; bash -c "$*" >> $S/logs/queue-sh.log 2>&1 < /dev/null; continue; fi
  waited=0
  while true; do
    if [ "$kind" = "clientvk" ]; then GRADLE_EXTRA="-Pp5aProgramArgs=--graphicsBackend,vulkan" $S/run.sh "$1" "$S/inst/p5a-inst-$2" "$3" "$4" "${5:-900}" < /dev/null; rc=$?
    elif [ "$kind" = "client" ]; then $S/run.sh "$1" "$S/inst/p5a-inst-$2" "$3" "$4" "${5:-900}" < /dev/null; rc=$?
    elif [ "$kind" = "gtjvm" ]; then $S/gt.sh "$1" :26.2:runProductionClientGameTest "-PgametestJvmArgs=-XX:+UseZGC -XX:+ZGenerational -Dusing.aikars.flags=x" < /dev/null; rc=$?
    else $S/gt.sh "$@" < /dev/null; rc=$?; fi
    [ $rc -ne 3 ] && break
    [ $waited -ge 2700 ] && { echo "$(date -Iseconds) queue: gave up waiting for the lock ($line)" >> $S/progress.log; exit 3; }
    echo "$(date -Iseconds) queue: lock held, retry in 30 s ($line)" >> $S/progress.log
    sleep 30; waited=$((waited+30))
  done
  echo "$(date -Iseconds) queue job done rc=$rc: $line" >> $S/progress.log
  echo "$line => rc=$rc" >> $S/logs/queue-results.log
  sleep 60
done 3< "$jobs"
echo "$(date -Iseconds) queue $jobs finished" >> $S/progress.log
