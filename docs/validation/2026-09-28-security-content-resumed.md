# Master v3 resumed — progress, not acceptance

## Recovery actually executed

Original archive preserved: `/tmp/umbra-security-content-c56b2a3.tar.gz`, SHA-256
`86be1bb8f33e06f9b543db5ffb6b98190b161e7888bf1e29ec5837f23d76d96c`.
All internal hashes checked. Restored into `/tmp/umbra-restore-check.ucdW5C/repository`
with prerequisite ba75d329bedb2a26cd2e70371d2369e2fb3ba7a1, bundle verified, fetched,
checked out and `git fsck --no-reflogs` passed. Recovered HEAD is
c56b2a3c6e87684f7efc4ace9b0d8c9d2bd01e3f, tree
81c6af341eac0fce6b352b440490eb36d7752f32.

Copied without overwriting to Windows:
`C:\Users\Usuario\Downloads\UMBRA_RESPALDOS_CODEX\umbra-security-content-c56b2a3.tar.gz`.
Destination hash matches. Original worktree retained; untracked `.venv` and
`.umbra-tools` are tool symlinks, not unpublished source. No authentication cache
or credentials included in archive. Original archive remains incremental.

## Distinct environment checks

- `gh pr view 15 --repo DevOps-Solutions-IA/umbra --json state,headRefOid,headRefName`:
  exit 0; OPEN, ba75d329. `gh api repos/DevOps-Solutions-IA/umbra` confirmed push
  permission. Successful authenticated HTTPS proves DNS/connection/TLS/auth worked
  for these operations; it does not diagnose the earlier restricted session.
- Normal push c56b2a3 verified with `git ls-remote`; draft PR #16 created against
  codex/emergency-lock. Initial nine workflows started, results not yet accepted.
- Gradle 8.13 offline with JDK21.0.11 and existing SDK36/buildtools35 now starts and
  completes both JVM variants. Earlier FileLockContentionHandler/wildcard-IP error
  no longer reproduces. No version/cache deletion workaround was used.
- Activated Python3.13.12 venv; `cd relay; PYTHONPATH=. timeout 60s python -m pytest
  tests/test_admission_http.py::test_unadmitted_rejected_even_for_future_private_routes -q`:
  10 pass, exit0. Then `bash scripts/test_local.sh` with JDK21: 204 backend tests,
  20 core scenarios, 85 security scenarios, source policy pass, exit0. One existing
  Starlette/httpx deprecation warning remains visible. Previous timeout's exact
  cause remains UNKNOWN; do not present a changed product as its correction.
- `/dev/kvm` now exists, but `emulator -accel-check` reports permission denied,
  accel code11. No local AVD executed; use authorized hosted Actions. No group,
  mount or device permissions changed. `adb devices` is empty.

## Current increment

Typed admission/connectivity rejection reasons and a secret-free presentation
mapping; local peer admission evidence includes observation time, expiry and
source (public credential / challenge proof / Nearby proof / legacy unknown).
This is historical local evidence, not live possession or current global
revocation knowledge. New bucket is encrypted by existing Records; no wire or
SQLite schema change. Missing metadata has explicit legacy-unknown provenance.

Nonvisual adapters: secure window/surface setup, generic notification construction,
nonpersistent sensitive input policy, bounded owned char buffer, bounded in-memory
JPEG/PNG decode/re-encode. Product UI is untouched; these adapters are not yet
applied by Claude. Android tests check synthetic metadata removal, input/window
properties and rejection; compilation is not Android execution.

Local expanded Gradle JVM plus debug test APK assembly and connected/offline lint
passed. New tests have not yet run on Android at this checkpoint. No content
F01–F06 acceptance is claimed. Clipboard, restricted transfer/session engine,
media/PDF adapters, dedicated labs and final Claude contracts remain outstanding.
