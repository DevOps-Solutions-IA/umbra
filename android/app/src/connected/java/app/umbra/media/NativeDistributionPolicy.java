package app.umbra.media;

/** Build-bound native capability gate. Changing the dependency does not silently
 * inherit the reviewed allocator's guarantee. Evidence: source build 36187887900,
 * 83 TURN and 12 parsed media-model/policy C++ tests; Android evidence is separate.
 * This does not grant Engine consent or claim validation on untested platforms.
 */
public final class NativeDistributionPolicy {
    private static final String REVIEWED="25f2abebc99e2e109cff83a428080408843fda51a9cdadb5c081d694c92b7620";
    private NativeDistributionPolicy() {}
    public static void requireAuthorizedTurnDestinations() {
        requireReviewedArtifact(app.umbra.BuildConfig.VOICE_NATIVE_SHA256);
    }
    public static void requireReviewedArtifact(String digest) {
        if(!REVIEWED.equals(digest)) throw new SecurityException(app.umbra.calls.RelayOnlyContract.FAILURE);
    }
}
