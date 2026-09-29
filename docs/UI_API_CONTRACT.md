# Technical API contract for Claude — evolving master v4 checkpoint

This contract describes the cumulative production domain/adapters on
codex/security-content-completion, base ba75d329. Exact published/checked-out SHA
and acceptance are recorded in PR16 and dated validation, not inferred from
historical results below. Do not assume final acceptance while that matrix is red.
No Claude UI or production MainActivity was changed. All domain/storage/codec work
runs on a worker; Android Window/View configuration runs on the UI thread before
first presentation. Never render an async result from a stale screen/vault epoch.

## Contract revision: UI_SECURITY_CONTENT_API_V1

This label versions the documented integration surface; it is not a new wire
field, runtime bypass or final acceptance tag. It covers existing Vault/admission/
connectivity/emergency/privacy APIs and restricted format v1. No domain signature
changes were required by the latest expiry/storage/legacy-parser regressions.
Consumers must use the exact accepted source SHA from the final PR receipt, not
interpret this label as permission to mix arbitrary newer commits. Changes to
ownership, authorization, format bounds or method signatures require coordinated
contract revision plus compatibility/rejection tests.

## Existing security layers

`Vault.getVaultState()` exposes UNINITIALIZED/LOCKED/UNLOCKING/UNLOCKED/LOCKING/
CORRUPT/KEY_UNAVAILABLE. `isPasswordConfigured`, `createPassword(byte[])`,
`unlock(byte[])`, `changePassword(byte[],byte[])`, `lock`, `getAutoLockPolicy`,
`setAutoLockPolicy(long)` remain the existing password contract. See VAULT_PASSWORD.
Create/change finish locked. Keep platform authentication and hardware requirements.
Input is caller-owned UTF-8, 12–1024 bytes, no silent normalization; caller wipes
password arrays in finally. Autolock is process-local, configured only while locked,
1..240000 ms (default 240000); existing background lock remains immediate.
Do not use a presentation boolean to grant access. No recovery/reset endpoint.

Use `engine.admission()` for public realm/request/credential operations. Read
`status`, `isAdmissionAuthority`, `issuedCredentials`, `peerStatus` under the current
vault authorization; snapshots do not authorize a later action. The original
`Review` from `reviewAdmissionRequest(String)` must be passed to approve/reject/
renew. Lock invalidates that review. Use atomic own approval and renewal import
APIs described in ADMISSION_API_CHECKPOINT; never split their transactions in UI.

`engine.connectivity()` exposes PRIVATE_STARTUP_STRICT and states LOCKED_PRIVATE,
UNLOCKED_OFFLINE, CONNECTING, CONNECTED, DISCONNECTING, OFFLINE_ERROR. Unlock only
calls `vaultUnlocked`; separate explicit `connect(origin,true)` enables on-demand
online I/O. CONNECTED is permission, not reachability. `disconnect` keeps vault
open; `startNearby(true)` returns a separately owned Lease; `stopNearby` closes it.
No automatic retry/reconnect on network return, unlock, app restart or admission.

`engine.emergencyLock()` requires no password and returns coordinator Status.
Immediately invalidate/hide sensitive presentation; consult `engine.emergency().status()`
for INVALIDATED/CLOSING/CLOSED/INCOMPLETE. Only CLOSED confirms all registered
closures. INCOMPLETE blocks reauthentication. Use EMERGENCY_LOCK's authentication
protocol; never unlock merely to send a final packet or clear an error banner.

## Privacy adapters

`PrivateAndroidSurface.protect(Activity|Window|SurfaceView)` is invoked before
first sensitive content, including dialogs/surfaces. UI must erase its last frame
on invalidation; the codec cannot erase a Canvas owned by presentation. Current
laboratory verifies flags/configuration, not every screen of Claude's app.
`notification(Context,channel,icon)` builds generic content without message inputs.
`sensitiveInput(EditText)` disables app-level restore/autofill/content-capture and
IME personalized learning requests; this cannot control a compromised IME/OS.

`SensitiveBuffer(char[])` owns a copy, max 1024 chars; `take()` transfers ownership
once, caller must erase it; `close()` clears retained buffers best-effort.
`PrivateClipboard(Activity).reviewMessage(engine,peer,messageId)` captures original
message/peer/lease. `copyMessage(review,true)` is one-use consent (60s monotonic),
ordinary text only. `clearOwned()` clears only the focused owner's marked clip;
Android lacks atomic clipboard compare-and-clear, so no global-erasure promise.
`OrdinaryTextExport.export` is not authorization for restricted content.

## Restricted objects — PNG, AAC, static PDF and bounded file-video

