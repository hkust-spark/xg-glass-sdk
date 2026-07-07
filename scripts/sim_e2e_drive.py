#!/usr/bin/env python3
"""Small portable helpers for scripts/sim-e2e.sh."""

from __future__ import annotations

import argparse
import os
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


def _normalize(text: str) -> str:
    return " ".join(text.casefold().split())


def _parse_bounds(raw: str) -> tuple[int, int, int, int]:
    match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", raw)
    if not match:
        raise ValueError(f"Invalid bounds: {raw}")
    return tuple(int(group) for group in match.groups())  # type: ignore[return-value]


def _cmd_find_text(args: argparse.Namespace) -> int:
    wanted = _normalize(args.text)
    tree = ET.parse(args.xml)
    candidates: list[ET.Element] = []
    for node in tree.iter("node"):
        text = node.attrib.get("text", "")
        content_desc = node.attrib.get("content-desc", "")
        if wanted not in {_normalize(text), _normalize(content_desc)}:
            continue
        if args.clickable_only and node.attrib.get("clickable") != "true":
            continue
        if node.attrib.get("enabled", "true") != "true":
            continue
        candidates.append(node)

    if not candidates:
        return 1

    node = candidates[0]
    left, top, right, bottom = _parse_bounds(node.attrib["bounds"])
    print(f"{(left + right) // 2} {(top + bottom) // 2}")
    return 0


def _avd_config_path(avd_name: str) -> Path:
    avd_home = os.environ.get("ANDROID_AVD_HOME")
    if avd_home:
        return Path(avd_home).expanduser() / f"{avd_name}.avd" / "config.ini"

    default_home = Path.home() / ".android" / "avd"
    ini_path = default_home / f"{avd_name}.ini"
    if ini_path.exists():
        for line in ini_path.read_text(encoding="utf-8", errors="replace").splitlines():
            key, sep, value = line.partition("=")
            if sep and key.strip() == "path" and value.strip():
                return Path(value.strip()).expanduser() / "config.ini"
    return default_home / f"{avd_name}.avd" / "config.ini"


def _upsert_config_value(lines: list[str], key: str, value: str) -> list[str]:
    prefix = f"{key}"
    rendered = f"{key} = {value}"
    for index, line in enumerate(lines):
        raw_key, sep, _raw_value = line.partition("=")
        if sep and raw_key.strip() == prefix:
            lines[index] = rendered
            return lines
    lines.append(rendered)
    return lines


def _cmd_set_avd_camera(args: argparse.Namespace) -> int:
    config = _avd_config_path(args.avd_name)
    if not config.exists():
        print(f"AVD config not found: {config}", file=sys.stderr)
        return 1

    lines = config.read_text(encoding="utf-8", errors="replace").splitlines()
    lines = _upsert_config_value(lines, "hw.camera.back", "emulated")
    # Keep the front camera away from host webcam passthrough as well.
    lines = _upsert_config_value(lines, "hw.camera.front", "none")
    config.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(config)
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="cmd", required=True)

    find_text = sub.add_parser("find-text")
    find_text.add_argument("--xml", required=True)
    find_text.add_argument("--text", required=True)
    find_text.add_argument("--clickable-only", action="store_true")
    find_text.set_defaults(func=_cmd_find_text)

    set_camera = sub.add_parser("set-avd-camera")
    set_camera.add_argument("--avd-name", required=True)
    set_camera.set_defaults(func=_cmd_set_avd_camera)

    args = parser.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
