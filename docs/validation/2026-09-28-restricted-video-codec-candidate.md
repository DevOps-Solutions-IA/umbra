# Restricted file-video codec candidate — 2026-09-28

Base of this increment: fbb98a3bfc6036a220222e9b05ef917fa178f3fc on draft PR16.
Master v4 remains open. Claude UI, main, prior branches and bundle unchanged.

Implemented worker preparation: MP4/AVC with optional admitted AAC, full native
video/audio decode/re-encode, fresh muxing through bounded RAM proxy FD, original
Review binding and emergency cleanup. Added pure JVM resource/format tests and
two native instrumentation cases per flavor. Native tests generate changing
synthetic video and tone before the real encoders, transfer through real Signal
and SQLite, inspect decoded output, and reject reopening. These are candidate
codec cases, not yet execution evidence or a surface/audio playback claim.

Local executed checks: 262 connected JVM and 205 offline JVM, zero failures,
errors or skips; 196 Python script tests passed. Both debug/release app APKs,
both debug instrumentation APKs and lint built (Gradle exit 0). APK policy and
Signal JNI/permission checks passed all four product builds. Offline remains
without INTERNET, ACCESS_NETWORK_STATE, CAMERA or RECORD_AUDIO. No new dependency.
JDK21/Gradle8.13/AGP8.13.2/SDK36 and previous pins unchanged.

Exact instrumentation expectations increase by the TWO actual new methods:
Verify 63 connected/60 offline; privacy 15 connected/14 offline. Older totals
remain rejected by script regressions. R8 fixture references include the video
codec test/helper specifically, with optimization/obfuscation required, not a
broad keep or weakened guard. Native execution is pending this revision's CI.

Still pending: file-video playback/surface lifecycle, measured A/V synchronization,
real HTTPS/RFCOMM file-video acceptance, physical execution, final contract freeze.
No file-video export, capture or UI is introduced. The product's existing media
call acceptance is not counted as file-video acceptance. No claim of native
parser immunity, no privileged-memory or forensic-erasure guarantee.
