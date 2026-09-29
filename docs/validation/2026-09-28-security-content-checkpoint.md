# Security/content master v3 — checkpoint, NOT acceptance

## Provenance (VERIFIED locally)

Branch: `codex/security-content-completion`. Exact base:
`ba75d329bedb2a26cd2e70371d2369e2fb3ba7a1` (emergency implementation reused).
Independent worktree: `/tmp/umbra-security-content-completion`; independent Git
metadata: `/tmp/umbra-security-content-repository/.git`. Original worktrees and
untracked Claude outputs were preserved. No UI, manifest, dependency, main or
previous delivery branch was changed.

Package `SHA256SUMS.txt`: four entries passed. Master SHA-256:
`1b869c270189f43ed5b4fc9fe2b9a754eb4d436728602cfab0e8565b66b269a8`.
Claude bundle SHA-256:
`a8cb0eaddee9610cbc1c8ad931cec55cf718a79d84e419db825ed94c298bc9c1`.
`git bundle verify` passed with prerequisites c394ea717e57d80454cff00c32927b39e13b2785
and 52cd1e531b9de41ecd398ba53309b630ce1074f5 present. Bundle HEAD
ca2a706fd30c8194181588b19e72c69529f70335 inspected through a review ref only.
No merge/cherry-pick. Reviewed Claude's API gaps and 798f5d transaction correction.

## Implemented, limited coverage

AdmissionService: atomic own approval/install and renewal-pair import; binding of
revocation to the installed credential and replacement device/Signal keys; local
pending-request cancellation; authority capability snapshot; separate request and
credential expiry snapshot; bounded authority-only issued credential metadata.
These are domain APIs, not UI integration or proof of remote membership freshness.

Reproduced existing bug: `Review.used` was set before a Records transaction
committed. A failed commit rolled back durable decisions but permanently consumed
the in-memory review. The same regression against the base fails with
`SecurityException: Admission unavailable`; corrected implementation succeeds.
Replay remains rejected by transactional decision/request nonce records and the
captured vault lease. No authentication, signature or replay check was removed.

Added eight tests for rollback, stale lease, wrong renewal revocation, malformed
pair, atomic retry, cancellation/late approval and read-only snapshots. Memory
transaction fault injection is not Android SQLite/process-death evidence.

## Executions

- Python 3.13.12 from activated inherited venv; no dependency installs.
- JDK explicitly `/usr/lib/jvm/java-21-openjdk-amd64` (21.0.11). Shell default
  is Java 25.0.3 and was NOT used for the Java compilation/test commands.
- Gradle 8.13; Android SDK 36 / build-tools 35.0.0 preflight exit 0.
- Copied existing pinned WebRTC AAR: SHA-256
  `25f2abebc99e2e109cff83a428080408843fda51a9cdadb5c081d694c92b7620`.
- Gradle offline connected/offline JVM attempt exit 1 BEFORE compilation:
  `FileLockContentionHandler` / `Could not determine a usable wildcard IP`.
  No sandbox exception or alternative network transport was attempted.
- Supplemental javac/JUnit: modified AdmissionService and new tests compiled
  with JDK21 against base connected classes plus existing JUnit4.13.2,
  libsignal-client0.102.3 (real JNI), BC1.86, JSON20250517, Kotlin2.2.20.
  Final 30 tests passed (exit 0): 22 inherited + 8 new. This is NOT a Gradle
  build, full current-source build, offline variant test, R8 or APK acceptance.
  Initial hand-built classpath omitted Kotlin: 26/27 failed to initialize;
  corrected classpath results supersede that harness error, not product failures.
- Regression against original AdmissionService: 1 test, 1 failure, exit 1.
- `python -m unittest discover -s scripts/tests -p 'test_*.py'`: 180 passed, exit 0.
- `python scripts/repository_guard.py`: exit 0 (392 files at execution).
- `git diff --check`: exit 0.
- `bash scripts/test_local.sh`: interrupted (130), no pass claimed. Backend
  attempt with proper PYTHONPATH collected 204 and stalled at first HTTP test.
  A separate invocation without PYTHONPATH failed collection (2); it is not a
  product failure. Full relay/core verification remains BLOCKED/NOT COMPLETED.

## Environment blocks and exact continuation

`gh pr view 15 --repo DevOps-Solutions-IA/umbra --json headRefOid,state` fails
connecting to api.github.com. Network is restricted and approval policy is never.
Remote HEAD, new push/draft PR and final CI are NOT verified or executed.
`/dev/kvm` absent: no local AVD, sensor, codec, RFCOMM or Android SQLite acceptance.
No historical green has been attributed to this work.

Resume in this worktree from the checkpoint commit. Keep its .run evidence and
export bundle. First unblock full Gradle/relay verification; repeat all existing
suites before accumulating dependent security/media changes. Then complete the
remaining A APIs (typed safe errors and provenance-aware peer admission), privacy
adapters and all B/C implementation. Existing generic attachments are 256 KiB;
Engine sendFile/exportData are NOT restricted-content APIs and must not be used
to claim one-use access. DocumentIO already registers emergency cancellation and
bounded input: reuse it rather than rebuilding the emergency coordinator.

A is PARTIAL; B and C are NOT IMPLEMENTED in this checkpoint. All master P01–P24
and acceptance cases 1–20 remain mandatory; none is waived or marked accepted.
New content transfer, per-object keys, persistent consume/tombstones, sanitized
image/voice/video/PDF paths, real codecs, debug/R8 labs and final four Claude
contract documents remain outstanding. Do not call this the final delivery or
ask Claude to integrate an incomplete restricted-content API.
