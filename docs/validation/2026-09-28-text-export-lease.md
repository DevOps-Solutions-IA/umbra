# Ordinary text export consent — incremental A correction

Parent: eeef8751797e1647406307d1c0486f8901d8263d. Not master acceptance.

Review found that the initial clipboard adapter captured a vault authorization
only when the confirmation callback invoked copyMessage. A callback retained
across lock/unlock could therefore acquire the new vault generation instead of
proving that its original confirmation was still valid.

The adapter now requires OrdinaryTextExport.Review created before confirmation,
bound to the Engine, exact peer/message, original vault generation and a 60-second
monotonic consent limit. A successful or failed external write consumes that
consent: a provider could have accepted data before reporting failure, so a retry
requires a new owner review. Authorization, ordinary-text type, recipient and
message expiry are checked again before calling the sink. The sink can still
complete an already admitted external call while cancellation occurs; this is
not a recall guarantee or remote deletion. No change to restricted content export.

API for Claude (not yet integrated into its UI):
- PrivateClipboard.reviewMessage(engine, peer, messageId): before dialog.
- PrivateClipboard.copyMessage(review, confirmed): same review after confirmation.
- PrivateClipboard.clearOwned(): explicit foreground cleanup, own marker only.

Do not recreate a Review in the confirmation callback. Re-prompt after expiry,
lock/unlock or sink failure. The bounded synchronous storage/authorization calls
must be accounted for when integrating threading; no new UI scheduling is added
here. Android clipboard itself has no atomic compare-and-clear operation; race
limits and inability to recall other apps' copies still apply.

Three new real Engine/libsignal JVM regressions passed in both flavors:
confirmation and replay; lock/unlock plus restricted-object rejection; external
sink failure with no second write. Full JVM + debug lint invocation exit 0 (1m18s).
These tests use an in-memory transactional fixture and synthetic text. They do
not prove Android clipboard presentation or storage durability. UI remains Claude's.
