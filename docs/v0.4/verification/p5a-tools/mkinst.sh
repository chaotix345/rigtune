#!/bin/bash
# usage: mkinst.sh <name> <mc> <rd> [extra mods: sodium iris dh]
S=C:/Users/Admin/AppData/Local/Temp/claude/C--Dev-Minecraft-Setting-Optimisation-Mod/32b9ff53-5b6c-44d7-bc00-195e4f1a3166/scratchpad/p5a
name=$1; mc=$2; rd=$3; shift 3
I=$S/inst/p5a-inst-$name
mkdir -p $I/mods
cp $S/jars/rigtune-0.4.0-dev+mc$mc.jar $S/jars/fabric-api-0.161.0+$mc.jar $I/mods/
if [ "$mc" = "26.2" ]; then cp $S/jars/p5a-driver.jar $I/mods/; else cp $S/jars/p5a-driver-263.jar $I/mods/; fi
for m in "$@"; do
  case $m in
    sodium) cp $S/dl/mods262/sodium-fabric-0.9.2+mc26.2.jar $I/mods/ ;;
    iris) cp $S/dl/mods262/iris-fabric-1.11.4+mc26.2.jar $I/mods/ ;;
    dh) cp $S/dl/mods262/DistantHorizons-3.3.2-26.2-fabric-neoforge.jar $I/mods/ ;;
  esac
done
printf "onboardAccessibility:false\npauseOnLostFocus:false\ntutorialStep:none\nskipMultiplayerWarning:true\njoinedFirstServer:true\nsoundCategory_master:0.0\nrenderDistance:%s\nsimulationDistance:8\nfullscreen:false\n" "$rd" > $I/options.txt
echo $I
