# Master v4 content acceptance audit — 2026-09-29

## Scope and exact source

Read-only implementation review began on technical HEAD
`278a572d8840f0989b21a738efe7823437cf1844`, against emergency base
`ba75d329bedb2a26cd2e70371d2369e2fb3ba7a1`. The additional tests described
below are an uncommitted candidate at the time of this audit; their final commit
and cumulative native acceptance belong to the PR16 publication/CI receipt.
No result from an earlier SHA is attributed to this candidate. No UI source,
production protocol, dependency, permission, cryptographic primitive or timeout
was changed by this audit.

The full v4 master was read from the owner's Windows worktree. The engine already
contains all four restricted profiles. This is a coverage audit and added
regressions, not a reimplementation or a declaration that the master is complete.

## Corrections to coverage, not unsupported product fixes

`everyFormatRetainsOnlyInUmbraExpiryAndExportDenialAfterRestart` exercises the
one-second **session** deadline and later UMBRA_ONLY access. It does not exercise
the minimum sixty-second **object** deadline. The new
`RestrictedContentTest.realObjectExpirySurvivesRestartAndImmutableRedeliveryForEveryFormat`
uses the unchanged production minimum TTL, creates all four formats in both modes,
positively opens repeatable objects, leaves ONCE objects unconsumed, waits for the
actual deadline with a separate bounded monotonic watchdog, reopens Engine,
rejects immutable expired delivery and verifies expiry remains distinct from
consumption after purge/listing/reopen. It does not manufacture expired ciphertext,
change the host clock or add a production clock bypass.

`unknownRestrictedVersionFormatModeAndCriticalFieldNeverDowngrade` exercises the
actual descriptor parser against unknown versions, formats, modes and critical
fields, preserves ordinary-history exclusion and verifies the original valid
object can still be opened. It is current-parser rejection, not execution of a
historical APK.

The new Android method
`RestrictedContentAndroidTest.encryptedVaultDatabaseAndWalDoNotPersistSyntheticContentOrObjectKeys`
uses the production `Vault`, password envelope and SQLite path with owned
AndroidKeyStore **no-auth fixture keys** from `DeviceVaultPasswordTest`. It requires
an actual nonempty WAL before inspection, prepares a PNG, sends it through real
Signal into the encrypted receiver Vault, positively renders, consumes, closes
and reopens. Bounded scans of that Vault's database and existing WAL/SHM/journal
reject the synthetic PNG, encoded object key and a known ordinary plaintext record.
The ordinary marker independently tests the outer Vault layer; observing no raw
PNG alone would be insufficient because restricted content already has inner AEAD.
No scanned bytes or keys are logged. The fixture's own keys/files are cleaned by
the existing isolated lifecycle. This is not a global cache/log/OS scan, a forensic
claim or authenticated production-hardware proof. Sender storage in this test is
still the separate synthetic SQLite adapter.

## Twenty cases and exact executable coverage

Abbreviations: RC = `android/app/src/test/java/app/umbra/RestrictedContentTest.java`;
RCA = `android/app/src/androidTest/java/app/umbra/RestrictedContentAndroidTest.java`.
Native methods require the cumulative candidate CI receipt. Source presence alone
is not a PASS. Historical receipts remain in their dated reports.

