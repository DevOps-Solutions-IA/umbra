# API gaps found while wiring the UI to vault, admission and private startup

Branch `claude/ui-security-integration` (base `codex/private-startup-no-network` @ `52cd1e5`).
Every gap below is left open in the UI: nothing is inferred, probed or implemented in presentation
code to replace it. Each entry names the file, method, affected flow, the minimal contract the UI
needs, and the tests that should accept it. The domain owner decides the actual design.

## G1 — No public query for administrator capability (blocking for a conditional admin section)

- **File / method:** `android/app/src/main/java/app/umbra/admission/AdmissionService.java`. Authority
  is checked privately by `authority()` (reads the `admission-secret/authority` seed). There is no
  public, side-effect-free query.
- **Flow affected:** "Administración del entorno". The UI cannot tell whether this phone is the
  authority. It therefore shows the tools to everyone with an explanation, and the domain rejects
  the operation when the phone is not the authority. The UI does not infer authority from ADMITTED,
  the realm, the vault, an alias or preferences, and it never probes by creating a realm or signing.
- **Minimal contract:** `boolean isAdmissionAuthority()` (or a capability enum), evaluated under the
  current vault lease. It returns true only when an authority seed exists **and** its public key
  matches the pinned realm. It never returns or logs key material, throws the lock exception when
  locked, and never creates or repairs anything.
- **Tests needed:** it is false for a fresh device, after a realm import and after admission. It is
  true after `createAdmissionRealm`. It is false after a realm/seed mismatch. It throws while locked.
  A stale lease after lock fails. No seed bytes are exposed (API surface and `toString`).

## G2 — No stable failure codes for admission imports and connectivity denials

- **File / method:** `AdmissionService.installRealmConfig` throws
  `SecurityException("Admission authority mismatch")`. Other paths throw `AdmissionCodec.invalid()`
  ("Admission unavailable"). `ConnectivityService.connect` throws the same "Explicit connectivity
  consent required" for every denial (no admission, vault, edition, cleanup failure, state).
