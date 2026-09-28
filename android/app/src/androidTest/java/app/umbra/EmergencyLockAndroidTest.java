package app.umbra;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.umbra.core.*;
import app.umbra.data.Vault;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real SQLite/AndroidKeyStore in the existing isolated password fixture, never hardware evidence. */
@RunWith(AndroidJUnit4.class)
public final class EmergencyLockAndroidTest {
    private final DeviceVaultPasswordTest fixture=new DeviceVaultPasswordTest();
    private static byte[] password(){return "synthetic emergency password".getBytes(StandardCharsets.UTF_8);}
    @Before public void before() throws Exception {fixture.before();}
    @After public void after() throws Exception {fixture.after();}
    private EmergencyLock.Status terminal() throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(7);
        EmergencyLock.Status status;
        do {
            status=fixture.gate.emergency().status();
            if(status.state()==EmergencyLock.State.CLOSED || status.state()==EmergencyLock.State.INCOMPLETE) {
                android.os.Bundle receipt=new android.os.Bundle();
                receipt.putString("emergencyState",status.state().name());
                receipt.putString("emergencyRequestNanos",Long.toString(status.requestedNanos()));
                receipt.putString("emergencyInvalidatedNanos",Long.toString(status.invalidatedNanos()));
                receipt.putString("emergencyFinishedNanos",Long.toString(status.finishedNanos()));
                InstrumentationRegistry.getInstrumentation().sendStatus(0,receipt);
                return status;
            }
            Thread.sleep(10);
        } while(System.nanoTime()<deadline);
        throw new AssertionError("Emergency coordinator did not settle");
    }
    @Test public void activeSqliteTransactionRollsBackBeforeClosureAndRequiresNewPassword() throws Exception {
        Vault vault=fixture.vault;AccessGate gate=fixture.gate;
        vault.createPassword(password());gate.unlock();vault.unlock(password());
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var executor=Executors.newSingleThreadExecutor();
        try {
            Future<Void> pending=executor.submit(()->vault.transaction(()->{
                vault.put("session","pending",new byte[]{4,5,6});entered.countDown();
                if(!release.await(3,TimeUnit.SECONDS))throw new AssertionError("No release");
                return null;
            }));
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            var requested=gate.emergency().request();
            assertTrue(requested.invalidatedNanos()>=requested.requestedNanos());
            assertThrows(SecurityException.class,gate::enter);
            assertNotEquals(EmergencyLock.State.CLOSED,requested.state());
            release.countDown();
            var failure=assertThrows(ExecutionException.class,()->pending.get(5,TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof SecurityException);
            assertEquals(EmergencyLock.State.CLOSED,terminal().state());
            assertThrows(SecurityException.class,gate::unlock);
            gate.unlock(gate.emergency().prepareAuthentication());
            assertThrows(SecurityException.class,()->vault.get("session","ratchet"));
            vault.unlock(password());
            assertNull(vault.get("session","pending"));
            assertArrayEquals(new byte[]{9,8,7},vault.get("session","ratchet"));
        } finally {release.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test public void committedRecordsSurviveAndRestartHasNoAuthorization() throws Exception {
        Vault vault=fixture.vault;AccessGate gate=fixture.gate;
        vault.createPassword(password());gate.unlock();vault.unlock(password());
        vault.transaction(()->{vault.put("session","committed",new byte[]{7,6});return null;});
        Runnable old=vault.authorization();
        gate.emergency().request();assertEquals(EmergencyLock.State.CLOSED,terminal().state());
        assertEquals(Vault.State.LOCKED,vault.getVaultState());
        gate.unlock(gate.emergency().prepareAuthentication());vault.unlock(password());
        assertThrows(SecurityException.class,old::run);
        assertArrayEquals(new byte[]{7,6},vault.get("session","committed"));
        AccessGate restarted=new AccessGate();assertThrows(SecurityException.class,restarted::enter);
        // New gate is a domain restart model, not an OS process-death claim.
    }
    @Test public void failedPasswordRewrapAndEmergencyPreserveOldPassword() throws Exception {
        Vault vault=fixture.vault;AccessGate gate=fixture.gate;
        vault.createPassword(password());gate.unlock();vault.unlock(password());
        vault.getWritableDatabase().execSQL("CREATE TRIGGER emergency_rewrap_failure BEFORE UPDATE ON vault_protection BEGIN SELECT RAISE(ABORT,'synthetic'); END");
        byte[] replacement="synthetic replacement password".getBytes(StandardCharsets.UTF_8);
        assertThrows(Exception.class,()->vault.changePassword(password(),replacement));
        gate.emergency().request();assertEquals(EmergencyLock.State.CLOSED,terminal().state());
        gate.unlock(gate.emergency().prepareAuthentication());
        assertThrows(SecurityException.class,()->vault.unlock(replacement));
        vault.unlock(password());assertArrayEquals(new byte[]{1,2,3,4},vault.get("meta","identity"));
    }
    @Test public void failedParticipantCannotPreventVaultClosureOrPermitUnlock() throws Exception {
        fixture.vault.createPassword(password());fixture.gate.unlock();fixture.vault.unlock(password());
        fixture.gate.emergency().register(EmergencyLock.Subsystem.MEDIA,()->{throw new IllegalStateException("Synthetic closure failure");});
        fixture.gate.emergency().request();
        assertEquals(EmergencyLock.State.INCOMPLETE,terminal().state());
        assertEquals(Vault.State.LOCKED,fixture.vault.getVaultState());
        assertThrows(SecurityException.class,fixture.gate::unlock);
        assertThrows(SecurityException.class,()->fixture.gate.emergency().prepareAuthentication());
        assertTrue(fixture.file.exists());
    }
    @Test public void admissionApprovalWaitingForTransactionCannotCommitAfterEmergency() throws Exception {
        try(var adminRecords=new app.umbra.lab.SqliteDeviceRecords();var memberRecords=new app.umbra.lab.SqliteDeviceRecords()) {
            var admin=new app.umbra.crypto.Engine(adminRecords);var member=new app.umbra.crypto.Engine(memberRecords);
            admin.initialize("Synthetic emergency administrator");member.initialize("Synthetic emergency member");
            member.admission().installRealmConfig(admin.admission().createAdmissionRealm(true).encode(),true);
            var request=member.admission().createAdmissionRequest();
            var review=admin.admission().reviewAdmissionRequest(request.wire());
            var waiting=new CountDownLatch(1);var release=new CountDownLatch(1);
            var worker=Executors.newSingleThreadExecutor();
            adminRecords.beforeTransaction=()->{
                waiting.countDown();
                try {if(!release.await(3,TimeUnit.SECONDS))throw new AssertionError("Approval barrier not released");}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException();}
            };
            try {
                var approval=worker.submit(()->admin.admission().approveAdmission(review,true,3600));
                assertTrue(waiting.await(2,TimeUnit.SECONDS));admin.emergencyLock();release.countDown();
                var rejected=assertThrows(ExecutionException.class,()->approval.get(5,TimeUnit.SECONDS));
                assertTrue(rejected.getCause() instanceof SecurityException);
                long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);
                while(admin.emergency().status().state()==EmergencyLock.State.CLOSING && System.nanoTime()<until)Thread.sleep(10);
                assertEquals(EmergencyLock.State.CLOSED,admin.emergency().status().state());
                adminRecords.beforeTransaction=null;
                adminRecords.gate.unlock(admin.emergency().prepareAuthentication());adminRecords.reopen();
                assertTrue(adminRecords.keys("admission-decisions").isEmpty());
                assertThrows(SecurityException.class,()->admin.admission().approveAdmission(review,true,3600));
            } finally {release.countDown();adminRecords.beforeTransaction=null;worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}
        }
    }

    @Test public void realSyntheticLocationProviderStopsAfterPositiveEncryptedDelivery() throws Exception {
        var instrumentation=InstrumentationRegistry.getInstrumentation();var context=instrumentation.getTargetContext();
        String pkg=context.getPackageName();
        instrumentation.getUiAutomation().grantRuntimePermission(pkg,android.Manifest.permission.ACCESS_COARSE_LOCATION);
        // Do not grant FINE to a previously coarse-only package: the following
        // coarse-provider regression must retain its real Android permission level.
        // Dedicated startup labs already granted FINE explicitly; respect either setup.
        int originalFine=context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION);
        String provider=originalFine==android.content.pm.PackageManager.PERMISSION_GRANTED
            ?android.location.LocationManager.GPS_PROVIDER:android.location.LocationManager.NETWORK_PROVIDER;
        LocationAndroidTest.shell("appops set "+pkg+" android:mock_location allow");
        LocationAndroidTest.shell("cmd location set-location-enabled true --user 0");
        var manager=context.getSystemService(android.location.LocationManager.class);
        var worker=Executors.newSingleThreadExecutor();app.umbra.location.AndroidLocationCapture capture=null;
        try(var visible=androidx.test.core.app.ActivityScenario.launch(app.umbra.ui.MainActivity.class);
            var ar=new app.umbra.lab.SqliteDeviceRecords();var br=new app.umbra.lab.SqliteDeviceRecords()) {
            var a=new app.umbra.crypto.Engine(ar,android.os.SystemClock::elapsedRealtime);
            var b=new app.umbra.crypto.Engine(br,android.os.SystemClock::elapsedRealtime);LocationAndroidTest.pair(a,b,ar,br);
            String sender=a.id();String id=a.locations().start(a.locations().review(b.id(),app.umbra.location.LocationPayload.Mode.ZONE,120,true),true);
            manager.addTestProvider(provider,new android.location.provider.ProviderProperties.Builder()
                .setAccuracy(android.location.provider.ProviderProperties.ACCURACY_FINE).setPowerUsage(android.location.provider.ProviderProperties.POWER_USAGE_HIGH).build());
            manager.setTestProviderEnabled(provider,true);
            var measured=new CountDownLatch(1);var callbacks=new java.util.concurrent.atomic.AtomicInteger();
            capture=new app.umbra.location.AndroidLocationCapture(context,worker,()->true,a.locations(),message->{
                if(message.startsWith("Ubicación activa")){callbacks.incrementAndGet();measured.countDown();}
            });
            var active=capture;worker.submit(()->{active.start(id,app.umbra.location.LocationPayload.Mode.ZONE,true);return null;}).get(5,TimeUnit.SECONDS);
            var point=new android.location.Location(provider);
            point.setLatitude(12.345678);point.setLongitude(45.678912);point.setAccuracy(5);
            point.setTime(System.currentTimeMillis());point.setElapsedRealtimeNanos(android.os.SystemClock.elapsedRealtimeNanos());
            manager.setTestProviderLocation(provider,point);
            assertTrue("Missing positive provider callback",measured.await(20,TimeUnit.SECONDS));
            for(var row:a.outbox())b.receive(row.getJSONObject("envelope"));
            assertTrue(b.locations().received(sender).get(0).has("lastPoint"));
            var requested=a.emergencyLock();long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(6);
            while(a.emergency().status().state()==EmergencyLock.State.CLOSING && System.nanoTime()<until)Thread.sleep(10);
            assertEquals(EmergencyLock.State.CLOSED,a.emergency().status().state());assertNull(active.activeSession());
            int before=callbacks.get();long observed=System.nanoTime();
            point.setTime(System.currentTimeMillis());point.setElapsedRealtimeNanos(android.os.SystemClock.elapsedRealtimeNanos());
            manager.setTestProviderLocation(provider,point);Thread.sleep(1000);
            assertEquals(before,callbacks.get());assertNull(active.activeSession());
            assertThrows(SecurityException.class,()->active.start(id,app.umbra.location.LocationPayload.Mode.ZONE,true));
            var receipt=new android.os.Bundle();receipt.putString("emergencyLocationRequestNanos",Long.toString(requested.requestedNanos()));
            receipt.putString("emergencyLocationObservedNanos",Long.toString(System.nanoTime()-observed));
            receipt.putString("emergencyLocation", "PASS synthetic AOSP provider, Signal delivery, removeUpdates and stale start denied");
            instrumentation.sendStatus(0,receipt);
        } finally {
            if(capture!=null)capture.close();worker.shutdownNow();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));
            manager.removeTestProvider(provider);
            assertEquals("Fixture changed fine permission",originalFine,context.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION));
            LocationAndroidTest.shell("appops set "+pkg+" android:mock_location deny");
        }
    }

}
