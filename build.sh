#!/bin/sh
# Builds jars/WingmanPvP.jar. Usage: ./build.sh /path/to/starsector-core /path/to/Wingman.jar
set -e
CORE="$1"; WINGMAN="$2"
cd "$(dirname "$0")"
rm -rf build/classes && mkdir -p build/classes jars
javac -nowarn -encoding UTF-8 --release 8 -cp "$WINGMAN:$CORE/*" -d build/classes $(find src -name '*.java')
jar cf jars/WingmanPvP.jar -C build/classes wingmanpvp
echo "Built jars/WingmanPvP.jar"
