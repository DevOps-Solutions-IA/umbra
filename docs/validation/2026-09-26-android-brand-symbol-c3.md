# Brand symbol replaced by refined Variant C (C3) — 2026-09-26

Status: PARTIAL (static checks here; Android build/lint/instrumentation/screenshots pending CI).
Previous note (`2026-09-26-android-branding-icons.md`) is kept unchanged as history.

Decision by the owner: Variant C (minimal táctico) replaces Variant A as the official symbol.
Three refinements of C were produced (C1 suspended wedge, C2 bevelled tops, C3 chevron); C3 was
selected for 16dp legibility and letter-avoidance (rationale: `docs/design/branding/README.md`).

## Replaced resources

- `res/values/brand.xml` `@string/umbra_symbol_path` → C3 geometry (single source of truth).
- `res/drawable/ic_launcher_foreground.xml` → transform ×2.6, translate (22.8, 22.67); max radius
  30.2dp < 33dp safe zone. Used by `mipmap-anydpi-v26/ic_launcher(_round)` foreground and
  monochrome layers and by the splash theme attribute.
- `res/drawable/umbra_symbol.xml` (in-app logo) and `res/drawable/ic_notification_umbra.xml`
  reference the string, so they changed with it; lock, onboarding, About, invitation and QR
  logos use `Ui.logo()` → same drawable. No internal glyph contained the logo; none changed.
- Design evidence: `umbra-symbol-c1/c2/c3.svg`, `umbra-symbol-c-refinements.png`,
  `umbra-launcher-masks-mockup.png`, `umbra-play-store-512.png`, `umbra-launcher-full-bleed.svg`.
  A, B and the original C remain only as documented proposals.

## Tests

`UiIconResourcesTest.symbolHasOneSourceOfTruthAndFitsTheSafeZone` now also checks: exactly three
pieces, gaps ≥ 2.4 units (≥ 1.6px at 16dp), central blade ≥ 3.6 units, horizontal symmetry,
vertical centering, and that no discarded symbol geometry (A, B, original C) remains in `src/**`.
Existing launcher/adaptive/monochrome/notification/splash/reference tests unchanged and passing.
Executed here: 56 UI JVM tests (local JUnit shim) pass; type check 0 errors both flavors.
`UiScreensRenderTest` (safe zone on the real AdaptiveIconDrawable, masks, splash, notification)
is resource-driven and needs no change; not executed here.

## Consolidation (same day, follow-up commit)

- Launcher background color renamed to the conventional `@color/ic_launcher_background` (#0E120F),
  used by both adaptive icons and the splash background/icon background.
- Secondary symbol color #879676 (`ACCENT_SECONDARY`) applied to the small logos of the invitation
  QR card and the verification QR panel; primary logos stay #A0AD93.
- New JVM check `brandColorsAndRequiredIconFamiliesArePresent`: brand colors and presence of every
  security, privacy, communication, location, device and navigation glyph family.
- Search for Variant A geometry under `android/app/src`: only the test's discarded-geometry guard.
- Executed here: 57 UI JVM tests pass (local JUnit shim); type check 0 errors both flavors;
  tool tests and repository guard pass. Gradle build/lint/instrumentation and emulator captures
  remain pending CI.
