from __future__ import annotations

import os
import platform
import re
import shutil
import subprocess
import urllib.error
from pathlib import Path

from .constants import _MANAGED_JDK_DIR, _MAX_AGP_JDK_MAJOR
from .downloads import _download_file, _download_json, _extract_archive, _verify_sha256


def _maybe_infer_java_home(env: dict[str, str]) -> dict[str, str]:
    """
    Best-effort: infer JAVA_HOME if it's missing.

    This is mainly helpful on macOS where users may have a JDK installed but
    haven't exported JAVA_HOME in their shell.
    """
    discovered = _discover_existing_jdk(env)
    if discovered:
        env["JAVA_HOME"] = discovered
    return env


def _java_exe_name() -> str:
    return "java.exe" if platform.system() == "Windows" else "java"


def _java_cmd(env: dict[str, str]) -> str:
    """
    Prefer JAVA_HOME/bin/java when JAVA_HOME is set; otherwise fall back to `java` on PATH.
    """
    home = env.get("JAVA_HOME")
    if home:
        exe = Path(home) / "bin" / _java_exe_name()
        if exe.exists():
            return str(exe)
    return "java"


def _parse_java_major(java_version_output: str) -> int | None:
    """
    Parse Java major version from `java -version` output.

    Examples:
      - 'openjdk version "17.0.10" ...' -> 17
      - 'java version "1.8.0_321"' -> 8
    """
    m = re.search(r'version\s+"([^"]+)"', java_version_output)
    if not m:
        return None
    v = m.group(1).strip()
    if v.startswith("1."):
        parts = v.split(".")
        if len(parts) >= 2 and parts[1].isdigit():
            return int(parts[1])
        return None
    head = v.split(".", 1)[0]
    return int(head) if head.isdigit() else None


def _java_home_exe(java_home: str | Path | None) -> Path | None:
    if not java_home:
        return None
    exe = Path(str(java_home)).expanduser() / "bin" / _java_exe_name()
    if exe.is_file() and os.access(exe, os.X_OK):
        return exe
    return None


def _java_home_major(java_home: str | Path | None) -> int | None:
    exe = _java_home_exe(java_home)
    if not exe:
        return None
    try:
        p = subprocess.run([str(exe), "-version"], capture_output=True, text=True, timeout=15)
    except Exception:
        return None
    if p.returncode != 0:
        return None
    return _parse_java_major((p.stdout or "") + "\n" + (p.stderr or ""))


def _is_usable_java_home(java_home: str | Path | None) -> bool:
    major = _java_home_major(java_home)
    return major is not None and 17 <= major <= _MAX_AGP_JDK_MAJOR


def _first_usable_java_home(candidates: list[str | Path]) -> str | None:
    seen: set[str] = set()
    for candidate in candidates:
        raw = str(candidate).strip()
        if not raw:
            continue
        key = str(Path(raw).expanduser())
        if key in seen:
            continue
        seen.add(key)
        if _is_usable_java_home(raw):
            return raw
    return None


def _homebrew_jdk_candidates() -> list[Path]:
    candidates: list[Path] = []
    if platform.system() != "Darwin":
        return candidates

    for opt_dir in (Path("/opt/homebrew/opt"), Path("/usr/local/opt")):
        for formula in ("openjdk@17", "openjdk@21"):
            candidates.append(opt_dir / formula / "libexec" / "openjdk.jdk" / "Contents" / "Home")

    brew = shutil.which("brew")
    if not brew:
        return candidates
    for formula in ("openjdk@17", "openjdk@21"):
        try:
            p = subprocess.run([brew, "--prefix", formula], capture_output=True, text=True, timeout=15)
        except Exception:
            continue
        prefix = (p.stdout or "").strip()
        if p.returncode == 0 and prefix:
            candidates.append(Path(prefix) / "libexec" / "openjdk.jdk" / "Contents" / "Home")
    return candidates


def _gradle_java_installation_paths() -> list[str]:
    gradle_props = Path.home() / ".gradle" / "gradle.properties"
    if not gradle_props.exists():
        return []
    try:
        text = gradle_props.read_text(encoding="utf-8", errors="ignore")
    except Exception:
        return []

    key = "org.gradle.java.installations.paths"
    paths: list[str] = []
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith(("#", "!")):
            continue
        value: str | None = None
        for sep in ("=", ":"):
            prefix = key + sep
            if line.startswith(prefix):
                value = line[len(prefix):]
                break
        if value is None:
            continue
        for item in value.split(","):
            path = item.strip().replace("\\ ", " ").replace("\\:", ":")
            if path:
                paths.append(path)
    return paths