`engine.restricted().reviewSend(String recipient, Mode mode, long ttlSeconds,
long sessionSeconds)` binds one exact verified/admitted device and vault generation.
TTL 60..86400 seconds, session 1..60 seconds. Consent review lasts 60 monotonic
seconds. `send(Review,Prepared,true)` transfers ownership of Prepared, invalidates
it on a transaction attempt and schedules wiping; `Prepared.closure()` confirms
cleanup. It encrypts per-object with AES-GCM inside the Signal transaction.
No ordinary history plaintext/preview or forwarding/export API is created.

Preparation adapters: `RestrictedImages.prepare(Engine,Review,byte[],boolean)`
accepts bounded JPEG/PNG and makes sanitized PNG; source remains caller-owned.
`RestrictedAudio.prepare(Engine,Review,byte[],boolean)` accepts the narrow AAC
profile and re-encodes it through Android, not WebRTC. Both run on a worker and
mint/check their authorization internally. UI cannot substitute a no-op callback.
Pass the SAME review to `send`; a new review cannot reassign a prepared object.
There are at most four pending prepared objects per Engine; close abandoned
ones. Domain invalidation immediately denies them, then wipes their bounded bytes
without waiting under the vault gate. `closure()` confirms cleanup. They expire
with the original review (60 seconds), never renew across lock/unlock. Capture
and PDF use the same pending-object ownership. See ADR-restricted-audio for limits.

`received(peer)` and `status(id)` return public policy/status metadata only.
`reviewOpen(id)` followed by `open(review,true)` consumes ONCE persistently before
returning Session; storage failure returns no session. Crash after consumption can
lose the opening, not restore it. UMBRA_ONLY allows a later session until expiry,
but no concurrent reopening before the prior busy deadline. Neither is exportable.

Session: `check`, `format`, `close`, `closure`. `close` immediately denies, then
asynchronously closes native resources; `closure()` confirms success or failure.
No public bytes/key/URI/file getters. One native decoder per session; max four
busy sessions, 128 stored objects, 4096 persistent tombstones. Exhaustion rejects
new objects; no premature tombstone eviction. Unknown format/version rejects.

`RestrictedImages.Decoder(Session).render(Canvas,Rect)` draws only while authorized;
close it on pause/abandon. `RestrictedPlayback(Context,Session,AudioDeviceInfo)` is
worker-created and one-shot `start`; no seek/replay/external player. States READY,
ROUTING, PLAYING, COMPLETED, INTERRUPTED, FAILED, CLOSED. It starts muted until
selected native route is confirmed; focus/route loss closes, never auto-resumes.
Native routed AAC playback and lock closure have passed earlier privacy checkpoints.
This does not cover physical acoustics or every route/focus-loss case. Completion
is not proof of human listening; the cumulative final SHA still requires CI.

Failures: `ContentException.Code` is INVALID, CONSENT_REQUIRED, CONSUMED, EXPIRED,
BUSY, CAPACITY, EXPORT_FORBIDDEN. `OperationFailure.classify` maps them to
INVALID_CONTENT, CONSENT_REQUIRED, CONTENT_CONSUMED, CONTENT_EXPIRED, CONTENT_BUSY,
RESOURCE_LIMIT, EXPORT_FORBIDDEN; unknown failures remain UNAVAILABLE. No exception
message/cause parsing or swallowed operation failure. State consumed is
not delivered/displayed/completed/expired. Generation revocation does not erase
copies already received by a malicious recipient or privileged OS.

## Restart and permission boundaries

No session/consent/player/capture resumes after restart or lock/unlock. External
selectors can trigger background lock; return requires new authentication/review,
not replay of an old callback. Prepare only caller-owned bounded buffers, no
plaintext temp files. Offline may receive/import/play but does not gain CAMERA,
RECORD_AUDIO, INTERNET or ACCESS_NETWORK_STATE. `RestrictedRecording.record(Context,Engine,Review,boolean,AudioDeviceInfo,BooleanSupplier)`
is connected-only, off-main-thread, <=8 seconds. It requires reviewed recipient,
permission and a selected input. `RestrictedContentService.preparationAuthorization`
revalidates that review; false consent/wrong owner/stale generation rejects.
Synthetic AVD native capture passed earlier privacy checkpoints; physical capture
has NOT been executed. Capture adapter is connected-only;
UI must request permission only on a real local action. No background exception.

Runnable examples are existing regression methods in RestrictedContentTest,
RestrictedContentAndroidTest and AdmissionFailureContractTest. Android tests use
synthetic isolated storage; they are not production hardware Keystore acceptance.