- **Flow affected:** import of realm/credential/rejection/revocation, authority decisions, "Conectar".
  The UI does not parse exception text, so it shows a generic, safe message ("No se aceptó esta
  configuración… se conserva sin cambios"). It cannot say "otra autoridad", "vencida", "ya decidida",
  "no eres la autoridad" or "sin red" specifically.
- **Minimal contract:** a typed exception or result enum per operation. Import needs
  `AUTHORITY_MISMATCH, MALFORMED, WRONG_DEVICE, EXPIRED, CONSUMED, REVOKED, NOT_AUTHORITY,
  CAPACITY`. Connect needs `NOT_ADMITTED, VAULT, EDITION, STATE, CLEANUP_FAILED`. Messages stay
  non-sensitive, and codes must not reveal secrets or timing beyond what the UI already knows.
- **Tests needed:** each rejection path returns its code, and the previous pin/state is preserved on
  every failure. The denial reason is not observable before the lease check (no oracle while
  locked).

## G3 — EXPIRED does not say which object expired; expired credential time unreadable

- **File / method:** `AdmissionService.getAdmissionState()` returns `EXPIRED` for both an expired
  pending request and an expired own credential. `requireAdmission()` throws once the credential is
  expired, so its expiry time cannot be shown.
- **Flow affected:** Admission status. The UI distinguishes the two cases only through
  `pendingRequest()` (present and past `expiresAt`) and says "La admisión venció" without a date.
- **Minimal contract:** a read-only snapshot, e.g. `AdmissionStatus status()` with the state,
  `requestExpiresAt` (nullable) and `credentialExpiresAt` (nullable, also when expired or revoked),
  under the vault lease. It must not validate or grant anything.
- **Tests needed:** a request expired without a credential; a credential expired with no request; a
  credential expired with a pending renewal request; revoked with a known expiry; lock propagation.

## G4 — The authority cannot list what it issued

- **File / method:** `AdmissionService` stores decisions in `admission-decisions` but exposes no
  listing. `revokeAdmission(credentialWire, …)` and `renewAdmission(review, oldCredential, …)` need
  the credential text.
- **Flow affected:** revoke and renew. The administrator must import the credential file (or the
  member's renewal request, which carries the current public credential).
- **Minimal contract:** `List<IssuedCredential> issuedCredentials()` with public fields only
  (credentialId, deviceId/fingerprint, issuedAt, expiresAt, revoked). It must be bounded, readable
  only under the lease, and only on the authority.
- **Tests needed:** credentials issued, renewed and revoked appear with the right flags; a
  non-authority gets nothing or is denied; the list is bounded by `MAX_DECISIONS`.

## G5 — No way to withdraw a pending request

- **File / method:** `AdmissionService.createAdmissionRequest()` refuses while a non-expired,
  non-rejected request is pending. No cancel method exists.
- **Flow affected:** "Solicitud pendiente". The UI can only wait up to ten minutes or import the
  answer; it offers no "cancelar/recrear" it cannot honour.
- **Minimal contract:** `cancelAdmissionRequest(boolean confirmed)`, which removes only the local
  pending request under the lease. Nonces must not be reused, and any answer signed for it must then
  be rejected on install.
- **Tests needed:** cancelling then creating works; a credential for the cancelled request is
  rejected; the lock is honoured.

## G6 — Relay provisioning cannot be used before admission under PRIVATE_STARTUP_STRICT v1

- **File / method:** `RelayClient.submitAdmissionRequest`, `admissionResult`,
  `publishAdmission*`. Every `RelayClient` method requires a `ConnectivityService` network lease, and
  `connect()` requires a valid admission.
- **Flow affected:** first admission. A device that is not yet admitted cannot submit its request or
  fetch its result online, so the UI implements the documented offline path (files) and never polls
  or connects before admission. The authority's online publication of decisions is not wired in this
  PR either; decisions are exported as files.
- **Minimal contract (only if online first admission is wanted):** a narrowly scoped provisioning
  lease that is explicit, user-confirmed, allowlisted to the public provisioning routes and origin,
  and time-bounded. It must be separate from `CONNECTED` and never start sync, calls or Nearby.
- **Tests needed:** a provisioning lease reaches only the allowlisted routes; it cannot poll the
  mailbox or send; it is revoked by lock/restart; no DNS or I/O happens before the explicit action
  (lab trap).

## G7 — Admission state of the user's other linked devices is not exposed

- **File / method:** `AdmissionService.requirePeer(deviceId)` is a check (void or throw), not a
  status query. `DeviceRoster` carries no admission.
- **Flow affected:** Devices screen. Admission is shown only for *this* device. The UI does not paint
  A2's admission from A1's, and it does not reuse the user-level identity to label devices.
- **Minimal contract:** `PeerAdmission peerAdmission(String deviceId)` returning
  `KNOWN_VALID / EXPIRED / REVOKED / UNKNOWN`, from locally known public evidence only, with an
  "as known locally" semantic.
- **Tests needed:** a revocation that has not been imported shows the peer as still valid (with
  documented staleness); after import it shows REVOKED; UNKNOWN for devices without evidence.

## Not API gaps (documented limits)

- **QR:** no scanner exists (`Feature.QR_SCAN` stays pending). Requests, credentials and realm
  configuration are exchanged as files. No QR is rendered for admission objects because nothing in
  UMBRA could read it.
- **Auto-lock:** `setAutoLockPolicy` is process-local by contract (maximum 240000 ms; no five-minute
  option in v1). The UI labels it as per-session and applies it only while locked, right before
  unlock.
- **Activity-level unlocked tests:** production keys require secure hardware (`Vault.prepareKey` →
  `requireHardware`). The AVD therefore cannot unlock through `MainActivity` without weakening
  Keystore, which is not done. Unlocked flows are covered through `VaultFlow`/`AdmissionFlow` over
  isolated test keys, and the integrated Activity is covered only while locked.
