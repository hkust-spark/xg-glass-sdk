from __future__ import annotations

import os
import platform
import shutil
import subprocess
from pathlib import Path

from .android_sdk import _find_env_android_sdk
from .constants import _MAX_AGP_JDK_MAJOR
from .java import _is_usable_java_home
from .paths import _is_truthy_env


def _persist_env_enabled() -> bool:
    """
    Whether `xg-glass init` should persist env vars for future shells.
    Opt out via: XG_NO_PERSIST_ENV=1
    """
    return not _is_truthy_env("XG_NO_PERSIST_ENV")


def _homeify(path: str) -> str:
    """
    Replace the current user's home directory with $HOME for portability in shell profiles.
    """
    try:
        home = str(Path.home())
        return path.replace(home, "$HOME")
    except Exception:
        return path


def _shell_setup_values(
    *,
    java_home: str | None,
    android_sdk: str | None,
    flutter_bin: str | None,
) -> tuple[str, str, str]:
    """
    Return only values that should be persisted for future shells.

    If the user already has a valid JAVA_HOME or Android SDK in the current
    environment, keep it completely out of the managed shell block.
    """
    persist_java = "" if _is_usable_java_home(os.environ.get("JAVA_HOME")) else (java_home or "")
    persist_android = "" if _find_env_android_sdk(os.environ) else (android_sdk or "")
    has_flutter = bool(os.environ.get("FLUTTER") or shutil.which("flutter"))
    persist_flutter = "" if has_flutter or not flutter_bin else str(Path(flutter_bin).parent)
    return persist_java, persist_android, persist_flutter


def _manual_shell_setup_lines(
    *,
    java_home: str | None,
    android_sdk: str | None,
    flutter_bin: str | None,
) -> list[str]:
    java_home, android_sdk, flutter_dir = _shell_setup_values(
        java_home=java_home,
        android_sdk=android_sdk,
        flutter_bin=flutter_bin,
    )
    lines: list[str] = []
    if java_home:
        lines += [
            f'export JAVA_HOME="{_homeify(java_home)}"',
            'export PATH="${JAVA_HOME}/bin:${PATH}"',
        ]
    if android_sdk:
        android = _homeify(android_sdk)
        lines += [
            f'export ANDROID_SDK_ROOT="{android}"',
            f'export ANDROID_HOME="{android}"',
            'export PATH="${ANDROID_SDK_ROOT}/platform-tools:${PATH}"',
            'export PATH="${ANDROID_SDK_ROOT}/emulator:${PATH}"',
        ]
    if flutter_dir:
        lines.append(f'export PATH="{_homeify(flutter_dir)}:${{PATH}}"')
    return lines


def _print_manual_shell_setup(
    *,
    java_home: str | None,
    android_sdk: str | None,
    flutter_bin: str | None,
) -> None:
    lines = _manual_shell_setup_lines(java_home=java_home, android_sdk=android_sdk, flutter_bin=flutter_bin)
    print("Shell profile setup skipped (--no-shell-setup).")
    if not lines:
        print("No export lines are needed for the current environment.")
        return
    print("Add these export lines manually if you want future shells to use the resolved tools:")
    for line in lines:
        print(line)


def _profile_block_markers(block_id: str) -> tuple[str, str]:
    return f"# >>> xg-glass {block_id} >>>", f"# <<< xg-glass {block_id} <<<"


def _profile_block_exists(profile: Path, block_id: str) -> bool:
    start, end = _profile_block_markers(block_id)
    if not profile.exists():
        return False
    try:
        existing = profile.read_text(encoding="utf-8", errors="ignore")
    except Exception:
        return False
    return start in existing and end in existing


def _profile_block_text(block_id: str, body: str) -> str:
    start, end = _profile_block_markers(block_id)
    return "\n".join([start, body.rstrip(), end, ""])


