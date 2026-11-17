#!/usr/bin/env bash
# Copies the built APKs into output/ with stable names and writes SHA-256 checksums.
# Run after: ./gradlew assembleDebug assembleRelease
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p output
cp app/build/outputs/apk/debug/app-debug.apk output/KiadasKoveto-debug.apk
if [ -f app/build/outputs/apk/release/app-release.apk ]; then
  cp app/build/outputs/apk/release/app-release.apk output/KiadasKoveto-release.apk
elif [ -f app/build/outputs/apk/release/app-release-unsigned.apk ]; then
  cp app/build/outputs/apk/release/app-release-unsigned.apk output/KiadasKoveto-release-unsigned.apk
fi
cd output
sha256sum KiadasKoveto-*.apk | tee SHA256SUMS.txt
