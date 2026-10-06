# Prepared content authorization — 2026-09-28

Master v3 remains open. This is a correction to the common A/B dependency, not completion of C.

Base inspected: c808418e555297983d0a8fb07b1a1d0e44f14578. Draft PR #16, base codex/emergency-lock. No UI changes.

## Reproduced failure

`Prepared` retained bytes/format but discarded the authorization supplied to its preparation adapter. A fresh review after lock/unlock could therefore send an older prepared object. The new JVM regression `preparedBeforeLockCannotBeSentWithFreshConsentAfterUnlock` failed before the correction: expected SecurityException, nothing thrown. Real libsignal and memory transactions; this is not Android durability evidence.

Every internal constructor now requires and checks the original preparation authorization. Send rechecks it within its transaction and in the enqueue permit; replacement consent does not revive the original epoch. Rejected transaction attempts still wipe the transferred buffer. No ratchet rewind, transport bypass, or new permissions.

Focused Gradle invocation (JDK 21, Gradle 8.13, offline dependency cache): `:app:testConnectedDebugUnitTest :app:testOfflineDebugUnitTest --tests app.umbra.RestrictedContentTest` exited 0: 13 tests per flavor. Before-fix focused command exited 1. Logs preserved under `.run/security-content/prepared-lock-{before,after}.log` (not committed). A native sanitized AAC regression was added to the existing malformed/stale-authorization case; execution on this change is still pending.

Pending independent lifecycle work: callers currently own and must close unsent Prepared objects. The original epoch blocks their reuse, but immediate domain-owned wiping of every pending preparation is not yet verified. Do not claim all B lifecycle work complete.

## Preservation

Full recoverable history plus pending PDF diff/new files exported to `C:\Users\Usuario\Downloads\UMBRA_RESPALDOS_CODEX\umbra-security-content-c808418-pdf.tar.gz`.
SHA-256: `1b071d431f3e303d06392092efc5889b2af2ec29ff7e749c937a71530935e40b`.
Cloned explicitly with branch `codex/security-content-completion`; recovered c808418e555297983d0a8fb07b1a1d0e44f14578; `git fsck --full` exited 0. Full bundle requires no external prerequisite. This backup precedes this authorization correction; originals retained.

## Earlier checkpoint, not this correction

c808418 privacy run 36458434466 passed both debug/R8. Admission, startup, vault, emergency and modulation also completed successfully when consulted. Remaining workflows were pending/running. PDF local work and file-video remain unaccepted; no physical hardware claim.
