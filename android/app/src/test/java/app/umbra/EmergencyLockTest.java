package app.umbra;

import app.umbra.core.*;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.concurrent.*;

public class EmergencyLockTest {
    private static void denied(Runnable work) {try {work.run();fail("Authorization survived emergency");}catch(AccessGate.LockedException expected){}}
    private static EmergencyLock.Status terminal(EmergencyLock service) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(7);
        while(System.nanoTime()<until) {
            var status=service.status();
            if(status.state()==EmergencyLock.State.CLOSED || status.state()==EmergencyLock.State.INCOMPLETE)return status;
            Thread.sleep(5);
        }
        throw new AssertionError("No bounded emergency result");
    }
    @Test public void freezesBeforeWaitingOnCommitAndRequiresFreshAuthentication() throws Exception {
        var gate=new AccessGate();gate.unlock();var lease=gate.enter();var service=gate.emergency();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var committed=new FutureTask<Void>(()->{gate.commit(lease,()->{entered.countDown();assertTrue(release.await(3,TimeUnit.SECONDS));return null;});return null;});
        Thread commit=new Thread(committed);
        commit.start();assertTrue(entered.await(2,TimeUnit.SECONDS));
        long before=System.nanoTime();var result=service.request();
        assertTrue(System.nanoTime()-before<TimeUnit.SECONDS.toNanos(1));
        assertTrue(result.invalidatedNanos()>=result.requestedNanos());
        assertNotEquals(EmergencyLock.State.CLOSED,result.state());
        release.countDown();commit.join(2000);assertFalse(commit.isAlive());committed.get(1,TimeUnit.SECONDS);
        denied(()->gate.check(lease));assertEquals(EmergencyLock.State.CLOSED,terminal(service).state());
        denied(gate::unlock);var authentication=service.prepareAuthentication();gate.unlock(authentication);
        gate.enter();denied(()->gate.check(lease));denied(()->gate.unlock(authentication));
    }
    @Test public void asynchronousCompletionNotRequestDefinesClosedAndRequestsAreIdempotent() throws Exception {
        var gate=new AccessGate();gate.unlock();var service=gate.emergency();var closed=new CompletableFuture<Void>();
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        service.register(EmergencyLock.Subsystem.MEDIA,()->{calls.incrementAndGet();return closed;});
        var first=service.request();for(int i=0;i<30;i++)assertEquals(first.requestedNanos(),service.request().requestedNanos());
        denied(gate::enter);denied(service::prepareAuthentication);
        closed.complete(null);assertEquals(EmergencyLock.State.CLOSED,terminal(service).state());assertEquals(1,calls.get());
    }
    @Test public void failureStillAttemptsEverySubsystemAndNeverUnlocks() throws Exception {
        var gate=new AccessGate();gate.unlock();var service=gate.emergency();var other=new CountDownLatch(1);
        service.register(EmergencyLock.Subsystem.MEDIA,()->{throw new IllegalStateException("synthetic; never expose");});
        service.register(EmergencyLock.Subsystem.LOCATION,()->{other.countDown();return CompletableFuture.completedFuture(null);});
        service.request();assertTrue(other.await(2,TimeUnit.SECONDS));
        var status=terminal(service);assertEquals(EmergencyLock.State.INCOMPLETE,status.state());
        assertFalse(status.toString().contains("synthetic"));denied(gate::unlock);denied(service::prepareAuthentication);
    }
    @Test public void invalidatorExceptionCannotPreventOtherInvalidators() throws Exception {
        var gate=new AccessGate();gate.unlock();var count=new java.util.concurrent.atomic.AtomicInteger();
        Runnable bad=()->{throw new IllegalStateException();};Runnable good=count::incrementAndGet;
        gate.onInvalidation(bad);gate.onInvalidation(good);gate.emergency().request();
        assertEquals(EmergencyLock.State.INCOMPLETE,terminal(gate.emergency()).state());assertEquals(1,count.get());denied(gate::enter);
    }

    @Test public void activeExportIsClosedAndOldProviderCannotResumeAfterUnlock() throws Exception {
        var gate=new AccessGate();gate.unlock();var lease=gate.enter();var service=gate.emergency();
        var writing=new CountDownLatch(1);var closed=new CountDownLatch(1);var ended=new CountDownLatch(1);
        var bytes=new java.util.concurrent.atomic.AtomicInteger();
        java.io.OutputStream output=new java.io.OutputStream() {
            public void write(int value) {throw new AssertionError();}
            public void write(byte[] data,int offset,int length) throws java.io.IOException {
                bytes.addAndGet(length);writing.countDown();
                try {if(!closed.await(2,TimeUnit.SECONDS))throw new java.io.IOException("No close");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new java.io.IOException();}
            }
            public void close(){closed.countDown();}
        };
        var transferred=new FutureTask<Void>(()->{
            try {DocumentIO.write(gate,lease,new byte[20000],()->output);fail("Export survived");}
            catch(AccessGate.LockedException expected) {}
            catch(Exception e){throw new AssertionError(e);}
            finally {ended.countDown();}
            return null;
        });Thread transfer=new Thread(transferred);transfer.start();assertTrue(writing.await(2,TimeUnit.SECONDS));assertEquals(8192,bytes.get());
        service.request();assertTrue(ended.await(2,TimeUnit.SECONDS));transferred.get(1,TimeUnit.SECONDS);
        assertEquals(EmergencyLock.State.CLOSED,terminal(service).state());assertEquals(8192,bytes.get());
        gate.unlock(service.prepareAuthentication());denied(()->gate.check(lease));
    }
    @Test public void stuckResourceTimesOutWithoutBlockingIndependentClosure() throws Exception {
        var gate=new AccessGate();gate.unlock();var service=gate.emergency();var never=new CompletableFuture<Void>();
        service.register(EmergencyLock.Subsystem.DOCUMENTS,()->never);
        var stopped=new CountDownLatch(1);
        service.register(EmergencyLock.Subsystem.LOCATION,()->{stopped.countDown();return CompletableFuture.completedFuture(null);});
        service.request();assertTrue(stopped.await(1,TimeUnit.SECONDS));
        assertEquals(EmergencyLock.State.INCOMPLETE,terminal(service).state());
        never.complete(null);denied(service::prepareAuthentication);denied(gate::unlock);
    }
    @Test public void concurrentProviderCloseFailureCannotBeReportedAsClosed() throws Exception {
        var gate=new AccessGate();gate.unlock();var lease=gate.enter();
        var writing=new CountDownLatch(1);var closeStarted=new CountDownLatch(1);var releaseClose=new CountDownLatch(1);
        java.io.OutputStream output=new java.io.OutputStream() {
            public void write(int value){throw new AssertionError();}
            public void write(byte[] value,int offset,int count) throws java.io.IOException {
                writing.countDown();
                try {if(!closeStarted.await(2,TimeUnit.SECONDS))throw new AssertionError("No cancellation");}
                catch(InterruptedException interrupted){throw new java.io.IOException(interrupted);}
            }
            public void close() throws java.io.IOException {
                closeStarted.countDown();
                try {if(!releaseClose.await(3,TimeUnit.SECONDS))throw new AssertionError("No provider release");}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
                throw new java.io.IOException("Synthetic close failure");
            }
        };
        var task=new FutureTask<Void>(()->{
            try {DocumentIO.write(gate,lease,new byte[9000],()->output);fail("Export survived");}
            catch(SecurityException | java.io.IOException expected) {}
            return null;
        });
        Thread thread=new Thread(task);thread.start();assertTrue(writing.await(2,TimeUnit.SECONDS));
        gate.emergency().request();assertTrue(closeStarted.await(2,TimeUnit.SECONDS));
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
        while(thread.isAlive() && thread.getState()!=Thread.State.BLOCKED && System.nanoTime()<until)Thread.sleep(1);
        releaseClose.countDown();task.get(2,TimeUnit.SECONDS);
        assertEquals(EmergencyLock.State.INCOMPLETE,terminal(gate.emergency()).state());
    }

    @Test public void simultaneousRequestsShareOneGenerationAndOneResourceClose() throws Exception {
        var gate=new AccessGate();gate.unlock();var ready=new CountDownLatch(1);
        var count=new java.util.concurrent.atomic.AtomicInteger();
        gate.emergency().register(EmergencyLock.Subsystem.MEDIA,()->{count.incrementAndGet();return CompletableFuture.completedFuture(null);});
        var workers=Executors.newFixedThreadPool(8);
        try {
            var calls=new java.util.ArrayList<Future<EmergencyLock.Status>>();
            for(int i=0;i<8;i++)calls.add(workers.submit(()->{assertTrue(ready.await(2,TimeUnit.SECONDS));return gate.emergency().request();}));
            ready.countDown();long requested=calls.get(0).get(2,TimeUnit.SECONDS).requestedNanos();
            for(var call:calls)assertEquals(requested,call.get(2,TimeUnit.SECONDS).requestedNanos());
            assertEquals(EmergencyLock.State.CLOSED,terminal(gate.emergency()).state());assertEquals(1,count.get());
        } finally {ready.countDown();workers.shutdownNow();assertTrue(workers.awaitTermination(2,TimeUnit.SECONDS));}
    }

}
