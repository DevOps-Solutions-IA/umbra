# Personal vault password — 2026-09-26

Status: PARTIAL, implementation and local validation in progress. No final CI
or hardware acceptance claimed by this initial entry.

Base reverified with Git and GitHub: PR #10 OPEN/DRAFT,
`codex/local-voice-modulator` at `6b0a844aa0a97d4984d04a58330621f37c5291d1`.
New isolated worktree `/tmp/umbra-personal-vault-password`, branch
`codex/personal-vault-password`. Original worktree and Claude UI untouched.

## Implemented scope

ADR records original Vault/Codec/Engine architecture before changes. Nested
password/device AES-GCM, Argon2id 64MiB/3/4, transactional initial reciphering,
subsequent rewrap, explicit APIs, epoch invalidation and bounded autolock.
Neither server nor UI has a password substitute. No recovery or new permissions.
UI integration intentionally separate, described in VAULT_PASSWORD.md.

## Executed locally (intermediate trees, not final HEAD)

- Python 3.13.12; JDK 21.0.11; Gradle 8.13; AGP 8.13.2; SDK 36;
  build tools 35.0.0; libsignal 0.102.3, unchanged.
- Activated existing `.venv/bin/activate` before Python tests.
- `bash scripts/test_local.sh`: backend 149 + core 105 pass; source checks separate.
- Tools initially 146 tests: 2 failed because strict Android fixture counts were
  stale after adding tests. Corrected expected fixtures, preserving strict checks.
- Tools with new host regressions: 148 pass (exit 0).
- Initial real JVM suites including envelope tests: 161 connected / 121 offline pass.
  Added subsequent epoch/RFC vector regressions require another run.
- `python scripts/build_android.py --check-only`: exit 0; preflight only.
- `python scripts/build_android.py --release`: first Android compilation/lint passed,
  post-build suite guard failed because it only discovered top-level test packages.
  Fixed recursive package discovery; second full build/guards exit 0.
- Dedicated R8 laboratory build both flavors: initial errors (Files Java API not
  available in Android, then missing lint task dependency) corrected; build exit 0.
  Building does not prove execution of Argon2 on Android.
- Production APK policies retained: offline no network/microphone/camera/WebRTC;
  all four Signal JNI ABI, no test adapters in release. Intermediate hashes remain
  local receipts, not final acceptance artifacts.
- KVM checked again: root:kvm 0660, caller not in kvm; no effective read/write
  access. Local AVD execution BLOCKED; use authorized GitHub Actions. No fallback.

## Added Android acceptance (not yet executed in this entry)

Eight actual Vault tests: migration/UI bypass/wrong password, change and fresh
instance, corruption rollback/lost key, SQLite failure during rewrap, deleted
metadata, autolock, lock during actual Argon2, and real Signal identity/ratchet
preservation. Test-only AndroidKeyStore keys in disposable debug/vaultLab UIDs;
production prepareKey hardware/authentication constraints remain unchanged.

A separate host-controlled force-stop kills an unlocked process, verifies the
process died, then asserts LOCKED/password-required on restart and rejects lost
Keystore key. Reports three production-profile unlock durations and sampled heap.
It does not claim death during SQLite commit, physical TEE/StrongBox or reinstall.

New `vault-password.yml` runs both flavors, debug and genuinely minified R8 lab.
Existing Verify/voice/video/modulation suites stay enabled. Final HEAD/checkout,
CI, results and artifact hashes must be appended after actual executions.

## Pending limits

Hardware biometric/TEE/StrongBox, real process death DURING commit, reinstall,
independent cryptographic review and human password UX. No forensic memory-erasure
or whole-storage rollback-resistance claim. No updates to PR #10 or Claude UI.

## First published Android run — 5c0c455

PR #11 OPEN/DRAFT against #10. HEAD `5c0c455b38b6bb6d897e2395683cfc8067439dae`,
checkout `a5b6423cbdba7de11f3c02447b864088819f8269`, tree
`6b9f6c3a3c774a872a5f60079f9d52224c4716cc`.
Local full debug/release/lint/APK/JNI build on published HEAD: exit 0;
164 connected + 124 offline JVM tests passed, 148 host tests passed.

Password CI `36263833451` FAILED both debug and R8: 7/8 connected Vault cases
passed, but the expected injected migration storage exception never happened.
No force-stop/calibration or offline execution occurred after that failure.
Artifacts `10913253373` (debug) and `10912844808` (R8) preserve the actual reports.
Debug digest `c544cc662c483f3bfb77d803cedb5772acc2610bf104857d61f151b1f7d88214`;
R8 digest `07f729796f3e75a2cfbb3dda81fb2a59472185679753e0d42f6d25c996856988`.

Cause: the fixture overrode Context.openOrCreateDatabase to inject a cursor
factory. Android 15 SQLiteOpenHelper instead calls SQLiteDatabase.openDatabase
using its constructor-supplied factory; that fixture factory never ran. Verified
against AOSP `android15-release`, SQLiteOpenHelper lines 156/370–382:
https://github.com/aosp-mirror/platform_frameworks_base/blob/android15-release/core/java/android/database/sqlite/SQLiteOpenHelper.java

Correction: exercise actual SQLiteFullException using max_page_count, restoring
its original quota afterward and retaining rollback assertions. For deterministic
force-stop before commit, a test-only SQL view/custom scalar function blocks the
migration SELECT after the staging table is created. On restart the test first
checks rollback removed staging/protection, then restores its synthetic view to
the original table. This is an actual uncommitted production migration killed by
the host, with a test schema barrier; it is not death during SQLite fsync/commit.
No production Vault/crypto relaxation accompanies this fixture correction.

## Additional storage/lifetime review

