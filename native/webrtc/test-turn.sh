#!/usr/bin/env bash
# Run the pinned TURN C++ suite; this does not rebuild/replace the Android AAR.
set -euo pipefail
: "${RUNNER_TEMP:?Dedicated ephemeral build directory required}"
repo="$(cd "$(dirname "$0")/../.." && pwd)"
source_root="$RUNNER_TEMP/umbra-native-x86_64/src"
test "$(git -C "$source_root" rev-parse HEAD)" = 73cb8180f7258ee292878d6edd05177f41883962
test "$(git -C "$source_root/../depot_tools" rev-parse HEAD)" = ca054941f756b50e1a3d83727270d879bec1f331
cd "$source_root"
# The binary under test must contain both reviewed patches, not clean upstream.
git apply --reverse --check "$repo/native/webrtc/reject-turn-redirect.patch"
git apply --reverse --check "$repo/native/webrtc/java-generics.patch"
git apply --reverse --check "$repo/native/webrtc/customize-before-integrity.patch"
export DEPOT_TOOLS_UPDATE=0 DEPOT_TOOLS_COLLECT_METRICS=0
export VPYTHON_BYPASS='manually managed python not supported by chrome operations'
report="$repo/native-test-output"
mkdir "$report"
buildtools/linux64/gn gen out_turn_tests --args='target_os="linux" target_cpu="x64" is_debug=false is_component_build=false rtc_include_tests=true rtc_build_examples=false use_siso=true use_remoteexec=false use_reclient=false symbol_level=0'
third_party/siso/cipd/siso ninja -C out_turn_tests -local_jobs=2 rtc_p2p_unittests
cp out_turn_tests/args.gn "$report/args.gn"
sha256sum out_turn_tests/rtc_p2p_unittests > "$report/binary-sha256.txt"
git rev-parse HEAD > "$report/source-revision.txt"
git diff --binary | sha256sum > "$report/source-patch-sha256.txt"
# Include the upstream-disabled TURN test too; never report it as passed by omission.
# This is the TURN suite, not all p2p or all upstream WebRTC tests.
timeout --signal=TERM --kill-after=10s 15m out_turn_tests/rtc_p2p_unittests \
  --gtest_filter='TurnPortTest.*:TurnPortWithMockDnsResolverTest.*' \
  --gtest_also_run_disabled_tests --gtest_output="xml:$report/turn.xml" \
  > "$report/turn.log" 2>&1
python "$repo/scripts/check_native_turn_tests.py" "$report/turn.xml"
