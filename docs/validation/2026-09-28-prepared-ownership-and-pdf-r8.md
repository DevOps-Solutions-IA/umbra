# Pending preparation ownership and PDF R8 regression — 2026-09-28

Master v3 remains open; no UI integration or file-video completion claimed.
Published preceding PDF HEAD c7584ee0e455bd5a30e0bcbb278c26e7f82a6bab, checkout
`efbcf5faf68c4d5ab3ceb2ef17dfc5f842384a13`.

## Executed Android checkpoint

Privacy run 36461176840: debug SUCCESS, R8 FAILURE. Debug receipts prove 13
connected / 12 offline tests including three document cases; real PNG force-stop
and connected HTTPS fixtures also completed. These are synthetic AVD/SQLite
adapters, not production Keystore hardware or death during commit.

R8 failed before instrumentation: `RestrictedDocuments$Decoder` was absent from
both mappings. The trace-references fixture Jar omitted RestrictedDocumentAndroidTest
and SyntheticDocuments; the new domain API had no other retained caller and was
removed. The guard correctly rejected an unexercisable optimized entry point.
Correction includes exactly those two test class families as trace inputs; existing
allowoptimization/allowobfuscation remain. No global keep or disabled shrinking.
The tool regression now rejects omission of EACH required entry point.

Downloaded artifacts and verified hashes:
- debug 10988346423: 724a6b17d09c65147fef75dc546d038ba4dc4aa2a1192baa1234251a4f545a6f
- R8 10987787959: 995b397a091395d09c68e7165654464e84f7e2e2d7d849ad0884615eb6517b66
Logs and archives preserved under `.run/security-content/privacy-c7584ee`.

## Domain-owned pending copies

Public image/audio preparation now accepts Engine + original Review + explicit
confirmation. UI no longer supplies a Runnable which could accidentally omit
its authorization check. Low-level codec functions remain package-private.
Completed image/audio/capture/PDF preparation registers with the existing domain
invalidator and EmergencyLock DOCUMENTS subsystem. Four pending copies per Engine,
no reassignment to another review, no extension past review expiry. Close/invalidate
denies immediately and wipes asynchronously; Prepared.closure confirms cleanup.
Cleanup never waits for a Prepared/SQLite monitor while holding the access gate.
No plaintext persistence and no change to immutable ciphertext retries/Signal.

16 focused JVM regressions passed per flavor, including capacity, original review,
lock cleanup, and lock during a blocked send transaction. The latter proves the
gate can invalidate before the transaction barrier releases, then sending fails
and the buffer closes; it is not a disk-commit crash or a measured hardware SLA.
Both instrumentation APKs and lint passed. First adapted HTTPS fixture compilation
failed from duplicate local variable names, corrected explicitly; failed log retained.
188 script tests passed. Native acceptance on this ownership revision is pending.

Unresolved master items include further route/focus/cancellation/storage scenarios,
PDF transport/adversarial cases, file-video, and final cumulative CI/API freeze.
The original external files remain caller-owned; no forensic erasure assertion.

Final local cumulative build: 258 connected / 201 offline JVM, zero failures/errors/
skips; four debug/release APKs, both debug instrumentation APKs and lint exit 0
(1m45s). APK policies and repository guard passed. Optimized lab builds exit 0;
both generated mappings pass the unchanged six-entry obfuscation/optimization
check. A worker-thread precondition was subsequently included in the public
preparation APIs and the cumulative debug/release build includes it; CI must still
execute the final optimized revision.

A separate c7584ee modulation debug failure (run 36461176812) occurred before
media: 93 route probes over 20157ms returned `Network is unreachable` despite
wlan0 address 10.0.2.16. The kernel dump lacks IPv4 routes in table 1016, while
ConnectivityService reports a connected Wi-Fi network and those routes in its
LinkProperties. Why netd/kernel state diverged is NOT established. No timeout,
TURN policy, route assertion or product code was weakened; no DSP failure inferred.
Artifact 10988212024 SHA-256:
`d7724dfcb63820fb1f5a5c8a239ae2ef5e29521281f83bce228adb27cf689076`.
This is not fixed by a later pass. Local KVM remains unavailable for reproducing
that AVD state; existing CI remains the authorized native execution environment.

Local cumulative APK hashes (not Android execution receipts):
```json
{
  "android/app/build/outputs/apk/connected/debug/app-connected-debug.apk": "8eb67db47edef6dcb87674aad8e65cfca5cacfa008d6d7f6a214941b4e818bec",
  "android/app/build/outputs/apk/connected/release/app-connected-release-unsigned.apk": "8ae64e916eda865daea8e3a975569a390237fca0aabfa8a0f49ce8e58a9548fe",
  "android/app/build/outputs/apk/offline/debug/app-offline-debug.apk": "ea4548d2fa751cd34a8003426a9e3aa5874894f03495a33a121b49809ce00259",
  "android/app/build/outputs/apk/offline/release/app-offline-release-unsigned.apk": "b30ec0ff171d69fb3e20bba63bdfa0abd8e13ea06064dfb2dc0bff5f44b7c078"
}
```
