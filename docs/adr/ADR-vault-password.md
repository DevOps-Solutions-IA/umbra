# ADR — Personal password and device-bound vault v1

Status: implementation in progress; no acceptance or hardware claim.
Base: 6b0a844aa0a97d4984d04a58330621f37c5291d1 (PR #10).

## Audit before implementation

`data/Vault.java` is SQLiteOpenHelper schema 2 and the production Records
implementation. `core/VaultCodec.java` uses AES-256-GCM with address/version AAD
and randomized 96-bit nonces. Its data key `umbra.vault.v1` is directly in Android
Keystore, not an exportable wrapped DEK. `umbra.index.v2` blinds addresses with
HMAC-SHA256. Both keys require Android authentication, unlocked device and
TEE/StrongBox; missing keys with existing data reject creation. StrongBox
unavailability permits TEE, never software. `prepareKey`/`destroyKey` serialize
alias lifecycle. Schema 1→2 validates/decrypts/reindexes transactionally.

Engine and SignalStore use Records, so identity, prekeys, ratchets, outbox and
application state share Vault transactions. AccessGate epochs cancel stale work
and linearize commit with lock. MainActivity currently grants this gate after
Android authentication and locks at pause/240 seconds. It does not have a personal
password. Both flavors share Vault. SQLite failure does not authorize replacing
keys or resetting identity. SQLite remains an encrypted-record store, not whole
file encryption; category/size metadata remains visible.

## Decision and migration

Keep index key and record AEAD. Introduce a random 32-byte DEK for enrolled vaults.
Because the old key is non-exportable, enrollment must decrypt/reseal existing
records once, in the same SQLite transaction as a versioned protection envelope.
Never claim this is a rewrap of the old Keystore key. Subsequent password changes
rewrap only the DEK. Preserve logical records/Signal identity/ratchet bytes.

Envelope = header || deviceNonce || AES-GCM(deviceKey,
passwordNonce || AES-GCM(Argon2id(password,salt,parameters), DEK, header), header).
Both independent authenticated layers use domain-separated AAD and random nonces.
Reuse the existing device AES handle for outer wrapping; no exported Keystore key,
no arbitrary key combination, no recovery copy. Metadata is bounded before KDF.
No fallback from an enrolled envelope to legacy records on failure. Deleting
metadata cannot decrypt records sealed under the random DEK with the old key.

Legacy enrollment is explicit and needs an authenticated valid legacy vault.
Rollback preserves old records if migration fails before commit. SQLite atomicity
does not constitute a tested process kill during fsync. Historical copies of the
legacy database remain under the legacy protection; this upgrade does not erase
backups or defeat privileged rollback of the complete device state. Old wrapping
snapshots can also roll back a password change; no hardware monotonic counter is
claimed. Changing a password invalidates the old password for the current envelope.

## KDF decision

Use Bouncy Castle lightweight Argon2id API, not a registered JCA provider:
`org.bouncycastle:bcprov-jdk15to18:1.86`, pure Java (no ABI/JNI), no POM dependencies.
The Java 8 distribution avoids multi-release jar entries on Android. MIT-style
Bouncy Castle license. Artifact/source provenance is Maven Central linked by the
upstream download page. Binary SHA256:
`fc50334d4d87b4272e72fa95ca748ead05bdef02ef760a5b13f64ffee317cd81`.
Source SHA256: `434b5d4b25811177d950fb66640133950d749d4121c8ca49c2d89e9ffc5314db`.
Android/R8 compatibility and footprint require actual build/run evidence.

Initial profile: Argon2id version 0x13, 65536 KiB, 3 passes, 4 lanes, 32-byte
output, random 16-byte salt. This is RFC 9106's second recommended profile,
not a measured guarantee of usability. Calibration measures this profile; it
must not silently lower it. Serialize KDF operations to bound memory. Password
input is bounded UTF-8 bytes, never retained in Strings; caller owns input wiping.
BC clears its primary block pool on normal completion; Java/JCA copies and
exception paths prevent any forensic RAM-erasure promise.

Sources checked 2026-09-26:
- https://www.rfc-editor.org/rfc/rfc9106.html
- https://www.bouncycastle.org/download/bouncy-castle-java/
- https://www.bouncycastle.org/about/license/
- https://github.com/bcgit/bc-java (Argon2BytesGenerator source from pinned jar)

## Domain boundary / UI integration

Vault will expose state/enrollment/unlock/change/lock/autolock without salts or
aliases in UI. Android authentication remains required in addition to password.
After enrollment, opening AccessGate from a UI callback cannot recreate the DEK.
Password operations run off the main thread and revalidate their original epoch
before commit/publication. Lock/re-authentication clears the held DEK and cancels
old work. New Vault instances/processes hold no DEK.

MainActivity and Claude UI remain untouched. Future UI must obtain Android system
authentication, then call password unlock before constructing/using Engine;
explicit enrollment and password-change controls also remain UI integration points.
No biometrics-only path for enrolled data. No server/password API, no recovery,
no automatic erasure, no password synchronization between devices. A compromised
OS/process can observe passwords or plaintext while legitimately unlocked.

## Dependency review and exceptional cleanup

Upstream 1.86 release notes (2026-09-26 review) include a fix for unbounded
password-KDF cost inputs (CVE-2026-17508); UMBRA independently authenticates and
bounds parameters before allocating Argon2 memory. The release also addresses
other algorithm/protocol issues not used by this integration. This is a scoped
review, not a claim that all future vulnerabilities are absent.
https://www.bouncycastle.org/resources/new-release-bouncy-castle-java-1-86/

The supported BC BlockPool API tracks this invocation's blocks and clears them
in finally, including exceptional paths. No algorithm is reimplemented. No
provider is registered/replaced, so AndroidKeyStore and libsignal are unchanged.
A bounded allocation failure rejects unlock, never falls back to a weaker KDF.
The parameters deliberately retain the RFC profile in instrumented tests.

### Audit correction: Android corruption handler

The inherited default SQLiteOpenHelper error handler was destructive on malformed
SQLite files, despite application-level record rejection. Replace it with a
non-deleting handler in both Vault and prepareKey's read-only probe. Refuse onCreate
for a database path that already existed, including truncated/version-zero files.
A separate synthetic control reproduces Android's default; the production Vault
must preserve damaged bytes and never silently create a replacement identity.
