# Restricted PDF preparation and page rendering

Status: implementation written; local compilation/parser regressions passed.
Android execution remains pending; this decision is not PDF acceptance.

The master requires a bounded PDF subset, no external viewer/printing/copy API,
no plaintext temporary file and one persistent opening using the common engine.
PDF is not an image extension and renaming a file is not sanitization.

Use the maintained Android framework PdfRenderer (PDFium supplied by the OS),
not a new downloaded parser. Its constructor needs a seekable descriptor and can
be long-running. Android recommends an isolated process for untrusted documents:
https://developer.android.com/reference/android/graphics/pdf/PdfRenderer .
The input therefore belongs in a non-exported isolated service with no vault,
Signal, admission authority or network capability. The parent supplies a bounded
RAM-backed read-only proxy descriptor, not a URI/FileProvider or plaintext disk
file. StorageManager.openProxyFileDescriptor supports this use on minSdk 31:
https://developer.android.com/reference/android/os/storage/StorageManager#openProxyFileDescriptor(int,android.os.ProxyFileDescriptorCallback,android.os.Handler) .

The initial admitted subset is a static rendered copy: at most four pages,
maximum 768 pixels on each rendered edge, no more than the existing 256 KiB
object transfer limit in total. Preparation renders pages locally and emits a
versioned pack of fresh PNG pages; original PDF objects, names, text layers,
actions, links, scripts, forms and metadata are not transmitted. This is an
explicit conversion, not a promise to preserve searchable/vector PDF fidelity.
The original external PDF remains owned by its user. Password-protected or
malformed input, oversized output, unsupported rendering and timeout fail closed.
No target/API update or permission expansion is justified by this feature.

The receiver only parses the bounded page pack and uses Android's bounded PNG
decoder. It does not run a remote PDF parser. Page navigation within the one
common Session is permitted; no raw bytes, print/export/URI or external app API
is exposed. ONCE, UMBRA_ONLY and expiry reuse the existing authenticated object,
concrete recipient, durable consume and cancellation contracts. Unknown format
on an older client is rejected, never displayed as an ordinary attachment.

Preparation must bind to the existing reviewed recipient and original vault
lease. No lock is held across native rendering. Cancellation revokes proxy reads
immediately; resource closure is a separate receipt. A native hang/crash or
unconfirmed isolated-service closure must not be reported as success or grant
an overlapping session. Explicit time/resource limits, binder death, malformed
messages, restart, storage failure and optimized Android execution still require
implementation and regression evidence before this decision is accepted.

The isolated process reduces parser impact; it does not promise absence of OS/
parser vulnerabilities or forensic erasure. Presentation must protect and clear
its surfaces before/after the authorized session; Claude exclusively owns that
integration. Rendering here cannot erase images copied by a recipient or taken
with an external camera.

Implementation detail under native validation: each preparation uses a unique
`bindIsolatedService` instance (API 29, compatible with minSdk 31), a runtime
`Process.isIsolated()` check (API 28), and a one-job service. Only the parser's
own isolated process may terminate itself. Binder death confirms that process
closure separately from receipt of output. Unexpected reconnection never resends
the PDF. See https://developer.android.com/reference/android/content/Context#bindIsolatedService(android.content.Intent,int,java.lang.String,java.util.concurrent.Executor,android.content.ServiceConnection)
and https://developer.android.com/reference/android/os/Process#isIsolated() .

The parser watchdog is 10 seconds after creation; the client response budget is
12 seconds after binding, with separate bounded cleanup waits. These are design
limits, not measured cancellation claims. The existing five-second emergency
coordinator deadline is unchanged and can report INCOMPLETE sooner. OS Binder or
VM stalls are not claimed to be forcibly bounded by Java timers. Native parser
allocation cannot be inferred solely from the 256 KiB input bound; adversarial
resource testing and platform security updates remain necessary.
