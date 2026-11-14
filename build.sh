#!/bin/sh
# Builds jars/WingmanPvP.jar. Usage: ./build.sh /path/to/starsector-core /path/to/Wingman.jar
set -e
CORE="$1"; WINGMAN="$2"
cd "$(dirname "$0")"
rm -rf build/classes && mkdir -p build/classes jars
javac -nowarn -encoding UTF-8 --release 8 -cp "$WINGMAN:$CORE/*" -d build/classes $(find src -name '*.java')
jar cf jars/WingmanPvP.jar -C build/classes wingmanpvp
# Classes the game's script sandbox would refuse to load (java.lang.reflect, file access): fail now, not in game.
if command -v python3 >/dev/null 2>&1; then python3 test/sandbox_check.py jars/WingmanPvP.jar || exit 1; fi
echo "Built jars/WingmanPvP.jar"