def _upsert_profile_block(profile: Path, *, block_id: str, body: str) -> bool:
    """
    Idempotently upsert a marked block into a profile file. Returns True if modified.
    """
    start, end = _profile_block_markers(block_id)
    new_block = _profile_block_text(block_id, body)
    existing = ""
    if profile.exists():
        try:
            existing = profile.read_text(encoding="utf-8", errors="ignore")
        except Exception:
            existing = ""
    if start in existing and end in existing:
        pre, rest = existing.split(start, 1)
        _, post = rest.split(end, 1)
        updated = pre.rstrip("\n") + "\n\n" + new_block + post.lstrip("\n")
    else:
        sep = "\n" if existing.endswith("\n") or existing == "" else "\n\n"
        updated = existing + sep + new_block
    if updated == existing:
        return False
    profile.parent.mkdir(parents=True, exist_ok=True)
    profile.write_text(updated, encoding="utf-8")
    return True


def _persist_env_macos_zshrc(*, java_home: str | None, android_sdk: str | None, flutter_bin: str | None) -> None:
    """
    Persist env vars into ~/.zshrc (macOS target per product requirement).
    """
    if not _persist_env_enabled():
        return
    zshrc = Path.home() / ".zshrc"
    raw_java, raw_android, raw_flutter_dir = _shell_setup_values(
        java_home=java_home,
        android_sdk=android_sdk,
        flutter_bin=flutter_bin,
    )
    managed_java = _homeify(raw_java) if raw_java else ""
    managed_android = _homeify(raw_android) if raw_android else ""
    flutter_dir = _homeify(raw_flutter_dir) if raw_flutter_dir else ""
    has_exports = bool(managed_java or managed_android or flutter_dir)
    if not has_exports and not _profile_block_exists(zshrc, "env"):
        print("  Shell profile setup not needed; existing environment is valid.")
        return

    lines: list[str] = [
        "# xg-glass: one-click environment bootstrap (Java/Android SDK/Flutter)",
        "# - This block is managed by `xg-glass init`.",
        "# - It only fills in missing or unusable toolchain settings.",
        "",
        "xg_glass_prepend_path() {",
        '  case ":$PATH:" in',
        '    *":$1:"*) ;;',
        '    *) PATH="$1:$PATH" ;;',
        "  esac",
        "}",
        "",
    ]

    if managed_java:
        lines += [
            "# Java",
            "xg_glass_java_home_valid() {",
            '  [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]] || return 1',
            '  local xg_glass_java_version xg_glass_java_major',
            '  xg_glass_java_version="$("${JAVA_HOME}/bin/java" -version 2>&1 | sed -n \'s/.* version "\\([^"]*\\)".*/\\1/p\' | head -n 1)"',
            '  xg_glass_java_major="${xg_glass_java_version%%.*}"',
            '  if [[ "${xg_glass_java_major}" == "1" ]]; then',
            '    xg_glass_java_major="$(printf "%s" "${xg_glass_java_version}" | cut -d. -f2)"',
            "  fi",
            '  [[ "${xg_glass_java_major}" =~ ^[0-9]+$ ]] || return 1',
            f"  (( xg_glass_java_major >= 17 && xg_glass_java_major <= {_MAX_AGP_JDK_MAJOR} ))",
            "}",
            "if ! xg_glass_java_home_valid; then",
            f'  export JAVA_HOME="{managed_java}"',
            "fi",
            'if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then',
            '  xg_glass_prepend_path "${JAVA_HOME}/bin"',
            "fi",
            "",
        ]

    if managed_android:
        lines += [
            "# Android SDK",
            "xg_glass_android_sdk_valid() {",
            '  [[ -n "${ANDROID_HOME:-}" && -d "${ANDROID_HOME}/platform-tools" ]] && return 0',
            '  [[ -n "${ANDROID_SDK_ROOT:-}" && -d "${ANDROID_SDK_ROOT}/platform-tools" ]] && return 0',
            "  return 1",
            "}",
            "if ! xg_glass_android_sdk_valid; then",
            f'  export ANDROID_SDK_ROOT="{managed_android}"',
            f'  export ANDROID_HOME="{managed_android}"',
            "fi",
            'xg_glass_android_sdk_for_path="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"',
            'if [[ -n "${xg_glass_android_sdk_for_path}" && -d "${xg_glass_android_sdk_for_path}/platform-tools" ]]; then',
            '  xg_glass_prepend_path "${xg_glass_android_sdk_for_path}/platform-tools"',
            "fi",
            'if [[ -n "${xg_glass_android_sdk_for_path}" && -d "${xg_glass_android_sdk_for_path}/emulator" ]]; then',
            '  xg_glass_prepend_path "${xg_glass_android_sdk_for_path}/emulator"',
            "fi",
            "",
        ]

    if flutter_dir:
        lines += [
            "# Flutter",
            'if [[ -z "$(command -v flutter 2>/dev/null)" ]]; then',
            f'  xg_glass_prepend_path "{flutter_dir}"',
            "fi",
            "",
        ]

    body = "\n".join(lines)
    modified = _upsert_profile_block(zshrc, block_id="env", body=body)
    if modified:
        print(f"  Updated shell profile: {zshrc}")
        print("  Added/updated lines:")
        print(_profile_block_text("env", body).rstrip())
        print(f"  Restart your terminal (or run `source {zshrc}`) to apply.")
    elif has_exports:
        print(f"  Shell profile already up to date: {zshrc}")


