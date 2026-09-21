package app.umbra.media;

/** Build-bound native capability gate. Changing the dependency does not silently
 * inherit the reviewed allocator's guarantee. Evidence: source build 35577083313,
 * actual two-AVD TURN redirect rejection plus authenticated bidirectional Opus.
 * This does not grant Engine consent or claim validation on untested platforms.
 */
public final class NativeDistributionPolicy {
    private static final String REVIEWED="bbc5675f91b31f901e1a482b00991a36ac2b3d912d2782b80e1cc1b756b1c413";
    private NativeDistributionPolicy() {}
    public static void requireAuthorizedTurnDestinations() {
        requireReviewedArtifact(app.umbra.BuildConfig.VOICE_NATIVE_SHA256);
    }
    public static void requireReviewedArtifact(String digest) {
        if(!REVIEWED.equals(digest)) throw new SecurityException(app.umbra.calls.RelayOnlyContract.FAILURE);
    }
}
