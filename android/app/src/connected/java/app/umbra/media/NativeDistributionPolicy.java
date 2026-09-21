package app.umbra.media;

/** Capability gate for the pinned native distribution, not a runtime override.
 * Its TURN allocator follows 300 ALTERNATE-SERVER without a local destination allowlist.
 * Java RELAY restricts candidate types, but cannot authorize those redirects before I/O.
 * Keep production closed until a maintained, pinned native implementation enforces it.
 */
public final class NativeDistributionPolicy {
    private NativeDistributionPolicy() {}
    public static void requireAuthorizedTurnDestinations() {
        throw new SecurityException(app.umbra.calls.RelayOnlyContract.FAILURE);
    }
}
