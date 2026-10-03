package app.umbra.media;

/** Lab-only failed delivery classification; never marks transport or native rejection successful. */
public final class NativeRejectionDeliveryAssertion {
    public static void check(SecurityException rejected, boolean wrongFingerprintExpected, String nativeState,
                      String failureStage, int capturedAudio, int decodedAudio, int capturedVideo,
                      String expectedCall, String queuedCall) {
        // CallService.Interrupted is private and R8 renames classes: do not reflect on its name.
        if (!wrongFingerprintExpected || !"FAILED".equals(nativeState)
                || !"native-certificate-binding".equals(failureStage)
                || capturedAudio != 0 || decodedAudio != 0 || capturedVideo != 0
                || expectedCall == null || expectedCall.isEmpty() || !expectedCall.equals(queuedCall)
                || !"Call interrupted".equals(rejected.getMessage())) {
            throw rejected;
        }
        // Caller may only abandon this pump. Existing late-callback/closure assertions remain required.
    }
    private NativeRejectionDeliveryAssertion() {}
}
