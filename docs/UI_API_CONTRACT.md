# Technical API contract for Claude — evolving master v4 checkpoint

This contract is NOT frozen for final integration. Base ba75d329; current published
checkpoint a8e6f0d; physical PDF/AAC correction a1a4a00. Consult dated validation and PR #16 for exact subsequent SHA.
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

### PDF static-copy preparation — accepted cases, incomplete lifecycle matrix

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
Native preparation/Signal/render/lock tests passed in privacy checkpoints and the
real RFCOMM fixture passed at fbb98a3. PNG/AAC/PDF force-stop consumption passed in
the connected debug lane at 9f4b533; the entire privacy job still FAILED on video.
These are synthetic SQLite/codec tests, not production hardware Vault tests.
This API is not a claim of completed C or acceptance of the final cumulative SHA.

### File-video — preparation accepted in bounded AVD cases, playback pending

`RestrictedVideo.prepare(Context,Engine,Review,byte[],boolean)` is published,
versioned as `AVC_MP4`. Native preparation/Signal/decode cases pass at a8e6f0d
Privacy run36513584411, both flavors debug/R8: ten changing frames and 20480 audio
samples. Profile: <=256KiB, <=320x240 even dimensions, AVC baseline, <=45 frames,
<=3s, optional mono16k AAC-LC. No camera, WebRTC or new offline permission.
The old proxy descriptor failed in framework muxer filesystem queries; anonymous
kernel-bounded memory corrected this path. See RESTRICTED_VIDEO and dated evidence.
Surface playback overload remains unpublished local work and is not yet a stable
UI contract. Transport/lifecycle matrix and final cumulative acceptance remain.

### Physical validation and connection ownership

A single Xiaomi API36 device is detected by Windows ADB on localhost5038; the
first isolated installation attempts returned INSTALL_FAILED_USER_RESTRICTED.
Attempt05 subsequently installed offline debug and ran eight cases: five passed,
three failed (AAC/PDF); attempt06 reproduced them. TEE applies only to the fixture
key, not authenticated production Vault. Physical acceptance is incomplete. The safe runner
never replaces an unowned package, clears data, changes global security settings,
or captures personal media. This transport/installation limitation is distinct
from the native codec failure reproduced on disposable AVDs.

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

Physical synthetic offline debug delivered changing frames and confirmed lock
closure in attempt11. New R8, HTTPS/RFCOMM video, focus-loss and cumulative CI
must be checked against the final published SHA. The physical AAC/PDF failures
were reproduced and corrected (validation/2026-09-28-physical-pdf-aac-corrections).
This does not imply Claude has integrated a protected SurfaceView or controls.
