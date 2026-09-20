package app.umbra;

import app.umbra.core.AccessGate;
import app.umbra.core.Bytes;
import app.umbra.crypto.Engine;
import app.umbra.crypto.SignalStore;
import app.umbra.data.Records;
import app.umbra.devices.*;
import app.umbra.pairing.PairingService;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real Signal JNI and protocol state. Memory storage here is not Android durability evidence. */
public class DeviceLinkingTest {
    static final class Store implements Records {
        final MemoryRecords memory = new MemoryRecords(); final AccessGate gate = new AccessGate();
        String failBucket; Work<Void> before; int remainingOutboxWrites = -1;
        Store() { gate.unlock(); }
        public synchronized byte[] get(String b, String k) { gate.requireUnlocked(); return memory.get(b,k); }
        public synchronized void put(String b, String k, byte[] v) {
            gate.requireUnlocked();
            if (b.equals("outbox") && remainingOutboxWrites >= 0 && remainingOutboxWrites-- == 0) throw new IllegalStateException("Synthetic second delivery failure");
            if (b.equals(failBucket)) throw new IllegalStateException("Synthetic disk error"); memory.put(b,k,v);
        }
        public synchronized void remove(String b, String k) { gate.requireUnlocked(); memory.remove(b,k); }
        public synchronized List<String> keys(String b) { gate.requireUnlocked(); return memory.keys(b); }
        public Runnable authorization() { var lease = gate.enter(); return () -> gate.check(lease); }
        public synchronized <T> T transaction(Work<T> work) throws Exception {
            var lease = gate.enter(); Work<Void> delayed = before; before = null; if (delayed != null) delayed.run();
            return memory.transaction(() -> { T result = work.run(); gate.check(lease); return result; });
        }
    }
    static final class Device {
        final Store db = new Store(); final Engine e = new Engine(db); final DeviceService d = new DeviceService(db);
        Device(String name) throws Exception { e.initialize(name); }
    }
    static void pair(Device a, Device b) throws Exception {
        a.e.importCard(b.e.createCard()); b.e.importCard(a.e.createCard());
        a.e.verify(b.e.id(), Bytes.safetyCode(a.e.id(),b.e.id())); b.e.verify(a.e.id(), Bytes.safetyCode(a.e.id(),b.e.id()));
    }
    static String[] link(Device a, Device b) throws Exception {
        String challenge = a.d.challenge(b.d.publicKey(),600);
        String response = b.d.respond(b.d.reviewChallenge(challenge),true);
        String approval = a.d.approve(a.d.reviewResponse(response),true);
        b.d.complete(approval); return new String[]{challenge,response,approval};
    }
    static void approveSet(Device viewer, Device owner, Device... devices) throws Exception {
        String roster = owner.d.roster(owner.e.id()); viewer.d.apply(roster);
        for (Device device : devices) viewer.e.importCard(device.e.createCard());
        var consent = viewer.d.reviewRoster(roster); viewer.d.approveRoster(consent,consent.fingerprint(),true);
    }
    static void deliver(Device sender, Device receiver) throws Exception {
        for (JSONObject queued : sender.e.outbox()) if (queued.getString("peer").equals(receiver.e.id()))
            receiver.e.receive(queued.getJSONObject("envelope"));
    }
    @Test public void a1a2b1SeparateSessionsFanoutPartialFailureAndRevocation() throws Exception {
        Device a1 = new Device("A1"), a2 = new Device("A2"), b1 = new Device("B1");
        pair(a1,b1); a1.e.sendText(b1.e.id(),"synthetic baseline",600); deliver(a1,b1); deliver(b1,a1);
        byte[] originalKey = a1.db.get("meta","identity");
        a1.d.migrate(); b1.d.migrate(); String[] ceremony = link(a1,a2);
        assertArrayEquals(originalKey,a1.db.get("meta","identity"));
        assertFalse(Arrays.equals(originalKey,a2.db.get("meta","identity")));
        approveSet(b1,a1,a2); pair(a2,b1); approveSet(a1,b1); approveSet(a2,b1);
        String logical = b1.e.sendIdentityText(a1.e.id(),"synthetic fanout",600);
        List<JSONObject> fanout = new ArrayList<>();
        for (JSONObject item : b1.e.outbox()) if (!item.getBoolean("receipt")) fanout.add(item);
        assertEquals(2,fanout.size());
        assertNotEquals(fanout.get(0).getJSONObject("envelope").getString("ct"),fanout.get(1).getJSONObject("envelope").getString("ct"));
        deliver(b1,a1); deliver(a1,b1); // A2 is disconnected; its immutable delivery remains.
        JSONObject pending = b1.e.outbox().stream().filter(o -> o.optString("peer").equals(safeId(a2))).findFirst().orElseThrow();
        String ciphertext = pending.getJSONObject("envelope").getString("ct");
        b1.e.relayFailed(pending.getJSONObject("envelope").getString("id"));
        assertEquals(ciphertext,b1.e.get("outbox",pending.getJSONObject("envelope").getString("id")).getJSONObject("envelope").getString("ct"));
        deliver(b1,a2); deliver(a2,b1);
        assertEquals(logical,a2.e.messages(b1.e.id()).get(0).getString("logicalId"));
        a2.e.sendIdentityText(b1.e.id(),"synthetic return",600); deliver(a2,b1); deliver(b1,a2);
        a1.e.sendIdentityText(b1.e.id(),"synthetic A1 return",600); deliver(a1,b1); deliver(b1,a1);
        assertTrue(new SignalStore(b1.db).containsSession(new org.signal.libsignal.protocol.SignalProtocolAddress(a1.e.id(),1)));
        assertTrue(new SignalStore(b1.db).containsSession(new org.signal.libsignal.protocol.SignalProtocolAddress(a2.e.id(),1)));
        String old = a1.d.roster(a1.e.id()); b1.e.sendIdentityText(a1.e.id(),"queued before revocation",600);
        String revoked = a1.d.revoke(a2.e.id()); b1.d.apply(revoked); a2.d.apply(revoked);
        assertTrue(b1.e.outbox().stream().noneMatch(o -> o.optString("peer").equals(safeId(a2))));
        assertThrows(SecurityException.class,() -> b1.e.sendText(a2.e.id(),"blocked",600));
        assertThrows(SecurityException.class,() -> b1.e.authorizeTransport(a2.e.id()));
        assertThrows(SecurityException.class,() -> a2.e.sendText(b1.e.id(),"blocked",600));
        assertThrows(SecurityException.class,() -> b1.d.apply(old));
        assertThrows(SecurityException.class,() -> a1.d.approve(a1.d.reviewResponse(ceremony[1]),true));
        assertThrows(SecurityException.class,() -> new Engine(b1.db).verify(a2.e.id(),Bytes.safetyCode(b1.e.id(),a2.e.id())));
        b1.e.sendIdentityText(a1.e.id(),"only active recipient",600);
        assertTrue(b1.e.outbox().stream().noneMatch(o -> o.optString("peer").equals(safeId(a2))));
        assertFalse(new SignalStore(b1.db).containsSession(new org.signal.libsignal.protocol.SignalProtocolAddress(a2.e.id(),1)));
    }
    private static String safeId(Device d) { try { return d.e.id(); } catch(Exception e) { throw new AssertionError(e); } }
    @Test public void additionsRequireFullSetApprovalAndLegacyVerificationCannotBypass() throws Exception {
        Device a = new Device("A1"), b = new Device("B1"), n = new Device("A2"); pair(a,b); a.d.migrate(); b.d.migrate(); link(a,n);
        String roster = a.d.roster(a.e.id()); b.d.apply(roster); b.e.importCard(n.e.createCard());
        assertThrows(SecurityException.class,() -> b.e.verify(n.e.id(),Bytes.safetyCode(b.e.id(),n.e.id())));
        assertThrows(SecurityException.class,() -> b.e.sendIdentityText(a.e.id(),"not approved",600));
        var consent = b.d.reviewRoster(roster);
        assertThrows(SecurityException.class,() -> b.d.approveRoster(consent,"0".repeat(64),true));
        b.d.approveRoster(consent,consent.fingerprint(),true); assertNotNull(b.e.sendIdentityText(a.e.id(),"approved",600));
    }
    @Test public void lockAndUnlockInvalidateBothConsents() throws Exception {
        Device a = new Device("A1"), n = new Device("A2"); a.d.migrate(); String c = a.d.challenge(n.d.publicKey(),600);
        var joining = n.d.reviewChallenge(c); n.db.gate.lock(); n.db.gate.unlock();
        assertThrows(SecurityException.class,() -> n.d.respond(joining,true));
        String response = n.d.respond(n.d.reviewChallenge(c),true); var approving = a.d.reviewResponse(response);
        a.db.gate.lock(); a.db.gate.unlock(); assertThrows(SecurityException.class,() -> a.d.approve(approving,true));
        assertThrows(SecurityException.class,() -> a.d.approve(a.d.reviewResponse(response),false));
        assertEquals(1,DeviceRoster.parse(a.d.roster(a.e.id())).members.size());
    }
    @Test public void wrongPeerContactInvitationTamperingAndNoncanonicalInputsFail() throws Exception {
        Device a = new Device("A1"), n = new Device("A2"), x = new Device("Other"); a.d.migrate(); x.d.migrate();
        String c = a.d.challenge(n.d.publicKey(),600);
        assertThrows(SecurityException.class,() -> x.d.reviewChallenge(c));
        String response = n.d.respond(n.d.reviewChallenge(c),true);
        assertThrows(SecurityException.class,() -> x.d.reviewResponse(response));
        assertThrows(SecurityException.class,() -> n.d.reviewChallenge(new PairingService(a.db).createInvitation(600)));
        for (String bad : List.of(c+"=",c+"\n",c.replace(":1:",":2:"),"umbra:device:challenge:1:"+"A".repeat(32001)))
            assertThrows(SecurityException.class,() -> n.d.reviewChallenge(bad));
        String roster = a.d.roster(a.e.id());
        assertThrows(SecurityException.class,() -> DeviceRoster.parse(roster.substring(0,roster.length()-1)));
    }
    @Test public void atomicStorageFailureDoesNotConsumeOrAdvanceRoster() throws Exception {
        Device a = new Device("A1"), n = new Device("A2"); a.d.migrate(); String before = a.d.roster(a.e.id());
        String response = n.d.respond(n.d.reviewChallenge(a.d.challenge(n.d.publicKey(),600)),true);
        a.db.failBucket = "device-issued";
        assertThrows(IllegalStateException.class,() -> a.d.approve(a.d.reviewResponse(response),true));
        assertEquals(before,a.d.roster(a.e.id())); assertNull(a.e.contact(n.e.id()));
        a.db.failBucket = null; String approval = a.d.approve(a.d.reviewResponse(response),true);
        assertEquals(approval,a.d.approve(a.d.reviewResponse(response),true)); n.d.complete(approval);
        assertEquals(a.e.id(),new DeviceService(n.db).complete(approval));
    }
    @Test public void revokedAdministratorIsTerminalAndCannotRenew() throws Exception {
        Device a = new Device("A1"), b = new Device("B1"); pair(a,b); a.d.migrate(); b.d.migrate(); approveSet(b,a);
        String previous = a.d.roster(a.e.id()), retired = a.d.revoke(a.e.id()); b.d.apply(retired);
        assertThrows(SecurityException.class,() -> a.d.renew());
        assertThrows(SecurityException.class,() -> b.d.apply(previous));
        assertThrows(SecurityException.class,() -> b.e.sendText(a.e.id(),"retired",600));
    }
    @Test(timeout=30000) public void twoConcurrentCeremoniesForSameKeyAuthorizeOnlyOnce() throws Exception {
        Device a = new Device("A1"), n = new Device("A2"); a.d.migrate();
        String r1 = n.d.respond(n.d.reviewChallenge(a.d.challenge(n.d.publicKey(),600)),true);
        String r2 = n.d.respond(n.d.reviewChallenge(a.d.challenge(n.d.publicKey(),600)),true);
        var c1 = a.d.reviewResponse(r1); var c2 = a.d.reviewResponse(r2);
        ExecutorService pool = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (var c : List.of(c1,c2)) results.add(pool.submit(() -> { start.await(); try { a.d.approve(c,true); return true; } catch(SecurityException expected) { return false; } }));
            start.countDown(); int wins = 0; for (var f : results) if (f.get(20,TimeUnit.SECONDS)) wins++;
            assertEquals(1,wins); assertEquals(2,DeviceRoster.parse(a.d.roster(a.e.id())).members.size());
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS)); }
    }
    @Test public void failedRevocationRetainsOldStateThenRetryPersistsTombstone() throws Exception {
        Device a = new Device("A1"), n = new Device("A2"); a.d.migrate(); link(a,n);
        String before = a.d.roster(a.e.id()); a.db.failBucket = "device-roster";
        assertThrows(IllegalStateException.class,() -> a.d.revoke(n.e.id())); assertEquals(before,a.d.roster(a.e.id()));
        a.db.failBucket = null; a.d.revoke(n.e.id());
        assertThrows(SecurityException.class,() -> new DeviceService(a.db).challenge(n.d.publicKey(),600));
        assertThrows(SecurityException.class,() -> new Engine(a.db).sendFile(n.e.id(),"synthetic.txt",new byte[]{1},600));
    }
    @Test public void attachmentFanoutUsesIndependentDeliveriesAndReceipts() throws Exception {
        Device a=new Device("A1"),n=new Device("A2"),b=new Device("B1"); pair(a,b); a.d.migrate(); b.d.migrate(); link(a,n);
        approveSet(b,a,n); pair(n,b); approveSet(a,b); approveSet(n,b);
        byte[] bytes=new byte[]{0,1,2,3,4};
        String logical=b.e.sendIdentityFile(a.e.id(),"synthetic.bin",bytes,600);
        deliver(b,a); deliver(b,n); deliver(a,b); deliver(n,b);
        for(Device receiver:List.of(a,n)) {
            JSONObject file=receiver.e.messages(b.e.id()).get(0);
            assertEquals(logical,file.getString("logicalId")); assertArrayEquals(bytes,Bytes.unb64(file.getString("data")));
        }
        assertTrue(b.e.outbox().isEmpty());
    }
}
