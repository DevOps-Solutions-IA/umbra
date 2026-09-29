# Restricted file-video profile v1 — implementation in progress

This extends the existing restricted object/session, not WebRTC, cameras, or a
second authorization scheme. No new permission or library. Android framework
MediaExtractor/MediaCodec/MediaMuxer are the maintained codec/container boundary.
Native acceptance is required before advertising the profile. Claude owns UI.

Initial reviewed bounds: source and prepared MP4 at most 256 KiB; one AVC video
track, optional AAC-LC mono 16 kHz audio; at most 320×240 even pixels, 45 frames,
3 seconds, timestamps starting at zero, no B-frame/reordered input, no rotation,
subtitles, data tracks or other formats. These limits deliberately keep the
existing immutable single-envelope transfer and bounded RAM, not a general video
importer. Out-of-profile content is rejected; source files are never changed.
No silent removal of an unsupported audio track. Future longer/larger support
needs a separately reviewed transport/resource profile.

Decode video to bounded YUV420 planes and re-encode with the AOSP software AVC
encoder; decode/re-encode admitted audio with the existing AAC primitive. Never
copy input MP4 metadata, comments, location, track names or opaque ancillary
payloads to output. Fresh MediaMuxer output goes through a bounded writable RAM
proxy descriptor, never a plaintext file/URI. Codec-generated parameter sets
are retained, not user container metadata. Frames/sound can reveal identifying
content; this is not anonymization. Encoder delay must be measured, not assumed
absent; audio/video synchronization acceptance remains pending native tests.

One preparation at a time, with original Review/lease and emergency DOCUMENTS
registration. Authorization is invalidated before cleanup; codec release must
complete before closure is confirmed. Native calls may stall beyond the local
worker deadline: existing emergency incomplete semantics remain, with no new
session over unconfirmed resources. Original input is caller-owned; bounded RAM
copies are wiped best-effort. No forensic guarantees.

Prepared format AVC_MP4 is authenticated by the existing descriptor/AES-GCM and
Signal envelope. Older clients reject the unknown format; no ordinary attachment
fallback. Receiver persistent consume precedes decoder/player access. No seek,
loop, replay, export, URI or external player APIs. Audio route/focus restrictions
reuse the existing playback contract. Surface protection/clearing belongs to
Claude's integration and must precede presentation; tests use owned synthetic
surfaces. No product UI changes are authorized by this adapter.

Required acceptance before completion: changing synthetic remote decoded frames
and audio; native positive/negative imports; size/duration/track/timestamp bounds;
consumption/duplicates/restart/storage failure; lock/focus/route and late-callback
closure with positive activity; debug and R8 in both flavors; existing HTTPS and
RFCOMM fixtures extended without replacing Bluetooth. None is inferred from a
successful compile or a previously tested video call.
