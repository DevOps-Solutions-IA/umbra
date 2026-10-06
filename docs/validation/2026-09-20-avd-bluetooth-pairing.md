# Automated Android Bluetooth pairing — 2026-09-20 UTC

Scope: disposable AOSP API 35 x86_64 emulators, emulator 37.1.11.0, English Settings.
This automates the existing Android Settings pairing flow; it does not replace
RFCOMM with TCP or change application/Keystore policies.

VERIFIED: `scripts/pair_bluetooth_emulators.py` ran against two newly prepared
independent AVDs, emulator-5580 and emulator-5582, both with `sys.boot_completed=1`.
The script verified `ro.kernel.qemu=1` on **both** before changing radios/UI, opened
Settings on both, discovered the other device, compared both six-digit pairing
codes before selecting Pair, and confirmed both addresses in the **Bonded devices**
section of `dumpsys bluetooth_manager`. No manual taps were necessary. Both virtual
addresses were discovered from Settings rather than assumed from emulator ports.

Executed after `. .venv/bin/activate`:

```sh
ANDROID_HOME=/mnt/c/Android/sdk-linux python scripts/pair_bluetooth_emulators.py \
  --serial-a emulator-5580 --serial-b emulator-5582 \
  --log-dir /tmp/umbra-avd-20260920/device/pairing
```

Result: exit 0; `pairing.json` reports `confirmed-comparison`, with independent
virtual addresses `BB:BB:BB:00:00:01` and `BB:BB:BB:00:00:02`. Logs include the final
Settings UI XML and Bluetooth status from both AVDs. They contain only synthetic
emulator state. No production identity or real radio address was used.

Reproduction: boot two fresh English AOSP AVDs, then run the command with their
actual serials. The helper uses a 150-second overall deadline and bounded adb
calls; `--timeout` accepts 30–600 seconds. UI locations derive from XML bounds and
support different resolutions. Other languages/Settings layouts are unsupported
and fail explicitly rather than selecting guessed coordinates. Ambiguous device
names do not trigger a tap. Physical devices and incomplete boots are rejected.
Previously bonded pairs are recorded as `already-bonded`, not fresh pairing.

Use `address_a` and `address_b` from `pairing.json` for
`scripts/run_bluetooth_emulation.py`; successful pairing alone does not prove
message delivery. The existing RFCOMM fixture checks that separately.

VERIFIED: `. .venv/bin/activate` followed by
`python -m unittest discover -s scripts/tests -p test_bluetooth_pairing.py -v`:
**7 host helper tests passed**, exit 0. They cover screen bounds, unique pairing
code, labeled address, strict bonded-section parsing, foreign UI rejection,
physical-device refusal before mutations, and ambiguous names. An initial test
caught a parser accepting a MAC from unrelated logs after an empty bonded section;
the parser was corrected and the regression passed. These are host orchestration
tests, not seven Android/RFCOMM tests.

NOT VERIFIED here: physical Bluetooth, other Android Settings versions/languages,
real hardware Keystore, unlocked Vault persistence, or radio reliability under
physical interference. No new production feature or dependency was introduced.

VERIFIED: after the automated fresh pairing, executed
`ANDROID_HOME=/mnt/c/Android/sdk-linux python scripts/run_bluetooth_emulation.py
--serial-a emulator-5580 --serial-b emulator-5582 --address-a BB:BB:BB:00:00:01
--address-b BB:BB:BB:00:00:02 --flavor offline --log-dir
/tmp/umbra-avd-20260920/device/rfcomm-offline`, and repeated with connected flavor
and `rfcomm-connected` log directory. **Both commands exited 0**. Both endpoints
passed actual Android Bluetooth Classic RFCOMM, real libsignal challenges, host
safety-code comparison, bidirectional text/attachment delivery, duplicate handling,
and authenticated receipts. Wi-Fi/mobile data were disabled by the existing
fixture. Each endpoint also ran its three JNI checks successfully. APKs were the
existing locally built outputs; this is a new emulator execution, not a claim of
a new product build. `git diff --check` also passed, exit 0.
