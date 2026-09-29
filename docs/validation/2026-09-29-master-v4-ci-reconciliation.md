# Master v4 cumulative checkpoint — 2026-09-29 UTC

Not final acceptance. Technical branch codex/security-content-completion, PR16,
base ba75d329bedb2a26cd2e70371d2369e2fb3ba7a1. No Claude UI changes.

## Executed CI of 8c7d25d24991dacf0bfa23f250aadcb47e8d0f71

Integration checkout 2f450f947c749e333182e3853441baf020a29618.

| Workflow | Run | Result |
|---|---|---|
| Verify | 36516445283 | FAIL: instrumentation checker expected 63, Android actually passed 66 |
| Password | 36516445266 | FAIL: optimized configuration could not resolve Kotlin stdlib/reflect 2.0.21 before compilation; debug PASS |
| Admission | 36516445314 | PASS |
| Startup | 36516445420 | PASS |
| Emergency | 36516445338 | PASS |
| Privacy/content | 36516445305 | PASS debug/R8 |
| Voice R8 | 36516445280 | PASS |
| Video | 36516445278 | FAIL debug shard1 before media: AVD Wi-Fi policy routes unavailable |
| Modulation | 36516445285 | PASS debug/R8 |
| Focused/RFCOMM | 36516445271 | PASS |

Verify artifact 11010969333 SHA256
225b340960abbe5ee66434ba15dfe4e779187adc7142846af6ecebd254640ca3.
Video failure artifact 11011960710 SHA256
847be23cf1456355a4ad859c7ddb083ba8a63a8550f59a0080050af23d917c41.
Privacy R8 artifact 11010933903 SHA256
ce2fcc4815fbf8049dfe6ce2e230c179221b2376078ef5c3df88029848c8475a.

## Corrections and remaining causes

Checker rejection reproduced locally with the real summary `OK (66 tests)`.
The shared source inventory has 63 annotated test methods; connected adds
one capture and two video-surface methods. Update exact acceptance to 66/63,
including two new file-video playback cases and audio-focus loss. Regressions
continue to reject old totals, missing completion, skips and ADB failure.
This is an inventory correction, not an assertion reduction. Offline full Verify
instrumentation was not reached in the failed run.

Video evidence: wlan0 retained 10.0.2.16 and Android NetworkAgent101 reported
connected, but IPv4 policy table1016 had no routes; `ip route get 10.0.2.2`
returned Network unreachable. All shard1 cases stopped at this same prerequisite.
Why netd lost/failed to populate the table is UNCONFIRMED. No timeout increase,
route injection, ignored preflight or media security change was made.

Kotlin POM is currently HTTP200 at the exact Maven Central URL (2322 bytes,
SHA256 fcbada4cd2e9f39658293570ef613752331db69fd1dad1cabaa60548403e17da).
That does not establish the historical runner response. Keep the missing-artifact
failure distinct from the earlier HTTP429. No dependency upgrade/cache deletion.

Added domain regressions: all four formats preserve UMBRA_ONLY and expiry across
Engine recreation; changes to authenticated mode/format/expiry/session limit fail
closed. These synthetic domain payload tests are not native codec acceptance.
Physical runner now requires fresh exact-hash owner approval before each pending
APK install. An optional audited four-format force-stop path is prepared but NOT
EXECUTED physically. No root, data wipe or settings bypass.

## Claude preview, separately authorized

Verified ca2a706 bundle and its two prerequisites. Detached preview build retains
all Claude source unchanged; external Gradle init changes only applicationId to
app.umbra.uipreview.offline.dev. Build/lint and 100 selected UI JVM tests pass.
No Internet/network-state/mic/camera permissions or WebRTC native binary.
APK SHA256 e1660d651418377acaad3d5c1a15ebc732591e45247380d4ace7cbeb5842a15b.
Owner authorized one installation attempt; Android returned
INSTALL_FAILED_USER_RESTRICTED. NOT INSTALLED, no launch and no retry.
Exported APK/logs are in Windows Downloads/UMBRA_RESPALDOS_CODEX/
claude-ui-preview-ca2a706-e1660d65. This is not combined master-v4 UI acceptance.

## Remaining closure

New cumulative commit requires its own complete CI. Resolve or preserve the
network/dependency failures with bounded diagnostics, not repeated attempts until
green. Hardware-authenticated vault, physical R8 (installation denied), two real
peers and combined Claude UI remain unexecuted. Master A/B/C is still open.

## Follow-up HEAD 663ca99, not final acceptance

Own checkout 7802e2e971f14d9b9cd8dec19d1f3cae4d27ecc2 has parents exact
ba75d329 and 663ca99d0a3e38ff1c0b207163248223ddc88cd8. Both published and tested
trees are 5498ec9584aed4891183044af1df49929e0d53c8.
Password36521397999, admission36521398007, startup36521397961,
privacy36521397956 and modulation36521397982 completed SUCCESS.
Privacy explicitly reports connected18/offline17, real HTTPS and four-format
force-stop consumption in debug/R8. Not exact production APK or physical Vault.
Privacy ZIP digests: debug af3b80216483a42d7be3af9728b583cafd4d176da2ef4c723e1738e3fdeb30f2;
R8 ddba0e07dcffca5180f524c2e77aa7e609764b0c23114b8fb2324b76fbe48983.

Emergency36521397953 FAILED: only emergency-media(false), job109254847831.
Lock debug/R8, Nearby and the other multimedia lane passed. Artifact11012908355
contains the repeated absent Wi-Fi policy route (87 samples over20s), before media.
The remaining workflows were still running when this paragraph was written;
query their final status, never infer it from this checkpoint.

Add read-only, bounded control-plane snapshots before/after `svc data disable`
and after `svc wifi enable` on disposable AVDs. This is DIAGNOSTIC ONLY, not a
route repair. It does not change the readiness20s budget, inject routes, retry the
scenario or accept missing UDP/media evidence. A future pass with extra sampling
could be an observer/timing effect; it would not demonstrate a root-cause fix.
Reject physical/unverified targets; capture no application payloads/logs/secrets.
205 Python tooling regressions pass, including two for this diagnostic. KVM local
still returns EACCES. CI on the diagnostic commit is required for new evidence.