| ID | Requirement | Exact test / implementation | Layer and explicit limit |
|---|---|---|---|
| 1 | Concurrent open | RC `concurrentEnginesCannotOpenTwoSessions`; RCA `concurrentEncryptedVaultOpenHasOneWinnerAndDurableConsumedState` | Memory cross-Engine regression plus new actual encrypted-Vault/SQLite two-thread candidate, compiled; native execution pending next CI. Not two Android processes. |
| 2 | Consume storage failure | RC `failedConsumeCommitGrantsNoSessionAndLeavesObjectAvailable`; RCA `sqliteConsumeFailureDoesNotDeliverDecoderSession` | Memory rollback and actual SQLite fault injection respectively; no decoder grant before commit. |
| 3 | Death after consume | `RestrictedRestartFixtureListener` prepare/verify, `scripts/run_privacy_tests.py::consumption_restart` | Four positively decoded formats then actual force-stop/relaunch; not death during commit. |
| 4 | Duplicates HTTPS/Nearby | `RestrictedHttpsFixtureListener`; `NearbyFixtureListener` restricted format loop and replay checks; RC `duplicateWritesBeforeAckAreIdempotentButAckCancelsFurtherWrites` | Real Signal, HTTPS and emulated RFCOMM in separate laboratories; physical two-radio acceptance needs second peer. |
| 5 | Export alternatives | `OrdinaryTextExportTest`; RC `signalDeliveryHasNoOrdinaryHistoryOrExportAndConsumesOnce`; Engine `get` rejects restricted buckets | Restricted content has no public raw-byte/URI/share/print/forward API. Ordinary consent cannot export it. Native clipboard exercises only ordinary synthetic text. |
| 6 | Wrong authorization | RC `preparationConsentBindsOwnerRecipientAndOriginalUnlockWithoutConnecting`, `staleReviewCannotOpenOrSendAfterNewUnlock`; admission/device suites | Sender/recipient consent, lease and verified/admitted transport authorization compose in Engine. Admission does not imply verified identity. |
| 7 | Altered policy/version | RC `alteredAuthenticatedPolicyCannotBecomeRepeatableOrChangeFormat`, new `unknownRestrictedVersionFormatModeAndCriticalFieldNeverDowngrade`, `invalidAeadDoesNotGrantSessionOrConsumeObject` | Actual descriptor/AES-GCM rejection with no downgrade. |
| 8 | Lock while active | RCA `lockAfterPositiveRenderClosesDecoderAndRejectsLateFrame`, `nativeNotePlaybackConfirmsRouteThenLockClosesWithoutReplay`; `RestrictedVideoAndroidTest.lockAfterNativeVideoFramesClosesSurfaceAndRejectsOldSession`; PDF navigation test | Positive native render/playback before invalidation, measured closure and nonempty post-close observation. Decoder closure differs from last OS packet. |
| 9 | Stale callback | RC `lockInvalidatesSessionAndNewUnlockCannotReuseIt`, `preparedBeforeLockCannotBeSentWithFreshConsentAfterUnlock`; same native lock tests | Old generation remains denied after unlock. |
| 10 | Exact target device | RC `linkedSecondDeviceCannotReceiveOrReassignExactTargetRestrictedObject`, `preparedRecipientReviewCannotBeReplacedEvenWithinSameUnlock` | Three independently keyed/admitted synthetic devices, A1/A2 linked; actual Signal rejects A2 routing copy/rewrite, A1 positive. No restricted fanout/reassignment API. |
| 11 | Retention inspection | New RCA encrypted Vault/WAL scan; `MemoryMediaSource`, `MemoryDocumentDescriptor`, `MemoryMuxerDescriptor`; APK policy | Actual owned Vault DB/sidecar scan is pending native execution on candidate. Anonymous RAM-backed codec bridge and backup policy are separately reviewed; no global logs/cache/forensic claim. |
| 12 | Restore/transfer | APK manifest/data-extraction guard; `PasswordRestartFixtureListener` reinstall failure | Supported cloud backup/device transfer excluded. No restricted recovery/restore feature. Privileged storage snapshots excluded from guarantees. |
| 13 | Malformed/large | RCA `notePreparationRejectsMalformedAndExpiredAuthorizationWithoutRecording`; `RestrictedDocumentAndroidTest.malformedAndTooManyPagesFailWithoutPoisoningNextPreparation`; `RestrictedVideoAndroidTest.malformedDeniedConsentAndOldLeaseCannotCreateVideoDelivery`; `DocumentPagesTest.boundedRandomInputNeverEscapesAsParserRuntimeCrash` | Bounded native profiles/parser rejection; not universal format support or fuzz-complete audit. |
| 14 | Old/unknown client | `LegacyWireCompatibilityTest.exactEmergencyBaseParserRejectsCurrentSignalRestrictedButAcceptsText`; RC unknown-format/version regression | Exact ba75d329 old Wire parser compiled/executed in isolated JVM against actual current Engine/Signal plaintext; text positive. Historical APK/old full Engine not executed. No ordinary-attachment conversion fallback exists. |
| 15 | Positive controls | RCA PNG and AAC positive methods; PDF preparation/navigation test; AVC reencode/decode and changing-frame playback tests; HTTPS/RFCOMM fixtures | Real codecs/Signal and lab storage. Distinguish two Engines in one AVD from two physical endpoints. |
| 16 | Optimized integrated app | Privacy R8 mapping checks and native production adapters; final Claude screen route absent | Technical R8 laboratory is executable. **MANUAL_PENDING / CLAUDE_INTEGRATION_PENDING** for actual final graphical app; explicitly outside Codex UI ownership. |
| 17 | Network consent | Private-startup laboratory plus RC `preparationConsentBindsOwnerRecipientAndOriginalUnlockWithoutConnecting` | Network/Nearby remain separate from content preparation/open. No claim of all OS traffic invisibility on an unrooted phone. |
| 18 | Audio policy | RCA `nativeNotePlaybackConfirmsRouteThenLockClosesWithoutReplay`, `nativeNoteFocusLossClosesAndNeverResumes`; connected `RestrictedRecordingAndroidTest.deniedPermissionThenEmulatorCaptureUsesReviewedLeaseAndNativeCodec` | Native route confirmed before unmute; loss closes; no seek/replay/export/transcription API. Physical route removal/acoustics need human consent. Offline cannot capture. |
| 19 | Object/session expiry | RC `terminalDeadlineClosesWithoutUiTimer`, `everyFormatRetainsOnlyInUmbraExpiryAndExportDenialAfterRestart`, new real object-TTL test | Session and object deadlines now separate tests. Engine reopen is not Android process death. Privileged rollback/snapshots are not claimed resisted. |
| 20 | External original | RCA PNG positive assertion; PDF and AVC preparation tests `assertArrayEquals(original,input)` | Preparation owns a new copy. Does not delete/recall originals, gallery files or external backups. |

