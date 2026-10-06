# Restricted PDF transport fixture extension — 2026-09-28

Master v3 remains open. Preceding published HEAD b8e91c962d0db1e06791fe9cc38ee0deee96ab50.

The isolated PDF preparation/decoder already passed Android debug at c7584ee;
its R8 guard failure and correction are preserved in prepared-ownership-and-pdf-r8.
This extension adds a distinct two-page synthetic PDF in each direction to the
existing real HTTPS and RFCOMM fixtures. Both use common Engine/libsignal send,
duplicate delivery, exact page-color checks after decoding, persistent consume
state and rejection of a second opening. HTTPS uses laboratory SQLite in one
Android process; regular RFCOMM uses memory records in two independent AVDs.
Neither is a physical-radio or production-hardware-Keystore claim.

The RFCOMM fixture prepares its PDF outside the coordination monitor. Existing
45-second delivery deadline, admission, out-of-band fingerprint approval, real
Bluetooth transport and text/file/location/PNG/AAC checks remain mandatory.
Host receipts now require explicit PDF evidence; an old AAC-only HTTPS receipt
is rejected by regression. No media, network or test timeout was relaxed.

Local checks: 188 script tests passed; both debug instrumentation APKs and lint
compiled successfully. Native HTTPS/PDF and RFCOMM/PDF execution is PENDING on
this new revision. Compilation and an updated fixture are not transport acceptance.
File-video remains unimplemented, and no final API freeze/master closure is claimed.
