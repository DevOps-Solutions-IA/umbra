# Post-acceptance privacy/exfiltration source review — 2026-09-29

VERIFIED scope: read-only review of production Java/XML in `android/app/src/main`,
`src/connected`, `src/offline`, selected storage/backend code, frozen API documents,
and physical-runner source on base `e0024f091d29dc15c2d788b430c5ea11204e5060`.
Only this new evidence document is added by this subreview. No contract, UI,
manifest, production adapter or runner was modified. No Gradle, ADB, physical
inspection, clipboard read, network interception or new native test was executed.
This is bounded source review, not certification of absence of exfiltration.

## Source findings and limits

| Surface | VERIFIED source evidence | Practical boundary |
|---|---|---|
| Android logs | Search for `Log.`, `println`, `printStackTrace`, `System.out` and `System.err` found no call sites in reviewed production Java. `connected/.../media/NativeVoiceSession.java` initialization disables internal WebRTC tracing and injects a no-op logger at LS_NONE. | Does not inspect native dependency binaries, platform crash/codec logs, OEM behavior or runtime logging. No global logs were collected. |
| Relay logs | `relay/umbra_relay/app.py` storage handler returns generic503 and logs only SQLite numeric code, without exception traceback; validation handler returns generic422. | Server/proxy/operator configuration can independently retain addresses/metadata. This source review does not prove production access-log policy or hosting behavior. |
| Android records/WAL | `main/.../data/Vault.java` seals records through `VaultCodec` before insertion, uses keyed indexes and nonce/ciphertext columns; configures secure_delete, synchronous FULL and memory temp storage. | Buckets, size and SQLite metadata remain visible. Java Strings, old copies, page remnants and privileged snapshots are not forensically erased by array wiping or secure_delete. No physical DB/WAL scan here. |
| Backend persistence | `relay/umbra_relay/app.py` enables WAL and persists envelopes/digests/metadata; client content remains Signal ciphertext. | Backend sees route IDs, timing, size and ciphertext. WAL is not an anonymity or forensic-erasure mechanism. |
| Restricted temp files | `content/MemoryDocumentDescriptor.java` uses read-only RAM proxy descriptors with authorization checks; `MemoryMuxerDescriptor.java` uses bounded anonymous memfd and wiping/close. `RestrictedAudio`, `RestrictedVideo`, `RestrictedPlayback` use memory media sources. | RAM descriptors/codecs can retain transient copies; no promise about swapped/kernel/native buffers or hostile OS. No regular-file plaintext fallback found in these adapters. |
| PDF process | `main/AndroidManifest.xml` declares only the nonexported isolated `RestrictedPdfService`; descriptor transport is bounded by the preparation/service code. | Isolation does not establish absence of framework/native decoder vulnerabilities. |
| Backups/components | Application disables allowBackup/fullBackupContent; `res/xml/data_extraction_rules.xml` excludes root/database/sharedpref/file/external from cloud and device transfer. No ContentProvider/FileProvider, exported service or receiver is declared in reviewed main/flavor manifests; launcher Activity is exported. | Source manifests are not the merged binary. APK permission/component verification belongs to the separate build receipt; OEM backup behavior is not exercised. |
| Network configuration | `res/xml/network_security_config.xml` permits system anchors only and disables cleartext. Offline manifest removes INTERNET and ACCESS_NETWORK_STATE; connected alone adds camera/mic/audio-setting permissions. | Offline APK final permission absence must be verified on actual APK. Platform location services are outside the app's own network permission boundary. |
| Clipboard | `privacy/OrdinaryTextExport.java` selects only unexpired ordinary stored text for the specified peer/message; original authorization and one-use60s consent are rechecked. `PrivateClipboard.java` requires focused main-thread owner, sets sensitive marker, and clears only its marked current clip. | Clipboard export is deliberately irreversible. Another app/IME/OS can retain it; foreground cleanup is not recall and is not automatic systemwide clearing. No clipboard contents accessed. |
| Autofill/capture | `privacy/PrivateAndroidSurface.sensitiveInput` disables save/freezes, autofill and content capture; requests no personalized IME learning. Surface adapter sets FLAG_SECURE, hides overlays and disables recents screenshots on API33+. | Caller must invoke before presentation and clear its own sensitive views. Flags do not defeat cameras, compromised keyboards/accessibility/OS, or unprotected caller-owned surfaces. |
| Notifications | `PrivateAndroidSurface.notification` has fixed generic title/text, SECRET visibility and local-only flag; no content input or posting operation. | Adapter existence does not prove every future caller uses it or validate OEM notification behavior. |
| Explicit external exports | `Engine.stageExport` temporarily persists caller-provided ordinary export bytes through encrypted Records, then `DocumentIO` writes via an explicitly selected provider under AccessGate checks. Restricted service export denial is separate from this general ordinary-export API. | Provider receives deliberately exported plaintext and may retain it. An in-progress external write cannot be recalled. No basis to claim all files in arbitrary external providers remain protected. |