### Restricted native failures and closure (c61abb8)

A failed decoder initialization immediately denies the consumed session; it does
not restore the opening right. `Session.closure()` confirms resource cleanup
separately. A reported native cleanup failure completes that stage exceptionally
and retains emergency coordination; never offer a new overlapping session based
only on a playback outcome. `RestrictedPlayback.state()` preserves its first
terminal outcome (COMPLETED/INTERRUPTED/FAILED/CLOSED); it is not a cleanup receipt.
Lock can legitimately produce INTERRUPTED with successfully confirmed closure.
Creation/start/render belong on their documented threads with the original lease;
new unlock cannot reuse those objects. Native failures remain generic to UI.

### PDF static-copy preparation — contract and laboratory limits

`RestrictedDocuments.prepare(Context, Engine, RestrictedContentService.Review,
byte[] pdf, boolean confirmed)` runs on a worker with the original send review.
The input remains caller-owned. The adapter uses a private OS-isolated parser and
returns `Prepared` only after confirmed parser closure. Up to four pages become
fresh bounded PNG rasters (768 px edge, total 256 KiB). This is a static copy,
not preservation of PDF text/forms/vector fidelity. No external viewer is used.
`RestrictedDocuments.Decoder(Session)` exposes `pageCount()`,
`render(int, Canvas, Rect)`, and `close()`; no raw bitmap, URI, print or export.
Claude must protect the surface before rendering and clear it at invalidation.
Page navigation does not create another opening. See RESTRICTED_DOCUMENTS.md.
Native receipts and their exact source SHA are indexed in the acceptance section
below. This adapter is implemented; isolated renderer tests do not establish
protection of a future Claude screen or authenticated hardware Vault.

### File-video — bounded preparation and playback

`RestrictedVideo.prepare(Context,Engine,Review,byte[],boolean)` returns managed
`Prepared`, format `AVC_MP4`. Profile: <=256 KiB, 16..320 x 16..240 even dimensions,
AVC baseline, <=45 frames, <=3 seconds, optional mono 16 kHz AAC-LC. Preparation
uses bounded decode/re-encode and anonymous memory, not plaintext disk files.
No camera, WebRTC or new offline permission. See RESTRICTED_VIDEO for rejected
containers/tracks; do not advertise support for arbitrary MP4 or general video.
The surface playback overload is described below. Implemented is not equivalent
to acceptance of a new cumulative SHA.

### Physical validation and connection ownership

Historical physical attempt11 passed ten synthetic offline debug cases, including
native video presentation, on the selected Xiaomi API36 phone. TEE was observed
for a fixture key, not authenticated production Vault. Hardware-authenticated
Vault, physical capture and two-peer physical media remain separate unexecuted cases.
The authorized2026-09-29 offline R8 laboratory passed10synthetic cases and
four-format force-stop/restart on the same phone; exact APK/test/mapping hashes
are in the dated physical acceptance receipt. It is not the exact product APK. Android rejected the authorized Claude preview install without
showing the owner a prompt; the preview is not the combined application.
Use PHYSICAL_DEVICE_TESTING and the explicit selected-device runner. Never clear
unowned data or change global security to obtain a pass; installation consent and
sensor/authentication interaction remain specific to the proposed action.

## File-video presentation contract (not final API freeze)

`RestrictedPlayback(Context, Session, AudioDeviceInfo, VideoOutput)` accepts only
AVC_MP4 sessions. `VideoOutput.surface()` must return one valid caller-owned
Surface, protected before first presentation; `close()` releases/clears graphics
on the domain cleanup worker (marshal UI cleanup safely without holding SQLite).
Ownership transfers to the adapter, including constructor failure. A silent clip
accepts a null audio device; audio requires an explicit available sink. One
MediaPlayer supplies video/audio, no camera or WebRTC. `start()` is single-use;
`state()` and `firstVideoFrameNanos()` are observations, not human-view receipts.
The latter is zero until the authorized native rendering callback arrives.
Session.closure confirms native/surface cleanup; a terminal playback state alone
does not replace that future. Call close on pause/abandon. Lock invalidates old
callbacks and requires a new, separately authorized object where policy allows.
Do not reattach an old playback after recreation or create a second decoder.

## Public admission signatures and result semantics

All operations below are instance methods on `AdmissionService`, obtained from
`engine.admission()`, run on a worker and declare `throws Exception`. Import wire
is a bounded signed public provisioning payload; never log it or seed material.

