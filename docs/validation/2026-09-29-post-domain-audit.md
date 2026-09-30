# Post-acceptance domain review — 2026-09-29

## Scope and provenance

VERIFIED: reviewed worktree `/tmp/umbra-post-acceptance-audit`, branch
`codex/post-acceptance-audit`, HEAD
`e0024f091d29dc15c2d788b430c5ea11204e5060`, remote
`https://github.com/DevOps-Solutions-IA/umbra.git`. Startup tree was clean.
The earlier branch/SHA named by CODEX_HANDOFF and ROADMAP are historical context;
the real checked-out revision above governs this review.

Reviewed restricted-content domain, Engine ingress/outbox, Records/Vault
transaction and authorization boundaries, AccessGate, VaultSessionRegistry,
existing JVM tests and relevant native-test source. Read AGENTS, SECURITY,
CODEX_HANDOFF, ROADMAP_CODEX, TESTING_WITHOUT_PHONES, RELEASE_CHECKLIST and
UI_API_CONTRACT. No UI changes, branch switch, commit, push, ADB, physical
operation or network transmission was performed by this reviewer. The production
correction below was authorized by the root reviewer after the RED reproduction.

## D-01: repeatable reopen after unconfirmed cleanup

Severity: **P2 — confirmed domain lifecycle/resource-bound defect**, triggered
by failed or still-pending native cleanup and a later local open. No external
attacker trigger, plaintext exfiltration or actual native codec failure was
demonstrated. Status: corrected in the working tree; focused JVM GREEN below,
new native acceptance and final root review remain separate.

VERIFIED by controlled JVM reproduction: `RestrictedContentService.open` (lines 185–200 at
the frozen base) denies consumed objects and unexpired `busyUntil`, but does not
consult completion of the previous session's cleanup. `Session.close`
(lines 263–274) removes the session from `active` and completes `closed`
exceptionally if a native release failed. The exceptional closure retains
emergency registration when available; registering a resource does not itself
request emergency lock. After the persisted busy deadline, an UMBRA_ONLY object
is eligible for another presentation even though prior native closure failed.

The contract explicitly distinguishes playback outcome from closure and says
not to offer a new overlapping session after native cleanup failure. SECURITY
also says failed native cleanup invalidates access rather than silently opening
another preparation. This finding concerns domain enforcement of that failure
boundary, not ONCE replay, ciphertext disclosure, or a demonstrated Android
codec exploit. A caller that explicitly requests emergency lock or obeys the
closure failure can avoid the second open; no UI behavior is assumed here.

Added `PostAcceptanceRestrictedClosureTest` with four bounded cases:

- Confirmed closure followed by the actual two-second busy deadline permits a
  fresh repeatable session (positive control).
- Synthetic internal `cleanupFailed()` produces exceptional closure; after the
  actual deadline, another presentation is expected to be denied.
- A synthetic resource release blocked by a latch must deny reopening the same
  object through a new Engine sharing the same storage scope after the busy deadline.
- Four pending/queued resource releases must continue occupying the four slots
  when a new Engine sharing Records tries a fifth object after their deadlines.

The tests use real Signal/JCA and the existing synchronized memory Records
fixture. Reflection is confined to test-only construction/fault injection, as in
the existing domain suite. They neither decode a real PNG nor reproduce an actual
native release failure.

### Reproduction receipts

