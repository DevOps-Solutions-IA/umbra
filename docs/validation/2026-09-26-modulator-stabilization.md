# PR #10 stabilization — 2026-09-26

Status: PARTIAL. This continuation does not claim either failure corrected.

Base: `2d9ac6e06d145971b58c7a6f3f64a3d00d03f08a`, PR #10 rechecked OPEN/DRAFT,
base branch `codex/turn-video-core`. No base/previous branch changed.

The previous seven unpublished files were already applied; no patch reapplied.
Original patch SHA-256:
`3a89f820d29201dab8206ef0cb32575f8d8f0cb0d79da5254ed09a7c9d26ab7a`.
Persistent copy: `.run/modulator-preservation-2026-09-26/verified.patch`.
Export: `/tmp/umbra-modulator-preserved-2026-09-26.tar.gz`, SHA-256
`f7ced6d2a70d0def1b2afb6c7366640a1fd708bba92d691d037fabf8bdd5b35a`.
Both remain preserved. Applicability and byte-exact reconstruction of all seven
files were verified in a separate temporary directory, not the dirty checkout.

## Effective access and original failures

After the owner changed session permissions, gh PR reads and artifact downloads
work; git metadata is writable. /dev/kvm exists but is not readable/writable by
this user (not in kvm group). Local ci_emulator.sh exits 1 at the mandatory KVM
check. No permissions changed and no software emulation fallback attempted.

Downloaded video debug artifact from run 36207520910. Endpoint A throws
SecurityException: Call unavailable through MediaLease.snapshot/stopVideo and
NativeVoiceSession.stopVideo, fixture line 337. The driver was waiting for the
video stopped phase; permission revocation comes later and was NOT reached.
Endpoint B reports process crashed. This does not prove that a camera kept
capturing after revocation. The underlying terminal transition is not recorded
in the old artifact; root cause remains under investigation.

RFCOMM run 36207520866: listener deadline failed before enrollment barrier;
dialer log only announces startup. Android bonding is not application-level
challenge authentication. The old logs omit which handshake operation stalled.
Added fixed enum stage observations (no payloads or exceptions) for connection,
hello exchange, proof and authentication, plus the existing fixed status mapping.
The production protocol, cryptographic checks and deadlines are unchanged.

The video fixture now reports bounded native/video state immediately before
its explicit stop operation. This is diagnostic evidence, not a behavior fix.

## Local commands

With .venv activated: tool unittest suite 142 PASS; repository_guard PASS;
git diff --check PASS; build_android.py --check-only PASS (not compilation).
An initial direct system gradle invocation failed (old system Gradle); subsequent
commands explicitly use the project's .umbra-tools/gradle-8.13/bin/gradle.
No previous CI is attributed to these changes. Complete final CI remains pending.

## Deterministic video cancellation reproduction prepared

A test-only SQLite pre-transaction rendezvous pauses the native media worker's
video-authorization check, then performs explicit stopVideo before releasing
that check. The test still requires audio ACTIVE and remote decoded audio after
video off. No error is treated as success. The hook lives in androidTest storage;
it does not replace Engine, Signal, native WebRTC or TURN. Debug and optimized
instrumentation compile successfully. Actual execution remains pending.

The focused workflow retains three repetitions each of video-stop-race and the
original camera-permission-revoked case, in debug/R8. Every result is retained;
any failure fails the matrix. Existing workflows now queue rather than cancel
an active run when new diagnostics are pushed, preserving original evidence.

Full local build_android.py --release passed on diagnostic sources (before the
new rendezvous): debug/release both variants, JVM, lint, JNI/DEX/APK policy.
149 backend and 105 pure-core scenarios passed. Existing Starlette httpx
migration warning and MainActivity deprecated API notes remain visible.

## Video race demonstrated on real media

Published reproduction HEAD `60bc42e8a953aa05e01827ed07b74cd5e26c86d4`, checkout
`48053b08410d0b89d0d7d35358a1509940525159`. Focused run 36253121718:
all three debug and all three R8 video-stop-race repetitions FAIL with
`Video-only cancellation terminated authorized audio: video-authorization`.
The three ordinary camera-permission-revoked repetitions in EACH matrix PASS.
Artifacts downloaded into /tmp/umbra-stop-race-red; no retry result replaced any
failure. This demonstrates a native adapter cancellation race, without claiming
that the older artifact recorded the exact internal interleaving.

Cause: inspect checks videoStopped before entering activateVideo; a concurrent
local/remote STOP can invalidate video while checkVideo waits for SQLite. The
ordinary SecurityException was classified as fatal to the whole audio session,
so the later stopVideo snapshot encountered a terminal Call unavailable.
Correction: a specific VideoStopped cancellation, emitted only after audio lease
revalidation, completes video teardown without failing audio. Other context,
identity, generation and certificate failures retain their fatal handling.
The same deterministic case is added to the complete video matrix as well as
the focused three-repetition workflow. Positive execution of the fix pending.

Additional failure retained: modulation run 36253121682 fails both matrices in
its host UDP reachability probe BEFORE creating media. Its earlier diagnostic
omitted tool exit/error classification. Add bounded exit/count/fixed-kind
reporting, not acceptance of tool errors or a weakened network assertion.

Additional evidence improvements: persist each validated video phase before the
next action; revocation checks granted/denied explicitly and records host monotonic
request/process-gone times and actual instrumentation exit. Tool regression is
red (missing function) then green; full tools suite now 143 PASS. No claim these
host observations measure the last camera callback or last network packet.