| Signature | Result / constraint |
|---|---|
| `RealmConfig getRealmInfo()` | Pinned public realm; import never grants membership |
| `RealmConfig createAdmissionRealm(boolean confirmed)` | Explicit local authority bootstrap, protected signing key; not a relay action |
| `void installRealmConfig(String wire, boolean confirmed)` | Pins reviewed public authority; unexpected replacement rejects |
| `AdmissionRequest createAdmissionRequest()` | Generates local signed request; export its public wire only |
| `boolean isAdmissionAuthority()` | Snapshot, not a grant; missing authority false, invalid/locked fails closed |
| `Status status()` | `State state`, nullable `Long requestExpiresAt`, `Long credentialExpiresAt`, epoch seconds |
| `List<IssuedCredential> issuedCredentials()` | Authority-only; max4096; credential/device IDs, issue/expiry and local revoked flag |
| `AdmissionRequest pendingRequest()` | Current local request, or null; its ID is the cancellation precondition |
| `void cancelPendingRequest(String expectedRequestId)` | Atomic local abandonment, not remote recall or credential revocation |
| `Review reviewAdmissionRequest(String wire)` | Lease-bound device/identity fingerprints, realm and expiry |
| `AdmissionCredential approveAdmission(Review, boolean confirmed, long ttl)` | Explicit authority decision; does not install on another device |
| `AdmissionCredential approveAndInstallOwnAdmission(Review, boolean confirmed, long ttl)` | Atomic authority self-approval + install, not UI transaction orchestration |
| `AdmissionRejection rejectAdmission(Review, boolean confirmed)` | Signed rejection; install with `installRejection(String wire)` |
| `void installAdmissionCredential(String wire)` | Requires matching local pending request and pinned authority |
| `Renewal renewAdmission(Review, String oldCredential, boolean confirmed, long ttl)` | Signed replacement credential and revocation pair |
| `void installRenewal(String credentialWire, String revocationWire)` | Atomic pair import; never split into two UI transactions |
| `AdmissionRevocation revokeAdmission(String credential, boolean confirmed, String reason)` | Fixed protocol reason, not arbitrary security-sensitive free text |
| `void applyRevocation(String wire)` | Authenticated revocation persistence; does not erase received data |
| `PeerStatus peerStatus(String deviceId)` | `PeerState`, `PeerSource`, nullable observedAt/expiresAt; local evidence only |

Credential approval/renewal `ttl` is60..604800 seconds (seven-day maximum).
Revocation reason is one of `owner_request`, `device_lost`, `policy`. Do not use a
UI alias as the device binding, or manufacture timestamps/credential internals.

States are UNCONFIGURED, NOT_ADMITTED, REQUEST_PENDING, REJECTED, ADMITTED,
EXPIRED, REVOKED, INVALID. An authority mismatch is a typed import failure, not a
replacement pinned realm or invented persisted state. `PeerState` is UNKNOWN,
VALID_LOCALLY, EXPIRED, REVOKED, INVALID; `PeerSource` is UNKNOWN_LEGACY,
PUBLIC_CREDENTIAL, CHALLENGE_PROOF, NEARBY_PROOF. Admission does not imply VERIFIED
contact trust, group membership or another linked device's admission.

## Limits and integration ordering

| Format / operation | Current bound |
|---|---|
| Image import | JPEG/PNG <=4 MiB input; <=2048 edge and <=4 Mi pixels; sanitized PNG <=256 KiB |
| Note import | AAC-LC ADTS, mono16 kHz, <=156 frames; decoded/re-encoded, <=256 KiB |
| Note capture | Connected only, selected input + RECORD_AUDIO + consent; <=8s, no restart/background capture |
| PDF | Static raster copy, <=4 pages, <=768 edge per page, aggregate <=256 KiB; no text/forms fidelity |
| File video | Profile above; one native player/session, no seek/loop/replay |
| Common object | One exact verified/admitted recipient; TTL60..86400s, session1..60s; no fanout |
| Common resources | Four pending prepared copies, four busy sessions,128 objects,4096 tombstones |

A successful send returns `String` object ID and means committed encrypted outbox,
not delivered, viewed, played or acknowledged. Do not automatically repeat a
failed send with a new review/object: it could create a second independent object.
A consumed Prepared cannot be reused. Query persisted state and obtain a fresh
explicit decision when needed. Concurrent open is arbitrated by the domain;
UI double-tap guards only improve usability and never grant rights.

1. Obtain local authentication, use worker-owned password buffers, then unlock.
2. Explicitly connect online or Nearby independently when delivery is requested.
3. Capture the current screen epoch and obtain the appropriate review. Perform
   preparation on a worker; close an abandoned Prepared even after UI destruction.
