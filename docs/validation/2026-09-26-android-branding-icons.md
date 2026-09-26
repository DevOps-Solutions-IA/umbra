# Android branding and icon system — working evidence, 2026-09-26

Status: PARTIAL. Resources and code written; static checks pass here. Gradle build, lint,
instrumentation and emulator screenshots are PENDING the CI run of `claude/android-ui-foundation`.

Scope: branding and iconography only. No change to cryptography, Signal, Keystore, TURN, WebRTC,
Bluetooth, Engine, protocols, permissions or call behavior. Navigation, flows and copy unchanged.

## Changes

- Symbol variant A selected (rationale and the two retained alternatives: `docs/design/branding/README.md`).
- Adaptive launcher + round launcher with separate background/foreground/monochrome; manifest
  `icon`/`roundIcon`; old `drawable/ic_umbra.xml` removed.
- Android 12 splash via theme attributes (symbol on #0E120F, no text/loader).
- `ic_notification_umbra` white silhouette (prepared; no notifications are posted).
- `Ui.logo()` for lock, onboarding, About, invitation and QR panels, all from `R.drawable.umbra_symbol`.
- 23 new glyphs; trust, offline, device, location, vault/emergency and modulator states mapped;
  icon buttons tint by state (`StateColors`); disabled call/video buttons no longer use 40% alpha.
- Modulator icon: one geometry; OFF #C4CBBF, ENABLING/DISABLING #8E968B, ON #879676, ERROR_MUTED
  DANGER_FG #BE928C (Danger #A35D57 is 2.9:1 on SurfaceElevated, below the 3:1 non-text target).

## Executed here

- Type check (android-36 jar, WebRTC classes, libsignal/zxing stubs): 0 errors in `ui/**`, both flavors.
- UI JVM tests with a local JUnit shim: 56 passed, including `UiIconResourcesTest` (adaptive icon
  layers, manifest refs, no flavor overrides/rasters, symbol single source + safe zone ≤ 33dp,
  notification white-only, every XML ref and `R.drawable` use resolves, 24dp grid for all icons,
  no emoji code points in `main/java`, icon-state contrast, modulator colors/descriptions).
- `UiScreensRenderTest` (now 16 methods) compiled against stubs; not executed.
  Instrumentation totals updated to 43 connected / 41 offline.
- Tool tests, source policy and repository guard: pass.

## Pending

CI (debug/release build, lint, connected/offline JVM + instrumentation, APK policy/permission
checks) and its screenshots: `21-launcher-icon-masks`, `22-splash-theme-composition`,
`23-icon-set`, plus the existing lock/chats/chat/call/modulator/verification/devices/offline renders.
The real system splash and home-screen launcher are not captured by these tests (off-screen renders
of the same resources); capturing them needs a launcher screenshot step on the emulator.
