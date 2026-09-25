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
