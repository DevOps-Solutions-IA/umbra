package app.umbra;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real Engine/Signal authorization; not a native media transport test. */
public final class ConnectedMediaAuthorizationTest {
    private static final String REVISION="a".repeat(64);
    @Test public void requiresSelectionFreshLocalConsentAndSingleAttachment() throws Exception {
        var p=new ConnectedCallTest.Pair(); String id=p.invite();
        assertThrows(SecurityException.class,()->p.ae.calls().reviewMedia(id,REVISION));
        p.be.calls().accept(p.be.calls().reviewAccept(id,app.umbra.calls.CallPayload.NetworkPolicy.RELAY_ONLY),true);
        ConnectedCallTest.deliver(p.be,p.ae); ConnectedCallTest.deliver(p.ae,p.be);
        var consent=p.ae.calls().reviewMedia(id,REVISION);
        assertThrows(SecurityException.class,()->p.ae.calls().prepareMedia(consent,false));
        assertThrows(SecurityException.class,()->p.be.calls().prepareMedia(consent,true));
        var media=p.ae.calls().prepareMedia(consent,true);
        assertEquals("SELECTED",media.snapshot().getString("state")); // Not ACTIVE.
        AtomicInteger cancelled=new AtomicInteger(); media.attach(cancelled::incrementAndGet);
        assertThrows(SecurityException.class,()->media.attach(cancelled::incrementAndGet));
        assertThrows(SecurityException.class,()->p.ae.calls().prepareMedia(consent,true));
        media.close(); assertEquals(1,cancelled.get());
        assertThrows(SecurityException.class,media::snapshot);
        p.ae.calls().cancelLocal(); assertEquals(1,cancelled.get());
    }
    @Test public void cancellationAndTrustLossInvalidateLocalMediaWithoutRelay() throws Exception {
        var p=new ConnectedCallTest.Pair(); String id=p.selected();
        var media=p.ae.calls().prepareMedia(p.ae.calls().reviewMedia(id,REVISION),true);
        AtomicInteger cancelled=new AtomicInteger(); media.attach(cancelled::incrementAndGet);
        p.ae.calls().cancelPending(id);
        assertEquals(1,cancelled.get()); assertThrows(SecurityException.class,media::snapshot);
        var q=new ConnectedCallTest.Pair(); String other=q.selected();
        var remote=q.be.calls().prepareMedia(q.be.calls().reviewMedia(other,REVISION),true);
        remote.attach(cancelled::incrementAndGet);
        q.be.block(q.a.e.id(),true);
        assertEquals(2,cancelled.get()); assertThrows(SecurityException.class,remote::snapshot);
    }
    @Test public void oldUnlockAndExpiredConsentCannotAuthorizeCapture() throws Exception {
        var p=new ConnectedCallTest.Pair(); String id=p.selected();
        var consent=p.ae.calls().reviewMedia(id,REVISION);
        p.time.addAndGet(30_001);
        assertThrows(SecurityException.class,()->p.ae.calls().prepareMedia(consent,true));
        var q=new ConnectedCallTest.Pair(); String other=q.selected();
        var old=q.ae.calls().reviewMedia(other,REVISION);
        q.a.db.gate.lock(); q.a.db.gate.unlock();
        assertThrows(SecurityException.class,()->q.ae.calls().prepareMedia(old,true));
    }
    @Test public void brokenAdapterCallbackCannotPreventLocalLockCancellation() throws Exception {
        var p=new ConnectedCallTest.Pair();String id=p.selected();
        var media=p.ae.calls().prepareMedia(p.ae.calls().reviewMedia(id,REVISION),true);
        media.attach(()->{throw new IllegalStateException("Synthetic adapter cleanup failure");});
        p.ae.calls().cancelLocal();
        assertTrue(media.cancellationFailed());
        assertThrows(SecurityException.class,media::snapshot);
    }
    @Test public void expiredSessionAndRemoteEndStopAnAttachedLease() throws Exception {
        var p=new ConnectedCallTest.Pair(); String id=p.selected();
        var media=p.ae.calls().prepareMedia(p.ae.calls().reviewMedia(id,REVISION),true);
        AtomicInteger cancelled=new AtomicInteger(); media.attach(cancelled::incrementAndGet);
        p.be.calls().end(id); ConnectedCallTest.deliver(p.be,p.ae);
        assertEquals(1,cancelled.get()); assertThrows(SecurityException.class,media::snapshot);
        var q=new ConnectedCallTest.Pair(); String other=q.selected();
        var later=q.ae.calls().prepareMedia(q.ae.calls().reviewMedia(other,REVISION),true);
        later.attach(cancelled::incrementAndGet); q.time.addAndGet(180_000);
        assertThrows(SecurityException.class,later::snapshot); assertEquals(2,cancelled.get());
    }
}
