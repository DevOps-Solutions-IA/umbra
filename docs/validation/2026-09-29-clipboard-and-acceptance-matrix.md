# Master v4 continued — native clipboard and acceptance audit

Base remains ba75d329bedb2a26cd2e70371d2369e2fb3ba7a1; parent of this
checkpoint is 04d4e44f9eabbc029b0542efa12ad2fdad5d73e4, tree
e035708f9b51488e187c856172c6628a1a67f4de. Its integration checkout is
5817986f0907d48d533d8f943c160c475cf3be6d with the same tree. This report
is not evidence that subsequent code has run. The PR receipt records exact
published/integration SHA and runs once available. Master A/B/C remains open
until that cumulative matrix is reviewed.

## New regression and safety boundary

PrivacyAdaptersAndroidTest adds one real Android clipboard case. It requires
ro.kernel.qemu=1 before touching a clipboard, rejects a nonempty starting
clipboard without reading its data, and runs with an explicitly focused host.
It tests denied consent, successful ordinary synthetic text copy with Android's
sensitive flag, rejection of reused consent, removal of its own clip, preservation
of another synthetic owner's clip and rejection after lock/unlock. Cleanup only
removes marked synthetic content. The physical runner's explicit method allowlist
does not include this test. No phone clipboard is accessed. No product UI changes.

Inventory becomes 64 shared / 67 connected tests; privacy becomes18 offline /19
connected. These are +1 actual annotated method, not relaxed acceptance. Old
counts, skips and missing completion remain rejected. The existing domain tests
continue to reject copying restricted content through ordinary-text consent.
208 tooling tests pass; both debug instrumentation APK builds pass (19 seconds).
Both connected/offline vaultLab app and instrumented R8 builds also pass (67
seconds). Repository guard checks481 source files without findings. Native
execution remains unexecuted on this change; compilation is not an Android
service test.

## Distinct historical failures, preserved

The 3ecfc8b focused debug job109259466993 (run36522524663) failed only
credential-expiry-1. ZIP11014371688 SHA256
45dc9f0ef3c8bfe0a041ed77656a12ef522ec92f9eecdabc813197332cf4474a.
Both endpoints had decoded native VP8 patterns/audio at approximately40.9 seconds
from fixture start. Video-off closed in19.5/16.4ms and preserved265/320 decoded
audio buffers. Failure occurred before resumed-video receipt with
`native-authorization`, not the UDP preflight. The fixture issues a60-second TURN
credential after device selection. The saved trace does NOT identify which
precondition in native check() failed; expiry/scheduling is an inference, not a
proven cause. No lifetime, assertion or production media policy is changed here.

The independent 3ecfc8b privacy R8 HTTPS reset and route bootstrap failure remain
in their artifacts. A successful 04d4e44 run does not retroactively repair them.

## Twenty-case acceptance mapping

Each entry names real tests or a precise limit; tests need the final SHA's receipts.

