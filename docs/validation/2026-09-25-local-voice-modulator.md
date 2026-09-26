# Local voice modulator — working evidence, 2026-09-25

Status: PARTIAL; remote voice debug executed, remaining acceptance/CI in progress.
PR #10 is draft, dependent on #9 (`codex/turn-video-core`). Base #9 HEAD checked
`26d7326a8b2a43ee9533284d20896727ad8bb3ce`, still OPEN. Its historical green runs
are not evidence for this change. No main/prior-branch writes or merge.

## Native provenance and executed checks

Published initial DSP/native commit: `32ab6c6a75372e7f414b4da684f2f5ba0b94ea98`.
Native Actions 35825968400: all four ABI source builds SUCCESS; downloaded receipts
and SHA-256 checked. Source patch matches the inspected local pinned checkout:
`6e253877d0381cc66486890a19b8b59a8002af8cd4d49e33c1c86ef90ac12df5`.
Source `73cb8180f7258ee292878d6edd05177f41883962`; all prior security patches kept.
83 selected TURN C++ tests and 12 selected media-model tests executed/passed, not
all upstream tests. Five standalone DSP test groups executed, covering formats,
OFF fidelity, sidebands, limiter, malformed input, authorization, sticky failure,
confirmation, mute, close, stale generations and concurrent control changes.

Adopted `.4` AAR SHA-256:
`771d17b79c1553baab2dd1747270eea70428b63e804a29011a282bece886cd29`.
ABI hashes, dependency receipts and license inventory: `android/webrtc-artifact.json`.
Original DSP MIT license included in the AAR. No new external DSP dependency.

Verify 35826003642 passed for **32ab6c6 only**, before Android adoption. It is not
final integration acceptance. Local Android code/artifact changes are pending
final published SHA and CI.

## Executed local commands and results

- `bash scripts/codex_setup.sh`: exit 0 on initial baseline.
- `. .venv/bin/activate`; `bash scripts/test_local.sh`: baseline exit 0.
- Latest script suite: 141 tests, exit 0; evidence-gate tests are not media tests.
- `python scripts/repository_guard.py`: exit 0 with exact new artifact allowed.
- `python scripts/build_android.py --check-only`: exit 0, not a build.
- `bash native/webrtc/voice-modulator/test.sh`: exit 0.
- Gradle 8.13 connected debug + AndroidTest builds: exit 0 with the real `.4` AAR.
- `python scripts/run_voice_integration.py --a emulator-5554 --b emulator-5556
  --modulation --reports /tmp/umbra-modulator-voice-debug-2`: exit 0.

One repeat of `test_local.sh` used the shell's inconsistent Java selection and
failed `release version 21 not supported`. Repeated with explicit
`JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64` and its bin first in PATH: exit 0.
No source relaxation. Python virtual environment remains activated for tests.
Project versions remain SDK36/min31, AGP8.13.2, Gradle8.13, JDK21, libsignal0.102.3.

## Real remote audio, debug working tree

Two independent Android35 AVDs, synthetic capture injected before APM, real
Engine/SQLite/libsignal, HTTPS relay, native WebRTC/Opus and authenticated coturn.
Remote decoder measured 1000 Hz natural input or the specified 900/1100 Hz
sidebands; opposite direction carried its independent 2000 Hz signal.

The first run failed its new observation gate: only 27 matching callbacks in a
one-second window, below the required 40. Failure retained in local logs. No
threshold reduced; changed observation to a positive 2–3.5 second bounded window,
with 1.2–2.5 second bounded allowance for previously admitted codec/network audio.
Actual successful callbacks: 480 samples at 48000 Hz. These observations do not
prove cancellation at the instant of a UI action or hardware scheduling bounds.

Successful sequence, remote matching blocks per observation:

