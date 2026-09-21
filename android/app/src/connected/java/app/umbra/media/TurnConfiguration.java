package app.umbra.media;

import app.umbra.calls.CallPayload;
import app.umbra.calls.RelayOnlyContract;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.webrtc.PeerConnection;

/** Local, short-lived credentials. Never constructed from a remote signaling payload.
 * Each configuration is disposable and fails permanently on expiry or policy failure.
 * This class configures the native API; it does not grant Engine consent or start media.
 */
public final class TurnConfiguration implements AutoCloseable {
    private final RelayOnlyContract contract;
    private final String revision;
    private final LongSupplier elapsedMillis;
    private final long createdMillis, deadlineMillis;
    private String username, password;
    private boolean closed;

    public TurnConfiguration(List<String> authorizedUrls, String revision, String username,
                             String password, long remainingMillis, LongSupplier elapsedMillis) {
        contract = new RelayOnlyContract(authorizedUrls, revision);
        this.revision = revision;
        this.elapsedMillis = Objects.requireNonNull(elapsedMillis);
        if (username == null || password == null || username.isBlank() || password.isBlank()
                || username.length() > 256 || password.length() > 256
                || username.chars().anyMatch(c -> c < 33 || c > 126)
                || password.chars().anyMatch(c -> c < 33 || c > 126)
                || remainingMillis < 1 || remainingMillis > 180_000) {
            throw new SecurityException(RelayOnlyContract.FAILURE);
        }
        this.username = username;
        this.password = password;
        createdMillis = elapsedMillis.getAsLong();
        try { deadlineMillis = Math.addExact(createdMillis, remainingMillis); }
        catch (ArithmeticException invalid) { throw new SecurityException(RelayOnlyContract.FAILURE); }
    }

    /** Fresh object on every use; no inherited ALL policy during restart/reconfiguration. */
    public synchronized PeerConnection.RTCConfiguration nativeConfiguration(
            String currentRevision, CallPayload.NetworkPolicy policy, int generation) {
        try {
            check();
            contract.check(currentRevision, policy, generation);
            var server = PeerConnection.IceServer.builder(contract.authorizedTurnUrls())
                    .setUsername(username).setPassword(password)
                    .setTlsCertPolicy(PeerConnection.TlsCertPolicy.TLS_CERT_POLICY_SECURE)
                    .createIceServer();
            var configuration = new PeerConnection.RTCConfiguration(List.of(server));
            configuration.iceTransportsType = PeerConnection.IceTransportsType.RELAY;
            configuration.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
            configuration.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
            configuration.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;
            configuration.iceCandidatePoolSize = 0;
            configuration.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE;
            return configuration;
        } catch (RuntimeException failure) {
            close();
            throw new SecurityException(RelayOnlyContract.FAILURE);
        }
    }

    public synchronized void check() {
        long now = elapsedMillis.getAsLong();
        if (closed || contract.failed() || now < createdMillis || now >= deadlineMillis) {
            close();
            throw new SecurityException(RelayOnlyContract.FAILURE);
        }
    }

    public synchronized void fail(RelayOnlyContract.Failure reason) {
        contract.failure(reason);
        close();
    }

    @Override public synchronized void close() {
        closed = true;
        username = null;
        password = null;
    }

    // Native WebRTC necessarily copies credentials into native memory. Dropping Java references
    // is not forensic erasure; the owner must also close/dispose the PeerConnection.
    @Override public String toString() { return "TurnConfiguration[redacted]"; }
}