Paths abbreviated `main/...` above mean
`android/app/src/main/java/app/umbra/`; `connected/...` means
`android/app/src/connected/java/app/umbra/`.

Existing `android/app/src/androidTest/java/app/umbra/RestrictedContentAndroidTest.java`
contains a meaningful owned encrypted-Vault/DB/WAL/SHM/journal sentinel scan and
requires actual WAL bytes before scanning. This review read that test but did not
execute it; historical native receipts must retain their exact source/APK scope.

## Contract integration observations, not silently fixed

VERIFIED: `UI_API_CONTRACT.md` revision UI_SECURITY_CONTENT_API_V1 and
`API_GAPS_UI_SECURITY.md` require stable typed error mapping, safe generic fallback,
no message/cause parsing, and explicit privacy adapter integration. Production
`privacy/OperationFailure.classify` follows this rule and defaults to UNAVAILABLE.
The clipboard, notification and sensitive-input adapters match their documented
signatures and limitations. Frozen documents were not edited.

Two legacy-UI discrepancies remain visible and must be handled in the separately
scoped UI integration, not confused with adapter acceptance:

1. `android/app/src/main/java/app/umbra/ui/MainActivity.java:201` (`safeError`)
   returns messages shorter than180 characters from IllegalArgumentException,
   SecurityException or IllegalStateException. This bypasses the prescribed typed
   mapping and does not by itself establish that the message is presentation-safe.
   **VERIFIED contract gap; no secret exfiltration path demonstrated here.**
2. The same file's `edit()` at line876 sets no-autofill and no-personalized-learning,
   but does not apply `sensitiveInput`'s explicit save/freezes/content-capture
   controls. Activity onCreate applies FLAG_SECURE/hide-overlays but does not call
   the adapter's API33 recents method. **VERIFIED wiring difference; native state
   capture/recents leakage is NOT_VERIFIED.** This is not evidence that FLAG_SECURE
   fails or that an actual saved-state payload leaked.

No combined Claude UI or screen behavior is accepted by this source review. The
existing docs explicitly keep graphical integration separate. No new domain API
or frozen-contract modification is required merely to use the existing adapters.

## Physical two-peer selector boundary

VERIFIED: `scripts/run_physical_tests.py:57` accepts one explicit USB-style serial,
rejects emulator/network-style selections and absent/offline/unauthorized matches;
`physical_profile` rejects qemu1 and unsupported/unknown API, checks battery and
temperature, and `device_lock` locks the selected serial. Its CLI has one `--serial`
and its receipt explicitly sets `physicalTwoPeerTested=false`.

There is **no reviewed physical two-peer selector/runner in this base**.
`scripts/run_bluetooth_emulation.py:57` rejects identical A/B serials, then lines88–92
requires qemu1 on both and refuses physical hardware before installation/radio
changes. Two distinct strings there identify an emulation test; they do not
establish two physical phones. Do not adapt it by removing that protection.

The newly prepared `docs/POST_ACCEPTANCE_PHYSICAL_MATRIX.md` correctly demands two
independent physical devices, distinct USB selections and owner confirmation that
A/B are two present phones; two apps/users/profiles on one handset do not count.
It also correctly states that the dual-physical runner remains future work.
That document is a requirement, not an implemented selection guard or a receipt.
**Physical RFCOMM/voice/video remains NEEDS_SECOND_PEER and runner review.**

No synthetic fixture, source scan, source permission removal or historical one-phone
receipt in this review is relabelled as a dual-radio or current physical result.