## Implementation boundaries reviewed

- Per-object random AES-256 key and AES-GCM descriptor AAD remain inside Signal
  envelopes. `Engine.RestrictedIngress` and `Permit` bind authenticated content to
  the owning Records; arbitrary ingress/enqueue is rejected.
- Consume commits before public Session return. Exceptions close the pending
  plaintext session. Four concurrent busy reservations and four prepared objects
  are bounded; state tombstones fail closed at4096 instead of evicting live replay
  protection. UMBRA_ONLY waits out a persistent reservation after early close.
- `Session.close` invalidates immediately and schedules cleanup separately;
  incomplete native cleanup is exceptional and retains emergency registration.
- PNG is decoded to bounded bitmaps. AAC is reencoded with bounded native codec
  profiles. PDF is rasterized in an OS-isolated one-job process with bounded
  pages/time/output and no keys/Engine passed to that process. Video is a bounded
  AVC/MP4 profile (320×240, at most45frames, three seconds, optional bounded AAC),
  not support for arbitrary videos.
- Playback remains muted until its selected audio route is confirmed, stops on
  focus/route loss, disallows capture by policy, and exposes no MediaSession,
  file/URI/seek/replay API. Video surface ownership is explicitly transferred for
  closure; Claude must secure and clear the actual presentation surface.
- Offline still has no new network/microphone/camera permission. This audit did
  not modify those policies, playback routing, consent or UI.

## Execution receipt for the added tests

Pinned Python/JDK/Gradle environment: activated `.venv`, JDK21 from
`/usr/lib/jvm/java-21-openjdk-amd64`, Gradle8.13, `--offline --no-daemon`, existing
SDK at `/mnt/c/Android/sdk-linux`, existing dependency cache.

The first actual command ran `:app:testConnectedDebugUnitTest` and
`:app:testOfflineDebugUnitTest --tests app.umbra.RestrictedContentTest` after the
object-TTL regression and before the later parser regression: exit0,
`BUILD SUCCESSFUL in 3m 4s`,20tests per flavor,0failed/0errors/0skipped.
Class times65.127s/65.504s; the real object-TTL methods60.031s/60.017s.
Log: `.run/security-content/restricted-object-expiry-278a572.log`.
This is historical intermediate test evidence, not the final candidate count.
Final21-method class execution and native compilation are recorded below when
completed. Native encrypted Vault/WAL execution remains pending Actions; there
is no authorized local KVM run claimed here.

