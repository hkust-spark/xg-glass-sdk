#!/usr/bin/env bash
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
XG_GLASS="${XG_GLASS_BIN:-${REPO_ROOT}/xg-glass}"
PYTHON_BIN="${PYTHON:-python3}"
HELPER="${REPO_ROOT}/scripts/sim_e2e_drive.py"

WORKDIR="${XG_SIM_E2E_WORKDIR:-/tmp/xg-sim-e2e-project}"
QUICK_WORKDIR="${XG_SIM_E2E_QUICK_WORKDIR:-/tmp/xg-sim-e2e-quick}"
EVIDENCE_DIR="${XG_SIM_E2E_EVIDENCE_DIR:-/tmp/xg-sim-e2e-evidence-$(date -u +%Y%m%dT%H%M%SZ)}"
AVD_NAME="${XG_SIM_E2E_AVD_NAME:-xg_glass_avd}"
AVD_HOME="${XG_SIM_E2E_AVD_HOME:-/tmp/xg-sim-e2e-avd-home}"
SERIAL="${XG_SIM_E2E_SERIAL:-emulator-5554}"
PACKAGE="${XG_SIM_E2E_PACKAGE:-com.example.xgglassapp}"
REMOTE_XML="/sdcard/xg-sim-e2e-window.xml"

STEP_RESULTS=()
FAILED_STEPS=()

log() {
  printf '[sim-e2e] %s\n' "$*"
}

record_step() {
  local status="$1"
  local name="$2"
  local detail="$3"
  STEP_RESULTS+=("${status} ${name}: ${detail}")
  if [[ "${status}" != "PASS" ]]; then
    FAILED_STEPS+=("${name}: ${detail}")
  fi
  log "${status} ${name}: ${detail}"
}

write_summary() {
  mkdir -p "${EVIDENCE_DIR}"
  {
    printf 'Simulator E2E step summary\n'
    printf 'workdir=%s\n' "${WORKDIR}"
    printf 'quick_workdir=%s\n' "${QUICK_WORKDIR}"
    printf 'evidence_dir=%s\n' "${EVIDENCE_DIR}"
    printf 'avd_name=%s\n' "${AVD_NAME}"
    printf 'avd_home=%s\n' "${AVD_HOME}"
    printf 'serial=%s\n\n' "${SERIAL}"
    for line in "${STEP_RESULTS[@]}"; do
      printf '%s\n' "${line}"
    done
  } > "${EVIDENCE_DIR}/step-summary.txt"
}

