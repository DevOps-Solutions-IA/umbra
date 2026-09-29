# Technical handoff for Claude — acceptance receipt required

Technical line: `codex/security-content-completion`, draft
[PR16](https://github.com/DevOps-Solutions-IA/umbra/pull/16), against
`codex/emergency-lock`. Exact base:
`ba75d329bedb2a26cd2e70371d2369e2fb3ba7a1`.
Contracts reconciled against `6093d0333a5616f6ebcd379929ba0958705bd4eb`.
This is an inspected checkpoint, **not a final accepted SHA**. PR16's cumulative
receipt supplies the eventual published HEAD, integration checkout/parents, tree,
CI runs, artifacts/APK hashes and restore backup. Do not infer a final freeze from
historical green or begin integration on an unreviewed moving branch.

## Preserved Claude source and ownership

Reference bundle: `umbra-ui-security-integration-ca2a706.bundle`.
HEAD `ca2a706fd30c8194181588b19e72c69529f70335`;
SHA256 `a8cb0eaddee9610cbc1c8ad931cec55cf718a79d84e419db825ed94c298bc9c1`.
Verified prerequisites: `c394ea717e57d80454cff00c32927b39e13b2785` and
`52cd1e531b9de41ecd398ba53309b630ce1074f5`. Inspected separately, not merged.
Preserve any newer legitimate Claude work. Preserve Spanish simplification, C3,
palette, icons and navigation. Codex has not wired or redesigned those screens.

The isolated ca2a706 preview is not the technical/UI combination. Its authorized
installation attempt returned USER_RESTRICTED without an owner-visible prompt;
do not claim it was installed. Existing technical phone APK showing the old UI
is not evidence that the preserved Claude design was lost.

## Subsequent Claude execution instruction

After the technical final receipt is accepted, inspect both branches and preserve
uncommitted work. Create the authorized isolated integration branch from the
**exact accepted technical SHA**, then integrate the current Claude presentation
with explicit history reconciliation. Do not reset either source, modify main,
auto-merge, force-push or install an APK without the required device consent.

Use revision `UI_SECURITY_CONTENT_API_V1` of
[UI_API_CONTRACT](UI_API_CONTRACT.md) for methods, ownership, threads, limits,
errors and lifecycle, [UI_INTEGRATION_MATRIX](UI_INTEGRATION_MATRIX.md) for actions,
and [API_GAPS_UI_SECURITY](API_GAPS_UI_SECURITY.md) for acceptance constraints.
Required G1–G7 APIs are implemented; no UI-side cryptographic or authorization
workaround is needed. If a verified contract mismatch appears, report its exact
method and test; do not silently grant access in presentation.

1. Connect platform authentication plus password Vault flow; enrollment/change
   finish locked. UTF-8 arrays are caller-owned and cleared. No password String
   cache, automatic reset, legacy fallback or biometric password substitute.
2. Wire public admission status/authority/issued list/peer evidence. Pass original
   reviews into decision APIs. Use `approveAndInstallOwnAdmission` and
   `installRenewal`; remove UI-orchestrated split storage transactions. Display
   local request cancellation and offline revocation knowledge honestly.
3. Wire separate explicit online and Nearby actions. Unlock/admission/restart do
   not connect; CONNECTED is local permission, not reachability/delivery.
4. Emergency invokes `engine.emergencyLock()` without a password. Invalidate visual
   epochs and clear sensitive presentation immediately, then observe
   `engine.emergency().status()`. CLOSED confirms registered closure; INCOMPLETE
   denies reauthentication. Never wait for server ACK or reconnect to send END.
5. Apply `PrivateAndroidSurface` to Activity, dialogs, surfaces and sensitive fields
   before first content. Generic notifications accept no sensitive payload.
   Clipboard only uses `PrivateClipboard` with one-use ordinary-text consent.
6. Wire F01 PNG, F02 note, F03 file-video and F04 static PDF using the common
   restricted service. `reviewSend` -> format-specific prepare -> `send` uses the
   same Review. A received object's `reviewOpen` -> `open` consumes before
   presentation; retain one Session, not bytes/URI. Close abandoned preparations.
7. Use `RestrictedPlayback` with an explicit selected audio sink and protected
   `VideoOutput` for file-video. Silent clips may omit audio sink. No seek, repeat,
   loop, external player, automatic speaker fallback or camera activation.
   Connected-only `RestrictedRecording.record` needs explicit permission/input/
   consent; offline may import/receive/play without new capture/network permissions.
8. Keep F05 UMBRA_ONLY distinct from ONCE; repeated authorized sessions until expiry
   do not permit export/share/forward/print/copy. F06 distinguishes object expiry
   from active-session deadline. No view count implying human attention.
9. On pause/recreation/selector return/lock: reject stale callbacks, clear the last
   frame/field references, close owned resources and observe closure futures on a
   worker. Marshal graphics cleanup to UI safely without holding SQLite locks.
   Never restore an old Session, Prepared, review, playback or consent after restart.
10. Validate the combined debug and optimized app: real screens/lifecycle,
    accessibility labels/targets, secure windows/recents, external selectors,
    password/admission/connectivity/emergency, all four viewers, audio route/focus,
    export rejection, both flavors and physical safe cases. Do not replace these
    tests with a locked-screen launch or the existing isolated technical host.

## Evidence and limits carried into integration

[The updated twenty-case matrix](validation/2026-09-29-content-acceptance-audit.md)
links current test names to layers. Historical7425385 privacy debug/R8 executed
19connected/18offline tests, native clipboard, all four native formats, real Signal/
HTTPS and force-stop after persistent consumption. RFCOMM executed admitted and
unadmitted cases. Its focused debug still failed HTTPS EOF after active video;
lifecycle diagnostics are not a demonstrated fix. Final cumulative acceptance
requires its own exact-HEAD ten-workflow matrix; keep earlier red evidence.

Physical attempt11 historically passed ten synthetic offline debug cases. TEE
observed on a fixture key does not validate authenticated production Vault.
The later authorized2026-09-29 offline R8 laboratory passed10synthetic tests
and four-format consume/force-stop/restart. Microphone/acoustics/route changes and
authentication interaction remain separately classified; two physical endpoints require NEEDS_SECOND_PEER.
No promise of root/OS resistance, forensic erasure, prevented external recording,
or disappearance of the sender's original/gallery/backups follows from ONCE.

## Backup and freeze procedure

Use the latest verified full recoverable backup referenced in the final receipt,
not an older archive mentioned in historical reports. Preserve every prior bundle
and hash. Verify SHA256, bundle prerequisites, isolated restore HEAD and git fsck
before recovery; never reapply already committed patches. Dependency/authentication
caches and private device selection files are not part of the exported source.

Only after all executable technical acceptance is reconciled, bind these APIs to
the final receipt's SHA/tree. Subsequent integration must record that exact base.
No additional feature/API changes during Claude integration without a coordinated
correction and regressions. The remaining graphical integration is Claude's task;
this document does not claim it executed or that master acceptance is already green.

The additional object-TTL, encrypted Vault/WAL and historical-parser regressions
change coverage, not the public API. Do not copy fixture key creation, isolated
SQLite adapters or historical parser compilation into the application. Native
fixtures and production hardware authentication remain explicitly different layers.


## Cumulative receipt binding

Contract revision `UI_SECURITY_CONTENT_API_V1` remains unchanged by the final
laboratory coordination fixes. The exact technical HEAD, integration checkout,
tree, ten-workflow results and artifact hashes are bound by PR16's cumulative
receipt and its exported `claude-contracts/SOURCE.json`; a prior checkpoint's
native pass does not validate a later SHA. Native encrypted-Vault concurrency and
retention tests executed on34e0374 in both flavors/debug/R8; see
`validation/2026-09-29-nearby-quiescent-shutdown.md` for the separate RFCOMM fixture
failure and correction awaiting its own native acceptance. No UI source changed.
