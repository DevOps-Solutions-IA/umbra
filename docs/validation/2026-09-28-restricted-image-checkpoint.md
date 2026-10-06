# Restricted image checkpoint — NOT master acceptance

Base: ba75d329bedb2a26cd2e70371d2369e2fb3ba7a1.
Parent published HEAD: 5ffa451cbd558ae47d0d0d7d2888a12b99618961.
This checkpoint preserves A/B work. A remains partial; B is PNG-only and C is
pending. It is not permission to integrate an allegedly complete Claude UI API.

## Preservation and current access

The original incremental history backup and its Windows Downloads export were
rehashed on 2026-09-28; both are SHA-256
86be1bb8f33e06f9b543db5ffb6b98190b161e7888bf1e29ec5837f23d76d96c.
Original: /tmp/umbra-security-content-c56b2a3.tar.gz.
Export: C:\Users\Usuario\Downloads\UMBRA_RESPALDOS_CODEX\umbra-security-content-c56b2a3.tar.gz.
That archive covers c56b2a3, NOT subsequent work. Recovery and prerequisites were
checked independently in the preceding resume. No UI bundle was merged.
GitHub reads and normal branch publication now work. Local KVM access remains
unavailable; Android execution is delegated to existing authorized Actions.

## Implemented incremental scope

Restricted PNG descriptors and object AES-GCM are carried inside real Signal.
One exact admitted/verified device; no fanout or ordinary history/export path.
Persistent atomic consume precedes decoder access. Repeatable objects retain a
conservative busy reservation across Engine/reopen. Sessions use vault leases,
wall/monotonic expiry, bounded native decoder ownership and the existing emergency
coordinator. Domain invalidation and asynchronous native closure are distinct.
Android codecs prepare metadata-stripped PNG and decode/render internally.
No public bytes/file/URI export API. PrivateClipboard only accepts an ordinary
stored text message with fresh authorization and explicit consent.

These controls do not protect against a modified recipient or compromised OS.
Claude still must secure/clear its own surfaces. No production UI was modified.
Voice notes, file video, PDF, final transport acceptance and full A privacy
acceptance remain pending. Android tests use synthetic SQLite laboratory storage,
not production hardware Keystore; reopening SQLite is not death during commit.

## Local execution

JDK 21, pinned Gradle 8.13, SDK 36, Python 3.13 venv; unchanged dependencies.
The preceding content-reviewed Gradle invocation passed both JVM variants,
both Android-test APK builds and both debug lint tasks (exit 0). New bound on
native decoder count and final script counts require the subsequent checkpoint
validation, not an inference from that run.
Tool regression invocation for Android report contracts and privacy report
contracts: 9 tests passed, exit 0. Counts remain exact; no skip/failure accepted.
New Android restricted tests have been compiled, NOT yet executed in this
checkpoint. The new debug/R8 privacy workflow runs both flavors and rejects
missing, skipped or empty reports. No test result is attributed to a future SHA.

## CI of parent 5ffa451 (not this checkpoint)

Integration checkout: 5baaef7241b206735ea4a8baac3fa5511742edf9.
- Verify 36426147803: FAILED. Android connected.log proves OK (51 tests),
  INSTRUMENTATION_CODE -1. The runner still required 48; it failed before offline.
  Cause demonstrated: strict suite inventory was not updated for three new
  privacy tests. This checkpoint requires 54/52 including three restricted tests;
  regression rejects earlier 48/46 and 51/49 counts, skips and failed adb.
- Voice R8 36426147775: FAILED before media, wait_wifi_ipv4 readiness budget.
  Both AVDs booted; Wi-Fi policy route readiness failed. Control-plane evidence
  retained. Underlying intermittent network cause unresolved; no timeout raised.
- Video 36426147790: FAILED debug shard 1 trust-loss. A reports Process crashed;
  B reports stage=0, no video source/sink frames. Driver was awaiting initial
  decoded audio. This does NOT demonstrate a trust revocation bug: that action
  had not yet been reached. Crash cause unresolved; no exception ignored.
- Focused 36426147822: SUCCESS. Does not explain the preceding c56b2a3 focused
  failure 36425360736 (credential-expiry repetitions 2/3 during reactivation).
- Password 36426147752, admission 36426147756, private startup 36426147821,
  emergency 36426147771, modulation 36426147777: SUCCESS.

Full acceptance remains red/pending. Preserve prior failures. No production,
hardware, final media/privacy acceptance or complete master claim.

Subsequent local validation completed: content-bounded-review Gradle command
(both JVM suites, Android-test assembly, debug lint) exit 0, 1m19s.
Full scripts unittest discovery: 181 passed, exit 0; repository_guard: 416 files,
exit 0; git diff --check: exit 0. These are local checks, not Android execution.
