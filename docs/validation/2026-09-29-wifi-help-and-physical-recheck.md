# Read-only CLI help status and physical recheck — 2026-09-29

Source checkpoint: c16ffab822732fede639e88ab5ba3bcd2f865247,
integration75735e5968e28f44faaf469ea8d246fa2fa737c8,
treec34843f6a6d3300ea0c603da242b62de3da78dde. Not final acceptance.

## CLI detection error

Startup36627792374/debug109609121314 failed before selecting a network:
`Installed AVD Wi-Fi CLI does not support explicit open AP selection`.
Artifact11060394950 SHA256
`297c82814a12d96cd2d26e0d06a641e0c41a0c32627e301d6fd0066383cc6bc5`.
The previous receipt stored only supported=false, so it cannot establish the
specific native help exit code. It does establish no connect command executed.

An independently reproduced helper defect: Android's
[BasicShellCommandHandler](https://android.googlesource.com/platform/frameworks/base/+/d5726c1916812ed3846092785abbd53352683502/core/java/android/os/BasicShellCommandHandler.java)
prints help and returns -1, exposed by ADB as255. Requiring zero incorrectly
rejects valid help text. The regression with exact supported Wi-Fi help syntax
and status255 fails before correction. The helper now accepts only0/255 for this
read-only query, still requires matching supported syntax and empty stderr, and
records the exit and bounded public syntax line. Actual network selection still
requires zero and no reported failure; radio/route/native proof remain mandatory.
No failed mutation or acceptance test is converted into success. The next native
receipt must determine whether this documented behavior explains this runner.

## Actual authorized phone run

The read-only recheck found the app absent while the previously owned test APK
remained. No reason for absence is inferred. The runner first refused installation
without new consent (zero install attempts). The owner explicitly approved one
installation of offline debug SHA256
`e7fac2a406333365ab3a47600c023d3429d34a70ea07e85073e795f6ee44c4ac`.
That installation returned Success. Ten synthetic tests then passed in63.588s,
with no microphone/camera/network capture. The app hash equals this checkpoint's
fresh local build; test APK remains historical ten-case hash
`483fcd3bfd5d12e33d534d615f1372f8f62aaa39865f9f5a455a258f5979a188`.
It does not include the new Vault race or four-format process-restart acceptance.

Android reports TEE only for the isolated non-authenticated fixture key. Physical
production Vault authentication remains MANUAL_PENDING. For active silent video,
lock request7288227693895ns, last frame7288210554433ns, closure7288255576356ns:
27.882461ms request-to-close, then200ms observation. This is one measured synthetic
case, not a general hardware timing guarantee or physical two-peer acceptance.

Sanitized evidence exported under Windows Downloads/UMBRA_RESPALDOS_CODEX/
c16ffab-ci-evidence/physical-authorized:
- receipt.json SHA25667109d42739938427c6afd7ec62fa7f113e1896432dd59816ac1b23d71d02b11
- instrumentation.log SHA2567698496f6eb3b56060c5277636409f9e42b8c9c9bd281d89ebe9f6e177437c9f

No data wipe, uninstall, global policy change or UI merge was performed.