Focused RFCOMM runner added: three repetitions per flavor on independent bonded
AVDs, all results retained and any failure fatal. It starts fresh AVDs separately
from the full Verify sequence (which runs voice scenarios first), allowing a
comparison for residue effects without replacing the original suite. The host
records each instrumentation process exit before/after cleanup and whether the
host terminated it. This does not classify a terminated dialer as spontaneous
failure or as successful enrollment.

## First positive race validation; separate infrastructure failure

Correction HEAD `69ec0216f3b116cf09020f3afe2c0ae65e295ccc`, checkout
`b4e9c385d7531554a8e32ab24b8c4f8308acdfab`: focused run 36253784224 R8 SUCCESS
(three forced stop interleavings and three ordinary permission revocations).
Debug FAILURE: all six cases stopped at the UDP direct-route preflight before
media, so no debug acceptance is claimed from that run. This is retained, not
converted into a negative media test or replaced by the earlier red results.

Diagnostic baseline 8600654 Verify 36252707341 completed four jobs SUCCESS;
checkout `1f78397ddb9c01020941a67ee51adab091879a25`. Downloaded both RFCOMM
logs show socket, hello, proof, authentication, verification, delivery and drain
in connected and offline. A new pass alone does not prove the old race fixed.

## Pairing readiness defect and correction

The original failed artifact's emulator-5556-bluetooth.txt says Discovering:true
and BTA_DM_SEARCH_ACTIVE at return from Android pairing. The passing diagnostic
artifact says Discovering:false on both endpoints. The host returned immediately
on observing bond records and left Settings in foreground; it neither stopped
its scan lifecycle nor checked radio readiness. A deterministic orchestration
regression reproduces that premature return (red), then passes after the fix.

The corrected helper sends HOME on both previously validated synthetic AVDs,
then requires one unambiguous Discovering:false observation per endpoint. It
uses the existing overall pairing deadline, with no timeout extension. Saves
separate before/after dumps. The RFCOMM fixture also rejects active discovery
before starting its protocol. No pairing code, identity proof or safety-code
comparison is omitted. No production Bluetooth protocol change for this fix.

AOSP BluetoothPairingDetail stops scanning on lifecycle stop:
https://android.googlesource.com/platform/packages/apps/Settings/+/refs/tags/android-13.0.0_r1/src/com/android/settings/bluetooth/BluetoothPairingDetail.java
This source explains the lifecycle choice, not evidence that the historical
handshake's exact failing native operation has been recovered. Its old logs
omit that operation; active discovery is observed and the readiness defect is
proved, but sole causation of that historical timeout remains uncertain.

Local tooling now 145 PASS, including rejection of missing/ambiguous discovery
and permission states. New positive RFCOMM and final whole-commit CI required;
final results/checkouts/artifact receipts are maintained in the PR #10 status.

## d9dd2f3 acceptance and an additional expiry harness failure

HEAD `d9dd2f3453436ecfab24ba5dbda3eb6835007627`, merge checkout
`213884eb56205abd358a2e2efcbd9f43ef7bda83`, common tree
`98f1da25f2553931b908db93f186afb538fa4edb`:
Verify 36254567564 all four jobs PASS; voice R8 36254567556 all 15 PASS;
modulation 36254567582 debug/R8 PASS; focused 36254567552 all 18 PASS
(six nearby and six video per configuration). Both full-sequence RFCOMM runs
also PASS with normal exit 0 on both endpoints. Local final-state tools 145,
backend 149, core 105, connected JVM 157/offline JVM 117 PASS; full release
build/lint/APK policies PASS. Both unsigned release hashes equal CI's hashes.
Preserved downloadable ZIPs with locally checked SHA-256 in
`.run/modulator-final-d9dd2f3/ci-receipt.json`, plus original patch/export/bundle.

Full video 36254567544: R8 all 32 PASS; debug 31 PASS, credential-expiry FAIL.
Original camera-permission-revoked, video-stop-race, direct-blocked and tls-valid
PASS. The failure is NOT hidden by those results. Artifact 10910841972 SHA-256
`491a1f4e11ac6f4d2ce03283fdb191ed7590017018b86f5405d95db61fb2782a`.
All active/stopped/resumed video phases completed; the host requested expiry
observation. The fixture instead terminated in pump/sendAuthorized with
CallService.lease `Call interrupted`, while native expiry cancelled the lease.
Cancellation between outbox enumeration and the transport guard is a valid
rejected write, not successful signaling. No production guard is weakened.

Lab correction asserts only that specific rejection, for the same queued call,
after requested credential expiry has actually arrived and native media is
terminal. Other exceptions, another call, missing expiry request, or live media
still fail. Rejected delivery is counted in the closure receipt, never marked
transported or retried; measured native callback quiescence remains mandatory.
The clock is rechecked at rejection, not before waiting for the write guard.
Negative JVM guard tests cover those distinctions; their first run failed on
the missing helper, then passed. This is a classification test, not a native
media reproduction. The actual red media run above is retained. Focused CI adds
three real credential-expiry repetitions per configuration; their new result
and all whole-commit suites remain pending until the next published HEAD runs.

The tooling job now explicitly selects JDK 21 for the standalone negative lab
assertion test. Local debug instrumentation compiled. An attempted combined
debug/mediaLab instrumentation command failed because testBuildType is exclusive;
separate Gradle invocations use the existing configuration rather than changing it.

R8 tracing initially rejected the new lab helper as missing from its explicit
fixture input JAR. Added that single test class to the source list; no broad keep
rules, production dependency or minification changes. This build failure is
retained in /tmp/umbra-expiry-r8-build-first.log.

Separate debug instrumentation build PASS (10 s); corrected mediaLab/R8 build
PASS (31 s). Tools 146 PASS, repository guard 315 files PASS, diff check PASS.
These compile/classification checks do not replace the pending native CI runs.