Final candidate verification (same test source):

- Debug instrumentation APKs for both flavors compiled, exit0,
  `BUILD SUCCESSFUL in 1m 55s`. The same invocation executed the connected complete
 21-method class (65.143s) and the selected new parser method in offline.
- The following complete-class command returned exit0,
  `BUILD SUCCESSFUL in 1m 22s`: connected was correctly reported UP-TO-DATE from
  that prior execution; offline executed all21methods (65.940s). No claim that
  an up-to-date task was executed again. XML records show0failures/0errors/0skipped
  in each21-method class; object-TTL test60.031sconnected/60.021soffline;
  parser-rejection test0.023s/0.025s.
- Receipt `.run/security-content/restricted-final-policy-278a572.json`
  SHA256 `654ea18b22b9ff6a4658a8e853f275c26a100f621ced5a28b3d407b9fecc1baa`.
- Final policy log SHA256
  `ebb1e22c289568810b3bf2476e95a4e32c078aec229c68554f320124bd87b82b`;
  native compilation/parser log SHA256
  `a5c37e23c2ff1675e034955f6e5c259ec9d833d7e583337fef3326e9b480f1b6`.
- `git diff --check`: exit0. Native new retention case has compiled but has not
  run locally. It must run debug/R8 on the final published candidate in Actions;
  changing expected inventory is justified by its additional real annotated
  method, not evidence of success. Physical packages were not installed or run
  by this audit.

## Case14 exact historical parser acceptance

`LegacyWireCompatibilityTest` uses the exact pre-content parser from emergency
baseba75d329bedb2a26cd2e70371d2369e2fb3ba7a1. The fixture lives only in JVM test
resources, is byte-identical to Git blob
`ea27fa8482c33302ecb755315cdb142d17377129`, and its SHA256 is checked at runtime:
`ea9d801633b605f9b2cef27cb544683f9d26b80370f4f1463e75b75a76e1f845`.
Repository MIT provenance is retained in the adjacent README. No runtime Git
fetch, new dependency, production class/resource or complete legacy app is added.

The test creates a current sender Engine with synthetic peers and generates both
restricted and ordinary text deliveries. Real libsignal decrypts their actual wire
contents; the current Wire validates both. The pinned JDK compiles the historical
Wire and a tiny probe into a temporary directory; a bounded separate Java process
loads that directory first and receives fixtures over stdin. The exact old parser
accepts text and rejects restricted with its original unsupported-kind exception.
No plaintext fixtures are written to disk or logs; compiler/receipt files contain
only code diagnostics/fixed counts and the directory is removed. Native format
validity is separately instrumented; this case exercises protocol compatibility.
It does not execute a historical APK or all historical dependency implementations.
The Bytes/StrictJson/Framing/FileNames helpers have no diff from the base; the
project's JSON20250517 and libsignal0.102.3 versions are unchanged from that base.

Initial test compilation failed because Android's compile bootclasspath excludes
`javax.tools` and `Files.readString/writeString`. Preserved log:
`.run/security-content/legacy-wire-connected.log`. The fixture now invokes the
actual pinned JDK `javac` as a bounded child process and uses supported file APIs;
no product or dependency change. Connected rerun exit0, BUILD SUCCESSFUL18s,
actual exact-parser test passed. Offline result is appended after completion.

Offline exact-parser run also exit0, BUILD SUCCESSFUL25s. XML: connected1test,
1.758s; offline1test,8.656s; both0failed/0errors/0skipped. No retries after a
behavioral failure: the single preserved red was the compile-API incompatibility.
Logs SHA256:
- initial compile red `7920db9c2dbffaa803adcf3bd588b62e899f64051f738f67bc9e65f4dc4346ce`;
- connected pass `abbc8c76ef35ba8e4b398b42e042d8925703da59d97d29aa217fdb1037607c15`;
- offline pass `236b3c0d179a51658b80fa183ff6210f8e42b1645cf8d63399cd9f4aaef6ed26`.

