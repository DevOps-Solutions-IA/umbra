package app.umbra;

import app.umbra.calls.*;
import app.umbra.core.Bytes;
import org.json.*;
import org.junit.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;
import static app.umbra.ConnectedCallTest.*;

/** Real libsignal/Engine, synthetic opaque SDP. This is NOT codec/network acceptance. */
public class ConnectedVideoSignalingTest {
    static class VideoPair extends Pair {
        final String id;
        VideoPair() throws Exception {
            id=selected();
            ae.calls().description(id,1,"offer",sdp("audio offer"),fingerprint("A")); deliver(ae,be);
            be.calls().description(id,1,"answer",sdp("audio answer"),fingerprint("B")); deliver(be,ae);
        }
        JSONObject aVideo() throws Exception { return ae.calls().session(id).getJSONObject("video"); }
        JSONObject bVideo() throws Exception { return be.calls().session(id).getJSONObject("video"); }
        void request() throws Exception { ae.calls().requestVideo(ae.calls().reviewVideo(id,true,true),true); deliver(ae,be); }
        void accept(boolean send,boolean receive) throws Exception {
            be.calls().acceptVideo(be.calls().reviewVideoAccept(id,send,receive),true); deliver(be,ae);
        }
        void negotiate(int generation) throws Exception {
            ae.calls().description(id,generation,"offer",sdp("video offer "+generation),fingerprint("A")); deliver(ae,be);
            be.calls().description(id,generation,"answer",sdp("video answer "+generation),fingerprint("B")); deliver(be,ae);
        }
    }
    @Test public void independentReceiveOnlyConsentBindsVersionedDescriptions() throws Exception {
        VideoPair p=new VideoPair(); p.request();
        assertEquals("REVIEW",p.bVideo().getString("state"));
        p.accept(false,true); assertEquals(1,p.aVideo().getInt("callerSend")); assertEquals(0,p.aVideo().getInt("calleeSend"));
        p.negotiate(2);
        JSONObject remote=p.be.calls().session(p.id).getJSONObject("descriptions").getJSONObject(p.ae.id());
        assertEquals(p.bVideo().getString("change"),remote.getJSONObject("video").getString("change"));
        assertEquals("NEGOTIATING",p.ae.calls().session(p.id).getString("state")); // No claim of native ACTIVE.
    }
    @Test public void requestFromCalleeStillUsesOriginalCallerOfferAuthority() throws Exception {
        VideoPair p=new VideoPair();p.be.calls().requestVideo(p.be.calls().reviewVideo(p.id,true,false),true);deliver(p.be,p.ae);
        p.ae.calls().acceptVideo(p.ae.calls().reviewVideoAccept(p.id,false,true),true);deliver(p.ae,p.be);
        assertEquals(0,p.aVideo().getInt("callerSend"));assertEquals(1,p.aVideo().getInt("calleeSend"));
        assertThrows(SecurityException.class,()->p.be.calls().description(p.id,2,"offer",sdp("wrong"),fingerprint("B")));
        p.negotiate(2);
    }
    @Test public void consentCannotBeReusedCrossServiceOrAfterLock() throws Exception {
        VideoPair p=new VideoPair();var c=p.ae.calls().reviewVideo(p.id,true,true);
        assertThrows(SecurityException.class,()->p.ae.calls().requestVideo(c,false));
        assertThrows(SecurityException.class,()->p.be.calls().requestVideo(c,true));
        p.a.db.gate.lock();p.a.db.gate.unlock();assertThrows(SecurityException.class,()->p.ae.calls().requestVideo(c,true));
        VideoPair q=new VideoPair();var once=q.ae.calls().reviewVideo(q.id,true,true);q.ae.calls().requestVideo(once,true);
        assertThrows(SecurityException.class,()->q.ae.calls().requestVideo(once,true));
    }
    @Test public void expiredReviewAfterWaitingForTransactionDoesNotSend() throws Exception {
        VideoPair p=new VideoPair();var c=p.ae.calls().reviewVideo(p.id,true,true);
        p.a.db.before=()->{p.time.addAndGet(31000);return null;};
        assertThrows(SecurityException.class,()->p.ae.calls().requestVideo(c,true));
        assertFalse(p.ae.calls().session(p.id).has("video"));
    }
    @Test public void missingConsentDowngradeAndDirectionEscalationReject() throws Exception {
        VideoPair p=new VideoPair();p.request();
        JSONObject forged=payload(p,p.id,"VIDEO_ACCEPT").put("v",2).put("device",p.be.id()).put("generation",2);
        JSONObject data=new JSONObject(p.aVideo().toString());for(String key:List.of("state","generation","reviewDeadline"))data.remove(key);
        forged.put("data",data.put("callerSend",2));
        assertThrows(SecurityException.class,()->p.ae.receive(ConnectedCallTest.forged(p.be,p.ae.id(),forged)));
        p.accept(true,true);
        JSONObject downgrade=payload(p,p.id,"DESCRIPTION").put("generation",2).put("data",new JSONObject().put("role","offer").put("sdp",sdp("downgrade"))
            .put("digest",Bytes.sha256(Bytes.utf8(sdp("downgrade")))).put("fingerprint",fingerprint("A")));
        assertThrows(SecurityException.class,()->p.be.receive(ConnectedCallTest.forged(p.ae,p.be.id(),downgrade)));
    }
    @Test public void crossedRequestsRejectBothWithoutCombiningConsent() throws Exception {
        VideoPair p=new VideoPair();p.ae.calls().requestVideo(p.ae.calls().reviewVideo(p.id,true,true),true);
        p.be.calls().requestVideo(p.be.calls().reviewVideo(p.id,true,true),true);
        JSONObject a=control(p.ae,"VIDEO_REQUEST"),b=control(p.be,"VIDEO_REQUEST");
        p.ae.receive(b);p.be.receive(a);deliver(p.ae,p.be);deliver(p.be,p.ae);
        assertEquals("REJECTED",p.aVideo().getString("state"));assertEquals("REJECTED",p.bVideo().getString("state"));
        assertEquals(1,p.ae.calls().session(p.id).getInt("generation"));
    }
    @Test public void concurrentLocalRequestsHaveOneWinner() throws Exception {
        VideoPair p=new VideoPair();var one=p.ae.calls().reviewVideo(p.id,true,false);var two=p.ae.calls().reviewVideo(p.id,false,true);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->{try {p.ae.calls().requestVideo(one,true);return 1;}catch(SecurityException rejected){return 0;}});
            var b=pool.submit(()->{try {p.ae.calls().requestVideo(two,true);return 1;}catch(SecurityException rejected){return 0;}});
            assertEquals(1,a.get()+b.get());
        } finally {pool.shutdownNow();}
    }
    @Test public void stopIsTerminalForChangeAndReactivationNeedsNewConsent() throws Exception {
        VideoPair p=new VideoPair();p.request();p.accept(true,true);String change=p.aVideo().getString("change");p.negotiate(2);
        p.ae.calls().stopVideo(p.id);deliver(p.ae,p.be);
        assertEquals("STOPPED",p.bVideo().getString("state"));
        p.ae.calls().requestVideo(p.ae.calls().reviewVideo(p.id,true,false),true);deliver(p.ae,p.be);p.accept(false,true);
        assertNotEquals(change,p.aVideo().getString("change"));p.negotiate(3);
        assertThrows(SecurityException.class,()->p.ae.calls().description(p.id,2,"offer",sdp("old"),fingerprint("A")));
    }
    @Test public void lateAcceptanceAfterStopAndRepeatedProposalCannotRevive() throws Exception {
        VideoPair p=new VideoPair();p.request();
        p.be.calls().acceptVideo(p.be.calls().reviewVideoAccept(p.id,true,true),true);JSONObject answer=control(p.be,"VIDEO_ACCEPT");
        p.ae.calls().stopVideo(p.id);assertThrows(SecurityException.class,()->p.ae.receive(answer));
        assertEquals("STOPPED",p.aVideo().getString("state"));
    }
    @Test public void rollbackRatchetImmutableRetryRestartAndTrustRevocation() throws Exception {
        VideoPair p=new VideoPair();p.ae.calls().requestVideo(p.ae.calls().reviewVideo(p.id,true,true),true);
        JSONObject request=control(p.ae,"VIDEO_REQUEST");String ciphertext=request.getString("ct");
        p.b.db.failBucket="calls";assertThrows(IllegalStateException.class,()->p.be.receive(request));p.b.db.failBucket=null;p.be.receive(request);
        p.ae.relayFailed(request.getString("id"));assertEquals(ciphertext,control(p.ae,"VIDEO_REQUEST").getString("ct"));
        assertEquals("FAILED",new app.umbra.crypto.Engine(p.b.db).calls().session(p.id).getString("state"));
        p.ae.identityChanged(p.be.id());assertEquals("FAILED",p.ae.calls().session(p.id).getString("state"));
        assertTrue(p.ae.outbox().stream().noneMatch(q->q.has("callSession")));
    }
    @Test public void directStopInvalidatesCaptureBeforeBlockedOrFailedStorage() throws Exception {
        VideoPair p=new VideoPair();p.request();p.accept(true,true);
        var media=p.ae.calls().prepareMedia(p.ae.calls().reviewMedia(p.id,"a".repeat(64)),true);
        java.util.concurrent.atomic.AtomicBoolean stopped=new java.util.concurrent.atomic.AtomicBoolean();
        media.attachVideoCancellation(()->stopped.set(true));
        p.a.db.before=()->{assertTrue("Capture invalidated before transaction",stopped.get());return null;};
        p.a.db.failBucket="calls";
        assertThrows(IllegalStateException.class,()->p.ae.calls().stopVideo(p.id));p.a.db.failBucket=null;
        assertTrue(stopped.get());
        p.a.db.gate.lock();p.a.db.gate.unlock();assertThrows(SecurityException.class,media::checkCaptureLease);
    }
    @Test public void versionTypesAndBooleanCoercionAreRejected() throws Exception {
        VideoPair p=new VideoPair();JSONObject x=payload(p,p.id,"VIDEO_REQUEST").put("generation",2);
        JSONObject data=new JSONObject().put("change",UUID.randomUUID().toString()).put("callerSend",1).put("calleeSend",0).put("expires",Bytes.now()+20);x.put("data",data);
        assertThrows(SecurityException.class,()->CallPayload.validate(x,Bytes.now()));
        x.put("v",2);CallPayload.validate(x,Bytes.now());data.put("callerSend",true);
        assertThrows(SecurityException.class,()->CallPayload.validate(x,Bytes.now()));
        data.put("callerSend",1).put("unexpected",1);assertThrows(SecurityException.class,()->CallPayload.validate(x,Bytes.now()));
    }
}
