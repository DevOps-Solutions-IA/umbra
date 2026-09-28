# Technical API contract for Claude — evolving master v3 checkpoint

This contract is NOT frozen for final integration. Base ba75d329; current published
checkpoint c61abb8. Consult dated validation and PR #16 for exact subsequent SHA.
No Claude UI or production MainActivity was changed. All domain/storage/codec work
runs on a worker; Android Window/View configuration runs on the UI thread before
first presentation. Never render an async result from a stale screen/vault epoch.

## Existing security layers

`Vault.getVaultState()` exposes UNINITIALIZED/LOCKED/UNLOCKING/UNLOCKED/LOCKING/
CORRUPT/KEY_UNAVAILABLE. `isPasswordConfigured`, `createPassword(byte[])`,
`unlock(byte[])`, `changePassword(byte[],byte[])`, `lock`, `getAutoLockPolicy`,
`setAutoLockPolicy(long)` remain the existing password contract. See VAULT_PASSWORD.
Create/change finish locked. Keep platform authentication and hardware requirements.
Input is UTF-8, no silent normalization; caller wipes password arrays in finally.
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

## Restricted objects — implemented PNG; audio under native validation

`engine.restricted().reviewSend(String recipient, Mode mode, long ttlSeconds,
long sessionSeconds)` binds one exact verified/admitted device and vault generation.
TTL 60..86400 seconds, session 1..60 seconds. Consent review lasts 60 monotonic
seconds. `send(Review,Prepared,true)` transfers ownership of Prepared, wipes it on
attempt, encrypts per-object with AES-GCM and queues inside Signal transaction.
No ordinary history plaintext/preview or forwarding/export API is created.

Preparation adapters: `RestrictedImages.prepare(byte[],Runnable)` accepts bounded
JPEG/PNG and makes sanitized PNG; source remains caller-owned and unchanged.
`RestrictedAudio.prepare(byte[],Runnable)` accepts the narrow AAC profile and
re-encodes it through Android, not WebRTC. Supply the captured domain authorization,
not an empty callback. This preparation alone does not permit sending; send
revalidates its independently bound review. See ADR-restricted-audio for limits.

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
This adapter's playback/route behavior is not yet accepted on Android in this
checkpoint; do not offer it as complete. Completion is not proof of human listening.

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
Native capture acceptance remains pending. Capture adapter is connected-only;
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

### PDF static-copy preparation — provisional, Android acceptance pending

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
This API is not a claim of completed C: native acceptance and file-video remain
pending. Do not enable a finished-product control solely from this entry.
