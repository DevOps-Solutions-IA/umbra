# Private device admission v1

Status: implementation under validation. This document is not a production or
final-CI acceptance claim. See the dated validation report for executed layers.

Admission is permission for one device to use a private realm. It is independent
of human contact verification, logical-identity device linking and future chat
groups. Installing the APK, importing a realm, copying a public credential or
linking A2 to A1 does not admit A2. Each device retains its own Signal identity,
ratchets, vault password and separate admission proof key.

## Keys and storage

`AdmissionService` uses the existing `Records`/Vault transactions and unlock
leases. Production authority and device seeds are encrypted vault records. The
password/Keystore policy is unchanged; no signing occurs through a locked vault.
Only a deliberate administrator action creates a realm authority. The authority
has a dedicated Ed25519 key, unrelated to Signal, vault AES/HMAC, TURN or TLS.
The relay stores public configuration and signed objects, never the authority
seed. The administrator receives no Signal private keys, ratchets, DEKs, message
plaintext, attachment keys or passwords.

The single authority's loss has no recovery path. Unexpected authority changes
fail closed. Rotation, threshold administrators and recovery are not implemented.
A compromised administrator can admit an attacker; admission still does not
supply contact verification or other people's private keys. A compromised OS can
observe an unlocked application's memory; this feature does not prevent that.

## Canonical public protocol

All identifiers and random nonces use 32 random bytes encoded as canonical
unpadded base64url. Ed25519 public keys use 32 bytes, signatures 64. Signal public
keys preserve libsignal's serialized 33-byte format; device IDs remain SHA-256
of that exact serialization. No conversion between XEdDSA and Ed25519 occurs.

Realm configuration is exactly:
`umbra:realm:1:<realmId>:<authorityPublicKey>:<authorityKeyId>`.
The key ID is the lowercase SHA-256 of the raw authority public key. Explicit,
authenticated provisioning outside the relay pins this configuration. Retrieving
it over HTTPS does not install it or change an existing pin.

Other signed objects use
`umbra:admission:<type>:1:<base64url(body)>.<base64url(signature)>`.
Body is ASCII `UMBRA-ADMISSION-<type>-1\n`, followed by the exact ordered fields
below, each followed by LF, including the final field. No duplicate fields,
extra fields, alternate base64, CRLF, whitespace, unknown versions or capabilities
are accepted. Maximum wire length is 4096 characters and body length 2048 bytes.
Numbers are positive decimal without leading zero, at most 12 digits.

| Type | Ordered fields | Signer |
| --- | --- | --- |
| request | realmId, requestId, devicePublicKey, signalPublicKey, createdAt, expiresAt, nonce | Device admission key |
| credential | realmId, credentialId, devicePublicKey, signalPublicKey, issuerKeyId, issuedAt, notBefore, expiresAt, capabilities, requestId | Authority |
| revocation | realmId, credentialId, devicePublicKey, issuerKeyId, sequence, revokedAt, reason | Authority |
| rejection | realmId, requestId, requestHash, devicePublicKey, issuerKeyId, rejectedAt | Authority |
| proof | realmId, credentialId, credentialHash, nonce, verifierHash, operationHash, issuedAt, expiresAt | Device admission key |
| request-proof | Same challenge fields, with requestId/requestHash instead of credentialId/credentialHash | Requesting device |
| nearby | realmId, credentialId, credentialHash, existingSignalTranscriptHash | Device admission key |

Capabilities v1 has exactly one known profile: `relay.nearby.turn`. It does not
grant administrative signing, mailbox read capabilities, VERIFIED contact trust
or media consent. Unknown or expanded values fail. Revocation reasons are fixed:
`owner_request`, `device_lost`, `policy`; they never mean delete stored messages.

Requests last ten minutes. Review and approval revalidate expiry after acquiring
the transaction and before persisting. Credentials last 60 seconds to seven days;
seven days is the maximum offline authorization horizon, not a freshness promise.
There is no automatic renewal. Explicit renewal consumes a new signed request,
creates a new credential ID and revokes the old credential. Local and relay
renewal bundle operations commit both changes atomically. Offline recipients must
receive the signed revocation; an unsynchronized peer may still accept an old,
unexpired credential.

## Proof of possession and HTTP

A credential is public and is never accepted alone as authentication. The relay
issues unpredictable challenges valid for 30 seconds and stores their use state
in SQLite. It also records a monotonic deadline and verifier-process identifier;
restart invalidates earlier challenges. Eight-entry pools amortize requests
without raising the existing ingress rate limit. The pool's all-zero operation
hash is only an unassigned placeholder: the client fills the exact operation
before signing; the server verifies it against the actual request. Every nonce
is still single-use. No wildcard operation signature is accepted.

`verifierHash` is SHA-256 of the explicitly configured HTTPS origin without a
trailing slash. HTTP operation hash is SHA-256 of this ASCII transcript:

```text
UMBRA-ADMISSION-HTTP-1\n
<METHOD>\n
<base64url(exact raw path plus query)>\n
<sha256(exact body bytes)>\n
<sha256(Authorization header bytes, or empty bytes)>\n
```

