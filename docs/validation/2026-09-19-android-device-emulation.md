# Android execution and Bluetooth stack emulation — 2026-09-19 UTC

This report records newly executed checks on synthetic AVDs. It does **not** claim
physical Bluetooth, hardware-protected secrets, a full unlocked UI flow, or SQLite
vault integration from an in-memory test adapter. Final aggregate validation must
also identify the final repository commit and final artifact hashes.

## Environment and preparation

VERIFIED: OpenJDK 21.0.11, Gradle 8.13, SDK compile 36, Android emulator
37.1.11.0; runtime image `system-images;android-35;default;x86_64`, build fingerprint
`Android/sdk_phone64_x86_64/emu64x:15/AE3A.240806.019/12368160:userdebug/test-keys`.
Android test dependencies: runner 1.6.2, ext:junit 1.2.1; production libsignal 0.102.3.

VERIFIED: two independently created AVDs, no PIN and no preexisting UMBRA vault:
`umbra_audit_35_a` on emulator-5580 and `umbra_audit_35_b` on emulator-5582.
KVM access used the existing local `kvm` group via `sudo -n -u wundah -g kvm`.
No paid infrastructure or production account was used. Both shared one netsimd,
which registered independent Bluetooth chips 1001/1002 and RootCanal controllers.
Both Android Bluetooth adapters reported ON and the virtual controller HCI 5.3.

AVD B creation (exit 0):

```sh
sudo -n -u wundah -g kvm env JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 \
  ANDROID_HOME=/mnt/c/Android/sdk-linux ANDROID_AVD_HOME=/tmp/umbra-audit-20260919/avds \
  /mnt/c/Android/sdk-linux/cmdline-tools/latest/bin/avdmanager create avd \
  --name umbra_audit_35_b --path /tmp/umbra-audit-20260919/avds/b.avd \
  --package 'system-images;android-35;default;x86_64' --device pixel_6
```

Launch used `emulator -avd umbra_audit_35_b -port 5582 -no-window -no-audio
-no-snapshot -gpu swiftshader -accel on` with the same environment/group.
`adb shell getprop sys.boot_completed` returned `1` on each device.

VERIFIED: paired the devices through Android Settings, discovered the other AVD,
compared the pairing numbers, and selected Pair on both. Access to contacts/call
history was not granted. `dumpsys bluetooth_manager` then listed the other device
as bonded on each AVD. The virtual MACs were `BB:BB:BB:00:00:01` and
`BB:BB:BB:00:00:02`. These are synthetic emulator addresses, not user identities.

## Builds and correction of the test fixture

VERIFIED: with JAVA_HOME pointing to JDK 21 and ANDROID_HOME to the SDK, ran:

```sh
.umbra-tools/gradle-8.13/bin/gradle -p android --no-daemon \
  :app:assembleConnectedDebugAndroidTest :app:assembleOfflineDebugAndroidTest \
  :app:assembleConnectedDebug :app:assembleOfflineDebug
```

Result: exit 0, BUILD SUCCESSFUL, 134 actionable tasks, 1m25s. Real debug APKs and
both instrumentation APKs were installed using `adb install -r`.

A subsequent Bluetooth-fixture build failed because `Files.readString` was not
available in the Android API surface. Corrected to UTF-8 `Files.readAllBytes`.
An attempted additional instrumentation manifest entry was rewritten by AGP to
the configured runner; removed it and implemented an explicitly selected JUnit
`RunListener` instead. No Gradle instrumentation-runner setting changed for this.
The corrected `assembleConnectedDebugAndroidTest assembleOfflineDebugAndroidTest`
build passed, exit 0, 98 actionable tasks, 28s. No failed fixture build is counted
as a successful build.

## Single-device tests

VERIFIED: eight tests per variant passed, with zero skipped/failed:

- Three real Activity checks: secure window, recreation, background/resume while
  locked without device credentials; no identity/database initialized.
- Two real Android Keystore checks: probe reported security level **0 (software)**;
  production `requireHardware` rejected that key. Production `prepareKey` rejected
  absent device credentials and did not create a database. Probe keys are test-only,
  deleted afterward, and never injected into the production Vault.
- Three real packaged libsignal JNI checks: encrypt/decrypt and authenticated
  receipt, duplicate display prevention, altered ciphertext rejection followed by
  authentic retry, and engine recreation with preserved synthetic records.

Initial commands: `adb -s emulator-5580 shell am instrument -w -r
<debug-package>.test/androidx.test.runner.AndroidJUnitRunner`, after installing each
app/test APK pair. Connected: 8 passed in 4.169s; offline: 8 passed in 3.480s.
ADB exit 0 and `OK (8 tests)` plus instrumentation completion were inspected.

The reusable runner was then executed against both variants:

