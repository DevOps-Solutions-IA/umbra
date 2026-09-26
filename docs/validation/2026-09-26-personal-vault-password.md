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
