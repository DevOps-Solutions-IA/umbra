# Local voice processing contract v1

Status: implementation in progress. This is a local capture API, not a new
signaling or network protocol. CALL_SIGNALING / VOICE_MEDIA / VIDEO_MEDIA and
all trust, TURN, DTLS, 60-second invitation and 180-second call limits remain.

## Modes and state

Requested mode is OFF (natural) or MODULATED (100 Hz ring modulation, gain 0.8,
limiter +/-24000 in FloatS16). Effective status is OFF, ENABLING, ON, DISABLING,
or ERROR_MUTED. The request must be installed before the first capture block.
There is no persisted preference in v1: each call visibly selects its mode.

ON is reported only after a capture block from the current generation passes
through the effect. A muted/unauthorized pipeline does not acknowledge a new
mode until processing actually resumes. OFF while a modulated/error request
exists requires explicit confirmation: “Vas a transmitir tu voz natural”.
This confirmation never unmutes or grants capture permission. ERROR_MUTED cannot
silently degrade to natural voice. Explicit retry preserves mute/authorization.
Closing is irreversible; a later call creates a separate processor.

## Processing

10 ms planar FloatS16 blocks, mono/stereo at 8/16/32/48 kHz, finite samples with
absolute value <=65536. Unsupported shape or nonfinite/out-of-range data fails
closed across the entire supplied block. OFF preserves valid samples exactly
at this boundary; lossy Opus is not byte-preserving. MODULATED outputs the same
number of samples with no dry component, delay queue or historical PCM.

AEC/NS/gain -> modulation -> limiter -> encoder -> DTLS-SRTP -> authorized TURN.
No second capture source, render processing or remote mode commands. Platform
route changes which invalidate processing must enter ERROR_MUTED; format change
while modulated also fails muted. No automatic switch to OFF.

Each control change increments an atomic generation. The callback snapshots it
at admission and discards its block if the generation changes during processing.
No old-generation processor window may feed a new generation. Audio previously
admitted into the codec/network is outside this boundary. Generation/status
counters contain no samples, identities or biometric features.

The processor holds one bounded oscillator table, atomic counters and no queue.
It rejects overlapping processing rather than waiting in the capture callback.
A block exceeding 10 ms fails muted. Timing metrics contain wall/thread-CPU
nanoseconds, maximum and histogram (1 us upper bound doubled per bucket), counts
of accepted/modulated/silent blocks, faults and clipped samples. Percentiles are
histogram upper bounds, not exact samples. Algorithmic and network latency must
be distinguished; no physical-hardware timing is implied by host tests.

## Required acceptance (not satisfied by this specification)

Synthetic OFF -> ON -> mute -> mode change while muted -> explicit unmute ->
confirmed OFF -> ON -> END in real voice and active video, debug and R8. Decode
on the remote endpoint and distinguish f from f +/-100 with timestamped windows,
allowing codec losses/tails. Force an ON error and require silence/closure, never
natural fallback. The reverse direction continues independently. Verify early
MODULATED selection, concurrent transitions, route/format/permission changes,
lock/revocation/expiry, callbacks after close and bounded resource consumption.
No PCM or sensitive signaling artifacts; only synthetic measurements and hashes.
