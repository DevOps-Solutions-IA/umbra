# Restricted file-video native investigation — 2026-09-28

Master v4 remains open: this is diagnostic work, not completion of A/B/C.

## Reproduced failure

Published candidate `a86607d3cb9d761d67902069d31bc71e61627b16`, Privacy
adapters run 36510340185, integration checkout
`0a4777e2f7925a6e6f034cfebedc327df279a31b`: debug and optimized instrumentation
both report `Process crashed` on the first RestrictedVideoAndroidTest case,
after the preceding privacy/content/PDF cases. Artifacts 11008549041 and
11008828503 are retained locally. The original reports do not identify a
native stack or preparation phase. Root cause is NOT YET DEMONSTRATED.

## Independent correction and diagnostics

Decoder output is raw video; encoded extractor/encoder tracks remain AVC.
Validate these formats separately, with a JVM rejection regression. This
source-level correction is not claimed to explain the process crash.

Add fixed synthetic encode/mux/prepare/decode phase receipts and collect only
sanitized owned crash-buffer metadata on a failed disposable-AVD suite.
No raw crash log, addresses, PCM, frames or exception messages are persisted.
The physical runner does not collect this buffer. Failure remains failure.

Executed locally on the candidate worktree: the raw-format JVM tests for both
flavors, both debug instrumentation APK builds and lint completed successfully
(Gradle exit 0, 54 seconds). Four diagnostic sanitizer tests and three exact
privacy-report tests passed. Native video acceptance still FAILED on the prior
published SHA and is pending for this correction; no physical execution.

Partial playback and physical R8 runner work remains separately uncommitted;
it is not part of the diagnostic commit or accepted video functionality.
