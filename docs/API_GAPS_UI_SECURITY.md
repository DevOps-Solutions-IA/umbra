# Master v3 API gap register — technical checkpoint, not closure

Compared with Claude bundle ca2a706 (not merged), including AdmissionFlow and
VaultFlow. Technical base ba75d329; published checkpoint bde7abb. Later working
changes require their own tests/commit. Do not treat this document as a UI patch.

| Claude gap | Technical contract | Remaining validation/integration |
|---|---|---|
| G1 authority | `AdmissionService.isAdmissionAuthority()` | Snapshot only; false when absent, fail closed on mismatch/lock; UI must query off main thread |
| G2 typed failures | `AdmissionException.Code`, `ConnectivityException.Code`, `OperationFailure.classify` | Use safe generic fallback for unclassified failures; never parse message/cause or log payload |
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

## Necessary master gaps that still prevent technical closure

- Connected note capture/reproduction acceptance, selected-route/focus lifecycle,
  encrypted HTTPS/RFCOMM note delivery, explicit process-death consumption.
- Restricted PNG RFCOMM acceptance (existing RFCOMM regression does not yet send it).
- Video-file and static PDF preparation/decoding and rejection tests.
- Final combined technical matrix, artifact hashes and final API freeze.
- Actual Claude screen integration, screenshot/recents verification on that UI,
  accessibility and Android physical hardware remain separate integration work.

Written adapters/classes are not marked accepted merely because JVM/lint compile.
