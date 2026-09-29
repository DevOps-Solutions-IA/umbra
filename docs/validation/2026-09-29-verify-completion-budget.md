# Cumulative Verify completion budget — 2026-09-29

Historical run36627792335 on c16ffab822732fede639e88ab5ba3bcd2f865247,
checkout75735e5968e28f44faaf469ea8d246fa2fa737c8, Android job109610908011.
The API marks this job CANCELLED, not SUCCESS. No agent cancellation was issued.
Artifact11063095471 SHA256
`dcd49cf2a735da01cc093db7e4160779f457abb15c6651f1c5e7408dcd2ad7d2`.

## Measured cause

- Job started20:43:15Z.
- Build/lint/JVM finished20:51:15Z; compiled APK policy and HTTPS integration passed.
- Device step ran20:52:25Z–21:18:20Z and is explicitly SUCCESS in GitHub's API.
- Connected69/offline66 instrumentation tests passed. All15 native voice scenarios
  passed, followed by admitted and unadmitted RFCOMM in both flavors.
- Both emulator kills completed normally in LIFO order at21:18:17Z/21:18:18Z.
- Commit/APK hash step started21:18:20Z and was cancelled21:18:21Z.
- Failure-evidence upload succeeded, but normal APK upload was skipped.

The35-minute job allowance expired after all behavior checks, during completion.
This is not a hung codec, late security timeout, unexecuted test declared passed,
or proof that every intermittent historical failure is solved.

## Bounded correction

Set only Verify's cumulative Android **job** limit to40minutes: observed35m05s
before hashes plus less than5minutes for hash/upload/post-job completion and
bounded runner variation. No individual test, AVD readiness, radio barrier,
call/content TTL, privacy deadline or closure limit changes. No test is removed,
partitioned away or retried. The other jobs and all acceptance assertions remain.
A new final-HEAD execution must finish normally; the cancelled checkpoint remains
cancelled. This adjustment is grounded in timestamps rather than an attempt to
make a failing behavioral assertion pass.
