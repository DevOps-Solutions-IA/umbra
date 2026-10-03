#!/usr/bin/env bash
# Source in the owning CI shell: its EXIT trap owns every emulator it starts.
set -euo pipefail
UMBRA_EMULATOR_PIDS=()
UMBRA_EMULATOR_SERIALS=()
UMBRA_NETSIM_ACTIVE=0
UMBRA_CAPTURE_BATCH=0
export UMBRA_CAPTURE_OWNER_PID=""
: "${RUNNER_TEMP:?RUNNER_TEMP must name disposable runner storage}"
: "${ANDROID_HOME:?ANDROID_HOME required}"
export ANDROID_AVD_HOME="$RUNNER_TEMP/umbra-avds"
mkdir -p "$ANDROID_AVD_HOME"
export ANDROID_TMP="$RUNNER_TEMP/umbra-netsim-private"
mkdir -p "$ANDROID_TMP"
chmod 700 "$ANDROID_TMP"
UMBRA_DEVICE_REPORTS="${UMBRA_DEVICE_REPORTS:-android/app/build/reports/device}"
mkdir -p "$UMBRA_DEVICE_REPORTS"
UMBRA_NETSIM_HELPER="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/netsim_owner.py"
umbra_stop_avds() {
  local strict="${1:-0}" cleanup_status=0 pid serial index attempt
  # Later AVDs share the first AVD's netsim service. Release dependents first.
  # A nonzero process exit remains an error, including during graceful cleanup.
  for ((index=${#UMBRA_EMULATOR_PIDS[@]}-1; index>=0; index--)); do
    pid="${UMBRA_EMULATOR_PIDS[$index]}"; serial="${UMBRA_EMULATOR_SERIALS[$index]}"
    if kill -0 "$pid" 2>/dev/null; then
      if timeout 10 "$ANDROID_HOME/platform-tools/adb" -s "$serial" emu kill; then :; else
        if (( strict )); then cleanup_status=1; fi
        if kill "$pid" 2>/dev/null; then :; else echo "Emulator $serial already exited during cleanup" >&2; fi
      fi
      for attempt in {1..20}; do
        if ! kill -0 "$pid" 2>/dev/null; then break; fi
        sleep 0.5
      done
      if kill -0 "$pid" 2>/dev/null; then
        if kill -KILL "$pid" 2>/dev/null; then cleanup_status=1; fi
      fi
    fi
    if wait "$pid"; then :; else
      echo "Emulator $serial exited abnormally" >&2
      cleanup_status=1
    fi
  done
  UMBRA_EMULATOR_PIDS=(); UMBRA_EMULATOR_SERIALS=()
  return "$cleanup_status"
}
umbra_stop_capture_owner() {
  local record="${1:-0}" avds_failed="${2:-0}" result=0 code=0 child attempt
  if (( ! UMBRA_NETSIM_ACTIVE )); then return 0; fi
  if python "$UMBRA_NETSIM_HELPER" stop --state "$UMBRA_CAPTURE_STATE"; then :; else result=1; fi
  if python "$UMBRA_NETSIM_HELPER" wait-exit --state "$UMBRA_CAPTURE_STATE" --timeout 30; then :; else
    result=1
    # Forced termination is cleanup only and can never produce CLOSED evidence.
    if python "$UMBRA_NETSIM_HELPER" kill --state "$UMBRA_CAPTURE_STATE"; then :; else
      # Readiness may fail before a state receipt exists. The shell job table
      # still identifies its own live child; never discover processes globally.
      while read -r child; do
        if [[ "$child" == "$UMBRA_CAPTURE_OWNER_PID" ]]; then
          if kill -KILL "$child"; then :; else result=1; fi
        fi
      done < <(jobs -pr)
    fi
    for attempt in {1..40}; do
      if ! kill -0 "$UMBRA_CAPTURE_OWNER_PID" 2>/dev/null; then break; fi
      sleep 0.05
    done
    if kill -0 "$UMBRA_CAPTURE_OWNER_PID" 2>/dev/null; then
      echo "Owned capture process could not be reaped safely" >&2
      return 1
    fi
  fi
  if wait "$UMBRA_CAPTURE_OWNER_PID"; then code=0; else code=$?; result=1; fi
  UMBRA_NETSIM_ACTIVE=0
  if (( record )); then
    local failed=()
    if (( result || avds_failed )); then failed=(--avds-failed); fi
    if python "$UMBRA_NETSIM_HELPER" closed --state "$UMBRA_CAPTURE_STATE" \
      --receipt "$UMBRA_DEVICE_REPORTS/capture-owner.json" --exit-code "$code" \
      --log "$UMBRA_CAPTURE_LOG" "${failed[@]}"; then :; else result=1; fi
  fi
  return "$result"
}
umbra_cleanup() {
  local original=$? cleanup_status=0
  trap - EXIT
  if umbra_stop_avds; then :; else cleanup_status=1; fi
  if umbra_stop_capture_owner; then :; else cleanup_status=1; fi
  # Raw synthetic captures can contain temporary capabilities; never upload them.
  if (( UMBRA_NETSIM_ACTIVE )); then
    echo "Capture cleanup incomplete; refusing to remove active writer files" >&2
    cleanup_status=1
  elif ! rm -rf -- "$ANDROID_TMP"; then cleanup_status=1; fi
  if (( original != 0 )); then exit "$original"; fi
  exit "$cleanup_status"
}
trap umbra_cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
umbra_start_capture_owner() {
  if [[ "${UMBRA_FINALIZED_CAPTURE:-0}" != 1 ]]; then return 0; fi
  if (( UMBRA_NETSIM_ACTIVE )); then
    python "$UMBRA_NETSIM_HELPER" check --state "$UMBRA_CAPTURE_STATE"
    return
  fi
  # The default discovery INI is otherwise outside ANDROID_TMP. Both owned AVDs
  # inherit this private runtime and attach to this one explicit daemon.
  export XDG_RUNTIME_DIR="$ANDROID_TMP/runtime"
  mkdir -p "$XDG_RUNTIME_DIR"; chmod 700 "$XDG_RUNTIME_DIR"
  if compgen -G "$XDG_RUNTIME_DIR/netsim*.ini" >/dev/null; then
    echo "Refusing existing private netsim discovery state" >&2; return 1
  fi
  if [[ -e "$UMBRA_DEVICE_REPORTS/capture-owner.json" ]]; then
    mv -- "$UMBRA_DEVICE_REPORTS/capture-owner.json" "$UMBRA_DEVICE_REPORTS/capture-owner-batch-$UMBRA_CAPTURE_BATCH.json"
  fi
  UMBRA_CAPTURE_BATCH=$((UMBRA_CAPTURE_BATCH + 1))
  UMBRA_CAPTURE_STATE="$ANDROID_TMP/capture-owner-$UMBRA_CAPTURE_BATCH.json"
  UMBRA_CAPTURE_LOG="$ANDROID_TMP/capture-owner-$UMBRA_CAPTURE_BATCH.log"
  "$ANDROID_HOME/emulator/netsimd" --pcap --no-shutdown --logtostderr > "$UMBRA_CAPTURE_LOG" 2>&1 &
  export UMBRA_CAPTURE_OWNER_PID=$!
  UMBRA_NETSIM_ACTIVE=1
  python "$UMBRA_NETSIM_HELPER" ready --state "$UMBRA_CAPTURE_STATE" --pid "$UMBRA_CAPTURE_OWNER_PID" \
    --parent "$$" --executable "$ANDROID_HOME/emulator/netsimd" --capture-root "$ANDROID_TMP" --timeout 30
}
umbra_finalize_capture() {
  if [[ "${UMBRA_FINALIZED_CAPTURE:-0}" != 1 ]] || (( ! UMBRA_NETSIM_ACTIVE )); then
    echo "No opt-in owned capture daemon to finalize" >&2; return 1
  fi
  local result=0
  if umbra_stop_avds 1; then :; else result=1; fi
  if umbra_stop_capture_owner 1 "$result"; then :; else result=1; fi
  # No purge here: caller validates/snapshots closed captures before starting a
  # new batch. Only the owning shell's EXIT trap purges the private capture root.
  return "$result"
}
umbra_start_avd() {
  local name="$1" port="$2" avd="$ANDROID_AVD_HOME/$1.avd" log="$UMBRA_DEVICE_REPORTS/$1-emulator.log"
  local camera="${UMBRA_SYNTHETIC_CAMERA:-none}"
  case "$camera" in none|emulated) ;; *) echo "Only disabled or synthetic AVD cameras are allowed" >&2; return 1 ;; esac
  if ! test -r /dev/kvm || ! test -w /dev/kvm; then
    echo "BLOCKED: readable/writable /dev/kvm is mandatory" >&2
    return 1
  fi
  timeout 15 "$ANDROID_HOME/emulator/emulator" -accel-check
  timeout 60 avdmanager create avd --force --name "$name" --path "$avd" \
    --package 'system-images;android-35;default;x86_64' --abi x86_64 --device pixel_2 </dev/null
  local listed
  listed=$(timeout 15 "$ANDROID_HOME/emulator/emulator" -list-avds)
  printf 'AVD home: %s\nRegistered devices:\n%s\n' "$ANDROID_AVD_HOME" "$listed"
  if ! grep -Fxq "$name" <<< "$listed" || ! test -s "$ANDROID_AVD_HOME/$name.ini" || ! test -s "$avd/config.ini"; then
    echo "AVD creation did not produce registered configuration: $name" >&2
    find "$ANDROID_AVD_HOME" -maxdepth 2 -type f -name '*.ini' -print >&2
    return 1
  fi
  # Small display bounds host SwiftShader buffers without changing Android security.
  sed -i -e 's/^hw.lcd.width[[:space:]]*=.*/hw.lcd.width=480/' \
    -e 's/^hw.lcd.height[[:space:]]*=.*/hw.lcd.height=800/' \
    -e 's/^hw.lcd.density[[:space:]]*=.*/hw.lcd.density=160/' "$avd/config.ini"
  local dns_options=()
  if [[ -n "${UMBRA_STARTUP_DNS:-}" ]]; then
    if [[ "$UMBRA_STARTUP_DNS" != "127.0.0.1" ]]; then echo "Only loopback startup DNS fixture is allowed" >&2; return 1; fi
    dns_options=(-dns-server "$UMBRA_STARTUP_DNS")
  fi
  umbra_start_capture_owner
  if [[ "${UMBRA_FINALIZED_CAPTURE:-0}" == 1 ]]; then
    log="$UMBRA_DEVICE_REPORTS/$name-batch-$UMBRA_CAPTURE_BATCH-emulator.log"
  fi
  "$ANDROID_HOME/emulator/emulator" "${dns_options[@]}" -avd "$name" -port "$port" -no-window -no-audio \
    -camera-front "$camera" -camera-back "$camera" \
    -netsim-args "--pcap" -no-boot-anim -no-snapshot -gpu swiftshader -feature -Vulkan -accel on -memory 1536 -cores 2 > "$log" 2>&1 &
  local pid=$!
  UMBRA_EMULATOR_PIDS+=("$pid"); UMBRA_EMULATOR_SERIALS+=("emulator-$port")
  python scripts/wait_emulator.py --pid "$pid" --serial "emulator-$port" --log "$log" --timeout 180
}
