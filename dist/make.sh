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
cp "$MOD/jars/Wingman.jar" "$STAGE/jars/"
cp -r "$MOD/data" "$STAGE/"
rm -f "$OUT"
(cd "$(dirname "$STAGE")" && zip -qr "$OUT" Wingman)
rm -rf "$(dirname "$STAGE")"
echo "$OUT"
