# Security/content completion — incremental domain integration

Status: partial implementation, not acceptance. Base: ba75d329bedb2a26cd2e70371d2369e2fb3ba7a1.

The emergency coordinator, encrypted Records transactions, vault leases and
private-startup gates remain authoritative. No replacement permission system or
UI is introduced. Claude's ca2a706 bundle is a reviewed contract reference only.

## Admission result atomicity

A renewal result contains two independently signed objects. Import must validate
both against the pinned realm, bind the revocation to the currently installed
credential and the replacement to the same device/Signal keys, then install both
in one Records transaction. A valid replacement paired with a malformed or
unrelated revocation must leave the credential and pending request unchanged.
The current credential may have expired: renewal still requires its authenticated
binding, a live local vault lease, a pending request and authority-signed approval.
No contact trust is raised. No authority rotation or network provisioning is added.

Self-approval and installation also need one transaction. A failed commit must
not consume the in-memory Review token permanently: durable decision/nonces, not
a pre-commit boolean, determine replay rejection. Reviews remain tied to their
originating service and vault authorization generation.

Reference: Claude commit 798f5d58805335b64be2d9e3d920082c4c7d9047
identified an atomic renewal import requirement in its UI flow. This change moves
the invariant into the domain; none of that commit's UI is imported.

## Remaining master scope

Admission snapshots/errors, nonvisual privacy adapters and restricted-content
blocks B/C require subsequent implementation and validation. This ADR does not
claim those features exist. Restricted access must eventually be enforced by
persistent atomic consumption and bounded sessions, not presentation flags.

## Nonvisual privacy adapters

Android framework only; no dependency added. `PrivateAndroidSurface` must be
invoked by the presentation owner before window/surface attachment. It sets
FLAG_SECURE, overlay rejection and (API33+) recents screenshot prohibition.
It cannot retroactively secure a frame already displayed; product integration
remains Claude's task. Generic notification construction accepts no message,
name, location or intent. Sensitive inputs disable restoration/content capture
and request no IME personalised learning; a compromised IME/OS remains outside
this guarantee. The domain-owned buffer has bounded, single-transfer ownership.

`ImagePreparation` accepts JPEG/PNG only, <=4MiB compressed and <=2048 each axis /
4MiPixels decoded. Framework ImageDecoder handles orientation into a software
sRGB bitmap; a fresh PNG is encoded in memory with <=256KiB output. No source
metadata/EXIF is copied; originals remain untouched. Output too large is rejected,
not a reason to increase the existing attachment limit. Cancellation rechecks the
vault lease before/after native decode/encode, which cannot be interrupted mid-call.
These are preparation APIs, not restricted-content session capabilities.

References reviewed 2026-09-28:
https://developer.android.com/reference/android/graphics/ImageDecoder
https://developer.android.com/security/fraud-prevention/activities
https://developer.android.com/privacy-and-security/risks/secure-clipboard-handling

Clipboard adapter accepts only an explicitly selected existing ordinary text
message from Engine, not a content buffer/type boolean. It requires current
vault/contact/admission authorization and focused foreground Activity. No URI,
attachment or restricted object copying. An opaque ownership marker permits
explicit cleanup of the currently matching clip; Android offers no compare-and-
swap clipboard API, so competing clipboard writes and copies already obtained by
other apps cannot be recalled. No background timer or indiscriminate global clear.
The permission is for deliberate ordinary-message export, not restricted content.
