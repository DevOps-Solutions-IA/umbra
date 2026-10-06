# File-video presentation checkpoint — master v4 remains open

The existing prepared AVC_MP4 is now consumed by RestrictedPlayback using the
same session, MediaPlayer and cancellation/resource ownership as AAC. No camera,
WebRTC, UI, permission or second authorization system is added. VideoOutput is a
caller-supplied protected Surface and close callback. Silent clips require no
output route/focus; clips with audio still require an explicitly selected sink,
start muted until routing confirmation, and stop on route/focus loss.

The session validates the narrow track/codec profile before MediaPlayer receives
it. Native preparation/re-encode was previously accepted on a8e6f0d in privacy
36513584411, debug/R8 both flavors. That historical result is not acceptance of
this new player/transport code.

## Physical execution

Attempt 10: 9 PASS / 1 FAIL. The two-frame cancellation precondition could precede
the asynchronously delivered MEDIA_INFO_VIDEO_RENDERING_START notification. The
initial assertion combined surface closure and notification in one line, so that
receipt alone did not distinguish them. The revised test labels each assertion
and requires both decoded frames and native start notification before requesting
lock, within the same two-second positive window. No production change or timeout
increase was made to turn that test green.

Attempt 11: 10 PASS / 0 FAIL, offline debug on the same isolated Xiaomi Android16
phone. Native playback delivered 10 synthetic frames, luma 40 through 202; no
local-preview path exists in the harness. After two positive frames in the lock
case, request=8635152817745ns, lastFrame=8635143393975ns,
closureConfirmed=8635188338052ns: observed local closure 35.52ms, followed by a
200ms positive observation window with no new frame callbacks. This is one local
measurement, not a bound on transmitted packets, forensic memory, or every phone.
The predeclared assertion is local closure under one second. Reopen after SQLite
reopen, repeated start and old-session reuse after unlock are rejected.

The production adapter's native start timestamp is a callback observation, not
proof a human saw an image. The test ImageReader independently measures decoded
frames. No PCM/images were persisted, no camera/microphone or audible output used.

App SHA-256: `e7fac2a406333365ab3a47600c023d3429d34a70ea07e85073e795f6ee44c4ac`.
Test SHA-256: `483fcd3bfd5d12e33d534d615f1372f8f62aaa39865f9f5a455a258f5979a188`.
Source was a dirty technical worktree; exact APK hashes identify tested bytes.
Do not attribute this to a clean published commit or final Claude UI.

## New cumulative coverage pending CI

Video AVC is included alongside AAC/PDF in bidirectional real HTTPS, alongside
PNG/AAC/PDF in actual emulated RFCOMM (duplicates/ACKs), and in the host force-stop
consumption/replay inventory. Old formats and tests remain; no time limits are
extended. Media focus loss adds a real AudioManager competing-focus test. The
privacy suite now expects 18 connected / 17 offline tests (three added methods),
plus HTTPS/restart suites, with exact completed receipts. The safe physical
subset is 10 non-sensor/non-audible cases. These new transport/focus/R8 executions
remain pending until their own runs complete.

## Infrastructure failure preserved

Focused media run 36513584569 for a8e6f0d: nearby and video debug SUCCESS; video R8
job109231850367 FAILED during Gradle classpath resolution, before media, with
HTTP429 from repo.maven.apache.org (first: kotlin-stdlib:2.0.21). No dependency
version/repository/checksum changes or blind retry. This is not a product media
failure and not a PASS. Logs retained locally.

Initial combined Gradle invocation incorrectly requested debug instrumentation
while -PumbraVaultLab=true selects vaultLab testBuildType: task absent, exit1.
Corrected by separating debug and vaultLab builds; no project policy changed.

Physical hardware-authenticated Vault, two physical peers, audible acoustic
quality/route removal, combined Claude UI and complete master acceptance remain
pending. No production UI changed; no claim of production readiness.
