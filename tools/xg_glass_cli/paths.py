from __future__ import annotations

import os
from pathlib import Path


def _is_truthy_env(name: str) -> bool:
    return os.environ.get(name, "").strip() not in ("", "0", "false", "False", "no", "No")


def _ensure_executable(path: Path) -> None:
    if not path.exists():
        return
    mode = path.stat().st_mode
    path.chmod(mode | 0o111)
