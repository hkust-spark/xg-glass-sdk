#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

"${REPO_ROOT}/scripts/build-xcframework.sh"

rm -f "${REPO_ROOT}/artifacts/XgGlassKit.xcframework.zip"
ditto -c -k --sequesterRsrc --keepParent \
  "${REPO_ROOT}/artifacts/XgGlassKit.xcframework" \
  "${REPO_ROOT}/artifacts/XgGlassKit.xcframework.zip"

swift package compute-checksum "${REPO_ROOT}/artifacts/XgGlassKit.xcframework.zip"
