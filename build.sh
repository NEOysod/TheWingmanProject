#!/bin/sh
# Rebuilds jars/Wingman.jar against your copy of the game. Usage: ./build.sh /path/to/starsector-core
CORE="${1:?usage: ./build.sh /path/to/starsector-core}"
rm -rf build && mkdir -p build/stub build/tools build/classes
# Stand-ins for a few game classes whose names Java source cannot spell (see tools/PatchNew.java, tools/PatchPackage.java).
javac -nowarn --release 8 -cp "$CORE/*" -d build/stub $(find tools/stub -name '*.java') || exit 1
javac -nowarn -d build/tools tools/PatchNew.java tools/PatchPackage.java || exit 1
javac -nowarn -encoding UTF-8 --release 8 -cp "build/stub:$CORE/*" -d build/classes $(find src -name '*.java') || exit 1
java -cp build/tools PatchNew build/classes/wingman/TitleLauncher.class coopNew new || exit 1
java -cp build/tools PatchPackage build/classes/wingman/MessageSpy.class com/fs/starfarer/klass/ com/fs/starfarer/class/ || exit 1
jar cf jars/Wingman.jar -C build/classes wingman && echo "Built jars/Wingman.jar"
