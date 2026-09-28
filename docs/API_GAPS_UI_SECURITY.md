# Master v3 API gap register — technical checkpoint, not closure

Compared with Claude bundle ca2a706 (not merged), including AdmissionFlow and
VaultFlow. Technical base ba75d329; published checkpoint c61abb8. Later working
changes require their own tests/commit. Do not treat this document as a UI patch.

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

## Necessary master gaps that still prevent technical closure

- Connected native note capture passed c808418 privacy debug/R8. The historical
  exact-zero assumption was disproved by pre-codec AVD sample statistics; a
  controlled zero-PCM codec reference remains exact zero. This is not a physical
  microphone result and the precise HAL/resampling source remains unknown.
- Routed playback and lock closure passed. Route/focus loss and remaining lifecycle
  cases still require explicit acceptance; no claim of physical acoustics.
- Android note HTTPS passed with real Signal/TLS and two SQLite stores in one AVD,
  not two independent devices. Real RFCOMM PNG/AAC passed in 126ac2f (historical),
  both flavors; final cumulative SHA must repeat it.
- Process force-stop consumption passed for PNG with laboratory SQLite, not
  hardware Keystore or death during commit. Audio/PDF lifecycle expansion remains.
- PDF static-copy implementation published at c7584ee; Android CI pending.
  File-video is still unimplemented. Neither is accepted by compiling classes.
- Pending-preparation cleanup and domain-only authorization APIs are under local
  regression now; they are not yet final native acceptance.
- Final combined technical matrix, artifact hashes and final API freeze.
- Actual Claude screen integration, screenshot/recents verification on that UI,
  accessibility and Android physical hardware remain separate integration work.

Written adapters/classes are not marked accepted merely because JVM/lint compile.
