# Restricted PDF implementation checkpoint — 2026-09-28

Master v3 remains open. No production UI changed. Exact preceding committed
correction: 4e969dc (original prepared-content authorization); this PDF change is
cumulative and awaits its own Android CI. Base remains ba75d329.

Implemented: private non-exported isolated PdfRenderer service; unique job
instances; bounded revocable RAM proxy descriptor; framework PDF to static PNG
pages; canonical bounded page pack; receiver decoder owned by common consumed
Session. No plaintext file, external viewer, new permission or new dependency.
Explicit APK guard requires exactly the private isolated component, rejecting
export, wrong process, missing isolation and intent filters. No general exception.

Local checks: JDK 21 / Gradle 8.13 / SDK 36, existing dependencies. Both debug
instrumentation APKs and lint built successfully; 188 Python script tests passed;
repository guard passed (455 source files at that check). `test_local.sh` exited 0:
204 relay tests, 20 utilities, 85 core security and 13 static source checks.
Starlette's existing httpx deprecation remains visible. Parser JVM regression
covers canonical input, bounds/truncation/version/trailing input and 2000 fixed-
seed malformed inputs (one test, not 2000 claimed tests).

Three native cases were added per flavor: isolated preparation -> real Signal ->
page render/navigation/consume/SQLite reopen; malformed/too-many pages and recovery;
consent/epoch and manifest isolation. These are not yet executed. Suite expected
counts change by exactly three (privacy 13/12, general instrumentation 61/58).
R8 lab requires proof that the document decoder was optimized. No local KVM.

Pending: native process behavior, malformed-input denial and cleanup receipts,
resource adversaries, transport expansion, cancellation measurements and file
video. SQLite reopen is not process death during commit. PDF conversion is not
support for arbitrary document formats. See ADR-restricted-pdf.md and protocol.

Historical c808418 privacy run 36458434466 passed debug/R8 at checkout
`a289dab88d910f2dcde6521a9654cc42c8df4f44`. Downloaded archives matched GitHub:
- debug 10986962428: 075fac17e0b2a91f68fde8e02bc2717a49d2d3f30887fe4ef0a9a752b8bfd717
- R8 10986038927: 8b6c0021e7330e4ecc3e700454dd3252833ba50e915ec6263355394b35ae883d
Those receipts validate the earlier sanitized AAC copy, not this PDF change.

Before publication, cumulative full JVM: 255 connected / 198 offline, zero failures,
errors or skips. Four debug/release APK builds exited 0 (1m14s); all four final APK
policy inspections passed. These are build/JVM/policy results, not PDF AVD runs.
