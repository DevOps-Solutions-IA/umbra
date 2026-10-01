# Execution02 — Access readiness

## Provenance and scope

Exact base: `1a0b040ff0fc3cd723bc519c279818616155b0b7`, tree
`17f79f114798ae6254648a5fb424711a59a6ade1`.
Isolated branch `codex/access-readiness-contract`; no accepted branch rewrite.
This receipt is updated with actual candidate/CI evidence below. Prior green CI
is not validation of these changes.

Only domain, storage, nonvisual opt-in test harness, tooling and documentation
change. MainActivity, ui.*, layouts, navigation and visual resources are unchanged.
`UI_SECURITY_CONTENT_API_V1` remains compatible; additive contract:
[`ACCESS_READINESS_V1`](../contracts/ACCESS_READINESS_V1.md).

## Findings and minimal corrections

1. **Core candidate, separately reproducible:** selected 60/120-second Vault limits
   relied on scheduled timer dispatch while AccessGate enforced only the original
   240-second ceiling. The change restricts the existing gate synchronously at
   password-open commit. It can only shorten authorization. A dedicated probe
   uses only exact-base APIs, advances a controlled monotonic clock, and reads
   protected data before timer dispatch. CI retains an expected RED only if that
   exact missing-rejection assertion ran once; runner/linkage/crash failures do
   not qualify. Candidate runs require this same probe to pass normally.
2. **UX semantic mismatch, not relaxed:** four minutes remain bounded by time
   already spent after Android authentication and by immediate background,
   emergency, invalidation or process loss. This execution exposes the distinction
   but does not change production UI or background policy.
3. **Observability gaps:** typed work/results distinguish create/change committed
   but locked, unlocked, generic password/tag failure, structurally corrupt
   metadata, missing/invalidated device key, stale callback and committed mutation
   whose cleanup failed. Public metadata inspection never repairs or deletes it.
4. **Concurrency review:** accepted operations carry their original lease;
   old external results and failed-before-acceptance operations use identity/CAS
   checks and cannot replace newer work. Snapshot is not authorization. New epoch
   creation does not revive an old grant; password processing does not renew auth.

## Local commands and evidence

Host: WSL/Linux, JDK 21, project-pinned Gradle 8.14.4, Android SDK 36.
Gradle bootstrap verified its pinned checksum. No dependency version was changed.

- `gradle -p android --no-daemon :app:testConnectedDebugUnitTest :app:testOfflineDebugUnitTest`:
  412 connected / 355 offline, zero failures/errors/skips at the initial complete run.
- Eight new JVM methods include 2,000 fixed-seed boundary combinations; these are
  test iterations, not 2,000 independently counted JUnit methods.
- `python3 -m unittest discover -s scripts/tests -p 'test_*.py'`: initial 253 PASS;
  final inventory is recorded with final validation, including RED receipt checks.
- `python3 scripts/repository_guard.py`, source-policy tooling and `git diff --check`
  passed during implementation; final results are reported separately below.
- Initial compilation failed because a new `lock(enum)` overload made an existing
  Java `submit(gate::lock)` ambiguous. Fixed additively with `lockWithCause`;
  existing test and method reference remain unchanged. This was a compile failure,
  not evidence of an authorization bypass.

## Android, R8 and boundaries

The password lab preserves its original nine cases and restart stages, adding
seventeen access cases (fifteen domain, one Activity lifecycle, one exact-deadline
probe) per flavor/configuration. General instrumentation adds the sixteen domain
and deadline cases; the Activity harness is opt-in only.

The lab uses real Vault/SQLite/Argon2/AndroidKeyStore with existing isolated lab
keys. It does not claim production hardware-authenticated unlocking. R8 remains
optimized and the runner requires an obfuscated AccessSession mapping. Ordinary
APK policy rejects the harness. No production Keystore fallback is introduced.

Local emulator acceleration is unavailable: `/dev/kvm` cannot be read/written by
this session; emulator acceleration check returned code 11. No permissions were
changed. Actions is the Android execution environment, with results below once
executed. Compilation alone does not establish an instrumentation PASS.

ActivityScenario lifecycle/recreation is distinct from process death. Existing
password restart stages perform actual force-stop. Product MainActivity wiring
is still Claude's responsibility, and no physical-phone execution is claimed.

## Compatibility / handoff

CONTRACT_CHANGE_REQUIRED: NO. The new coordinator is obtained via `vault.access()`.
Consumers read the exact record fields in ACCESS_READINESS_V1 and continue to
invoke authorized domain operations; a phase or deadline is not a grant.
No new network/Nearby activation, permission, dependency, Signal identity/ratchet,
wire-format change, or recovery path is introduced.

Next execution, not implemented here: Claude connects the additive contract to
its existing presentation and tests the combined product lifecycle, including
platform authentication and external-action callbacks. Do not infer successful
visual integration from this nonvisual domain/lab delivery.
