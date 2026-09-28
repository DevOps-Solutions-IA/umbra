# Restricted notes — native work in progress, not master acceptance

Published starting point bde7abb61c7b18d403df62348cbe0b638bc96aa4.
Privacy run 36447793205, checkout 52bc4f49afac1aceb07638b21d31711040d67272:
FAILURE in debug and optimized R8. Seven of eight connected methods passed;
positive AAC test failed its decoded-sample length assertion at line 83. Native
encoder/extractor/decoder and Signal/SQLite executed, but duration acceptance did
not pass. Offline was not reached by that failing workflow and is NOT EXECUTED.
Do not report the pipeline accepted by a successful build or frequency assumption.

Artifacts preserved locally under `.run/security-content/privacy-bde7abb/`:
- 10981596391 debug: sha256 `302789c97525a7678eeae6f4ced5d81fc5c419f631ac3963b60ac7beb3e9f3bd`
- 10981054884 R8: sha256 `ede59baed5e1f2b0056a54412241eb97095ecf491f6b6a56ee8a374aa275a331`

Candidate correction sends EOS as a separate empty input buffer after submitting
all PCM instead of marking the last partial PCM buffer EOS. This still requires
native verification; exact cause is not established from the old generic assertion.
The test now emits sample count/RMS before asserting, with separate bounded-length
reasons. No original duration/energy/frequency assertion was lowered or removed.

## Independent work

Connected-only RestrictedRecording uses existing reviewed recipient/vault lease,
permission checks, AudioRecord PCM16 mono 16kHz, a chosen input route and <=8s
RAM capture; native AAC encoding follows release of the microphone. It registers
with the existing emergency coordinator before allocation. Cancellation or stale
lease discards output, release failure leaves a failed closure. No background
service, second network connection, WebRTC modification or new permission.
This adapter has compiled/linted; native capture/permission/route tests and physical
microphones are NOT accepted. Automated tests must never use a real microphone.

Preparation consent has JVM regressions for no confirmation, wrong service owner,
blocked recipient, old unlock generation and no automatic network connection.
Connected/offline JVM passed locally (exit 0, 1m10s), log
`.run/security-content/capture-consent-tests.log`.

RFCOMM fixture now sends an independently colored synthetic restricted PNG from
each endpoint, including its existing duplicate transport, checks decoded peer
pixels, persists consumption, rejects reopening and checks no ordinary-history
entry. It retains identity/admission/challenge verification and original deadlines.
Built both instrumentation APKs and lint (exit 0, 27s); actual RFCOMM execution for
this extension still requires new CI. Existing successful RFCOMM results did not
send restricted PNG. AAC RFCOMM remains pending until codec acceptance.

185 tool tests passed (exit 0); repository guard passed. Exact logs under
`.run/security-content/`. UI handoff documents are evolving contracts, not a final
API freeze or proof that Claude's windows/screens enforce the adapters.

## Recoverable exported checkpoint

`umbra-security-content-bde7abb.tar.gz` copied to Windows
`C:\Users\Usuario\Downloads\UMBRA_RESPALDOS_CODEX\`.
SHA-256 `9d880b314782b1adc9a8854d9007e27f0551305c9edd54f06adb31831188c788`.
Full-history bundle without prerequisites; cloned into /tmp/umbra-restore-bde7abb,
HEAD matched and git fsck --full exited 0. Prior backups remain untouched. This
archive covers bde7abb, not later recording/RFCOMM/contract changes.

A remains under cumulative validation; B is partial; C video-file/PDF remains
unimplemented. No production, final UI, real-radio, acoustic or hardware claim.

## Second native attempt — aa37375

Privacy 36449076033 failed debug/R8 on checkout
c0dcddc2d3dbf5bfe548ad213db53a2459f06c68. Both decoded 15360 samples from 16000,
RMS 5645.93. Separate EOS alone was not a correction. Artifacts:
10982002865 debug (`b3c7da214260c2fb38cd777f20c03364c0ee5147e1d2cef65d6e203074fc3c37`),
10982386800 R8 (`18a9ef0faae331bd3fb6e15fd06b21bb6730c1f7b191fa74e04ab6f63babc51a`).
The next test retains the original assertions, records extracted AAC frame count,
and adds a distinct final 40ms synthetic marker to detect actual tail loss, not
just total duration. Input alignment zero-fills the last 1024-sample access unit;
this is a candidate fix until native execution, not an accepted codec workaround.
Android C2 AAC source reviewed for EOS handling:
https://android.googlesource.com/platform/frameworks/av/+/dbda76adf06a0df34edd68fab017031e95ddb40c/media/codec2/components/aac/C2SoftAacEnc.cpp
No encoder, dependency or test threshold was replaced to hide the failure.

## Third attempt — 299049b: duration alone concealed missing tail

Privacy 36450536633 failed. Debug artifact 10983726492 SHA-256
`84204d7c1ad7cc789f51fa5da26815f5e0633842255b5bf3186fc800959b6c26`.
It produced 16 AAC access units / 16384 decoded samples, but final-marker spectral
fraction was 0.00010624 (required >0.6). Alignment repaired sample count, NOT the
lost final content. The new assertion prevented a false pass.

Candidate next correction restricts encoding to the AOSP software
`c2.android.aac.encoder`, fails closed if absent, and supplies two final zero AAC
blocks before EOS. This is bounded codec drain, not relaxed observation: the
original 40ms marker, energy and duration assertions remain. AOSP FDK AAC-LC
MDCT/block-switch delay is 1600 samples (1024 + 4.5*128); two 1024-sample blocks
cover that delay. OEM codecs are not assumed equivalent. The input bound reserves
three blocks within the existing nine-second decoded-memory cap (one alignment,
two drain). Recording remains <=8s. AOSP source reviewed:
https://android.googlesource.com/platform/external/aac/+/master/libAACenc/src/aacenc_lib.cpp
The exact emulator system-image source revision has not been independently
reconstructed; native tests must still verify the output, not source inference.

Independent local checks: content error presentation contract passed both JVM
flavors (exit 0); restart-fixture instrumentation build and both lints passed
(exit 0). Three privacy runner validator tests passed. New host force-stop
acceptance is NOT executed locally: /dev/kvm remains unavailable (accel-check 11).
The fixture requires a positive decoded PNG before kill, then persisted consumption
and duplicate rejection after restart. It uses synthetic plaintext test SQLite,
not a production Keystore fallback and not death during commit.
