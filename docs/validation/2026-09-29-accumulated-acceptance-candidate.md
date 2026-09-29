# Accumulated acceptance candidate — 2026-09-29

PR16 remains draft against `codex/emergency-lock` (`ba75d329bedb2a26cd2e70371d2369e2fb3ba7a1`).
This document records the candidate following published6093d03 and local80c2a78;
it is not a final green acceptance claim. The external PR receipt binds its eventual
exact published HEAD, integration checkout/tree and runs without a self-referential
commit hash. The owner's checkout and Claude UI remain unchanged.

## New independent acceptance coverage

- Exact historical emergency-base Wire parser: compiled from the hash-pinned test
  resource, ordinary-text positive and current restricted Signal content rejected.
- A1/A2/B1 exact target: A2 is admitted, linked and verified but cannot receive A1's
  restricted object or substitute routing metadata/consent. Both JVM flavors passed
  the focused method in `restricted-exact-target-final.log` (exit0,20s); XML timestamps
  2026-09-29T06:53:09.813Z/06:53:12.085Z. This predates this document, not a new run.
- Concurrent ONCE opens now also target the real encrypted Android Vault/SQLite
  in one process, using two worker threads and fixture no-auth AndroidKeyStore keys.
  The single winner renders; consumed state must persist across password reopen.
  Compiled for both debug flavors; native execution belongs to candidate CI.
- Exact instrumentation inventories become69connected/66offline; privacy21/20.
  No old test is removed or skipped. Existing master20 matrix remains in
  `2026-09-29-content-acceptance-audit.md` and is refined by these regressions.

## Wi-Fi recovery

6093d03 startup and emergency debug/R8 prove the radio was enabled but remained
unassociated; enabling a radio is not selecting the AP. Tokens from a flattened
Wi-Fi diagnostic do not establish why a specific saved network was disabled.
The candidate explicitly selects only the disposable AVD's fixed synthetic AP.
Runtime CLI support and emulator identity are required; normal device/physical
commands are rejected. Startup preserves its original five-second settling period
before selection. Media observes enabled state and selects once within the existing
20-second route budget. Healthy associations are not cycled. No deadline increase,
cellular fallback, injected route or weakening of UDP/TURN/application gates.
This is a causal laboratory correction candidate, not yet demonstrated native green.

## Separate Verify cleanup failure

6093d03 Verify36531179926/android109292745845 completed68/65 instrumentation,
fifteen voice scenarios and both RFCOMM variants including unadmitted rejection.
It failed afterward during host emulator cleanup. At07:30:04 first AVD received
kill, at07:30:06 second AVD received kill; second process aborted with
`Netsim Wifi ... Stream removed (CANCELLED)` and `libc++abi: terminating`.
Artifact11018493739 SHA256:
`0a8760c6222a1be9c9ffefc67084a00f36508956f669b1b74a7f379fe86e3cec`.

The cleanup now releases later AVD clients before the first shared-service owner
(LIFO). Every abnormal process exit still fails the job and original failures
remain failures. A shell-boundary regression first failed on old FIFO ordering;
a separate regression proves SIGABRT-like exit134 still fails an otherwise successful
scenario. This ordering correction does not prove the exact upstream C++ abort
cause; that remains unconfirmed until native evidence. No suppressed emulator error,
ignored exit, timeout increase or retry loop was introduced.

## Environment interruption, retained independently

During the intervening restricted session, GitHub DNS resolution failed (-3),
TCP/UDP localhost binds failed EPERM, and Gradle failed before project compilation:
`FileLockContentionHandler` could not determine a usable wildcard IP. A bounded
90-second `test_local.sh` attempt returned124 with no captured output, so no backend
pass was attributed to it. Its empty log is preserved. The GitHub connector was
not connected. No alternate route was used to publish around these restrictions.
After the owner changed the session, GitHub PR16 read and localhost bind succeeded.
The full local build/tests were then restarted. No dependency/version/cache change
was made to mask the environment failure.

## Physical and graphical boundaries

No additional phone install was attempted. Previously owned offline debug bytes
are historical, not this candidate. R8/Claude-preview USER_RESTRICTED with no prompt
remains pending a legitimate owner-visible installation path and exact APK consent.
No UI integration, physical two-peer, authenticated physical Vault, ambient capture
or physical audio-route claim is made. Claude contract revision
`UI_SECURITY_CONTENT_API_V1` describes implemented APIs; final acceptance receipt
is still required before integrating the combined graphical app.

## Local cumulative execution after restored access

Activated `.venv`; Python3.13.12, JDK21.0.11, Gradle8.13, AGP8.13.2,
SDK36/build-tools35.0.0, pinned existing dependencies. `python scripts/build_android.py
--release`: exit0, BUILD SUCCESSFUL4m8s; connected269/offline212 JVM tests,
zero failures/errors/skips, debug/release compile and lint, merged manifest,
Signal JNI four-ABI and compiled APK policy PASS. Full real restricted TTL and
legacy/linked-target regressions are included. `bash scripts/test_local.sh` exit0:
205backend tests plus existing Java/core/source policy checks. Tools225 PASS
(5.207s). `repository_guard.py`493files PASS; `git diff --check` exit0.

Local APK SHA256 (unsigned release is compile/policy evidence, not installation):

| Variant | SHA256 |
|---|---|
| connected debug | 4e7f7f39a7a8cd06ce73a4de0a9b6dab06380dffb8139b35629b0fb7f0994774 |
| offline debug | e7fac2a406333365ab3a47600c023d3429d34a70ea07e85073e795f6ee44c4ac |
| connected release unsigned | 2f3804aeaec482c4e010b30ad30274c5cd5951e0d3627d845bef2f08f6a80892 |
| offline release unsigned | 2a1e2ce41d15fb1db9a39ba9f07959b8c14d3ed0bab50a355067bdeeea954934 |

No native/physical success is inferred from compilation. Offline remains without
INTERNET/ACCESS_NETWORK_STATE/microphone/camera/WebRTC. Logs are preserved under
`.run/security-content/resume-full-build.log`, `resume-full-local.log` and
`final-tools-candidate.log`. Their exact source tree is the following checkpoint;
publication and Actions acceptance are recorded separately.
