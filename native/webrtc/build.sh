#!/usr/bin/env bash
# Local-only source build. No automatic application dependency/policy changes.
set -euo pipefail
: "${RUNNER_TEMP:?Dedicated ephemeral build directory required}"
abi="${1:?ABI required}"
case "$abi" in x86_64|x86|arm64-v8a|armeabi-v7a) ;; *) exit 2 ;; esac
repo="$(cd "$(dirname "$0")/../.." && pwd)"
build_root="$RUNNER_TEMP/umbra-native-$abi"
mkdir "$build_root"
cd "$build_root"
source_revision=73cb8180f7258ee292878d6edd05177f41883962
depot_revision=ca054941f756b50e1a3d83727270d879bec1f331
for entry in 'depot_tools https://chromium.googlesource.com/chromium/tools/depot_tools.git' 'src https://github.com/webrtc-sdk/webrtc.git'; do
  read -r folder url <<< "$entry"
  revision="$source_revision"
  if [[ "$folder" == depot_tools ]]; then revision="$depot_revision"; fi
  git init "$folder"
  git -C "$folder" remote add origin "$url"
  git -C "$folder" fetch --depth 1 origin "$revision"
  git -C "$folder" checkout --detach FETCH_HEAD
  test "$(git -C "$folder" rev-parse HEAD)" = "$revision"
done
export PATH="$build_root/depot_tools:$PATH"
export DEPOT_TOOLS_UPDATE=0 DEPOT_TOOLS_COLLECT_METRICS=0
export VPYTHON_BYPASS='manually managed python not supported by chrome operations'
python - <<'PY'
from pathlib import Path
import sys
assert sys.version_info >= (3,12)
# Do not install remote execution clients. Linux browser ASAN sysroots are not
# inputs to this Android AAR; this does not exclude any UMBRA test suite.
p=Path('depot_tools/cipd_manifest.txt')
s=p.read_text(); marker='@Subdir reclient'; assert s.count(marker)==1
p.write_text(s[:s.index(marker)])
Path('.gclient').write_text('solutions = [{"name":"src","url":"https://github.com/webrtc-sdk/webrtc.git","deps_file":"DEPS","managed":False,"custom_deps":{"src/buildtools/reclient":None},"custom_vars":{"checkout_instrumented_libraries":False}}]\ntarget_os=["android","unix"]\n')
PY
python depot_tools/gclient.py sync --nohooks --no-history --revision "src@$source_revision"
cd src
git apply --check "$repo/native/webrtc/reject-turn-redirect.patch"
git apply "$repo/native/webrtc/reject-turn-redirect.patch"
python build/linux/sysroot_scripts/install-sysroot.py --arch=amd64
python build/util/lastchange.py --source-dir . --filter= --revision-id-only -o build/util/LASTCHANGE
grep -F "$source_revision" build/util/LASTCHANGE
# Siso loads a backend descriptor even when every action is local. Supply no
# remote endpoint, credentials, cache or execution properties.
python - <<'LOCAL_SISO'
from pathlib import Path
Path('build/config/siso/backend_config/backend.star').write_text(
    'load("@builtin//struct.star", "module")\n'
    'def platforms(ctx):\n    return {"default": {}, "large": {}}\n'
    'def configs(ctx):\n    return []\n'
    'backend = module("backend", platform_properties=platforms, configs=configs)\n')
LOCAL_SISO
mkdir -p "$repo/native-output/$abi"
python tools_webrtc/android/build_aar.py --arch "$abi" \
  --output "$repo/native-output/$abi/webrtc-$abi.aar" \
  --extra-gn-args 'use_remoteexec=false' 'use_reclient=false' 'symbol_level=0' \
  --extra-ninja-switches='-j2'
# Keep the resolved dependency revisions and the patch alongside the binary.
python ../depot_tools/gclient.py revinfo > "$repo/native-output/$abi/dependencies.txt"
git diff --binary > "$repo/native-output/$abi/source.patch"
cp LICENSE PATENTS "$repo/native-output/$abi/"
cp out_aar/"$abi"/args.gn "$repo/native-output/$abi/"
printf '%s\n' "$source_revision" > "$repo/native-output/$abi/source-revision.txt"
printf '%s\n' "$depot_revision" > "$repo/native-output/$abi/depot-revision.txt"
cd "$repo/native-output/$abi"
sha256sum * > SHA256SUMS
