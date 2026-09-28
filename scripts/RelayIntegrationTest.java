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
    /** Gate-aware synthetic memory adapter; emphatically not SQLite durability. */
    private static final class EmergencyRecords implements app.umbra.data.Records {
        private final MemoryRecords records=new MemoryRecords();private final AccessGate gate=new AccessGate();
        EmergencyRecords(){gate.unlock();}
        public EmergencyLock emergency(){return gate.emergency();}
        public void onInvalidation(Runnable callback){gate.onInvalidation(callback);}
        public Runnable authorization(){var lease=gate.enter();return ()->gate.check(lease);}
        public synchronized byte[] get(String bucket,String key){gate.requireUnlocked();return records.get(bucket,key);}
        public synchronized void put(String bucket,String key,byte[] value){gate.requireUnlocked();records.put(bucket,key,value);}
        public synchronized void remove(String bucket,String key){gate.requireUnlocked();records.remove(bucket,key);}
        public synchronized List<String> keys(String bucket){gate.requireUnlocked();return records.keys(bucket);}
        public synchronized <T> T transaction(Work<T> work) throws Exception {
            var lease=gate.enter();return records.transaction(()->{T result=work.run();return gate.commit(lease,()->result);});
        }
    }
    public static void main(String[] args) throws Exception {
        String base = args[0]; Path exchange = Path.of(args[1]);
        String[] invitations = Files.readString(exchange.resolve("invitations")).split("\n");
        MemoryRecords aStore = new MemoryRecords(); EmergencyRecords bStore = new EmergencyRecords();
        Engine alice = new Engine(aStore), bob = new Engine(bStore);
        alice.initialize("Synthetic Alice"); bob.initialize("Synthetic Bob");
        AdmissionLab.provision(alice,exchange,"admission-a",Files.readString(exchange.resolve("admission-realm")));
        AdmissionLab.provision(bob,exchange,"admission-b",Files.readString(exchange.resolve("admission-realm")));
        alice.connectivity().vaultUnlocked(); alice.connectivity().connect(base,true);
        bob.connectivity().vaultUnlocked(); bob.connectivity().connect(args[2],true);
        JSONObject aProfile = alice.profile(), bProfile = bob.profile();
        PairingService inviter = new PairingService(aStore), joiner = new PairingService(bStore);
        try (RelayClient client = new RelayClient(base, () -> true, alice.admission())) {
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
            // A separate hostile TLS fixture withholds a PUBLIC realm response body.
            // No unadmitted private API is opened to exercise cancellation.
            ExecutorService pendingExecutor = Executors.newSingleThreadExecutor();
            try {
                for(int attempt=0;attempt<8;attempt++) {
                    bob.connectivity().disconnect();
                    bob.connectivity().connect(args[2],true); // Fresh explicit synthetic consent for each cancellation sample.
                    Files.deleteIfExists(exchange.resolve("pending-response"));
                    CountDownLatch betweenReads = new CountDownLatch(1), resumeRead = new CountDownLatch(1);
                    boolean pauseBetweenReads = attempt % 2 == 0;
                    try (RelayClient pendingClient = new RelayClient(args[2],()-> {
                        if(pauseBetweenReads && Files.exists(exchange.resolve("pending-response"))) {
                            betweenReads.countDown();
                            try { if(!resumeRead.await(15,TimeUnit.SECONDS)) throw new IllegalStateException("Test read barrier expired"); }
                            catch(InterruptedException stopped) {Thread.currentThread().interrupt();return false;}
                        }
                        return true;
                    },bob.admission())) {
                        Future<Boolean> failed = pendingExecutor.submit(() -> {
                            try { pendingClient.publicRealm(); return false; }
                            catch (Exception expected) { return true; }
                        });
                        long startedDeadline = System.nanoTime() + 10_000_000_000L;
                        while (!Files.exists(exchange.resolve("pending-response"))) {
                            if (System.nanoTime() > startedDeadline) throw new AssertionError("hostile response did not start");
                            Thread.sleep(10);
                        }
                        if(pauseBetweenReads && !betweenReads.await(10,TimeUnit.SECONDS))
                            throw new AssertionError("TLS between-read boundary not reached");
                        long closing = System.nanoTime();
                        try {pendingClient.close();} finally {resumeRead.countDown();}
                        require(System.nanoTime() - closing < 2_000_000_000L, "locking cancels a pending HTTPS read promptly (sample "+attempt+")");
                        require(failed.get(2, TimeUnit.SECONDS), "cancelled HTTPS read never reports success");
                    }
                }
            } finally { pendingExecutor.shutdownNow(); }
            // A cancellation wrapper must not bypass HTTPS endpoint identity. The lab
            // certificate covers localhost, deliberately NOT the 127.0.0.1 IP literal.
            bob.connectivity().disconnect();
            String wrongName="https://127.0.0.1:"+java.net.URI.create(args[2]).getPort();
            bob.connectivity().connect(wrongName,true);
            try(RelayClient invalidCertificate=new RelayClient(wrongName,()->true,bob.admission())) {
                try {invalidCertificate.publicRealm();throw new AssertionError("Mismatched TLS identity accepted");}
                catch(javax.net.ssl.SSLHandshakeException expected) {require(true,"cancellable HTTPS still rejects a wrong certificate name");}
            }
            // Same verified TLS server, now exercise the domain emergency operation while
            // its body is actually withheld. This is real HTTPS, not a mock connection.
            Files.delete(exchange.resolve("pending-response"));
            bob.connectivity().disconnect();
            require(bob.connectivity().getConnectivityState()==app.umbra.connectivity.ConnectivityService.State.UNLOCKED_OFFLINE,
                "explicit disconnect precedes a new synthetic network session");
            bob.connectivity().connect(args[2],true); // New explicit synthetic owner action, never automatic retry.
            ExecutorService emergencyWorker=Executors.newSingleThreadExecutor();
            try(RelayClient pendingClient=new RelayClient(args[2],()->true,bob.admission())) {
                Future<Boolean> rejected=emergencyWorker.submit(()->{
                    try {pendingClient.publicRealm();return false;}catch(Exception expected){return true;}
                });
                long emergencyDeadline=System.nanoTime()+10_000_000_000L;
                while(!Files.exists(exchange.resolve("pending-response"))) {
                    if(System.nanoTime()>emergencyDeadline)throw new AssertionError("Emergency HTTPS request never reached server");
                    Thread.sleep(10);
                }
                long requested=System.nanoTime();var result=bob.emergencyLock();
                require(System.nanoTime()-requested<1_000_000_000L,"emergency request does not wait for the withheld HTTPS body");
                require(result.invalidatedNanos()>=result.requestedNanos(),"emergency authorization barrier recorded");
                require(rejected.get(2,TimeUnit.SECONDS),"emergency HTTPS response cannot become a success");
                emergencyDeadline=System.nanoTime()+5_000_000_000L;
                while(bob.emergency().status().state()==EmergencyLock.State.CLOSING && System.nanoTime()<emergencyDeadline)Thread.sleep(10);
                require(bob.emergency().status().state()==EmergencyLock.State.CLOSED,"emergency confirms all HTTP resources closed without server response");
                rejects(pendingClient::publicRealm,"old HTTPS client remains unusable after emergency");
            } finally {emergencyWorker.shutdownNow();require(emergencyWorker.awaitTermination(2,TimeUnit.SECONDS),"emergency HTTPS worker terminated");}
            client.unregister(bProfile);
            rejectsHttp(() -> client.poll(bProfile, 0), 401, "revoked read capability rejected");
            rejectsHttp(() -> client.send(bobRoute, wire), 401, "revoked write capability rejected");
        }
        DeviceRelayIntegration.run(base, invitations, exchange);
        AdmissionRelayIntegration.run(base,invitations,exchange);
        require(!Files.exists(exchange.resolve("server-failed")), "isolated relay remained healthy");
        System.out.println(checks + " real HTTPS relay integration checks passed; no Android Keystore or Bluetooth exercised.");
    }
}
