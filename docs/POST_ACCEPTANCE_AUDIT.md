# Post-acceptance audit candidate

The accepted receipt remains **PR #16**, source
`e0024f091d29dc15c2d788b430c5ea11204e5060`, contract
`UI_SECURITY_CONTENT_API_V1`. Its branch, exported SOURCE.json and four contract
files are unchanged. Historical validation text is not the current status of
that accepted receipt.

This separate `codex/post-acceptance-audit` branch proposes a new candidate:

- Restricted presentation reservations survive an incomplete/failed resource
  closure and Engine/Vault owner replacement. A wall-clock presentation deadline
  cannot stand in for native closure confirmation. No content, identity, ratchet,
  permission, transport or UI format is changed.
- Gradle 8.14.4 fixes the reproduced build-repository fallback advisory affecting
  8.13. AGP and runtime dependencies remain pinned to their existing versions.
- Additional bounded native/media framing corpus, TLS framing probes and an
  offline two-phone plan validator strengthen verification without claiming
  physical execution.

## Admission follow-up from Claude PR #19

The independently reproduced `status()` corruption failure and nullable
`pendingRequest()` mismatch are corrected in a later candidate on this same branch.
See [RED/GREEN and compatibility evidence](validation/2026-09-30-admission-status-contract-regression.md).
INVALID snapshots carry no untrusted expiry; required operations still reject
absence explicitly. This restores the existing UI V1 contract without modifying
Claude's UI or the four frozen contract files. Earlier89bc6c9 CI is historical;
the follow-up requires its own ten workflows and27 jobs.

## Contract impact for Claude

**CONTRACT_CHANGE_REQUIRED — additive internal storage contract only:** custom
`Records` implementations/wrappers for the same live storage must expose the same
`restrictedResourceScope()` identity. Production `Vault` implements this through
its existing process/path ownership registry, including replacement AccessGates.
This token is resource accounting, never authorization or a cryptographic key.
The default preserves existing simple Records implementations; independently
wrapped shared storage must explicitly delegate the token. No UI V1 method,
state enum, wire version, encryption or persistence schema changes.

Continue using the frozen four UI documents and `Session.closure()`; an exceptional
closure is not permission to resume presentation. A failed resource reservation
remains denied for that process; process death removes live resources, while
persisted consume/expiry rules remain. Never recover by weakening admission,
creating a new UI-only lease, exporting content or ignoring the closure failure.
No automatic merge into Claude's branch is authorized.

The legacy UI wiring observations in the privacy review concern existing typed
error/surface APIs. Claude owns their eventual combined-UI integration. This audit
neither modifies nor validates that interface.

## Evidence index

- [Domain defect, regression and scope](validation/2026-09-29-post-domain-audit.md)
- [HTTPS framing and historical EOF](validation/2026-09-29-post-https-audit.md)
- [Bounded media corpus](validation/2026-09-29-post-media-audit.md)
- [Privacy/exfiltration source review](validation/2026-09-29-post-privacy-exfiltration-audit.md)
- [Supply chain, reproduced Gradle advisory and backup restore](validation/2026-09-29-post-supply-chain-restore.md)
- [Future physical matrix](POST_ACCEPTANCE_PHYSICAL_MATRIX.md)

Candidate-specific CI, commit/tree, artifact hashes and final backup receipts belong
to this branch's draft PR and exported evidence, not to PR #16. A restored baseline
build or an old accepted workflow never validates these new changes.

## Deliberately unresolved boundaries

`HISTORICAL_UNCONFIRMED`: old HTTPS EOF after active video; no causal fix claimed.
`MANUAL_PENDING`: authenticated hardware Vault, biometrics, real microphone/camera,
acoustics/routes and combined UI physical acceptance.
`NEEDS_SECOND_PEER`: two-phone RFCOMM, bidirectional voice/video and reviewed runtime
physical pair selection. The new offline validator checks declared preparation,
not physical identity; it grants no installation/execution authorization.
`BLOCKED_OBSERVABILITY`: global UID-attributed traffic absence on physical hardware.
`CLAUDE_INTEGRATION_PENDING`: graphical wiring and acceptance.

No phone was queried or installed during this audit. No promise of anonymity,
forensic erasure, universal anti-copy protection or production readiness is made.