def _discover_existing_jdk(env: dict[str, str] | None = None) -> str | None:
    """
    Discover an installed, AGP-compatible JDK before falling back to downloads.

    Priority:
      1. valid JAVA_HOME from env
      2. Android Studio bundled JBR (macOS)
      3. /usr/libexec/java_home -v <=_MAX_AGP_JDK_MAJOR (macOS)
      4. Homebrew openjdk@17/openjdk@21
      5. Gradle org.gradle.java.installations.paths
      6. existing xg-glass managed JDK
    """
    env = os.environ if env is None else env

    java_home = (env.get("JAVA_HOME") or "").strip()
    if _is_usable_java_home(java_home):
        return java_home

    system = platform.system()
    if system == "Darwin":
        candidate = _first_usable_java_home([
            "/Applications/Android Studio.app/Contents/jbr/Contents/Home",
        ])
        if candidate:
            return candidate

        java_home_tool = Path("/usr/libexec/java_home")
        if java_home_tool.exists():
            try:
                p = subprocess.run(
                    [str(java_home_tool), "-v", f"<={_MAX_AGP_JDK_MAJOR}"],
                    capture_output=True,
                    text=True,
                    timeout=15,
                )
            except Exception:
                p = None
            if p is not None and p.returncode == 0:
                candidate = _first_usable_java_home([(p.stdout or "").strip()])
                if candidate:
                    return candidate

    candidate = _first_usable_java_home(_homebrew_jdk_candidates())
    if candidate:
        return candidate

    candidate = _first_usable_java_home(_gradle_java_installation_paths())
    if candidate:
        return candidate

    managed = _find_managed_java_home()
    if managed and _is_usable_java_home(managed):
        return managed
    return None


def _find_managed_java_home() -> str | None:
    """
    Find a previously downloaded managed JDK under ~/.xg-glass/jdk/.
    Returns JAVA_HOME path if found.
    """
    if not _MANAGED_JDK_DIR.is_dir():
        return None
    exe = _java_exe_name()
    try:
        children = [p for p in _MANAGED_JDK_DIR.iterdir() if p.is_dir()]
    except Exception:
        return None
    for child in sorted(children, key=lambda p: p.name):
        # Common layouts:
        # - <dir>/bin/java
        # - mac: <dir>/Contents/Home/bin/java (when extracting a .jdk bundle)
        for home in (child, child / "Contents" / "Home"):
            if (home / "bin" / exe).exists():
                return str(home)
    # Fallback: search deeper (handles nested top-level folder layouts).
    for child in children:
        try:
            for p in child.rglob(exe):
                if p.name == exe and p.parent.name == "bin":
                    return str(p.parent.parent)
        except Exception:
            continue
    return None


def _default_managed_jdk_major() -> int:
    """
    Pick the default managed JDK major version to download.

    - Must be >=17 (Android/AGP baseline).
    - Capped at _MAX_AGP_JDK_MAJOR (currently 21) because the project's AGP/Gradle
      toolchain may not yet support newer JDKs (e.g. JDK 25 causes "25.0.2" build errors).
    - Override via env var: XG_JAVA_MAJOR=17|21|25|...  (the cap is skipped when overriding)
    """
    raw = os.environ.get("XG_JAVA_MAJOR", "").strip()
    if raw:
        # Explicit override: trust the user, no upper cap.
        try:
            v = int(raw)
            return v if v >= 17 else 17
        except Exception:
            return 17

    # Upper bound: highest JDK major known to work with current AGP / Gradle.
    # Bump this when libs.versions.toml upgrades AGP to a version that supports newer JDKs.
    max_jdk = _MAX_AGP_JDK_MAJOR

    try:
        data = _download_json("https://api.adoptium.net/v3/info/available_releases")
        if isinstance(data, dict):
            lts = data.get("available_lts_releases")
            if isinstance(lts, list):
                lts_int = [
                    int(x) for x in lts
                    if isinstance(x, int) or (isinstance(x, str) and str(x).isdigit())
                ]
                # Pick the highest LTS that is within [17, max_jdk].
                valid = [x for x in lts_int if 17 <= x <= max_jdk]
                if valid:
                    return max(valid)
            # Fallback: most_recent_lts (capped).
            mr = data.get("most_recent_lts")
            if isinstance(mr, int) and 17 <= mr <= max_jdk:
                return mr
    except Exception:
        pass
    return 21  # safe default: JDK 21 (LTS, well-supported by AGP 8.x)


