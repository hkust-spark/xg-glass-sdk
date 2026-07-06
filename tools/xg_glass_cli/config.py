from __future__ import annotations

import os
import re
from dataclasses import dataclass
from pathlib import Path

_FQCN_RE = re.compile(r'^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)+$')


def _validate_entry_class(entry_class: str, source: str) -> str:
    entry_class = str(entry_class).strip()
    if not entry_class:
        raise ValueError(f"{source} must be non-empty")
    if not _FQCN_RE.fullmatch(entry_class):
        raise ValueError(f"{source} must be a fully-qualified Java/Kotlin class name")
    return entry_class


@dataclass(frozen=True)
class XgConfig:
    sdk_path: str | None = None
    entry_class: str | None = None
    rayneo_mercury_aar_dir: str | None = None
    devices: str | None = None
    variant: str = "debug"
    module: str = "app"
    application_id: str | None = None


def _load_config(project: Path, config_arg: str) -> XgConfig:
    cfg_path = (project / config_arg) if not os.path.isabs(config_arg) else Path(config_arg)
    if not cfg_path.exists():
        return XgConfig()
    data = _parse_simple_yaml(cfg_path.read_text(encoding="utf-8"))
    entry_class = data.get("entryClass")
    if entry_class:
        entry_class = _validate_entry_class(entry_class, "entryClass in config")
    return XgConfig(
        sdk_path=data.get("sdkPath"),
        entry_class=entry_class,
        rayneo_mercury_aar_dir=data.get("rayneoMercuryAarDir"),
        devices=data.get("devices"),
        variant=(data.get("variant") or "debug"),
        module=(data.get("module") or "app"),
        application_id=data.get("applicationId"),
    )


def _apply_overrides(
    cfg: XgConfig,
    *,
    sdk: str | None = None,
    entry_class: str | None = None,
    rayneo_aar_dir: str | None = None,
    variant: str | None = None,
    module: str | None = None,
) -> XgConfig:
    v = (variant or cfg.variant).strip() if (variant or cfg.variant) else "debug"
    m = (module or cfg.module).strip() if (module or cfg.module) else "app"
    merged_entry_class = entry_class or cfg.entry_class
    if merged_entry_class:
        merged_entry_class = _validate_entry_class(
            merged_entry_class,
            "--entry-class" if entry_class else "entryClass in config",
        )
    return XgConfig(
        sdk_path=(sdk or cfg.sdk_path),
        entry_class=merged_entry_class,
        rayneo_mercury_aar_dir=(rayneo_aar_dir or cfg.rayneo_mercury_aar_dir),
        devices=cfg.devices,
        variant=v,
        module=m,
        application_id=cfg.application_id,
    )


def _parse_simple_yaml(text: str) -> dict[str, str]:
    """
    Minimal YAML subset parser:
    - top-level 'key: value'
    - ignores blank lines and lines starting with '#'
    - trims quotes around values
    """
    out: dict[str, str] = {}
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if ":" not in line:
            continue
        k, v = line.split(":", 1)
        k = k.strip()
        v = v.strip()
        if (v.startswith('"') and v.endswith('"')) or (v.startswith("'") and v.endswith("'")):
            v = v[1:-1]
        out[k] = v
    return out
