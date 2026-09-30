# Post-acceptance local validation

Frozen accepted source: `e0024f091d29dc15c2d788b430c5ea11204e5060` / PR #16.
Candidate branch: `codex/post-acceptance-audit`, separate worktree
`/tmp/umbra-post-acceptance-audit`. No accepted/Claude branch was changed.
Commands below ran on the candidate source before committing this evidence;
the draft PR's exported receipt binds the published SHA/tree and subsequent CI.

## Executed layers

| Command / layer | Exit | Result |
|---|---:|---|
| `python scripts/repository_guard.py --git-history` | 0 | 508 source files / 1766 historical blobs examined at that invocation; no supported prohibited patterns, not a security certification |
| `python -m unittest discover -s scripts/tests -p 'test_*.py' -v` | 0 | 235 tooling tests, including five offline physical-plan checks |
| `bash scripts/test_local.sh` | 0 | 207 relay tests, 20 core utility scenarios, 85 security scenarios; 13 source policy checks are separate, not behavioral tests |
| `bash native/webrtc/voice-modulator/test.sh` | 0 | five host DSP groups; no new Android/Opus/TURN or physical claim |
| `python scripts/check_voice_artifact.py android/vendor/webrtc-150.7871.01-umbra.5.aar` | 0 | exact AAR/entry/license/four-ABI digest verification |
| `python scripts/build_android.py --release` | 0 | Gradle 8.14.4 build successful in 4m54s; connected277/offline220 JVM tests, no failures/errors/skips; debug/release/lint/R8/APK policies passed |

JDK `/usr/lib/jvm/java-21-openjdk-amd64`; SDK
`/mnt/c/Android/sdk-linux` with platform36/build-tools35.0.0;
Python venv3.13; Gradle cache `/tmp/umbra-security-gradle`.
Build explicitly selects JDK21; unrelated shell defaults do not validate it.
Warnings retained: deprecated Android APIs, libsignal JNI symbols left intact,
and Starlette's current httpx-testclient deprecation. No global suppression added.

Focused domain RED/GREEN, the Gradle fallback reproduction and fresh verified-HTTPS
integration have separate reports. Their overlapping tests are not summed as
independent acceptance. The complete final CI must run native Android suites;
compiling their APKs alone is not native execution.

## Locally generated candidate APKs

| APK | SHA-256 |
|---|---|
| connected debug | `16ed7413b677edc0643f863922eedcb7c27002ffc974140522dc0fb2b7f06218` |
| connected unsigned release | `2e032b5e3b86cdddddfc0508123fe3087bfaa6a428e6b14979baccfc22e8b4f3` |
| offline debug | `111b39e5624d295e9ce00af8e4996d3438033e0f4b708686c233ecb4cc23f40d` |
| offline unsigned release | `49d1f8a827f5322eeb6d614aaa3c674923282e2b029e027346c7f8eeb57d57fa` |

These hashes identify local outputs, not CI APKs or physical installation.
Offline final APK checks reject INTERNET/ACCESS_NETWORK_STATE, microphone/camera,
WebRTC Java/JNI/assets and laboratory content. Connected remains explicitly scoped.

## Additional provenance observations

Resolved cached POM metadata declares libsignal-client and libsignal-android
0.102.3 as AGPLv3; AGP8.13.2 as Apache-2.0. Existing project release/license review
is not replaced by this metadata observation. Runtime coordinate/hash inventory,
red/green logs, build/test reports and advisory query receipts are retained in
exported candidate evidence, without authentication caches or real-user data.

## Not executed here

No ADB, physical install, sensor capture, dual-phone test or production deployment.
Local `/dev/kvm` exists but is not readable/writable by this user; no permissions
were changed. Hosted Actions are the authorized native execution layer.
The old HTTPS EOF remains HISTORICAL_UNCONFIRMED. New passing framing tests do
not prove its cause or correction. Physical and Claude integration boundaries
remain explicit in POST_ACCEPTANCE_AUDIT and POST_ACCEPTANCE_PHYSICAL_MATRIX.

Both `:app:assembleConnectedDebugAndroidTest` and
`:app:assembleOfflineDebugAndroidTest` completed exit0 with Gradle8.14.4 (20s),
including all added native corpus source. A one-off reviewed Gradle init task
recorded resolved release runtime coordinates/SHA-256 for both variants. Local
file WebRTC is separately verified by the complete AAR/entry checker above.
Neither test-APK compilation nor dependency resolution counts as instrumentation.
