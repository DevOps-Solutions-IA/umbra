# Admission domain API increment — partial integration contract

This supplements existing admission APIs; it is not the master's final UI handoff.
All calls run off the UI thread with the current opened encrypted Records owner.
No snapshot grants authorization. Locked/invalid data fails closed. Exceptions
must not be printed with payloads. Typed AdmissionException/ConnectivityException
codes and OperationFailure.classify are now available; see UI_API_CONTRACT.

- `isAdmissionAuthority()`: validates local authority key against pinned realm;
  returns false if absent, throws on lock/mismatch. Never generates an authority.
- `status()`: `State`, nullable request expiry and nullable credential expiry in
  existing protocol epoch seconds. Request expiry is not membership expiry.
- `issuedCredentials()`: authority-only immutable list, at most MAX_DECISIONS;
  IDs, device ID, issued/expiry timestamps, locally known revocation flag.
  Not a public member directory; no private key or complete credential exposed.
- `cancelPendingRequest(expectedRequestId)`: local atomic cancellation, preserves
  installed credential. Stale UI cannot cancel a replacement request. Late approval
  cannot install against the absent/replaced pending request. No global recall.
- `approveAndInstallOwnAdmission(review, confirmed, ttl)`: local authority's own
  pending request only, one transaction. Remote-device request cannot install.
- `installRenewal(credentialWire, revocationWire)`: one transaction; rejects a
  revocation that is not for the current credential/device. Pair must be signed
  by the pinned authority. Pending request binding is still mandatory.

On storage failure, retry only after a fresh user decision with a still-valid
review/lease; no automatic reconnect or approval. Durable replay checks remain.
An API call does not render a screen, verify a person or demonstrate peer PoP.
Claude's UI files, including its Spanish text simplification, were not changed.
