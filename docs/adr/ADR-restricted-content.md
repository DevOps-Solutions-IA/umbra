# Restricted content v1 — evolving implementation checkpoint

The implementation must be shared by picture, voice note, short video and PDF.
It is an application policy for cooperating clients, not DRM against a modified
recipient, OS/root or external camera. Claude owns presentation integration.

## Boundaries chosen before implementation

A new `restricted` Signal content kind must be rejected by old clients. It must
not enter the ordinary message/file/export path. One exact verified, admitted,
authorised device receives it; no identity fanout. Engine authenticates ingress
and the descriptor binds sender/device, recipient/device, object ID, format,
policy, created/expiry times and maximum session duration. Alias grants nothing.

Use the existing standard JCA AES-256-GCM helper with an independent random
256-bit object key, provider-generated 96-bit nonce and domain-separated AAD
containing the canonical descriptor hash. Key plus descriptor plus ciphertext
travel inside the real Signal ciphertext. Relay stores opaque existing envelopes.
No custom cipher, no key derived from Signal state and no new plaintext endpoint.

Keep existing 256KiB attachment limit for v1. Prepared objects larger than this
are rejected, not fragmented implicitly. A single bounded immutable encrypted
payload avoids partial-object display and ratchet regeneration on retry. Future
larger transfer needs a separately versioned quota/chunk protocol. Small payloads
still require genuine codec/renderer validation and real decoded output tests.

Object lifetime 60s–24h; session lifetime 1–60s, bounded additionally by object
expiry and original vault authorization. Use monotonic session deadlines; wall
clock rollback during a session must fail closed. No background restoration.
One-use consumes durably before decoder access. Failure to commit grants no
session; crash after commit can lose the opening. Concurrent calls cannot obtain
two permits. Repeatable-in-UMBRA permits a later explicit session but not parallel
sessions or export. Active marks must not be bypassed by constructing a second
Engine over the same Records.

Maintain bounded authenticated descriptor/digest tombstones until object expiry
plus accepted clock skew. Never evict live tombstones to admit new objects;
capacity exhaustion rejects. Missing content behind a tombstone fails closed.
Consume does not mean displayed, completed or human attention. Sender outbox holds
only Signal ciphertext, no restricted payload in ordinary history. Originals
selected outside UMBRA remain untouched and may exist in user backups.

## Decoder ownership / release

Domain APIs return opaque sessions, not public byte arrays. Only internal
nonvisual adapters may read decrypted bytes through a current session. Register
real resources with the existing emergency coordinator before starting; resource
failure leaves closure incomplete. No exported URI, FileProvider, share intent,
clipboard, seek/repeat API or external viewer for restricted objects. Network,
Nearby, location and capture consent remain independent.

Session-integrated PNG and AAC adapters have scoped Android and transport
acceptance recorded in the dated evidence. PDF-derived pages are under native
validation; restricted video files remain unimplemented. PDF requires a seekable
read-only in-memory/proxy descriptor; plaintext disk staging is forbidden. Codecs
must have duration/resolution/output bounds and stop on route/focus loss. No
claims of acceptance until real Android debug/R8 and transport tests execute.

## Initial implementation checkpoint limits

The original implementation accepted PNG only. The current parser additionally
accepts AAC_ADTS and the pending-validation PDF_PAGES static document profile.
Video files remain unimplemented and must not be advertised. Tombstones are currently retained
indefinitely with a 4096 total cap (fail closed on exhaustion); only expired
payloads are purged. No early tombstone eviction or silent reset. At most four
persistent busy reservations may overlap; repeatable objects require waiting
until the previous session reservation ends even after early close/restart.
This conservative cooldown avoids cross-Engine/crash double grants without
restoring a capture session. UI must distinguish this from permanently consumed.

Session invalidation is immediate; native cleanup is scheduled on a bounded
single worker and confirmed separately. A native operation may finish in flight;
the emergency coordinator retains its existing incomplete/timeout semantics.
Periodic 250ms checks and all decoder boundaries revalidate local authorization;
this is a design bound to measure, not an observed cancellation guarantee.
Ordinary clipboard export deliberately accepts only a stored, unexpired text
message and never arbitrary restricted bytes. Full cumulative media/PDF acceptance remains outstanding; consult per-SHA
evidence rather than treating all formats as validated.
