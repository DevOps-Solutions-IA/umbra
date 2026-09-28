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
