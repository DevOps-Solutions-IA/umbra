package app.umbra;

import app.umbra.core.Bytes;
import app.umbra.crypto.*;
import app.umbra.devices.*;
import java.util.*;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import static app.umbra.DeviceLinkingTest.*;

public class DeviceAdversarialTest {
    private static String[] fields(String value) {
        return Bytes.text(Base64.getUrlDecoder().decode(value.substring(value.lastIndexOf(":")+1).split("\\.")[0])).split("\n",-1);
    }
    private static String signed(Device d,String kind,String[] f) {
        byte[] raw=Bytes.utf8(String.join("\n",f));
        var encoder=Base64.getUrlEncoder().withoutPadding();
        return "umbra:device:"+kind+":1:"+encoder.encodeToString(raw)+"."+
            encoder.encodeToString(new SignalStore(d.db).getIdentityKeyPair().getPrivateKey().calculateSignature(raw));
    }
    private static String expiring(Device a,Device n,long expiry) throws Exception {
        String challenge=a.d.challenge(n.d.publicKey(),600); String[] f=fields(challenge); f[5]=""+expiry;
        String shortened=signed(a,"challenge",f);
        a.db.transaction(() -> {
            JSONObject row=a.e.get("device-issued",f[3]); row.put("challenge",shortened).put("expires",expiry);
            a.db.put("device-issued",f[3],Bytes.utf8(row.toString())); return null;
        }); return shortened;
    }
    @Test(timeout=15000) public void approvalExpiredWhileWaitingForTransactionDoesNotConsume() throws Exception {
        Device a=new Device("A1"),n=new Device("A2"); a.d.migrate(); long expiry=Bytes.now()+3;
        String response=n.d.respond(n.d.reviewChallenge(expiring(a,n,expiry)),true); var consent=a.d.reviewResponse(response);
        String before=a.d.roster(a.e.id());
        a.db.before=() -> { while(Bytes.now()<expiry) Thread.sleep(20); return null; };
        assertThrows(SecurityException.class,() -> a.d.approve(consent,true)); assertEquals(before,a.d.roster(a.e.id()));
    }
    @Test(timeout=15000) public void responseExpiredWhileWaitingForTransactionDoesNotCreatePrekeys() throws Exception {
        Device a=new Device("A1"),n=new Device("A2"); a.d.migrate(); long expiry=Bytes.now()+3;
        var consent=n.d.reviewChallenge(expiring(a,n,expiry));
        n.db.before=() -> { while(Bytes.now()<expiry) Thread.sleep(20); return null; };
        assertThrows(SecurityException.class,() -> n.d.respond(consent,true)); assertTrue(n.db.keys("prekey").isEmpty());
    }
    @Test public void validSignerCannotSubstituteAnotherDevicesCard() throws Exception {
        Device a=new Device("A1"),n=new Device("A2"),x=new Device("X"); a.d.migrate();
        String response=n.d.respond(n.d.reviewChallenge(a.d.challenge(n.d.publicKey(),600)),true);
        String[] f=fields(response); f[3]=Base64.getUrlEncoder().withoutPadding().encodeToString(Bytes.utf8(x.e.createCard().toString()));
        String altered=signed(n,"response",f);
        assertThrows(SecurityException.class,() -> a.d.approve(a.d.reviewResponse(altered),true)); assertNull(a.e.contact(x.e.id()));
        assertEquals(1,DeviceRoster.parse(a.d.roster(a.e.id())).members.size());
    }
    @Test public void signedNewVersionCannotReactivateOrRemoveTombstone() throws Exception {
        Device a=new Device("A1"),n=new Device("A2"),b=new Device("B1"); pair(a,b); a.d.migrate(); b.d.migrate(); link(a,n);
        String before=a.d.roster(a.e.id()); approveSet(b,a,n); b.d.apply(a.d.revoke(n.e.id()));
        String[] f=fields(before); f[2]="4";
        assertThrows(SecurityException.class,() -> b.d.apply(signed(a,"roster",f)));
        String[] missing=fields(a.d.roster(a.e.id())); missing[2]="5";
        missing[5]=Arrays.stream(missing[5].split(",")).filter(s -> !s.startsWith(n.d.publicKey()+":")).findFirst().orElseThrow();
        assertThrows(SecurityException.class,() -> b.d.apply(signed(a,"roster",missing)));
    }
    @Test public void fanoutFailureRollsBackEverySessionAndDelivery() throws Exception {
        Device a=new Device("A1"),n=new Device("A2"),b=new Device("B1"); pair(a,b); a.d.migrate(); b.d.migrate(); link(a,n); approveSet(b,a,n);
        b.db.remainingOutboxWrites=1;
        assertThrows(IllegalStateException.class,() -> b.e.sendIdentityText(a.e.id(),"synthetic atomic fanout",600));
        assertTrue(b.e.outbox().isEmpty()); assertTrue(b.db.keys("message").isEmpty()); assertTrue(b.db.keys("session").isEmpty());
        b.db.remainingOutboxWrites=-1; assertNotNull(b.e.sendIdentityText(a.e.id(),"synthetic retry",600)); assertEquals(2,b.e.outbox().size());
    }
    @Test public void partialMembershipLossFailsClosedWithoutReinitializing() throws Exception {
        Device a=new Device("A1"),n=new Device("A2"); a.d.migrate(); link(a,n); a.d.revoke(n.e.id());
        a.db.transaction(() -> { a.db.remove("device-index",n.e.id()); return null; });
        assertThrows(SecurityException.class,() -> a.e.verify(n.e.id(),Bytes.safetyCode(a.e.id(),n.e.id())));
        a.db.transaction(() -> { a.db.remove("meta","device-affiliation"); return null; });
        assertThrows(SecurityException.class,() -> a.e.initialized()); assertThrows(SecurityException.class,() -> a.e.initialize("replacement"));
    }
    @Test public void importedRevocationBlocksNearbyProofAndWrites() throws Exception {
        Device a=new Device("A1"),n=new Device("A2"); a.d.migrate(); link(a,n);
        // Queue a current signed roster; after local revocation, a signed update is imported on A2.
        String before=a.d.roster(a.e.id()); String revoked=a.d.revoke(n.e.id()); n.d.apply(revoked);
        assertThrows(SecurityException.class,() -> n.d.apply(before));
        assertThrows(SecurityException.class,() -> n.e.proveNearby(true,a.e.id(),new byte[32],new byte[32]));
        assertThrows(SecurityException.class,() -> n.e.authorizeTransport(a.e.id()));
    }
    @Test public void directDelegationApiRejectsForgedProofAndKeepsCapability() throws Exception {
        Device a=new Device("A1"),n=new Device("A2"); a.d.migrate(); link(a,n);
        JSONObject grant=n.d.relayDelegation();
        a.db.transaction(() -> { a.d.receiveRelayDelegation(n.e.id(),grant); return null; });
        JSONObject changed=new JSONObject(grant.toString()).put("token",Bytes.token());
        assertThrows(SecurityException.class,() -> a.db.transaction(() -> { a.d.receiveRelayDelegation(n.e.id(),changed); return null; }));
        a.d.revoke(n.e.id()); assertEquals(grant.getString("token"),a.d.pendingRelayRevocations().get(0).getString("token"));
        assertThrows(SecurityException.class,() -> a.db.transaction(() -> { a.d.receiveRelayDelegation(n.e.id(),grant); return null; }));
        n.d.apply(a.d.roster(a.e.id()));
        assertThrows(SecurityException.class,() -> n.e.createCard());
        assertThrows(SecurityException.class,() -> n.e.authorizeTransportSelf());
    }
    @Test(timeout=30000) public void concurrentMembershipChangeAndFanoutNeverLeaveRevokedDelivery() throws Exception {
        Device a=new Device("A1"),n=new Device("A2"),b=new Device("B1"); pair(a,b); a.d.migrate(); b.d.migrate(); link(a,n); approveSet(b,a,n);
        String revoked=a.d.revoke(n.e.id());
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var start=new java.util.concurrent.CountDownLatch(1);
        try {
            var sending=pool.submit(() -> { start.await(); return b.e.sendIdentityText(a.e.id(),"synthetic concurrent",600); });
            var revoking=pool.submit(() -> { start.await(); b.d.apply(revoked); return true; });
            start.countDown(); assertNotNull(sending.get(20,java.util.concurrent.TimeUnit.SECONDS)); assertTrue(revoking.get(20,java.util.concurrent.TimeUnit.SECONDS));
            String id=n.e.id(); assertTrue(b.e.outbox().stream().noneMatch(o -> o.optString("peer").equals(id)));
            assertThrows(SecurityException.class,() -> b.e.authorizeTransport(id));
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS)); }
    }
}