| Action at A | A effective | B natural | B modulated | B energetic |
|---|---|---:|---:|---:|
| Enable | ON | 0 | 204 | 204 |
| Mute | ENABLING | 0 | 0 | 0 |
| Confirm OFF while muted | DISABLING | 0 | 0 | 0 |
| Request ON while muted | ENABLING | 0 | 0 | 0 |
| Explicit unmute | ON | 0 | 208 | 208 |
| Reject unconfirmed OFF | ON | 0 | 197 | 197 |
| Confirm OFF | OFF | 204 | 0 | 204 |
| Enable again | ON | 0 | 201 | 201 |
| Local processor invalidation | ERROR_MUTED | 0 | 0 | 0 |
| Explicit retry | ON | 0 | 204 | 204 |

Reverse direction retained natural audio in every window. Processor states are
acknowledgements of current generation; a muted pending mode is not falsely ON.
The forced failure tests native sticky error handling, not recovery from an OS
process crash. No synthetic callback bypasses the production APM/DSP.

## Timing and limits

Host DSP: 10,000 blocks; approximately 1.4 us mean wall/thread CPU; measured object
2224 bytes; histogram p99 upper bound 4 us in one run. Impulse stays at its sample
index: no algorithmic lookahead. Not Android end-to-end latency or physical CPU.
One AVD endpoint's native totals at the final stage: 2687 blocks, 8,157,730 ns wall,
5,011,330 ns thread CPU, maximum 54,584 ns, one deliberately injected fault,
zero clipped samples. Counters only; no PCM files or biometric profiles.

NOT EXECUTED at this receipt stage: initial ON remote acceptance, video-modulated
remote acceptance, R8 modulation, final complete CI and final APK policy checks.
These are active work, not passes. Physical microphones, speakers, intelligibility,
hardware Keystore, biometric anonymity and exact production-APK media acceptance
are not claimed. The separate mediaLab R8 target is not the production APK.

## Bootstrap correction and `.5` adoption

Initial MODULATED remote acceptance failed with `.4` (`Initial mode not effective`).
A new deterministic C++ regression reproduced the rejection of supported APM
bootstrap formats before any admitted PCM (exit 134). Commit
`2040a2c92dd8e0408556fb13a3230b50292dc46b` allows only that pre-admission bootstrap;
unsupported formats still fail, and changes after admission remain sticky-muted.
The same regression passed after correction. Tests also cover extra synthetic
frequencies/amplitudes and reentrant processing (must silence, not wait/bypass).

Build 36187887900: four ABI SUCCESS, 83 TURN + 12 media C++ tests validated from
its downloaded XML. Local source patch and all ABI receipts agree on
`41df998e0088d3bf675c77547e87b10a5f825038b5297119946cd7d14cf02017`.
Final candidate `.5` AAR SHA-256:
`25f2abebc99e2e109cff83a428080408843fda51a9cdadb5c081d694c92b7620`.

`--modulation --modulated-start` with `.5` exited 0 on two Android35 AVDs.
Before the explicitly confirmed natural-voice transition, B decoded 105 modulated
blocks and zero natural blocks; A decoded 111 independent natural blocks. All ten
subsequent mode/mute/error/retry stages passed. No initial dry frame was detected
by this synthetic spectral acceptance. This is not a proof of biometric anonymity.

Earlier `.4` remote voice/video tests also passed in debug and R8, but are only
intermediate regression evidence. `.5` optimized APKs compiled successfully;
its remote video/R8/lock/revocation and final CI are still pending at this commit.
The workflow adds four isolated cases per debug/R8 matrix without removing the
existing Verify, voice R8 or 31-case video matrices. Final run IDs and the exact
published HEAD/integration checkout will be attached to the PR after completion;
no earlier green is attributed to that final HEAD.

## Continuation receipt: integrated `.5`, 2026-09-25

