# VIDEO_MEDIA — call extension v2 (2026-09-21)

Status: implementation under validation in PR #9; **not yet accepted as working
video**. See the dated validation report. Audio starts with CALL_SIGNALING v1 and
the unchanged 60-second invitation / 180-second foreground session. This extension
uses the same selected device, Engine, libsignal session and opaque HTTPS relay.
Offline rejects calls before any media action and contains no WebRTC or camera permission.

## Wire and compatibility

Outer encrypted content remains the authenticated logical envelope v2. The `call`
object uses the existing exact fields (`v`, `purpose`, `context`, `type`, `event`,
`device`, `selected`, `generation`, `policy`, `data`). Context/membership, event UUID,
selected peer, RELAY_ONLY and all previous resource/time checks still apply.

`call.v=2` accepts ONLY VIDEO_REQUEST, VIDEO_ACCEPT, VIDEO_REJECT, VIDEO_STOP and
DESCRIPTION. All ordinary audio controls remain v1. An older audio client rejects
unknown v2 explicitly; it cannot consent to capture and the request expires. Unknown
fields/types/versions, duplicate JSON fields and boolean/string coercions reject.

| Control | Exact data fields | Effect |
|---|---|---|
| VIDEO_REQUEST | change, callerSend, calleeSend, expires | One proposal for the next generation; no capture |
| VIDEO_ACCEPT | same fields | Explicit recipient consent, directions may only be reduced |
| VIDEO_REJECT | change | Reject matching pending proposal; preserve audio |
| VIDEO_STOP | change | Terminal for this change; stop video, preserve authorized audio |
| DESCRIPTION v2 | role, sdp, digest, fingerprint, video | Existing description plus exact approved binding |

`change`: canonical random UUID, unique within retained call history. `callerSend`
and `calleeSend`: strict integers 0 or 1, with at least one direction enabled. Names
always refer to the ORIGINAL caller and selected callee, not the control sender.
`expires`: Unix seconds, greater than now, no more than 30 seconds ahead, and no
later than the original call end. Request/accept envelopes cannot outlive this
value or the existing 30-second delivery TTL. Local review also has a monotonic
30-second lease bound; waiting for storage does not renew consent.

The description `video` object has exactly `change`, `callerSend`, `calleeSend`.
It must equal locally confirmed state for that generation. A v1 description cannot
silently downgrade a confirmed v2 change. Native WebRTC parses SDP; these JSON
checks alone neither validate multimedia nor prove a network path.

## State and authority

No change → REQUESTED (local) / REVIEW (remote) → CONFIRMED → native negotiation.
REJECTED, EXPIRED and STOPPED are terminal for that change. Re-enabling requires a
new change UUID and new local consent. No restored capture lease after lock/restart.

Both endpoints independently choose send and receive. Effective A→B permission is
A.send AND B.receive; B→A is B.send AND A.receive. Accepting receive-only cannot enable
a local camera. Direct Engine APIs enforce the same transcript/lease constraints.

The original caller alone generates offers; the selected callee alone answers.
One unresolved change is allowed. Crossed requests reject each other without mixing
consent; a later attempt requires a new review. The original event+digest replay
cache and separate retained change identifiers prevent reuse under a fresh event.
Generation is current+1 and at most 4. Initial audio is generation 1. Stop does not
rewind SDP/ratchet generation. Changing directions while video is enabled requires
stopping video first, then a new approved generation; no arbitrary renegotiation,
ICE restart, transfer or groups are added.

The persistent `calls` record gains optional `video` and `videoChanges` objects.
Existing records default to no video; no identity/history/ratchet migration or SQLite
DDL is required. Existing runtime/session rejection prevents resuming a call on
upgrade or process restart. Pending request/accept/description outbox rows carry
`videoChange` locally for authorization checks; this metadata stays encrypted at rest
and is not added to the relay envelope. Rejections for crossed proposals must survive
cancellation of a different proposal. Outbox, state and ratchet use the existing
transaction. Cancellation discards deliveries without rewinding consumed keys.

## Native and lifecycle contract

Same PeerConnection and immutable authorized TURN configuration; RELAY before initial
creation, no direct fallback or server-directed TURN destinations. DTLS fingerprint
must remain the authenticated one across generations. One audio and at most one
video transceiver; no screen capture or DataChannel feature. Media authorization
is not granted merely by receiving ACCEPT or a track callback.

The current adapter uses Camera2 through the pinned WebRTC capturer, with a moderate
320×240 / 15 fps target and a 400 kbit/s video sender cap. Native codec availability,
actual adaptation and decoded frames require execution; class presence is insufficient.
Camera permission is checked at the local send action and on capture. Permission
revocation stops video; microphone permission/lock/trust/session loss closes the call.
Camera callbacks use a revocable in-memory lease and no database I/O so a blocked
transaction cannot hold up camera teardown. The controller separately revalidates
authorization against persistent state before negotiation/activation.

Stop invalidates the local capture gate before attempting storage or signaling.
Terminal delivery failure cannot re-enable video. Buffered frames already sent cannot
be recalled. A new approved device is never added to an active call.

Remote frame freshness is independent of transport state: no frame is WAITING_FOR_FRAME;
a last frame older than 3 seconds is STALE. A frozen image is not proof of live video.
Surfaces clear on stop/close; no application frame archive or thumbnails are created.

## Incremental ICE

The initial native-generated description contains an allocated TURN candidate. Later
native candidates are sent through existing encrypted ICE controls, bound to the
current generation, description digest and media mid. An exact candidate already
present in the native SDP need not be sent twice. No SDP rewrite is performed.
Waiting for every interface to finish gathering is not required and must not delay
the peer indefinitely. The native negotiation deadline remains 30 seconds.

A provisional peer-reflexive native classification does not authorize capture.
Before activation, wait within the same deadline for native recognition of the
authenticated remote relay candidate; both selected candidates must be relay and
the effective DTLS certificate must match. Any non-relay pair after activation
fails closed. This does not enable direct ICE or change the native RELAY policy.
Delayed callbacks from an earlier generation are discarded; pending candidates and
controls remain bounded by the native adapter and CallService capacities.

## Verification boundaries

Synthetic patterns live in androidTestConnected, enter before the encoder, and are
checked after the remote decoder for the OTHER endpoint's marker and two changing
phases. No host webcam/microphone or real images are used. This does not validate a
physical camera, speaker, headset, radio or hardware-backed Keystore.

TURN UDP/IPv4, TLS and IPv6 are separate acceptance claims. Do not infer TLS/IPv6 from
C++ virtual TURN tests. TLS protects the TURN hop; DTLS-SRTP protects peer media.
TURN/ISP/signaling operators still observe metadata. Native parsing, transport,
negative tests, cancellation timing and optimized R8 execution must each have fresh
receipts before this delivery can be marked complete.
