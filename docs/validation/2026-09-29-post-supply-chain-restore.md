# Post-acceptance supply chain and restore — 2026-09-29

Frozen reference: `e0024f091d29dc15c2d788b430c5ea11204e5060`, PR #16,
`UI_SECURITY_CONTENT_API_V1`. Audit commands continued into 2026-09-30 UTC.
This is evidence for a separate candidate, not a modification of that receipt.

## Restored accepted source (VERIFIED)

Original archive, left unchanged:
`UMBRA_RESPALDOS_CODEX/umbra-security-content-e0024f0-final-20260929.tar.gz`.
SHA-256 `af4d0fd5573e7348d4a09ed46edc4191559873c49b3e26572ddeefe9315e9714`.
Extracted its Git bundle into `/tmp/umbra-post-acceptance-restore/repository`;
HEAD equals the frozen reference; `git fsck --full` exit 0. The four exported
contract hashes match SOURCE.json and restored files. This restores history,
not merely a directory of source files.

From that fresh source checkout, with the existing verified dependency/tool
cache, JDK 21 and SDK 36/build-tools 35.0.0:
`python scripts/build_android.py --release` exit 0. Connected JVM: 269;
offline JVM: 212; zero failures/errors/skips. Debug/release compilation, lint,
R8, JNI, merged permissions and APK policy checks passed. No AVD was run in
this restore experiment. This is not a clean-room dependency rebuild.

| Restored APK | SHA-256 |
|---|---|
| connected debug | `2625c08f74d5b8ba910d2e07b4e14a75833653e22d35a2c5008250e1538c1fac` |
| connected unsigned release | `2f3804aeaec482c4e010b30ad30274c5cd5951e0d3627d845bef2f08f6a80892` |
| offline debug | `e362a4fba9c83337196679654d88a9062335a8afbfa29dfe83f9e3c27588bb46` |
| offline unsigned release | `2a1e2ce41d15fb1db9a39ba9f07959b8c14d3ed0bab50a355067bdeeea954934` |

## Applicable Gradle advisory: RED → correction → GREEN

[Gradle GHSA-mqwm-5m85-gmcv](https://github.com/gradle/gradle/security/advisories/GHSA-mqwm-5m85-gmcv)
and [related repository fallback advisory](https://github.com/gradle/gradle/security/advisories/GHSA-w78c-w6vf-rw82)
include Gradle 8.13; 8.14.4 fixes this release line. Under certain repository
connection failures, dependency resolution could continue at a later repository.
An attacker still needs influence over a fallback repository/dependency namespace;
this audit does not claim compromise of any UMBRA dependency. Signal's exclusive
repository and critical artifact hashes limit exposure, but do not cover every
transitive dependency.

Executed `scripts/check_gradle_repository_failure.py` with two owned loopback
HTTP repositories and a non-executable synthetic text artifact. The first server
closes the connection without a response; no third-party repository is attacked.

| Distribution | Primary requests | Fallback requests | Gradle exit | Probe result |
|---|---:|---:|---:|---|
| 8.13 | 12 | 2 | 0 | FAIL (reproduced) |
| 8.14.4 | 12 | 0 | 1 | PASS (resolution fails closed) |

The probe expects Gradle failure, actual requests to the broken primary, and zero
fallback requests. Verify's Android job now runs it. No application transport,
TLS policy or repository content is weakened. The official 8.14.4 ZIP SHA-256 is
`f1771298a70f6db5a29daf62378c4e18a17fc33c9ba6b14362e0cdf40610380d`, pinned in
bootstrap and verified during download. All workflow Gradle pins move together;
AGP remains 8.13.2. Unknown-host variants were not independently reproduced.

## Other dependency observations

GitHub global advisory queries for 23 exact package/version coordinates completed
at 2026-09-30T00:05:36Z with zero matching entries. This is database/version coverage,
not a claim that the software has no vulnerabilities. It does not cover arbitrary
native vendored revisions. The separately reviewed Gradle advisory above is real.

- libsignal Android/client 0.102.3 remain unchanged; real JNI/ratchet tests passed
  in the restored build and isolated verified-HTTPS integration.
- Bouncy Castle `bcprov-jdk15to18:1.86`, no transitive dependencies, pinned SHA
  `fc50334d4d87b4272e72fa95ca748ead05bdef02ef760a5b13f64ffee317cd81`.
  Bouncy Castle license/provenance remain in ADR-vault-password. BCTLS advisories
  do not establish that the distinct bcprov artifact is affected.
- WebRTC revision `73cb8180f7258ee292878d6edd05177f41883962`; AAR SHA
  `25f2abebc99e2e109cff83a428080408843fda51a9cdadb5c081d694c92b7620` rechecked.
  Four ABI entries and five license assets are present. `android/webrtc-artifact.json`
  retains builder/depot/patch/ABI provenance. TURN redirection and media patches
  are unchanged. No native rebuild or whole-upstream C++ suite is claimed.
- coturn 4.18.0-r0 stays pinned to image digest
  `bbefd3e1fdfdc0d58770fe01b581fd8b00d9f3a5580d00acb77cf719a6bc78e3`.
  Published mobility/CONNECT advisories reviewed concern earlier fixed versions;
  the lab also disables mobility/TCP relay and limits peer destinations. This
  does not generalize to a deployment with different settings.
- Python runtime/test packages remain exact/hash-locked. Observed metadata:
  FastAPI 0.133.0 MIT; Starlette 1.3.1 BSD-3-Clause; uvicorn 0.48.0 BSD-3-Clause;
  httpx 0.28.1/httpcore 1.0.9 BSD-3-Clause; h11 0.16.0 MIT;
  PyNaCl 1.6.2 Apache-2.0; pydantic 2.13.4 MIT.
- Android framework codec/PDF behavior is covered by bounded instrumented corpus
  tests, not by a claim about all OEM codec/security patch levels.

No bulk upgrade, new runtime dependency, or license change. Full transitive
reproducible-build attestation and independent native source audit remain outside
this bounded review. Local KVM device exists but current user has no read/write
access; no permission changes or physical device operations were attempted.
