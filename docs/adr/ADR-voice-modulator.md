# ADR: local outgoing voice modulation

Status: implementation in progress; no remote-media acceptance claimed.
Date: 2026-09-23.

## Decision and provenance

Use original MIT-licensed bounded DSP in `native/webrtc/voice-modulator/`,
compiled into the existing pinned WebRTC source distribution. Preserve every
TURN/security patch, four ABIs, native provenance and the existing APM. No cloud,
models, recordings, new transport, permissions or wire messages.

The initial effect is ring modulation: multiply each FloatS16 capture sample by
`0.8 * cos(2*pi*100*t)`, then limit to +/-24000. This produces a robotic timbre
(sidebands at f-100 and f+100 for a sinusoid), not a certified privacy level or
human impersonation. Sample count, speech duration and rhythm remain unchanged.
There is no lookahead or retained PCM. A 480-float maximum oscillator table is
not voice data. The design target is <=50 ms added algorithmic latency; native,
codec and remote measurements must be reported separately.

The pinned source `73cb8180f7258ee292878d6edd05177f41883962` was inspected locally:
`BuiltinAudioProcessingBuilder.SetCapturePostProcessing` runs after AEC, noise
suppression and gain processing, before `AudioTransportImpl.SendProcessedData`
and the encoder. Use a per-call APM with a CustomProcessing object, retaining
default APM configuration. Do not change the incoming/render path.
`SamplesReadyCallback` receives a copy after admission to native capture and
cannot enforce this policy. The fork's global ExternalAudioProcessingFactory
and its bypass switch are unsuitable for per-call fail-muted ownership.

## Threat and control boundary

The local owner alone requests OFF/MODULATED. Remote signaling, TURN, codecs and
network preferences cannot alter it. Mode selection conveys no microphone
permission, consent, vault lease, trust or device authorization. Offline carries
neither this adapter nor its JNI. One processor serves both voice and video.

A generation-tagged atomic command is sampled at block admission. Activation
invalidates earlier processing generations; a concurrently changed block is
zeroed. ON is acknowledged only by a successfully transformed capture block.
This guarantee covers frames admitted after the local command, not packets or
Opus state already admitted before it. Codec/network tail must be measured in
remote tests and must not be described as recalled audio. No dry/wet mixing.

An unsupported format, invalid output/input, format change while modulated,
concurrent processing, unavailable clock or >10 ms processing deadline fails
muted. Error is sticky; only an explicit retry or confirmed natural-voice action
can clear it. Mute and authorization are independent flags preserved by mode
changes. Closing is terminal. Call invalidation stops capture and the processor
without awaiting relay acknowledgement. No recovery after lock/unlock or process
restart, no persistent consent and no automatic unmute.

## Limitations and verification gates

Natural PCM necessarily exists transiently in capture/APM memory; no forensic
RAM erasure promise. The effect may leave a person recognizable. Human
intelligibility/acoustics need separate consented physical evaluation. Raw PCM
must never be logged, stored or uploaded by production code.

Standalone synthetic DSP tests are not proof of Android encoder integration.
Adoption requires the patched native build, ABI integrity and existing TURN C++
regressions. Acceptance requires two independent AVDs, real UMBRA signaling,
Opus and authorized TURN: remote decoded transformation, fail-muted, independent
reverse direction, voice/video and debug/R8. Resource, timing and cancellation
results must identify the environment. Pending tests remain NOT EXECUTED.
