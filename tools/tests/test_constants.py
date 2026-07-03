from __future__ import annotations

from xg_glass_cli.constants import missing_sdk_checkout_message


def test_init_missing_sdk_message_no_longer_mentions_template() -> None:
    message = missing_sdk_checkout_message("init")

    assert "xg-glass init ... --sdk /path/to/xg-glass-sdk" in message
    assert "--template" not in message


def test_run_missing_sdk_message_keeps_run_command() -> None:
    message = missing_sdk_checkout_message("run")

    assert "xg-glass run /path/to/MyEntry.kt --sdk /path/to/xg-glass-sdk" in message
