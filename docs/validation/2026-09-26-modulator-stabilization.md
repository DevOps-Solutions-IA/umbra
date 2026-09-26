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