## Native checkpoint6093d03: new retention test actually executed
Published HEAD `6093d0333a5616f6ebcd379929ba0958705bd4eb`; integration checkout
`8fa74009a3dadf9e93cdb3c8de6abfe58a24ec39`; common tree
`c8784f9171d02ef9c74842e71d868c095999f904`. GitHub commit metadata confirms
parents are emergency baseba75d329 and this exact published HEAD.
Privacy adapters laboratory run36531179913: **SUCCESS**. Debug job109284991206
and optimized job109284990864 both completed successfully. Downloaded ZIPs:
- Debug artifact11016599110: SHA256 `c231b1f2e05a2bd10279a8269e873b8cad2e4a132b0479c59e2446500f855ee6`.
- R8 artifact11016514356: SHA256 `9062e489239f5d1c70ea2d302e56d26196043041532bb261aba2c4baf179da74`.
Both hashes were computed from downloaded bytes and match GitHub metadata. Files
are preserved under `.run/security-content/6093d03-ci/content-audit/`.
All four native combinations actually execute
`encryptedVaultDatabaseAndWalDoNotPersistSyntheticContentOrObjectKeys` and report
`PASS ownedDatabaseAndSidecarsScanned=9,positiveDecoded=true,passwordVault=true,fixtureNoAuthKeys=true`.
Connected completes20tests and offline19tests in both debug and R8, with explicit
completion, no failure/skip markers, four-format force-stop/consumption receipts,
and real HTTPS+Signal two-way AAC/PDF/AVC receipt for connected. The force-stop
fixture remains plaintext test SQLite and separate from the new encrypted Vault
inspection; combining their names does not turn it into production hardware proof.
This supersedes the earlier **pending native execution** status for case11 on
this checkpoint only. It does not validate unpublished later parser/diagnostic
changes. Private-startup and emergency workflows on this SHA are red; other
workflows were still pending/running when inspected. This is not cumulative
master acceptance or a claim of all-green CI.
### APK hashes recorded by the executing runner
These ZIPs contain reports/mappings, not APK binaries. The values below are the
runner-produced SHA256 receipts for installed APKs; this audit did not independently
rehash absent APK files.
| Build | APK | SHA256 |
|---|---|---|
| debug | app-connected-debug.apk | `f034123bc8d8c49d59ac5dba54fdcddd04975baca4fd94c6c4a369ad380a0892` |
| debug | app-connected-debug-androidTest.apk | `21990c5aab0d86477fd1a87ef231cae3b49734cae51b0d5a04fb28ba148ef203` |
| debug | app-offline-debug.apk | `c6d7d394f6bcbcf37701bcff9ca4e8b2917af85cdac7833bb6914cc778577563` |
| debug | app-offline-debug-androidTest.apk | `e6540ca219cd8bd5d12d9fb9111b90da3ac8896f07e4e0c770f742c5f4216f2f` |
| r8 | app-connected-vaultLab.apk | `a97afec65abfa4082c4520a2045d0d4a8330feef59b680b3f5d3bb78d21ac5eb` |
| r8 | app-connected-vaultLab-androidTest.apk | `037213d72e26745c494ed00916c482a9728543f4d94368bd2f53ef6828aa4cb7` |
| r8 | app-offline-vaultLab.apk | `c16c2b58696e7e379466cd5641c1c65b7a34f195be3f3052dca2cadc55f9bccc` |
| r8 | app-offline-vaultLab-androidTest.apk | `6eb58e691769d751678f93147b50142f489f5d1f8726195afc152b041a663127` |

