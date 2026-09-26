package app.umbra.transport;

import android.annotation.SuppressLint;
import android.bluetooth.*;
import android.content.Context;
import app.umbra.core.*;
import app.umbra.protocol.Wire;
import org.json.JSONObject;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded RFCOMM transport. Pairing is explicit; reconnects require known verified identity keys. */
@SuppressLint("MissingPermission")
public final class BluetoothLink implements AutoCloseable {
    public enum Stage { CONNECTING, SOCKET_CONNECTED, HELLO_SENT, HELLO_RECEIVED, PROOF_SENT, AUTHENTICATED }
    public interface Listener {
        default void stage(Stage stage) {}
        String ownId() throws Exception;
        JSONObject ownCard() throws Exception;
        String acceptCard(JSONObject card) throws Exception;
        byte[] prove(boolean dialer, String peer, byte[] ownNonce, byte[] peerNonce) throws Exception;
        void verify(boolean peerIsDialer, String peer, byte[] peerNonce, byte[] ownNonce, byte[] proof, boolean enrolling) throws Exception;
        void authorizeSend(String peer) throws Exception;
        default void authorizeEnvelope(String peer, JSONObject envelope) throws Exception { authorizeSend(peer); }
        void receive(String peer, JSONObject envelope) throws Exception;
        void status(String text);
    }
    private static final UUID SERVICE = UUID.fromString("b171f40c-36ac-42a1-b128-3a4343d14002");
    private final BluetoothAdapter adapter;
    private final Listener listener;
    private final ThreadPoolExecutor io = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(2), new ThreadPoolExecutor.AbortPolicy());
    private final ThreadPoolExecutor writes = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(4), new ThreadPoolExecutor.AbortPolicy());
    private final ConcurrentMap<String, CompletableFuture<Void>> inFlight = new ConcurrentHashMap<>();
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor();
    private volatile BluetoothServerSocket server;
    private volatile BluetoothSocket socket;
    private volatile String peer;
    private volatile long started, lastFrame;
    private long epoch;
    private final AtomicBoolean closed = new AtomicBoolean();
    public BluetoothLink(Context context, Listener listener) {
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        adapter = manager == null ? null : manager.getAdapter(); this.listener = listener;
        watchdog.scheduleWithFixedDelay(() -> {
            BluetoothSocket active = socket; if (active == null) return;
            long now = System.nanoTime();
            if ((peer == null && now - started > TimeUnit.SECONDS.toNanos(20)) ||
                now - lastFrame > TimeUnit.SECONDS.toNanos(45)) {
                disconnectExpected(active); listener.status("Bluetooth: enlace cerrado por tiempo límite"); return;
            }
            if (peer != null) try {
                writes.execute(() -> {
                    try { write(active, new JSONObject().put("kind", "ping")); }
                    catch (Exception e) { disconnectExpected(active); }
                });
            } catch (RejectedExecutionException ignored) { /* A blocked write has its own deadline. */ }
        }, 10, 10, TimeUnit.SECONDS);
    }
    public BluetoothAdapter adapter() { return adapter; }
    public String connectedPeer() { return peer; }
    private void available() {
        if (closed.get()) throw new IllegalStateException("Enlace cerrado");
        if (adapter == null || !adapter.isEnabled()) throw new IllegalStateException("Activa Bluetooth en este teléfono");
    }
    public void listen() throws IOException { listen(false); }
    public synchronized void listen(boolean enroll) throws IOException {
        available(); disconnect(); stopListening(); long expectedEpoch = epoch;
        server = adapter.listenUsingRfcommWithServiceRecord("UMBRA", SERVICE);
        BluetoothServerSocket accepting = server;
        listener.status(enroll ? "Bluetooth: esperando vinculación explícita" : "Bluetooth: esperando contacto verificado");
        try {
            io.execute(() -> {
                try {
                    BluetoothSocket accepted = accepting.accept(120_000);
                    synchronized (this) {
                        if (closed.get() || server != accepting || epoch != expectedEpoch) { accepted.close(); return; }
                        server = null;
                    }
                    accepting.close(); runSocket(accepted, false, enroll, expectedEpoch);
                } catch (Exception e) { if (!closed.get()) listener.status("Bluetooth: escucha finalizada"); }
                finally { try { accepting.close(); } catch (IOException ignored) {} }
            });
        } catch (RejectedExecutionException e) { stopListening(); throw new IOException("Bluetooth ocupado", e); }
    }
    public void connect(BluetoothDevice device) { connect(device, false); }
    public synchronized void connect(BluetoothDevice device, boolean enroll) {
        available();
        if (device == null || device.getBondState() != BluetoothDevice.BOND_BONDED)
            throw new SecurityException("Vincula primero ambos teléfonos mediante Android");
        disconnect(); stopListening(); long expectedEpoch = epoch;
        try {
            io.execute(() -> {
                try { runSocket(device.createRfcommSocketToServiceRecord(SERVICE), true, enroll, expectedEpoch); }
                catch (Exception e) { if (!closed.get()) listener.status("Bluetooth: conexión fallida"); }
            });
        } catch (RejectedExecutionException e) { throw new IllegalStateException("Bluetooth ocupado", e); }
    }
    private void runSocket(BluetoothSocket active, boolean dialer, boolean enroll, long expectedEpoch) {
        try {
            synchronized (this) {
                if (closed.get() || epoch != expectedEpoch || socket != null) { active.close(); return; }
                socket = active; started = lastFrame = System.nanoTime();
            }
            listener.stage(Stage.CONNECTING);
            if (dialer) { adapter.cancelDiscovery(); active.connect(); }
            listener.stage(Stage.SOCKET_CONNECTED);
            if (active.getRemoteDevice().getBondState() != BluetoothDevice.BOND_BONDED)
                throw new SecurityException("Paired device required");
            String local = Wire.identity(listener.ownId()); byte[] nonce = Bytes.random(32);
            JSONObject hello = new JSONObject().put("kind", "hello").put("v", 2).put("id", local)
                .put("nonce", Bytes.b64(nonce)).put("role", dialer ? "dialer" : "listener").put("enroll", enroll);
            if (enroll) hello.put("card", listener.ownCard()); // Never sent automatically on ordinary reconnect.
            write(active, hello);
            listener.stage(Stage.HELLO_SENT);
            JSONObject remoteHello = Wire.parse(Framing.read(active.getInputStream(), 20_000), 20_000);
            listener.stage(Stage.HELLO_RECEIVED);
            Wire.fields(remoteHello, enroll ? new String[]{"kind", "v", "id", "nonce", "role", "enroll", "card"}
                : new String[]{"kind", "v", "id", "nonce", "role", "enroll"});
            if (!"hello".equals(Wire.string(remoteHello, "kind", 16)) || Wire.integer(remoteHello, "v") != 2 ||
                !(remoteHello.get("enroll") instanceof Boolean) || remoteHello.getBoolean("enroll") != enroll ||
                !(dialer ? "listener" : "dialer").equals(Wire.string(remoteHello, "role", 16)))
                throw new SecurityException("Incompatible nearby handshake");
            String remote = Wire.identity(Wire.string(remoteHello, "id", 64));
            byte[] otherNonce = Bytes.unb64(Wire.string(remoteHello, "nonce", 44));
            // Validate before signing, including role separation, self-connections and nonce reflection.
            NearbyTranscript.encode(dialer, local, nonce, remote, otherNonce);
            if (enroll && !remote.equals(listener.acceptCard(remoteHello.getJSONObject("card"))))
                throw new SecurityException("Contact identity substitution");
            byte[] proof = listener.prove(dialer, remote, nonce, otherNonce);
            write(active, new JSONObject().put("kind", "proof").put("signature", Bytes.b64(proof)));
            listener.stage(Stage.PROOF_SENT);
            JSONObject remoteProof = Wire.parse(Framing.read(active.getInputStream(), 512), 512);
            Wire.fields(remoteProof, "kind", "signature");
            if (!"proof".equals(Wire.string(remoteProof, "kind", 16))) throw new SecurityException("Missing identity proof");
            listener.verify(!dialer, remote, otherNonce, nonce, Bytes.unb64(Wire.string(remoteProof, "signature", 88)), enroll);
            synchronized (this) {
                if (closed.get() || socket != active || epoch != expectedEpoch || System.nanoTime() - started > TimeUnit.SECONDS.toNanos(20))
                    throw new IOException("Handshake no longer active");
                peer = remote; lastFrame = System.nanoTime();
                listener.stage(Stage.AUTHENTICATED);
            }
            listener.status(enroll ? "Clave del dispositivo comprobada · verifica el código del contacto" : "Bluetooth: contacto verificado conectado");
            long window = System.nanoTime(); int frames = 0;
            while (!closed.get() && socket == active) {
                JSONObject frame = Wire.parse(Framing.read(active.getInputStream()), Framing.MAX_FRAME);
                long now = System.nanoTime();
                if (now - window >= TimeUnit.MINUTES.toNanos(1)) { window = now; frames = 0; }
                if (++frames > 120) throw new IOException("Frame rate exceeded");
                String kind = Wire.string(frame, "kind", 16);
                if (kind.equals("ping")) { Wire.fields(frame, "kind"); lastFrame = now; continue; }
                Wire.fields(frame, "kind", "envelope");
                if (!kind.equals("message")) throw new IOException("Unsupported frame");
                JSONObject envelope = frame.getJSONObject("envelope");
                if (!remote.equals(envelope.getString("from"))) throw new SecurityException("Peer substitution");
                listener.receive(remote, envelope); // Wait for authenticated processing and durable commit.
                lastFrame = System.nanoTime();
            }
        } catch (Exception e) {
            if (!closed.get()) listener.status("Bluetooth: enlace cerrado o paquete no aceptado");
        } finally { disconnectExpected(active); try { active.close(); } catch (IOException ignored) {} }
    }
    private void write(BluetoothSocket active, JSONObject frame) throws Exception {
        if (closed.get() || active == null || socket != active) throw new IOException("Link changed");
        ScheduledFuture<?> deadline = watchdog.schedule(() -> disconnectExpected(active), 15, TimeUnit.SECONDS);
        try { Framing.write(active.getOutputStream(), Bytes.utf8(frame.toString())); }
        finally { deadline.cancel(false); }
    }
    public CompletableFuture<Void> sendAsync(String recipient, JSONObject envelope) throws Exception {
        if (peer == null || !peer.equals(recipient) || !recipient.equals(envelope.getString("to"))) throw new IOException("Contact not connected");
        String id = envelope.getString("id"); BluetoothSocket expected = socket;
        JSONObject immutableEnvelope = Wire.parse(Bytes.utf8(envelope.toString()), Framing.MAX_FRAME);
        CompletableFuture<Void> result = new CompletableFuture<>();
        if (inFlight.putIfAbsent(id, result) != null) throw new IOException("Already queued on this link");
        try {
            writes.execute(() -> {
                try {
                    if (result.isDone() || socket != expected || !recipient.equals(peer)) throw new IOException("Link changed");
                    listener.authorizeEnvelope(recipient, immutableEnvelope);
                    write(expected, new JSONObject().put("kind", "message").put("envelope", immutableEnvelope)); result.complete(null);
                } catch (Exception e) { result.completeExceptionally(e); }
                finally { inFlight.remove(id, result); }
            });
        } catch (RejectedExecutionException e) { inFlight.remove(id, result); result.completeExceptionally(e); }
        return result;
    }
    private synchronized void stopListening() {
        BluetoothServerSocket old = server; server = null;
        if (old != null) try { old.close(); } catch (IOException ignored) {}
    }
    private synchronized void disconnectExpected(BluetoothSocket expected) { if (socket == expected) disconnect(); }
    public synchronized void disconnect() {
        epoch++; BluetoothSocket old = socket; socket = null; peer = null;
        if (old != null) try { old.close(); } catch (IOException ignored) {}
        inFlight.forEach((id, future) -> future.completeExceptionally(new IOException("Link closed"))); inFlight.clear();
    }
    @Override public void close() {
        closed.set(true); disconnect(); stopListening(); watchdog.shutdownNow(); writes.shutdownNow(); io.shutdownNow();
    }
}
