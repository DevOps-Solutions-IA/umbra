# Android lifecycle, vault and nearby review — 2026-09-19 UTC

This is scoped review evidence, not a complete security audit. Final aggregate build/CI
results belong to the repository audit report and must identify the final commit.
Subsequent [device/emulator execution](2026-09-19-android-device-emulation.md) covers
locked Activity recreation, real Android JNI, rejection of emulator software keys,
and actual emulated RFCOMM. It does not prove physical radio or hardware protection.

## Scope and findings

VERIFIED (source review): read complete `MainActivity`, `Vault`, `BluetoothLink`,
`AccessGate`, `Framing`, and document-export methods in `Engine`; read root AGENTS
and handoff/roadmap/testing/security/release instructions. No nested AGENTS were found
under Android. The Android components were reviewed as a whole, not only PR changes.

| Severity | Location | Finding / impact | State |
|---|---|---|---|
| High | `MainActivity.resumeExternalResult`, now around line 661 | Export checked only current unlock status after opening a potentially blocking provider. A lock followed by reauthentication allowed plaintext from the old session to be exported. A single unbounded write also continued without authorization checkpoints. | Corrected with an original-session lease and checked chunks in `DocumentIO`; deterministic JVM regression passes. Already handed-off bytes cannot be revoked. |
| Medium | `MainActivity.onDestroy`, around line 95 | Activity destruction shut down the worker but never closed its SQLite helper. Repeated recreation could retain database handles. | Cleanup queued after the running operation, followed by orderly worker shutdown; application context replaces Activity context. Android recreation validation is separate, not proven by JVM tests. |
| Low | `MainActivity.onboarding` / `renderSecurity` | Alias and verification code were read from EditText on a worker thread. | Capture the values on the UI thread before enqueueing work. Android execution pending in this scoped report. |
| Medium | `MainActivity.readBounded` / `DocumentIO.read` | Read authorization was checked after the provider read, permitting an unnecessary provider call from an expired session; a zero-progress provider could loop indefinitely. | Check before and after read, reject no progress, close streams, clear temporary buffers. JVM regression passes. |

VERIFIED (source review, not Android execution): Vault rejects missing AES keys for
existing databases and missing index keys for schema v2; requires TEE/StrongBox;
authenticated decrypt errors propagate. Writes require explicit transactions;
normal transaction commit uses the original AccessGate lease. Bluetooth uses paired
RFCOMM, explicit enrollment, bounded executors/frames and identity challenges;
transport completion is not a delivery receipt. No Keystore policy, protocol,
Bluetooth implementation or production/lab separation was weakened by these changes.

## Executed evidence

VERIFIED: `/usr/lib/jvm/java-21-openjdk-amd64/bin/java -version` returned
OpenJDK **21.0.11+10-1-Debian**, exit 0. Default `java` is 25.0.3;
all scoped compilation/tests used the explicit JDK 21 path.

VERIFIED: before changes, a temporary standalone Java harness used the real
`AccessGate` and exactly the former export sequence: obtain content, provider open,
lock/unlock during open, `requireUnlocked`, write, flush. It printed
`Legacy export bytes released across authorization epochs: 16384` and asserted this
observed failure, exit 0. This reproduces the authorization logic; it is **not an
Android ContentProvider execution**. Temporary harness was not committed.

VERIFIED: compiled `AccessGate.java`, `DocumentIO.java`, and `DocumentIOTest.java`
with JDK 21 `javac`, the cached JUnit 4.13.2 jar on classpath, output under
`/tmp/umbra-lifecycle-review/classes`; exit 0. Ran JDK 21 `java` with that directory,
JUnit 4.13.2 and Hamcrest 1.3 jars on classpath, main class
`org.junit.runner.JUnitCore app.umbra.DocumentIOTest`; **8 tests passed**, exit 0.
The tests use real in-memory streams with deterministic fault callbacks and real
AccessGate, no cryptographic substitutes. They cover stale exports before open,
reauthentication during open, lock during write/close, exact chunked output,
reauthentication on input open and EOF, exact bounds, and non-progress input.
`git diff --check` passed, exit 0.

Reproducible aggregate entry point (root runs both Android variants):
`python scripts/build_android.py`. Do not substitute the standalone JVM run for
Android compilation, lint, instrumentation, or device validation.

## Explicit remaining limitations

NOT_VERIFIED/BLOCKED in this scoped work: real SQLite migration/rollback/disk-full,
Keystore invalidation and hardware backing, full UI recreation/rotation and picker
flows, permission revocation, and RFCOMM virtual/physical Bluetooth. These require
Android execution and, for hardware claims, compatible devices. Root may append
independent evidence but prior historical results do not close these items.

INFERRED from source: the final `gate.check` in `Vault.onUpgrade` occurs before
SQLiteOpenHelper commits the upgrade transaction. It does not itself linearize
commit with a concurrent lock. No data-loss bypass was reproduced; an Android
migration/lock regression is still needed. Holding the gate around an entire
migration could block UI locking and was not introduced as an untested workaround.

VERIFIED limitation of the document API: a provider call already in progress may
block indefinitely and can retain bytes already passed to it. Chunk checks stop
subsequent calls after it returns; they do not retract external copies, close an
uncooperative provider on lock, or guarantee worker termination. Vault cleanup
waits for the running worker operation to finish. This remains an operational
risk, not a passing cancellation test.

NOT_VERIFIED: preservation of picker continuation across process destruction;
current pending recipient/export references are Activity fields. Existing staged
exports expire, but no saved-state design is asserted. Repeated taps can queue
repeated sends; no UI automation was executed to measure that behavior.
