# Safe physical runner checkpoint — 2026-09-28

Master v4 is read in full and remains open. The SHA-256 of the preserved cb2df35
Windows export was recomputed: f12e07913b301099dbfc4493fa2c022037bbea3fadd6fd466755cab3fa303f89.
Claude's bundle was rehashed and `git bundle verify` passed with its two recorded
prerequisites. It was not merged and no product UI changed.

The first physical runner lane is now implemented: explicit selected USB device,
per-device lock, compatibility/readiness, exact package/signature/hash checks,
collision refusal, no replacement install and fixed reviewed test methods.
Eight instrumented tests exercise existing synthetic PNG/AAC/PDF, Signal and
SQLite APIs; an explicit RunListener additionally observes a disposable
non-authenticated Keystore key. It is NOT an automatically discovered test:
AVD suites and their existing test counts are preserved, not skipped.
It neither enables sensors nor changes OS settings. See PHYSICAL_DEVICE_TESTING.

Local validation: eight runner regression tests and all 196 script tests pass.
Both debug instrumentation APKs and lint build successfully (JDK 21, Gradle 8.13,
AGP 8.13.2, SDK 36, unchanged pins). Inspection of both flavors' actual local
app/test APK identities and matching signatures passed using SDK aapt/apksigner.
These are tooling/build results, NOT a physical instrumentation pass.

Physical status: NOT EXECUTED. Linux and Windows SDK ADB 36.0.0-13206524 both
return an empty device list. No phone serial selected, no package installed,
no hardware level observed. Owner action: connect USB and authorize the chosen
Windows ADB host on the phone. No PIN/password should be supplied to the agent.
R8 physical lane, authenticated Vault interaction and sensor/route tests remain
explicitly pending; AVD suites continue. Two-radio tests need a second peer.

The separately published RFCOMM fixture correction is
5c40c24f81e7525382447a67766cf544a2e6f859. Its CI is distinct from this new runner
revision. Earlier red logs are retained. No master completion is claimed.
