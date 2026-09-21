package app.umbra;

import app.umbra.core.*;
import app.umbra.crypto.Engine;
import app.umbra.pairing.PairingService;
import app.umbra.transport.RelayClient;
import org.json.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Real Engine + libsignal + HTTPS RelayClient; only the local Records adapter is synthetic. */
public final class RelayIntegrationTest {
    private static int checks;
    private static void require(boolean result, String label) {
        if (!result) throw new AssertionError(label);
        checks++; System.out.println("PASS " + label);
    }
    @FunctionalInterface interface Operation { void run() throws Exception; }
    private static void rejects(Operation operation, String label) throws Exception {
        try { operation.run(); } catch (Exception expected) { require(true, label); return; }
        throw new AssertionError(label);
    }
    private static void rejectsHttp(Operation operation, int status, String label) throws Exception {
        try { operation.run(); }
        catch (java.io.IOException expected) {
            var http = java.util.regex.Pattern.compile("HTTP ([0-9]{3})\\)").matcher(
                    Objects.toString(expected.getMessage(), ""));
            String observed = http.find() ? http.group(1) : "non-HTTP failure";
            require(observed.equals(Integer.toString(status)),
                    label + " (expected " + status + ", observed " + observed + ")");
            return;
        }
        throw new AssertionError(label);
    }
    public static void main(String[] args) throws Exception {
        String base = args[0]; Path exchange = Path.of(args[1]);
        String[] invitations = Files.readString(exchange.resolve("invitations")).split("\n");
        MemoryRecords aStore = new MemoryRecords(), bStore = new MemoryRecords();
        Engine alice = new Engine(aStore), bob = new Engine(bStore);
        alice.initialize("Synthetic Alice"); bob.initialize("Synthetic Bob");
        JSONObject aProfile = alice.profile(), bProfile = bob.profile();
        PairingService inviter = new PairingService(aStore), joiner = new PairingService(bStore);
        try (RelayClient client = new RelayClient(base)) {
            client.register(aProfile, invitations[0]); client.register(bProfile, invitations[1]);
            client.register(aProfile, invitations[0]);
            String invite = inviter.createInvitation(3600), request = joiner.request(invite);
            client.registerPairing(aProfile, inviter.relayRegistration(invite));
            client.claimPairing(invite, request); client.claimPairing(invite, request);
            rejectsHttp(() -> client.claimPairing(invite, request + "altered"), 409, "pairing claim atomically binds the exact transcript");
            String ack = inviter.accept(request);
            require(ack.equals(inviter.accept(request)), "lost pairing acknowledgement retries immutable transcript");
            require(joiner.complete(ack).equals(alice.id()), "two identities pair without phone or email");
            require(alice.trustState(bob.id()) == Engine.TrustState.UNVERIFIED, "pairing does not claim human verification");
            rejects(() -> alice.sendText(bob.id(), "synthetic", 3600), "direct Engine rejects unverified text");
            rejects(() -> alice.sendFile(bob.id(), "synthetic.txt", new byte[]{1}, 3600), "direct Engine rejects unverified attachments");
            String code = Bytes.safetyCode(alice.id(), bob.id());
            require(code.equals(Bytes.safetyCode(bob.id(), alice.id())), "both identities derive the same verification fingerprint");
            alice.verify(bob.id(), code); bob.verify(alice.id(), code);
            String revoke = inviter.revoke(PairingService.invitationId(invite));
            client.revokePairing(PairingService.invitationId(invite), revoke);
            rejectsHttp(() -> client.claimPairing(invite, request), 403, "revocation rejects subsequent pairing claims");
            JSONObject bobRoute = alice.contact(bob.id()).getJSONObject("card");
            JSONObject aliceRoute = bob.contact(alice.id()).getJSONObject("card");
            Engine other = new Engine(new MemoryRecords()); other.initialize("Other");
            rejectsHttp(() -> client.register(other.profile(), invitations[0]), 403, "invitation cannot be reused by another mailbox");
            JSONObject unauthorized = new JSONObject(bProfile.toString()).put("read", aProfile.getString("read"));
            rejectsHttp(() -> client.poll(unauthorized, 0), 401, "mailbox read capability isolation");
            for (int i = 0; i < 7; i++) alice.sendText(bob.id(), "synthetic-" + i, 3600);
            List<JSONObject> queued = alice.outbox();
            // Reorder delivery, duplicate requests and restart both Engine and relay with queued ciphertext.
            for (int i = queued.size() - 1; i >= 0; i--) {
                JSONObject envelope = queued.get(i).getJSONObject("envelope");
                client.send(bobRoute, envelope); client.send(bobRoute, envelope);
            }
            String before = queued.get(0).getJSONObject("envelope").toString();
            Engine restarted = new Engine(aStore);
            require(before.equals(restarted.outbox().get(0).getJSONObject("envelope").toString()), "engine restart preserves queued ciphertext");
            Files.writeString(exchange.resolve("restart-request"), "restart");
            long deadline = System.nanoTime() + 30_000_000_000L;
            while (!Files.exists(exchange.resolve("restart-done"))) {
                if (System.nanoTime() > deadline) throw new AssertionError("relay restart timed out");
                Thread.sleep(50);
            }
            JSONObject first = client.poll(bProfile, 0);
            require(first.getJSONArray("messages").length() == 5 && first.getBoolean("more"), "restart retains queue and five-envelope pagination");
            JSONObject second = client.poll(bProfile, first.getLong("next_cursor"));
            require(second.getJSONArray("messages").length() == 2 && !second.getBoolean("more"), "cursor reaches remaining envelopes");
            List<JSONObject> delivery = new ArrayList<>();
            for (JSONObject page : List.of(first, second)) {
                JSONArray messages = page.getJSONArray("messages");
                for (int i = 0; i < messages.length(); i++) delivery.add(messages.getJSONObject(i));
            }
            JSONObject wire = delivery.get(0);
            JSONObject tampered = new JSONObject(wire.toString());
            byte[] ciphertext = Bytes.unb64(tampered.getString("ct")); ciphertext[ciphertext.length - 1] ^= 1;
            tampered.put("ct", Bytes.b64(ciphertext));
            rejects(() -> bob.receive(tampered), "modified ciphertext rejected before persistence");
            JSONObject wrong = new JSONObject(wire.toString()).put("to", "f".repeat(64));
            rejects(() -> bob.receive(wrong), "wrong recipient rejected");
            rejectsHttp(() -> client.send(bobRoute, new JSONObject(wire.toString()).put("ct", "AAAA")), 422, "relay rejects truncated ciphertext");
            rejectsHttp(() -> client.send(bobRoute, new JSONObject(wire.toString()).put("ct", Bytes.b64(new byte[720001]))), 422, "relay rejects oversized ciphertext");
            // HTTPS scheduling can cross a wall-clock second. Exact MAX_TTL/+1
            // boundaries are tested with a controlled relay clock; this network
            // rejection stays invalid for the entire bounded request timeout.
            rejectsHttp(() -> client.send(bobRoute, new JSONObject(wire.toString()).put("expires", Bytes.now() + 604800 + 3600)), 400, "relay rejects excessive TTL");
            for (JSONObject envelope : delivery) {
                bob.receive(envelope); bob.receive(envelope);
                // Acknowledge only after Engine's transaction returned successfully.
                client.acknowledge(bProfile, envelope.getString("id"));
            }
            require(bob.messages(alice.id()).size() == 7, "out-of-order real Signal messages persisted once");
            require(client.poll(bProfile, 0).getJSONArray("messages").isEmpty(), "acknowledged messages leave the relay queue");
            for (JSONObject entry : bob.outbox()) client.send(aliceRoute, entry.getJSONObject("envelope"));
            long cursor = 0;
            while (true) {
                JSONObject page = client.poll(aProfile, cursor); JSONArray receipts = page.getJSONArray("messages");
                for (int i = 0; i < receipts.length(); i++) {
                    JSONObject receipt = receipts.getJSONObject(i); restarted.receive(receipt);
                    client.acknowledge(aProfile, receipt.getString("id"));
                }
                cursor = page.getLong("next_cursor"); if (!page.getBoolean("more")) break;
            }
            require(restarted.outbox().isEmpty(), "authenticated receipts clear sender outbox");
            client.send(bobRoute, wire);
            require(client.poll(bProfile, 0).getJSONArray("messages").isEmpty(), "retry after lost acknowledgement does not resurrect message");
            try (RelayClient locked = new RelayClient(base, () -> false)) {
                rejects(() -> locked.poll(bProfile, 0), "locked policy prevents network operation");
            }
            // A separate hostile TLS fixture sends headers but withholds its body.
            ExecutorService pendingExecutor = Executors.newSingleThreadExecutor();
            try (RelayClient pendingClient = new RelayClient(args[2])) {
                Future<Boolean> failed = pendingExecutor.submit(() -> {
                    try { pendingClient.poll(bProfile, 0); return false; }
                    catch (Exception expected) { return true; }
                });
                long startedDeadline = System.nanoTime() + 10_000_000_000L;
                while (!Files.exists(exchange.resolve("pending-response"))) {
                    if (System.nanoTime() > startedDeadline) throw new AssertionError("hostile response did not start");
                    Thread.sleep(10);
                }
                long closing = System.nanoTime();
                pendingClient.close();
                require(System.nanoTime() - closing < 2_000_000_000L, "locking cancels a pending HTTPS read promptly");
                require(failed.get(2, TimeUnit.SECONDS), "cancelled HTTPS read never reports success");
            } finally { pendingExecutor.shutdownNow(); }
            client.unregister(bProfile);
            rejectsHttp(() -> client.poll(bProfile, 0), 401, "revoked read capability rejected");
            rejectsHttp(() -> client.send(bobRoute, wire), 401, "revoked write capability rejected");
        }
        DeviceRelayIntegration.run(base, invitations);
        require(!Files.exists(exchange.resolve("server-failed")), "isolated relay remained healthy");
        System.out.println(checks + " real HTTPS relay integration checks passed; no Android Keystore or Bluetooth exercised.");
    }
}
