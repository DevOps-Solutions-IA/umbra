# Master v4 — preserve HTTPS failure and add server-side observation

Technical parent74253858ed1e29e1bb9c7b8ee7519180a269fad5; integration
4b9f1041dd8100513c577acd190eb283bf470433; common tree
4491bf429eb01b5a99ad2c922db4d60f0b28f23b. This is an open diagnostic checkpoint,
not a claim that the HTTPS failure is corrected or that A/B/C is accepted.

## Executed evidence

Video36525583342 SUCCESS, all debug/R8 partitions including IPv6 after preserving
already-routed Wi-Fi. This validates those runs, not every guest/netd root cause.
Privacy36525583280 SUCCESS:19connected/18offline debug/R8, real clipboard,
PNG/AAC/PDF/AVC, HTTPS and force-stop after consume. R8 mapping contains actual
PrivateClipboard -> k.g and OrdinaryTextExport -> k.d in both flavors.
ZIP11015110082 SHA2564c35898749092a3e5c7e0e3ee076dacc5d49350180f9f5fa1cbe61a62e26fcaf;
ZIP11015100063 SHA25699c61bd93326039d8a9c56e6c0ffec39485ef6246b98be492a221eb595d9a6ba.

Focused36525583313 FAILED only video debug. R8 and Nearby passed. Nearby artifact
11015006198 SHA2567d11b38750f1abc4d95f5f790dd80ab79a8628611f7a86d637911f3305d872fc
has six positive repetitions across both flavors and two unadmitted negatives;
real RFCOMM/challenge/roster/Signal, four restricted formats/duplicates/receipts.
Not physical Bluetooth. Modulation36525583274 SUCCESS debug/R8, ten stages per
voice/video/lock/revocation case with remote synthetic audio, not human acoustics.

## Failure still under investigation

Focused job109268229430, video-stop-race-1, artifact11015490782 SHA256
c9e6234d7b20a60621b17405f82d83a655ae35be352029470b6d1dd2d23c5604.
Both endpoints logged ACTIVE before stopVideo. EndpointA then raised
`java.io.IOException: unexpected end of stream` / EOF in Android's HTTP response
parser, RelayClient.requestRawChecked -> sendAuthorized -> fixture pump. EndpointB
was stopped by cleanup after A exited. It is not the UDP preflight, not proof of
camera continuing, and not evidence of TLS authentication being disabled.

The old artifact has no server connection lifecycle. Idle pooled connection closure
is a hypothesis, not a demonstrated cause. No IOException is swallowed, no automatic
reconnect/proof replay is introduced, and no scenario is retried as acceptance.

## Diagnostic change and bounded verification

The isolated HTTPS fixture still uses pinned uvicorn0.48.0/h11 0.16.0, one worker,
loopback listener, exact synthetic certificate, no proxy/access logs and unchanged
timeouts. A subclass observes connection-made, receive-event, response-complete,
keepalive and connection-lost callbacks and delegates each unchanged to upstream.
It records only monotonic timestamps, counts and fixed booleans. No address, URL,
headers, certificate, key, tokens, payload or exception text is recorded. At most128
connection records are retained; dropped records are counted. Graceful shutdown
persists the receipt before uvicorn re-raises its captured termination signal.
Missing diagnostics are explicitly unavailable, never a network-success result.

A local real HTTPS regression verifies normal server idle closure after the existing
five-second keepalive and records that it was not an incomplete-response error.
First attempt of the diagnostic incorrectly wrote only after uvicorn.run returned;
uvicorn re-raises SIGTERM, so the receipt was absent. That local diagnostic failure
was preserved and corrected by snapshotting during shutdown. The real TLS regression
then passed (5.69s), and a second run after lifecycle timestamp additions passed
(5.65s). This is not an Android pool reproducer or proof of the historical EOF cause.

211 stdlib tooling tests PASS. Activated .venv, then bash scripts/test_local.sh:
205 backend tests PASS in17.52s;20 core utility and85 JVM security scenarios PASS;
156 Java files syntax-parsed;13 static source-policy checks PASS (not behavioral).
Repository guard485 source files PASS. Existing Starlette TestClient/httpx deprecation
warning remains visible; dependencies were not changed or warnings suppressed.
Android/new native CI is still required on the diagnostic commit. No physical APK
was installed and no Claude screen/MainActivity changed. Preserve all prior receipts.

## Separate private-startup recovery failure on17564e2

Emergency36528074109 failed only emergency-lock debug job109275395061. R8,
Nearby and both multimedia lanes passed. Artifact11015557510 SHA256
e518259e3ab4664df748533921fd8358b15455596fd30533af3a306ff7725021:
PrivateStartupFixtureListener rejected `Lab network did not return before explicit
action` at its unchanged15s default-network readiness assertion. This occurred in
the connected restart fixture, not native media or HTTPS EOF.

Code inspection establishes a topology inconsistency: startup explicitly disables
cellular and requires Wi-Fi, whereas restoration enabled both Wi-Fi and cellular.
Whether that mismatch caused the historical Android/netd failure is not yet proven.
The candidate restores only the original Wi-Fi path, records before/after netlink
state and replaces the existing blind5s sleep with a route-readiness check within
that same5s budget. App consent stays denied; the existing OS/default-network,
UID packet/DNS observation and explicit reconnect checks are unchanged. No radio
operation is available to physical targets; no assertions or cases are removed.

Three tooling regressions cover Wi-Fi-only restoration, failure with preserved
diagnostics and rejection of physical targets before mutation. Full tooling total:
214 PASS. This is orchestration evidence, not native Android acceptance. The native
candidate requires its own CI and must not be described as a proven root-cause fix.

## Subsequent278a572 native failure and correction of an early host deadline

The candidate did not solve native recovery. Emergency36529583869 failed both
lock lanes (media/Nearby passed); Startup36529584019 failed both lanes. Debug
emergency artifact11015688031 SHA256
61fe21fb9ee92a9586055a765d3d5d1d58b9d095f697af1d7184ccdec6efda98;
startup debug11016366848 SHA256
e471c49a3b4ba34157779f5c36500f9cecc2deb04ac8ac8b616f3923b035b9d3;
startup R8 11016162362 SHA256
e26f2837bb41efc551f0013b49906287367ce9447ead84e606bafbafcdf95cea.
The host imposed route availability at five seconds; all24 observations showed
no Wi-Fi IPv4 source/route. This does not establish whether the radio was disabled,
associating or waiting for DHCP. No Wi-Fi service-state receipt existed yet.

Code review demonstrated a separate regression introduced by278a572: the former
host slept5s, observed quiet traffic5s, then released the Android barrier. Only
then did Android start its existing15s default-network readiness assertion. The
new mandatory host route deadline prematurely cut that established window. The
correction preserves the original settling/quiet/native timing and assertions;
it adds one route check with timeout0 AFTER the native locked checkpoint, which
can only follow successful OS readiness and explicit reconnect. No new time is
granted to readiness, no failed native proof is ignored, and the locked barrier
is not released if the final route is absent. Original17564e2's failure remains
causally open; this correction is not a claim to have solved Android Wi-Fi itself.

Read-only Wi-Fi status and filtered service-state tokens are now observed before
and after restoration. Commands are bounded3s each and limited to verified owned
AVDs. No raw SSID, password, peer payload, packet dump or key is persisted. Unknown
status remains unknown; diagnostic absence cannot count as successful networking.
Three diagnostic regressions plus ordering/failure regressions run with the full
218-tooling suite, PASS. Native execution of this corrected ordering is pending
its own commit/CI. Phone settings and installations remain untouched.
