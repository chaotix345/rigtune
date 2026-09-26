#!/bin/bash
# Starts the scratch 26.2 vanilla server (port 25577) in the background; log in run/console.log.
D=C:/Users/Admin/AppData/Local/Temp/claude/C--Dev-Minecraft-Setting-Optimisation-Mod/32b9ff53-5b6c-44d7-bc00-195e4f1a3166/scratchpad/p5a/server/run
cd $D && nohup C:/Dev/Tools/jdk/jdk-25.0.4.1+1/bin/java -Xmx2G -jar ../server-26.2.jar nogui > console.log 2>&1 < /dev/null &
echo started
