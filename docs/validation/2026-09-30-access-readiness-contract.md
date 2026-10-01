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

## Executed candidate receipt: c9ce6e1

Published candidate: `c9ce6e1f90a88d38ee7b2ab128cbd3ea675552d9`.
PR #21 OPEN/DRAFT against `codex/product-reality-audit`; no auto-merge.
Actions integration checkout `83460158fb08f5b6a08356b8c826f0fb79b50596`, tree
`3ac84d80119cda15b454d7a18cdab2a251f3e425`, identical to candidate tree.

**Core deadline bug reproduced RED and corrected:** password workflow
[36799170274](https://github.com/DevOps-Solutions-IA/umbra/actions/runs/36799170274)
completed SUCCESS in both debug and optimized matrices. The isolated unchanged
base accepted the protected read at the selected 60-second deadline, before its
real timer could run. Exact expected assertion recorded as EXPECTED_HISTORICAL_RED;
not a linkage/runner failure. Base APK SHA-256
`98e6c232ad7d706e9781c9239dc55809912b0c03311c668c2e139c5851115493`;
RED log SHA-256 `4643dec6a7409e142eba8bd282e34a00ed03ea15ca8e0ac12a6cf3a267debf18`.
The candidate probe passed independently, as did all 17 access cases and the
original 9 password cases in each of connected/offline × debug/R8. Restart,
interrupted migration and debug reinstall stages also passed their existing
three-case post-restart checks; these do not represent physical hardware auth.

Downloaded artifact bytes were independently hashed:

| Artifact | ID | ZIP SHA-256 |
|---|---|---|
| Password debug | 11134939397 | e984a11e2b1bab55171c7648a2255edb686d6f83afb07c0387edafbf940e2bca |
| Password R8 | 11134814186 | f7c76403dba4d20321f521abe3200df78a4c3d574fffdc4bcfbbc157d55afe5d |

Final local source run: 413 connected / 355 offline JVM tests, zero failures,
errors or skips. The earlier 412 count belongs to an intermediate compilation,
not the final inventory. Tooling: 253 PASS. Source policy: 13 PASS. Full-history
repository guard and diff checks are recorded separately; source guard passed.

Local Gradle commands (JDK 21 / Gradle 8.14.4 / SDK 36), exit 0:

```
gradle -p android --no-daemon -PumbraAccessLab=true -PumbraVaultLab=true \
 :app:testConnectedDebugUnitTest :app:testOfflineDebugUnitTest \
 :app:assembleConnectedVaultLab :app:assembleOfflineVaultLab \
 :app:assembleConnectedVaultLabAndroidTest :app:assembleOfflineVaultLabAndroidTest
gradle -p android --no-daemon :app:assembleConnectedDebug :app:assembleOfflineDebug \
 :app:assembleConnectedRelease :app:assembleOfflineRelease \
 :app:lintConnectedDebug :app:lintOfflineDebug
python3 scripts/check_apk_policy.py --sdk /mnt/c/Android/sdk-linux --include-release
```

Both ordinary flavors/debug+release passed final APK policy, including no lab DEX,
backup/TLS/component policy and offline permission isolation. New access code is
obfuscated as `b.a` in both laboratory mappings; mapping hashes:
connected `4b715b0ae332ef19f708238389777ecbdbdf6af980991cf7162feda436d94131`,
offline `97d45014ba51b93b43c895640545d569dd6b3806e0a6b0833b7dec48db818b4b`.
Warnings about pre-existing deprecated APIs and libsignal stripping were retained;
no suppression or global keep rule was added.

## Preserved unrelated UI-fixture RED and bounded correction

[UI run 36799170306](https://github.com/DevOps-Solutions-IA/umbra/actions/runs/36799170306)
failed debug connected at `UiContentIntegrationTest:159`, second positive render
of a one-second UMBRA_ONLY session; optimized matrix passed. Trace reports
`RestrictedContentService.Session.timeCheck` EXPIRED. It uses SqliteDeviceRecords,
not the modified Vault access coordinator. The exact historical trigger remains
**HISTORICAL_UNCONFIRMED**: the branch combines monotonic expiry, whole-second wall
expiry, rollback and denied-session state, without timing evidence in that run.

Artifact 11134303948 is preserved, ZIP SHA-256
`7e431fb4e5f91c184fa602dc18194e0c6e2574730abdad35f77e0ff56b0acd68`.
Known fixture deficiency: `deadline=floor(wallMillis/1000)+1` permits only roughly
1..1000 ms before wall expiry, but the test demanded successful rendering from an
arbitrary starting phase. The test-only correction waits once for the next whole
second (bounded 1500 ms; jumps/no boundary fail), emits safe phase/elapsed timing,
and keeps the **one-second** policy, all positive/expiry/export assertions and
existing waits. No retries, larger session, production content change or UI change.
A later pass establishes execution of the controlled fixture, not proof of the
historical exact trigger. That distinction must remain in the final PR receipt.

The follow-up commit contains only this fixture control and documentation; it
requires its own CI. Final run IDs/statuses are attached to PR #21 after they
finish, rather than attributing this candidate's green to a later SHA.
