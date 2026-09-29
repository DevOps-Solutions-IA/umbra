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
| 1 | Concurrent open | RC `concurrentEnginesCannotOpenTwoSessions` | Real Signal + memory transactions; not two Android processes. |
| 2 | Consume storage failure | RC `failedConsumeCommitGrantsNoSessionAndLeavesObjectAvailable`; RCA `sqliteConsumeFailureDoesNotDeliverDecoderSession` | Memory rollback and actual SQLite fault injection respectively; no decoder grant before commit. |
| 3 | Death after consume | `RestrictedRestartFixtureListener` prepare/verify, `scripts/run_privacy_tests.py::consumption_restart` | Four positively decoded formats then actual force-stop/relaunch; not death during commit. |
| 4 | Duplicates HTTPS/Nearby | `RestrictedHttpsFixtureListener`; `NearbyFixtureListener` restricted format loop and replay checks; RC `duplicateWritesBeforeAckAreIdempotentButAckCancelsFurtherWrites` | Real Signal, HTTPS and emulated RFCOMM in separate laboratories; physical two-radio acceptance needs second peer. |
| 5 | Export alternatives | `OrdinaryTextExportTest`; RC `signalDeliveryHasNoOrdinaryHistoryOrExportAndConsumesOnce`; Engine `get` rejects restricted buckets | Restricted content has no public raw-byte/URI/share/print/forward API. Ordinary consent cannot export it. Native clipboard exercises only ordinary synthetic text. |
| 6 | Wrong authorization | RC `preparationConsentBindsOwnerRecipientAndOriginalUnlockWithoutConnecting`, `staleReviewCannotOpenOrSendAfterNewUnlock`; admission/device suites | Sender/recipient consent, lease and verified/admitted transport authorization compose in Engine. Admission does not imply verified identity. |
| 7 | Altered policy/version | RC `alteredAuthenticatedPolicyCannotBecomeRepeatableOrChangeFormat`, new `unknownRestrictedVersionFormatModeAndCriticalFieldNeverDowngrade`, `invalidAeadDoesNotGrantSessionOrConsumeObject` | Actual descriptor/AES-GCM rejection with no downgrade. |
| 8 | Lock while active | RCA `lockAfterPositiveRenderClosesDecoderAndRejectsLateFrame`, `nativeNotePlaybackConfirmsRouteThenLockClosesWithoutReplay`; `RestrictedVideoAndroidTest.lockAfterNativeVideoFramesClosesSurfaceAndRejectsOldSession`; PDF navigation test | Positive native render/playback before invalidation, measured closure and nonempty post-close observation. Decoder closure differs from last OS packet. |
| 9 | Stale callback | RC `lockInvalidatesSessionAndNewUnlockCannotReuseIt`, `preparedBeforeLockCannotBeSentWithFreshConsentAfterUnlock`; same native lock tests | Old generation remains denied after unlock. |
| 10 | Exact target device | RC `preparedRecipientReviewCannotBeReplacedEvenWithinSameUnlock`; authenticated `from`/`to` and `Engine.receive` target validation | One exact device; no restricted fanout/reassignment/history synchronization API. |
| 11 | Retention inspection | New RCA encrypted Vault/WAL scan; `MemoryMediaSource`, `MemoryDocumentDescriptor`, `MemoryMuxerDescriptor`; APK policy | Actual owned Vault DB/sidecar scan is pending native execution on candidate. Anonymous RAM-backed codec bridge and backup policy are separately reviewed; no global logs/cache/forensic claim. |
| 12 | Restore/transfer | APK manifest/data-extraction guard; `PasswordRestartFixtureListener` reinstall failure | Supported cloud backup/device transfer excluded. No restricted recovery/restore feature. Privileged storage snapshots excluded from guarantees. |
| 13 | Malformed/large | RCA `notePreparationRejectsMalformedAndExpiredAuthorizationWithoutRecording`; `RestrictedDocumentAndroidTest.malformedAndTooManyPagesFailWithoutPoisoningNextPreparation`; `RestrictedVideoAndroidTest.malformedDeniedConsentAndOldLeaseCannotCreateVideoDelivery`; `DocumentPagesTest.boundedRandomInputNeverEscapesAsParserRuntimeCrash` | Bounded native profiles/parser rejection; not universal format support or fuzz-complete audit. |
| 14 | Old/unknown client | New RC unknown-format/version regression; Wire kind parser and Engine unsupported-kind rejection | Current implementation fails closed; historical client APK not executed. No ordinary-attachment conversion fallback exists. |
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