```sh
. .venv/bin/activate
ANDROID_HOME=/mnt/c/Android/sdk-linux python scripts/run_android_instrumentation.py \
  --serial emulator-5580 --flavor offline \
  --app-apk /tmp/umbra-audit-20260919/device-apks/offline.apk \
  --test-apk /tmp/umbra-audit-20260919/device-apks/offline-test.apk \
  --log /tmp/umbra-audit-20260919/instrumentation-offline-script.log
```

Repeat with `connected` in the flavor/paths. Without APK overrides, the runner uses
normal Gradle output paths. It fails on adb error, omitted/skipped tests, missing
completion, or anything other than exactly eight successful checks. It does not
clear existing vault data to force a passing test.

## Actual emulated Bluetooth transport

The fixture `NearbyFixtureListener` is selected explicitly with instrumentation
`-e listener app.umbra.NearbyFixtureListener`; it is not a discovered JUnit test and
adds no skip/assumption to the eight single-device checks. It operates only from
androidTest, requires a debug app and no existing vault, and uses synthetic
`DeviceMemoryRecords`. Production sources do not select that adapter.

The host orchestration installs the app and test APK on both AVDs, grants Bluetooth
permissions, disables Wi-Fi and mobile data, and starts listener/dialer. Actual
production `BluetoothLink` transports messages over the Android RFCOMM API. It
performs explicit enrollment and real libsignal identity challenges. Each endpoint
reports its independently computed synthetic safety code; the host compares both
and approves them through the test's private file only after equality. A second
barrier ensures both contacts are verified before sending.

```sh
. .venv/bin/activate
ANDROID_HOME=/mnt/c/Android/sdk-linux python scripts/run_bluetooth_emulation.py \
  --serial-a emulator-5580 --serial-b emulator-5582 \
  --address-a BB:BB:BB:00:00:01 --address-b BB:BB:BB:00:00:02 \
  --flavor offline \
  --app-apk /tmp/umbra-audit-20260919/device-apks/offline.apk \
  --test-apk /tmp/umbra-audit-20260919/device-apks/offline-test.apk \
  --log-dir /tmp/umbra-audit-20260919/nearby-offline
```

VERIFIED: offline and connected fixtures each passed on **both endpoints**, exit 0,
using the same command with the corresponding flavor and APK/log paths, with bidirectional
text and attachment, a repeated ciphertext without duplicate display, and actual
authenticated delivery receipts. Three JNI tests also passed afterward per endpoint;
these repeat existing checks and are not six novel scenarios. No TCP byte exchange
was relabeled as Bluetooth. `settings get global wifi_on` and `mobile_data` returned
`0` on both AVDs. The host uses ADB only for setup, public-code comparison, and test
coordination; message content goes through RFCOMM.

## Artifacts used for the execution above

The following temporary copies protected executed artifacts while the root task
performed another clean final build. They identify this device execution, not an
unexecuted future commit or final release:

| APK | SHA-256 |
|---|---|
| connected.apk | `00476f7d54dd6575ed8ae89794abe11bbf7fa6b6ed8f31bf3a0562edad8e329d` |
| connected-test.apk | `c7422cefa30bb7dd3e9562a178a93ecb6451e65243fde52f9ba1585814aae848` |
| offline.apk | `ad0dc8a71c25418de103d1a1046ea156f0be787bd7ff5654852ac8bd110a78a1` |
| offline-test.apk | `3372cc611a27025c3d0af2d2ed5de2f651d7e39de6fb3a42862bc60e10cfd9c4` |

## Coverage still open

NOT EXECUTED here: real radio range/interference/power, physical devices, hardware
Keystore, unlocking/creating a production vault, Vault SQLite migration/disk-full,
UI cancellation while unlocked, large Bluetooth loads, disconnect/reconnect/replay
fault matrix, full process death/picker continuation, or release APK installation.
MemoryRecords only proves protocol state behavior, not encrypted disk durability.
The strict production hardware requirement remains in force; emulator software
keys are never accepted as a successful production identity.

## Host runner regression checks

VERIFIED: the final Bluetooth runner refuses either device unless
`adb shell getprop ro.kernel.qemu` returns `1`, before installation, permission
grants or radio changes. On a failed run it stops both the local adb clients and
the started debug app instrumentation processes. Re-executed the offline fixture
with these changes: both endpoints PASS, exit 0
(`/tmp/umbra-audit-20260919/nearby-offline-final-script`).

VERIFIED: activated `.venv/bin/activate` and ran
`python -m unittest discover -s scripts/tests -p test_android_execution.py -v`:
**6 orchestration regression tests passed**, exit 0. These use synthetic adb
responses solely to test runner failure propagation and physical-device refusal;
they are not six Android behavior tests. Covered zero tests, skips, missing
completion, adb failure despite a success-looking summary, exact successful count,
and refusing a physical device before mutations. `git diff --check`: exit 0.
