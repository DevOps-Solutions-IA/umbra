package app.umbra;

import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.runner.Description;
import org.junit.runner.notification.RunListener;
import android.bluetooth.*;
import android.os.Bundle;
import android.os.SystemClock;
import app.umbra.core.Bytes;
import app.umbra.crypto.Engine;
import app.umbra.transport.BluetoothLink;
import org.json.JSONObject;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Explicit two-device fixture: real RFCOMM + libsignal, synthetic test-only memory storage. */
public final class NearbyFixtureListener extends RunListener {
    private Bundle arguments;
    private final Object recordsLock = new Object();
    private Engine engine;
    private BluetoothLink link;
    private volatile Throwable receiveFailure;
    private static void require(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    private void status(String key, String value) { Bundle b = new Bundle(); b.putString(key, value); InstrumentationRegistry.getInstrumentation().sendStatus(0, b); }
    private interface Condition { boolean ready() throws Exception; }
    private static void await(Condition condition, long milliseconds, String failure) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + milliseconds;
        while (!condition.ready()) {
            if (SystemClock.elapsedRealtime() >= deadline) throw new AssertionError(failure);
            Thread.sleep(100);
        }
    }
    @Override public void testRunStarted(Description description) throws Exception {
        arguments = InstrumentationRegistry.getArguments();
        File approval = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getFilesDir(), "nearby-synthetic-approval");
        try {
            require(BuildConfig.DEBUG, "Only synthetic debug APKs may run this fixture");
            require(!InstrumentationRegistry.getInstrumentation().getTargetContext().getDatabasePath("umbra.db").exists(), "Refuse existing vault data");
            Files.deleteIfExists(approval.toPath());
            String role = arguments.getString("role", "");
            require(role.equals("listener") || role.equals("dialer"), "Specify listener or dialer role");
            boolean dialer = role.equals("dialer");
            BluetoothAdapter adapter = InstrumentationRegistry.getInstrumentation().getTargetContext().getSystemService(BluetoothManager.class).getAdapter();
            require(adapter != null && adapter.isEnabled(), "Bluetooth adapter must be enabled");
            BluetoothDevice device = adapter.getRemoteDevice(arguments.getString("address", ""));
            require(device.getBondState() == BluetoothDevice.BOND_BONDED, "Pair the synthetic devices in Android first");
            DeviceMemoryRecords records = new DeviceMemoryRecords();
            engine = new Engine(records); engine.initialize("Synthetic " + role);
            new app.umbra.devices.DeviceService(records).migrate();
            link = new BluetoothLink(InstrumentationRegistry.getInstrumentation().getTargetContext(), new BluetoothLink.Listener() {
                public String ownId() throws Exception { synchronized (recordsLock) { return engine.id(); } }
                public JSONObject ownCard() throws Exception { synchronized (recordsLock) { return engine.createCard(); } }
                public String acceptCard(JSONObject card) throws Exception { synchronized (recordsLock) { return engine.importCard(card); } }
                public byte[] prove(boolean d, String peer, byte[] a, byte[] b) throws Exception {
                    synchronized (recordsLock) { return engine.proveNearby(d, peer, a, b); }
                }
                public void verify(boolean d, String peer, byte[] a, byte[] b, byte[] proof, boolean enrolling) throws Exception {
                    synchronized (recordsLock) { engine.verifyNearby(d, peer, a, b, proof, enrolling); }
                }
                public void authorizeSend(String peer) throws Exception { synchronized (recordsLock) { engine.authorizeTransport(peer); } }
                public void receive(String peer, JSONObject envelope) throws Exception {
                    synchronized (recordsLock) {
                        try { engine.receive(envelope); }
                        catch (Exception failure) { receiveFailure = failure; throw failure; }
                    }
                }
                public void status(String text) { /* Do not log envelopes or synthetic message contents. */ }
            });
            if (dialer) link.connect(device, true); else link.listen(true);
            status("nearbyStage", "listening-or-connecting");
            await(() -> link.connectedPeer() != null, 30000, "RFCOMM enrollment handshake failed");
            String peer = link.connectedPeer(), code;
            synchronized (recordsLock) { code = Bytes.safetyCode(engine.id(), peer); }
            // Public synthetic fingerprint: host compares both codes before granting either approval.
            status("syntheticSafetyCode", code);
            await(() -> approval.exists() && code.equals(new String(Files.readAllBytes(approval.toPath()), java.nio.charset.StandardCharsets.UTF_8).trim()),
                60000, "Host comparison/approval not received");
            Files.delete(approval.toPath());
            synchronized (recordsLock) {
                engine.verify(peer, code);
            }
            status("nearbyStage", "verified");
            await(() -> approval.exists(), 30000, "Host transfer barrier not released");
            Files.delete(approval.toPath());
            synchronized (recordsLock) {
                engine.sendDeviceRoster(peer);
                engine.sendText(peer, "Synthetic " + role + " text", 3600);
                engine.sendFile(peer, "synthetic.bin", Bytes.utf8("Synthetic " + role + " attachment"), 3600);
            }
            Set<String> sent = new HashSet<>();
            long deadline = SystemClock.elapsedRealtime() + 45000;
            boolean duplicateSent = false;
            while (SystemClock.elapsedRealtime() < deadline) {
                if (receiveFailure != null) throw new AssertionError("Incoming processing failed", receiveFailure);
                List<JSONObject> queue;
                synchronized (recordsLock) { queue = engine.outbox(); }
                for (JSONObject queued : queue) {
                    JSONObject envelope = queued.getJSONObject("envelope");
                    String id = envelope.getString("id");
                    if (!sent.add(id)) continue;
                    link.sendAsync(peer, envelope).get(15, TimeUnit.SECONDS);
                    if (!duplicateSent && !queued.optBoolean("receipt")) {
                        Thread.sleep(100); // Let the bounded write queue retire its completed entry.
                        link.sendAsync(peer, new JSONObject(envelope.toString())).get(15, TimeUnit.SECONDS);
                        duplicateSent = true;
                    }
                    synchronized (recordsLock) { engine.transported(id, false); }
                }
                boolean complete;
                synchronized (recordsLock) {
                    List<JSONObject> messages = engine.messages(peer);
                    long incoming = messages.stream().filter(m -> !m.optBoolean("outgoing")).count();
                    long delivered = messages.stream().filter(m -> m.optBoolean("outgoing") && "Entregado".equals(m.optString("status"))).count();
                    require(incoming <= 2, "Duplicate displayed more than once");
                    complete = incoming == 2 && delivered == 2 && engine.get("device-roster", peer) != null;
                    if (complete) {
                        String other = dialer ? "listener" : "dialer";
                        for (JSONObject m : messages) if (!m.optBoolean("outgoing")) {
                            if ("text".equals(m.getString("kind")))
                                require(("Synthetic " + other + " text").equals(m.getString("text")), "Text mismatch");
                            else require(Arrays.equals(Bytes.utf8("Synthetic " + other + " attachment"),
                                Bytes.unb64(m.getString("data"))), "Attachment mismatch");
                        }
                    }
                }
                if (complete) {
                    Thread.sleep(1500); // Keep the reader available for the peer's final receipt checks.
                    status("nearbyResult", "PASS: RFCOMM, challenge, host verification, authenticated device roster, bidirectional text/attachment, duplicate, receipts");
                    return;
                }
                Thread.sleep(100);
            }
            throw new AssertionError("RFCOMM delivery and receipt deadline expired");
        } catch (Exception | AssertionError failure) {
            status("nearbyResult", "FAIL: " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
            throw failure;
        } finally {
            if (link != null) link.close();
            approval.delete();
        }
    }
}
