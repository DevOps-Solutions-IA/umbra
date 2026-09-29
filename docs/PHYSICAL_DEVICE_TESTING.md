# Physical device laboratory (master v4)

Status reconciled on 2026-09-29: the selected physical USB target passed
read-only preflight and isolated offline debug installation. Historical attempt11
executed ten synthetic cases: 10 PASS / 0 FAIL, including silent native file-video
playback. Earlier failures remain preserved in dated validation reports; this is
not a new execution or validation of the latest source HEAD. TEE was reported for
the synthetic non-authenticated test key, not production Vault authentication.
Physical R8 and the separate Claude preview installation remain blocked by
INSTALL_FAILED_USER_RESTRICTED; no prompt appeared in the owner-reported attempts.
That error does not establish intentional human cancellation or a specific cause.
Claude owns later combined UI validation.

On this WSL2 host Windows port 5037 was owned by `wslrelay`, forwarding to Linux
ADB without USB access. Windows ADB 36.0.0 on a separate localhost-only port 5038
then detected the phone. The owner accepted its RSA prompt. The existing server
was not killed/restarted; no USB bind, firewall or driver changes were made.
Use `--server-port 5038` consistently for this selected route. It cannot select
a remote server, start one, or replace another server. A separately owned server
can be stopped after all tasks using it finish; do not stop the unrelated 5037.

Read-only profile: Xiaomi 2606FRN72L, Android 16/API 36, ARM64/ARM32, patch
2026-05-01; battery 57%, 32 C at preflight. Boot properties green/locked are
informational, not attestation. Serial is kept privately outside repository.

## First lane: no sensors, no external endpoints

`scripts/run_physical_tests.py` defaults to read-only preflight. It requires
`--adb PATH --serial SELECTED_USB_SERIAL --safe --reports NEW_DIRECTORY`.
Never publish the serial. A Windows `adb.exe` route is supported explicitly;
APK paths pass through `wslpath -w`. This Windows route installed the historical
offline debug app/test pair; it does not imply approval for a new installation.
Do not switch ADB servers during a case or run an AVD orchestration script here.

The preflight rejects absent/unauthorized devices, network serials, emulators,
API below 31, unknown battery readiness, battery below 20%, or temperature at
least 40 °C. These are conservative lab scheduling limits, not product limits.
It only reads model/API/ABI/patch, boot indicators and battery; boot properties
are not hardware attestation. It never reads accounts, photos, IMEI or contacts.
A local `flock` serializes this runner's operations per selected device. Other
agents must use the same runner/lock convention; it cannot stop unrelated tools.

For execution additionally provide `--execute --flavor connected|offline`,
`--sdk` (Linux SDK for inspection), `--app-apk`, `--test-apk` and
`--signer-sha256` from the exact locally built debug certificate. Build with
JDK 21 and the pinned Gradle first. The runner verifies signature, package,
instrumentation target, existing APK policy/JNI/permission guards and ABI before
installing either package. It installs only `.dev` or optimized `.vaultlab` isolated packages, without
downgrade or permission grants. Existing unowned packages block the run, even if
signed alike. There is no uninstall/clear workaround. Default runs require exact
previously installed bytes. Explicit `--update-owned` allows `-r` ONLY after the
currently installed hash matches this runner's private ownership receipt and the
new app/test signatures, targets, ABI and policy pass. Record old/new hashes,
retain app data, and accept the normal system installer confirmation. A changed
or unowned installed package is never replaced. Inputs are copied and hashed to
a new report-directory snapshot before inspection, so concurrent builds cannot
change the APK being installed. No build is physical acceptance by itself.

The ten selected tests are explicitly enumerated in `CASES`: three PNG/SQLite
cases, two synthetic AAC codec cases, three isolated PDF cases and two silent
native file-video playback/cancellation cases. They use
real libsignal and Android codecs, in-process delivery and disposable synthetic
SQLite records. No recording, audible playback, clipboard alteration, location
provider changes, Bluetooth, relay or media network setup is selected.
This does not validate production Vault authentication or two physical peers.
The fixture also creates exactly one random non-authenticated AES-GCM Keystore
key in the isolated UID, observes `KeyInfo.getSecurityLevel()`, tests authenticated
encryption and tamper rejection, and deletes ONLY that call's alias. It never
enumerates other aliases. SOFTWARE/TEE/STRONGBOX/UNKNOWN are observed results,
not assumptions; this key does not stand in for production authenticated keys.

