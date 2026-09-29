# Physical route and restart expansion — checkpoint, 2026-09-28

Published diagnostic reference: 080eed971389a51a541f971b46251559ba5f678d.
Master v4 remains open; this document does not close A/B/C.

## Phone route

Windows PnP eventually reported REDMI 17 and ADB Interface, both OK. Port 5037
was owned by wslrelay, forwarding to the Linux ADB server without USB. An
independent Windows ADB server on localhost port 5038 detected one USB target;
the owner accepted RSA. No server belonging to another task was killed, no LAN
listener, driver installation, root, USB bind or security-setting change.

Read-only preflight passed: Xiaomi 2606FRN72L, Android 16/API36, ARM64/ARM32,
2026-05-01 patch, battery57%, temperature32C. Boot indicators are not attestation.
No physical Keystore level can be claimed before the fixture actually executes.

Offline debug APK policy, JNI, ABI and certificate checks passed. The package
was absent. Attempts01/02 installed nothing: the second preserved the fixed
Android error INSTALL_FAILED_USER_RESTRICTED. The owner subsequently offered to
accept the normal USB installer prompt; attempt03 is separately recorded. No
blanket install grant, Play Protect disable, replacement or uninstall is used.

## Additional code and local validation

The physical runner now supports an explicitly selected localhost ADB server
and optimized vaultLab inspection, retaining production manifest/DEX/JNI guards.
Eleven runner regressions pass, including refusal of security-flag normalization,
package collisions, wrong targets and sanitized installation-error reporting.
R8 is supported but not claimed executed physically in this checkpoint.

Expand actual force-stop acceptance from PNG to PNG/AAC/PDF in the same synthetic
SQLite store. Require positive native decode/render and persistent consume for
every format before host kill, then reject all replay/reopen after restart.
Three parser regressions pass. Both debug instrumentation builds/lint passed
(exit0,23s); both optimized vaultLab app/test builds passed (exit0,66s).
Native execution of this expansion is PENDING. It is not death during commit,
production Vault authentication, audible playback or a physical sensor test.

## Native video failure retained

Privacy run36511468136 failed both matrices on the diagnostic reference;
checkout8ae68303e24ab230b6874b282421959c1c38089f. Fixed stage receipts prove
synthetic AVC encode and AAC encode finished before SIGABRT in muxing;
owned sanitized native metadata names MediaMuxer/MPEG4Writer. No raw crash log
or media was persisted. Root condition remains unknown; add bounded fixed-symbol
diagnostics, not retries or skipped tests. The decoder raw-format correction did
not resolve this separate native abort. Partial surface playback remains local,
not published in this checkpoint.

Prior a866 startup R8 failed with `Lab network did not return before explicit
action`; startup passed on 080eed9 without a related fix. Preserve it as an
unresolved laboratory intermittency, not a demonstrated correction.
