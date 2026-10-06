# R01-B1 — candidate reply payload and compatibility probes

Status: PROPOSED / PARALLEL / NOT ACTIVE IN PRODUCT.
Starting R01 commit: 7d7bfa7dc6d4325babc744f356bdf728a35b96f2.
Base main: 4fdd338f3fff4c6864ea820ca85cba6cf284b8da.

## Implemented in this isolated unit

ReplyPayload.decode accepts the bytes of an ALREADY DECRYPTED per-device v1
payload, the existing outer envelope, the expected local identity and current
wall-clock seconds. It does not decrypt, authenticate a peer, grant authorization,
perform I/O, establish peer support or prove the original message exists.

Proposed exact plaintext fields:
`v,id,from,to,created,createdMs,expires,kind,text,replyTo`.
`v` must be integer 1; `kind` must be reply. `replyTo` is the exact R01-A reference
object with v/id/from/to, without quote, attachment, name, timestamp or preview.
The existing relay envelope remains v/id/from/to/type/ct/expires, with no new
plaintext reply metadata. The eventual sender must put the reference INSIDE Signal
ciphertext and retain existing padding and immutable-ciphertext retry behavior.

The decoder requires the current envelope schema and recipient, exact inner/outer
id/from/to/expires bindings, canonical identifiers, distinct participants, bounded
clock skew/lifetime, integer timestamp types and a 16,000 UTF-8 byte text maximum.
The strict raw-JSON boundary is capped at 100,000 bytes to allow worst-case escaping
of that text plus bounded reference/headers. Arithmetic rejects impossible clock
ranges before signed-long operations. Duplicates, coercions, extra fields, foreign
conversations, logical v2 fanout and same-direction self-reference are rejected.
Opposite-direction UUID equality is not confused with same-message identity.

Returned values do not retain the mutable input JSON. Log representation is
redacted; rejection messages are fixed and do not expose parser causes or content.
Plaintext ownership/wiping remains the caller's responsibility; no forensic RAM
erasure is claimed for Java Strings.

## Compatibility observed, not compatibility negotiated

Wire and Engine are UNCHANGED. Current production reception intentionally rejects
kind=reply, including correctly encrypted proposed messages. It still accepts
ordinary text. ReplyPayloadSignalTest executes real libsignal/JNI with synthetic
transactional memory records and candidate-only direct SessionCipher helpers.
It exercises envelope binding, ciphertext tampering, third-party references,
rollback, out-of-order original delivery and compatibility refusal.

These are host/JVM proposal probes, NOT a production Engine.sendReply/receiveReply
implementation, real-device acceptance, Android SQLite durability or a new network
transport. A failed reply parse must remain inside the eventual shared transaction.
The original is never reconstructed from the reference: absent/deleted content
remains unavailable; late original arrival is handled by the existing local resolver.

## Required agreement before shared Engine/Wire changes

1. Define authenticated per-device support discovery with version and bounded expiry.
   Absence of a positive observation must not silently convert reply into ordinary
   text or imply support from an alias, pairing or VERIFIED. Any new control kind
   needs its own legacy/refusal and replay tests. Do not change signed cards silently.
2. With Y02 owner, define logicalId/logicalFrom/logicalTo and roster/version binding
   for fanout. The current candidate supports only the existing device-pair v1 case.
3. Agree exact Engine/Wire integration hunks and an atomic validation + ratchet +
   history + outbox + dedup/ACK transaction. Helper objects are not authorization.
4. Revalidate current lease, trust, admission, device revocation and target original
   at commit time. Review/snapshot does not authorize a later generation.
5. UI activation waits for those contracts, implementation, negative tests, exact-SHA
   acceptance and physical tests with approved APKs. Never send experimental replies
   to existing users from this branch.

## Isolation and delivery

No shared Engine/Wire/UI/Records/Vault/manifest/build/workflow files are changed.
MESSAGE_REPLY remains pending. No AVD, device operation, APK installation, VPS, DNS,
new PR or merge is part of this unit. Branch publication alone is permitted only
after workflow-trigger inspection; it is not CI evidence. No other agent agreement
is implied by this proposal. See the dated R01-B1 validation receipt for actual runs.
