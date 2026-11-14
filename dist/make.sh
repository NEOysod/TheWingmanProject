#!/bin/bash
# Builds the player download: only what the game needs, plus README, release notes and licence.
# Run from anywhere after build.sh has made jars/Wingman.jar. Output: Wingman-<version>.zip next to the mod folder.
set -e
MOD="$(cd "$(dirname "$0")/.." && pwd)"
VER=$(grep -o '"version": *"[^"]*"' "$MOD/mod_info.json" | sed 's/.*"\([^"]*\)"$/\1/')
OUT="$(dirname "$MOD")/Wingman-$VER.zip"
STAGE=$(mktemp -d)/Wingman
mkdir -p "$STAGE/jars"
cp "$MOD/mod_info.json" "$MOD/wingman_settings.json" "$MOD/RELEASE_NOTES.md" "$MOD/LICENSE" "$MOD/NOTICE" "$STAGE/"
cp "$MOD/dist/README.md" "$STAGE/README.md"
cp "$MOD/jars/Wingman.jar" "$MOD/jars/WingmanAgent.jar" "$STAGE/jars/"
cp -r "$MOD/data" "$STAGE/"
# the agent's launcher for Windows (optional; see the README)
mkdir -p "$STAGE/launch"
cp "$MOD/launch/WingmanLaunch.ps1" "$STAGE/launch/"
# the .bat goes in the zip next to the Wingman folder: it belongs in the Starsector folder, not in mods
cp "$MOD/launch/Start Starsector with Wingman agent.bat" "$MOD/launch/Put .bat in Starsector put Wingman in mods.txt" "$(dirname "$STAGE")/"
rm -f "$OUT"
(cd "$(dirname "$STAGE")" && zip -qr "$OUT" Wingman "Start Starsector with Wingman agent.bat" "Put .bat in Starsector put Wingman in mods.txt")
rm -rf "$(dirname "$STAGE")"
echo "$OUT"
