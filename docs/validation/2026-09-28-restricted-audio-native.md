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

## Native correction verified on 6bef5e8

Privacy 36451474410 SUCCESS debug AND R8, connected AND offline. Integration
checkout `840b4dde547d5d46faff9fd5f26ef5fdb855ad7e`, source HEAD
`6bef5e8fd391bd150842c830edeb3d1c95ed87b8`. Eight Android cases per flavor and
actual host force-stop consumption scenario passed in each matrix. AAC output:
18 access units, 18432 decoded samples, final-marker spectral fraction
0.9972409152, RMS 5644.33. Earlier tail-loss failures remain recorded above.
This validates the narrow synthetic codec pipeline, not native recording,
output-device playback, physical speech or HTTPS/RFCOMM notes yet.

Artifacts:
- debug 10983998000: `33e125f2fd0f59fca98e3f12f43a44b226d3171f62048027612123240fcc87af`
- R8 10982684819: `f3505d692bb1866d0e4bc5edccb4f33dbb1206d2ebeabfef136dd60cf3e7826d`

Local complete JVM: 249 connected / 192 offline, zero failures/errors/skips;
Gradle exit 0 (1m8s). Both R8 vaultLab app/test APK builds exit 0 (1m7s).
186 Python tool tests passed. These are separate from Android execution.

