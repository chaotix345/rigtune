#!/bin/bash
# usage: gt.sh <label> <gradle args...>: one Gradle game-test run under the lock, released in this same command.
S=C:/Users/Admin/AppData/Local/Temp/claude/C--Dev-Minecraft-Setting-Optimisation-Mod/32b9ff53-5b6c-44d7-bc00-195e4f1a3166/scratchpad/p5a
WT=C:/Dev/Worktrees/rigtune-p5a
LOCK=C:/Dev/Worktrees/.gametest-lock
label=$1; shift
export JAVA_HOME="C:/Dev/Tools/jdk/jdk-25.0.4.1+1"
if ! mkdir $LOCK 2>/dev/null; then echo "LOCK HELD"; exit 3; fi
printf "agent: p5-a\nworktree: %s\nstarted: %s\nrun: %s\n" "$WT" "$(date -Is)" "$label" > $LOCK/owner.txt
release() { grep -q "run: $label" $LOCK/owner.txt 2>/dev/null && { rm -f $LOCK/owner.txt; rmdir $LOCK; }; }
trap release EXIT
echo "$(date -Iseconds) gt $label start: $*" >> $S/progress.log
cd $WT
timeout 1800 ./gradlew "$@" --console=plain > $S/logs/$label.gradle.log 2>&1
rc=$?
echo "$(date -Iseconds) gt $label rc=$rc" >> $S/progress.log
exit $rc
