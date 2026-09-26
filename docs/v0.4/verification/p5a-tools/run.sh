#!/bin/bash
# usage: run.sh <mc> <instance dir> <label> <jvm args file> [timeout s]
# Takes the machine-wide game-test lock for ONE client launch and releases it in this same command (trap), pass or fail.
S=C:/Users/Admin/AppData/Local/Temp/claude/C--Dev-Minecraft-Setting-Optimisation-Mod/32b9ff53-5b6c-44d7-bc00-195e4f1a3166/scratchpad/p5a
WT=C:/Dev/Worktrees/rigtune-p5a
LOCK=C:/Dev/Worktrees/.gametest-lock
mc=$1; inst=$2; label=$3; args=$4; to=${5:-900}
export JAVA_HOME="C:/Dev/Tools/jdk/jdk-25.0.4.1+1"
if ! mkdir $LOCK 2>/dev/null; then echo "LOCK HELD: $(tr '\n' ' ' < $LOCK/owner.txt 2>/dev/null)"; exit 3; fi
printf "agent: p5-a\nworktree: %s\nstarted: %s\nrun: %s\n" "$WT" "$(date -Is)" "$label" > $LOCK/owner.txt
release() { grep -q "run: $label" $LOCK/owner.txt 2>/dev/null && { rm -f $LOCK/owner.txt; rmdir $LOCK; }; }
trap release EXIT
name=$(basename "$inst")
killmine() {
  pwsh -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe' or Name='javaw.exe'\" | Where-Object { \$_.CommandLine -like '*$name*' } | ForEach-Object { Write-Output ('killing ' + \$_.ProcessId); Stop-Process -Id \$_.ProcessId -Force }"
}
echo "$(date -Iseconds) run $label start ($mc, $name, $(tr '\n' ' ' < $args))" >> $S/progress.log
mv -f "$inst/logs/latest.log" "$inst/logs/prev.log" 2>/dev/null
cd $WT
timeout $to ./gradlew -I $S/p5a-init.gradle $GRADLE_EXTRA :$mc:e2eClient -Pe2e.instance="$inst" -Pe2e.driver=undo -Pe2e.jvmArgsFile="$args" --console=plain > $S/logs/$label.gradle.log 2>&1
rc=$?
if [ $rc -eq 124 ]; then echo "timeout; killing this run's client" >> $S/logs/$label.gradle.log; killmine >> $S/logs/$label.gradle.log 2>&1; fi
for i in $(seq 1 30); do
  n=$(pwsh -NoProfile -Command "@(Get-CimInstance Win32_Process -Filter \"Name='java.exe' or Name='javaw.exe'\" | Where-Object { \$_.CommandLine -like '*$name*' }).Count")
  [ "$n" = "0" ] && break; sleep 2
done
cp "$inst/logs/latest.log" "$S/logs/$label.latest.log" 2>/dev/null
echo "$(date -Iseconds) run $label rc=$rc" >> $S/progress.log
exit $rc