Headers `X-Umbra-Credential`, `X-Umbra-Challenge` and `X-Umbra-Proof` carry the
public credential, challenge and signed response. Duplicate authentication
headers fail. A challenge is consumed before entering the protected handler;
credential expiry/revocation is checked again after transaction acquisition and
before commit. Mailbox capabilities remain independently necessary. A failed
business operation does not authorize a proof replay. After verifier restart,
the client permits one fresh-challenge retry with the original lease and frozen
request; it does not recipher or advance a ratchet for that retry.

The gate denies all routes except an exact method/path public allowlist:
health; realm public config; signed request submission; signed authority decision
publication (credentials/rejections/revocations/renewals); proof challenge issuance;
and challenge-authenticated retrieval of one's own request result. There is no
public user directory or request enumeration endpoint. The result proof has a
different signature purpose from service authentication and cannot admit anyone.

Private mailbox registration, envelope PUT/GET, ACK, deletion, contact invitation
registration/claim/revocation and device capability endpoints all pass this gate.
Location, attachments and call signaling remain encrypted envelope content; no
new plaintext payload tables exist. Unknown future routes are denied by default
before routing. `/healthz` remains public for container health checks.

There is **no production TURN issuance service** in this base. Local media still
requires `CallService`/Engine authorization, now including admission, before
using configured TURN. The existing ephemeral coturn laboratory remains a test
facility. Rejection of an unadmitted request to an unknown TURN URL is not a claim
that a production credential issuer has been implemented. RELAY_ONLY, DTLS and
certificate behavior are unchanged.

## Relay deployment and migration

Set `UMBRA_ADMISSION_REALM` to the public RealmConfig and
`UMBRA_ADMISSION_ORIGIN` to the exact HTTPS origin clients use. Configure both or
neither; missing configuration leaves private APIs closed. Neither value is a
private key. Existing mailbox capabilities are preserved but no longer sufficient.

Schema v4 migrates transactionally to v5 with admission realm, requests,
credentials, revocations and challenge tables. Unknown/future/incomplete schemas
fail before migration; rollback retains the old schema. The relay does not
manufacture credentials from its SQLite rows. Validation uses pinned PyNaCl
1.6.2/libsodium with full Ed25519 point checks; Android uses existing BC 1.86.
See the ADR and hashed dependency lock for provenance and the degenerate-key
regression that informed this choice.

Keep one worker for this SQLite deployment. Challenge runtime identity is local
to the verifier process; multiple independent workers are not supported. Public
admission is bounded by the existing header/body/deadline/IP rate and active
request caps, plus 4096 requests/credentials and 1024 live challenge rows, at most
eight outstanding per subject. Request-ID/nonce and revocation tombstones are
retained, with bounded capacity failing closed rather than silently recycling
identifiers. This first bounded deployment can be exhausted by hostile request
submission; capacity/retention evolution needs a separate reviewed protocol and
is not unlimited-service availability. No raw IP, names, aliases, email or phone
are added to admission storage or logs.

## Nearby and offline

RFCOMM hello v3 exchanges the original Signal proof plus a signed credential and
an admission-key proof. The latter binds the hash of the original role, both
Signal IDs and both fresh nonces. No TCP replacement or identity bypass exists.
Legacy hello is rejected. Contact card v2 can carry distinct public membership
evidence inside its Signal-signed body; v1 remains an old identity card, not an
admission credential or one-use invitation. Import never admits the importer or
grants human verification.

Offline uses the same public key checks and local encrypted vault, without new
network permissions or dependencies. Provisioning/revocation can be exported and
imported as bounded payloads through the domain APIs. A peer applies a revocation
only after learning it; wall-clock rollback or an offline OS under attacker control
can defeat local freshness assumptions. No instant offline revocation or remote
erasure is promised. Linking another device never copies the admission seed.

## APIs and UI boundary

`Engine.admission()` exposes `AdmissionService`: state/realm access, explicit
realm creation/import, request creation, administrative review/approval/rejection,
credential/rejection installation, explicit renewal, signed revocation creation
and import, local/peer authorization, and possession proof operations. Review
objects carry the device and identity fingerprints and a captured vault lease.
`RelayClient` adds signed-public provisioning and own-result retrieval; protected
requests require its AdmissionService constructor argument.

No MainActivity, Claude UI, navigation or design-system changes are included.
The existing UI must later wire these APIs and construct authenticated RelayClient
instances. Its old constructor cannot use private APIs. No default realm or
administrator secret is embedded to conceal that pending integration.

## Testing distinctions

Synthetic signing fixtures are test/laboratory sources only. MemoryRecords is
not durable SQLite. SqliteDeviceRecords uses plaintext synthetic records and is
not production encrypted storage. The dedicated Vault test uses actual SQLite,
AES-GCM and AndroidKeyStore with test-created software-capable keys; it does not
weaken production key preparation or prove TEE/StrongBox. Physical hardware,
radio and independent security review remain separate acceptance requirements.