finish() {
  write_summary
  adb_cmd="$(find_adb 2>/dev/null || true)"
  if [[ -n "${adb_cmd}" ]]; then
    "${adb_cmd}" -s "${SERIAL}" emu kill >/dev/null 2>&1 || true
  fi
  if ((${#FAILED_STEPS[@]})); then
    printf '\nFAILED STEPS:\n' >&2
    for failure in "${FAILED_STEPS[@]}"; do
      printf ' - %s\n' "${failure}" >&2
    done
    printf 'Evidence: %s\n' "${EVIDENCE_DIR}" >&2
    exit 1
  fi
  printf '\nALL STEPS PASSED\n'
  printf 'Evidence: %s\n' "${EVIDENCE_DIR}"
}
trap finish EXIT

find_sdk_root() {
  local candidate
  for candidate in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "${HOME}/Library/Android/sdk" "${HOME}/Android/Sdk" "${HOME}/.xg-glass/android-sdk"; do
    if [[ -n "${candidate}" && -d "${candidate}/platform-tools" ]]; then
      printf '%s\n' "${candidate}"
      return 0
    fi
  done

  local adb_path
  adb_path="$(command -v adb 2>/dev/null || true)"
  if [[ -n "${adb_path}" ]]; then
    local sdk_from_adb
    sdk_from_adb="$(cd "$(dirname "${adb_path}")/.." && pwd)"
    if [[ -d "${sdk_from_adb}/platform-tools" ]]; then
      printf '%s\n' "${sdk_from_adb}"
      return 0
    fi
  fi
  return 1
}

find_adb() {
  local sdk
  if command -v adb >/dev/null 2>&1; then
    command -v adb
    return 0
  fi
  sdk="$(find_sdk_root)" || return 1
  if [[ -x "${sdk}/platform-tools/adb" ]]; then
    printf '%s\n' "${sdk}/platform-tools/adb"
    return 0
  fi
  return 1
}

find_sdk_tool() {
  local name="$1"
  local sdk="$2"
  local candidate
  if [[ -x "${sdk}/cmdline-tools/latest/bin/${name}" ]]; then
    printf '%s\n' "${sdk}/cmdline-tools/latest/bin/${name}"
    return 0
  fi
  for candidate in "${sdk}"/cmdline-tools/*/bin/"${name}"; do
    if [[ -x "${candidate}" ]]; then
      printf '%s\n' "${candidate}"
      return 0
    fi
  done
  if [[ -x "${sdk}/tools/bin/${name}" ]]; then
    printf '%s\n' "${sdk}/tools/bin/${name}"
    return 0
  fi
  if command -v "${name}" >/dev/null 2>&1; then
    command -v "${name}"
    return 0
  fi
  return 1
}

system_image_package() {
  printf 'system-images;android-34;google_apis;%s\n' "$(system_image_abi)"
}

system_image_abi() {
  case "$(uname -m)" in
    arm64|aarch64) printf 'arm64-v8a\n' ;;
    *) printf 'x86_64\n' ;;
  esac
}

create_manual_avd() {
  local sdk="$1"
  local image_dir="$2"
  local abi arch avd_dir ini_path

  if [[ ! -d "${image_dir}" ]]; then
    return 1
  fi

  abi="$(system_image_abi)"
  case "${abi}" in
    arm64-v8a) arch="arm64" ;;
    x86_64) arch="x86_64" ;;
    *) arch="${abi}" ;;
  esac

  avd_dir="${ANDROID_AVD_HOME}/${AVD_NAME}.avd"
  ini_path="${ANDROID_AVD_HOME}/${AVD_NAME}.ini"
  rm -rf "${avd_dir}"
  mkdir -p "${avd_dir}"

  cat > "${ini_path}" <<EOF
avd.ini.encoding=UTF-8
path=${avd_dir}
path.rel=avd/${AVD_NAME}.avd
target=android-34
EOF

  cat > "${avd_dir}/config.ini" <<EOF
AvdId = ${AVD_NAME}
PlayStore.enabled = no
abi.type = ${abi}
avd.ini.displayname = ${AVD_NAME}
avd.ini.encoding = UTF-8
disk.cachePartition = yes
disk.cachePartition.size = 66MB
disk.dataPartition.size = 6442450944
fastboot.forceColdBoot = yes
hw.accelerometer = yes
hw.audioInput = yes
hw.audioOutput = yes
hw.battery = yes
hw.camera.back = emulated
hw.camera.front = none
hw.cpu.arch = ${arch}
hw.cpu.ncore = 4
hw.device.manufacturer = Google
hw.device.name = pixel
hw.gps = yes
hw.gpu.enabled = yes
hw.gpu.mode = auto
hw.initialOrientation = portrait
hw.keyboard = yes
hw.lcd.density = 420
hw.lcd.height = 1920
hw.lcd.width = 1080
hw.ramSize = 2048
hw.sdCard = yes
hw.sensors.orientation = yes
hw.touchScreen = yes
hw.useext4 = yes
image.sysdir.1 = system-images/android-34/google_apis/${abi}/
runtime.network.latency = none
runtime.network.speed = full
sdcard.size = 512 MB
tag.display = Google APIs
tag.id = google_apis
vm.heapSize = 228
EOF

  printf '%s\n' "${avd_dir}/config.ini"
}

kill_emulators() {
  local adb="$1"
  local serials serial
  serials="$("${adb}" devices | awk '$1 ~ /^emulator-/ && $2 == "device" { print $1 }')"
  for serial in ${serials}; do
    log "Killing existing emulator ${serial}"
    "${adb}" -s "${serial}" emu kill >/dev/null 2>&1 || true
  done

  local deadline=$((SECONDS + 30))
  while ((SECONDS < deadline)); do
    if ! "${adb}" devices | awk '$1 ~ /^emulator-/ && $2 == "device" { found=1 } END { exit found ? 0 : 1 }'; then
      return 0
    fi
    sleep 1
  done
}

ensure_avd() {
  local sdk="$1"
  local sdkmanager avdmanager pkg image_dir config_path
  pkg="$(system_image_package)"
  image_dir="${sdk}/system-images/android-34/google_apis/$(system_image_abi)"
  sdkmanager="$(find_sdk_tool sdkmanager "${sdk}" || true)"
  avdmanager="$(find_sdk_tool avdmanager "${sdk}" || true)"

  export ANDROID_HOME="${sdk}"
  export ANDROID_SDK_ROOT="${sdk}"
  export ANDROID_AVD_HOME="${AVD_HOME}"
  mkdir -p "${ANDROID_AVD_HOME}"

  if [[ ! -x "${sdk}/emulator/emulator" || ! -d "${image_dir}" ]]; then
    if [[ -z "${sdkmanager}" ]]; then
      record_step "FAIL" "ensure_avd" "sdkmanager not found and emulator/system image is missing under ${sdk}"
      return 1
    fi
    log "Installing emulator/system image if needed: ${pkg}"
    if ! printf 'y\ny\ny\ny\ny\ny\ny\ny\ny\ny\ny\ny\ny\ny\ny\ny\ny\ny\ny\ny\n' \
      | "${sdkmanager}" --sdk_root="${sdk}" "emulator" "${pkg}" > "${EVIDENCE_DIR}/sdkmanager-avd.log" 2>&1; then
      record_step "FAIL" "ensure_avd" "sdkmanager failed; see ${EVIDENCE_DIR}/sdkmanager-avd.log"
      return 1
    fi
  fi

  if ! "${sdk}/emulator/emulator" -list-avds 2>/dev/null | grep -Fxq "${AVD_NAME}"; then
    log "Creating AVD ${AVD_NAME}"
    if [[ -n "${avdmanager}" ]] \
      && printf 'no\n' | "${avdmanager}" create avd -n "${AVD_NAME}" -k "${pkg}" -d pixel --force >> "${EVIDENCE_DIR}/sdkmanager-avd.log" 2>&1; then
      :
    else
      log "Falling back to manual AVD files for installed image ${pkg}"
      if ! create_manual_avd "${sdk}" "${image_dir}" >> "${EVIDENCE_DIR}/sdkmanager-avd.log" 2>&1; then
        record_step "FAIL" "ensure_avd" "failed to create AVD; see ${EVIDENCE_DIR}/sdkmanager-avd.log"
        return 1
      fi
    fi
  fi

  config_path="$("${PYTHON_BIN}" "${HELPER}" set-avd-camera --avd-name "${AVD_NAME}")" || {
    record_step "FAIL" "ensure_avd" "failed to set emulated camera for ${AVD_NAME}"
    return 1
  }
  record_step "PASS" "ensure_avd" "camera configured in ${config_path}"
}

dump_ui() {
  local label="$1"
  local adb="$2"
  local xml="${EVIDENCE_DIR}/ui-${label}.xml"
  "${adb}" -s "${SERIAL}" shell uiautomator dump "${REMOTE_XML}" >/dev/null 2>&1 || return 1
  "${adb}" -s "${SERIAL}" pull "${REMOTE_XML}" "${xml}" >/dev/null 2>&1 || return 1
  printf '%s\n' "${xml}"
}

wait_for_text() {
  local adb="$1"
  local text="$2"
  local label="$3"
  local timeout="${4:-30}"
  local deadline=$((SECONDS + timeout))
  local xml coords
  while ((SECONDS < deadline)); do
    xml="$(dump_ui "${label}" "${adb}")" || true
    if [[ -n "${xml:-}" ]]; then
      coords="$("${PYTHON_BIN}" "${HELPER}" find-text --xml "${xml}" --text "${text}" --clickable-only 2>/dev/null)" && {
        printf '%s\n' "${coords}"
        return 0
      }
    fi
    sleep 1
  done
  return 1
}

tap_text() {
  local adb="$1"
  local step="$2"
  local text="$3"
  local coords x y
  coords="$(wait_for_text "${adb}" "${text}" "${step}" 45)" || {
    record_step "FAIL" "${step}" "button text not found: ${text}"
    return 1
  }
  read -r x y <<< "${coords}"
  "${adb}" -s "${SERIAL}" shell input tap "${x}" "${y}"
  record_step "PASS" "${step}_tap" "tapped '${text}' at ${x},${y}"
}

save_screenshot() {
  local adb="$1"
  local label="$2"
  "${adb}" -s "${SERIAL}" exec-out screencap -p > "${EVIDENCE_DIR}/${label}.png" 2>/dev/null || true
}

collect_logcat() {
  local adb="$1"
  "${adb}" -s "${SERIAL}" logcat -d -s XgGlassApp:I > "${EVIDENCE_DIR}/logcat.txt" 2>/dev/null || true
}

wait_for_log() {
  local adb="$1"
  local step="$2"
  local regex="$3"
  local timeout="${4:-30}"
  local deadline=$((SECONDS + timeout))
  while ((SECONDS < deadline)); do
    collect_logcat "${adb}"
    if grep -E -q "${regex}" "${EVIDENCE_DIR}/logcat.txt"; then
      record_step "PASS" "${step}" "matched /${regex}/"
      return 0
    fi
    sleep 1
  done
  record_step "FAIL" "${step}" "missing logcat match /${regex}/"
  return 1
}

wait_for_boot_completed() {
  local adb="$1"
  local timeout="${2:-30}"
  local deadline=$((SECONDS + timeout))
  local boot

  while ((SECONDS < deadline)); do
    if "${adb}" devices | awk -v serial="${SERIAL}" '$1 == serial && $2 == "device" { found=1 } END { exit found ? 0 : 1 }'; then
      boot="$("${adb}" -s "${SERIAL}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
      if [[ "${boot}" == "1" ]]; then
        return 0
      fi
    fi
    sleep 1
  done
  return 1
}

run_quick_mode_stage() {
  local adb="$1"
  local quick_entry="${QUICK_WORKDIR}/QuickEntry.kt"
  local source_entry="${REPO_ROOT}/templates/kotlin-app/xgglass_app_logic/src/main/java/com/example/xgglassapp/logic/ExampleAppEntry.kt"

  rm -rf "${QUICK_WORKDIR}"
  mkdir -p "${QUICK_WORKDIR}"
  if ! cp "${source_entry}" "${quick_entry}"; then
    record_step "FAIL" "quick_run_prepare" "failed to copy ${source_entry}"
    return 1
  fi
  record_step "PASS" "quick_run_prepare" "copied template entry to ${quick_entry}"

  "${adb}" -s "${SERIAL}" logcat -c >/dev/null 2>&1 || true
  if (
    cd "${QUICK_WORKDIR}" \
      && "${XG_GLASS}" run "${quick_entry}" --sim --devices simulator --serial "${SERIAL}" --keep-tmp
  ) > "${EVIDENCE_DIR}/quick-run.log" 2>&1; then
    record_step "PASS" "quick_run_sim" "xg-glass run QuickEntry.kt --sim --devices simulator completed on ${SERIAL}"
  else
    record_step "FAIL" "quick_run_sim" "see ${EVIDENCE_DIR}/quick-run.log"
    return 1
  fi

  wait_for_log "${adb}" "quick_run_launch" 'connect\(SIMULATOR\) => true' 60 || return 1
  save_screenshot "${adb}" "08-quick-run-launched"
}

main() {
  mkdir -p "${EVIDENCE_DIR}"
  log "Evidence directory: ${EVIDENCE_DIR}"
  log "Generated project workdir: ${WORKDIR}"
  log "Quick-run workdir: ${QUICK_WORKDIR}"
  log "Script-owned AVD home: ${AVD_HOME}"
  if [[ -z "${XG_SIM_E2E_AVD_HOME+x}" ]]; then
    rm -rf "${AVD_HOME}"
  fi
  mkdir -p "${AVD_HOME}"
  export ANDROID_AVD_HOME="${AVD_HOME}"

  if [[ ! -x "${XG_GLASS}" ]]; then
    record_step "FAIL" "preflight" "xg-glass not executable: ${XG_GLASS}"
    return
  fi
  if ! command -v "${PYTHON_BIN}" >/dev/null 2>&1; then
    record_step "FAIL" "preflight" "python not found: ${PYTHON_BIN}"
    return
  fi

  rm -rf "${WORKDIR}"
  if "${XG_GLASS}" init --sim "${WORKDIR}" --no-shell-setup > "${EVIDENCE_DIR}/init.log" 2>&1; then
    record_step "PASS" "init" "generated simulator project at ${WORKDIR}"
  else
    record_step "FAIL" "init" "see ${EVIDENCE_DIR}/init.log"
    return
  fi

  local sdk adb
  sdk="$(find_sdk_root)" || {
    record_step "FAIL" "preflight" "Android SDK not found"
    return
  }
  adb="$(find_adb)" || {
    record_step "FAIL" "preflight" "adb not found"
    return
  }

  kill_emulators "${adb}"
  ensure_avd "${sdk}" || return

  if "${XG_GLASS}" run --project "${WORKDIR}" --sim --serial "${SERIAL}" > "${EVIDENCE_DIR}/run.log" 2>&1; then
    record_step "PASS" "run_sim" "xg-glass run --sim completed on ${SERIAL}"
  else
    log "Initial xg-glass run --sim failed; checking whether the CLI-started emulator completed boot"
    if wait_for_boot_completed "${adb}" 45 \
      && "${XG_GLASS}" run --project "${WORKDIR}" --sim --serial "${SERIAL}" > "${EVIDENCE_DIR}/run-retry.log" 2>&1; then
      record_step "PASS" "run_sim" "xg-glass run --sim completed on retry after CLI booted ${SERIAL}"
    else
      record_step "FAIL" "run_sim" "see ${EVIDENCE_DIR}/run.log and ${EVIDENCE_DIR}/run-retry.log if present"
      return
    fi
  fi

  "${adb}" -s "${SERIAL}" shell pm grant "${PACKAGE}" android.permission.CAMERA >/dev/null 2>&1 || true
  "${adb}" -s "${SERIAL}" shell pm grant "${PACKAGE}" android.permission.RECORD_AUDIO >/dev/null 2>&1 || true
  "${adb}" -s "${SERIAL}" shell am force-stop "${PACKAGE}" >/dev/null 2>&1 || true
  "${adb}" -s "${SERIAL}" logcat -c >/dev/null 2>&1 || true
  "${adb}" -s "${SERIAL}" shell am start -n "${PACKAGE}/.MainActivity" > "${EVIDENCE_DIR}/am-start.log" 2>&1

  save_screenshot "${adb}" "00-launched"
  wait_for_log "${adb}" "connect" 'connect\(SIMULATOR\) => true' 60 || return
  save_screenshot "${adb}" "01-connected"

  tap_text "${adb}" "capture_photo" "Capture photo" || return
  wait_for_log "${adb}" "capture_photo" 'capture_photo: [1-9][0-9]* bytes' 60 || return
  save_screenshot "${adb}" "02-capture-photo"

  tap_text "${adb}" "display_hello" "Display hello" || return
  wait_for_log "${adb}" "display_hello" 'display_hello: ok' 30 || return
  save_screenshot "${adb}" "03-display-hello"

  tap_text "${adb}" "mic_record_3s" "Mic record 3s" || return
  wait_for_log "${adb}" "mic_record_3s" 'mic_record: [1-9][0-9]* chunks, [0-9]+ bytes' 30 || return
  save_screenshot "${adb}" "04-mic-record"

  tap_text "${adb}" "simulate_tap" "Simulate Tap" || return
  wait_for_log "${adb}" "simulate_tap" 'TAP: 1' 15 || return
  save_screenshot "${adb}" "05-simulate-tap"

  tap_text "${adb}" "simulate_long_press" "Simulate Long-press" || return
  wait_for_log "${adb}" "simulate_long_press" 'LONG_PRESS' 15 || return
  save_screenshot "${adb}" "06-simulate-long-press"

  tap_text "${adb}" "disconnect" "Disconnect" || return
  wait_for_log "${adb}" "disconnect" 'disconnect\(\) => true' 15 || return
  save_screenshot "${adb}" "07-disconnect"

  run_quick_mode_stage "${adb}" || return
}

main "$@"
