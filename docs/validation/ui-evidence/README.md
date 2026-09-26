# UI evidence (synthetic data)

Screenshots are produced by `android/app/src/androidTest/java/app/umbra/UiScreensRenderTest.java`
on the CI emulator and collected by `scripts/run_android_instrumentation.py --evidence-dir`.
Download them from the `Android-test-and-lint-results-<sha>` artifact of the
"Verify UMBRA" run, folder `android/app/build/reports/device/ui-evidence/`.

Only synthetic identities and content are used. Images are off-screen renders of the
production screen builders on real Android framework code, not recordings of an unlocked
session with a real vault.

Required set and file names (connected variant; offline omits calls/modulator/video):

| # | Screen | File |
|---|---|---|
| 1 | Lock | `01-lock.png`, `01b-lock-no-device-credential.png` |
| 2 | Home | `03-home-chats.png`, `03b-home-groups-pending.png` |
| 3 | 1:1 chat | `04-chat-verified.png`, `04b-chat-sharing-location.png` |
| 4 | Group chat | `05-chat-group.png`, `19a/19b` new group, `19c-new-message.png` |
| 5 | Contact info | `06-contact-info.png` |
| 6 | Verification | `07-verify-code.png`, `07b-verify-qr.png`, `07c-verify-manual-identity-changed.png` |
| 7 | Devices | `08-devices.png` |
| 8 | Location | `09-location-sheet.png`, `09b-location-stop.png` |
| 9 | Voice call | `10-call-voice.png`, `10b-call-muted.png`, `10c-call-mic-not-authorized.png`, `16-incoming-call.png` |
| 10 | Modulator | `11a-modulator-enabling.png`, `11b-modulator-on.png`, `11c-modulator-error.png` |
| 11 | Video call | `12a-video-consent.png`, `12b-video-call.png` |
| 12 | Settings | `13-settings*.png` |
| 13 | Offline | `14-offline-nearby.png`, `14b-offline-chat.png` (offline variant) |
| 14 | Identity changed | `15-chat-identity-changed.png`, `15b/15c` unverified/blocked |
| — | Onboarding, empty, errors, large font | `02*`, `17-empty-state.png`, `18-errors.png`, `20*` |

Status on 2026-09-26: not yet generated (no CI run of this branch). Add a dated
receipt here with the run id and SHA once the artifact exists; do not replace it
with mockups.