The original four-argument SQLiteOpenHelper constructor uses Android's default
corruption handler, which deletes the damaged database and retries opening it.
This conflicts with UMBRA's no-silent-reset requirement. Source inspection:
https://github.com/aosp-mirror/platform_frameworks_base/blob/android15-release/core/java/android/database/DefaultDatabaseErrorHandler.java
The new handler throws without deletion, including the read-only index-key probe;
an existing zero-version/truncated file cannot invoke fresh Vault initialization.
The ninth Android case reproduces default deletion/recreation on a separate
synthetic control file, then requires Vault to preserve malformed bytes and
refuse initialization of a truncated existing file. Android execution pending
for this correction, not inferred from source inspection.

The scheduled key cleanup now uses the earlier of the configured timeout and
the original Android-authentication deadline, rather than retaining the DEK for
a fresh full interval after KDF completion. A JVM boundary regression confirms
that rotating authorization epochs preserves the remaining lifetime. BC runtime
transitives are explicitly disabled (the reviewed artifact requires none).

## Android password acceptance of d4711ab (before subsequent hardening)

Run `36264314234`: debug and R8 SUCCESS. Both flavors each ran all 8 Vault cases,
plus actual force-stop after unlock and during the staged migration. Every restart
rejected device-only access and missing Keystore; original records survived the
interrupted transaction. R8 target is `.vaultlab`, not the production APK.
Checkout `f1da632db68b09bfac4427fcf18e3486446389ee`.
Debug artifact `10913337216`, SHA256
`cf6db65bcafb3296c43fd0c40af9f80960e262db5030dd372e02502f8341604f`;
R8 artifact `10913780241`, SHA256
`22f62fb75a114287b5d32cf2f7db5c2e7ef49c22fbdb590b43b9b79f532d8159`.

Production-profile unlock samples (milliseconds): debug connected cold 1873,
offline cold 2745; subsequent samples 503–538. R8 samples 295–342.
Sampled Java heap peaks approximately 143 MB debug / 201 MB R8, including
collectable Java allocations, not a live-object census/native RSS or a hardware
memory guarantee. The 64 MiB KDF profile was not reduced. Physical calibration
remains pending. These measurements do not validate later source changes.

Other initial-run results retained: modulation `36263833428` SUCCESS. Focused
regressions `36263833447`: debug video and RFCOMM SUCCESS; R8 failed all 9 cases
before media at the owned-AVD direct UDP reachability precondition
(`run_voice_integration.py:182`). No endpoint media fixture ran in those failures.
Cause of unavailable UDP route is not established; do not remove the precondition
or count those attempts as media passes. Artifact `10913321750` retains evidence.
Verify `36263833523` ran 35 Android cases, failing only the now-corrected migration
fault injection; repository/core/container jobs passed. Subsequent HEADs need
independent full CI, including the new corruption/deadline corrections.

The next candidate also adds a debug-only real uninstall/reinstall probe in both
flavors. The host preserves only bounded synthetic ciphertext in transient memory,
reinstalls the same APK and restores that ciphertext; correct password must fail
because Keystore keys were deleted. No database/key material is uploaded. Test
fixtures now report the security level and authentication requirement of the
actual AndroidKeyStore key used, without labeling AVD results physical hardware.
New local candidate: debug instrumented APKs compile, JVM 164/124 and 149 host
checks pass. Android execution of the ninth corruption case and reinstall remains
pending until this candidate's own CI finishes. Existing multimedia failures and
superseded runs stay in their reports; they are not removed or reclassified green.

## Password acceptance — 8f204ea

HEAD `8f204ea6dbda0164f2a9a99510826117712b2960`, checkout
`a27aebe2c43ef3248734e2b76f38b67594e31438`, equal tree
`3930c59b2fbaf2678ddaf73e889d5eaa22bfbfef`.
Password run `36265376601`: both jobs SUCCESS. Both flavors passed nine actual
Android Vault cases in debug and R8, including the newly reproduced default
SQLite corruption deletion and preservation regression. Force-stop after unlock
and during uncommitted migration passed in all four combinations. Debug also
passed actual uninstall/reinstall of both flavors with restored ciphertext:
correct password without the original device keys could not open or regenerate.
Keystore fixture reported security level 0 (software), not TEE/StrongBox.

Debug artifact `10914076104`, SHA256
`1bee6deeb514c5402eb89e34dd530fb4a24c2ba8053f2795da5da9cb7abf5a90`;
R8 artifact `10913526745`, SHA256
`dc01a7767fb64191d4759dbcadca9eaf411f154f93ab3f6c22f185a3f40664cf`.
App/test APK and R8 mapping hashes are in each flavor's receipt.json. Reports
contain no database, password or key. Debug unlock samples 671–1011 ms; R8
225–300 ms; sampled Java heap peak approximately 201 MB, including collectable
allocations. These are AVD measurements, not physical-device calibration.

Local on this exact HEAD: test_local, 149 tools tests, repository_guard,
build_android --check-only, build_android --release, and both optimized vaultLab
app/test builds all exited 0. JVM 164 connected / 124 offline. UI diff remains
empty. The original base worktree is unchanged.

Final regression acceptance is NOT complete: focused run `36265376586` debug
again failed all nine attempts before media, at the owned-AVD UDP listener
(exit 1, diagnostic category other, receivedBytes 0). RFCOMM passed. The previous
`d4711ab` focused run had the same debug failure while its R8 cases passed; its
Verify run `36264314236` passed all four jobs. This is insufficient to establish
the cause of the UDP listener failure. A bounded escaped diagnostic of this
specific synthetic toybox command is added next; it does not relax the route
precondition, add retries or change network/privacy policy. Other current runs
must finish and each final HEAD needs its own acceptance.
