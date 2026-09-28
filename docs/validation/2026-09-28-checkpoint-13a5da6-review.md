# Master v3 — checkpoint review, not completion

Reference HEAD `13a5da67d1987b30a87da7497c3a5143180f454a`, tree
`10b45d20a1ef9b8cadb1ba667ca2e9101c00b76a`; PR #16 remains draft.
Base `ba75d329bedb2a26cd2e70371d2369e2fb3ba7a1`. No reset or UI changes.

Verified through GitHub API on 2026-09-28:

| Workflow | Run | Result |
|---|---|---|
| Verify UMBRA | 36441708556 | SUCCESS |
| Vault password | 36441708547 | SUCCESS |
| Private startup | 36441708633 | SUCCESS |
| Admission | 36441708468 | SUCCESS |
| Emergency | 36441708667 | SUCCESS |
| Privacy adapters | 36441708437 | SUCCESS |
| Focused/RFCOMM | 36441708593 | SUCCESS |
| Modulation | 36441708477 | SUCCESS |
| Voice R8 | 36441708509 | SUCCESS |
| Video | 36441708481 | FAILURE |

Video checkout `cdb4a784255b79f40446e22e7e77bc9bed5637d4`.
Shard 0 passed debug/R8. Shard 1 failed:

- Debug trust-loss: both endpoints reported ACTIVE before stopping; A passed.
  B failed at `finishTransport -> pump -> Engine.receive -> DevicePolicy.member`
  with suspended device authority. The fixture performs another normal fetch/apply
  after deliberately blocking the peer. This is an expected domain rejection
  incorrectly treated as normal cleanup; correction/regression still pending here.
- R8 video-stop-race: B reports `Process crashed`; A fails sending with HTTPS EOF.
  The driver terminates on early fixture exit. Evidence does not establish why B
  crashed or whether A's EOF preceded cleanup. Do not call this fixed by a retry.
  Other scenarios, including credential-expiry, passed in this run.

Downloaded failed shards and instrumentation/driver logs are preserved locally in
`.run/security-content/video-13a5da6/`. GitHub archive digests:
- 10981072619 (debug shard 1): `31cde7ecefa000497ace198b8bf62f0d2d4da5e78ec929cd3d383dcdc17d9df1`
- 10980568288 (R8 shard 1): `b7fb6ece2a3fc82b23b693d8f9c3aef5ec2a968e502d53586ab023240d23fb00`

Backup rehashed successfully before editing:
`umbra-security-content-13a5da6.tar.gz`, SHA-256
`571db3b03b31b1c8b1afc71389b3959a858c06870b3bf109ad34f16db010dd20`.
Export remains in Windows Downloads/UMBRA_RESPALDOS_CODEX. It contains the
published checkpoint, not the subsequent working-tree changes.

## Next typed API delta

Admission now distinguishes authenticated expiry, not-before, own-device mismatch,
revocation, missing admission and absent administrative authority. Connectivity
checks the vault lease before exposing consent/edition/state/cleanup reasons.
No new permission, online provisioning, trust elevation or exception text parsing.
Two JVM regression methods run in both flavors cover these contracts. An initial
fixture assertion incorrectly required the production LockedException from its
Memory adapter (which throws SecurityException); corrected the fixture expectation
without changing production gates.

Local JDK 21.0.11 / Gradle 8.13 / AGP 8.13.2 / SDK 36, pinned dependencies:
`:app:testConnectedDebugUnitTest :app:testOfflineDebugUnitTest
:app:lintConnectedDebug :app:lintOfflineDebug` exited 0 (1m19s).
Log `.run/security-content/audio-initial-build.log`. This does not validate native
AAC playback/capture or the uncommitted later codec changes. Android and new CI
for this delta must be recorded separately. Master A/B/C remain incomplete.
