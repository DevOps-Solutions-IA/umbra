package app.umbra;

import app.umbra.content.RestrictedContentService;
import app.umbra.content.RestrictedPayload;
import app.umbra.content.ContentException;
import app.umbra.core.Bytes;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic cleanup fault at the domain boundary; not a native codec failure reproduction. */
public class PostAcceptanceRestrictedClosureTest {
    private RestrictedContentTest.Pair pair;
    @Before public void createSyntheticIdentities() throws Exception {
        // Identity/JNI initialization is fixture setup, outside the presentation
        // operation timeout. The deadline/closure/latch bounds below stay fixed.
        long start=System.nanoTime();pair=new RestrictedContentTest.Pair();
        System.out.println("Synthetic identity fixture setup ms="+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));
    }
    private static String send(RestrictedContentTest.Pair pair) throws Exception {
        var constructor=RestrictedContentService.Prepared.class.getDeclaredConstructor(
                RestrictedPayload.Format.class,byte[].class,Runnable.class);
        constructor.setAccessible(true);
        var prepared=constructor.newInstance(RestrictedPayload.Format.PNG,new byte[]{1,2,3,4},pair.a.db.authorization());
        String id=pair.a.e.restricted().send(pair.a.e.restricted().reviewSend(
                pair.b.e.id(),RestrictedPayload.Mode.UMBRA_ONLY,60,2),prepared,true);
        for(var row:pair.a.e.outbox())pair.b.e.receive(row.getJSONObject("envelope"));
        return id;
    }

    private static void awaitBusyDeadline(RestrictedContentTest.Pair pair,String id) throws Exception {
        long busy=new JSONObject(Bytes.text(pair.b.db.get("restricted-state",id))).getLong("busyUntil");
        long timeout=System.nanoTime()+3_000_000_000L;
        while(Bytes.now()<busy && System.nanoTime()<timeout)Thread.sleep(10);
        assertTrue("Real persisted busy deadline must pass",Bytes.now()>=busy);
    }

    private static void blockingResource(RestrictedContentService.Session session,
            CountDownLatch entered,CountDownLatch release) throws Exception {
        Class<?> decoder=Class.forName("app.umbra.content.RestrictedContentService$Decoder");
        Object fake=java.lang.reflect.Proxy.newProxyInstance(decoder.getClassLoader(),new Class<?>[]{decoder},
                (proxy,method,args)->new Object());
        var decode=session.getClass().getDeclaredMethod("decode",decoder,java.util.function.Consumer.class);
        decode.setAccessible(true);
        decode.invoke(session,fake,(java.util.function.Consumer<Object>)value->{
            entered.countDown();
            try {if(!release.await(8,TimeUnit.SECONDS))throw new IllegalStateException("Synthetic cleanup release timeout");}
            catch(InterruptedException failure){Thread.currentThread().interrupt();throw new IllegalStateException(failure);}
        });
    }

    private static app.umbra.data.Records storageView(app.umbra.data.Records records,Object scope) {
        return new app.umbra.data.Records() {
            public byte[] get(String b,String k){return records.get(b,k);}
            public void put(String b,String k,byte[] v){records.put(b,k,v);}
            public void remove(String b,String k){records.remove(b,k);}
            public java.util.List<String> keys(String b){return records.keys(b);}
            public <T>T transaction(Work<T> work)throws Exception{return records.transaction(work);}
            public Runnable authorization(){return records.authorization();}
            public void onInvalidation(Runnable callback){records.onInvalidation(callback);}
            public Object restrictedResourceScope(){return scope;}
        };
    }

    @Test(timeout=10000) public void confirmedCleanupAllowsFreshRepeatableSessionAfterDeadline() throws Exception {
        String id=send(pair);var service=pair.b.e.restricted();
        var first=service.open(service.reviewOpen(id),true);
        first.close();first.closure().toCompletableFuture().get(3,TimeUnit.SECONDS);
        awaitBusyDeadline(pair,id);
        var replacement=new app.umbra.crypto.Engine(pair.b.db).restricted();
        try(var next=replacement.open(replacement.reviewOpen(id),true)){next.check();}
        assertFalse(service.status(id).consumed());
    }

    @Test(timeout=10000) public void unconfirmedCleanupMustNotGrantAnotherRepeatableSessionAfterDeadline() throws Exception {
        String id=send(pair);var service=pair.b.e.restricted();
        var first=service.open(service.reviewOpen(id),true);
        var failed=first.getClass().getDeclaredMethod("cleanupFailed");failed.setAccessible(true);failed.invoke(first);
        assertThrows(ExecutionException.class,()->first.closure().toCompletableFuture().get(3,TimeUnit.SECONDS));
        awaitBusyDeadline(pair,id);
        assertFalse(service.status(id).consumed());
        // The persisted busy deadline must not stand in for confirmation that native
        // resources from the previous presentation have actually closed.
        assertEquals(ContentException.Code.BUSY,assertThrows(ContentException.class,()->{
            try(var next=service.open(service.reviewOpen(id),true)){next.check();}
        }).code());
    }

    @Test(timeout=15000) public void pendingCleanupBlocksReopenAcrossEngineInstances() throws Exception {
        String id=send(pair);Object sharedScope=new Object();
        var service=new app.umbra.crypto.Engine(storageView(pair.b.db,sharedScope)).restricted();
        var first=service.open(service.reviewOpen(id),true);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        blockingResource(first,entered,release);
        try {
            first.close();assertTrue(entered.await(3,TimeUnit.SECONDS));
            awaitBusyDeadline(pair,id);assertFalse(first.closure().toCompletableFuture().isDone());
            var replacement=new app.umbra.crypto.Engine(storageView(pair.b.db,sharedScope)).restricted();
            assertEquals(ContentException.Code.BUSY,assertThrows(ContentException.class,()->{
                try(var next=replacement.open(replacement.reviewOpen(id),true)){next.check();}
            }).code());
        } finally {release.countDown();first.closure().toCompletableFuture().get(3,TimeUnit.SECONDS);}
    }

    @Test(timeout=15000) public void pendingCleanupContinuesToOccupyAllFourSlotsAfterDeadlines() throws Exception {
        var service=pair.b.e.restricted();
        var ids=new java.util.ArrayList<String>();for(int i=0;i<5;i++)ids.add(send(pair));
        var sessions=new java.util.ArrayList<RestrictedContentService.Session>();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        try {
            for(int i=0;i<4;i++) {
                var session=service.open(service.reviewOpen(ids.get(i)),true);sessions.add(session);
                blockingResource(session,entered,release);
            }
            for(var session:sessions)session.close();assertTrue(entered.await(3,TimeUnit.SECONDS));
            for(int i=0;i<4;i++)awaitBusyDeadline(pair,ids.get(i));
            for(var session:sessions)assertFalse(session.closure().toCompletableFuture().isDone());
            var replacement=new app.umbra.crypto.Engine(pair.b.db).restricted();
            assertEquals(ContentException.Code.CAPACITY,assertThrows(ContentException.class,()->{
                try(var next=replacement.open(replacement.reviewOpen(ids.get(4)),true)){next.check();}
            }).code());
        } finally {
            release.countDown();for(var session:sessions){session.close();session.closure().toCompletableFuture().get(3,TimeUnit.SECONDS);}
        }
    }

    @Test(timeout=10000) public void failedAeadBeforeSessionDoesNotLeakResourceReservations() throws Exception {
        String id=send(pair);var service=pair.b.e.restricted();
        byte[] original=pair.b.db.get("restricted-object",id);
        var corrupted=new JSONObject(Bytes.text(original));
        byte[] bytes=Bytes.unb64(corrupted.getString("ciphertext"));bytes[0]^=1;
        corrupted.put("ciphertext",Bytes.b64(bytes));
        pair.b.db.transaction(()->{pair.b.db.put("restricted-object",id,Bytes.utf8(corrupted.toString()));return null;});
        for(int i=0;i<5;i++)assertThrows(java.security.GeneralSecurityException.class,
                ()->service.open(service.reviewOpen(id),true));
        pair.b.db.transaction(()->{pair.b.db.put("restricted-object",id,original);return null;});
        assertFalse(service.status(id).consumed());
        try(var session=service.open(service.reviewOpen(id),true)){session.check();}
    }
}
