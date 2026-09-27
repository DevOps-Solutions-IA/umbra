package app.umbra;

import app.umbra.connectivity.ConnectivityService;
import app.umbra.crypto.Engine;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;
import static app.umbra.connectivity.ConnectivityService.State.*;

/** Real admission signatures and access epochs; no assertion of physical network observation. */
public class ConnectivityServiceTest {
    private static final String ORIGIN="https://no-dns-before-consent.example.invalid";
    @Test public void admissionAndUnlockNeverConnectOrEnableNearby() throws Exception {
        var device=new DeviceLinkingTest.Device("Synthetic startup"); var service=device.e.connectivity();
        assertEquals(LOCKED_PRIVATE,service.getConnectivityState());
        assertFalse(service.isNetworkSessionAllowed()); assertFalse(service.isNearbySessionAllowed());
        assertThrows(SecurityException.class,()->service.networkLease(ORIGIN));
        service.vaultUnlocked(); assertEquals(UNLOCKED_OFFLINE,service.getConnectivityState());
        assertTrue(service.canConnect()); assertFalse(service.isNetworkSessionAllowed());
        assertThrows(SecurityException.class,()->service.connect(ORIGIN,false));
        assertEquals(UNLOCKED_OFFLINE,service.getConnectivityState());
        service.connect(ORIGIN,true);
        try { assertEquals(CONNECTED,service.getConnectivityState()); assertFalse(service.isNearbySessionAllowed()); }
        finally { service.disconnect(); }
    }
    @Test public void disconnectClosesResourcesAndOldFailureCannotCloseReconnection() throws Exception {
        var device=new DeviceLinkingTest.Device("Synthetic reconnect"); var service=device.e.connectivity();
        service.vaultUnlocked(); service.connect(ORIGIN,true);
        var old=service.networkLease(ORIGIN); var closes=new AtomicInteger(); old.attach(closes::incrementAndGet);
        service.disconnect(); assertEquals(1,closes.get()); assertEquals(UNLOCKED_OFFLINE,service.getConnectivityState());
        assertThrows(SecurityException.class,old::check);
        service.connect(ORIGIN,true);
        try { old.failed(); assertEquals(CONNECTED,service.getConnectivityState()); assertEquals(1,closes.get()); }
        finally { service.disconnect(); }
    }
    @Test public void lockCancelsNearbyAndOnlineAndOldEpochCannotInvalidateNewUnlock() throws Exception {
        var device=new DeviceLinkingTest.Device("Synthetic lock"); var service=device.e.connectivity();
        service.vaultUnlocked(); service.connect(ORIGIN,true);
        var old=service.networkLease(ORIGIN); var near=service.startNearby(true); var closes=new AtomicInteger();
        old.attach(closes::incrementAndGet); near.attach(closes::incrementAndGet);
        device.db.gate.lock(); assertEquals(2,closes.get()); assertEquals(LOCKED_PRIVATE,service.getConnectivityState());
        device.db.gate.unlock(); service.vaultUnlocked(); service.connect(ORIGIN,true);
        try { assertThrows(SecurityException.class,old::check); assertEquals(CONNECTED,service.getConnectivityState()); }
        finally { service.disconnect(); }
    }
    @Test public void epochWithoutAttachedResourceAlsoCannotLockNewSession() throws Exception {
        var device=new DeviceLinkingTest.Device("Synthetic callback"); var service=device.e.connectivity();
        service.vaultUnlocked(); service.connect(ORIGIN,true); var old=service.networkLease(ORIGIN);
        device.db.gate.lock(); device.db.gate.unlock(); service.vaultUnlocked(); service.connect(ORIGIN,true);
        try { assertThrows(SecurityException.class,old::check); assertEquals(CONNECTED,service.getConnectivityState()); }
        finally { service.disconnect(); }
    }
    @Test public void processLocalReconstructionStartsLockedWithoutInheritingConsent() throws Exception {
        var device=new DeviceLinkingTest.Device("Synthetic restart"); var service=device.e.connectivity();
        service.vaultUnlocked(); service.connect(ORIGIN,true);
        try { assertEquals(LOCKED_PRIVATE,new Engine(device.db).connectivity().getConnectivityState()); }
        finally { service.disconnect(); }
    }
    @Test public void missingAdmissionInvalidOriginAndOfflineEditionFailClosed() throws Exception {
        var db=new DeviceLinkingTest.Store(); var engine=new Engine(db); engine.initialize("Synthetic unadmitted");
        var service=engine.connectivity(); service.vaultUnlocked(); assertFalse(service.canConnect());
        assertThrows(Exception.class,()->service.connect(ORIGIN,true)); assertEquals(UNLOCKED_OFFLINE,service.getConnectivityState());
        AdmissionFixture.enroll(engine);
        assertThrows(IllegalArgumentException.class,()->service.connect("http://relay.example.invalid",true));
        var offline=new ConnectivityService(db,engine.admission(),false); offline.vaultUnlocked();
        assertThrows(SecurityException.class,()->offline.connect(ORIGIN,true));
        assertEquals(UNLOCKED_OFFLINE,offline.getConnectivityState());
    }
    @Test public void lossRequiresNewConsentAndDoesNotRestartNearby() throws Exception {
        var device=new DeviceLinkingTest.Device("Synthetic loss"); var service=device.e.connectivity();
        service.vaultUnlocked(); service.connect(ORIGIN,true); var epoch=service.onlineEpochAuthorization();
        service.networkLost(); assertEquals(OFFLINE_ERROR,service.getConnectivityState());
        assertFalse(service.isNetworkSessionAllowed()); assertThrows(SecurityException.class,epoch::run);
        assertFalse(service.isNearbySessionAllowed()); service.disconnect(); assertEquals(UNLOCKED_OFFLINE,service.getConnectivityState());
    }
    @Test public void callbacksAreAllAttemptedButCleanupFailureIsNotHidden() throws Exception {
        var device=new DeviceLinkingTest.Device("Synthetic cleanup"); var service=device.e.connectivity();
        service.vaultUnlocked(); service.connect(ORIGIN,true); var second=new AtomicInteger();
        service.networkLease(ORIGIN).attach(()->{throw new IllegalStateException("Synthetic cleanup failure");});
        service.networkLease(ORIGIN).attach(second::incrementAndGet);
        service.disconnect(); assertTrue(service.cleanupFailed()); assertEquals(1,second.get());
        assertFalse(service.canConnect()); assertThrows(SecurityException.class,()->service.connect(ORIGIN,true));
        assertFalse(service.isNetworkSessionAllowed());
    }
    @Test public void lockDuringCleanupCannotExposeNewEpochAheadOfOldCallbacks() throws Exception {
        var device=new DeviceLinkingTest.Device("Synthetic cleanup race"); var service=device.e.connectivity();
        service.vaultUnlocked(); service.connect(ORIGIN,true);
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        service.networkLease(ORIGIN).attach(()->{
            entered.countDown();
            try { if(!release.await(3,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("Cleanup barrier not released"); }
            catch(InterruptedException interrupted) {Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
        });
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var pending=pool.submit(service::disconnect);
            assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS));
            device.db.gate.lock();device.db.gate.unlock();
            assertEquals(DISCONNECTING,service.getConnectivityState());
            assertThrows(SecurityException.class,service::vaultUnlocked);
            release.countDown();pending.get(3,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(LOCKED_PRIVATE,service.getConnectivityState());service.vaultUnlocked();
            assertEquals(UNLOCKED_OFFLINE,service.getConnectivityState());
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test public void lockWhileAdmissionTransactionWaitsNeverPublishesConnected() throws Exception {
        var device=new DeviceLinkingTest.Device("Synthetic connect race");var service=device.e.connectivity();service.vaultUnlocked();
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        device.db.before=()->{entered.countDown();if(!release.await(3,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Admission barrier");return null;};
        var pool=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var pending=pool.submit(()->{assertThrows(SecurityException.class,()->service.connect(ORIGIN,true));});
            assertTrue(entered.await(3,java.util.concurrent.TimeUnit.SECONDS));device.db.gate.lock();release.countDown();
            pending.get(3,java.util.concurrent.TimeUnit.SECONDS);assertEquals(LOCKED_PRIVATE,service.getConnectivityState());
        } finally {release.countDown();pool.shutdownNow();}
    }
    @Test public void onlineLeaseCannotAuthorizeBluetooth() throws Exception {
        var device=new DeviceLinkingTest.Device("Synthetic separate consent");var service=device.e.connectivity();
        service.vaultUnlocked();service.connect(ORIGIN,true);
        try {assertThrows(SecurityException.class,service.networkLease(ORIGIN)::checkNearby);}
        finally {service.disconnect();}
    }
    @Test public void failedAdmissionReadNeverLeavesConnectingOrAnOpenGrant() throws Exception {
        var device=new DeviceLinkingTest.Device("Synthetic storage failure");var service=device.e.connectivity();service.vaultUnlocked();
        device.db.before=()->{throw new IllegalStateException("Synthetic disk read failure");};
        assertThrows(IllegalStateException.class,()->service.connect(ORIGIN,true));
        assertEquals(UNLOCKED_OFFLINE,service.getConnectivityState());
        assertThrows(SecurityException.class,()->service.networkLease(ORIGIN));
        service.connect(ORIGIN,true);service.disconnect();
    }
}
