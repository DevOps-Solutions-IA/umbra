package app.umbra;

import android.Manifest;
import android.location.*;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.umbra.core.Bytes;
import app.umbra.crypto.Engine;
import app.umbra.devices.DeviceService;
import app.umbra.lab.SqliteDeviceRecords;
import app.umbra.location.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Real AOSP location callback + synthetic test-provider injection, SQLite and libsignal. Not physical GPS. */
@RunWith(AndroidJUnit4.class)
public class LocationAndroidTest {
    static void pair(Engine a,Engine b,SqliteDeviceRecords ar,SqliteDeviceRecords br) throws Exception {
        a.initialize("Synthetic Location A"); b.initialize("Synthetic Location B");
        a.importCard(b.createCard()); b.importCard(a.createCard());
        a.verify(b.id(),Bytes.safetyCode(a.id(),b.id())); b.verify(a.id(),Bytes.safetyCode(a.id(),b.id()));
        DeviceService ad=new DeviceService(ar),bd=new DeviceService(br); ad.migrate(); bd.migrate();
        String aList=ad.roster(a.id()),bList=bd.roster(b.id()); ad.apply(bList); bd.apply(aList);
        var ac=ad.reviewRoster(bList); ad.approveRoster(ac,ac.fingerprint(),true);
        var bc=bd.reviewRoster(aList); bd.approveRoster(bc,bc.fingerprint(),true);
    }
    private static void shell(String command) throws Exception {
        try(var fd=InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);
            var input=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)) { input.readAllBytes(); }
    }
    private static void permissionEvidence() {
        var i=InstrumentationRegistry.getInstrumentation(); var c=i.getTargetContext();
        var ops=c.getSystemService(android.app.AppOpsManager.class); android.os.Bundle status=new android.os.Bundle();
        status.putString("locationPermissionEvidence","fine="+c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)+
            ",coarse="+c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)+
            ",fineOp="+ops.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_FINE_LOCATION,android.os.Process.myUid(),c.getPackageName())+
            ",coarseOp="+ops.unsafeCheckOpNoThrow(android.app.AppOpsManager.OPSTR_COARSE_LOCATION,android.os.Process.myUid(),c.getPackageName()));
        i.sendStatus(0,status);
    }
    @Test public void frameworkSyntheticProviderReducedBeforeSignalAndSqlitePersistence() throws Exception {
        var instrumentation=InstrumentationRegistry.getInstrumentation(); var context=instrumentation.getTargetContext();
        String pkg=context.getPackageName(); var ui=instrumentation.getUiAutomation();
        ui.grantRuntimePermission(pkg,Manifest.permission.ACCESS_COARSE_LOCATION); ui.grantRuntimePermission(pkg,Manifest.permission.ACCESS_FINE_LOCATION);
        shell("appops set "+pkg+" android:mock_location allow"); shell("cmd location set-location-enabled true --user 0");
        LocationManager manager=context.getSystemService(LocationManager.class);
        ExecutorService worker=Executors.newSingleThreadExecutor(); AndroidLocationCapture capture=null;
        try(var visible=androidx.test.core.app.ActivityScenario.launch(app.umbra.ui.MainActivity.class);var ar=new SqliteDeviceRecords();var br=new SqliteDeviceRecords()) {
            Engine a=new Engine(ar,SystemClock::elapsedRealtime),b=new Engine(br,SystemClock::elapsedRealtime); pair(a,b,ar,br);
            String id=a.locations().start(a.locations().review(b.id(),LocationPayload.Mode.ZONE,120,false),true);
            manager.addTestProvider(LocationManager.GPS_PROVIDER,new android.location.provider.ProviderProperties.Builder()
                .setHasSatelliteRequirement(true).setAccuracy(android.location.provider.ProviderProperties.ACCURACY_FINE)
                .setPowerUsage(android.location.provider.ProviderProperties.POWER_USAGE_HIGH).build());
            manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER,true);
            CountDownLatch measured=new CountDownLatch(1);
            permissionEvidence(); capture=new AndroidLocationCapture(context,worker,() -> true,a.locations(),message -> { if(message.equals("Punto cifrado en cola")) measured.countDown(); });
            AndroidLocationCapture active=capture; worker.submit(() -> { active.start(id,LocationPayload.Mode.ZONE,false); return null; }).get(10,TimeUnit.SECONDS);
            Location synthetic=new Location(LocationManager.GPS_PROVIDER); synthetic.setLatitude(12.345678); synthetic.setLongitude(45.678912);
            synthetic.setAccuracy(5); synthetic.setTime(System.currentTimeMillis()); synthetic.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            manager.setTestProviderLocation(LocationManager.GPS_PROVIDER,synthetic);
            assertTrue("Framework location callback missing",measured.await(20,TimeUnit.SECONDS));
            JSONObject envelope=a.outbox().get(0).getJSONObject("envelope"); b.receive(envelope);
            br.reopen(); JSONObject received=new Engine(br).locations().received(a.id()).get(0);
            assertEquals(1000000,received.getJSONObject("lastPoint").getLong("cellE7"));
            assertNotEquals(123456780,received.getJSONObject("lastPoint").getLong("latE7"));
            assertEquals(5000,received.getJSONObject("lastPoint").getLong("sensorAccuracyMm"));
            for(String bucket:new String[]{"location-out","location-in","message","outbox"}) for(String key:ar.keys(bucket)) assertFalse(Bytes.text(ar.get(bucket,key)).contains("123456780"));
        } finally {
            if(capture!=null) capture.close(); worker.shutdownNow(); assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));
            manager.removeTestProvider(LocationManager.GPS_PROVIDER); shell("appops set "+pkg+" android:mock_location deny");
        }
    }
    @Test public void absentPermissionRejectsProviderButManualChatStillWorks() throws Exception {
        var context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        // Shell appops denial exercises framework rejection without killing this instrumentation UID.
        shell("appops set --uid "+context.getPackageName()+" FINE_LOCATION deny"); shell("appops set --uid "+context.getPackageName()+" COARSE_LOCATION deny");
        ExecutorService worker=Executors.newSingleThreadExecutor(); AndroidLocationCapture capture=null;
        try(var visible=androidx.test.core.app.ActivityScenario.launch(app.umbra.ui.MainActivity.class);var ar=new SqliteDeviceRecords();var br=new SqliteDeviceRecords()) {
            Engine a=new Engine(ar),b=new Engine(br); pair(a,b,ar,br);
            String id=a.locations().start(a.locations().review(b.id(),LocationPayload.Mode.PRECISE,120,false),true);
            permissionEvidence(); capture=new AndroidLocationCapture(context,worker,() -> true,a.locations(),message -> {});
            AndroidLocationCapture active=capture;
            assertThrows(SecurityException.class,() -> active.start(id,LocationPayload.Mode.PRECISE,false));
            assertTrue(a.outbox().isEmpty()); active.close(); a.locations().interrupt(id);
            a.locations().manual(a.locations().review(b.id(),LocationPayload.Mode.MANUAL,120,false),true,1,2);
            b.receive(a.outbox().get(0).getJSONObject("envelope")); assertEquals("MANUAL",b.locations().received(a.id()).get(0).getJSONObject("lastPoint").getString("source"));
            a.sendText(b.id(),"synthetic chat unaffected",600); assertFalse(a.messages(b.id()).isEmpty());
        } finally { if(capture!=null) capture.close(); worker.shutdownNow(); assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));
            shell("appops set --uid "+context.getPackageName()+" FINE_LOCATION foreground"); shell("appops set --uid "+context.getPackageName()+" COARSE_LOCATION foreground"); }
    }
    @Test public void sqliteRollbackReopenAndOldLeaseNeverResumeCapture() throws Exception {
        try(var visible=androidx.test.core.app.ActivityScenario.launch(app.umbra.ui.MainActivity.class);var ar=new SqliteDeviceRecords();var br=new SqliteDeviceRecords()) {
            Engine a=new Engine(ar),b=new Engine(br); pair(a,b,ar,br);
            var c=a.locations().review(b.id(),LocationPayload.Mode.ZONE,900,true); String id=a.locations().start(c,true);
            ar.failBucket="location-out";
            assertThrows(IllegalStateException.class,() -> a.locations().publish(id,1,2,3,Bytes.now(),"ANDROID_FINE"));
            ar.failBucket=null; assertEquals(1,a.outbox().size());
            a.locations().publish(id,1,2,3,Bytes.now(),"ANDROID_FINE");
            JSONObject pending=a.outbox().get(1).getJSONObject("envelope");
            ar.gate.lock(); ar.gate.unlock(); assertThrows(SecurityException.class,() -> a.authorizeEnvelope(pending));
            ar.reopen(); Engine reopened=new Engine(ar); assertTrue(reopened.outbox().isEmpty());
            assertEquals("INTERRUPTED",reopened.get("location-out",id).getString("state"));
            assertThrows(SecurityException.class,() -> reopened.locations().authorizeCapture(id));
        }
    }
    @Test public void providerDisabledAndPermissionRevokedInterruptWithoutAutoResume() throws Exception {
        var instrumentation=InstrumentationRegistry.getInstrumentation(); var context=instrumentation.getTargetContext(); String pkg=context.getPackageName();
        instrumentation.getUiAutomation().grantRuntimePermission(pkg,Manifest.permission.ACCESS_COARSE_LOCATION);
        instrumentation.getUiAutomation().grantRuntimePermission(pkg,Manifest.permission.ACCESS_FINE_LOCATION);
        shell("appops set "+pkg+" android:mock_location allow"); shell("appops set --uid "+pkg+" FINE_LOCATION foreground");
        LocationManager manager=context.getSystemService(LocationManager.class); ExecutorService worker=Executors.newSingleThreadExecutor();
        AndroidLocationCapture capture=null;
        try(var visible=androidx.test.core.app.ActivityScenario.launch(app.umbra.ui.MainActivity.class);var ar=new SqliteDeviceRecords();var br=new SqliteDeviceRecords()) {
            Engine a=new Engine(ar),b=new Engine(br); pair(a,b,ar,br);
            manager.addTestProvider(LocationManager.GPS_PROVIDER,new android.location.provider.ProviderProperties.Builder()
                .setAccuracy(android.location.provider.ProviderProperties.ACCURACY_FINE).setPowerUsage(android.location.provider.ProviderProperties.POWER_USAGE_HIGH).build());
            manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER,true);
            for(boolean revoke:new boolean[]{false,true}) {
                CountDownLatch interrupted=new CountDownLatch(1);
                String id=a.locations().start(a.locations().review(b.id(),LocationPayload.Mode.PRECISE,900,true),true);
                permissionEvidence(); capture=new AndroidLocationCapture(context,worker,() -> true,a.locations(),message -> { if(message.startsWith("Ubicación interrumpida")) interrupted.countDown(); });
                capture.start(id,LocationPayload.Mode.PRECISE,true);
                if(revoke) shell("appops set --uid "+pkg+" FINE_LOCATION deny"); else manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER,false);
                assertTrue("Interruption callback missing",interrupted.await(10,TimeUnit.SECONDS));
                assertNull(capture.activeSession()); assertTrue(a.outbox().isEmpty());
                assertEquals("INTERRUPTED",a.get("location-out",id).getString("state"));
                manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER,true); shell("appops set --uid "+pkg+" FINE_LOCATION foreground");
                assertThrows(SecurityException.class,() -> a.locations().authorizeCapture(id)); capture.close();
            }
        } finally {
            if(capture!=null) capture.close(); worker.shutdownNow(); assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));
            manager.removeTestProvider(LocationManager.GPS_PROVIDER); shell("appops set "+pkg+" android:mock_location deny"); shell("appops set --uid "+pkg+" FINE_LOCATION foreground");
        }
    }
    @Test public void coarseOnlyUsesAospNetworkProviderAndNeverGpsFallback() throws Exception {
        var instrumentation=InstrumentationRegistry.getInstrumentation(); var context=instrumentation.getTargetContext(); String pkg=context.getPackageName();
        instrumentation.getUiAutomation().grantRuntimePermission(pkg,Manifest.permission.ACCESS_COARSE_LOCATION);
        shell("appops set "+pkg+" android:mock_location allow"); shell("appops set --uid "+pkg+" FINE_LOCATION deny"); shell("appops set --uid "+pkg+" COARSE_LOCATION foreground");
        shell("cmd location set-location-enabled true --user 0");
        LocationManager manager=context.getSystemService(LocationManager.class); ExecutorService worker=Executors.newSingleThreadExecutor(); AndroidLocationCapture capture=null;
        try(var visible=androidx.test.core.app.ActivityScenario.launch(app.umbra.ui.MainActivity.class);var ar=new SqliteDeviceRecords();var br=new SqliteDeviceRecords()) {
            Engine a=new Engine(ar,SystemClock::elapsedRealtime),b=new Engine(br); pair(a,b,ar,br);
            manager.addTestProvider(LocationManager.NETWORK_PROVIDER,new android.location.provider.ProviderProperties.Builder()
                .setAccuracy(android.location.provider.ProviderProperties.ACCURACY_COARSE).setPowerUsage(android.location.provider.ProviderProperties.POWER_USAGE_LOW).build());
            manager.setTestProviderEnabled(LocationManager.NETWORK_PROVIDER,true);
            String id=a.locations().start(a.locations().review(b.id(),LocationPayload.Mode.ZONE,120,false),true);
            CountDownLatch measured=new CountDownLatch(1);
            permissionEvidence(); capture=new AndroidLocationCapture(context,worker,() -> true,a.locations(),message -> { if(message.equals("Punto cifrado en cola")) measured.countDown(); });
            capture.start(id,LocationPayload.Mode.ZONE,false);
            Location synthetic=new Location(LocationManager.NETWORK_PROVIDER); synthetic.setLatitude(12.345678); synthetic.setLongitude(45.678912);
            synthetic.setAccuracy(3000); synthetic.setTime(System.currentTimeMillis()); synthetic.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            manager.setTestProviderLocation(LocationManager.NETWORK_PROVIDER,synthetic);
            assertTrue("Coarse framework callback missing",measured.await(20,TimeUnit.SECONDS));
            b.receive(a.outbox().get(0).getJSONObject("envelope"));
            assertEquals("ANDROID_COARSE",b.locations().received(a.id()).get(0).getJSONObject("lastPoint").getString("source"));
        } finally {
            if(capture!=null) capture.close(); worker.shutdownNow(); assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));
            manager.removeTestProvider(LocationManager.NETWORK_PROVIDER); shell("appops set "+pkg+" android:mock_location deny");
            shell("appops set --uid "+pkg+" FINE_LOCATION foreground"); shell("appops set --uid "+pkg+" COARSE_LOCATION foreground");
        }
    }

}
