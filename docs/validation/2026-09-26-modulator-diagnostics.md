# Modulator CI continuation — 2026-09-26

Status: PARTIAL. No claim of complete green CI.

Published baseline: `2d9ac6e06d145971b58c7a6f3f64a3d00d03f08a`.
Integration checkout: `6c680b71340e36beb51a078bdd90e53cb3c3ffba`.
Common tree: `1da7f2420dac02eef556a27a338c9f47ffdb76c6`.
PR #10 remains open/draft into `codex/turn-video-core`, rechecked with the
GitHub connector. Local git metadata remains at that baseline.

## Observed results

- Modulation 36207520861: both matrices SUCCESS; eight real synthetic two-AVD
  flows, ten stages each. Receipts downloaded and checkout checked.
- Voice R8 36207520899: SUCCESS; receipts downloaded and checkout checked.
- Video 36207520910: R8 SUCCESS. Debug failed camera-permission-revoked; the
  other 30 cases passed. Both matrices passed wrong-fingerprint after the tick
  guard. Current job logs were read through the GitHub connector.
- Verify 36207520866: guard, relay/core and container SUCCESS; Android FAIL at
  connected RFCOMM enrollment handshake. The preceding 15 voice cases passed.
  Listener timed out before the host comparison barrier; dialer had only
  announced listening-or-connecting. Offline RFCOMM afterwards was NOT EXECUTED.

The wrong-fingerprint diagnostic race from 36204852534 was corrected, with three
focused local R8 repetitions and both later video matrices passing that case.
That is not a fix for the separate RFCOMM or camera-permission-revoked failures.
Their causes remain UNKNOWN; no hypothesis is presented as a verified fix.

GitHub-reported video artifact SHA-256 (not locally verified ZIP bytes):

- debug: `135deebe5d98f10eb7d7ea8a1c2cc46394c062b5b961e2c9c1d5ae7563eb1403`
- R8: `59eef6adbe1ef876164a0b70f3584a81f39d2d9f5ca4db4b62d73626a72b11d1`

## Diagnostic changes and local checks

Verify previously wrote commit.txt only after successful device tests; the failed
artifact actually lacks it. Record checkout before building/testing and require
the evidence upload to find files. Test failures remain fatal.

The video matrix redirects details to artifacts. Print bounded source locations
on failure: Python script/line and UMBRA Java file/line only, never exception
messages, commands, SDP, credentials or PCM. New regression: AttributeError before
implementation, exit 1; full tools suite after implementation: 142 tests, exit 0.
The existing regression still requires overall failure when any case fails and
execution of later independent cases. No threshold, timeout or case removed.

The RFCOMM fixture previously discarded transport status. Emit only a fixed
allowlist of stage codes in instrumentation; unknown text becomes UNCLASSIFIED.
Direct JDK21 javac compilation against cached Android36/main/test classes exits
0. This is compilation of the changed fixture, not an APK build or device test.
Repository guard and git diff --check also passed before the documentation update.

## Environment and remaining work

The resumed session restricts shell networking and marks local .git read-only.
gh cannot connect to api.github.com; downloading the connector's artifact file
fails DNS resolution too. The GitHub connector can read job summaries and logs.
The camera failure's detailed artifact has not been inspected. KVM is not
writable in this context; no new local AVD execution is claimed.

No permission escalation, Keystore fallback, paid runner or production change.
These diagnostic-only changes need their own CI after publication. Previous
passes do not validate them. Full acceptance remains incomplete until both
outstanding failures are investigated and all final suites actually pass.

Publication attempt: the GitHub create_tree tool rejected the write with
"MCP tool call requires approval, but approval policy is never". No new remote
tree/commit/ref update is claimed. The seven diagnostic/documentation files are
local changes based on 2d9ac6e, with no local git metadata mutation. A patch is
saved at /tmp/umbra-modulator-diagnostics.patch for review/continuation. Resume
by inspecting the camera case artifact, executing the strengthened diagnostics
on both variants, publishing only to this PR branch and checking all final CI.