Instrumentation must report every exact class/method and a Keystore receipt;
empty, skipped, duplicate or different cases cannot pass. Each attempt gets a
new directory, source HEAD/tree, runner hash, APK hashes/signers, properties,
exit status and limited instrumentation output. No global logcat/bugreport is
collected. The runner force-stops only its owned target on completion/failure;
it leaves installations intact. USB loss and command deadlines fail the case.
Raw instrumentation logs still require review before public upload.

## Remaining lanes — not implied by a passing first lane

- Authenticated Vault/password/biometric operations: MANUAL_PENDING; owner enters
  credentials physically, never through chat. No software fallback.
- Camera/microphone/location and audio route tests: MANUAL_PENDING, explicit
  human consent and an audited per-case runner required. No ambient recording.
- Force-stop/restart restricted consumption: the explicit `--restart-content`
  lane is implemented for four formats and remains NOT EXECUTED physically;
  existing AVD coverage is separate. It force-stops after persistent consumption,
  not during commit. SQLite reopen is not process death.
- R8 physical execution: the runner supports `--optimized` using the existing
  non-debuggable vaultLab flavor, exact mapping/configuration hashes and native
  APK/JNI/permission guards. Only the package name differs in the manifest policy
  comparison; debug flags, backup, TLS and components cannot be normalized away.
  This branch's optimized APKs passed local inspection, but physical R8 execution
  is NOT EXECUTED. Existing AVD R8 suites remain. It is not the exact product APK.
- RFCOMM Android-to-Android and bidirectional physical media: NEEDS_SECOND_PEER.
- No-network global packet absence on non-root phone: BLOCKED_OBSERVABILITY
  where per-UID observation is unavailable; relay silence alone is insufficient.
- UI screenshots/recents/product flow: Claude integration pending.

Do not run root, emulator commands, `pm clear`, uninstall, reboot, host/phone
network reconfiguration or changes to global security settings on this lane.
No self-hosted public PR runner is registered. No background service is installed.
When finished, the owner can revoke the selected host's USB debugging authorization
on the phone after other work completes. No usbipd sharing was configured here.
If a later authorized USB bind is used, record its exact detach/unbind procedure.

References reviewed: Android official [ADB](https://developer.android.com/tools/adb)
and [KeyInfo](https://developer.android.com/reference/android/security/keystore/KeyInfo).

### Continued physical corrections and video subset

Attempt07 diagnostic: PDF result/death arrived but onBindingDied cancelled its
owner. Attempt08 removes that race: seven pass, AAC remains failed. Attempt09
bounds extra AAC EOS padding: all original eight cases pass without changing
assertions. The runner now additionally selects two silent file-video presentation
cases; no camera/audio acquisition or audible output. Attempt10: nine pass, one
ambiguous combined assertion failed; attempt11 requires native start callback AND
decoded frames before lock and labels each assertion: ten pass. Reports retain
every attempt, hashes, source dirty state and no serial. See dated validation.
R8/connected physical and final CI are separate results, never inferred here.

### Owner decision: fresh consent before every installation (2026-09-28)

No APK installation or update is authorized by earlier general execution consent.
Before EACH app/test installation, present the exact isolated package, build and
APK hash, and obtain the owner's specific approval. No installation was running
when this restriction arrived. The runner now refuses pending installs unless
`--approved-install-sha256` explicitly pins every approved APK for that invocation.
The operator must NOT supply these flags without the owner's decision. Approval
is not persisted or inferred from `--execute`, `--update-owned`, a previous run,
or willingness to accept a system dialog. Existing installed identical bytes can
be inspected/tested within the already authorized synthetic scope without install.
No installer-policy workaround is authorized. The four-format physical force-stop
expansion and R8 physical remain pending where their APK is not installed.

### Current installation boundary

There is no new installation authorization in a historical successful install or
a general instruction to continue testing. Before a new attempt, obtain approval
for the exact package/build/SHA-256 of each app/test APK. The owner then keeps the
phone unlocked and may accept the normal system installer prompt if it appears.
If it again fails without a prompt, preserve the fixed error and stop that attempt;
identify the actual installer-policy cause through narrowly scoped diagnostics
before proposing any setting change. No specific setting is demonstrated as the
cause yet. Do not disable Play Protect, weaken device policy, or repeatedly retry.

The already installed, runner-owned exact bytes can be tested without installing
again under the approved synthetic scope. Report their historical APK hash, not
the latest repository HEAD, as the installed code identity. Current-head physical
acceptance requires matching new bytes and a separately authorized successful
installation. See `validation/2026-09-29-http-and-physical-review.md`.
