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

## Required technical API gaps: none identified in this reconciliation

G1–G7 have concrete production methods and regressions. G6 is a deliberate
PRIVATE_STARTUP_STRICT prohibition, not an unfinished network endpoint. Native
PNG/AAC/static-PDF/AVC preparation, playback, managed pending objects,
ONCE/UMBRA_ONLY, expiry, export denial and coordinated cleanup exist. File video
and PDF are not placeholders. No authorization is delegated to a UI flag.

This conclusion concerns available contracts, **not final acceptance**. It was
checked against6093d0333a5616f6ebcd379929ba0958705bd4eb and the source signatures
indexed in UI_API_CONTRACT. New findings may reopen a concrete gap; no API freeze
or green final SHA is claimed while cumulative CI remains incomplete.

## Acceptance constraints kept separate from missing APIs

| Constraint | Classification / action |
|---|---|
| All ten workflows on final cumulative SHA | Acceptance prerequisite; inspect PR16's exact-HEAD receipt, never reuse earlier green |
| Historical HTTPS EOF after active video | Causal investigation still open; lifecycle diagnostics alone are not a fix |
| Android Wi-Fi restoration fixture failure | Distinct laboratory issue; preserve failure and verify the resulting correction on its own SHA |
| Physical authenticated production Vault | MANUAL_PENDING; fixture TEE is not authenticated production-key acceptance |
| Physical R8 installation/execution | Authorized offline laboratory run PASS on2026-09-29:10synthetic cases plus four-format force-stop/restart; not exact production APK or authenticated Vault |
| Physical microphone/acoustics/route removal | MANUAL_PENDING with explicit consent; codec tests do not cover room audio or physical routing |
| Two physical Android endpoints | NEEDS_SECOND_PEER for RFCOMM/radio/voice/video; does not block single-device safe tests |
| Death during SQLite commit / privileged snapshot | Not established by injected failure or force-stop after commit; no forensic or rollback-resistance claim |
| Claude combined screens and optimized app | Pending separate graphical integration; domain/R8 hosts are not the combined UI |

Historical7425385 privacy debug/R8 passed native ClipboardManager consent,
sensitive marker, owned-only cleanup and stale-lease rejection (19connected /
18offline). Four-format HTTPS/SQLite/force-stop and native RFCOMM also ran at that
checkpoint. These are executed laboratory results, not a mere build; they do not
validate a subsequent SHA or permit reading the owner's physical clipboard.

Preserve failure history in docs/validation/. Final acceptance must bind every
remaining executable case to final HEAD/checkout/tree, artifact and limitation.
See the updated twenty-case mapping in
validation/2026-09-29-content-acceptance-audit.md; the earlier clipboard matrix
remains historical. Object TTL, encrypted Vault/WAL and exact historical-parser
regressions add acceptance coverage without changing the public API.
No gap here authorizes changing Claude's UI, production Keystore, Signal, TURN-only,
format/resource limits or offline permissions.
