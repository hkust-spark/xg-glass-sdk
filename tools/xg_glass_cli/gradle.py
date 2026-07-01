from __future__ import annotations

import platform
import subprocess
from pathlib import Path

from .paths import _ensure_executable


def _gradlew_path(project: Path) -> Path:
    is_windows = platform.system().lower().startswith("win")
    p = project / ("gradlew.bat" if is_windows else "gradlew")
    if not p.exists():
        raise FileNotFoundError(f"gradlew not found in project root: {p}")
    if not is_windows:
        _ensure_executable(p)
    return p


def _cap(s: str) -> str:
    if not s:
        return s
    return s[0].upper() + s[1:]


def _run(cmd: list[str], cwd: Path, *, env: dict[str, str] | None = None) -> None:
    print("+", " ".join(cmd))
    subprocess.check_call(cmd, cwd=str(cwd), env=env)
