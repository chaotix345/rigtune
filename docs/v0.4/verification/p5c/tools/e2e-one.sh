#!/bin/bash
# usage: e2e-one.sh <name> <harness args...>
# ONE harness run per lock hold (PLAN Global Constraints): mkdir the lock, owner.txt, run with --lock none, release in this
# same command pass or fail (only if owner.txt still names this worktree + run). Exit 3 = lock held (nothing ran).
S=C:/Users/Admin/AppData/Local/Temp/claude/C--Dev-Minecraft-Setting-Optimisation-Mod/32b9ff53-5b6c-44d7-bc00-195e4f1a3166/scratchpad/p5c
WT=C:/Dev/Worktrees/rigtune-p5c
LOCK=C:/Dev/Worktrees/.gametest-lock
E=docs/smoke/self-update
name=$1; shift
export JAVA_HOME="C:/Dev/Tools/jdk/jdk-25.0.4.1+1"
export PATH="$JAVA_HOME/bin:$PATH"
if ! mkdir $LOCK 2>/dev/null; then echo "LOCK HELD: $(tr '\n' ' ' < $LOCK/owner.txt 2>/dev/null)"; exit 3; fi
printf "agent: p5-c\nworktree: %s\nstarted: %s\nrun: %s\n" "$WT" "$(date -Is)" "$name" > $LOCK/owner.txt
release() { grep -qx "run: $name" $LOCK/owner.txt 2>/dev/null && grep -qx "worktree: $WT" $LOCK/owner.txt && { rm -f $LOCK/owner.txt; rmdir $LOCK; }; }
trap release EXIT
echo "$(date -Iseconds) e2e $name start" >> $S/progress.log
cd $WT
mkdir -p $S/work-$name
python tools/e2e/self_update_e2e.py --lock none --agent p5-c --work $S/work-$name --evidence ${EVID:-$E/$name} --name "$name" "$@" > $S/logs/e2e-$name.log 2>&1
rc=$?
echo "$(date -Iseconds) e2e $name rc=$rc" >> $S/progress.log
exit $rc
