package app.umbra;

import app.umbra.core.Bytes;
import app.umbra.crypto.Engine;
import app.umbra.location.*;
import app.umbra.protocol.Wire;
import org.json.*;
import org.junit.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

/** Synthetic coordinates only. Real Signal; memory rollback is not Android durability. */
public class LocationTest {
    static final class Pair {
        final DeviceLinkingTest.Device a=new DeviceLinkingTest.Device("Location A"), b=new DeviceLinkingTest.Device("Location B");
        final AtomicLong time=new AtomicLong(100000); final Engine sender=new Engine(a.db,time::get);
        Pair() throws Exception { DeviceLinkingTest.pair(a,b); a.d.migrate(); b.d.migrate(); DeviceLinkingTest.approveSet(a,b); DeviceLinkingTest.approveSet(b,a); }
        String live() throws Exception { return sender.locations().start(sender.locations().review(b.e.id(),LocationPayload.Mode.ZONE,900,true),true); }
        void update(String id) throws Exception { sender.locations().publish(id,12.345678,45.678912,3,Bytes.now(),"ANDROID_FINE"); time.addAndGet(15000); }
        List<JSONObject> envelopes() throws Exception { List<JSONObject> result=new ArrayList<>(); for(JSONObject row:sender.outbox()) result.add(row.getJSONObject("envelope")); return result; }
    }
    @Test public void manualNoProviderRoundTripAndImmutableRetry() throws Exception {
        Pair p=new Pair(); String id=p.sender.locations().manual(p.sender.locations().review(p.b.e.id(),LocationPayload.Mode.MANUAL,120,false),true,12.345678,45.678912);
        JSONObject envelope=p.envelopes().get(0); String cipher=envelope.getString("ct");
        p.b.e.receive(envelope); p.b.e.receive(new JSONObject(envelope.toString()));
        JSONObject row=p.b.e.locations().received(p.a.e.id()).get(0);
        assertEquals("POINT",row.getString("state")); assertEquals(id,row.getJSONObject("payload").getString("session"));
        assertEquals("MANUAL",row.getJSONObject("lastPoint").getString("source"));
        assertEquals(123456780,row.getJSONObject("lastPoint").getLong("latE7"));
        p.sender.relayFailed(envelope.getString("id")); assertEquals(cipher,p.envelopes().get(0).getString("ct"));
        for(JSONObject receipt:p.b.e.outbox()) p.sender.receive(receipt.getJSONObject("envelope"));
        assertTrue(p.sender.outbox().isEmpty());
        DeviceLinkingTest.Device wrong=new DeviceLinkingTest.Device("Wrong recipient");
        assertThrows(SecurityException.class,() -> wrong.e.receive(envelope));
    }
    @Test public void startUpdatesReorderingStopAndReplayCannotRevive() throws Exception {
        Pair p=new Pair(); String id=p.live(); JSONObject start=p.envelopes().get(0); p.update(id); JSONObject u1=p.envelopes().get(1); p.update(id); JSONObject u2=p.envelopes().get(1);
        p.b.e.receive(u2); p.b.e.receive(start); p.b.e.receive(u1); p.b.e.receive(u2);
        assertEquals(2,p.b.e.locations().received(p.a.e.id()).get(0).getJSONObject("payload").getLong("seq"));
        p.sender.locations().stop(id); JSONObject stop=p.envelopes().get(0); p.b.e.receive(stop); p.b.e.receive(u1);
        assertEquals("STOPPED",p.b.e.locations().received(p.a.e.id()).get(0).getString("state"));
        assertThrows(SecurityException.class,() -> p.update(id)); assertThrows(SecurityException.class,() -> p.sender.authorizeEnvelope(u2));
    }
    @Test public void approximationBeforeStorageAndEncryptionPolesAndAntimeridian() throws Exception {
        Pair p=new Pair(); String id=p.live(); p.update(id);
        for(String bucket:List.of("location-out","outbox","message")) for(String key:p.a.db.keys(bucket)) {
            String raw=Bytes.text(p.a.db.get(bucket,key)); assertFalse(raw.contains("123456780")); assertFalse(raw.contains("456789120"));
        }
        for(JSONObject e:p.envelopes()) p.b.e.receive(e);
        JSONObject q=p.b.e.locations().received(p.a.e.id()).get(0).getJSONObject("lastPoint");
        assertEquals(1000000,q.getLong("cellE7")); assertEquals(3000,q.getLong("sensorAccuracyMm")); assertNotEquals(123456780,q.getLong("latE7"));
        for(double lat:new double[]{-90,-89.99999,0,89.99999,90}) for(double lon:new double[]{-180,-179.99999,0,179.99999,180}) {
            JSONObject reduced=LocationPayload.point(lat,lon,4,Bytes.now(),LocationPayload.Mode.ZONE,"ANDROID_FINE");
            assertTrue(Math.abs(reduced.getLong("latE7"))<900000000L); assertTrue(Math.abs(reduced.getLong("lonE7"))<1800000000L);
        }
        assertEquals(LocationPayload.point(0,-180,1,1,LocationPayload.Mode.ZONE,"ANDROID_FINE").getLong("lonE7"),LocationPayload.point(0,180,1,1,LocationPayload.Mode.ZONE,"ANDROID_FINE").getLong("lonE7"));
        assertNotEquals(LocationPayload.point(0.0999999,0,1,1,LocationPayload.Mode.ZONE,"ANDROID_FINE").getLong("latE7"),LocationPayload.point(0.1,0,1,1,LocationPayload.Mode.ZONE,"ANDROID_FINE").getLong("latE7"));
    }
    @Test public void invalidCoordinatesTypesDuplicateFieldsUnknownKindsAndStaleFixReject() throws Exception {
        Pair p=new Pair(); String id=p.live();
        for(double bad:new double[]{Double.NaN,Double.POSITIVE_INFINITY,91,-91}) assertThrows(SecurityException.class,() -> p.sender.locations().publish(id,bad,0,1,Bytes.now(),"ANDROID_FINE"));
        assertThrows(SecurityException.class,() -> p.sender.locations().publish(id,0,0,1,Bytes.now()-121,"ANDROID_FINE"));
        p.update(id); JSONObject payload=new JSONObject(p.a.e.get("location-out",id).getJSONObject("payload").toString());
        payload.put("point",LocationPayload.point(1,2,1,Bytes.now(),LocationPayload.Mode.ZONE,"ANDROID_FINE"));
        JSONObject bad=new JSONObject(payload.toString()).put("type","CALL_OFFER"); assertThrows(SecurityException.class,() -> LocationPayload.validate(bad,Bytes.now()));
        payload.getJSONObject("point").put("latE7","10500000"); assertThrows(SecurityException.class,() -> LocationPayload.validate(payload,Bytes.now()));
        assertThrows(IllegalArgumentException.class,() -> Wire.parse(Bytes.utf8("{\"v\":1,\"v\":2}"),4096));
        var c=p.sender.locations().review(p.b.e.id(),LocationPayload.Mode.PRECISE,120,false); String precise=p.sender.locations().start(c,true);
        assertThrows(SecurityException.class,() -> p.sender.locations().publish(precise,0,0,3000,Bytes.now(),"ANDROID_COARSE"));
    }
    @Test public void directApiConsentWrongServiceDenialReuseAndOldLeaseReject() throws Exception {
        Pair p=new Pair(); var c=p.sender.locations().review(p.b.e.id(),LocationPayload.Mode.MANUAL,120,false);
        assertThrows(SecurityException.class,() -> p.sender.locations().manual(c,false,1,2));
        assertThrows(SecurityException.class,() -> p.a.e.locations().start(c,true));
        p.sender.locations().manual(c,true,1,2); assertThrows(SecurityException.class,() -> p.sender.locations().manual(c,true,1,2));
        assertThrows(SecurityException.class,() -> p.sender.enqueueLocation(null,new JSONObject()));
        var stale=p.sender.locations().review(p.b.e.id(),LocationPayload.Mode.ZONE,900,true); p.a.db.gate.lock(); p.a.db.gate.unlock();
        assertThrows(SecurityException.class,() -> p.sender.locations().start(stale,true));
        assertTrue(p.sender.outbox().isEmpty());
    }
    @Test public void lockedCallbacksTransportLeaseAndRestartDoNotResume() throws Exception {
        Pair p=new Pair(); String id=p.live(); p.update(id); JSONObject envelope=p.envelopes().get(1); var write=p.sender.deliveryAuthorization(envelope);
        p.a.db.gate.lock(); p.a.db.gate.unlock();
        assertThrows(SecurityException.class,write::run); assertThrows(SecurityException.class,() -> p.update(id));
        assertTrue(new Engine(p.a.db).outbox().isEmpty());
        assertEquals("INTERRUPTED",p.a.e.get("location-out",id).getString("state"));
    }
    @Test public void monotonicDeadlineBackwardClockRateAndQueueBound() throws Exception {
        Pair p=new Pair(); String id=p.live(); p.update(id);
        p.time.addAndGet(-15000); assertThrows(SecurityException.class,() -> p.update(id)); p.time.addAndGet(15000);
        for(int i=0;i<20;i++) p.update(id);
        assertEquals(2,p.sender.outbox().size()); // START plus latest UPDATE, never a trajectory backlog.
        p.time.addAndGet(900000); assertThrows(SecurityException.class,() -> p.update(id)); assertTrue(p.sender.outbox().isEmpty());
        assertEquals("EXPIRED",p.a.e.get("location-out",id).getString("state"));
        Pair back=new Pair(); String b=back.live(); back.time.set(0); assertThrows(SecurityException.class,() -> back.update(b));
    }
    @Test public void storageFailureRollsBackSignalAndStopStillCancelsWrites() throws Exception {
        Pair p=new Pair(); String id=p.live(); String before=p.envelopes().get(0).toString();
        p.a.db.failBucket="location-out"; assertThrows(IllegalStateException.class,() -> p.update(id));
        p.a.db.failBucket=null; assertEquals(before,p.envelopes().get(0).toString()); assertEquals(0,p.a.e.get("location-out",id).getJSONObject("payload").getLong("seq"));
        p.update(id); JSONObject pending=p.envelopes().get(1);
        p.b.db.failBucket="location-in";
        assertThrows(IllegalStateException.class,() -> p.b.e.receive(pending));
        assertTrue(p.b.e.outbox().isEmpty()); assertTrue(p.b.db.keys("location-in").isEmpty());
        p.b.db.failBucket=null; p.b.e.receive(pending); assertEquals(1,p.b.e.locations().received(p.a.e.id()).size());
        p.a.db.failBucket="location-out";
        assertThrows(IllegalStateException.class,() -> p.sender.locations().stop(id)); p.a.db.failBucket=null;
        assertThrows(SecurityException.class,() -> p.sender.authorizeEnvelope(pending));
    }
    @Test public void newDeviceExcludedThenRevocationShrinksAndTrustLossStops() throws Exception {
        Pair p=new Pair(); String id=p.live(); DeviceLinkingTest.Device b2=new DeviceLinkingTest.Device("B2"); DeviceLinkingTest.link(p.b,b2);
        DeviceLinkingTest.approveSet(p.a,p.b,b2); p.update(id);
        assertTrue(p.sender.outbox().stream().noneMatch(q -> q.optString("peer").equals(safeId(b2))));
        String multi=p.sender.locations().start(p.sender.locations().review(p.b.e.id(),LocationPayload.Mode.ZONE,900,true),true); p.update(multi);
        assertTrue(p.sender.outbox().stream().anyMatch(q -> q.optString("peer").equals(safeId(b2))));
        p.a.d.apply(p.b.d.revoke(b2.e.id())); p.update(multi);
        assertTrue(p.sender.outbox().stream().noneMatch(q -> q.optString("peer").equals(safeId(b2))));
        p.a.e.identityChanged(p.b.e.id()); assertThrows(SecurityException.class,() -> p.update(multi)); assertTrue(p.sender.outbox().isEmpty());
    }
    @Test(timeout=30000) public void concurrentConfirmationIsSingleUse() throws Exception {
        Pair p=new Pair(); var c=p.sender.locations().review(p.b.e.id(),LocationPayload.Mode.ZONE,900,true);
        ExecutorService pool=Executors.newFixedThreadPool(2); CountDownLatch go=new CountDownLatch(1);
        try {
            List<Future<Boolean>> results=new ArrayList<>();
            for(int i=0;i<2;i++) results.add(pool.submit(() -> { go.await(); try { p.sender.locations().start(c,true); return true; } catch(SecurityException rejected) { return false; } }));
            go.countDown(); int success=0; for(var f:results) if(f.get(20,TimeUnit.SECONDS)) success++;
            assertEquals(1,success); assertEquals(1,p.a.db.keys("location-out").size());
        } finally { pool.shutdownNow(); assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS)); }
    }
    @Test public void authenticatedPayloadCannotForgeSenderOrRecipientContext() throws Exception {
        Pair p=new Pair(); String id=p.live(); p.update(id);
        JSONObject payload=new JSONObject(p.a.e.get("location-out",id).getJSONObject("payload").toString());
        payload.put("point",LocationPayload.point(1,2,3,Bytes.now(),LocationPayload.Mode.ZONE,"ANDROID_FINE"));
        payload.put("device",p.b.e.id());
        JSONObject forged=encryptFixture(p.sender,p.a.db,p.b.e.id(),payload);
        assertThrows(SecurityException.class,() -> p.b.e.receive(forged));
        assertTrue(p.b.e.locations().received(p.a.e.id()).isEmpty());
        payload.put("device",p.a.e.id()).put("targets",new JSONArray(List.of(p.a.e.id())));
        JSONObject wrongTarget=encryptFixture(p.sender,p.a.db,p.b.e.id(),payload);
        assertThrows(SecurityException.class,() -> p.b.e.receive(wrongTarget));
        p.a.e.block(p.b.e.id(),true); p.a.e.block(p.b.e.id(),false);
        assertThrows(SecurityException.class,() -> p.update(id)); // Restoring trust never restores old consent.
    }
    private static JSONObject encryptFixture(Engine engine,DeviceLinkingTest.Store db,String peer,JSONObject payload) throws Exception {
        var method=Engine.class.getDeclaredMethod("encrypt",String.class,JSONObject.class,long.class); method.setAccessible(true);
        JSONObject content=new JSONObject().put("kind","location").put("logicalId",UUID.randomUUID().toString())
            .put("logicalFrom",engine.id()).put("logicalTo",peer).put("location",payload);
        return db.transaction(() -> (JSONObject)method.invoke(engine,peer,content,Bytes.now()+120));
    }
    @Test public void stopDeliveredFirstIsTerminalAndDoesNotActivateCapture() throws Exception {
        Pair p=new Pair(); String id=p.live(); p.update(id); JSONObject delayed=p.envelopes().get(1);
        p.sender.locations().stop(id); JSONObject stop=p.envelopes().get(0); p.b.e.receive(stop); p.b.e.receive(delayed);
        assertEquals("STOPPED",p.b.e.locations().received(p.a.e.id()).get(0).getString("state"));
        assertThrows(SecurityException.class,() -> p.b.e.locations().authorizeCapture(id));
    }
    @Test public void receiverFreshnessDeadlineAndReopenCannotClaimForeverLive() throws Exception {
        Pair p=new Pair(); Engine receiver=new Engine(p.b.db,p.time::get); String id=p.live();
        p.sender.locations().publish(id,1,2,3,Bytes.now()-40,"ANDROID_FINE");
        for(JSONObject envelope:p.envelopes()) receiver.receive(envelope);
        assertEquals("LAST_KNOWN",receiver.locations().received(p.a.e.id()).get(0).getString("display"));
        p.time.addAndGet(901000);
        assertEquals("EXPIRED",receiver.locations().received(p.a.e.id()).get(0).getString("display"));
        assertEquals("EXPIRED",new Engine(p.b.db).locations().received(p.a.e.id()).get(0).getString("display"));
        Pair fresh=new Pair(); Engine recipient=new Engine(fresh.b.db,fresh.time::get); String active=fresh.live(); fresh.update(active);
        for(JSONObject e:fresh.envelopes()) recipient.receive(e);
        assertEquals("RECENT",recipient.locations().received(fresh.a.e.id()).get(0).getString("display"));
        fresh.time.addAndGet(31000); // Wall time unchanged: freshness must still end.
        assertEquals("LAST_KNOWN",recipient.locations().received(fresh.a.e.id()).get(0).getString("display"));
    }
    @Test public void expiryWhileWaitingForTransactionRechecksConsent() throws Exception {
        Pair p=new Pair(); var consent=p.sender.locations().review(p.b.e.id(),LocationPayload.Mode.ZONE,900,true);
        p.a.db.before=() -> { p.time.addAndGet(61000); return null; };
        assertThrows(SecurityException.class,() -> p.sender.locations().start(consent,true));
        String id=p.live(); p.a.db.before=() -> { p.time.addAndGet(901000); return null; };
        assertThrows(SecurityException.class,() -> p.update(id));
    }
    @Test public void higherSequenceCannotReplaceANewerMeasurementWithOlderCoordinates() throws Exception {
        Pair p=new Pair(); String id=p.live(); p.update(id);
        for(JSONObject e:p.envelopes()) p.b.e.receive(e);
        long older=p.a.e.get("location-out",id).getLong("lastMeasured")-1;
        assertThrows(SecurityException.class,() -> p.sender.locations().publish(id,1,2,3,older,"ANDROID_FINE"));
        JSONObject payload=new JSONObject(p.a.e.get("location-out",id).getJSONObject("payload").toString()).put("seq",2);
        payload.put("point",LocationPayload.point(1,2,3,older,LocationPayload.Mode.ZONE,"ANDROID_FINE"));
        p.b.e.receive(encryptFixture(p.sender,p.a.db,p.b.e.id(),payload));
        assertEquals(1,p.b.e.locations().received(p.a.e.id()).get(0).getJSONObject("payload").getLong("seq"));
    }
    @Test public void revokedCapturingDeviceLosesGrantAndAllPendingDeliveries() throws Exception {
        Pair p=new Pair(); String id=p.live(); p.update(id);
        JSONObject delayed=p.envelopes().get(1); p.a.d.revoke(p.a.e.id());
        assertThrows(SecurityException.class,() -> p.sender.locations().authorizeCapture(id));
        assertThrows(SecurityException.class,() -> p.sender.authorizeEnvelope(delayed));
        assertTrue(p.sender.outbox().isEmpty()); assertEquals("INTERRUPTED",p.a.e.get("location-out",id).getString("state"));
    }
    @Test public void suspendingOwnAdministratorCannotResumeAnOldSecondaryCaptureAfterUnblock() throws Exception {
        Pair p=new Pair(); DeviceLinkingTest.Device a2=new DeviceLinkingTest.Device("Synthetic A2");
        DeviceLinkingTest.link(p.a,a2); DeviceLinkingTest.pair(a2,p.b);
        DeviceLinkingTest.approveSet(a2,p.b); DeviceLinkingTest.approveSet(p.b,p.a,a2);
        String id=a2.e.locations().start(a2.e.locations().review(p.b.e.id(),LocationPayload.Mode.ZONE,900,true),true);
        a2.e.block(p.a.e.id(),true); a2.e.block(p.a.e.id(),false);
        assertThrows(SecurityException.class,() -> a2.e.locations().authorizeCapture(id));
        assertTrue(a2.e.outbox().isEmpty());
    }
    @Test public void stopClickCancelsQueuedWritesBeforePersistenceWorkerRuns() throws Exception {
        Pair p=new Pair(); String id=p.live(); p.update(id);
        var write=p.sender.deliveryAuthorization(p.envelopes().get(1));
        p.sender.locations().cancelCapture(id);
        assertThrows(SecurityException.class,write::run);
        assertThrows(SecurityException.class,() -> p.sender.locations().authorizeCapture(id));
        p.sender.locations().stop(id);
        assertEquals(1,p.envelopes().size()); p.sender.authorizeEnvelope(p.envelopes().get(0));
        p.b.e.receive(p.envelopes().get(0)); assertEquals("STOPPED",p.b.e.locations().received(p.a.e.id()).get(0).getString("state"));
    }
    @Test public void permissionPolicyIsRecheckedAtWriteAndCannotRestoreOldGrant() throws Exception {
        Pair p=new Pair(); String id=p.live(); java.util.concurrent.atomic.AtomicBoolean permission=new java.util.concurrent.atomic.AtomicBoolean(true);
        p.sender.locations().bindCapturePolicy(id,() -> { if(!permission.get()) throw new SecurityException("Synthetic permission revoked"); });
        p.update(id); var write=p.sender.deliveryAuthorization(p.envelopes().get(1));
        permission.set(false); assertThrows(SecurityException.class,write::run);
        permission.set(true); assertThrows(SecurityException.class,() -> p.sender.locations().authorizeCapture(id));
        assertTrue(p.sender.outbox().isEmpty());
    }
    @Test public void acknowledgedStopsReleaseCaptureSlotsWithoutRemovingReplayTombstones() throws Exception {
        Pair p=new Pair();
        for(int i=0;i<8;i++) {
            String id=p.live(); p.sender.locations().stop(id);
            for(JSONObject envelope:p.envelopes()) p.b.e.receive(envelope);
            for(JSONObject receipt:p.b.e.outbox()) {
                JSONObject envelope=receipt.getJSONObject("envelope"); p.sender.receive(envelope); p.b.e.transported(envelope.getString("id"),false);
            }
            assertEquals("STOPPED",p.a.e.get("location-out",id).getString("state"));
        }
        assertEquals(8,p.a.db.keys("location-out").size()); assertEquals(8,p.b.db.keys("location-in").size());
    }
    @Test public void clearingChatRemovesCoordinatesButRetainsTerminalReplayProtection() throws Exception {
        Pair p=new Pair(); String id=p.live(); p.update(id);
        for(JSONObject e:p.envelopes()) p.b.e.receive(e);
        p.b.e.clearConversation(p.a.e.id()); assertTrue(p.b.e.locations().received(p.a.e.id()).isEmpty());
        p.update(id); JSONObject pending=p.envelopes().get(1); p.b.e.receive(pending);
        assertTrue(p.b.e.locations().received(p.a.e.id()).isEmpty());
        for(String key:p.b.db.keys("location-in")) {
            JSONObject tombstone=p.b.e.get("location-in",key);
            assertFalse(tombstone.has("lastPoint")); assertFalse(tombstone.getJSONObject("payload").has("point"));
        }
        p.sender.clearConversation(p.b.e.id());
        assertThrows(SecurityException.class,() -> p.sender.authorizeEnvelope(pending));
        assertThrows(SecurityException.class,() -> p.update(id));
    }
    private static String safeId(DeviceLinkingTest.Device d) { try { return d.e.id(); } catch(Exception e) { throw new AssertionError(e); } }
}