Full-history backup `umbra-security-content-6bef5e8.tar.gz` exported to
`C:\Users\Usuario\Downloads\UMBRA_RESPALDOS_CODEX\` without overwriting originals.
SHA-256 `ce0f715050fb5ee51e072dca7c96a5a3545be16e29d1a041c3dbecd7f6bf29a3`.
Recovered HEAD exactly matched; git fsck --full exit 0, no bundle prerequisites.
It does not include subsequent playback/capture/nearby test work.

## Other checkpoint regressions, kept separate

On aa37375 Verify 36449076139 executed 56 connected Android cases with exactly
one failure: the same pre-correction AAC sample count (15360). Offline not reached.
Artifact 10983716170 SHA-256
`0e2457427c94709229213d161a32e1daeb51115f902e2facc9f67155908373bd`.

Focused 36449076146 nearby job SUCCESS: three connected and three offline
RFCOMM exchanges with new PNG decoder/consumption assertions, plus unadmitted
rejection in both. Artifact 10982917881 SHA-256
`afd4722023008f7c2880a66496447319348ea3d47bd386eaebd70a76fc2c0124`.
This is the emulated Bluetooth stack and synthetic records, not physical radio or
production Vault persistence. Whole focused workflow status must be checked separately.

Modulation 36449076084 R8 failed expected effective mode. Diagnostic recorded
ACTIVE / ERROR_MUTED, faults=1, maxBlockNanos=10756631, processed=752 on A; B was
OFF/faults=0. Processor budget remains 10000000ns, and its code fails muted above
that wall-time bound. The source of the excessive delay is NOT established by
these aggregate metrics. No threshold, fail-closed behavior or native binary was
changed; future passes do not prove this intermittent failure corrected.
Artifact 10983685512 SHA-256
`35f5846a87d5541b1ffc9fcf226eacc9f581655eb0f67238358669fd94407067`.

## d875eb7: two new acceptance failures, not suppressed

Privacy 36452724276 debug artifact 10983279814:
SHA-256 `1364ab07c8dd3b52c60766d192e296e5e5ed9a48127bfd10b11e1fa506111124`.
Ten connected cases, two failures; offline not reached in this matrix.

1. RestrictedPlayback called setPreferredDevice before setDataSource. It returned
false at line 42, before playback. AOSP native MediaPlayer reports NO_INIT without
its underlying player. Move selection after prepare, still volume zero and before
start/focus; retain the return-value and actual-route checks. Native regression
must prove route-confirmed PLAYING before lock and confirmed closure after it.
Source: https://android.googlesource.com/platform/frameworks/av/+/e1368e4257fa747e78eee204f136e67e176fbef9/media/libmedia/mediaplayer.cpp

2. AVD AudioRecord capture completed, was encoded/Signal-transferred/decoded, but
its output was not exact zero PCM as the new fixture assumed for -no-audio. This
is not evidence of a physical microphone and also not yet an accepted capture
fixture. Preserve the rejection, add synthetic peak/RMS/sample-count diagnostics
(no PCM saved), and determine whether emulator HAL output or lossy-codec behavior
invalidates that test assumption. Do not silently lower the assertion or claim
speech/intelligibility tested.

HTTPS note harness is implemented next with two independent Engines in one AVD,
local HTTPS relay, explicit admission/verification/network consent, native AAC,
duplicate transport and persistent consumption. Test CA is local to instrumentation
and hostname verification is unchanged. It is not two Android processes, and its
new CI must execute before acceptance. No production UI or offline networking.

Tool-suite correction: adding playback/capture changed exact instrumentation
counts to connected 58 / offline 55. The runner was updated but its synthetic
report regression still expected 56/54; the first cumulative run failed two of
187 tool tests. Update those fixtures to the actual new totals and explicitly
reject the historical 56/54 summaries. No minimum was reduced or test skipped.
The corrected complete 187-test tool suite passed (exit 0).

## a5a931b acceptance: HTTPS passed; recording reference still needed

Privacy 36453551593 debug artifact 10984741309 SHA-256
`edc81eb874b2765921ece4e20a3bea1559fa6dcae2400c30868615ccd5a4b1d2`, checkout
`0117df32ac1a485e2626c7135c2f52d682b54660`. The new HTTPS fixture passed with its
three packaged JNI regression tests: two Engine stores in one Android process,
real relay TLS/admission, native AAC, both directions and consume/reopen rejection.
This is not yet a green privacy workflow: two of ten subsequent Android cases failed.

Playback now reached actual route-confirmed PLAYING and its closure future
completed after lock. The assertion wrongly required only CLOSED although a
source/routing callback can first report INTERRUPTED. Closure confirmation is a
separate contract. Retain positive PLAYING, successful native closure, no restart
and no reopen checks; accept the documented terminal interruption outcome and
require it to remain stable in the observation window. Production terminal state
updates now use atomic transitions so late callbacks cannot replace a terminal
outcome or re-enter playback. This does not turn failed native release into success.

Captured decoded statistics: 17408 samples, peak 10, RMS 2.54759. Exact-zero
assumption remains red pending a controlled zero-PCM reference encoded and decoded
through the same native codec. The next fixture reports both measurements, stores
no PCM, and retains the rejecting assertion until the source of the discrepancy
is demonstrated. No change to production capture permissions or input routing.

Local e855363: four APK builds passed, APK policies/integrity passed for both
flavors/debug/release. Initial test_local invocation used the wrong default JDK
and failed at --release 21; log preserved. With explicit JDK21 it passed 204 backend
cases, 20 utility scenarios, 85 security scenarios and 13 static source checks.
The backend retains one Starlette TestClient/httpx deprecation warning; no
unreviewed dependency update or suppression was introduced.

Exported full-history backup `umbra-security-content-e855363.tar.gz` SHA-256
`aacae2ac55a0442938f3b870874a32e9f839987f7d7b262008a5b14bf361cd20` in Windows
Downloads/UMBRA_RESPALDOS_CODEX. It includes master text, recoverable bundle,
validation, exact HEAD/tree and CI/APK hash snapshot. Isolated restore matched
HEAD and fsck passed. Later working changes require their next backup.

## Decoder initialization closure and 13c89ad capture control

Checkpoint `13c89adf55e4f22ef1e3b00de7a20f5e322bbdb8`, privacy run
36454508257, tested checkout `5140dbf7b5fc9dc8a39b806d2eeaf335f24f0ada`:
**FAILURE in both debug and R8**, exactly one of ten connected cases.
Playback now reached its positive routed state and confirmed closure; both
force-stop receipts and connected real-HTTPS note fixtures completed. Offline
was not reached in this run and is not counted as passed.

The zero-input native codec reference returned peak 0 / RMS 0 in both matrices.
Captured/decoded AVD input instead measured debug peak 11 / RMS 3.701848597081502
(21504 samples), R8 peak 10 / RMS 1.5402281738044374 (21504 samples).
This **disproves the proposed codec-only silence-floor explanation**. The exact
capture/HAL cause remains unresolved. The original zero assertion remains;
no tolerance was loosened. The next fixture adds a bounded, separate raw
AudioRecord observation before the product recording, using the same route
and source, guarded by owned AVD and `-no-audio`. It exports statistics only,
wipes PCM, and is absent from production. This is diagnostic, not a fix or
physical microphone validation.

Artifacts downloaded through authorized GitHub access:
- debug 10984903033: SHA-256 `4155dd27fcad43f4aec974a2fe5d4c4c001f3f69060c163cf4f0f76c95ecb2a4`.
- R8 10985412631: SHA-256 `2c0a597c8f9369fc0c72489e800a6479385849e504407f1278fdc6ba6f6ba0fe`.

Independent common-engine regression: a decoder initialization exception left
an already consumed session authorized until its deadline. The new JVM test
failed before the fix with TimeoutException waiting for closure (Gradle exit 1,
`decoder-init-before.log`). The engine now invalidates and closes on initialization
failure without undoing consumption. Playback additionally attempts every owned
cleanup independently; a cleanup failure before resource registration is reported
to the session so its closure cannot falsely succeed. A synthetic failing-cleanup
regression checks exceptional closure and persistent consumption; it is not a
claim of reproducing a native MediaPlayer release failure.

Local validation of this patch, Python 3.13.12 / JDK 21 / pinned Gradle 8.13:
- `:app:testConnectedDebugUnitTest :app:testOfflineDebugUnitTest --tests app.umbra.RestrictedContentTest`: exit 0; 12 tests per flavor, zero failures/errors/skips.
- `:app:assembleConnectedDebugAndroidTest :app:assembleOfflineDebugAndroidTest :app:lintConnectedDebug :app:lintOfflineDebug`: exit 0.
- `python scripts/repository_guard.py`: exit 0, 444 source files, not a security audit.
- `git diff --check`: exit 0. No production UI file changed.

Preservation: previous e855363 archive hash rechecked. Full-history 13c89ad
bundle cloned in an isolated repository; explicit branch checkout and
`git fsck --full` succeeded. A bundle without symbolic HEAD requires
`git clone -b codex/security-content-completion history.bundle NEW_DIRECTORY`;
the initial unqualified clone could not select HEAD and was not counted as a
successful recovery. Export verified byte-for-byte at
`C:\Users\Usuario\Downloads\UMBRA_RESPALDOS_CODEX\umbra-security-content-13c89ad.tar.gz`,
SHA-256 `13f53d1d2cc8dc61c569f7fa4997e0d45cc1e2e5373e8606a83317da1de10860`.
Its adjacent RESTORE file documents explicit branch selection. The archive
contains the initial uncommitted decoder regression, not the subsequent fix;
original backups remain untouched. A/B remain partial and C remains pending.

### c61abb8 follow-up: raw capture separates the fixture assumption

R8 privacy run 36455601965 / checkout
`30495c310cbfaa88607b8ff6ad14f2865d1a552f` again failed only the exact-zero
capture assertion (10 connected cases). Artifact 10985418421 SHA-256
`1fb1ecbf04ac672715a06ad012d353b3d52b9c01f770231572b60875c3e52eef`.
Raw AudioRecord before the product codec: 4096 samples, peak 8,
RMS 3.797614382740828. Product capture after AAC/Signal/decode: 20480 samples,
peak 10, RMS 3.770775782249589. Known-zero PCM through AAC: peak/RMS 0.
The assertion's assumption is demonstrably false before product encoding.
It is NOT evidence that the product captures the host microphone, nor proof
of the particular HAL/resampler component introducing those small values.
Attempts to inspect the relevant upstream source returned HTTP 503; no source
hypothesis is recorded as a demonstrated cause.

Test correction: exact-zero remains required for the explicitly zero PCM
control; actual AVD recording must produce bounded decodable samples, preserve
permission/consent/original-lease rejection, and retains raw/decoded statistics.
The owned-emulator and host `-no-audio` requirements remain unchanged. No
amplitude tolerance was invented or raised, and this test is not an acoustic
privacy/amplitude test. Separate known-tone tests still verify AAC frequency,
energy and tail preservation. Native verification of this correction is pending.

Full local JVM on c61abb8: 251 connected / 194 offline, zero failures/errors/skips.
Four production APK builds and both APK/JNI policy scripts returned 0. Offline
permissions still exclude INTERNET/ACCESS_NETWORK_STATE/RECORD_AUDIO/CAMERA.
SHA-256 (local c61abb8, not future CI artifacts):
- connected debug: `db9fc9dff23350428214ae6b081c87302251acc4a4eda704706ebc5d3e48af16`.
- connected release unsigned: `c9e0d3fa994048dc4d367582d52980f687143cd373c9e175a9916351420dd0a8`.
- offline debug: `cbc50a22a6271bfd7c50a2d165e07f1fef84c17b673bb9c1df5101738e1ee3f2`.
- offline release unsigned: `6b6c2a976cb5e5e564c23d9f9d6fcc4ebc539f65deddfb43e2bb23b3195a9d31`.

Full-history c61abb8 backup exported to Windows Downloads/UMBRA_RESPALDOS_CODEX,
`umbra-security-content-c61abb8.tar.gz`, SHA-256
`b750bc047a5f9c22ea89c7f697945ebb9f0bbc1e25c809229e0a934c19737a46`.
Explicit branch clone, exact HEAD and fsck verified. Includes then-pending four
contract-document diffs, not this subsequent capture assertion correction.

Debug of the same c61abb8 run independently confirmed pre-codec nonzero input:
raw peak 8 / RMS 4.032612415125585; decoded peak 11 / RMS 3.9148875091832234,
17408 samples; zero reference remained zero. Artifact 10985269031 SHA-256
`ed736736244b8237ca433af8efe31c58fe939a57108cde2448cc9de28b942c4a`.
Capture-test correction compiled and linted locally (exit 0, 22 seconds).
The runner still starts AVDs with `-no-audio` in `scripts/ci_emulator.sh`;
no production audio source, codec, security threshold or offline permission changed.

### 126ac2f native acceptance of the capture-test correction

HEAD `126ac2f2d07c2e0ec4227cb961d7007643abcc08`, privacy run 36456403088,
checkout `20d39152149271190fb95621e505999878f2d051`: **SUCCESS debug and R8**.
Each matrix completed connected 10 tests and offline 9 tests, plus the separate
force-stop receipts and connected HTTPS fixture. This validates the scoped
capture/codec and playback checks, not physical microphones/speakers, hardware
Keystore, all route-loss conditions or the complete master.
- debug artifact 10986435468 SHA-256 `d0ea7c196137d088294f80bdf5f4debf808b3b0b524c5f017f75017fe81bf656`.
- R8 artifact 10985339769 SHA-256 `536e22190cc3026a763a58758dc3039c8a62045953b7ccebd60cf2eb2d900ffe`.

Next independent coverage correction: the prior tone test called preparation but
checked frequency/tail on the original encoding. It now generates synthetic ADTS,
prepares it through the real decode/re-encode API, delivers the **prepared** copy
via Signal and verifies its decoded frequency/tail after persistent consumption.
No thresholds change. The small internal encoder-byte primitive remains package
private; no public raw-content/export API is added. Temporary codec buffers are
also wiped in nested finally blocks even if native release throws. Local builds
of both test APKs and lint passed (27 seconds); new native result remains pending.

126ac2f focused run 36456403163: **nearby job SUCCESS**, three positive exchanges
per flavor and an unadmitted rejection per flavor. Both native endpoint receipts
explicitly include restricted PNG and native AAC decoded/consumed plus duplicate
and ACK checks. Artifact 10986740961 SHA-256
`bab81e7fd47885821fe258c6dee116af200c06251622a82c58404e6f7861e671`.
This is real emulated RFCOMM, not physical radio or Vault durability; other jobs
in the focused workflow must be evaluated separately.

Local optimized build for the pending sanitized-copy test: the first command
omitted `-PumbraVaultLab=true` and failed before compilation because the tasks
were absent (exit 1). With the project's documented property, both vaultLab apps
and test APKs built (exit 0, 1m10s). This working-tree build is not final Android
runtime acceptance. Preserve both logs; no project version/dependency changed.
