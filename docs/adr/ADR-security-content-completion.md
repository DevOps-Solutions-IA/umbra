# Security/content completion — incremental domain integration

Status: partial implementation, not acceptance. Base: ba75d329bedb2a26cd2e70371d2369e2fb3ba7a1.

The emergency coordinator, encrypted Records transactions, vault leases and
private-startup gates remain authoritative. No replacement permission system or
UI is introduced. Claude's ca2a706 bundle is a reviewed contract reference only.

## Admission result atomicity

A renewal result contains two independently signed objects. Import must validate
both against the pinned realm, bind the revocation to the currently installed
credential and the replacement to the same device/Signal keys, then install both
in one Records transaction. A valid replacement paired with a malformed or
unrelated revocation must leave the credential and pending request unchanged.
The current credential may have expired: renewal still requires its authenticated
binding, a live local vault lease, a pending request and authority-signed approval.
No contact trust is raised. No authority rotation or network provisioning is added.

Self-approval and installation also need one transaction. A failed commit must
not consume the in-memory Review token permanently: durable decision/nonces, not
a pre-commit boolean, determine replay rejection. Reviews remain tied to their
originating service and vault authorization generation.

Reference: Claude commit 798f5d58805335b64be2d9e3d920082c4c7d9047
identified an atomic renewal import requirement in its UI flow. This change moves
the invariant into the domain; none of that commit's UI is imported.

## Remaining master scope

Admission snapshots/errors, nonvisual privacy adapters and restricted-content
blocks B/C require subsequent implementation and validation. This ADR does not
claim those features exist. Restricted access must eventually be enforced by
persistent atomic consumption and bounded sessions, not presentation flags.
