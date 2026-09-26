# Android UI foundation — working evidence, 2026-09-26

Status: PARTIAL. Code written and type-checked locally; Gradle build, lint,
instrumentation and emulator screenshots are PENDING the first CI run of this
branch. Nothing here claims production readiness.

Branch: `claude/android-ui-foundation`.
Base: `codex/local-voice-modulator` @ `6b0a844` (PR #10, draft). The open PR
chain is linear (`android-build-validation` → … → `turn-video-core` →
`local-voice-modulator`); this is the newest head and the only one exposing the
modulator state the UI must mirror. No base branch was modified or merged.

## Audit before changes

- Java 21, framework Views built in code; no XML layouts, no AndroidX, no
  Compose, no Kotlin. Single `MainActivity` (884 lines) mixed lifecycle,
  engine calls and rendering; four tabs (Chats, Cerca, Identidad, Ajustes).
- Real engine/service APIs used by the UI: `Engine.trustState()`,
  messages/files, `LocationService` review/start/stop, `CallService`
  sessions/invite/accept/end and video review state, `NativeVoiceSession`
  (via `VoiceControls`) state/modulation/video/capture evidence, stored signed
  device rosters, pairing exports.
- Not implemented by the engine: groups, reply, unread/mute, contact deletion,
  EXIF cleaning, vault password, private admission/startup, emergency lock,
  camera QR scanning, orchestrated device revocation, device-linking wizard.
- Flavors: `offline` has no INTERNET/NETWORK_STATE (manifest overlay +
  `ALLOW_RELAY=false`), `CallPlatform.ENABLED=false` and a stub `VoiceControls`.

## Architecture

```
ui.screens (Views from presentation state + callbacks; no Engine access)
    ↑
ui.model   (pure Java: TrustPresentation, ModulatorPresentation, Navigator,
            FeatureAvailability, ErrorPresentation, … — JVM-tested)
    ↑
MainActivity (lifecycle, lock gate, worker-thread Engine/service calls,
              maps engine-reported values to presentation state)
    ↑
Engine / services / VoiceControls (unchanged behavior)
```

`ui.design` holds tokens (`UmbraColors`, `UmbraType`), icons and components
(`Ui`, `SecureDialogs`). No new dependency. `FeatureAvailability` decides what
may be presented as working; anything else shows «UI preparada · Backend
pendiente» or is hidden when the flavor excludes it.

Only change outside `ui/`: `VoiceControls` (connected + offline stub) gains a
read-only `Snapshot` and thin methods reusing the exact `NativeVoiceSession`
calls and epoch/consent guards of its existing dialog; the video-answer lambda
was extracted unchanged into `answerVideo()`. `scripts/run_android_instrumentation.py`
expects 42/40 tests (was 27/25; +15 `UiScreensRenderTest`), allows 300 s, and
can pull the rendered evidence; `verify.yml` passes `--evidence-dir`.

## Real backend vs prepared UI

| Area | Backend real | Prepared only |
|---|---|---|
| Lock | Keystore/biometric unlock, auto-lock | Vault password, private startup, emergency lock |
| Onboarding | Local identity creation | Private admission |
| Chats 1:1 | Text, files, delivery status, expiry, export | Reply, unread, mute |
| Photos | — (disabled: EXIF not cleaned) | Photo picker with metadata cleaning |
| Groups | — | Group list, conversation, creation flow (create disabled) |
| Contact | Trust state, block/unblock, clear, roster size | Delete contact |
| Verification | Code, QR display, manual comparison (`Engine.verify`) | Camera QR scan, accept replacement identity |
| Devices | Own signed roster, contacts' roster sizes | Revocation distribution, add-device wizard |
| Location | Review, precise/approx/zone/manual, live, stop | — |
| Voice call | Signaling, mic consent (existing TURN dialog), mute, output, end | — |
| Modulator | Engine states OFF/ENABLING/ON/DISABLING/ERROR_MUTED, retry, confirmed natural | — |
| Video | Per-direction consent, stop, switch camera, remote view (existing protected dialog) | Embedded video surface |
| Settings | Invitations, relay registration, sync, expiry, destroy identity | Notification content, links, EXIF, clipboard controls |
| Offline | Bluetooth only, no call UI, no Internet switch | — |

## Commands executed here and results

Environment: cloud sandbox without Android SDK/Maven access (dl.google.com,
maven.google.com and Maven Central refused by egress policy) and without KVM.

- Type check of `main+connected` and `main+offline` sources against
  `android.jar` API 36 (Sable/android-platforms mirror) plus the vendored WebRTC
  `classes.jar`; libsignal/zxing replaced by signature stubs, so only errors in
  `ui/**` and `VoiceControls` were evaluated: **0 errors in both flavors, at
  every commit of the branch**. This is NOT a Gradle/AGP build.
- UI JVM tests (`Ui*Test`, 10 suites, 47 methods) compiled and run with a
  minimal local JUnit shim: **47 passed**. Real JUnit/Gradle execution pending CI.
- `UiScreensRenderTest` compiled against the same stubs; **not executed**.
- `python -m unittest discover -s scripts/tests`: 147 tests OK.
- `python scripts/repository_guard.py`: PASS. `check_source_policy.py`: 12 PASS.
- `CoreSelfTest` 20, `SecuritySelfTest` 85 scenarios passed; `JavaSyntaxCheck`
  parsed 123 files.

Not executed: `gradle testConnectedDebugUnitTest/testOfflineDebugUnitTest`,
assemble/lint (debug, release), merged-manifest and APK policy scripts,
instrumentation, emulator screenshots, TalkBack session, physical devices.

## Visual evidence

`UiScreensRenderTest` renders the production screen builders with synthetic
data (Ana, Bruno, Carlos, Equipo Operaciones, Documento.pdf) on the CI emulator
and writes PNGs; CI copies them to
`android/app/build/reports/device/ui-evidence/{connected,offline}` inside the
`Android-test-and-lint-results-<sha>` artifact. They are off-screen renders of
each screen, not recordings of an unlocked session. No screenshot is committed
until that run exists; see `docs/validation/ui-evidence/README.md`.

## API issues found

1. `NativeVoiceSession` exposes no mute getter; `VoiceControls` now records mute
   only after `mute()` succeeds.
2. Remote video proposals are only visible as raw session JSON
   (`video.state == "REVIEW"`); a typed accessor would be safer.
3. `DeviceService.revoke()` returns a transcript that must be distributed to
   other devices/contacts; there is no orchestrated API, so the UI keeps it off.
4. `Engine.confirmIdentityChange()` needs the new card, but no flow delivers it;
   accepting a replacement identity is pending.
5. `DeviceRoster.parse()` enforces freshness, so the UI cannot distinguish an
   unapproved roster from an expired one.
6. Engine message JSON has no typed size/mime; file size is estimated from the
   Base64 length. No last-message, unread or mute data exists.
7. Incoming calls and relay reachability are only observed through the 8 s
   sync tick and status strings.

## Pending

CI run and review; screenshots from that run; TalkBack/manual review on
emulator and physical phones; the planned security PRs (vault password,
private admission, private startup, emergency lock, notification/Recents/
screenshot/EXIF/clipboard protection) remain separate.
