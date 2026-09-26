# Personal vault password v1

Implementation under validation. Consult the dated evidence and the new PR's
actual CI before treating this feature as accepted. No UI is wired in this PR.

## Domain contract

`Vault` remains the production `Records` store used by Engine/libsignal.
Call password APIs on a worker thread. Inputs are caller-owned UTF-8 `byte[]`,
12–1024 bytes, no normalization; erase them after return, including errors. The
length bound is not a password-strength guarantee. Never log input or keys.

- `getVaultState()` is a snapshot, not an authorization token.
- `isPasswordConfigured()` inspects the protection metadata, not a UI preference.
- `createPassword(password[, parameters])` migrates a valid legacy vault under
  current Android authentication. It verifies wrapping before changing storage,
  migrates all record ciphertext in one transaction and finishes locked.
- `unlock(password)` additionally requires an active Android-authenticated
  AccessGate. Success holds the random DEK only in this Vault instance/epoch.
- `lock()` immediately invalidates the gate and drops/clears the held DEK, without
  waiting for a running KDF or SQLite transaction. Old operations cannot commit.
- `changePassword(current, replacement)` requires the open vault and verifies
  the current password; it rewraps the same DEK with a new salt and nonce and
  finishes locked. Identity/ratchets and record ciphertext do not change.
- `getAutoLockPolicy()` / `setAutoLockPolicy(milliseconds)` configure a process-local
  monotonic timer, only while locked. Default 240000 ms, maximum 240000 ms to
  preserve the existing four-minute guarantee. No background delay is enabled;
  existing pause still locks. The future five-minute option is not enabled.

The UI must get Android system authentication, then perform password unlock
**before** sensitive Engine work. Granting AccessGate alone cannot open migrated
records. Biometric Android authentication is only one prerequisite, not a
substitute for the personal password. Existing legacy installations remain
legacy until explicit enrollment. No migration is triggered from a remote event.

MainActivity, Claude UI and navigation are unchanged. Enrollment/change/unlock
controls are the explicit integration points for the separate UI project. An
already enrolled vault will reject the old biometric-only UI flow; do not replace
that rejection with an automatic reset or silent legacy fallback.

## Cryptographic format

See [ADR](adr/ADR-vault-password.md). Records retain VaultCodec AES-GCM with
bucket/address AAD; HMAC-SHA256 indexes retain their existing Keystore key.
The initial legacy migration must recipher records because the existing AES key
is non-exportable. Later changes affect only a 148-byte protection envelope.

Binary envelope, big-endian, no optional/duplicate fields or trailing bytes:

| Offset | Bytes | Meaning |
|---|---:|---|
| 0 | 4 | magic 0x554d5057 |
| 4 | 4 | format 1 |
| 8 | 4 | Argon2 version 0x13 (Argon2id only) |
| 12 | 4 | memory KiB |
| 16 | 4 | passes |
| 20 | 4 | lanes |
| 24 | 4 | output length 32 |
| 28 | 16 | random salt |
| 44 | 16 | random wrapping identifier |
| 60 | 12 | device-layer GCM nonce |
| 72 | 76 | device ciphertext/tag covering password-layer nonce/ciphertext/tag |

Inner plaintext is the 32-byte random DEK. Each layer authenticates all 60 header
bytes prefixed by `UMBRA-password-v1/password\0` or `UMBRA-password-v1/device\0`.
Nonces are provider-generated, independent of the salt. Device authentication
precedes KDF allocation on unlock. Accepted profiles: 65536–131072 KiB (multiples
of 1024), 3–6 passes, 4 lanes; default 65536/3/4. Format rejects other algorithms,
versions, lengths and out-of-bound costs. No production/test weak profile exists.

The relay receives none of this material. Each device has its own envelope,
password and data key. Passwords never replace Signal keys or ratchets.

## Failure, migration and recovery limits

Wrong password/tag/wrapping returns generic `Vault unlock failed`. Missing key may
be reported as KEY_UNAVAILABLE; malformed local metadata can be CORRUPT. Neither
state authorizes resetting identity. Lock invalidates stale leases; new instances
and actual restarted processes have no DEK. No reusable unlock secret is persisted.

Migration swaps staged record storage and protection metadata in one SQLite
transaction. Failure rolls back; lock must win before commit. Password change
updates metadata atomically; injected SQLite abort must retain the old envelope.
A commit already linearized before lock remains committed. Death during actual
SQLite commit is a separate test from reopening or force-stop between operations.

No recovery/export/admin/reset API exists. Forgotten password or lost device key
means inaccessible data. No destructive failed-attempt counter. No assurance
against compromised OS, keylogger, memory extraction of a legitimately unlocked
process, or privileged whole-storage rollback. Historical pre-enrollment copies
still use legacy device-only protection. Changing a password does not invalidate
an attacker-restored old envelope snapshot; no rollback-resistant counter exists.

Java and providers may copy sensitive bytes. Owned arrays/Argon2 blocks are cleared
best-effort; no forensic erasure claim. SQLite categories/sizes remain observable.
Keystore hardware/authentication requirements are not relaxed. Synthetic AVD tests
create test-only AndroidKeyStore keys in separate debug/lab application IDs; they
do not validate TEE, StrongBox or production biometric interaction.

## Dependency, calibration and release

BC `bcprov-jdk15to18:1.86`, pure Java lightweight API, MIT-style license, no transitives
or native ABI. Build verifies exact SHA-256 (ADR). No global JCA-provider replacement.
R8 keeps only the public domain entry points pending UI integration. The dedicated
`.vaultlab` APK retains test-referenced signatures with optimization/obfuscation
allowed; it is not the exact production APK and contains no production user data.

`run_password_tests.py` runs the actual Vault cases, then
`run_password_restart.py` kills an unlocked process and verifies reopening requires
both factors. The latter measures three unlocks at production parameters and a
5-ms sampled Java heap peak, not total/native peak RSS or a hardware benchmark.
Measurements must be read from actual CI reports. No timing/memory value is assumed
from RFC recommendations. No KDF runs merely to display the initial locked state.

Storage corruption is also guarded below record AEAD: Vault overrides Android's
default delete-and-reopen corruption handler and refuses fresh schema creation
for an existing truncated/version-zero database. It does not automatically repair
or delete such files. The key-cleanup timer uses the earlier of the configured
interval and the original AccessGate authentication deadline; KDF time cannot
extend it. A damaged file is distinct from an explicit future user-approved reset.

The debug laboratory additionally uninstalls/reinstalls each target APK and
restores only its bounded synthetic encrypted database. The correct password
must then fail without deleted device keys; no aliases may be regenerated.
This is a real package reinstall on an AVD, not recovery or a production export
feature. The encrypted fixture stays transient in the host process, is never
uploaded as evidence, and is deleted from the AVD after assertions. R8 performs
force-stop and key-loss tests separately; no R8 reinstall claim is implied.

## Logging regression guard

`check_source_policy.py` rejects direct Android/Java logging sinks in the password
envelope, Vault, VaultCodec and SignalStore production sources. Missing protected
sources also fail. The guard reports paths only, never a matched value. A host
regression injects synthetic logging calls to verify rejection. This narrow
source check complements review; it is not whole-program data-flow analysis and
does not prove secrecy in a compromised runtime.
