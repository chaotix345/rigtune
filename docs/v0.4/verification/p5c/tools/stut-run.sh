#!/bin/bash
# usage: stut-run.sh <label> <uncapped:0|1> [timeout s]
# AC5.8 C re-run on the RC: a FRESH instance (RC jar, fabric-api, Sodium 0.9.2; so 200000,200,200000 is never-generated
# terrain), the product's own -Drigtune.dev.stutterScript=teleport, -Xlog:gc for the GC-overlap check. ONE client per lock
# hold, released in this same command (trap), pass or fail. Exit 3 = lock held (nothing ran).
S=C:/Users/Admin/AppData/Local/Temp/claude/C--Dev-Minecraft-Setting-Optimisation-Mod/32b9ff53-5b6c-44d7-bc00-195e4f1a3166/scratchpad/p5c
P5A=C:/Users/Admin/AppData/Local/Temp/claude/C--Dev-Minecraft-Setting-Optimisation-Mod/32b9ff53-5b6c-44d7-bc00-195e4f1a3166/scratchpad/p5a
WT=C:/Dev/Worktrees/rigtune-p5c
LOCK=C:/Dev/Worktrees/.gametest-lock
label=$1; uncapped=$2; to=${3:-600}
export JAVA_HOME="C:/Dev/Tools/jdk/jdk-25.0.4.1+1"
I=$S/inst/p5c-$label
if [ -e "$I" ]; then echo "instance $I exists; use a new label (the terrain must be ungenerated)"; exit 2; fi
mkdir -p $I/mods $S/logs
cp $S/jars/rc/rigtune-0.4.0-dev+mc26.2.jar $P5A/jars/fabric-api-0.161.0+26.2.jar $P5A/dl/mods262/sodium-fabric-0.9.2+mc26.2.jar $I/mods/ || exit 2
printf "onboardAccessibility:false\npauseOnLostFocus:false\ntutorialStep:none\nskipMultiplayerWarning:true\njoinedFirstServer:true\nsoundCategory_master:0.0\nrenderDistance:12\nsimulationDistance:8\nfullscreen:false\n" > $I/options.txt
if [ "$uncapped" = "1" ]; then printf 'enableVsync:false\nmaxFps:260\ninactivityFpsLimit:"minimized"\n' >> $I/options.txt; fi
printf -- "-Xmx4G\n-Drigtune.dev.stutterScript=teleport\n-Xlog:gc,safepoint:file=%s/logs/%s-gc.log:time,uptime,level,tags\n" "$S" "$label" > $S/logs/$label.args
if ! mkdir $LOCK 2>/dev/null; then echo "LOCK HELD: $(tr '\n' ' ' < $LOCK/owner.txt 2>/dev/null)"; rm -rf "$I"; exit 3; fi
printf "agent: p5-c\nworktree: %s\nstarted: %s\nrun: %s\n" "$WT" "$(date -Is)" "$label" > $LOCK/owner.txt
release() { grep -qx "run: $label" $LOCK/owner.txt 2>/dev/null && grep -qx "worktree: $WT" $LOCK/owner.txt && { rm -f $LOCK/owner.txt; rmdir $LOCK; }; }
trap release EXIT
name=$(basename "$I")
count() { pwsh -NoProfile -Command "@(Get-CimInstance Win32_Process -Filter \"Name='java.exe' or Name='javaw.exe'\" | Where-Object { \$_.CommandLine -like '*$name*' }).Count"; }
killmine() { pwsh -NoProfile -Command "Get-CimInstance Win32_Process -Filter \"Name='java.exe' or Name='javaw.exe'\" | Where-Object { \$_.CommandLine -like '*$name*' } | ForEach-Object { Write-Output ('killing ' + \$_.ProcessId); Stop-Process -Id \$_.ProcessId -Force }"; }
echo "$(date -Iseconds) stutter $label start (uncapped=$uncapped)" >> $S/progress.log
cd $WT
timeout $to ./gradlew :26.2:e2eClient -Pe2e.instance="$I" -Pe2e.driver=undo -Pe2e.jvmArgsFile="$S/logs/$label.args" --console=plain > $S/logs/$label.gradle.log 2>&1
rc=$?
if [ $rc -eq 124 ]; then echo "timeout; killing this run's client" >> $S/logs/$label.gradle.log; killmine >> $S/logs/$label.gradle.log 2>&1; fi
for i in $(seq 1 30); do [ "$(count)" = "0" ] && break; sleep 2; done
cp "$I/logs/latest.log" "$S/logs/$label.latest.log" 2>/dev/null
echo "$(date -Iseconds) stutter $label rc=$rc" >> $S/progress.log
exit $rc
