# Physical R8 receipt and initial AVD route correction — 2026-09-29

Technical source3e04e55465bcc92426e12567fe9ebfcf40db2bdb. No UI modification.

## Authorized physical optimized laboratory

Owner approved both exact APK hashes before installation. Both installed without
changing any device protection. `run_physical_tests.py --optimized --restart-content`
returned0. Ten synthetic tests PASS, then PNG/AAC/PDF/AVC positively decoded,
consumed, force-stopped, relaunched and rejected on reopen/duplicate. This is death
**after commit**, not during commit. No capture of microphone/camera/network.
One physical API36 phone; fixture AndroidKeyStore reports TEE, with authentication
explicitly absent. Production authenticated Vault is still MANUAL_PENDING.

| Artifact | SHA256 |
|---|---|
| offline vaultLab APK | f7b655206c6b9cd815d8fd19263ad9e3623ce9140ff79228246a550941acc761 |
| offline vaultLab test APK | 0d8b39fae6617efb08879755f7f2fe6c52550d274f1f99ed8d14371782b704b0 |
| mapping | 3ac5a32f9d9ad251d7ba8ac443933f44aa830ec2268f36007eb8f8bc676b6ee2 |
| R8 configuration | bfdb898f96f83eac108fb917f6a34549bce9ec1cd5a1ebceb8c33224394691fc |
| receipt.json | 37fb9b00f9bdfebd35c37cb6f820f083fe09c228329fe5984cc5bb55e55cb547 |
| instrumentation.log | fae4807c52d45f046682f1f6f6fa84ad164493f8bf29fba5806d936408cd0102 |
| restart receipt | 9eea0778afc7b7f9341af93851c1e14b94b54e0de77bdf2296c220c2a21f91e8 |

Export: Windows Downloads/UMBRA_RESPALDOS_CODEX/3e04e55-ci-evidence/physical-r8.
Silent video lock request7748630267999ns, last frame7748615338845ns,
closure7748665804307ns:35.536308ms request-to-close followed by200ms observation.
R8 production adapters are exercised in an isolated applicationId/test harness;
this does not validate the exact production APK or Claude's future combined UI.

## Wi-Fi correction verified and distinct initial preparation failure

Run36628721483/R8job109612222743 SUCCESS on3e04e55. Artifact11062560557 SHA256
`fd6e7177e171f31d5b764972dd00252d77c5062c41806d60f36c9a8ef54ccffd`.
Its receipt confirms helpExit255, exact supported syntax, connect exit0, no
reported failure, and all connected/offline network/sensor/force-stop stages PASS.
This confirms the read-only help-status correction and restored explicit connection
in this native lane, not a green whole workflow.

The debug lane failed earlier, before app startup: initial Wi-Fi address10.0.2.16
existed, but policy-route lookup failed for the full unchanged20-second deadline.
The kernel connected route existed in the main table; netd Wi-Fi policy/default
routes did not. Wi-Fi reported L3ConnectedState. Artifact11062101142 SHA256
`1257c01a296ba379141dff48b6f71fea6174819716d262b196621d4b58c9cb9d`.
This is neither the later recovery barrier nor a failed UMBRA network grant.

Startup's initial setup still only enabled the already-enabled radio. It now reuses
`initialize_owned_wifi` and the same20-second readiness/association helper as media:
preserve healthy route; otherwise observe cleared old address before enable, select
once after enabled, then require a real matching route. No route injection, cellular
fallback, new timeout, disabled assertion or application-policy change. A host
regression verifies both initialization outcomes reach the actual route guard.
Native success of this additional initial-setup change awaits the next SHA's CI.
