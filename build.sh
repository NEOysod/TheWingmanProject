#!/bin/sh
# Rebuilds jars/Wingman.jar against your copy of the game. Usage: ./build.sh /path/to/starsector-core
CORE="${1:?usage: ./build.sh /path/to/starsector-core}"
rm -rf build && mkdir -p build/stub build/tools build/classes build/agent
# Stand-ins for a few game classes whose names Java source cannot spell (see tools/PatchPackage.java).
javac -nowarn --release 8 -cp "$CORE/*" -d build/stub $(find tools/stub -name '*.java') || exit 1
javac -nowarn -d build/tools tools/PatchPackage.java tools/GameNames.java || exit 1
# The battle's message feed and its line class, as this game build names them (MessageSpy extends the feed).
NAMES=$(java -noverify -cp build/tools GameNames "$CORE" 2>/dev/null) || { echo "can't read the feed's names from $CORE"; exit 1; }
FEED=$(echo "$NAMES" | sed -n 1p); LINE=$(echo "$NAMES" | sed -n 2p)
# The optional agent (jars/WingmanAgent.jar, see docs/TECHNICAL.md section 9): its own jar, started by the game's launch settings.
javac -nowarn -encoding UTF-8 --release 8 -cp "$CORE/*" -d build/agent $(find agent/src -name '*.java') || exit 1
jar cfm jars/WingmanAgent.jar agent/MANIFEST.MF -C build/agent wingman || exit 1
javac -nowarn -encoding UTF-8 --release 8 -cp "build/agent:build/stub:$CORE/*" -d build/classes $(find src -name '*.java') || exit 1
java -cp build/tools PatchPackage build/classes/wingman/MessageSpy.class com/fs/starfarer/klass/Object/O0OO "$LINE" || exit 1
java -cp build/tools PatchPackage build/classes/wingman/MessageSpy.class com/fs/starfarer/klass/C "$FEED" || exit 1
jar cf jars/Wingman.jar -C build/classes wingman && # Classes the game's script sandbox would refuse to load (java.lang.reflect, file access): fail now, not in game.
if command -v python3 >/dev/null 2>&1; then python3 test/sandbox_check.py jars/Wingman.jar || exit 1; fi
echo "Built jars/Wingman.jar and jars/WingmanAgent.jar"
