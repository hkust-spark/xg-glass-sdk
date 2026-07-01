from __future__ import annotations

import argparse
import hashlib
import shutil
import subprocess
from pathlib import Path

from .constants import _XG_GLASS_HOME


def _is_youtube_url(url: str) -> bool:
    return any(h in url for h in ("youtube.com", "youtu.be"))


def _is_bilibili_url(url: str) -> bool:
    return "bilibili.com" in url or "b23.tv" in url


def _find_yt_dlp() -> str:
    """Locate yt-dlp on PATH; raise with install instructions if missing."""
    p = shutil.which("yt-dlp")
    if p:
        return p
    raise RuntimeError(
        "yt-dlp is required to download videos from YouTube/Bilibili but was not found on PATH.\n"
        "Install it with:  pip install yt-dlp\n"
        "Or visit: https://github.com/yt-dlp/yt-dlp#installation"
    )


def _download_video_from_url(url: str, dest: Path) -> Path:
    """
    Download a video from a YouTube or Bilibili URL to *dest* directory.

    Returns the path of the downloaded file (always converted to mp4).
    Uses a hash of the URL as filename so different URLs are cached separately.
    """
    yt_dlp = _find_yt_dlp()
    dest.mkdir(parents=True, exist_ok=True)

    # Derive a short hash from the URL to uniquely identify the video.
    url_hash = hashlib.sha256(url.encode()).hexdigest()[:12]
    output_template = str(dest / f"video_{url_hash}.%(ext)s")
    expected_mp4 = dest / f"video_{url_hash}.mp4"

    # If the file already exists, reuse it (yt-dlp would also skip).
    if expected_mp4.exists() and expected_mp4.stat().st_size > 0:
        print(f"Using cached video: {expected_mp4}")
        return expected_mp4

    cmd = [yt_dlp]

    if _is_bilibili_url(url):
        # Bilibili needs a referer header; prefer mp4 container directly.
        cmd += [
            "-f", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best",
            "--merge-output-format", "mp4",
            "--referer", "https://www.bilibili.com",
            "-o", output_template,
            url,
        ]
    elif _is_youtube_url(url):
        cmd += [
            "-f", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best",
            "--merge-output-format", "mp4",
            "-o", output_template,
            url,
        ]
    else:
        # Generic: try yt-dlp with mp4 preference.
        cmd += [
            "-f", "bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best",
            "--merge-output-format", "mp4",
            "-o", output_template,
            url,
        ]

    print(f"Downloading video from: {url}")
    try:
        subprocess.check_call(cmd)
    except subprocess.CalledProcessError as exc:
        raise RuntimeError(
            f"Failed to download video from {url}.\n"
            "Make sure yt-dlp is up to date: pip install -U yt-dlp\n"
            f"Error: {exc}"
        ) from exc

    # Find the downloaded file (yt-dlp may adjust the extension).
    candidates = sorted(dest.glob(f"video_{url_hash}.*"))
    if not candidates:
        raise RuntimeError(f"yt-dlp finished but no video file found under: {dest}")
    return candidates[0]


def _resolve_sim_video(args: argparse.Namespace) -> Path | None:
    """
    Resolve the video file to use in simulator mode.

    Returns the local path to the video file, or None if neither --local_video
    nor --video_url was provided.
    """
    local_video = getattr(args, "local_video", None)
    url = getattr(args, "video_url", None)

    if local_video and url:
        raise RuntimeError("Cannot specify both --local_video and --video_url. Choose one.")

    if local_video:
        p = Path(local_video).expanduser().resolve()
        if not p.exists():
            raise FileNotFoundError(f"Local video file not found: {p}")
        print(f"Using local video: {p}")
        return p

    if url:
        dl_dir = _XG_GLASS_HOME / "video_cache"
        return _download_video_from_url(url, dl_dir)

    return None
