"""Line-based generated-template filtering for xg.glass device markers.

Markers must be standalone comment lines, except for the explicit inline
`:line` form. Never place marker text inside string literals: this filter does
not lex Kotlin/Gradle/XML, it only reads one line at a time.
"""

from __future__ import annotations

import re
from dataclasses import dataclass

from .constants import CliUsageError

VALID_DEVICE_NAMES: tuple[str, ...] = (
    "rokid",
    "rayneo",
    "meta",
    "frame",
    "omi",
    "even",
    "inmo",
    "simulator",
)

_VALID_DEVICE_SET = frozenset(VALID_DEVICE_NAMES)


@dataclass(frozen=True)
class DeviceSelection:
    devices: tuple[str, ...]
    is_all: bool
    explicit: bool

    def yaml_devices(self) -> tuple[str, ...]:
        if self.is_all:
            return ("all",)
        return self.devices


def parse_device_selection(raw_devices: str | None, *, sim: bool = False) -> DeviceSelection:
    if raw_devices is None:
        return DeviceSelection(devices=VALID_DEVICE_NAMES, is_all=True, explicit=False)

    tokens = [token.strip().lower() for token in raw_devices.split(",")]
    tokens = [token for token in tokens if token]
    if not tokens:
        raise CliUsageError(_valid_devices_message("No devices were provided to --devices."))

    unknown = [token for token in tokens if token != "all" and token not in _VALID_DEVICE_SET]
    if unknown:
        raise CliUsageError(_valid_devices_message(f"Unknown device for --devices: {', '.join(unknown)}."))

    if "all" in tokens:
        if len(tokens) > 1:
            raise CliUsageError(_valid_devices_message("Use --devices all by itself, or list concrete devices."))
        return DeviceSelection(devices=VALID_DEVICE_NAMES, is_all=True, explicit=True)

    selected = set(tokens)
    if sim:
        selected.add("simulator")
    ordered = tuple(device for device in VALID_DEVICE_NAMES if device in selected)
    return DeviceSelection(devices=ordered, is_all=False, explicit=True)


def format_devices_yaml_value(selection: DeviceSelection) -> str:
    return "[" + ", ".join(selection.yaml_devices()) + "]"


def filter_template_for_devices(text: str, selection: DeviceSelection) -> str:
    """
    Apply // xg:device:<selector>:begin/end marker blocks.

    Marker lines are always removed. Blocks are included when any selected device
    matches the selector. The special selectors `all` and `partial` match the
    default all-devices path and every concrete-device path, respectively.
    """

    out: list[str] = []
    stack: list[tuple[str, bool]] = []
    active = True

    for raw_line in text.splitlines(keepends=True):
        marker = _parse_block_marker(raw_line)
        if marker is not None:
            selector, direction = marker
            if direction == "begin":
                matched = _selector_matches(selector, selection)
                stack.append((selector, active))
                active = active and matched
            else:
                if not stack:
                    raise CliUsageError(f"Unexpected xg device marker end for selector '{selector}'.")
                begin_selector, parent_active = stack.pop()
                if begin_selector != selector:
                    raise CliUsageError(
                        f"Mismatched xg device marker: began '{begin_selector}', ended '{selector}'."
                    )
                active = parent_active
            continue

        inline = _parse_inline_marker(raw_line)
        if inline is not None:
            content, selector, newline = inline
            if active and _selector_matches(selector, selection):
                out.append(content.rstrip() + newline)
            continue

        if "xg:device:" in raw_line:
            raise CliUsageError(
                "Malformed xg device marker. Use standalone comments like "
                "'// xg:device:even:begin' / '// xg:device:even:end' or "
                "the inline '// xg:device:even:line' form with no trailing text."
            )

        if active:
            out.append(raw_line)

    if stack:
        selector, _parent_active = stack[-1]
        raise CliUsageError(f"Unclosed xg device marker for selector '{selector}'.")

    return "".join(out)


def _valid_devices_message(prefix: str) -> str:
    valid = ", ".join((*VALID_DEVICE_NAMES, "all"))
    return f"{prefix} Valid values: {valid}."


_BLOCK_MARKER_RE = re.compile(
    r"^\s*(?://|#)\s*xg:device:([A-Za-z0-9_, -]+):(begin|end)\s*$"
)
_INLINE_MARKER_RE = re.compile(
    r"^(.*?)(?:\s+)(?://|#)\s*xg:device:([A-Za-z0-9_, -]+):line[ \t]*(\r?\n?)$"
)


def _parse_block_marker(line: str) -> tuple[str, str] | None:
    match = _BLOCK_MARKER_RE.match(line.rstrip("\r\n"))
    if not match:
        return None
    selector = _normalize_selector(match.group(1))
    return selector, match.group(2)


def _parse_inline_marker(line: str) -> tuple[str, str, str] | None:
    match = _INLINE_MARKER_RE.match(line)
    if not match:
        return None
    selector = _normalize_selector(match.group(2))
    return match.group(1), selector, match.group(3)


def _normalize_selector(raw_selector: str) -> str:
    parts = [part.strip().lower() for part in raw_selector.split(",")]
    parts = [part for part in parts if part]
    if not parts:
        raise CliUsageError("Empty xg device marker selector.")

    unknown = [part for part in parts if part not in _VALID_DEVICE_SET and part not in {"all", "partial"}]
    if unknown:
        raise CliUsageError(f"Unknown xg device marker selector: {', '.join(unknown)}.")

    if len(parts) > 1 and any(part in {"all", "partial"} for part in parts):
        raise CliUsageError("Marker selectors 'all' and 'partial' must not be combined with other selectors.")

    return ",".join(parts)


def _selector_matches(selector: str, selection: DeviceSelection) -> bool:
    parts = selector.split(",")
    if "all" in parts:
        return selection.is_all
    if "partial" in parts:
        return not selection.is_all
    selected = set(selection.devices)
    return any(part in selected for part in parts)