Actual downloaded R8 mapping files were independently hashed:
- connected `0324bf988515d47f021f4c06721eb77b679dfa2c6c72010ad7d9d8defd83d5a7`;
- offline `59398604169655bb7d12b32b5d5ac649d0ba63961a9403230851dcb61ef36e6e`.
They agree with receipts and contain obfuscated production restricted service,
PNG/PDF decoders, preparation and playback classes. Harness explicitly reports
`exactProductionApk=false`; no broad keep/nonoptimization override was added.
### Positive presentation and cancellation measurements
All file-video positive completion cases delivered10changing decoded frames
with minY40/maxY202. Lock cases began after at least2frames; R8 connected saw4
frames total. Request-to-confirmed-close was6.850msdebug connected,6.729msdebug
offline,235.838msR8 connected and60.352msR8 offline, within the existing one-second
assertion. Each test then observed a nonempty200ms window with no further callbacks.
The R8 connected last decoded frame arrived198.896ms **after lock request** and
before confirmed closure. Preserve that observation: authorization invalidation
is not instantaneous withdrawal of native buffered frames, and closure is not a
claim of the last network datagram. No physical acoustic, hardware-authentication
or two-radio result follows from these AVD observations.
### Remaining exact-case limits
- Case11 now has actual encrypted Android Vault DB/WAL/sidecar inspection in
  both flavors/debug/R8. Global OS caches/logs, swap, privileged snapshots and
  forensic erasure remain outside the evidence.
- Case14 exact historical parser test passed locally but is unpublished relative
  to6093d03 and requires its own final-HEAD CI. No historical full APK execution.
- Case16 final Claude graphical paths remain intentionally unintegrated; technical
  optimized adapters are executed. This is an explicit master exception, not a
  delegated domain authorization or permission bypass.
- Physical authenticated Vault, permission-driven microphone/route interaction
  and final app UI need owner action/Claude integration. Two physical Android
  endpoints remain NEEDS_SECOND_PEER. Other cases retain their layer-specific
  limitations in the table above and require the final accumulated CI matrix.

## Case1 additional native encrypted-Vault race candidate

A later uncommitted candidate adds
`RestrictedContentAndroidTest.concurrentEncryptedVaultOpenHasOneWinnerAndDurableConsumedState`.
Two actual Android worker threads reach a bounded start barrier and race the same
ONCE object through the production password-protected Vault/SQLite receiver. Exactly
one Session must be returned; the other must specifically report CONSUMED. The
winner must positively render the synthetic PNG. After closure, Vault close/reopen
and fresh password unlock, consumed state must remain and another open must fail.

It reuses only the isolated no-auth AndroidKeyStore fixture, never production
hardware fallback. The sender is synthetic SQLite; receiver is the actual encrypted
Vault. This is two threads in **one process**, not independent processes or a crash
inside SQLite commit. Worker/fixture cleanup remains bounded and ownership-specific.

Connected/offline debug instrumentation APK compilation completed: exit0,
BUILD SUCCESSFUL18s. Log
`.run/security-content/restricted-vault-race-build-6093d03.log`, SHA256
`3f84870cfc2080dec058f1dac6693bfdde9a344b549c9b4001817e9d7c9a92a6`.
This new method did **not** run in6093d03's privacy artifacts; native debug/R8
execution requires the next published candidate. Inventory rises by one genuine
shared method (privacy21connected/20offline, full69connected/66offline), without
changing any rejection or success criterion of prior tests.

## Case10 exact linked-device regression candidate

`RestrictedContentTest.linkedSecondDeviceCannotReceiveOrReassignExactTargetRestrictedObject`
creates independently keyed/admitted A1/A2/B1, links A2 to A1, approves the roster
and verifies contacts. It explicitly confirms A2's valid admission and contact
verification, so an unrelated prerequisite is not the cause of rejection.
A preparation reviewed for A1 cannot be reassigned to A2. A successful send creates
exactly one A1 envelope; A2 rejects both the unmodified routing copy and a rewritten
`to` with the original A1 Signal ciphertext. A2 obtains neither restricted nor
ordinary content; A1 positively opens and consumes. These are actual libsignal
JVM sessions with transactional memory storage, not native-device acceptance.

Initial fixture compilation referenced a nonexistent method `isDeviceAdmitted`;
this was corrected to the existing authorization API `requireAdmission`, without
adding/changing a production API. Compile-red log preserved at
`.run/security-content/restricted-exact-target-connected.log`. Connected corrected
run exit0, BUILD SUCCESSFUL18s. Offline result follows when completed.
