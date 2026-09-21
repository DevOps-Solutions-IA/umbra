package app.umbra.media;

/** Build-bound native capability gate. Changing the dependency does not silently
 * inherit the reviewed allocator's guarantee. Evidence: source build 35634570646,
 * 83 TURN and 12 parsed media-model/policy C++ tests; Android evidence is separate.
 * This does not grant Engine consent or claim validation on untested platforms.
 */
public final class NativeDistributionPolicy {
    private static final String REVIEWED="5743b0e47574a7d8bad047b00fdef8f49e56e41c944a12542282e2b91ccf9433";
    private NativeDistributionPolicy() {}
    public static void requireAuthorizedTurnDestinations() {
        requireReviewedArtifact(app.umbra.BuildConfig.VOICE_NATIVE_SHA256);
    }
    public static void requireReviewedArtifact(String digest) {
        if(!REVIEWED.equals(digest)) throw new SecurityException(app.umbra.calls.RelayOnlyContract.FAILURE);
    }
}
