# Pinned native build and TURN regressions

The Android pin is the reviewed four-ABI source build, not the original Maven AAR.
Running this recipe does **not** automatically replace that pin or grant consent.
The original Maven allocator followed TURN 300 ALTERNATE-SERVER without local approval.
The patch rejects all such redirects before changing the endpoint; redundancy must
use separately configured local TURN servers. Existing native redirect tests are
changed to assert rejection, unchanged destination and zero allocated candidates.
Four ABI builds passed in run 35577083313. The actual x86_64 AVD network
regression observed zero alternate traffic and bidirectional Opus. Modified C++ unit
tests themselves were NOT EXECUTED in the voice delivery. No cryptographic primitive or DTLS validation is changed.

The video branch adds `bash native/webrtc/test-turn.sh` after the x86_64 source build.
It separately builds the Linux `rtc_p2p_unittests` executable and runs the complete
TurnPortTest and TurnPortWithMockDnsResolverTest suites, including disabled tests.
It checks XML results and the presence of all 18 redirect regressions. This is not
the full upstream suite, nor Android TLS/IPv6 network acceptance. Consult the new
dated validation report for actual execution results; adding the command is not a pass.

`bash native/webrtc/build.sh x86_64` uses a fresh `$RUNNER_TEMP` directory, Python 3.12+
and the exact source/depot revisions in the script. Upstream DEPS pins toolchain/SDK
and third-party revisions/hashes. Only remote execution tools and Linux browser ASAN
library downloads are excluded from preparation; no UMBRA suite is excluded.
The build itself disables remote execution and telemetry. Four ABIs build separately
on standard runners. Artifacts include the patch, dependency revisions, license output
and hashes. They are build artifacts, not releases or a claim of bit-for-bit reproducibility.

Before replacing the distribution: review licenses and resolved provenance; compare
common Java classes across ABI outputs; normalize packaging; update the exact inventory;
execute native certificate rejection, bidirectional Engine/HTTPS audio and the real
unauthorized redirect regression with zero alternate traffic. Repeat APK/R8/JNI checks.
Never remove `NativeDistributionPolicy` based only on this compilation succeeding.

`java-generics.patch` corrects an upstream raw `LinkedHashSet` construction to
`LinkedHashSet<>`. The pinned compiler rejects the unchecked conversion; warnings
remain errors. This does not enable video or change codec selection behavior.

`package.py` verifies the reviewed per-ABI receipts, identical Java API and manifest,
ELF architecture and exact source patch, then normalizes ZIP metadata and includes
component license assets. It never grants consent or enables a native adapter.

The first C++ execution (35625739825) aborted in the upstream-disabled
`DISABLED_TestTurnCustomizerAddAttribute`: the customizer added attributes after
MESSAGE-INTEGRITY. `customize-before-integrity.patch` moves customization before
signing, retaining the authentication fields visible to the callback and including
its additions in integrity protection. The disabled test remains required and is
executed explicitly; no assertion or integrity check is removed. The Android AAR
is **not** replaced by editing this recipe: new four-ABI hashes and acceptance are
required before adopting its output.

Run 35630454176 rebuilt all four ABIs and **passed 83 TURN C++ tests**, including
the disabled customizer regression. The sanitized receipt is in
`docs/validation/2026-09-21-video-native-turn-receipt.json`.

`restrict-media-sections.patch` adds an independent bound over WebRTC's parsed SDP
model at Android JNI ingress: exactly one audio section, at most one video section,
no data/unsupported section, at most one stream per section. It does not parse or
rewrite SDP text. The recipe also compiles `rtc_pc_unittests` and executes the
focused UMBRA policy + upstream session-model tests. Run 35634570646 passed all
four ABI builds, 83 TURN tests and 12 selected media-model/policy tests. The `.3`
AAR and per-ABI hashes are recorded in android/webrtc-artifact.json and the dated
video-native-media receipt. This is not the full upstream suite. Java still checks
per-generation consent and allowed directions.

## Local voice processing overlay

`voice-modulator/apply.py` adds the original MIT DSP and per-call
`UmbraVoiceProcessor` capture APM factory to the same pinned source. New files
are included explicitly in the binary source-patch receipt. No global processor,
render transform or bypass setting is used. Build 35825968400 produced four ABI
artifacts and passed the 83 TURN and 12 media-policy tests. The `.4` AAR is pinned
in `android/webrtc-artifact.json`; its source patch matches the locally inspected
overlay byte-for-byte. The MIT license is included as a specific AAR asset.

`bash native/webrtc/voice-modulator/test.sh` exercises DSP/generation/failure
controls and reports host timings. It does not establish remote-media acceptance.
Android native/Opus/TURN tests and R8 runs must validate the adopted AAR separately.

`.5` (build 36187887900, builder 2040a2c) additionally permits supported APM
bootstrap reconfiguration before the first admitted PCM. A reproduced C++ test
failed before and passed after; actual initial-MODULATED remote audio passed on
the rebuilt artifact. Later format changes still fail muted. Four-ABI receipts
and the 83 TURN + 12 media tests were reverified; see the modulator evidence.
