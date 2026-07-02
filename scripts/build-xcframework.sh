#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"

"${REPO_ROOT}/templates/kotlin-app/gradlew" \
  -p "${REPO_ROOT}" \
  :app-contract:assembleXgGlassKitXCFramework \
  --console=plain

rm -rf "${REPO_ROOT}/artifacts/XgGlassKit.xcframework"
mkdir -p "${REPO_ROOT}/artifacts"
cp -R \
  "${REPO_ROOT}/app-contract/build/XCFrameworks/release/XgGlassKit.xcframework" \
  "${REPO_ROOT}/artifacts/XgGlassKit.xcframework"

echo "Wrote ${REPO_ROOT}/artifacts/XgGlassKit.xcframework"