| Case | Executable coverage / limit |
|---|---|
| 1 concurrent open | RestrictedContentTest.concurrentEnginesCannotOpenTwoSessions; memory transactions, not multi-process Android |
| 2 write failure | failedConsumeCommitGrantsNoSessionAndLeavesObjectAvailable; RestrictedContentAndroidTest.sqliteConsumeFailureDoesNotDeliverDecoderSession uses SQLite fault injection |
| 3 death after consume | RestrictedRestartFixtureListener + run_privacy_tests.consumption_restart; positive render, actual force-stop, four formats; not death during commit |
| 4 duplicate online/Nearby | RestrictedHttpsFixtureListener and RFCOMM fixture perform duplicate delivery with real Signal; final RFCOMM result required |
| 5 export | OrdinaryTextExportTest and restricted domain tests; no restricted URI/file/share API; native ordinary clipboard case added |
| 6 authorization | admission/device/pairing/Engine suites plus restricted stale-recipient/review tests; admission is not human verification |
| 7 policy/version | alteredAuthenticatedPolicyCannotBecomeRepeatableOrChangeFormat and ingress/AEAD rejection |
| 8 closure | positive PNG render/AAC routing/AVC changing frames then lock; emergency native media; physical attempt11 historical only |
| 9 stale callbacks | lockInvalidatesSessionAndNewUnlockCannotReuseIt; native lock tests reject old render/playback after unlock |
| 10 exact device | preparedRecipientReviewCannotBeReplacedEvenWithinSameUnlock; authenticated descriptor recipient; no restricted fanout/reassignment API |
| 11 retention | encrypted production Records/object envelope; bounded anonymous native memory; APK backup policy; test SQLite adapter deliberately plaintext and not production storage proof; no forensic claim |
| 12 restore | supported Android backup/transfer disabled by APK policy; no recovery/restore implementation; privileged snapshots excluded |
| 13 malformed/large | native image/audio/PDF/video rejection tests, bounded profiles and domain parser tests |
| 14 old client | unknown restricted wire kind/format fails closed; no conversion to ordinary attachment |
| 15 positive | four native formats, real Signal, HTTPS, SQLite, selected playback/frames; one-AVD two-Engine HTTPS is not two physical endpoints |
| 16 combined UI/R8 | technical R8 privacy harness executed on parent; final Claude screens NOT INTEGRATED/NOT EXECUTED |
| 17 network consent | private-startup suites and admission; final cumulative run required; phone without root does not prove absence of all OS traffic |
| 18 audio | AAC codec, selected route, native focus loss/lock; physical route removal and acoustics NOT EXECUTED; offline cannot capture |
| 19 expiry | terminalDeadlineClosesWithoutUiTimer; everyFormatRetainsOnlyInUmbraExpiryAndExportDenialAfterRestart; object and session deadlines distinct |
| 20 source original | native sanitization asserts unchanged source buffer; no deletion/recall of caller's gallery/file or external backups |

## Owner observation and preserved preview

Owner reports no installation prompt appeared. ADB's USER_RESTRICTED wording is
not evidence of an intentional human cancellation. No reinstall or protections
change was attempted after that response. Preview APK remains exported separately,
SHA256 e1660d651418377acaad3d5c1a15ebc732591e45247380d4ace7cbeb5842a15b.
It contains Claude's unchanged ca2a706 UI, not the cumulative technical integration.

Current full recoverable backup exported to Windows Downloads/UMBRA_RESPALDOS_CODEX:
umbra-security-content-04d4e44-checkpoint.tar.gz, SHA256
bc2e2c015bf4a803a187b769ecb280a0508f837cbb17a1294416ede9c4672083.
Original bundle and older backups are preserved. A new checkpoint must preserve
this additional regression separately and never overwrite the older archive.

## Additional setup regression observed on parent04d4e44

Video36523141987 failed IPv6-UDP in debug/R8 shard0 and IPv6-TLS R8 shard1.
All three stopped before media: no non-link-local IPv6 address on wlan0.
Artifacts11013878846 /11014482818 /11014177032 have respective ZIP SHA256:
17fdfd58849015188c074f6ff77da1c3d883849f9d95bb00304e7918dcbdfe3f;
f356bd419230190d537a519cbc35b8290b3e6bc1c9fdec2491b71ec0e659ab74;
3d78c9b20986c1060afcdcfb68075ec242e2b04c27da4045e1d373183d12eba8.
Each saved pre-reset snapshot shows both AVDs already had valid wlan0 IPv4 policy
routes. Unconditional OFF/ON was unnecessary and discards interface address state;
IPv4 readiness alone does not establish IPv6 autoconfiguration. Kernel reference:
https://www.kernel.org/doc/html/v6.15/networking/ip-sysctl.html (IPv6 keep_addr_on_down,
router solicitations and DAD). This explains the setup hazard, not every guest/netd
failure or the exact historical RA timing, which was not recorded.

Added a regression that rejects radio cycling of a healthy routed association:
RED, AssertionError on svc wifi disable, 12 tooling cases/1failed before the fix.
Initializer now preserves an existing matching IPv4 address/route and records
IPv6 address state. Only the inconsistent missing-route state uses bounded OFF/ON.
No IPv6/direct-route assertion removed, route/address injected, fallback enabled
or timeout increased. 209 tool tests then PASS. Native IPv6 recovery still needs
new-HEAD CI; simulated command tests are not packet-level validation.
