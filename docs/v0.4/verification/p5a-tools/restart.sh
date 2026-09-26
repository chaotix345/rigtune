#!/bin/bash
# usage: restart.sh <view-distance>: stops the scratch server cleanly over RCON, sets view-distance, starts it again.
S=C:/Users/Admin/AppData/Local/Temp/claude/C--Dev-Minecraft-Setting-Optimisation-Mod/32b9ff53-5b6c-44d7-bc00-195e4f1a3166/scratchpad/p5a
python $S/server/rcon.py stop
for i in $(seq 1 30); do netstat -ano | grep -q ":25577 .*LISTEN" || break; sleep 2; done
cp $S/server/run/console.log $S/server/console-$(date +%H%M%S).log
sed -i "s/^view-distance=.*/view-distance=$1/" $S/server/run/server.properties
$S/server/start.sh
for i in $(seq 1 60); do grep -q "Done (" $S/server/run/console.log 2>/dev/null && break; sleep 2; done
grep "Done (" $S/server/run/console.log; grep "^view-distance" $S/server/run/server.properties