4. For receive, protect window/dialog/surface on the UI thread **before** requesting
   presentation. Open with fresh consent on the worker; no raw restricted URI.
5. Construct the matching decoder/player with that Session; route UI drawing safely.
   Do not perform codec/storage work on the UI thread or hold SQLite during UI waits.
6. On pause, lock, emergency, abandoned screen or permission loss: invalidate the
   screen epoch, clear visual references immediately, close handles. Closure futures
   confirm cleanup separately; a failure remains denied, never auto-reopens.
7. Recreate UI from public metadata only. No session/player/review/consent restoration.

`engine.emergency().status()` returns immutable `Status(state, requestedNanos,
invalidatedNanos, finishedNanos, results)`; results include subsystem/outcome and
confirmedNanos. READY is normal, not a closed receipt. CLOSED follows confirmed
cleanup; INCOMPLETE includes FAILED/TIMED_OUT (existing5s bound) and prevents
reauthentication. See EMERGENCY_LOCK for the new platform authentication ticket.

`OperationFailure.classify(Throwable)` lives in `app.umbra.privacy`; its enum
covers admission, connectivity, privacy and content failures. Use the actual enum
and a generic UNAVAILABLE fallback, never exception message/cause parsing. Native
failure or unknown exception is not permission to use an external player/export.

## Acceptance binding, examples and compatibility

These contracts were reconciled against checkpoint
`6093d0333a5616f6ebcd379929ba0958705bd4eb`; this identifies the inspected source,
not an accepted final build. PR16's final receipt must bind **published HEAD, base,
integration checkout, both parents, tree, runs, APK hashes and mappings**. A later
SHA needs its own applicable CI. Do not freeze an accepted SHA from this paragraph.

The twenty cases and exact test names are indexed in
[the dated matrix](validation/2026-09-29-clipboard-and-acceptance-matrix.md).
Historical7425385 privacy debug/R8 executed19connected/18offline cases including
native clipboard, all four formats, HTTPS and actual force-stop after consumption;
RFCOMM passed admitted and unadmitted cases. That checkpoint's focused debug EOF
remained FAILED. HTTP diagnostics are not a demonstrated EOF correction. Current
cumulative acceptance is recorded separately; historical reports stay immutable.

Compiled usage/regressions: `RestrictedContentTest`, `RestrictedContentAndroidTest`,
`RestrictedHttpsFixtureListener`, `RestrictedRestartFixtureListener`,
`PrivacyAdaptersAndroidTest`, `AdmissionFailureContractTest`. They exercise domain
and adapters, not Claude's combined screens. Existing production records remain
Vault-encrypted; laboratory SQLite is not evidence of production hardware security.
Unknown content kind/version/format rejects rather than becoming an ordinary file.
Legacy password migration is explicit/transactional; password change preserves
identity and ratchets. Active-session expiry uses a monotonic limit plus wall-clock checks; backward wall
time relative to that session start rejects. The persisted object deadline is an
epoch timestamp; privileged clock/snapshot rollback across process restart is not
an anti-rollback guarantee. No automatic restore or recovery for restricted content.
No new content dependency or UI permission is required by these adapters.

### Coverage additions after the earlier clipboard matrix

[The content acceptance audit](validation/2026-09-29-content-acceptance-audit.md)
is the current twenty-case technical index. `6093d03` adds an actual sixty-second
object-expiry/restart/redelivery test for every format/mode; it does not reuse the
one-second session-expiry test as object-expiry evidence. It also adds production
Vault database/WAL inspection under isolated no-auth fixture keys; native execution
must be checked in the final SHA's privacy debug/R8 receipts. Fixture storage
inspection is not authenticated production-key or global-cache/forensic acceptance.

The separately prepared `LegacyWireCompatibilityTest` executes the hash-pinned
emergency-base parser against current real Signal plaintext with an ordinary-text
positive control. Its local connected/offline JVM receipts are in that audit;
source presence or those local receipts do not validate a historical full APK or
the next cumulative CI. No new public API or production dependency is introduced.


## Cumulative receipt binding

Contract revision `UI_SECURITY_CONTENT_API_V1` remains unchanged by the final
laboratory coordination fixes. The exact technical HEAD, integration checkout,
tree, ten-workflow results and artifact hashes are bound by PR16's cumulative
receipt and its exported `claude-contracts/SOURCE.json`; a prior checkpoint's
native pass does not validate a later SHA. Native encrypted-Vault concurrency and
retention tests executed on34e0374 in both flavors/debug/R8; see
`validation/2026-09-29-nearby-quiescent-shutdown.md` for the separate RFCOMM fixture
failure and correction awaiting its own native acceptance. No UI source changed.
