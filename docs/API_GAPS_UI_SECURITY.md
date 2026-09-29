# Master v4 API gap register — technical checkpoint, not closure

Compared with Claude bundle ca2a706 (not merged), including AdmissionFlow and
VaultFlow. Technical base ba75d329; current SHA and CI are recorded in PR16. Historical
results below do not validate a later commit. Do not treat this document as a UI patch.

| Claude gap | Technical contract | Remaining validation/integration |
|---|---|---|
| G1 authority | `AdmissionService.isAdmissionAuthority()` | Snapshot only; false when absent, fail closed on mismatch/lock; UI must query off main thread |
| G2 typed failures | `AdmissionException.Code`, `ConnectivityException.Code`, `ContentException.Code`, `OperationFailure.classify` | Use safe generic fallback for unclassified failures; never parse message/cause or log payload |
| G3 distinct expiry | `AdmissionService.status()` | Nullable request and credential epoch-second expiries; EXPIRED alone is ambiguous |
| G4 issued list | `issuedCredentials()` | Authority-only bounded metadata; revocation/renewal still consume authenticated credential wire through existing APIs, not a public directory |
| G5 cancel | `cancelPendingRequest(expectedRequestId)` | Local abandonment, never global recall; UI obtains explicit decision and must discard stale async results |
| G6 online initial provisioning | Deliberately unavailable under strict startup | Use existing offline public payload provisioning; no network exception is planned |
| G7 linked peer | `peerStatus(deviceId)` | Show local evidence/source/observation time, not live global revocation or inherited admission |

Claude's member renewal path currently wraps two operations in Records.transaction.
Use domain `installRenewal(credentialWire, revocationWire)` instead. Own authority
approval uses `approveAndInstallOwnAdmission(review, confirmed, ttl)`. Do not put
storage orchestration or private-key handling into UI. The existing Spanish text
simplification remains Claude's responsibility and was not overwritten.

## Acceptance still open; no placeholder security delegated to UI

The required G1–G7 domain contracts above are implemented. Initial online provisioning
is deliberately forbidden, not an unimplemented bypass. Native PNG, AAC, static PDF
and AVC preparation/presentation, managed pending objects, ONCE/UMBRA_ONLY, expiry,
export denial and coordinated cleanup are implemented. No new UI is part of this
branch. See UI_API_CONTRACT for actual entry points and ownership.

- Cumulative checkpoint 04d4e44 privacy run36523141967 passed debug/R8, including
  native formats, selected audio route/focus loss, real Signal/HTTPS and force-stop
  after persistent consumption of all four formats. Replayed delivery/reopening
  after restart was rejected. This is not death during commit or hardware Vault.
- Candidate native clipboard regression now exercises the Android service on a
  disposable AVD only: explicit consent, sensitive clip, one-use review, own-only
  cleanup, foreign synthetic clip preservation and old-vault-generation rejection.
  Its build is not execution evidence; the new commit needs its own laboratory CI.
- Audio output physically disconnected/changed, physical acoustics, authenticated
  production Vault and physical R8 remain NOT EXECUTED. The phone rejected the R8
  installation. No extra install or settings change is implied by this document.
- Simulated SQLite write failures and force-stop AFTER commit are executed cases;
  process death DURING commit and arbitrary privileged snapshots remain unverified.
- New cumulative Verify, real RFCOMM, media and security suites must pass on the
  actual final SHA. Earlier successful checkpoints are historical only. Preserved
  failures include Wi-Fi route initialization, HTTPS ConnectionResetException and
  one credential-expiry scenario interrupted during video renegotiation. A later
  pass does not establish each original cause is fixed.
- Final Claude screen integration, screenshot/recents/accessibility and combined
  optimized application acceptance require Claude's separate integration. The
  exported ca2a706 preview was NOT installed: Android rejected the authorized
  attempt without presenting the owner an installation prompt.

The exact executable coverage and limitations are recorded in the dated validation
matrix and PR16. Do not report master closure or a frozen accepted SHA before the
cumulative acceptance is reviewed. Do not turn unexecuted physical tests into passes.
