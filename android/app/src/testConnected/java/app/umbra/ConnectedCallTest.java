package app.umbra;

import app.umbra.calls.*;
import app.umbra.core.Bytes;
import app.umbra.crypto.Engine;
import org.json.*;
import org.junit.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;
import static app.umbra.calls.CallPayload.NetworkPolicy.*;

/** Real Signal; protocol-only fixtures, not WebRTC or physical media. */
public class ConnectedCallTest {
    static class Pair {
        final DeviceLinkingTest.Device a=new DeviceLinkingTest.Device("Call A"), b=new DeviceLinkingTest.Device("Call B");
        final AtomicLong time=new AtomicLong(10000); final Engine ae=new Engine(a.db,time::get), be=new Engine(b.db,time::get);
        Pair() throws Exception { DeviceLinkingTest.pair(a,b); a.d.migrate(); b.d.migrate(); DeviceLinkingTest.approveSet(a,b); DeviceLinkingTest.approveSet(b,a); }
        String invite() throws Exception { String id=ae.calls().invite(ae.calls().reviewInvite(b.e.id(),RELAY_ONLY),true); deliver(ae,be); return id; }
        String selected() throws Exception { String id=invite(); be.calls().accept(be.calls().reviewAccept(id,RELAY_ONLY),true); deliver(be,ae); deliver(ae,be); return id; }
    }
    static void deliver(Engine a,Engine b) throws Exception {
        for(JSONObject q:a.outbox()) if(q.getString("peer").equals(b.id())) { JSONObject e=q.getJSONObject("envelope"); a.authorizeEnvelope(e); b.receive(e); a.transported(e.getString("id"),true); }
    }
    static JSONObject control(Engine e,String type) throws Exception { return e.outbox().stream().filter(q->q.optString("callType").equals(type)).findFirst().orElseThrow().getJSONObject("envelope"); }
    static String fingerprint(String who) { return Bytes.sha256(Bytes.utf8(who)); }
    static String sdp(String role) { return "v=0\r\ns=synthetic signaling "+role+"; no media executed\r\nt=0 0\r\n"; }
    @Test public void inviteAcceptSelectNegotiateEndRealSignal() throws Exception {
        Pair p=new Pair(); String id=p.selected();
        assertEquals("SELECTED",p.be.calls().session(id).getString("state"));
        p.ae.calls().description(id,1,"offer",sdp("offer"),fingerprint("A")); deliver(p.ae,p.be);
        p.be.calls().description(id,1,"answer",sdp("answer"),fingerprint("B")); deliver(p.be,p.ae);
        p.ae.calls().ice(id,1,Bytes.sha256(Bytes.utf8(sdp("offer"))),"0","synthetic opaque ICE fixture; not parsed or applied"); deliver(p.ae,p.be);
        p.be.calls().verifyRemoteBinding(id,1,Bytes.sha256(Bytes.utf8(sdp("offer"))),fingerprint("A"));
        assertEquals("NEGOTIATING",p.ae.calls().session(id).getString("state"));
        p.ae.calls().end(id); deliver(p.ae,p.be); assertEquals("ENDED",p.be.calls().session(id).getString("state"));
        assertTrue(p.ae.messages(p.b.e.id()).isEmpty());
    }
    @Test public void explicitConsentLeasePurposeAndApiBypassRejected() throws Exception {
        Pair p=new Pair(); var c=p.ae.calls().reviewInvite(p.b.e.id(),RELAY_ONLY);
        assertThrows(SecurityException.class,()->p.ae.calls().invite(c,false));
        assertThrows(SecurityException.class,()->p.be.calls().invite(c,true));
        assertThrows(SecurityException.class,()->p.ae.enqueueCall(null,new JSONObject(),List.of(p.b.e.id())));
        assertThrows(SecurityException.class,()->p.ae.calls().receive(null,new JSONObject()));
        p.a.db.gate.lock();p.a.db.gate.unlock();assertThrows(SecurityException.class,()->p.ae.calls().invite(c,true));
        assertThrows(SecurityException.class,()->p.ae.calls().reviewInvite(p.b.e.id(),DIRECT_ALLOWED));
    }
    @Test public void expiryAfterTransactionWaitMonotonicAndNoExpiredQueue() throws Exception {
        Pair p=new Pair(); var c=p.ae.calls().reviewInvite(p.b.e.id(),RELAY_ONLY);
        p.a.db.before=()->{p.time.addAndGet(31000);return null;}; assertThrows(SecurityException.class,()->p.ae.calls().invite(c,true));
        String id=p.invite();p.time.addAndGet(61000);
        assertThrows(SecurityException.class,()->p.be.calls().reviewAccept(id,RELAY_ONLY));
        assertEquals("EXPIRED",p.ae.calls().session(id).getString("state")); assertTrue(p.ae.outbox().stream().noneMatch(q->q.has("callSession")));
    }
    @Test public void cancelBeforeInviteAndDelayedAcceptCannotRevive() throws Exception {
        Pair p=new Pair(); String id=p.ae.calls().invite(p.ae.calls().reviewInvite(p.b.e.id(),RELAY_ONLY),true); JSONObject invite=control(p.ae,"INVITE");
        p.ae.calls().end(id); JSONObject cancel=control(p.ae,"CANCEL");p.be.receive(cancel);p.be.receive(invite);
        assertEquals("CANCELLED",p.be.calls().session(id).getString("state"));
        Pair q=new Pair();String other=q.invite();q.be.calls().accept(q.be.calls().reviewAccept(other,RELAY_ONLY),true);JSONObject accept=control(q.be,"ACCEPT");
        q.ae.calls().end(other);q.ae.receive(accept);assertEquals("CANCELLED",q.ae.calls().session(other).getString("state"));
    }
    @Test public void twoInviteesConcurrentAcceptanceOnlyOneSelected() throws Exception {
        Pair p=new Pair(); DeviceLinkingTest.Device b2=new DeviceLinkingTest.Device("Call B2"); DeviceLinkingTest.link(p.b,b2);
        DeviceLinkingTest.pair(p.a,b2);DeviceLinkingTest.approveSet(p.a,p.b,b2);DeviceLinkingTest.approveSet(b2,p.a);
        String id=p.invite();deliver(p.ae,b2.e);
        p.be.calls().accept(p.be.calls().reviewAccept(id,RELAY_ONLY),true);b2.e.calls().accept(b2.e.calls().reviewAccept(id,RELAY_ONLY),true);
        JSONObject one=control(p.be,"ACCEPT"),two=control(b2.e,"ACCEPT");
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try { var x=pool.submit(()->{p.ae.receive(one);return true;});var y=pool.submit(()->{p.ae.receive(two);return true;});assertTrue(x.get());assertTrue(y.get()); } finally {pool.shutdownNow();}
        String winner=p.ae.calls().session(id).getString("selected");deliver(p.ae,p.be);deliver(p.ae,b2.e);
        assertEquals(winner.equals(p.b.e.id())?"SELECTED":"NOT_SELECTED",p.be.calls().session(id).getString("state"));
        assertEquals(winner.equals(b2.e.id())?"SELECTED":"NOT_SELECTED",b2.e.calls().session(id).getString("state"));
        p.ae.receive(two);p.ae.receive(one);assertEquals(winner,p.ae.calls().session(id).getString("selected"));
    }
    @Test public void crossedInvitationsReturnBusyWithoutTwoLiveSessions() throws Exception {
        Pair p=new Pair();String a=p.ae.calls().invite(p.ae.calls().reviewInvite(p.b.e.id(),RELAY_ONLY),true);
        String b=p.be.calls().invite(p.be.calls().reviewInvite(p.a.e.id(),RELAY_ONLY),true);
        JSONObject ai=control(p.ae,"INVITE"),bi=control(p.be,"INVITE");p.be.receive(ai);p.ae.receive(bi);
        deliver(p.ae,p.be);deliver(p.be,p.ae);
        assertEquals("BUSY",p.ae.calls().session(a).getString("state"));assertEquals("BUSY",p.be.calls().session(b).getString("state"));
    }
    @Test public void diskRollbackSelectionRatchetAndSameCiphertextRetry() throws Exception {
        Pair p=new Pair();String id=p.invite();p.be.calls().accept(p.be.calls().reviewAccept(id,RELAY_ONLY),true);JSONObject accept=control(p.be,"ACCEPT");
        p.a.db.failBucket="calls";assertThrows(IllegalStateException.class,()->p.ae.receive(accept));p.a.db.failBucket=null;
        assertEquals("OUTGOING",p.ae.calls().session(id).getString("state"));p.ae.receive(accept);assertEquals("SELECTED",p.ae.calls().session(id).getString("state"));
    }
    @Test public void lockRestartAndImmediateCancelInvalidateTransport() throws Exception {
        Pair p=new Pair();String id=p.invite();JSONObject e=control(p.ae,"INVITE");var write=p.ae.deliveryAuthorization(e);
        p.ae.calls().cancelPending(id);assertThrows(SecurityException.class,write::run);
        Pair q=new Pair();String other=q.invite();var c=q.be.calls().reviewAccept(other,RELAY_ONLY);
        q.b.db.gate.lock();q.b.db.gate.unlock();assertThrows(SecurityException.class,()->q.be.calls().accept(c,true));
        assertEquals("FAILED",new Engine(q.a.db).calls().session(other).getString("state"));
        assertTrue(new Engine(q.a.db).outbox().stream().noneMatch(r->r.has("callSession")));
    }
    @Test public void trustAndRevocationCancelPendingAndDoNotResume() throws Exception {
        Pair p=new Pair();String id=p.selected();p.ae.block(p.b.e.id(),true);p.ae.block(p.b.e.id(),false);
        assertEquals("FAILED",p.ae.calls().session(id).getString("state"));
        Pair q=new Pair();String other=q.invite();q.be.identityChanged(q.a.e.id());assertThrows(SecurityException.class,()->q.be.calls().reviewAccept(other,RELAY_ONLY));
        Pair r=new Pair();String call=r.selected();r.a.d.revoke(r.a.e.id());assertEquals("FAILED",r.ae.calls().session(call).getString("state"));
    }
    @Test public void wrongGenerationFingerprintRoleAndEarlyIceReject() throws Exception {
        Pair p=new Pair();String id=p.selected();
        assertThrows(SecurityException.class,()->p.ae.calls().ice(id,1,fingerprint("x"),"0","fixture"));
        assertThrows(SecurityException.class,()->p.be.calls().description(id,1,"offer",sdp("bad"),fingerprint("B")));
        p.ae.calls().description(id,1,"offer",sdp("offer"),fingerprint("A"));deliver(p.ae,p.be);
        assertThrows(SecurityException.class,()->p.be.calls().verifyRemoteBinding(id,1,Bytes.sha256(Bytes.utf8(sdp("offer"))),fingerprint("wrong")));
        p.be.calls().description(id,1,"answer",sdp("answer"),fingerprint("B"));deliver(p.be,p.ae);
        assertThrows(SecurityException.class,()->p.ae.calls().description(id,2,"offer",sdp("restart"),fingerprint("changed")));
        p.ae.calls().description(id,2,"offer",sdp("restart"),fingerprint("A"));deliver(p.ae,p.be);
        assertThrows(SecurityException.class,()->p.ae.calls().ice(id,1,Bytes.sha256(Bytes.utf8(sdp("offer"))),"0","old"));
        assertEquals(0,p.be.calls().session(id).getJSONArray("ice").length());
    }
    @Test public void signalingCannotStartOrExtendLocationAndLockStopsBoth() throws Exception {
        Pair p=new Pair();String live=p.ae.locations().start(p.ae.locations().review(p.b.e.id(),app.umbra.location.LocationPayload.Mode.ZONE,900,true),true);
        String before=p.ae.get("location-out",live).getJSONObject("payload").toString();String call=p.selected();
        assertEquals(before,p.ae.get("location-out",live).getJSONObject("payload").toString());assertTrue(p.b.db.keys("location-out").isEmpty());
        p.a.db.gate.lock();p.a.db.gate.unlock();assertThrows(SecurityException.class,()->p.ae.locations().authorizeCapture(live));
        assertEquals("FAILED",p.ae.calls().session(call).getString("state"));
    }
    static JSONObject forged(Engine sender,String peer,JSONObject payload) throws Exception {
        var method=Engine.class.getDeclaredMethod("encrypt",String.class,JSONObject.class,long.class);method.setAccessible(true);
        JSONObject c=payload.getJSONObject("context");boolean caller=sender.id().equals(c.getString("callerDevice"));
        JSONObject content=new JSONObject().put("kind","call").put("call",payload).put("logicalId",payload.getString("event"))
            .put("logicalFrom",c.getString(caller?"caller":"callee")).put("logicalTo",c.getString(caller?"callee":"caller"));
        return (JSONObject)method.invoke(sender,peer,content,Bytes.now()+20);
    }
    static JSONObject payload(Pair p,String id,String type) throws Exception {
        JSONObject row=p.ae.calls().session(id);
        return new JSONObject().put("v",1).put("purpose","UMBRA-CALL-SIGNALING").put("context",row.getJSONObject("context"))
            .put("type",type).put("event",UUID.randomUUID().toString()).put("device",p.ae.id()).put("selected",row.getString("selected"))
            .put("generation",row.getInt("generation")).put("policy","RELAY_ONLY").put("data",new JSONObject());
    }
    @Test public void authenticatedMaliciousControlContextPolicyAndReplayCollisionRejected() throws Exception {
        Pair p=new Pair();String id=p.selected();JSONObject bad=payload(p,id,"END");bad.put("policy","DIRECT_ALLOWED");
        assertThrows(SecurityException.class,()->p.be.receive(forged(p.ae,p.be.id(),bad)));
        JSONObject wrong=payload(p,id,"END").put("device","0".repeat(64));
        assertThrows(SecurityException.class,()->p.be.receive(forged(p.ae,p.be.id(),wrong)));
        JSONObject end=payload(p,id,"END");JSONObject original=forged(p.ae,p.be.id(),end);p.be.receive(original);p.be.receive(original);
        JSONObject collision=new JSONObject(end.toString()).put("generation",1);
        assertThrows(SecurityException.class,()->p.be.receive(forged(p.ae,p.be.id(),collision)));
        assertEquals("ENDED",p.be.calls().session(id).getString("state"));
    }
    @Test public void turnMissingAndUnavailableAdapterFailSessionWithoutFallback() throws Exception {
        Pair p=new Pair();String id=p.selected();assertThrows(SecurityException.class,()->p.ae.calls().prepareMedia(id,null));
        assertEquals("FAILED",p.ae.calls().session(id).getString("state"));deliver(p.ae,p.be);assertEquals("ENDED",p.be.calls().session(id).getString("state"));
        Pair q=new Pair();String other=q.selected();var config=new RelayOnlyContract(List.of("turns:relay.example.invalid:5349"),"0".repeat(64));
        assertThrows(SecurityException.class,()->q.ae.calls().prepareMedia(other,config));assertTrue(config.failed());
    }
    @Test public void newMembershipCannotExpandRecipientsOrRetainOldConsent() throws Exception {
        Pair p=new Pair();String id=p.invite();var consent=p.be.calls().reviewAccept(id,RELAY_ONLY);
        DeviceLinkingTest.Device b2=new DeviceLinkingTest.Device("Later B2");DeviceLinkingTest.link(p.b,b2);
        assertThrows(SecurityException.class,()->p.be.calls().accept(consent,true));assertEquals("FAILED",p.be.calls().session(id).getString("state"));
    }
    @Test public void outboxFailureRollsBackSelectionAndRevocationCancelsCapturedWriter() throws Exception {
        Pair p=new Pair();String id=p.invite();p.be.calls().accept(p.be.calls().reviewAccept(id,RELAY_ONLY),true);JSONObject accept=control(p.be,"ACCEPT");
        p.a.db.failBucket="outbox";assertThrows(IllegalStateException.class,()->p.ae.receive(accept));p.a.db.failBucket=null;
        assertEquals("OUTGOING",p.ae.calls().session(id).getString("state"));p.ae.receive(accept);
        JSONObject selected=control(p.ae,"SELECT");var writer=p.ae.deliveryAuthorization(selected);
        p.ae.identityChanged(p.be.id());assertThrows(SecurityException.class,writer::run);
        assertEquals("FAILED",p.ae.calls().session(id).getString("state"));
    }
    @Test public void floodAndInvalidDescriptionLimitsFailClosed() throws Exception {
        Pair p=new Pair();String id=p.selected();
        assertThrows(SecurityException.class,()->p.ae.calls().description(id,1,"offer","x".repeat(24001),fingerprint("A")));
        p.ae.calls().description(id,1,"offer",sdp("offer"),fingerprint("A"));
        for(int i=0;i<32;i++) p.ae.calls().ice(id,1,Bytes.sha256(Bytes.utf8(sdp("offer"))),"0","synthetic fixture "+i);
        assertThrows(SecurityException.class,()->p.ae.calls().ice(id,1,Bytes.sha256(Bytes.utf8(sdp("offer"))),"0","overflow"));
        assertThrows(SecurityException.class,()->p.ae.calls().invite(p.ae.calls().reviewInvite(p.be.id(),RELAY_ONLY),true));
    }
    @Test public void lostSelectionRetriesSameCiphertextBeforeReceiverCanNegotiate() throws Exception {
        Pair p=new Pair();String id=p.invite();p.be.calls().accept(p.be.calls().reviewAccept(id,RELAY_ONLY),true);deliver(p.be,p.ae);
        JSONObject select=control(p.ae,"SELECT");String cipher=select.getString("ct");
        assertEquals("ACCEPTING",p.be.calls().session(id).getString("state"));
        assertThrows(SecurityException.class,()->p.be.calls().description(id,1,"answer",sdp("answer"),fingerprint("B")));
        p.ae.relayFailed(select.getString("id"));assertEquals(cipher,control(p.ae,"SELECT").getString("ct"));
        p.be.receive(select);p.be.receive(select);assertEquals("SELECTED",p.be.calls().session(id).getString("state"));
    }
}
