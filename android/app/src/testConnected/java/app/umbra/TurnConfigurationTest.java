package app.umbra;

import app.umbra.calls.CallPayload.NetworkPolicy;
import app.umbra.calls.RelayOnlyContract;
import app.umbra.media.TurnConfiguration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import org.webrtc.PeerConnection;
import static org.junit.Assert.*;

/** Real Java API of the pinned Android library. Does not load JNI or prove TURN transport. */
public final class TurnConfigurationTest {
    private static final String REVISION = "a".repeat(64);
    private TurnConfiguration config(AtomicLong clock) {
        return new TurnConfiguration(List.of("turn:127.0.0.1:3478?transport=udp"), REVISION,
                "synthetic-user", "synthetic-password", 180_000, clock::get);
    }
    private void reject(Runnable action) {
        try { action.run(); fail("Unsafe configuration accepted"); }
        catch (SecurityException expected) { assertEquals(RelayOnlyContract.FAILURE, expected.getMessage()); }
    }
    @Test public void nativeApiIsRelayOnlyAcrossGenerationsWithSecureTls() {
        try (var config = config(new AtomicLong())) {
            for (int generation=1; generation<=4; generation++) {
                var rtc = config.nativeConfiguration(REVISION, NetworkPolicy.RELAY_ONLY, generation);
                assertEquals(PeerConnection.IceTransportsType.RELAY, rtc.iceTransportsType);
                assertEquals(PeerConnection.TlsCertPolicy.TLS_CERT_POLICY_SECURE, rtc.iceServers.get(0).tlsCertPolicy);
                assertEquals(0, rtc.iceCandidatePoolSize);
                assertEquals(List.of("turn:127.0.0.1:3478?transport=udp"), rtc.iceServers.get(0).urls);
                rtc.iceTransportsType = PeerConnection.IceTransportsType.ALL;
            }
        }
    }
    @Test public void downgradeOrConfigurationChangePermanentlyInvalidatesCredentials() {
        for (boolean remoteDowngrade : List.of(false, true)) {
            try (var config = config(new AtomicLong())) {
                reject(() -> config.nativeConfiguration(remoteDowngrade ? REVISION : "b".repeat(64),
                        remoteDowngrade ? NetworkPolicy.DIRECT_ALLOWED : NetworkPolicy.RELAY_ONLY, 1));
                reject(() -> config.nativeConfiguration(REVISION, NetworkPolicy.RELAY_ONLY, 2));
            }
        }
    }
    @Test public void expiryRollbackAndCloseCannotBeReactivated() {
        for (long invalidTime : new long[]{180_000, -1}) {
            AtomicLong clock = new AtomicLong();
            try (var config = config(clock)) {
                clock.set(invalidTime); reject(config::check);
                clock.set(0); reject(config::check);
            }
        }
        var config = config(new AtomicLong()); config.close(); reject(config::check);
    }
    @Test public void turnFailuresNeverYieldFallbackConfiguration() {
        for (var reason : RelayOnlyContract.Failure.values()) {
            try (var config = config(new AtomicLong())) {
                config.fail(reason);
                reject(() -> config.nativeConfiguration(REVISION, NetworkPolicy.RELAY_ONLY, 1));
                assertFalse(config.toString().contains("synthetic"));
            }
        }
        reject(() -> new TurnConfiguration(List.of(), REVISION, "user", "secret", 1000, () -> 0));
        reject(() -> new TurnConfiguration(List.of("turn:127.0.0.1:3478"), REVISION, "user", "secret", 180_001, () -> 0));
    }
}
