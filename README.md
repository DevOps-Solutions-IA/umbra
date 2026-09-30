<div align="center">

# UMBRA

### PRIVATE ANDROID COMMUNICATIONS

**Local identity · explicit trust · fail-closed access · controlled connectivity**

Android-native secure communications project for closed, explicitly admitted environments.

</div>

---

## Operational status

| Control | Current reference |
|---|---|
| Technical baseline | `e0024f091d29dc15c2d788b430c5ea11204e5060` |
| Technical line | `codex/security-content-completion` |
| Acceptance receipt | [PR #16](https://github.com/DevOps-Solutions-IA/umbra/pull/16) |
| UI contract | `UI_SECURITY_CONTENT_API_V1` |
| CI on accepted baseline | 10 workflows · 27 jobs · SUCCESS |
| Final graphical integration | In progress on a separate presentation line |
| Production release | Not published |

> **Repository note:** `main` remains a conservative historical baseline while the accepted technical line and final UI integration are reviewed separately. Do not infer current product capability from an older commit on `main`.

UMBRA is under active development. The accepted technical baseline has passed the executed software, Android, R8 and laboratory layers recorded in PR #16; this is **not** a security certification or a claim of production readiness.

---

## Mission profile

UMBRA is designed around a narrow operating doctrine:

- local cryptographic identity; no phone number or email as identity;
- explicit device admission into a private realm;
- human verification kept separate from admission and device linking;
- end-to-end encrypted 1:1 messaging using libsignal;
- explicit network activation — unlocking does not silently connect;
- HTTPS relay and Bluetooth RFCOMM as distinct transport paths;
- TURN-only policy for internet voice/video media;
- restricted-content modes for one-time and UMBRA-only access;
- local encrypted vault protected by Android Keystore and a personal password;
- emergency invalidation that denies new access before coordinated resource shutdown;
- separate connected and offline Android variants.

The system is intentionally fail-closed: an unknown state, invalid credential, altered policy, unsupported format or stale authorization is rejected rather than downgraded.

---

## Operating doctrine

### 01 — Identity is local

UMBRA creates identity material on the device. User-facing aliases are presentation data, not identity anchors.

### 02 — Admission is not trust

A valid realm credential authorizes participation in the private environment. It does **not** automatically mark another person as verified.

### 03 — Trust requires explicit verification

Contact trust states are independent from admission, linking and group membership. Identity changes suspend prior trust until explicitly resolved.

### 04 — Connectivity is deliberate

Opening or unlocking UMBRA does not automatically create network sessions. Online and Nearby authorization are independent user decisions.

### 05 — Restricted content is enforced in the domain

A hidden button is not a security control. One-time access, expiry, export denial and recipient binding are enforced below the presentation layer.

### 06 — Failure does not widen access

Storage, cryptographic, authorization, media or cleanup failures do not create fallback access paths.

---

## System architecture

```text
┌─────────────────────────────────────────────────────────────┐
│                        ANDROID CLIENT                       │
│                                                             │
│  Identity / Trust     Vault / Password     Admission        │
│  Messaging / Signal  Restricted Content   Emergency Lock    │
│  Location            Calls / Media        Privacy Adapters  │
│                                                             │
└───────────────┬──────────────────┬──────────────────────────┘
                │                  │
        E2EE HTTPS envelopes       │ Bluetooth RFCOMM
                │                  │
                ▼                  ▼
        ┌──────────────┐     ┌──────────────┐
        │ HTTPS RELAY  │     │ NEARBY PEER  │
        │ opaque data  │     │ direct radio │
        └──────┬───────┘     └──────────────┘
               │
               │ signaling / authorized media
               ▼
        ┌──────────────┐
        │ TURN RELAY   │
        │ RELAY_ONLY   │
        └──────────────┘
```

The relay handles routing and opaque envelopes; application content keys and plaintext are not intentionally delegated to the relay.

---

## Core capabilities

### Identity, vault and admission

- local Signal identity;
- signed one-use pairing material;
- explicit safety-code verification;
- multi-device linking with device-specific keys/sessions;
- signed private-realm admission credentials with proof of possession;
- individual renewal and revocation;
- AES-256-GCM local record protection;
- HMAC indexes for protected local lookup;
- Android Keystore binding;
- Argon2id personal-password layer;
- no master password, server password recovery or silent identity reset.

### Messaging and transport

- encrypted 1:1 text;
- encrypted attachments;
- persistent ACK/retry behavior;
- HTTPS relay transport;
- Bluetooth RFCOMM transport;
- per-device ciphertext handling;
- explicit online and Nearby consent;
- no automatic network restoration after unlock or process restart.

### Calls and media

- authenticated 1:1 call signaling;
- TURN-only voice transport policy;
- TURN-only video transport policy;
- local voice modulation with fail-muted processing behavior;
- encrypted location sharing with explicit recipient/device authorization.

### Restricted content

| Mode | Behavior |
|---|---|
| **ONCE** | Persistent consumption occurs before presentation; reopening is rejected. |
| **UMBRA_ONLY** | May be reopened while valid, but remains non-exportable. |
| **Expiry** | Object lifetime and active-session lifetime are separate controls. |

Supported restricted profiles are deliberately narrow:

| Type | Current profile |
|---|---|
| Image | JPEG/PNG input, sanitized PNG output; bounded processing |
| Voice note | AAC-LC / ADTS, mono 16 kHz; connected capture up to 8 s |
| File video | AVC baseline, up to 320×240, 45 frames, 3 s, optional AAC |
| PDF | Static raster copy, up to 4 pages, no external viewer |

Restricted objects are not exposed through a generic save/share/print/external-open path.

---

## Android operating variants

### Connected

Includes authorized HTTPS transport and internet media functionality in addition to local/Nearby operation.

### Offline

The accepted APK policy removes:

- `INTERNET`
- `ACCESS_NETWORK_STATE`
- `RECORD_AUDIO`
- `CAMERA`
- WebRTC media binaries

Offline can still support the functionality explicitly permitted by its own manifest and domain policy. This constrains the UMBRA process; it does not make the entire Android device networkless.

---

## Validated technical baseline

The accepted technical baseline is:

```text
e0024f091d29dc15c2d788b430c5ea11204e5060
```

Its recorded acceptance includes:

| Layer | Result |
|---|---|
| GitHub Actions | 10 / 10 workflows SUCCESS |
| Jobs | 27 / 27 SUCCESS |
| Verify Android | 69 connected · 66 offline |
| JVM Android suites | 269 connected · 212 offline |
| File-video matrices | 32 debug · 32 R8 |
| Focused media | 9 debug · 9 R8 |
| Nearby focused | 8 scenarios |
| Build / lint / release / R8 | PASS in the recorded acceptance |
| JNI / APK policy | PASS in the recorded acceptance |

The complete evidence, failure history and scope limitations are tracked in [PR #16](https://github.com/DevOps-Solutions-IA/umbra/pull/16) and the validation records on the accepted technical line.

### Physical laboratory scope

Authorized isolated hardware tests have exercised synthetic debug/R8 cases and persistent restricted-content consumption on an Android 16 arm64 device.

They do **not** establish:

- authenticated production-Vault hardware acceptance;
- complete biometric acceptance;
- physical microphone/camera/acoustic-route acceptance;
- two-phone RFCOMM/voice/video acceptance;
- global absence of device traffic outside the app's observable boundary.

Those remain separate hardware acceptance items.

---

## Security boundaries

UMBRA deliberately does **not** claim to be “unbreakable”, “military-grade”, anonymous, or resistant to every compromised device.

The current design does not prevent:

- a recipient from photographing a screen with another device;
- external audio recording;
- privileged or compromised-OS observation of plaintext while legitimately displayed;
- traffic-analysis metadata visible to infrastructure or network operators;
- rollback or snapshot attacks outside the demonstrated application boundary;
- forensic recovery guarantees from managed memory or flash storage;
- denial of service by infrastructure;
- vulnerabilities in Android, native codecs, dependencies or future platform changes.

TURN-only media avoids direct peer media paths by policy; it does not make the TURN operator or ISP blind to connection metadata.

Security claims are limited to the mechanisms and evidence actually executed.

---

## Development reference

The current accepted technical line is intentionally separate from the historical default branch.

- [PR #16 — accepted technical receipt](https://github.com/DevOps-Solutions-IA/umbra/pull/16)
- [Accepted technical tree](https://github.com/DevOps-Solutions-IA/umbra/tree/e0024f091d29dc15c2d788b430c5ea11204e5060)
- [Security model](https://github.com/DevOps-Solutions-IA/umbra/blob/e0024f091d29dc15c2d788b430c5ea11204e5060/docs/SECURITY.md)
- [Android build procedure](https://github.com/DevOps-Solutions-IA/umbra/blob/e0024f091d29dc15c2d788b430c5ea11204e5060/docs/ANDROID_BUILD.md)
- [Validation records](https://github.com/DevOps-Solutions-IA/umbra/tree/e0024f091d29dc15c2d788b430c5ea11204e5060/docs/validation)

Current toolchain on the accepted line:

```text
JDK             21
Gradle          8.13
Android Gradle  8.13.2
Android SDK     36
Build Tools     35.0.0
minSdk          31
libsignal       0.102.3
```

No production signing key belongs in this repository. No production-signed APK or Play Store release is represented by this README.

---

## Repository structure

```text
android/                 Native Android client
relay/                   HTTPS relay and persistence
native/                  Reviewed native/WebRTC material
scripts/                 Build, validation and laboratory tooling
docs/                    Architecture, protocol and security contracts
docs/validation/         Dated evidence and failure history
.github/workflows/       CI and Android/media laboratories
AGENTS.md                 Agent/repository operating rules
```

---

## Engineering discipline

Changes that affect identity, authorization, cryptography, persistence, transport or restricted-content policy require:

1. a reproducible failing case or explicit requirement;
2. a minimal reviewable change;
3. regression coverage;
4. validation against the relevant matrix;
5. preservation of prior failed evidence;
6. an explicit statement of what was **not** demonstrated.

A later green run does not retroactively explain an earlier failure.

---

## License and third-party software

Original UMBRA source files are distributed under the repository's **MIT License**.

Third-party components retain their own licenses and obligations. In particular, the project references **libsignal 0.102.3**, whose upstream project declares GNU AGPLv3 terms. WebRTC, Bouncy Castle and other dependencies retain their respective notices.

Review [LICENSE](LICENSE) and [THIRD_PARTY_NOTICES.md](https://github.com/DevOps-Solutions-IA/umbra/blob/e0024f091d29dc15c2d788b430c5ea11204e5060/THIRD_PARTY_NOTICES.md) before redistribution.

---

<div align="center">

**UMBRA**

_Explicit trust. Controlled exposure. No silent fallback._

</div>
