package app.umbra.media;

/** Lab-only assertion: a rejected write is evidence of cancellation, never a successful send. */
final class ExpiredDeliveryAssertion {
    /** Classifies only a failed audit read; native closure and its deadline remain mandatory. */
    static void checkSnapshot(SecurityException rejected, boolean expiryRequested, boolean expiryReached) {
        if (!expiryRequested || !expiryReached || !"Call interrupted".equals(rejected.getMessage())) {
            throw rejected;
        }
    }

    static void check(SecurityException rejected, boolean expiryRequestedAndReached, boolean nativeTerminal,
                      String expectedCall, String queuedCall) {
        if (!expiryRequestedAndReached || !nativeTerminal || expectedCall == null || expectedCall.isEmpty()
                || !expectedCall.equals(queuedCall) || !"Call interrupted".equals(rejected.getMessage())) {
            throw rejected;
        }
    }
    private ExpiredDeliveryAssertion() {}
}
