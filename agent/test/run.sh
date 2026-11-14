#!/bin/sh
# Runs the agent's own test (AgentRun) under the real agent. Usage: agent/test/run.sh /path/to/starsector-core
# Build first (./build.sh): it needs jars/WingmanAgent.jar.
set -e
CORE="${1:?usage: agent/test/run.sh /path/to/starsector-core}"
cd "$(dirname "$0")/../.."
FIX=build/agentfix; RUN=build/agentrun   # (AgentRun's "isolated loader" check reads build/agentfix and build/apiimp)
rm -rf $FIX $RUN build/apiimp; mkdir -p $FIX $RUN build/apiimp
(cd build/apiimp && unzip -oq "$CORE/starfarer.api.jar" && unzip -oq "$CORE/lwjgl_util.jar")
CP="$CORE/starfarer.api.jar:$CORE/lwjgl_util.jar:$CORE/json.jar:$CORE/log4j-1.2.9.jar"
javac -nowarn -encoding UTF-8 -cp "$CP:jars/WingmanAgent.jar" -d $FIX $(find agent/test/fixtures -name '*.java')
javac -nowarn -encoding UTF-8 -cp "$FIX:$CP:jars/WingmanAgent.jar" -d $RUN agent/test/AgentRun.java
# the stand-ins for the game's Ship and CombatEngine implement only part of their interfaces
python3 agent/test/unabstract.py $FIX/com/fs/starfarer/combat/entities/Ship.class
python3 agent/test/unabstract.py $FIX/com/fs/starfarer/combat/CombatEngine.class
java -javaagent:jars/WingmanAgent.jar -noverify -cp "$RUN:$FIX:$CP" AgentRun