def _auto_download_jdk(major: int) -> str:
    """
    Download and extract a managed JDK into ~/.xg-glass/jdk/.
    Returns JAVA_HOME.
    """
    major = max(17, int(major))
    system = platform.system().lower()
    machine = platform.machine().lower()
    os_name = {"darwin": "mac", "windows": "windows"}.get(system, "linux")
    arch = "aarch64" if machine in ("arm64", "aarch64") else "x64"
    prefer_ext = ".zip" if os_name == "windows" else ".tar.gz"

    print(f"Java (JDK {major}) not found. Downloading a managed JDK for xg-glass...")
    print(f"  Install location: {_MANAGED_JDK_DIR}")

    _MANAGED_JDK_DIR.mkdir(parents=True, exist_ok=True)

    link: str | None = None
    name: str | None = None
    expected_sha256: str | None = None

    # Allow explicit override for air-gapped / mirrored environments.
    override_url = os.environ.get("XG_JDK_URL", "").strip()
    if override_url:
        link = override_url
        name = override_url.rsplit("/", 1)[-1] or "jdk17"
    else:
        # Try to fetch a package link (more stable naming) via the assets API.
        assets_url = (
            f"https://api.adoptium.net/v3/assets/latest/{major}/hotspot"
            f"?os={os_name}&architecture={arch}&image_type=jdk"
        )
        try:
            data = _download_json(assets_url)
            if isinstance(data, dict):
                data = [data]
            candidates: list[tuple[str, str, str | None]] = []
            for item in data:
                bins = item.get("binaries") or []
                if not bins and item.get("binary"):
                    bins = [item.get("binary")]
                for b in bins or []:
                    pkg = (b or {}).get("package") or {}
                    lnk = pkg.get("link")
                    nm = pkg.get("name") or ""
                    checksum = pkg.get("checksum")
                    checksum = str(checksum).strip() if checksum else None
                    if lnk:
                        candidates.append((lnk, nm, checksum))
            if candidates:
                # Prefer expected extension for the platform.
                for lnk, nm, checksum in candidates:
                    if nm.endswith(prefer_ext):
                        link, name, expected_sha256 = lnk, nm, checksum
                        break
                if not link:
                    link, name, expected_sha256 = candidates[0]
        except Exception:
            link = None

    # Fallback: direct binary endpoint.
    if not link:
        link = f"https://api.adoptium.net/v3/binary/latest/{major}/ga/{os_name}/{arch}/jdk/hotspot/normal/eclipse"
        name = f"temurin-jdk{major}-{os_name}-{arch}{prefer_ext}"

    archive_path = _MANAGED_JDK_DIR / (name or "temurin-jdk17")
    print(f"  Downloading from: {link}")
    try:
        # Support local path override (air-gapped env), e.g. XG_JDK_URL=/tmp/jdk.tar.gz
        if "://" not in link:
            local = Path(link).expanduser()
            if local.exists() and local.is_file():
                shutil.copy2(local, archive_path)
            else:
                _download_file(link, archive_path)
        else:
            _download_file(link, archive_path)
        print()  # newline after progress
        if expected_sha256:
            _verify_sha256(archive_path, expected_sha256)
    except (urllib.error.HTTPError, urllib.error.URLError, OSError, RuntimeError) as exc:
        archive_path.unlink(missing_ok=True)
        hint = ""
        if isinstance(exc, urllib.error.HTTPError) and getattr(exc, "code", None) == 403:
            hint = (
                "\nHint: HTTP 403 usually means the download host is blocked by your network/WAF.\n"
                "      You can set a proxy (HTTPS_PROXY/HTTP_PROXY) or provide a mirror URL via XG_JDK_URL.\n"
            )
        raise RuntimeError(
            f"Failed to download JDK {major}: {exc}{hint}\n"
            "Please install JDK 17+ manually.\n"
            "macOS: brew install --cask temurin@17\n"
            "Windows (PowerShell): winget install EclipseAdoptium.Temurin.17.JDK"
        ) from exc

    # Extract to a temp dir, then move into place.
    tmp_extract = _MANAGED_JDK_DIR / "_jdk_extract_tmp"
    if tmp_extract.exists():
        shutil.rmtree(tmp_extract, ignore_errors=True)
    tmp_extract.mkdir(parents=True, exist_ok=True)

    print("  Extracting...")
    try:
        _extract_archive(archive_path, tmp_extract)
    finally:
        archive_path.unlink(missing_ok=True)

    # Pick a single top-level directory if present; otherwise search under tmp_extract.
    top_dirs = [p for p in tmp_extract.iterdir() if p.is_dir()]
    install_dir = top_dirs[0] if len(top_dirs) == 1 else tmp_extract

    # Clear existing managed JDKs (keep it simple: one managed install).
    for p in _MANAGED_JDK_DIR.iterdir():
        if p.name.startswith("_"):
            continue
        if p.is_dir():
            shutil.rmtree(p, ignore_errors=True)
        else:
            p.unlink(missing_ok=True)

    final_dir = _MANAGED_JDK_DIR / (install_dir.name if install_dir != tmp_extract else "jdk17")
    if final_dir.exists():
        shutil.rmtree(final_dir, ignore_errors=True)
    shutil.move(str(install_dir), str(final_dir))
    shutil.rmtree(tmp_extract, ignore_errors=True)

    # Detect JAVA_HOME inside final_dir.
    exe = _java_exe_name()
    for home in (final_dir, final_dir / "Contents" / "Home"):
        if (home / "bin" / exe).exists():
            print(f"  JDK installed successfully: {home}")
            return str(home)
    # Fallback search:
    for p in final_dir.rglob(exe):
        if p.name == exe and p.parent.name == "bin":
            home = p.parent.parent
            print(f"  JDK installed successfully: {home}")
            return str(home)
    raise RuntimeError(
        f"JDK extracted but java executable not found under: {final_dir}\n"
        "Please install JDK 17+ manually."
    )


