#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
test_dir="$(mktemp -d "${TMPDIR:-/tmp}/xgglass-swift-tests.XXXXXX")"
trap 'rm -rf "$test_dir"' EXIT

xcrun swiftc -swift-version 5 -parse-as-library \
  "$repo_dir/Sources/XgGlassMeta/MetaAsyncOperation.swift" \
  "$repo_dir/scripts/tests/MetaAsyncOperationTests.swift" \
  -o "$test_dir/meta-operation-tests"
"$test_dir/meta-operation-tests"