def _persist_env_windows(*, java_home: str | None, android_sdk: str | None, flutter_bin: str | None) -> None:
    """
    Persist user env vars on Windows (takes effect in new terminals).
    """
    if not _persist_env_enabled():
        return
    if platform.system() != "Windows":
        return
    java_home, android_sdk, flutter_dir = _shell_setup_values(
        java_home=java_home,
        android_sdk=android_sdk,
        flutter_bin=flutter_bin,
    )
    if not (java_home or android_sdk or flutter_dir):
        print("  User environment setup not needed; existing environment is valid.")
        return

    ps = r"""
$ErrorActionPreference = "Stop"
function Set-UserEnvIfMissing([string]$name, [string]$value) {
  if ([string]::IsNullOrEmpty($value)) { return }
  $cur = [Environment]::GetEnvironmentVariable($name, "User")
  if ([string]::IsNullOrEmpty($cur) -or (-not (Test-Path $cur))) {
    [Environment]::SetEnvironmentVariable($name, $value, "User")
  }
}
function Prepend-UserPathIfMissing([string]$dir) {
  if ([string]::IsNullOrEmpty($dir)) { return }
  $path = [Environment]::GetEnvironmentVariable("Path", "User")
  if ([string]::IsNullOrEmpty($path)) { $path = "" }
  $parts = $path -split ';' | Where-Object { $_ -ne "" }
  if ($parts -notcontains $dir) {
    [Environment]::SetEnvironmentVariable("Path", ($dir + ";" + $path), "User")
  }
}
"""
    ps += r"""
if (-not [string]::IsNullOrEmpty($env:XG_ANDROID_SDK)) {
  Set-UserEnvIfMissing "ANDROID_SDK_ROOT" $env:XG_ANDROID_SDK
  Set-UserEnvIfMissing "ANDROID_HOME" $env:XG_ANDROID_SDK
  Prepend-UserPathIfMissing (Join-Path $env:XG_ANDROID_SDK "platform-tools")
}
if (-not [string]::IsNullOrEmpty($env:XG_JAVA_HOME)) {
  Set-UserEnvIfMissing "JAVA_HOME" $env:XG_JAVA_HOME
  Prepend-UserPathIfMissing (Join-Path $env:XG_JAVA_HOME "bin")
}
if (-not [string]::IsNullOrEmpty($env:XG_FLUTTER_DIR)) {
  Prepend-UserPathIfMissing $env:XG_FLUTTER_DIR
}
"""
    ps += 'Write-Host "Updated user environment variables. Restart your terminal to apply."\n'
    ps_env = {
        **os.environ,
        "XG_ANDROID_SDK": android_sdk or "",
        "XG_JAVA_HOME": java_home or "",
        "XG_FLUTTER_DIR": flutter_dir or "",
    }
    subprocess.run(
        ["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", ps],
        check=False,
        env=ps_env,
    )


def _persist_env(*, java_home: str | None = None, android_sdk: str | None = None, flutter_bin: str | None = None) -> None:
    if not _persist_env_enabled():
        return
    sysname = platform.system()
    if sysname == "Windows":
        _persist_env_windows(java_home=java_home, android_sdk=android_sdk, flutter_bin=flutter_bin)
    elif sysname == "Darwin":
        _persist_env_macos_zshrc(java_home=java_home, android_sdk=android_sdk, flutter_bin=flutter_bin)
