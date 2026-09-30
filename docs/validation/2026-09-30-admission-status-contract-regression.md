# Admission snapshot / pending-request correction — 2026-09-30 UTC

Follow-up on draft PR #18, branch `codex/post-acceptance-audit`.
Prior candidate `89bc6c90f5ac3461498e2bde9511109fa62ecbf8` passed its own
10 workflows / 27 jobs; those results do not validate this follow-up.
Frozen accepted base `e0024f091d29dc15c2d788b430c5ea11204e5060` / PR #16
and all four `UI_SECURITY_CONTENT_API_V1` documents remain unchanged.
Claude's PR #19 and presentation files were read only, never modified.

## Evidence and cause

PR #19 source `dcc81525dc404eaa21d9ab87405e23a18d45b71d`, Verify run
36653888664, Android job109695300612, artifact11071748518:
`UiAdmissionFlowTest.invalidStoredAdmissionIsReportedNotRepaired` fails along
`AdmissionCredential.decode -> AdmissionService.status -> AdmissionFlow.read`.
Artifact SHA-256: `b4fdddfceb485966c1cf30a05a777b0a5864fe64c39ba6f94b589ae6d7495466`.
Its integration checkout178bff1b8eca379a7df998f25a877758e12c7940 and source
share treeeafe320a50d10675bad54a46cac07458157f7ec9. This is evidence of a
JVM failure before that job's Android execution, not a physical-device failure.

Independent reproduction on unmodified89bc6c9 production code:
`AdmissionStatusContractTest`:13 tests,7 failures,6 passing controls.
Failures: corrupt credential, corrupt pending, corrupt pending beside a valid
credential, corrupt realm, missing realm with orphaned records, absent request,
and lock/unlock during pending read. The XML and complete command log are kept
in exported `admission-status-followup/red-results` and `red.log`.

`getAdmissionState()` already classifies bad stored data as INVALID. `status()`
then decoded it again to obtain expiration metadata and threw instead of
returning an INVALID snapshot. A valid credential could also conceal malformed
pending-renewal data until this second decode. Separately, `pendingRequest()`
contradicted the documented nullable lookup and lacked a final captured-lease
check around its read.

## Minimal domain correction

- INVALID state returns `Status(INVALID,null,null)`, without a second decode.
  Metadata decode errors also produce INVALID without publishing untrusted dates.
  Actual valid expiry values remain exact, including EXPIRED/REJECTED snapshots.
- Storage reads and authorization checks remain outside format-error handling.
  Locked/stale authorization and storage failures propagate. No credential,
  request, realm, key, identity or rejection is repaired, deleted or regenerated.
- `pendingRequest()` returns null only for an absent pending row. It checks its
  captured lease before and after reading. Malformed pending records still throw.
  This lookup alone is neither membership validation nor an authorization grant.
- Required-operation consumers use explicit INVALID rejection: cancellation,
  request authorization at capture and reuse, and request-result proof. The relay
  also rejects absence after its second snapshot, before entering transport.
  Wrong IDs, replacement requests and cancellation invalidate previously captured
  authorization; they never become successful null operations or NPEs.
- `getAdmissionState()`, `requireAdmission()`, membership/trust separation,
  cryptographic validation, connectivity consent and all UI signatures are intact.

This aligns implementation to the frozen contract, not a new contract version.
No UI workaround, broad exception swallowing, synthetic expiry or silent repair.

## Regression scope

13 shared JVM methods cover corruption/no mutation, realm loss, lock, read failure,
valid request/credential/rejection expirations, absent lookup, cancellation,
stale authorization and canceled-result proof. One connected-only regression
controls cancellation between authorization capture and relay snapshot and checks
zero transport entries. It uses real AdmissionService/RelayClient with a record
read rendezvous; it is not a test of real HTTPS packets.

One new Android `DeviceAdmissionTest` method exercises real SQLite reopening,
positive admission/expiry snapshots, cancellation, corruption preservation and
locked rejection. It uses isolated plaintext synthetic SQLite, not encrypted
production Vault or authenticated hardware Keystore. Existing encrypted Vault
coverage remains separate and unchanged. It is included in debug and R8 admission
laboratories and both full instrumentation variants.

Inventory increases: admission3->4, full Android connected69->70/offline66->67.
Runner receipt guards and their fixtures change to those exact counts; old counts,
empty results, crashes and skipped instrumentation still fail. The first tooling
run exposed two old-count fixtures; that failure is retained before correcting
the fixtures. No test exclusion or relaxed assertion was introduced.

## Commands and publication boundary

Environment: JDK21, SDK36/build-tools35.0.0, pinned Gradle8.14.4, Python3.13 venv.

```sh
# RED, before production edit; exit1, 13 tests / 7 failures
.umbra-tools/gradle-8.14.4/bin/gradle -p android --no-daemon \
  :app:testConnectedDebugUnitTest --tests app.umbra.admission.AdmissionStatusContractTest
# GREEN; exit0. Gradle applies this filter to the last task:
# connected full291; offline admission50 (not the full offline suite).
.umbra-tools/gradle-8.14.4/bin/gradle -p android --no-daemon \
  :app:testConnectedDebugUnitTest :app:testOfflineDebugUnitTest --tests 'app.umbra.admission.*'
.venv/bin/python -m unittest discover -s scripts/tests -p 'test_*.py' -v
.venv/bin/python scripts/build_android.py --release
```

Local completed validation (all exit0 with the stated JDK21 environment):
- Full build debug/release, lint, R8, manifest/APK policy and Signal JNI: PASS.
- Full JVM: connected291/offline233, zero failures/errors/skips.
- Python tooling:236 PASS; backend:207 PASS.
- Both Android debug instrumentation APKs compiled; device execution remains CI-only.

Preserved setup/inventory failures: the first core command used the shell default
Java/compiler selection and rejected `--release 21`; explicitly selecting the
project JDK21 fixed execution without code changes. The full build then exposed
that JVM inventory discovery used nonrecursive variant paths and omitted the new
connected subpackage. Recursive discovery now preserves package names for every
variant test, with a focused nested-package regression. Neither failure is hidden
or counted as a passing run. No dependency version changed.

Published follow-up SHA, its own CI runs/jobs,
Android counts and artifact hashes are bound in the subsequent PR #18 receipt
and exported evidence. This pre-publication report does not reuse89bc6c9 CI as
acceptance. Old failures remain historical. No ADB or physical tests were run;
phone/manual/second-peer/observability/Claude integration limits remain.
