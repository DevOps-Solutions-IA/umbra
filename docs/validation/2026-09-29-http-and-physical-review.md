# HTTPS and physical-device review — 2026-09-29

Read-only causal review on technical HEAD
`278a572d8840f0989b21a738efe7823437cf1844`, based on
`ba75d329bedb2a26cd2e70371d2369e2fb3ba7a1`. This report records
historical artifacts inspected now, not new Android acceptance of this HEAD.
No product code, UI, phone configuration or installation was changed by this review.

## HTTPS: failure preserved, root cause unconfirmed

Focused run `36525583313`, debug job `109268229430`, source
`74253858ed1e29e1bb9c7b8ee7519180a269fad5`, integration checkout
`4b9f1041dd8100513c577acd190eb283bf470433`, tree
`4491bf429eb01b5a99ad2c922db4d60f0b28f23b`, failed in
`video-stop-race-1`. Artifact `11015490782` ZIP SHA-256:
`c9e6234d7b20a60621b17405f82d83a655ae35be352029470b6d1dd2d23c5604`.
Both endpoint logs report `videoBeforeStop=ACTIVE:none:ACTIVE`.
Endpoint A then fails in Android Http1xStream response-header reading,
`RelayClient.requestRawChecked` -> `sendAuthorized`, with unexpected EOF.
Endpoint B is subsequently stopped by cleanup. The log does not demonstrate a
UDP, decoder, TURN, or certificate failure. Server lifecycle counters were not
available for this historical failure; a stale HTTPS connection remains a
hypothesis, not a demonstrated cause or fixed defect.

Compared with successful focused run `36528074080`, source
`17564e20b82d1853e3d6aa2573efcd4943892836`, checkout
`40ce2e25c61a2dcc9d27353923ea1d61aacb213f`, shared tree
`8a25233692c54f3b9c5b597b57575c75a6c84e87`.
Downloaded debug artifact `11016072366` to ignored local review storage;
ZIP SHA-256 matches GitHub's digest:
`77bbf62ce5925ed5cb74a5fdc9f5bdf8337c6f416eaa30a9542e56538f0a1933`.
All nine scenario lifecycle receipts report five connections, zero dropped
records, zero incomplete responses and server alive at scope exit. Every receipt
also contains three transport-error flags on startup/idle connections despite
successful acceptance. In `video-stop-race-1`, the two ongoing relay connections
complete 76 and 75 responses and close without transport error. Two initial idle
connections reach the five-second keepalive callback and close about thirty
seconds later. A transport-error count alone therefore does not identify failure
of the active media/signaling exchange. No application payload, credential, full
SDP, or private device identifier was extracted into this report.

RelayClient inspection confirms one TLS-factory identity per client, bounded
request cancellation, immutable retry payloads with fresh admission challenges,
and connectivity invalidation on transport IOException. This review does not
justify disabling reuse, weakening TLS, arbitrary retry, or deadline increases.
Passing scenarios with diagnostics do not retroactively fix the old EOF.

## Separate AVD restoration failure

The `278a572` emergency debug artifact retained locally as
`278a572-ci/emergency-debug.zip` records no Wi-Fi IPv4 address or route during
24 readiness checks over 5.134 seconds following restoration. ConnectivityManager
reports no active default network. The IPv4 address listing cannot distinguish a
missing interface from an interface with no IPv4 address. Existing evidence does
not establish whether the service was disabled, still enabling, unassociated,
waiting for DHCP, or affected by another emulator condition.

AOSP WifiShellCommand revision `aac147fa40`, lines 987–990, invokes
`setWifiEnabled` and returns without waiting for network association:
https://android.googlesource.com/platform/packages/modules/Wifi/%2B/aac147fa40/service/java/com/android/server/wifi/WifiShellCommand.java
This is a platform reference, not identification of the exact runner image source.
The attempted Android-15 branch source fetch returned HTTP 503. Capturing bounded
read-only Wi-Fi status/service-state diagnostics on owned AVDs is appropriate;
command exit zero alone does not prove restored connectivity. No timeout change
or product correction is justified solely by this evidence.

## Physical evidence and exact remaining boundary

Historical offline debug attempt11 executed ten synthetic tests successfully on
Xiaomi 2606FRN72L / Android16 API36. App SHA-256:
`e7fac2a406333365ab3a47600c023d3429d34a70ea07e85073e795f6ee44c4ac`;
test APK SHA-256:
`483fcd3bfd5d12e33d534d615f1372f8f62aaa39865f9f5a455a258f5979a188`.
Its source worktree was dirty; these hashes identify actual tested bytes and must
not be relabelled as final-HEAD acceptance. See the preserved
`2026-09-28-file-video-playback-checkpoint.md`.
The measured silent-video lock closure was 35.52 ms with a subsequent 200 ms
observation window. TEE applies only to the synthetic non-authenticated fixture
key; production authenticated Vault/biometrics remain MANUAL_PENDING.

Physical R8 installation and the separately exported Claude preview remain
blocked by `INSTALL_FAILED_USER_RESTRICTED`. Owner reports no prompt. The preview
APK SHA-256 is
`e1660d651418377acaad3d5c1a15ebc732591e45247380d4ace7cbeb5842a15b`;
its package is `app.umbra.uipreview.offline.dev`. It was not installed/launched,
and is not the cumulative technical/UI integration.