def _ensure_java_runtime(env: dict[str, str]) -> None:
    """
    Ensure Java runtime is available for Android SDK tools (sdkmanager/avdmanager).

    On macOS, `/usr/bin/java` can exist but still fail with:
      "Unable to locate a Java Runtime."
    """
    env = _maybe_infer_java_home(env)

    def check() -> tuple[bool, str, int | None]:
        cmd = _java_cmd(env)
        try:
            p = subprocess.run([cmd, "-version"], capture_output=True, text=True, env=env)
        except FileNotFoundError:
            return False, "java executable not found", None
        out = ((p.stdout or "") + "\n" + (p.stderr or "")).strip()
        major = _parse_java_major(out)
        ok = p.returncode == 0 and major is not None and 17 <= major <= _MAX_AGP_JDK_MAJOR
        return ok, out, major

    ok, out, major = check()
    if ok:
        return

    # If Java exists but is too old (<17), or Java is missing/broken, try managed JDK (opt-out supported).
    if os.environ.get("XG_NO_JAVA_DOWNLOAD", "").strip() not in ("", "0"):
        if major is not None and major < 17:
            raise RuntimeError(
                f"Java {major} detected, but JDK 17+ is required.\n"
                "Please install JDK 17+ and ensure `java -version` works.\n"
                "macOS: brew install --cask temurin@17 && export JAVA_HOME=$(/usr/libexec/java_home -v 17)\n"
                "Windows (PowerShell): winget install EclipseAdoptium.Temurin.17.JDK"
            )
        if major is not None and major > _MAX_AGP_JDK_MAJOR:
            raise RuntimeError(
                f"Java {major} detected, but this Android Gradle Plugin supports up to JDK {_MAX_AGP_JDK_MAJOR}.\n"
                "Please install JDK 17 or 21 and set JAVA_HOME to it.\n"
                "macOS: brew install openjdk@17 && export JAVA_HOME=$(/usr/libexec/java_home -v 17)\n"
                "Windows (PowerShell): winget install EclipseAdoptium.Temurin.17.JDK"
            )
        raise RuntimeError(
            "Java runtime is not available, and auto-download is disabled (XG_NO_JAVA_DOWNLOAD=1).\n"
            "Please install JDK 17+ and ensure `java -version` works.\n"
            "macOS: brew install --cask temurin@17 && export JAVA_HOME=$(/usr/libexec/java_home -v 17)\n"
            "Windows (PowerShell): winget install EclipseAdoptium.Temurin.17.JDK\n"
            f"`java -version` output:\n{out}"
        )

    java_home = _discover_existing_jdk(env)
    if not java_home:
        java_home = _auto_download_jdk(_default_managed_jdk_major())
    env["JAVA_HOME"] = java_home
    env["PATH"] = str(Path(java_home) / "bin") + os.pathsep + env.get("PATH", "")

    ok2, out2, major2 = check()
    if ok2:
        return
    raise RuntimeError(
        "Java runtime is still not available after setting up a managed JDK.\n"
        "Please install JDK 17+ manually and ensure `java -version` works.\n"
        f"`java -version` output:\n{out2}"
    )
