from __future__ import annotations

import hashlib
import json
import os
import subprocess
import sys
import tarfile
import urllib.request
import zipfile
from pathlib import Path

from .paths import _is_truthy_env


def _http_user_agent() -> str:
    # Some CDNs/WAFs return 403 for default Python urllib UA; use a browser-like UA.
    return "Mozilla/5.0 (xg-glass-cli) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36"


def _download_file(url: str, dest: Path) -> None:
    """
    Download a URL to a file with a custom User-Agent and progress output.
    """
    req = urllib.request.Request(url, headers={"User-Agent": _http_user_agent(), "Accept": "*/*"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        total = resp.headers.get("Content-Length")
        try:
            total_size = int(total) if total else 0
        except Exception:
            total_size = 0
        dest.parent.mkdir(parents=True, exist_ok=True)
        downloaded = 0
        with open(dest, "wb") as f:
            while True:
                chunk = resp.read(256 * 1024)
                if not chunk:
                    break
                f.write(chunk)
                downloaded += len(chunk)
                if total_size > 0:
                    pct = min(100, downloaded * 100 // total_size)
                    mb_done = downloaded / (1024 * 1024)
                    mb_total = total_size / (1024 * 1024)
                    sys.stdout.write(f"\r  Progress: {pct}% ({mb_done:.1f} / {mb_total:.1f} MB)")
                    sys.stdout.flush()


def _download_json(url: str) -> object:
    req = urllib.request.Request(
        url,
        headers={
            "User-Agent": _http_user_agent(),
            "Accept": "application/json,text/plain,*/*",
        },
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read())


def _verify_sha256(path: Path, expected: str) -> None:
    expected = expected.strip().lower()
    if expected.startswith("sha256:"):
        expected = expected.split(":", 1)[1]
    h = hashlib.sha256()
    with path.open("rb") as f:
        while True:
            chunk = f.read(1024 * 1024)
            if not chunk:
                break
            h.update(chunk)
    actual = h.hexdigest()
    if actual != expected:
        raise RuntimeError(
            f"SHA-256 mismatch for {path.name}: expected {expected}, got {actual}"
        )


def _extract_archive(archive: Path, dest: Path) -> None:
    """Extract a zip / tar.gz / tar.xz archive into *dest*."""
    name = archive.name.lower()
    if name.endswith(".zip"):
        dest_root = Path(dest).resolve()
        with zipfile.ZipFile(str(archive), "r") as zf:
            for m in zf.infolist():
                target = (dest_root / m.filename).resolve()
                if not str(target).startswith(str(dest_root) + os.sep) and target != dest_root:
                    raise RuntimeError(f"Unsafe path in archive (zip-slip): {m.filename}")
            zf.extractall(str(dest))
    elif name.endswith((".tar.gz", ".tgz", ".tar.xz", ".tar")):
        with tarfile.open(str(archive), "r:*") as tf:
            if sys.version_info >= (3, 12):
                tf.extractall(str(dest), filter="data")
            else:
                tf.extractall(str(dest))
    else:
        raise RuntimeError(f"Unknown archive format: {archive.name}")


def _run_quiet(
    cmd: list[str],
    *,
    env: dict[str, str],
    timeout: int | None = None,
    input_text: str | None = None,
    check: bool = True,
    verbose_env: str = "XG_VERBOSE",
) -> subprocess.CompletedProcess[str]:
    """
    Run a command. By default, suppress stdout/stderr to avoid extremely noisy tools.
    Set the env var in `verbose_env` (e.g. XG_VERBOSE_SDKMANAGER=1) to stream output.
    """
    if _is_truthy_env(verbose_env):
        return subprocess.run(
            cmd,
            input=input_text,
            text=True,
            env=env,
            check=check,
            timeout=timeout,
        )
    p = subprocess.run(
        cmd,
        input=input_text,
        text=True,
        env=env,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        check=False,
        timeout=timeout,
    )
    if check and p.returncode != 0:
        tail = (p.stdout or "")[-8000:]
        raise subprocess.CalledProcessError(p.returncode, cmd, output=tail)
    return p