This section supersedes the pending execution status above for the explicitly
listed checks only; the intermediate `.4` history is retained.
Published application HEAD: `383fc37f5ccfbea4e19c384a590bf8a019f34bef`.
Actions checkout: `7839081afb9c138c6413d7ef5cca1cfd5481ef79`.
Both trees: `064b1089e847679abe06b3828b7f00c9ee939d28` (GitHub commit API and
artifact commit.txt checked). Modulation run **36202456119 SUCCESS**, both
matrices: initial-modulated voice, active video, lock and device revocation.
Eight real two-AVD flows, ten mode/mute/error/retry stages each. Downloaded all
eight processing receipts; they are native remote-decoder evidence, not mocks.

Additional local `.5` execution, exit 0 for each: the same four cases in debug
and optimized mediaLab. R8 mediaLab executes optimized production classes but
uses isolated lab identity, persistence adapters and synthetic sources: it is
NOT execution of the exact production APK with hardware-backed Keystore.
The final local R8 repetition includes the small diagnostic-only changes following
383fc37. Its reports are `umbra-modulator-final-{initial,video,lock,revocation}-r8`.

Local checks on this integrated implementation, all exit 0:

- Python 3.13.12 virtualenv, JDK 21.0.11 explicitly selected; Gradle 8.13,
  AGP 8.13.2, SDK 36/min31/build tools 35.0.0; libsignal 0.102.3 unchanged.
- `bash scripts/test_local.sh`: backend 149, pure core 105 behavioral checks;
  source/syntax policy checks are counted separately.
- `python -m unittest discover -s scripts/tests -p 'test_*.py' -v`: 141 tests.
- `python scripts/repository_guard.py --git-history`: source/history checks.
- `python scripts/build_android.py --release`: both debug/release variants,
  lint, manifest/APK policy, JNI, DEX and packaging; JVM 157 connected and
  117 offline, no failures/errors/skips. Offline excludes network/media permissions
  and WebRTC. No production signing keys.
- `python scripts/test_relay_integration.py`: 28 real HTTPS integration checks.
- `bash native/webrtc/voice-modulator/test.sh`: five standalone test groups;
  separate host ASan/UBSan execution also passed (not Android sanitizer coverage).

### Failure retained and diagnostic improvement

One local `.5` device-revocation scenario failed BEFORE applying revocation,
at confirmed OFF (step 6): receiver observed no energetic/natural/modulated
blocks. Instrumentation reported process termination; collected Android exit
information also contains host cleanup force-stop, which does not establish the
original cause. Root cause is NOT CONFIRMED. No threshold/deadline was relaxed.
Added lab-only last-state diagnostics (effective state, step, failure stage,
fault count, maximum block time) and bounded sanitized collection during cleanup.
The repeat passed, as did subsequent R8 and both CI matrices. This is an
unreproduced failure with better diagnostics, not a claimed product fix.

### Measurements and boundaries

Four successful local debug flows: processor mean wall time 2.9–4.3 us/block,
thread CPU 1.65–2.27 us/block, maximum 0.34–1.06 ms. Synthetic tones had no
clipping. Each sender had one deliberately injected failure; reverse endpoints
had zero. Whole-process PSS approximately 53–75 MiB, native heap 12–34 MB;
these include Engine/WebRTC/video and are NOT incremental DSP memory.
Host processor object 2224 bytes; fixed oscillator storage, no PCM history or
algorithmic lookahead. Impulse retains its sample index. This meets the added
algorithmic-delay goal for this DSP, not an end-to-end audio latency claim.
The four local R8 sender histograms gave p50 upper bound 4 us, p95 4–8 us,
p99 8–32 us; mean wall 2.6–3.6 us, CPU 1.5–1.9 us, maximum 87–336 us.
These cumulative buckets include OFF and MODULATED blocks; they are not isolated
ON-only percentile estimates. Scheduler maxima are observations, not hard
real-time guarantees.

For local lock and revocation: capture quiet after 1000–1001 ms followed by a
positive 500–501 ms observation, zero late capture callbacks. This is a bounded
observed cancellation window, not immediate withdrawal of codec/network buffers.
Video stages continued decoding 30–32 remote frames per two-second window with
independent reverse audio. Quantitative audio/video lip synchronization remains
NOT EXECUTED; simultaneous decoding is not a lip-sync measurement.
Physical microphones, intelligibility, speakers/headsets and hardware Keystore
remain NOT EXECUTED. No biometric anonymity claim or new IPv6/TLS claim.

