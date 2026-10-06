# Pairing product v1 — candidate design, 2026-10-02

Base c117051ac5353c84ef840d9b5f1eafa81f86ddb4. This is not physical acceptance.
Keep the signed invite/request/ack protocol and libsignal identity unchanged.
File, QR and human-code entry must converge on the existing atomic contact commits.
A contact is created at accept on inviter, complete on joiner, always UNVERIFIED
for a new contact. An exact retry must not produce a different transcript.

## Admission decision

Preserve existing Engine.importCard rejection of nonempty invalid/foreign-realm
membership evidence. No silent credential discard, authority replacement or
implicit admission. Both unconfigured devices can exchange empty-evidence cards;
private messaging still requires independent local/peer admission and VERIFIED.
Mixed admission works only if the non-admitted party already pins the same realm.
The product boundary must explain mismatch before presenting completion. A future
cross-realm contact-only import is a separate compatibility decision; not silently
introduced here. File UI/lifecycle wiring remains a Claude integration gap.

## Candidate rendezvous

Connected only, explicit online consent, admitted relay requests remain mandatory.
No pre-admission allowlist expansion. A configured HTTPS relay is a prerequisite,
not embedded in an untrusted scanned invite. No automatic DNS/network on parsing.
The relay receives bounded encrypted blobs, opaque locators/capability hashes,
expiry and request digests. It is not an authority for contact identity or trust.
Offline retains file/QR payload processing without camera/network permissions.

Human code: 16 independently uniform characters from a fixed unambiguous 32-symbol
alphabet (80 bits), SecureRandom; only ASCII case, spaces and hyphens normalize.
TTL 600 seconds maximum, bounded by signed invitation validity. HKDF-SHA256 via
existing Bouncy Castle implementation, not hand-written HKDF: separate locator,
read capability and invite-encryption key domains. AES-256-GCM with fresh 96-bit
nonce and 128-bit tag; immutable ciphertext on exact retry. Request/ack keys derive
from the signed invitation's existing independent 256-bit nonce, never from the
human code or Signal ratchet. Canonical AAD includes version, direction, invitation
ID and full signed-invitation SHA256. Invite wrapper carries public ID/digest so
AAD can be reconstructed before decrypt and must match the decrypted signed invite.
No primitive/dependency upgrade. References inspected: RFC5869 and BC
HKDFBytesGenerator API; implementation API must be checked against pinned jar.

Server first claimant is an availability boundary, not proof of a valid transcript.
Only inviter PairingService.accept may commit one valid request/contact/ack. Never
report server reservation as pairing success. Tombstones survive until expiry;
quotas, authenticated admission, exact retries and rejection must be tested.

## Acceptance boundaries

No production UI edits or installation without fresh per-APK permission. Physical
runner must select two distinct USB physical devices, reject emulator/TCP identities,
validate hashes/packages and emit only redacted evidence. Product completion awaits
actual QR/code/file, human verification, bidirectional message/ACK and restart on
two physical peers. JVM, SQLite/AVD, QR rendering and camera decoding are separate.
