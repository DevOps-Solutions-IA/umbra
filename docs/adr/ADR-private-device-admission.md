# ADR — Private device admission v1

Date: 2026-09-27. Status: implementation in progress, not an acceptance report.
Base: `0de2b102d2d09b1eeed0e4fe233d3b27976e1036`, PR #11.

## Audit before changing structure

`Engine` and `SignalStore` retain libsignal identities, prekeys and ratchets in
`Records`. `Vault` encrypts those records with AES-GCM and blinds indices with
Keystore HMAC. Password enrollment wraps a random DEK with Argon2id and a
hardware-required Keystore key; authorization leases invalidate after lock.
Admission must use these transactions and leases, not cache an unlocked identity.

`DeviceService` authenticates a versioned logical-identity roster using the root
Signal identity. That authority manages its own devices, not realm membership.
`PairingService` invitations authorize contact linking, not admission or human
verification. Both have bounded canonical, purpose-separated transcripts.
`DeviceTranscript` uses libsignal XEdDSA; it is not interchangeable with Ed25519.

`BluetoothLink` currently proves the Signal identity over fresh nonces and roles.
This remains necessary in addition to admission. `RelayClient` and all relay
mailbox, ACK, pairing and delegated-revocation endpoints currently use opaque
capabilities. These capabilities alone do not prove membership. Attachments,
location and call signaling use those same opaque encrypted envelopes.
`LocationService` and `CallService` recheck Engine/DevicePolicy authorization and
leases. Native media additionally requires a selected peer and local consent.
There is **no production TURN credential issuance endpoint**: `VoiceControls`
accepts local authorized configuration and the test host provisions ephemeral
coturn credentials. No claim of protecting a nonexistent issuer will be made.

## Decision

Use standard Ed25519 (RFC 8032), implemented by the **already pinned** Bouncy
Castle 1.86 lightweight API, for two independent keys: realm authority and a
per-device admission proof key. Do not convert or reuse Signal private keys.
A credential binds the admission public key to the exact serialized Signal
public key (and hence its existing device identifier). Existing Signal proofs
and contact verification remain mandatory; admission grants neither.

The authority seed and the device proof seed live only in encrypted Records in
the respective administrator/device vault. The relay gets only public keys.
No administrator seed is embedded in any build. Public realm configuration is
pinned by explicit local provisioning; a different authority fails closed.
There is one authority; losing it has no recovery or automatic rotation path.

Reuse the positional ASCII / canonical base64url / domain-prefix pattern of
DeviceTranscript with a distinct `UMBRA-ADMISSION-<TYPE>-1` domain. Exact field
counts reject extensions until a protocol version explicitly supports them.
Random realm, request, credential and challenge identifiers are 256 bits.
Known capabilities are explicit, never an unknown-bit wildcard. Signed public
objects are not bearer authentication: service requests also require a fresh,
one-use possession proof bound to the intended operation and verifier.

A request lasts at most ten minutes, limiting exposure of a stolen provisioning
payload while allowing a local review. Credentials last at most seven days,
matching the existing bounded offline roster horizon: shorter validity reduces
stale offline authorization but imposes regular explicit administrator renewal.
This is a deployment tradeoff, not instant revocation. Online revocations apply
at the relay transaction boundary; offline peers apply them only once received.
Clock rollback and a malicious OS remain limitations of offline time validation.
Renewal requires a new request and approval, never replay of an old credential.
Per-credential revocation is terminal and does not erase history or revoke other
devices. Tombstones must be retained while old credentials can be accepted.

## Integration requirements, not yet claims of completion

- Unconfigured/unadmitted Engine and private relay operations fail closed.
- Relay authentication adds admission to existing capabilities, not replaces them.
  Bind PoP to realm, credential, device, verifier, fresh challenge and operation.
  Recheck revocation after SQLite transaction acquisition, not only middleware.
- RFCOMM retains the real Bluetooth transport and Signal proof, adds signed
  credential plus admission-key proof; reject legacy membership bypass.
- New linked devices start unadmitted, with their own admission seed/credential.
- Lock prevents signing and invalidates any pending administrative consent.
- Update fixtures with genuine synthetic signed admission, never a production
  bypass switch. Exercise negative unprovisioned cases separately.
- No MainActivity or Claude UI edits; expose domain APIs for later UI wiring.

## Limits and verification

Compromised administrator can admit devices, but receives no Signal private
keys, vault DEKs, message plaintext or attachment keys. Compromised relay can
withhold/replay public state or deny service, but cannot sign new credentials.
Admission is not contact verification, device linking or future group membership.
Revocation cannot erase received copies. No anonymity or compromised-OS security
is claimed. AVD Keystore and RFCOMM results are separate from physical hardware.

Sources: [RFC 8032](https://www.rfc-editor.org/rfc/rfc8032),
[Bouncy Castle Java](https://www.bouncycastle.org/download/bouncy-castle-java/),
[Python cryptography Ed25519](https://cryptography.io/en/latest/hazmat/primitives/asymmetric/ed25519/).
The existing BC artifact SHA-256 is
`fc50334d4d87b4272e72fa95ca748ead05bdef02ef760a5b13f64ffee317cd81`;
no Android dependency change or network permission is required for signatures.

## Implementation review findings

The initially evaluated Python `cryptography` 50.0.1/OpenSSL verify-only path
accepted synthetic degenerate public points with a constructed signature. This
was reproduced before publication. It was **not adopted** for admission.
The backend instead pins **PyNaCl 1.6.2**, using libsodium's full
`crypto_core_ed25519_is_valid_point` check and Ed25519 verification. Android uses
BC `Ed25519.validatePublicKeyFull` and verification. Regressions reject zero and
identity points in both implementations. No handwritten curve arithmetic or
conversion from Signal keys is introduced.

PyNaCl uses Apache-2.0, libsodium ISC; release 1.6.2 bundles libsodium
1.0.20-stable (2025-12-31 build), fixing CVE-2025-69277 according to its
[release notes](https://pynacl.readthedocs.io/en/latest/changelog/).
Runtime transitive pins: cffi 2.0.0 (MIT), pycparser 3.0 (BSD). Exact wheel hashes
from PyPI are in `relay/requirements-hashed.lock`; binary-only, hash-required
installation remains enabled. These are relay/laboratory dependencies, not APK
or offline network dependencies. This is not an assertion that no other defects
exist. Update by reviewing upstream changes, lock hashes and cross-language
negative vectors, then repeating container and client integration.

HTTP challenges are issued in atomic pools of eight, each random, bound to one
credential/verifier and valid for 30 seconds. A pool entry initially reserves an
unassigned operation hash; the device fills in the exact operation **before**
signing. The verifier checks every remaining issued field, actual operation and
single-use state. This amortizes control traffic without increasing the existing
240/minute ingress limit. A verifier restart invalidates prior runtime nonces;
the client may obtain one replacement pool and retry the frozen request once,
with its original vault lease. Ordinary authorization errors never trigger that
retry. Single-worker SQLite deployment remains required.

Contact card v2 optionally carries a separate authority-signed credential inside
the Signal-signed card body. Import does not admit the importing device or grant
VERIFIED trust. Legacy card v1 remains an identity/pairing mechanism; private
operations still require separately installed membership. RFCOMM hello v3 adds
a bounded composite proof (Signal signature plus admission-key signature over
the hash of the original nonce/role/identity transcript). Legacy hello cannot
bypass the new admission gate.