CI report hashes (SHA-256 of voice-processing.json, run 36202456119):

| Mode | Case | SHA-256 |
|---|---|---|
| debug | initial voice | afb9d6a88425c8e17b22798a7852237d6bf1493c16e49bb52421a1502b4ffa51 |
| debug | video | 37529f26780150b70aab51f6cbcab564b815bff938bf2e579c6eb01d7dd843ce |
| debug | lock | 027148e6bb09ed416929da3bf7e86bd2fc3ba13d2a744ff93e200b4c59b14f32 |
| debug | revocation | 7978416f957f583de9d27fc543cd099c96adec27c19d0344fd36b1bec11a8090 |
| R8 | initial voice | 85686a19bff6c7a3f81b65e148b75b6b9910ca7d554a5f657e32b2206c67ca96 |
| R8 | video | 0dbeba00510225160b5c79cb11c609c81af2c7f7783ada356245048376435a32 |
| R8 | lock | 9278b7f978fec3a3601c42ebc6ded8bca54b7d5e64198ff53c369c0843f3d437 |
| R8 | revocation | 73e3975a1fce06b7a5b3b30e6eba61063be4d5bb5c643900c559f7004424c86f |

General Verify/voice/video regressions are still running at this receipt.
The subsequent diagnostic/documentation commit requires its own final CI;
its published HEAD, checkout, trees and run results will be recorded on PR #10.
No earlier green validates that later commit automatically.

## CI terminal-diagnostic regression, 2026-09-25

HEAD `63862ccca5019662139e6a1f3cc69794bfe5ec07`, integration checkout
`d0d353d6e4557f467c1660797ecd93ec326144d5`, common tree
`26c71162c186bc89a511e6b937fd41cfbee968fe`:
Verify 36204852624 four jobs SUCCESS; voice R8 36204852616 SUCCESS (15 receipts);
modulation 36204852563 both matrices SUCCESS (eight ten-stage flows).
Video 36204852534: debug 31/31 PASS, R8 **30/31 PASS, wrong-fingerprint FAIL**.
That run remains a failed run, never a validation of the entire revision.

The R8 assertion reported terminal reason `authorization-or-signaling` instead
of certificate binding/peer closure. It asserted zero capture/decoding before
checking the reason. The periodic `tick()` calls `check()` even after cancellation;
`check()` throws, and the catch unconditionally overwrites the earlier failure.
Unlike posted native callbacks, periodic tasks lacked a cancellation guard.
The correction returns before polling a cancelled session and also from the
catch when cancellation raced the operation. It does NOT accept the generic
reason as proof of certificate rejection, bypass verification or extend deadlines.
The real rejection fixture now observes an additional positive 350 ms window
and requires stable FAILED/reason with zero audio/video capture or decoding.
CI supplies the before-failure evidence; local focused R8 repetitions and a new
full CI are required after this correction. No assertion of unauthorized media
exposure is made from this diagnostic race.

Additional source build 36202452752 SUCCESS in all four ABIs. Downloaded SHA256SUMS
checked; all source.patch digests equal the adopted `.5` patch. The strict XML
validator reports 83 executed TURN tests and 12 executed media tests, including
the explicitly enabled upstream-named DISABLED case. No native artifact replacement.

Post-correction local results: R8 mediaLab compile exit 0; three consecutive
`run_voice_integration.py --optimized --video --scenario wrong-fingerprint`
executions exit 0 on two Android35 AVDs. Each required at least one real native
certificate-binding rejection, neither endpoint captured/decoded, and the added
350 ms terminal-stability observation passed. This is three repeats of the same
regression, not three distinct new tests. Tools suite 141 PASS; repository guard
and five standalone DSP groups PASS. New full CI required for the published fix.
