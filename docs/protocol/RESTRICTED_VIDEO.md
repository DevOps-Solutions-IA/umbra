# Restricted file-video v1 — implementation with layered acceptance

The authenticated restricted descriptor v1 uses format `AVC_MP4`. Unknown-format
clients reject it; no ordinary-file fallback. Object ID, sender/recipient device,
mode ONCE/UMBRA_ONLY, TTL and session deadline retain the common protocol. No
relay endpoint, raw URL, MIME-based authorization, camera or microphone is added.

`RestrictedVideo.prepare(Context, Engine, Review, byte[], boolean)` is a worker
API. The caller owns the original immutable input; the adapter returns a managed
Prepared tied to the SAME original review and authorization epoch. One concurrent
preparation, up to 20 seconds of worker polling, emergency registration before
codec work. Native closure failures keep authorization denied and the preparation
slot unavailable. This is not a guaranteed deadline for an unresponsive native
platform call. Prepared exposes neither MP4 bytes nor a file/URI.

Input: MP4 ftyp header plus real framework extractor validation, one AVC track,
optional AAC-LC 16kHz mono track (ASC 1408), no other tracks or encryption flags.
Nonzero rotation is rejected. Dimensions even, 16..320 × 16..240; 2..45 frames;
first timestamp zero, increasing timestamps, frame intervals 66..500 ms and
inferred final duration <=3 seconds. No timestamp reordering/B frames. Input and
output <=262144 bytes, unchanged envelope limit. Audio native decoded PCM is
limited to 45056 samples so AAC alignment/drain remains within the three-second
profile. Unsupported codecs/container properties are rejected, never silently
stripped into a misleading successful import.

Video: real AOSP `c2.android.avc.decoder` YUV420 image planes, followed by
`c2.android.avc.encoder`, baseline/no B frames, 160 kbit/s target. Actual compressed
size is separately bounded. Audio: existing real AAC decode/re-encode primitive,
not a copied ancillary stream. Fresh framework MP4 muxer, no original metadata;
anonymous memfd with kernel growth seal, 256KiB output and 2MiB backing-size cap. At most 45 raw
320×240 YUV frames (~5.2MB) plus bounded compressed/audio buffers. Plane strides
and crop geometry are checked. Output timing and audio encoder delay require
measurement; no lip-sync/quality claim yet. No plaintext file or disk staging.

Receiver session, persistent consume, expiry, duplicate and export policies are
unchanged. Playback uses `RestrictedPlayback(Context, Session, AudioDeviceInfo, VideoOutput)`.
The Surface must already be protected before construction. Ownership transfers:
player release precedes `VideoOutput.close`, which must clear/release the caller's
last frame and graphics resources off the UI thread. A silent clip can pass null
route; audio requires a selected sink and existing capture/focus policy. Start is
one-shot, never seek/loop/replay. Lock/session expiry denies then asynchronously
closes; await `Session.closure` before claiming resource release. Actual physical
silent-file presentation and lock tests are recorded in the 2026-09-28 playback
checkpoint; transport/R8 expansion requires its own CI. The native test currently checks re-encoding, real Signal transfer
in-process, changing decoded luma and AAC samples, plus consume/reopen denial.
That is not visible file playback, audible output, HTTPS/RFCOMM video acceptance,
or physical hardware acceptance. Do not advertise F03 complete from this API.

Security limits: recipient can copy plaintext with a modified client/OS; original
external file remains. Bounded Java inputs do not prove a native parser immune to
all resource/corruption faults. No claim of biometric/geographic anonymization.
Metadata removal does not remove information visible/audible in the clip.
