#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "usage: scripts/bump-version.sh <new-version>" >&2
  exit 2
fi

new_version="$1"
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

python3 - "$new_version" <<'PY'
from __future__ import annotations

import re
import sys
from pathlib import Path

version = sys.argv[1]

changed: list[str] = []


def write_if_changed(path: str, old: str, new: str) -> None:
    if new == old:
        return
    Path(path).write_text(new, encoding="utf-8")
    if path not in changed:
        changed.append(path)


def replace_required(path: str, pattern: str, repl: str | callable) -> None:
    p = Path(path)
    old = p.read_text(encoding="utf-8")
    new, count = re.subn(pattern, repl, old, flags=re.MULTILINE)
    if count == 0:
        raise SystemExit(f"Expected version pattern not found in {path}: {pattern}")
    write_if_changed(path, old, new)


def update_gradle_properties(path: str) -> None:
    p = Path(path)
    old = p.read_text(encoding="utf-8")
    if re.search(r"^version=.*$", old, flags=re.MULTILINE):
        new = re.sub(r"^version=.*$", f"version={version}", old, flags=re.MULTILINE)
    else:
        suffix = "" if old.endswith("\n") else "\n"
        new = f"{old}{suffix}version={version}\n"
    write_if_changed(path, old, new)


def update_coordinates(path: str) -> None:
    replace_required(
        path,
        r"(io\.github\.hkust-spark:xgglass-[A-Za-z0-9_.-]+:)([0-9][0-9A-Za-z.+-]*)",
        rf"\g<1>{version}",
    )


def update_swiftpm(path: str) -> None:
    replace_required(
        path,
        r'(\.package\(url:\s*"https://github\.com/hkust-spark/xg-glass-sdk",\s*from:\s*")([^"]+)(")',
        rf"\g<1>{version}\3",
    )


update_gradle_properties("gradle.properties")
replace_required("tools/pyproject.toml", r'^version = "[^"]+"$', f'version = "{version}"')

for path in [
    "templates/kotlin-app/app/build.gradle.kts",
    "templates/kotlin-app/xgglass_app_logic/build.gradle.kts",
    "build-logic/src/main/kotlin/com/xgglass/buildlogic/rayneo/TemplateFiles.kt",
    "README.md",
    "docs/getting-started-android.md",
    "docs/releasing.md",
]:
    update_coordinates(path)

for path in [
    "README.md",
    "docs/swift-package.md",
    "docs/releasing.md",
]:
    update_swiftpm(path)

print("Changed files:")
if changed:
    for path in changed:
        print(f"  {path}")
else:
    print("  (none)")
PY

cat <<'EOF'

Manual release steps still required:
  - Add/update the CHANGELOG entry.
  - At release time, update Package.swift with the GitHub Release URL and XCFramework checksum.
  - Commit the release changes, create the plain semver git tag, and push both.
EOF
