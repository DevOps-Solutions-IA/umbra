package app.umbra;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real Engine/libsignal cancellation; adapter state is synthetic, not native media evidence. */
public final class ConnectedCancellationPublicationTest {
    @Test public void interruptedSnapshotPrecedesAdapterTerminalPublication() throws Exception {
        var p=new ConnectedVideoSignalingTest.VideoPair();
        var media=p.ae.calls().prepareMedia(p.ae.calls().reviewMedia(p.id,"a".repeat(64)),true);
        var entered=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var active=new AtomicBoolean(true);
        var failure=new AtomicReference<Throwable>();
        media.attach(()->{
            entered.countDown();
            try {
                if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("Callback release missing");
                active.set(false);
            } catch(InterruptedException interrupted) {
                Thread.currentThread().interrupt();throw new AssertionError(interrupted);
            }
        });
        Thread cancellation=new Thread(()->{
            try {p.ae.calls().cancelPending(p.id);}catch(Throwable rejected){failure.set(rejected);}
        },"synthetic-cancellation-publication");
        var reads=new AtomicInteger();
        p.a.db.afterRead=(bucket,key)->{
            if(bucket.equals("calls") && key.equals(p.id) && reads.incrementAndGet()==2) {
                p.a.db.afterRead=null; // Session's read after maintain(), before its final lease check.
                cancellation.start();
                try {assertTrue("Cancellation callback reached",entered.await(5,TimeUnit.SECONDS));}
                catch(InterruptedException interrupted) {
                    Thread.currentThread().interrupt();throw new AssertionError(interrupted);
                }
            }
        };
        try {
            SecurityException rejected=assertThrows(SecurityException.class,()->p.ae.calls().session(p.id));
            assertEquals("Call interrupted",rejected.getMessage());
            assertTrue("Snapshot rejection precedes adapter terminal publication",active.get());
            // The old fixture's catch rethrows in this controlled real-domain window.
            assertSame(rejected,assertThrows(SecurityException.class,()->{if(active.get())throw rejected;}));
            assertThrows(SecurityException.class,media::snapshot);
        } finally {
            p.a.db.afterRead=null;release.countDown();cancellation.join(5000);
        }
        assertFalse("Cancellation thread completed",cancellation.isAlive());
        if(failure.get()!=null)throw new AssertionError("Cancellation failed",failure.get());
        assertFalse("Adapter terminal publication is still required",active.get());
        assertThrows(SecurityException.class,media::snapshot);
    }
}
