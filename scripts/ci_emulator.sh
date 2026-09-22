#!/usr/bin/env bash
# Source in the owning CI shell: its EXIT trap owns every emulator it starts.
set -euo pipefail
UMBRA_EMULATOR_PIDS=()
UMBRA_EMULATOR_SERIALS=()
: "${RUNNER_TEMP:?RUNNER_TEMP must name disposable runner storage}"
: "${ANDROID_HOME:?ANDROID_HOME required}"
export ANDROID_AVD_HOME="$RUNNER_TEMP/umbra-avds"
mkdir -p "$ANDROID_AVD_HOME"
export ANDROID_TMP="$RUNNER_TEMP/umbra-netsim-private"
mkdir -p "$ANDROID_TMP"
chmod 700 "$ANDROID_TMP"
UMBRA_DEVICE_REPORTS="${UMBRA_DEVICE_REPORTS:-android/app/build/reports/device}"
mkdir -p "$UMBRA_DEVICE_REPORTS"
umbra_cleanup() {
  local original=$? cleanup_status=0 pid serial index
  trap - EXIT
  for index in "${!UMBRA_EMULATOR_PIDS[@]}"; do
    pid="${UMBRA_EMULATOR_PIDS[$index]}"; serial="${UMBRA_EMULATOR_SERIALS[$index]}"
    if kill -0 "$pid" 2>/dev/null; then
      if timeout 10 "$ANDROID_HOME/platform-tools/adb" -s "$serial" emu kill; then :; else
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
      echo "Emulator $serial exited abnormally (original status $original)" >&2
      cleanup_status=1
    fi
  done
  # Raw synthetic captures can contain temporary capabilities; never upload them.
  if ! rm -rf -- "$ANDROID_TMP"; then cleanup_status=1; fi
  if (( original != 0 )); then exit "$original"; fi
  exit "$cleanup_status"
}
trap umbra_cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
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
  "$ANDROID_HOME/emulator/emulator" -avd "$name" -port "$port" -no-window -no-audio \
    -camera-front "$camera" -camera-back "$camera" \
    -netsim-args "--pcap" -no-boot-anim -no-snapshot -gpu swiftshader -feature -Vulkan -accel on -memory 1536 -cores 2 > "$log" 2>&1 &
  local pid=$!
  UMBRA_EMULATOR_PIDS+=("$pid"); UMBRA_EMULATOR_SERIALS+=("emulator-$port")
  python scripts/wait_emulator.py --pid "$pid" --serial "emulator-$port" --log "$log" --timeout 180
}
