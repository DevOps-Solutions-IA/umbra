package app.umbra;

import app.umbra.content.*;
import app.umbra.core.Bytes;
import app.umbra.crypto.Engine;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real Signal/JCA with memory transactions; not Android format/codec or durability acceptance. */
public class RestrictedContentTest {
    private static RestrictedContentService.Prepared synthetic(Runnable authorization) throws Exception {
        // Test-only access to internal preparation; production callers must use format adapters.
        var constructor=RestrictedContentService.Prepared.class.getDeclaredConstructor(RestrictedPayload.Format.class,byte[].class,Runnable.class);
        constructor.setAccessible(true);
        return constructor.newInstance(RestrictedPayload.Format.PNG,new byte[]{1,2,3,4},authorization);
    }
    private static RestrictedContentService.Prepared retain(RestrictedContentService service,
            RestrictedContentService.Review review,RestrictedContentService.Prepared prepared)throws Exception {
        var method=RestrictedContentService.class.getDeclaredMethod("retainPrepared",RestrictedContentService.Review.class,RestrictedContentService.Prepared.class);
        method.setAccessible(true);
        try{return (RestrictedContentService.Prepared)method.invoke(service,review,prepared);}
        catch(java.lang.reflect.InvocationTargetException failed){throw (Exception)failed.getCause();}
    }
    @Test public void pendingPreparationsAreBoundedAndWipedOnInvalidation()throws Exception {
        Pair p=new Pair();var service=p.a.e.restricted();
        var review=service.reviewSend(p.b.e.id(),RestrictedPayload.Mode.ONCE,600,30);
        var pending=new java.util.ArrayList<RestrictedContentService.Prepared>();
        for(int i=0;i<4;i++)pending.add(retain(service,review,synthetic(p.a.db.authorization())));
        var excess=synthetic(p.a.db.authorization());
        assertEquals(ContentException.Code.CAPACITY,assertThrows(ContentException.class,()->retain(service,review,excess)).code());
        excess.closure().toCompletableFuture().get(3,TimeUnit.SECONDS);
        p.a.db.gate.lock();
        var bytes=RestrictedContentService.Prepared.class.getDeclaredField("bytes");bytes.setAccessible(true);
        for(var prepared:pending){prepared.closure().toCompletableFuture().get(3,TimeUnit.SECONDS);assertNull(bytes.get(prepared));prepared.close();}
        p.a.db.gate.unlock();
        var fresh=service.reviewSend(p.b.e.id(),RestrictedPayload.Mode.ONCE,600,30);
        assertThrows(SecurityException.class,()->service.send(fresh,pending.get(0),true));assertTrue(p.a.e.outbox().isEmpty());
        try(var next=retain(service,fresh,synthetic(p.a.db.authorization()))){assertNotNull(next);}
    }
    @Test public void preparedRecipientReviewCannotBeReplacedEvenWithinSameUnlock()throws Exception {
        Pair p=new Pair();var service=p.a.e.restricted();
        var original=service.reviewSend(p.b.e.id(),RestrictedPayload.Mode.ONCE,600,30);
        var replacement=service.reviewSend(p.b.e.id(),RestrictedPayload.Mode.UMBRA_ONLY,600,30);
        try(var prepared=retain(service,original,synthetic(p.a.db.authorization()))) {
            assertEquals(ContentException.Code.CONSENT_REQUIRED,assertThrows(ContentException.class,()->service.send(replacement,prepared,true)).code());
            prepared.closure().toCompletableFuture().get(3,TimeUnit.SECONDS);assertTrue(p.a.e.outbox().isEmpty());
        }
    }
    @Test public void lockDoesNotWaitForPendingSendTransactionOrReviveItsPreparation()throws Exception {
        Pair p=new Pair();var service=p.a.e.restricted();
        var review=service.reviewSend(p.b.e.id(),RestrictedPayload.Mode.ONCE,600,30);
        var prepared=retain(service,review,synthetic(p.a.db.authorization()));
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        p.a.db.before=()->{entered.countDown();if(!release.await(3,TimeUnit.SECONDS))throw new AssertionError("Synthetic transaction barrier");return null;};
        var workers=Executors.newFixedThreadPool(2);
        try {
            var sending=workers.submit(()->service.send(review,prepared,true));assertTrue(entered.await(3,TimeUnit.SECONDS));
            workers.submit(p.a.db.gate::lock).get(3,TimeUnit.SECONDS);
            assertFalse(prepared.closure().toCompletableFuture().isDone());
            release.countDown();var denied=assertThrows(ExecutionException.class,()->sending.get(3,TimeUnit.SECONDS));
            assertTrue(denied.getCause() instanceof SecurityException);
            prepared.closure().toCompletableFuture().get(3,TimeUnit.SECONDS);
            p.a.db.gate.unlock();assertTrue(p.a.e.outbox().isEmpty());
        }finally{release.countDown();prepared.close();workers.shutdownNow();assertTrue(workers.awaitTermination(3,TimeUnit.SECONDS));}
    }
    static final class Pair {
        final DeviceLinkingTest.Device a=new DeviceLinkingTest.Device("Synthetic sender"),b=new DeviceLinkingTest.Device("Synthetic recipient");
        Pair()throws Exception {DeviceLinkingTest.pair(a,b);}
        String send(RestrictedPayload.Mode mode)throws Exception {
            String id=a.e.restricted().send(a.e.restricted().reviewSend(b.e.id(),mode,600,30),synthetic(a.db.authorization()),true);
            for(JSONObject row:a.e.outbox())b.e.receive(row.getJSONObject("envelope"));return id;
        }
    }
    @Test public void preparedBeforeLockCannotBeSentWithFreshConsentAfterUnlock() throws Exception {
        Pair p=new Pair();
        try(var prepared=synthetic(p.a.db.authorization())) {
            p.a.db.gate.lock();p.a.db.gate.unlock();
            var fresh=p.a.e.restricted().reviewSend(p.b.e.id(),RestrictedPayload.Mode.ONCE,600,30);
            assertThrows(SecurityException.class,()->p.a.e.restricted().send(fresh,prepared,true));
            assertTrue(p.a.e.outbox().isEmpty());
        }
    }
    @Test public void signalDeliveryHasNoOrdinaryHistoryOrExportAndConsumesOnce() throws Exception {
        Pair p=new Pair();String id=p.send(RestrictedPayload.Mode.ONCE);
        assertTrue(p.a.e.messages(p.b.e.id()).isEmpty());assertTrue(p.b.e.messages(p.a.e.id()).isEmpty());
        assertThrows(ContentException.class,()->p.b.e.get("restricted-object",id));
        try(var session=p.b.e.restricted().open(p.b.e.restricted().reviewOpen(id),true)) {session.check();assertTrue(p.b.e.restricted().status(id).consumed());}
        assertThrows(ContentException.class,()->p.b.e.restricted().open(p.b.e.restricted().reviewOpen(id),true));
        for(JSONObject queued:p.a.e.outbox())p.b.e.receive(queued.getJSONObject("envelope"));
        assertTrue(p.b.e.restricted().status(id).consumed());
        for(JSONObject ack:p.b.e.outbox())p.a.e.receive(ack.getJSONObject("envelope"));
        assertTrue(p.a.e.outbox().isEmpty());
        var reopened=new Engine(p.b.db);assertThrows(ContentException.class,()->reopened.restricted().open(reopened.restricted().reviewOpen(id),true));
    }
    @Test public void failedConsumeCommitGrantsNoSessionAndLeavesObjectAvailable() throws Exception {
        Pair p=new Pair();String id=p.send(RestrictedPayload.Mode.ONCE);p.b.db.failBucket="restricted-state";
        assertThrows(IllegalStateException.class,()->p.b.e.restricted().open(p.b.e.restricted().reviewOpen(id),true));p.b.db.failBucket=null;
        assertFalse(p.b.e.restricted().status(id).consumed());
        try(var session=p.b.e.restricted().open(p.b.e.restricted().reviewOpen(id),true)){session.check();}
    }
    @Test public void concurrentEnginesCannotOpenTwoSessions() throws Exception {
        Pair p=new Pair();String id=p.send(RestrictedPayload.Mode.ONCE);var other=new Engine(p.b.db);
        var executor=Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> one=()->{try(var session=p.b.e.restricted().open(p.b.e.restricted().reviewOpen(id),true)){session.check();return true;}catch(ContentException rejected){return false;}};
            Callable<Boolean> two=()->{try(var session=other.restricted().open(other.restricted().reviewOpen(id),true)){session.check();return true;}catch(ContentException rejected){return false;}};
            var results=executor.invokeAll(List.of(one,two));int wins=0;for(var result:results)if(result.get())wins++;
            assertEquals(1,wins);
        }finally{executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test public void lockInvalidatesSessionAndNewUnlockCannotReuseIt() throws Exception {
        Pair p=new Pair();String id=p.send(RestrictedPayload.Mode.ONCE);var session=p.b.e.restricted().open(p.b.e.restricted().reviewOpen(id),true);session.check();
        p.b.db.gate.lock();session.closure().toCompletableFuture().get(3,TimeUnit.SECONDS);p.b.db.gate.unlock();
        assertThrows(SecurityException.class,session::check);assertThrows(ContentException.class,()->p.b.e.restricted().open(p.b.e.restricted().reviewOpen(id),true));
    }
    @Test public void noConsentAndUnknownIngressCannotCreateUsableContent() throws Exception {
        Pair p=new Pair();
        try(var prepared=synthetic(p.a.db.authorization())) {assertThrows(ContentException.class,()->p.a.e.restricted().send(p.a.e.restricted().reviewSend(p.b.e.id(),RestrictedPayload.Mode.ONCE,600,30),prepared,false));}
        assertThrows(SecurityException.class,()->p.a.e.enqueueRestricted(null,new JSONObject()));
        assertThrows(ContentException.class,()->p.b.e.restricted().receive(null,new JSONObject()));
        assertTrue(p.a.e.outbox().isEmpty());
    }
    @Test public void repeatablePolicyDoesNotConsumeButStillRejectsParallelSession() throws Exception {
        Pair p=new Pair();String id=p.send(RestrictedPayload.Mode.UMBRA_ONLY);
        try(var session=p.b.e.restricted().open(p.b.e.restricted().reviewOpen(id),true)){
            assertFalse(p.b.e.restricted().status(id).consumed());
            assertThrows(ContentException.class,()->p.b.e.restricted().open(p.b.e.restricted().reviewOpen(id),true));
        }
    }
    @Test public void staleReviewCannotOpenOrSendAfterNewUnlock() throws Exception {
        Pair p=new Pair();String id=p.send(RestrictedPayload.Mode.ONCE);
        var opening=p.b.e.restricted().reviewOpen(id);
        var sending=p.a.e.restricted().reviewSend(p.b.e.id(),RestrictedPayload.Mode.ONCE,600,30);
        p.b.db.gate.lock();p.b.db.gate.unlock();p.a.db.gate.lock();p.a.db.gate.unlock();
        assertThrows(SecurityException.class,()->p.b.e.restricted().open(opening,true));
        try(var prepared=synthetic(p.a.db.authorization())) {assertThrows(SecurityException.class,()->p.a.e.restricted().send(sending,prepared,true));}
        assertFalse(p.b.e.restricted().status(id).consumed());
    }
    @Test public void invalidAeadDoesNotGrantSessionOrConsumeObject() throws Exception {
        Pair p=new Pair();String id=p.send(RestrictedPayload.Mode.ONCE);
        p.b.db.transaction(()->{
            JSONObject payload=new JSONObject(Bytes.text(p.b.db.get("restricted-object",id)));
            byte[] ciphertext=Bytes.unb64(payload.getString("ciphertext"));ciphertext[0]^=1;
            payload.put("ciphertext",Bytes.b64(ciphertext));p.b.db.put("restricted-object",id,Bytes.utf8(payload.toString()));return null;
        });
        assertThrows(java.security.GeneralSecurityException.class,()->p.b.e.restricted().open(p.b.e.restricted().reviewOpen(id),true));
        assertFalse(p.b.e.restricted().status(id).consumed());
    }
    @Test public void terminalDeadlineClosesWithoutUiTimer() throws Exception {
        Pair p=new Pair();String id=p.a.e.restricted().send(p.a.e.restricted().reviewSend(p.b.e.id(),RestrictedPayload.Mode.ONCE,60,1),synthetic(p.a.db.authorization()),true);
        for(JSONObject row:p.a.e.outbox())p.b.e.receive(row.getJSONObject("envelope"));
        var session=p.b.e.restricted().open(p.b.e.restricted().reviewOpen(id),true);session.check();
        session.closure().toCompletableFuture().get(3,TimeUnit.SECONDS);
        assertThrows(ContentException.class,session::check);
    }
    @Test public void preparationConsentBindsOwnerRecipientAndOriginalUnlockWithoutConnecting() throws Exception {
        Pair p=new Pair();var service=p.a.e.restricted();
        var review=service.reviewSend(p.b.e.id(),RestrictedPayload.Mode.ONCE,600,30);
        assertThrows(ContentException.class,()->service.preparationAuthorization(review,false));
        assertThrows(ContentException.class,()->p.b.e.restricted().preparationAuthorization(review,true));
        var lease=service.preparationAuthorization(review,true);lease.run();
        assertTrue(p.a.e.outbox().isEmpty());assertFalse(p.a.e.connectivity().isNetworkSessionAllowed());
        p.a.e.block(p.b.e.id(),true);assertThrows(SecurityException.class,lease::run);
        Pair q=new Pair();var other=q.a.e.restricted();
        var old=other.preparationAuthorization(other.reviewSend(q.b.e.id(),RestrictedPayload.Mode.UMBRA_ONLY,600,30),true);
        q.a.db.gate.lock();q.a.db.gate.unlock();assertThrows(SecurityException.class,old::run);
    }
    @Test public void decoderInitializationFailureImmediatelyClosesConsumedSession() throws Exception {
        Pair p=new Pair();String id=p.send(RestrictedPayload.Mode.ONCE);
        var session=p.b.e.restricted().open(p.b.e.restricted().reviewOpen(id),true);
        Class<?> decoder=Class.forName("app.umbra.content.RestrictedContentService$Decoder");
        Object failing=java.lang.reflect.Proxy.newProxyInstance(decoder.getClassLoader(),new Class<?>[]{decoder},
                (proxy,method,args)->{throw new IllegalArgumentException("Synthetic invalid decoder input");});
        var decode=session.getClass().getDeclaredMethod("decode",decoder,java.util.function.Consumer.class);
        decode.setAccessible(true);
        var failure=assertThrows(java.lang.reflect.InvocationTargetException.class,
                ()->decode.invoke(session,failing,(java.util.function.Consumer<Object>)value->fail("No resource initialized")));
        assertTrue(failure.getCause() instanceof IllegalArgumentException);
        session.closure().toCompletableFuture().get(1,TimeUnit.SECONDS);
        assertThrows(ContentException.class,session::check);
        assertTrue(p.b.e.restricted().status(id).consumed());
        assertThrows(ContentException.class,()->p.b.e.restricted().open(p.b.e.restricted().reviewOpen(id),true));
    }

    @Test public void failedInitializationCleanupCannotReportSuccessfulClosure() throws Exception {
        Pair p=new Pair();String id=p.send(RestrictedPayload.Mode.ONCE);
        var session=p.b.e.restricted().open(p.b.e.restricted().reviewOpen(id),true);
        Class<?> decoder=Class.forName("app.umbra.content.RestrictedContentService$Decoder");
        var cleanupFailed=session.getClass().getDeclaredMethod("cleanupFailed");cleanupFailed.setAccessible(true);
        Object failing=java.lang.reflect.Proxy.newProxyInstance(decoder.getClassLoader(),new Class<?>[]{decoder},
                (proxy,method,args)->{cleanupFailed.invoke(session);throw new IllegalArgumentException("Synthetic initialization cleanup failure");});
        var decode=session.getClass().getDeclaredMethod("decode",decoder,java.util.function.Consumer.class);decode.setAccessible(true);
        assertThrows(java.lang.reflect.InvocationTargetException.class,
                ()->decode.invoke(session,failing,(java.util.function.Consumer<Object>)value->fail("No resource initialized")));
        assertThrows(ExecutionException.class,()->session.closure().toCompletableFuture().get(1,TimeUnit.SECONDS));
        session.close();assertThrows(ContentException.class,session::check);
        assertTrue(p.b.e.restricted().status(id).consumed());
    }

}
