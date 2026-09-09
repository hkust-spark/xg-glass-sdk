#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
xcframework="${1:-$repo_dir/kotlin/app-contract/build/XCFrameworks/release/XgGlassKit.xcframework}"
if [[ ! -d "$xcframework" ]]; then
  echo "XCFramework not found: $xcframework" >&2
  echo "Build :app-contract:assembleXgGlassKitXCFramework first, or pass an XCFramework directory." >&2
  exit 1
fi

check_dir="$(mktemp -d "${TMPDIR:-/tmp}/xgglass-swift-package.XXXXXX")"
trap 'rm -rf "$check_dir"' EXIT
cp -R "$repo_dir/Sources" "$check_dir/Sources"
cp -R "$xcframework" "$check_dir/XgGlassKit.xcframework"

# Compile this checkout's Swift source against this checkout's Kotlin binary. The distributable
# manifest keeps pointing at the published archive; no release URL/checksum changes are required.
python3 - "$repo_dir/Package.swift" "$check_dir/Package.swift" <<'PY'
import re
import sys
from pathlib import Path

source, destination = map(Path, sys.argv[1:])
manifest, replacements = re.subn(
    r'\.binaryTarget\(\s*name:\s*"XgGlassKit",.*?\n\s*\)',
    '.binaryTarget(name: "XgGlassKit", path: "XgGlassKit.xcframework")',
    source.read_text(),
    count=1,
    flags=re.DOTALL,
)
if replacements != 1:
    raise SystemExit("Could not locate the XgGlassKit binary target in Package.swift")
destination.write_text(manifest)
PY

cd "$check_dir"
xcodebuild -scheme XgGlassMetaTesting \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath "$check_dir/build" \
  CODE_SIGNING_ALLOWED=NO ARCHS=arm64 build
