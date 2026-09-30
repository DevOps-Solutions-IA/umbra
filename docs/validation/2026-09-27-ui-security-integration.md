# UI + security integration — validation receipt (2026-09-27)

Branch `claude/ui-security-integration`. This receipt separates what was executed in the authoring
environment from what still requires GitHub Actions. **No CI run exists yet for this branch.** Green
results of either parent do not validate this merged tree.

## Base and merge

| Item | Value |
|---|---|
| Security base | `codex/private-startup-no-network` @ `52cd1e531b9de41ecd398ba53309b630ce1074f5` |
| Approved UI | `claude/android-ui-foundation` @ `c394ea717e57d80454cff00c32927b39e13b2785` |
| Integration merge | `ae5b18bbc183a731ce518b20539d11e95d6f007b` (normal merge commit, both histories kept) |
| Code validated below | `798f5d5` (merge + wiring + tests + review fixes) |

The merge had conflicts only in `MainActivity.java`, `scripts/run_android_instrumentation.py` and
`scripts/tests/test_android_execution.py`. They were resolved by hand; the merge message lists each
decision. Video partitions, workflows and laboratory scripts from `52cd1e5` are kept.

## Executed here (auxiliary; not Gradle/Android acceptance)

The environment has no Android SDK/Gradle, no Maven access and no KVM. The checks below are auxiliary.

| Check | Result |
|---|---|
| `javac` type check, UI + VoiceControls, both flavors (android-36.jar, WebRTC classes) | 0 errors |
| Whole-app compile with `java.*` resolved from android.jar (AGP-like), both flavors, libsignal-client **0.33** and BC **1.77** from the owner's Gradle cache (the build uses 0.102.3 / 1.86) | 0 errors in `ui/**` and in new tests; 17 main-source errors, all libsignal/BC version drift in `SignalStore`/`Engine`/`PasswordEnvelope` (same count as base code) |
| Instrumentation sources `UiScreensRenderTest`, `UiSecurityFlowTest` compiled the same way with JUnit/androidx.test stubs | 0 errors, both flavors |
| Presentation JVM tests (`Ui*Test` in `ui/model`, local JUnit shim) | 80 passed, 0 failed |
| `python -m unittest discover -s scripts/tests` | OK |
| `scripts/repository_guard.py`, `scripts/check_source_policy.py`, `git diff --check` | pass |
| Independent code review of the wiring | 1 major + 6 minor findings, all fixed in `798f5d5` |

**Not executed here:**

- `UiAdmissionFlowTest` (JVM, needs libsignal 0.102.3 natives).
- Gradle JVM suites, debug/release builds, lint.
- Instrumentation, emulator screenshots, APK/JNI/permission inspection.
- Private-startup, vault, admission, voice/video/modulation laboratories.

## Instrumentation inventory

The totals come from counting `@Test` methods, not from adding up branch totals. `androidTest` has 62
methods in both flavors: 40 security/laboratory tests, 19 `UiScreensRenderTest` and 3
`UiSecurityFlowTest`. `androidTestConnected` adds 2, giving 64 connected / 62 offline. No test is
parameterized, ignored or filtered.

## Required before acceptance (by SHA of the final published head)

- **Verify UMBRA:** Gradle connected/offline, debug/release, lint, JVM (real libsignal), instrumentation
  (64/62) with UI evidence, APK/JNI and offline manifest (no `INTERNET`, no `ACCESS_NETWORK_STATE`).
- **Security workflows:** password, admission and private startup. The private-startup lab now also
  observes a network return and a cold relaunch of the integrated locked Activity.
- **Media and focused workflows:** voice R8, video debug/R8 with the complete partitioned matrix,
  modulation debug/R8, focused/RFCOMM.
- **Record keeping:** record failures and flakes as observed. A passing retry does not explain a
  failure.

## Limits

- Production Keystore requires secure hardware, so the AVD cannot unlock through `MainActivity`
  without weakening it (not done). Unlocked flows are exercised through `VaultFlow`/`AdmissionFlow`
  over test-UID keys. The integrated Activity is exercised only while locked (lifecycle tests and the
  private-startup lab).
- Admin visibility, specific rejection reasons, expired-credential dates, credential listing, request
  cancellation, online provisioning before admission and linked-device admission are API gaps:
  see `docs/API_GAPS_UI_SECURITY.md`.
- Renders are synthetic-data images of the screen builders; they do not demonstrate authentication,
  cancellation or network behavior. Physical devices, Bluetooth radio, TEE/StrongBox and an
  independent security review remain open.
