# Restricted static document pages v1

Status: Android implementation written and under native validation. Not final
master acceptance, not support for arbitrary PDF or other document formats.

Sender API: `RestrictedDocuments.prepare(Context, Engine, Review, byte[], boolean)`
on a worker, using an original `reviewSend` and explicit confirmation. Input PDF
is at most 262144 bytes, one to four pages, non-password-protected and readable
by Android PdfRenderer. Page geometry is positive, at most 20000 points per edge,
with aspect ratio at most 16. Each page becomes a fresh PNG raster, no larger
than 768 pixels per edge. Total prepared pack remains at most 262144 bytes.
This loses selectable text, links, forms and vector fidelity; it is a documented
static conversion, never a renamed original PDF. No scripts or resource-fetch
APIs are exposed. The caller's original input remains unchanged and caller-owned.

The original PDF is read through a revocable RAM proxy descriptor in a uniquely
bound, non-exported OS-isolated parser process. No vault/Signal key is passed.
No plaintext file, provider URI or external viewer is used. Output is accepted
only with original authorization and confirmed parser process death. Invalid
input fails closed; no unsafe fallback. Cleanup failure retains denial through
the existing emergency coordinator. The service is never started on app launch.

Inside the existing encrypted `restricted` envelope, `format=PDF_PAGES` and all
other v1 descriptor/AEAD/Signal fields remain unchanged. Older clients reject
this unknown format. Page-pack bytes use big-endian integers:

- uint32 magic `0x55504731` (UPG1).
- uint32 page count, 1..4.
- For each page: uint32 PNG byte length (at least 8), exactly that many bytes.
- No trailing data or alternate versions. Length arithmetic is checked before reads.

Container parsing is not PNG validation. The receiver additionally checks PNG
MIME, dimensions, complete native decoding and resource ownership before exposing
`RestrictedDocuments.Decoder`. All pages belong to the one consumed Session.
`pageCount()` and `render(pageIndex, protectedCanvas, destinationRect)` require
that original live Session. Page navigation/zoom does not grant another opening.
No method returns page bytes/bitmap/text/URI or provides copy/print/share/export.
`close()` invalidates the Session; its separate `closure()` receipt governs
confirmed cleanup. Presentation must clear its own Canvas/surface on invalidation.

ONCE, UMBRA_ONLY, object/session expiry, exact-device delivery, replay tombstones,
immutable retries and lock/emergency behavior reuse RestrictedContentService.
No new relay table, endpoint, permission or network exemption exists. Both
flavors share rendering; offline retains its existing prohibited permissions.

Errors use existing ContentException codes (INVALID/CAPACITY/BUSY/CONSENT_REQUIRED
and common consumed/expiry codes) or original vault/authorization denial. UI uses
OperationFailure's safe classification, never native parser strings. Concurrent
preparation is bounded to one per application process. An unconfirmed native
closure prevents another preparation instead of silently freeing its slot.

Pending: actual Android debug/R8 receipts, pathological PDF resource tests,
transport coverage, race/cancellation measurements and final Claude integration.
No claim of hardware protection, external-copy prevention or forensic RAM erasure.
