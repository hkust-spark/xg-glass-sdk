from __future__ import annotations

from .android_sdk import (
    _android_sdk_has_platform_tools,
    _auto_download_android_sdk,
    _ensure_project_sdk_dir,
    _ensure_sdk_local_properties,
    _find_android_sdk,
    _find_env_android_sdk,
    _resolve_android_sdk,
    _write_local_properties,
)
from .downloads import (
    _download_file,
    _download_json,
    _extract_archive,
    _http_user_agent,
    _run_quiet,
    _verify_sha256,
)
from .flutter import (
    _auto_download_flutter,
    _ensure_flutter_executables,
    _ensure_flutter_module_ready,
    _find_flutter_cmd,
    _managed_flutter_bin,
    _wipe_flutter_caches,
)
from .java import (
    _auto_download_jdk,
    _default_managed_jdk_major,
    _discover_existing_jdk,
    _ensure_java_runtime,
    _find_managed_java_home,
    _first_usable_java_home,
    _gradle_java_installation_paths,
    _homebrew_jdk_candidates,
    _is_usable_java_home,
    _java_cmd,
    _java_exe_name,
    _java_home_exe,
    _java_home_major,
    _maybe_infer_java_home,
    _parse_java_major,
)
from .paths import _is_truthy_env
from .shell_env import (
    _homeify,
    _manual_shell_setup_lines,
    _persist_env,
    _persist_env_enabled,
    _persist_env_macos_zshrc,
    _persist_env_windows,
    _print_manual_shell_setup,
    _profile_block_exists,
    _profile_block_markers,
    _profile_block_text,
    _shell_setup_values,
    _upsert_profile_block,
)
