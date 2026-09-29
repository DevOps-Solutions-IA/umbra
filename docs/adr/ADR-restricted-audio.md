# Restricted audio v1 — Android framework pipeline

Design before implementation; no acceptance claimed here. Reuse RestrictedContentService,
Signal/AES-GCM envelope, one-use consume, one authorized recipient and expiry. No
WebRTC or call session is used for notes. No new permission or dependency. AAC_ADTS is a new explicitly recognized format in the version-1 descriptor; older PNG-only clients reject it without downgrade.

Initial profile: AAC-LC, mono, 16000 Hz, <=156 AAC access units (9.984s), <=256KiB
prepared bytes. The small profile fits existing envelopes; no MAX_ATTACHMENT
increase. Android MediaExtractor parses imports; native MediaCodec decodes at most nine seconds of PCM, and a fresh native encoder removes ancillary/container bytes. Only one AAC-LC track and the
explicit AudioSpecificConfig are accepted. Fresh encoded access units use an ADTS stream, omitting container/ID3 fields. This is container serialization,
not a replacement codec. Android MediaCodec supplies encoding/decoding. Inputs
are bounded before invoking native parsers, sample sizes and timestamps checked,
no encrypted/multiple tracks or unsupported formats silently accepted.

MediaDataSource reads RAM only, with authorization and read-budget checks. No
plaintext file, URI or public raw-data getter. Playback is owned by an already
consumed restricted session. MediaPlayer uses a selected output, audio focus and
ALLOW_CAPTURE_BY_NONE; Playback starts at zero volume. Only a routing callback confirming the selected sink enables sound; no confirmation within one second closes the session. Route/focus loss terminates, never resumes or intentionally switches to speaker. Android native route-change detection is asynchronous: already-buffered sound is not retractable; actual route/cancellation measurements remain pending. No seek, restart, looping, media-session controls or external
player API. Completion and local closure are separate facts.

Connected RestrictedRecording uses AudioRecord only after explicit capture consent tied
to the original vault generation, verified destination and RECORD_AUDIO. Offline
can import/receive/play but has no capture implementation or permission. Synthetic
sources are test-only and must exercise the same AAC encoder before transfer.
Native codec processing does not prove intelligibility or physical microphones. Capture is implemented but has not passed native capture/route acceptance. The native tests generate a 440 Hz synthetic source with a distinct 1320 Hz final marker, encode it, transfer through Signal, decode after persistent consumption and measure interior PCM energy/frequency; they do not claim that a speaker played it.

Framework code tracks the Android OS security update level, not a downloaded
binary dependency. Documentation reviewed 2026-09-28:
- https://developer.android.com/reference/android/media/MediaDataSource
- https://developer.android.com/reference/android/media/MediaExtractor
- https://developer.android.com/reference/android/media/MediaCodec
- https://developer.android.com/reference/android/media/MediaPlayer
- https://android.googlesource.com/platform/frameworks/av/+/09a7381025/media/extractors/aac/AACExtractor.cpp

AAC extractor returns access units without ADTS headers. Original imported file
remains unchanged; spoken content itself is not anonymized. Java/native buffers
are best-effort cleared/released; no forensic RAM claim. Android application
capture policy is not a guarantee against a compromised OS or external recorder.

## AAC end-of-stream correction under native validation

Duration-only checks missed a truncated final marker on 299049b. Encoding now
requires the AOSP software c2.android.aac.encoder; no unreviewed OEM delay assumption
or alternative fallback. Two 1024-sample zero input blocks after frame alignment
cover FDK AAC-LC's 1600-sample MDCT/block-switch lookahead. The native test checks a
1320Hz final marker independently of total duration. Input PCM is capped at
9*16000-3*1024 samples, reserving alignment/drain within the unchanged nine-second
decode buffer. Capture remains eight seconds. Small priming/trailing silence is
allowed; gapless playback is not promised. Unsupported codec/profile fails closed.

## Android 16 EOS padding

The retained access-unit count is bounded by aligned PCM input plus the existing
two-frame drain. Native output is still drained to EOS and validated; further
EOS padding is discarded, never signal-dependent silence trimming. This avoids
accumulating additional encoder flush padding on sanitation/re-encoding. The
physical before/after and unchanged final-marker assertions are recorded in
`../validation/2026-09-28-physical-pdf-aac-corrections.md`. No capture-duration,
parser, frame-count, decoder-buffer or cancellation limit is increased.
