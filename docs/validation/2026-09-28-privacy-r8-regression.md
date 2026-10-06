# Privacy / restricted PNG Android regression

Tested published HEAD: 65c551d7b76a87ff6e775eddee7b1703e5b2f552.
Source tree: e45a7a9ffcfcd1709255f07e1c2d214002ad0fb8.
Actions run 36439676420, integration checkout e98210fee776d6a0e69105f51813ef9125d160c2.

Debug job 108986594346: SUCCESS. Both connected and offline executed all six
instrumented cases: framework image preparation, malformed image/lease rejection,
nonvisual window/input/notification settings, real Signal + synthetic SQLite +
Android PNG encode/decode, single-open durability after SQLite reopen, transaction
failure, and lock after positive rendered pixels with late-frame rejection.
No HTTPS/RFCOMM, actual process death during commit or physical Keystore claim.
Artifact 10977411585 contains receipts and individual reports.

R8 job 108986594077: FAILURE before instrumentation. The guard correctly rejected
missing ImagePreparation in mapping. Artifact 10976769214 proves the generated
TraceReferences roots did not include either new instrumentation class. Production
UI intentionally does not consume these APIs yet, so the optimizer removed them.

Correction: add only PrivacyAdaptersAndroidTest and RestrictedContentAndroidTest
class files to the existing laboratory TraceReferences source set. Generated
rules still allow optimization and obfuscation; no broad keep or disabled pass.
Strengthen the mapping guard to require all three exercised entry points:
ImagePreparation, RestrictedContentService and RestrictedImages.Decoder.
Regression rejects missing/plain-name classes and dontoptimize/dontobfuscate/
dontshrink. Two tool regression tests passed locally. Actual R8 Android execution
of this correction remains pending its own new commit/run, not inferred from debug.

## Additional checks of 65c551d

Both debug/release APK builds and release lint passed locally (52 seconds).
Android JVM report guard: connected 241, offline 184, no failed/skipped tests.
APK permissions, Signal JNI on all four ABI, manifest/TLS/backup/DEX checks passed
for connected/offline debug and release. Release was unsigned, not deployed.

Local APK SHA-256:
- connected debug: de93f5a32f25de6485796ba8de3a1f3339cef602639d26fe0596a98a25be53be
- offline debug: ea8a82cdefda93e5f15775a5a6787e5fa48b7b3d2517133fb6ec33e48caccc2a
- connected release: 00b337b965d7f32296d0386238c01a186d97fc1a44fab87533a6c35e94dbbb88
- offline release: 964c88be98898dfc59f2709eaefb37041466551cadf693dc324d0b1a87df86ff

CI debug application hashes (different build environment/signing, not claimed equal):
- connected: 68bda6ef5f26e6a17d50506fc06fd070509145d8c325019e0441bcb4363beb21
- offline: 158a9c15861d0464dea61e86a35a82ef00b84767fae106db372f06f4f3f76ba7

Initial test_local invocation used an incompatible shell JDK and failed JavaSyntaxCheck
(release 21 unsupported). Preserved log; reran with explicit OpenJDK 21.0.11 and
activated Python venv: 204 backend, 20 utility, 85 security cases and 13 source
policy checks passed. One existing Starlette/httpx deprecation warning remains.
Source policy checks are not Android behavior tests.

## Recoverable export

Self-contained history bundle was restored in /tmp/umbra-restore-65c551d; HEAD and
git fsck verified. Export exists at:
C:\Users\Usuario\Downloads\UMBRA_RESPALDOS_CODEX\umbra-security-content-65c551d.tar.gz
SHA-256 da3bddcd15372f5681ceec5364d3a770dd959d6c2dd99cc14a2ed8fc30151940.
This covers 65c551d, not later commits. Original c56b2a3 archive preserved.

Master remains PARTIAL; audio/video/PDF content and cumulative acceptance pending.