Minimum legitimate next action: identify the precise new package/build/hash and
obtain fresh approval for each installation; with the owner available and phone
unlocked, accept the ordinary installer prompt if Android presents one. If no
prompt is presented and installation is rejected, preserve that attempt and
investigate its actual installer restriction before proposing a configuration
change. No particular phone setting is established as the cause, and no Play
Protect/device-policy bypass is justified. No install retry, device mutation,
sensor acquisition or phone command occurred during this review.

The physical runner permits testing identical already-owned installed bytes
without reinstalling; these results would remain tied to those historical APKs.
Four-format force-stop-after-consume, optimized R8, authenticated Vault and sensor/
audio-route tests require their distinct preconditions and remain unexecuted
physically here. Android-to-Android radio/media tests remain NEEDS_SECOND_PEER.
Claude's final UI integration remains outside this technical review.

## Additional current-checkpoint modulation failure (not HTTPS)

Local voice modulation run `36529584006`, R8 job `109280014782`, source
`278a572d8840f0989b21a738efe7823437cf1844`, checkout
`36e00c0c1feb11c8fe1cef8feb0a9c201f50948a`, FAILED before the first voice
scenario could start. Debug passed separately. Artifact `11015817931`, downloaded
and hashed during this review, ZIP SHA-256:
`edde83907328b10c198997cbbd2bddd733761dd9f2cdc4e7d7176e13591f4d63`.

The first failure is `wait_wifi_ipv4` in `run_voice_integration.py:271`:
91 observations over 20.005 seconds, no address/routable Wi-Fi on the first AVD.
Before data disable this AVD had a Wi-Fi address but lacked its policy-table route;
conditional initialization therefore toggled that Wi-Fi interface. The second AVD
had a healthy Wi-Fi route and preserved it. After the first toggle, its IpClient
records termination followed by a newly stopped client, without subsequent
provisioning start in the retained trace. OS connectivity validation failures
preceded the toggle; they do not establish the reason association failed to return.
Wi-Fi selection/service-state evidence is still necessary to distinguish causes.

HTTPS lifecycle contains only the host readiness request: one complete response,
server alive until cleanup. There are no endpoint media/DSP stage logs. This is an
AVD network initialization failure, not evidence of regression in modulation,
Opus, TURN, libsignal, R8 execution or an HTTPS EOF. It also differs from the
five-second premature startup assertion: this media preflight used its existing
full twenty-second limit. No rerun or product alteration was made by this review.

## Subsequent Wi-Fi service evidence and proposed deterministic selection

The later `6093d03` startup-debug artifact `11016249690` was reviewed locally
(`6093d03-ci/startup-debug.zip`), SHA-256:
`4a5ddb5b45bcdcbd81686dec4ecd17247a67e1c0d513d035b5d690363874aa6f`.
Its before-restoration receipt reports disabled Wi-Fi. After restoration, the
service reports enabled Wi-Fi but not connected. Sanitized dumpsys tokens contain
EnabledState, DisconnectedState and NETWORK_SELECTION_PERMANENTLY_DISABLED.
They also contain historical/other-manager L3ConnectedState entries: the flattened
safe summary does not associate each token with a particular current saved
network. Therefore enabled-but-disconnected is demonstrated; the exact reason or
network-specific permanent-disable cause is not established by that summary.

A narrowly scoped laboratory change can explicitly select the runner's known
virtual access point after enabling Wi-Fi, instead of assuming radio enablement
also selects its saved network. AOSP WifiShellCommand `aac147fa40` documents
`connect-network <ssid> open` and calls the service connection operation. Verify
support with the actual image's CLI help; the command outcome does not replace
existing native/default-network and address/policy-route checks. Use only the
known disposable AVD network and qemu/target guards. Never apply this to a phone,
an arbitrary saved network, or change validation policy, TLS, TURN-only, cellular
fallback, app connectivity consent, or readiness deadlines. This paragraph records
review of a proposed fix, not its execution or successful native acceptance.

## Explicit owned-AVD association correction (candidate after 6093d03)

The 6093d03 startup and emergency fixtures retain their original native 15-second
readiness window and still fail with the radio enabled but disconnected. This
establishes that `svc wifi enable` alone does not guarantee association. The
flattened `PERMANENTLY_DISABLED` tokens do not establish why the particular saved
AP was disabled; that causal detail remains unknown.

The host now explicitly selects the fixed synthetic `AndroidWifi` open AP after
radio restoration, and after conditional initialization of a missing media route.
A healthy route is preserved (including its IPv6 state). `select_owned_wifi`
requires an emulator serial plus `ro.kernel.qemu=1`, verifies installed CLI help,
and invokes `su 0 cmd wifi connect-network AndroidWifi open` only in that disposable
AOSP laboratory. API35 administration uses the same authorized root context as
existing laboratory firewall rules; no command is permitted on the phone.
The command's result is not acceptance: native default-network readiness,
Wi-Fi policy route, UID/DNS quiet window, explicit app consent and direct UDP/media
assertions remain required. No cellular fallback, route injection, global network
validation change or increased readiness budget was added.

Source reference: AOSP WifiShellCommand, revision aac147fa40, documents
`connect-network <ssid> open|owe|wpa2|wpa3|wep`; runtime help is checked rather than
assuming that every image implements it. Tool tests exercise unsupported CLI,
failed selection, physical-target rejection, healthy-route preservation and
retention of the original route failure when diagnostics themselves fail.
Local Python tools: 221 tests PASS (4.987 s); focused route tests 15 PASS after
using the API35 root context. Native success of this candidate is not yet claimed.
