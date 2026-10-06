# R01-B1 — payload candidate / real Signal compatibility probes

Date: 2026-10-06. Start: 7d7bfa7dc6d4325babc744f356bdf728a35b96f2.
Base main: 4fdd338f3fff4c6864ea820ca85cba6cf284b8da.
Branch: chatgpt/r01-message-reply. Status: PARTIAL / NOT MERGED / NOT UI AVAILABLE.

## Change

Adds isolated ReplyPayload, 22 structural tests and 9 real-libsignal/JNI proposal
probes. Adds candidate contract and updates R01 continuation. No existing Engine,
Wire, UI, build configuration, manifest, dependency, workflow or Codex file changed.
Reference schema, exact envelope binding, text bounds, expiry/skew, context and
unknown-field rejection are validated without sending anything to real users.

The nine crypto probes use existing libsignal with synthetic memory records and
test-only SessionCipher helpers. They exercise cipher roundtrip, legacy Engine
rejection followed by ordinary-message delivery, tampering, envelope substitution,
foreign references, arrival before original, rollback and cleared originals.
They are NOT implemented production sendReply/receiveReply, Android persistence
or physical-radio acceptance. No encryption algorithm was replaced or mocked.

## RED evidence retained

1. New codec API absent: expected compilation failure before implementation.
   This is not counted as a behavioral-test run.
2. First focus run: 59 connected tests, one failure in numeric-version fixture.
   A standalone probe against the pinned org.json JAR demonstrated that in-memory
   Double(1.0) serializes as JSON integer1. The fixture was not sending what it
   intended to reject. Replaced it with literal raw1.0/1e0 inputs; no decoder
   allowance, assertion removal, accepted-state expansion or timeout change.
3. Corrected focused run: 59 connected and59 offline tests, no failures/errors/skips.
   This includes 28 prior R01-A tests plus31 new R01-B1 tests per flavor.

## Validation scope

Host JDK21, SDK36 and Gradle8.14.4; JVM tasks only, no Android emulator/device.
Tooling suite: 417 tests PASS. Source policy: 13 checks PASS.
Current-source guard PASS; full-history scan is not claimed.
Author self-review completed; independent review remains pending.
Full JVM totals and final scope evidence appear below.

## Still pending

Authenticated peer support/version discovery; Y02 logical roster/fanout binding;
production Engine/Wire send/receive integration and atomic outbox/dedup/ACK; UI
selection/preview/cancellation/lifecycle; independent review; APK debug/R8/lint
and physical acceptance for the integrated feature; exact combined-SHA CI and
controlled merge. MESSAGE_REPLY remains pending. R02 and externals not started.
No PR opened, no AVD, no APK install, no device changes, no main modification.

## Completed full host run

Full JVM: **550 connected / 489 offline**, zero failures/errors/skips.
Gradle exit0, BUILD SUCCESSFUL in4m5s. Selected focus:59/59 each flavor.
Full-JVM log SHA-256: `368c4623037bbb3514d3e334b936fbafa26a12dcde54263d428fd3bcf616b16f`.
Java source-set SHA-256 at run start and after completion: `bc1d7a203ac69afda8eaafd0a2f235e3e0bb30402c30159f49143ebbf7537d5c`.
Tooling417/417 and source-policy13 checks passed. No exclusions or test weakening.
Current diff check covers the staged new files; final current-source guard passed
with813 source files. This is a source guard, not an independent security audit.
APK/lint/R8 acceptance for this unit remains pending; no historical APK result
is inherited. Branch publication is separate from CI; no PR is opened.
