# Vault key lifecycle and SQLite boundaries — 2026-09-20 UTC

Scope: Vault.java, AccessGate authorization semantics and synthetic Android
instrumentation. No production fallback key, key injection, schema/protocol change,
or weakening of hardware requirements. Existing Engine/libsignal tests remain
independent of these tests. Base of this task: `7570270`.

## Finding and correction

VERIFIED by the before-fix Android regression (see execution evidence): `prepareKey` performed a check-then-generate for
AndroidKeyStore aliases without synchronization between Activity instances;
`destroyKey` could also interleave. Android alias generation must not race another
preparation or deletion. The original methods allowed overlapping key lifecycle
operations even though each Activity serialized its own worker independently.

Correction: both static key lifecycle methods acquire the same class monitor.
They run on the existing background worker; no migration or database transaction
is placed under this additional lock. No UI-thread authentication policy changes.
The test deliberately blocks `getDatabasePath` after the **real AndroidKeyStore**
missing-alias check and before the no-key/existing-database rejection. A second
preparation must not reach that probe concurrently, and deletion must wait.
No test generates or installs a software key under a production Vault alias.
A unique synthetic file forces rejection before any key generation, and must
remain present afterward.

## SQLite coverage design

`DeviceVaultTransactionTest` uses real Android SQLite and the actual Vault
transaction method with a test-only ContextWrapper pointing at a UUID-named cache
file. Opaque synthetic marker rows exercise transaction boundaries without being
misrepresented as encrypted messages or a production identity. Cases:

1. Successful commit remains after close/reopen.
2. Work exception rolls back added rows and retains prior data.
3. Lock before commit rolls back.
4. Lock/re-authentication does not authorize the original transaction.
5. A failed nested transaction rolls back the outer writes even if its exception
   is caught (the outer API currently does not report that rollback as failure).
6. A v1 migration rejected because its data key is missing preserves original
   schema version, row and table; no migration staging table remains.

This does **not** validate encryption/persistence with a hardware-backed key,
complete successful encrypted migration, disk-full, power loss, or all UI races.

## Migration boundary still open

NOT_VERIFIED: the final `gate.check` in `onUpgrade` and SQLiteOpenHelper's later
framework commit are separate. The comment now states that limit instead of
promising rollback for every lock. No holding of the gate across migration was
introduced: doing so can block UI locking for the full migration. Closing that gap
requires a separately reviewed commit/migration design and Android regression;
this narrow lifecycle fix does not claim to close P0-03.

## Before-fix execution

VERIFIED: JDK 21.0.11, Gradle 8.13, AOSP API35 x86_64 fresh AVD, emulator
37.1.11/KVM; SDK compile 36. Explicit temporary before-fix build removed only the
two `synchronized` modifiers, retaining the new tests. Command:

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/mnt/c/Android/sdk-linux \
.umbra-tools/gradle-8.13/bin/gradle -p android --no-daemon \
  :app:assembleConnectedDebug :app:assembleConnectedDebugAndroidTest
```

Build exit 0, 67 tasks, 39s. Installed both debug APKs on emulator-5580 and ran:

```sh
adb -s emulator-5580 shell am instrument -w -r \
  -e class app.umbra.DeviceVaultKeyLifecycleTest \
  app.umbra.privatechat.dev.test/androidx.test.runner.AndroidJUnitRunner
```

JUnit result: **2 tests, 2 failures**, 0.328s. Deletion completed while preparation
was blocked, and both preparations reached the protected probe (observed 2 versus
expected 1). The adb shell itself returned 0; that is not treated as test success.
The original failure log is `/tmp/umbra-vault-20260920/before-races.log`.
Both `synchronized` modifiers were restored immediately after this reproduction.

## Additional product failure found by real SQLite execution

VERIFIED: after synchronizing key lifecycle operations, the four debug app/test
APKs built successfully (134 tasks, 58s). Both complete instrumentation suites ran
16 tests, and **six failed per variant**. Both key-lifecycle regressions passed.
Every SQLite case failed at `Vault.onConfigure`: Android rejected
`execSQL("PRAGMA secure_delete=ON")` with `SQLiteException: Queries can be performed
using SQLiteDatabase query or rawQuery methods only`. That PRAGMA returns a result
row even for its setter, so the production Vault could not finish opening on the
executed Android API35 image. This was not a mocked database or a test-only parser.
The issue occurs before any production key decryption; hardware policy was not
bypassed to reproduce it.

Correction: use `rawQuery`, consume and close the cursor, and require returned
value `1`. The secure-delete policy is preserved and failure to enable it remains
fatal. The successful reopen test also checks the resulting PRAGMA value directly.
Before-fix logs: `/tmp/umbra-vault-20260920/after-connected.log` and
`after-offline.log`; despite their filename they are the recorded failing runs
before the additional PRAGMA correction, not successful final evidence.

## Corrected execution

VERIFIED: rebuilt both app/test debug variants using the same Gradle command plus
`:app:assembleOfflineDebug :app:assembleOfflineDebugAndroidTest`; exit 0, 134 tasks,
55s. Activated `.venv/bin/activate` and ran:

```sh
ANDROID_HOME=/mnt/c/Android/sdk-linux python scripts/run_android_instrumentation.py \
  --serial emulator-5580 --flavor connected \
  --log /tmp/umbra-vault-20260920/pragma-fixed-connected.log
ANDROID_HOME=/mnt/c/Android/sdk-linux python scripts/run_android_instrumentation.py \
  --serial emulator-5580 --flavor offline \
  --log /tmp/umbra-vault-20260920/pragma-fixed-offline.log
```

Both commands exit **0**. Connected **16 passed**, 3.661s; offline **16 passed**,
4.105s, no failures/omissions. The runner now requires exactly sixteen tests,
including the eight new cases; no old tests were excluded. The corresponding six
orchestration regression tests passed after updating the expected count, exit 0.
`git diff --check` passed. No claim is made that this scoped debug build validates
later changes, release/R8, or the final PR commit; root records that separately.

Executed artifact SHA-256:

| Artifact | SHA-256 |
|---|---|
| connected debug | `1f3d7d3b4c24821c8efdac2248f46b59f14317dcbd9566c625831b66e639a06b` |
| offline debug | `d3f09ea316a86d8af89fbcc9cd5a5fb3367928588b953b61fb2b91d520753f89` |
| connected test APK | `4684c402140c7f8a4eb44963867df2be8a223625ea932a242ce23cfd1549dab4` |
| offline test APK | `0f5a91381ca1d87c3450061ca581d91126621dc971a0f5c4e9d1ac2a0eb3ba70` |

The data-key lifecycle race and PRAGMA failure are verified defects with failing
before runs and passing after runs. Alias replacement under genuine hardware load
was not executed. The tests prove the overlapping lifecycle entry and its
serialization, not physical hardware resilience. The SQLite tests prove real
transaction rollback and rejected-migration preservation with keyless synthetic
marker rows, not a successfully unlocked encrypted production vault.