The baseline executions used JDK21, cached Gradle8.13 and SDK
`/mnt/c/Android/sdk-linux`. Command from the worktree root:

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:$PATH ANDROID_HOME=/mnt/c/Android/sdk-linux GRADLE_USER_HOME=/tmp/umbra-security-gradle .umbra-tools/gradle-8.13/bin/gradle -p android --no-daemon --max-workers=2 :app:testConnectedDebugUnitTest --tests app.umbra.PostAcceptanceRestrictedClosureTest
```

Initial execution: exit1, four failures. The three rejection assertions failed
because another session was granted; the positive control timed out at the
whole-method ten-second bound while waiting for its persisted deadline. That
run alone was not accepted as a controlled reproduction. Original log
`.run/post-audit/closure-red.log`; XML preserved in
`.run/post-audit/closure-red-initial/`. Exact cause of the initial slow fixture
is NOT_VERIFIED.

Corrected fixture places synthetic identity construction in JUnit `@Before`,
outside the presentation-operation timeout. Closure3s, actual deadline wait3s,
resource-release latch8s and method10/15s bounds remain unchanged. Re-execution
exited1: four tests, positive passed and three expected security-rejection
assertions failed. Log `.run/post-audit/closure-red-corrected.log`; XML preserved
in `.run/post-audit/closure-red-corrected/`. Measured fixture setup was958ms then
10/14/10ms. Executions are on2026-09-30 UTC,2026-09-29 America/Bogota.

### Correction and internal contract impact

Implemented after root review: acquire one of four process-local resource
reservations inside the existing open transaction, including uniqueness by
object id. Release only after successful resource cleanup. Failed/pending native
closure continues occupying its slot even when `busyUntil` expires. Pre-session
failures release their reservation; failures after Session creation delegate
release to that Session's successful asynchronous cleanup. Reservations retain
object ids only, never keys/plaintext/native objects/Session references.

CONTRACT_CHANGE_REQUIRED for technical storage implementers:
`Records.restrictedResourceScope()` is an additive internal resource-accounting
identity, not another authorization grant. Default scope is the existing emergency
coordinator, otherwise the Records object. Alternate Records wrappers must share
a stable token when they represent the same storage lifetime. Production Vault
uses a path token retained by VaultSessionRegistry across handles and gate
replacement. UI_SECURITY_CONTENT_API_V1 UI signatures and frozen exported
contract files are unchanged by this patch; approval of the internal additive
storage contract remains explicit.

Accounting survives same-process Engine/Records/Vault-owner replacement for a
shared scope, including Vault's same-path/new-gate replacement. Failed cleanup
retains a bounded slot until process end; it does not silently renew access.
Process death removes native resources and process-local reservations; persistent
ONCE consumption and existing busy deadlines retain their previous semantics.
The new registry test checks token stability across gate/claim replacement and
separation of unrelated database paths. The pending-cleanup test also checks two
distinct Records wrappers sharing a scope; this is not Android instrumentation.

Initial GREEN used the same environment with Gradle8.14.4 after the root's
separate build-tool correction, selecting PostAcceptanceRestrictedClosureTest,
VaultSessionRegistryTest, RestrictedContentTest and DocumentPagesTest. Exit0,
BUILD SUCCESSFUL in2m22s:34 tests,0 failures,0 skipped; log `.run/post-audit/domain-green.log`, XML preserved
in `.run/post-audit/domain-green-initial/`. This includes the existing
`failedConsumeCommitGrantsNoSessionAndLeavesObjectAvailable` regression: failed
state write returns no session, does not consume, and subsequent retry succeeds.

After review, Vault captures the final scope under the same monitor as its
emergency registration to prevent concurrent close from nulling its claim.
Negative assertions now require exact BUSY/CAPACITY codes, preventing an unrelated
authorization failure from satisfying them. Added a fifth regression: five
AEAD failures before Session creation, then restored ciphertext and positive
open, proving those failed attempts do not leak reservations. Final focused
execution used Gradle8.14.4 and the same environment, selecting
PostAcceptanceRestrictedClosureTest, VaultSessionRegistryTest and
DocumentPagesTest. Exit0, BUILD SUCCESSFUL in17s:13 tests,0 failures,0 skipped
(5 new closure/rollback,3 registry,5 document-policy). Log
`.run/post-audit/domain-green-final.log`; XML preserved in
`.run/post-audit/domain-green-final/`. The earlier22 RestrictedContentTest cases
were not rerun in this last focused invocation; their own preceding receipt
remains separate. New native/Android acceptance remains pending.

## Verified source properties, not new runtime acceptance

- ONCE sets `consumed`, deletes the encrypted object and writes `busyUntil`
  before returning a Session, in one Records transaction. A failed transaction
  closes the pending session instead of returning plaintext access.
- Vault serializes transactions and uses `gate.commit` for the final
  authorization/SQLite commit boundary. Nested transaction failures mark the
  outer operation rollback-only. This source review is not a fresh hardware or
  SQLite durability test.
- Reviewed preparation is bound to owner, exact original Review, recipient,
  unlock epoch and a 60-second monotonic lifetime. Session checks revalidate
  recipient admission/trust and original unlock lease. Active-session lifetime
  uses both monotonic time and wall time.
- Engine authenticates ingress through real Signal before constructing the
  restricted ingress capability. Outer/inner recipient, sender and expiry are
  checked; policy descriptor is also the object AEAD context.
- Received restricted objects bypass ordinary message history. Public Engine
  access rejects restricted buckets. Restricted outbox receipts remove their
  acknowledged immutable delivery; a duplicate receive uses the saved digest
  and receipt instead of opening/consuming again.
- Sender delivery targets one device. The existing linked-A2 regression has
  independently admitted/verified A2 and rejects retargeting original
  preparation or A1 ciphertext; source presence is not an execution receipt.

## Explicit limits retained

VERIFIED in contract/source: four pending Prepared objects are bounded per
Engine, four busy session deadlines are counted in persistent Records, 128
objects and 4096 tombstones reject additional ingress at capacity. Tombstones
are deliberately not evicted early; eventual capacity exhaustion is documented,
not silently treated as a new security defect.

VERIFIED in contract: ONCE consumption is not proof that a human saw/listened
to content; a crash after commit may lose the opening. Persisted epoch expiry
does not promise resistance to privileged clock/snapshot rollback across
restart. The native encrypted-Vault concurrency and retention tests exist,
but this reviewer did not rerun them or infer present acceptance from their
historical results.

NOT_VERIFIED: production authenticated Keystore behavior, native decoder release
faults, Android process-death durability, physical transports, UI integration,
or CI for the newly added tests. This is a bounded code review and JVM
regression proposal, not an independent cryptographic certification.
