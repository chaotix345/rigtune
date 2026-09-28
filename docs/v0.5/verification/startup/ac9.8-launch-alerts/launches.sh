#!/bin/bash
# AC9.8 (docs/v0.5/SPEC.md 9): real launches of a production client on the worktree's dev run dir (run/, a copy; never the
# player's instance), one runProductionSmoke per argument (base|batch), each launch's latest.log, screenshots and
# startup-times.json copied out. Takes the game-test lock and releases it in the same script. Usage: launches.sh <tag> base base ... batch
SP="C:/Users/Admin/AppData/Local/Temp/claude/C--Dev-Minecraft-Setting-Optimisation-Mod/590d2d3e-58b4-418a-a809-0e625214088f/scratchpad/ws-w2"
WT="C:/Dev/Worktrees/rigtune-launchalert"
LOCK="C:/Dev/Worktrees/.gametest-lock"
tag=$1; shift
out="$SP/ac98/$tag"; mkdir -p "$out"
mkdir "$LOCK" 2>/dev/null || { echo "game-test lock held: $(cat $LOCK/owner.txt 2>/dev/null)"; exit 75; }
printf 'agent: ws-w2\nworktree: %s\nstarted: %s\nexpected_minutes: %s\n' "$WT" "$(date -Is)" "$(( $# * 5 + 10 ))" > "$LOCK/owner.txt"
export JAVA_HOME="C:/Dev/Tools/jdk/jdk-25.0.4.1+1"
cd "$WT" || { rm -f "$LOCK/owner.txt"; rmdir "$LOCK"; exit 1; }
i=0; rc=0
for mods in "$@"; do
  i=$((i+1))
  echo "$(date -Is) launch $i ($mods) starting" | tee -a "$out/launches.txt"
  timeout 1200 ./gradlew -Dorg.gradle.daemon.idletimeout=600000 :26.2:runProductionSmoke "-PextraModsDir=$SP/ac98/mods-$mods" > "$out/gradle-$i.log" 2>&1; r=$?
  echo "$(date -Is) launch $i ($mods) exit $r" | tee -a "$out/launches.txt"
  cp versions/26.2/run/logs/latest.log "$out/latest-$i.log" 2>/dev/null || cp run/logs/latest.log "$out/latest-$i.log" 2>/dev/null
  for d in versions/26.2/run run; do
    [ -f "$d/config/rigtune/startup-times.json" ] && cp "$d/config/rigtune/startup-times.json" "$out/startup-times-$i.json"
    [ -d "$d/screenshots" ] && { mkdir -p "$out/shots-$i"; cp "$d/screenshots/"*.png "$out/shots-$i/" 2>/dev/null; rm -f "$d/screenshots/"*.png; }
  done
  grep -h "Launch to title screen" "$out/latest-$i.log" | tee -a "$out/launches.txt"
  [ $r -ne 0 ] && rc=$r
done
rm -f "$LOCK/owner.txt"; rmdir "$LOCK"
echo "$(date -Is) done rc=$rc" | tee -a "$out/launches.txt"
exit $rc
