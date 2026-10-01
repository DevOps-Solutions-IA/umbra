package app.umbra;

import android.content.Context;
import android.content.ContextWrapper;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.umbra.core.AccessGate;
import app.umbra.data.Vault;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real Vault/SQLite/Argon2 with existing isolated AndroidKeyStore lab fixture.
 * Controlled monotonic time tests authorization, not Android hardware authentication.
 * No production Activity, permission, network or sensor is modified by this fixture. */
@RunWith(AndroidJUnit4.class)
public class DeviceAccessReadinessTest {
    private DeviceVaultPasswordTest fixture;
    private final AtomicLong clock = new AtomicLong(TimeUnit.SECONDS.toNanos(1));
    private Context isolated;
    private Vault vault;
    private AccessGate gate;
    private static byte[] password() { return "synthetic readiness alpha".getBytes(StandardCharsets.UTF_8); }
    private static byte[] replacement() { return "synthetic readiness beta".getBytes(StandardCharsets.UTF_8); }
    @Before public void before() throws Exception {
        fixture = new DeviceVaultPasswordTest(); fixture.before(); fixture.vault.close();
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        isolated = new ContextWrapper(target) {
            @Override public java.io.File getDatabasePath(String ignored) { return fixture.file; }
            @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler handler) {
                return SQLiteDatabase.openDatabase(fixture.file.getPath(), factory, SQLiteDatabase.CREATE_IF_NECESSARY, handler);
            }
        };
        gate = new AccessGate(clock::get, TimeUnit.MINUTES.toNanos(4)); gate.unlock();
        vault = new Vault(isolated, gate); fixture.vault = vault;
    }
    @After public void after() throws Exception { if (fixture != null) fixture.after(); }
    private void create() throws Exception {
        byte[] value = password(); try { vault.access().createPassword(value); } finally { Arrays.fill(value, (byte)0); }
        assertEquals("COMPLETED_LOCKED", vault.access().snapshot().operation().outcome().name());
        assertNotEquals("OPEN", vault.access().snapshot().phase().name());
    }
    private void open() throws Exception {
        gate.unlock(); vault.access().refresh();
        byte[] value = password(); try { vault.access().unlock(value); } finally { Arrays.fill(value, (byte)0); }
        assertEquals("OPEN", vault.access().snapshot().phase().name());
        assertEquals("OPENED", vault.access().snapshot().operation().outcome().name());
    }
    @Test public void createAndChangeFinishLockedAndUnlockActuallyOpens() throws Exception {
        create(); open(); byte[] current=password(), next=replacement();
        try { vault.access().changePassword(current, next); } finally { Arrays.fill(current,(byte)0); Arrays.fill(next,(byte)0); }
        assertEquals("COMPLETED_LOCKED", vault.access().snapshot().operation().outcome().name());
        assertEquals(Vault.State.LOCKED, vault.getVaultState());
        assertThrows(SecurityException.class, () -> vault.get("meta", "identity"));
        gate.unlock(); vault.access().refresh(); current=password();
        try { vault.access().unlock(current); } finally { Arrays.fill(current,(byte)0); }
        assertEquals("GENERIC_FAILURE", vault.access().snapshot().operation().outcome().name());
        next=replacement(); try { vault.access().unlock(next); } finally { Arrays.fill(next,(byte)0); }
        assertEquals("OPEN", vault.access().snapshot().phase().name());
    }
    @Test public void selectedPoliciesRejectAtExactDeadlineWithoutTimerDispatch() throws Exception {
        create();
        for(long millis:new long[]{60_000,120_000,240_000}) {
            vault.setAutoLockPolicy(millis); open();
            assertEquals(millis,vault.access().snapshot().selectedAutoLockMillis());
            assertEquals(millis,vault.access().snapshot().effectiveRemainingMillis());
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis)-1);
            assertArrayEquals(new byte[]{1,2,3,4},vault.get("meta","identity"));
            clock.incrementAndGet();
            assertThrows(SecurityException.class,()->vault.get("meta","identity"));
            assertNotEquals("OPEN",vault.access().snapshot().phase().name());
        }
    }
    @Test public void consumedAndroidAuthorizationCapsFourMinutePolicy() throws Exception {
        create(); gate.unlock(); clock.addAndGet(TimeUnit.SECONDS.toNanos(90)); vault.access().refresh();
        byte[] value=password(); try {vault.access().unlock(value);} finally {Arrays.fill(value,(byte)0);}
        assertEquals(150_000,vault.access().snapshot().effectiveRemainingMillis());
        assertEquals(150_000,vault.access().snapshot().absoluteGateRemainingMillis());
        clock.addAndGet(TimeUnit.SECONDS.toNanos(150));
        assertThrows(SecurityException.class,()->vault.get("meta","identity"));
    }
    @Test public void backgroundForegroundAndNewAuthenticationNeverReviveOldGrant() throws Exception {
        create(); open(); Runnable old=vault.authorization();
        vault.access().foreground(); assertEquals("OPEN",vault.access().snapshot().phase().name());
        vault.access().background(); assertThrows(SecurityException.class,old::run);
        vault.access().foreground(); assertNotEquals("OPEN",vault.access().snapshot().phase().name());
        open(); assertThrows(SecurityException.class,old::run);
        assertArrayEquals(new byte[]{1,2,3,4},vault.get("meta","identity"));
    }
    @Test public void freshVaultAndGateDoNotRestoreOpenState() throws Exception {
        create(); open(); vault.close(); clock.incrementAndGet();
        gate=new AccessGate(clock::get,TimeUnit.MINUTES.toNanos(4));
        vault=new Vault(isolated,gate); fixture.vault=vault;
        assertNotEquals("OPEN",vault.access().snapshot().phase().name());
        assertThrows(SecurityException.class,()->vault.get("meta","identity"));
        gate.unlock(); vault.access().refresh();
        assertNotEquals("OPEN",vault.access().snapshot().phase().name());
    }
    @Test public void malformedMetadataReportsCorruptWithoutRepair() throws Exception {
        create(); gate.unlock();
        vault.getWritableDatabase().execSQL("UPDATE vault_protection SET envelope=X'00'");
        assertThrows(SecurityException.class,()->vault.access().refresh()); byte[] value=password();
        try {vault.access().unlock(value);} finally {Arrays.fill(value,(byte)0);}
        assertEquals("CORRUPT",vault.access().snapshot().phase().name());
        try(var cursor=vault.getReadableDatabase().rawQuery("SELECT envelope FROM vault_protection",null)) {
            assertTrue(cursor.moveToFirst()); assertArrayEquals(new byte[]{0},cursor.getBlob(0));
        }
    }
    @Test public void missingDeviceKeyReportsUnavailableWithoutReplacement() throws Exception {
        create(); KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);keys.deleteEntry("umbra.index.v2");
        gate.unlock();assertThrows(SecurityException.class,()->vault.access().refresh());byte[] value=password();
        try {vault.access().unlock(value);} finally {Arrays.fill(value,(byte)0);}
        assertEquals("KEY_UNAVAILABLE",vault.access().snapshot().phase().name());
        assertFalse(keys.containsAlias("umbra.index.v2"));
    }
    @Test public void passwordAndAlteredTagHaveSameGenericOutcome() throws Exception {
        create();gate.unlock();vault.access().refresh();byte[] wrong=replacement();
        try {vault.access().unlock(wrong);} finally {Arrays.fill(wrong,(byte)0);}
        String rejected=vault.access().snapshot().operation().outcome().name();assertEquals("GENERIC_FAILURE",rejected);
        byte[] envelope;try(var cursor=vault.getReadableDatabase().rawQuery("SELECT envelope FROM vault_protection",null)) {
            assertTrue(cursor.moveToFirst());envelope=cursor.getBlob(0);
        }
        envelope[envelope.length-1]^=1;vault.getWritableDatabase().execSQL("UPDATE vault_protection SET envelope=?",new Object[]{envelope});
        byte[] value=password();try {vault.access().unlock(value);} finally {Arrays.fill(value,(byte)0);Arrays.fill(envelope,(byte)0);}
        assertEquals(rejected,vault.access().snapshot().operation().outcome().name());
        assertNotEquals("OPEN",vault.access().snapshot().phase().name());
    }
    @Test public void latePasswordCompletionCannotPublishIntoANewAuthenticationEpoch() throws Exception {
        create();
        var oldPicker=vault.access().beginExternal(app.umbra.access.AccessSnapshot.ExternalAction.DOCUMENT_PICKER);
        gate.unlock();vault.access().refresh();
        var worker=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var result=worker.submit(()->{
                byte[] value=password();
                try {return vault.access().unlock(value);} finally {Arrays.fill(value,(byte)0);}
            });
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
            while(vault.getVaultState()!=Vault.State.UNLOCKING && !result.isDone() && System.nanoTime()<deadline) Thread.yield();
            assertEquals("Must observe actual Argon2 operation",Vault.State.UNLOCKING,vault.getVaultState());
            assertEquals("STALE",vault.access().externalReturned(oldPicker,false).outcome().name());
            assertEquals("UNLOCK",vault.access().snapshot().operation().operation().name());
            vault.access().background();gate.unlock();
            var completed=result.get(10,TimeUnit.SECONDS);
            assertEquals("STALE",completed.outcome().name());
            assertNotEquals("OPEN",vault.access().snapshot().phase().name());
            assertThrows(SecurityException.class,()->vault.get("meta","identity"));
        } finally {worker.shutdownNow();assertTrue(worker.awaitTermination(10,TimeUnit.SECONDS));}
    }
    @Test public void refreshWhileOpenRejectsCorruptionAndClearsExistingDataAuthorization() throws Exception {
        create();open();Runnable old=vault.authorization();
        vault.getWritableDatabase().execSQL("UPDATE vault_protection SET envelope=X'00'");
        assertThrows(SecurityException.class,()->vault.access().refresh());
        assertEquals("CORRUPT",vault.access().snapshot().phase().name());
        assertThrows(SecurityException.class,old::run);
        assertThrows(SecurityException.class,()->vault.get("meta","identity"));
    }
    @Test public void refreshWhileOpenRejectsLostKeyAndClearsExistingDataAuthorization() throws Exception {
        create();open();Runnable old=vault.authorization();
        KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);keys.deleteEntry("umbra.index.v2");
        assertThrows(SecurityException.class,()->vault.access().refresh());
        assertEquals("KEY_UNAVAILABLE",vault.access().snapshot().phase().name());
        assertThrows(SecurityException.class,old::run);
        assertThrows(SecurityException.class,()->vault.get("meta","identity"));
        assertFalse(keys.containsAlias("umbra.index.v2"));
    }
    @Test public void passwordChangeAfterDeviceWrappingKeyLossReportsUnavailable() throws Exception {
        create();open();Runnable old=vault.authorization();
        KeyStore keys=KeyStore.getInstance("AndroidKeyStore");keys.load(null);keys.deleteEntry("umbra.vault.v1");
        byte[] current=password(),next=replacement();
        try {vault.access().changePassword(current,next);} finally {Arrays.fill(current,(byte)0);Arrays.fill(next,(byte)0);}
        assertEquals("KEY_UNAVAILABLE",vault.access().snapshot().operation().outcome().name());
        assertEquals("KEY_UNAVAILABLE",vault.access().snapshot().phase().name());
        assertThrows(SecurityException.class,old::run);
        assertFalse(keys.containsAlias("umbra.vault.v1"));
    }
    @Test public void externalResultsRequireNewAuthenticationAndOldAuthenticationTicketIsRejected() throws Exception {
        create();open();var access=vault.access();
        var picker=access.beginExternal(app.umbra.access.AccessSnapshot.ExternalAction.DOCUMENT_PICKER);
        access.background();access.foreground();
        var returned=access.externalReturned(picker,false);
        assertEquals("REQUIRES_USER_ACTION",returned.state().name());
        assertEquals("AUTHENTICATION_REQUIRED",returned.outcome().name());
        assertNotEquals("OPEN",access.snapshot().phase().name());
        assertEquals("STALE",access.externalReturned(picker,false).outcome().name());
        var settings=access.beginExternal(app.umbra.access.AccessSnapshot.ExternalAction.ANDROID_SETTINGS);
        access.background();assertEquals("CANCELLED",access.externalReturned(settings,true).state().name());
        var authentication=access.beginExternal(app.umbra.access.AccessSnapshot.ExternalAction.ANDROID_AUTHENTICATION);
        access.background();
        assertThrows(SecurityException.class,()->access.androidAuthenticationSucceeded(authentication));
        assertNotEquals("OPEN",access.snapshot().phase().name());
        assertThrows(SecurityException.class,()->vault.get("meta","identity"));
    }
    @Test public void committedPasswordChangeIsReportedWhenPostCommitCleanupFails() throws Exception {
        create();open();Runnable old=vault.authorization();
        var armed=new java.util.concurrent.atomic.AtomicBoolean(true);
        Runnable failedCleanup=()->{if(armed.get())throw new IllegalStateException("Synthetic cleanup failure");};
        gate.onInvalidation(failedCleanup);
        byte[] current=password(),next=replacement();
        try {
            var result=vault.access().changePassword(current,next);
            assertEquals("COMMITTED_CLEANUP_FAILED",result.outcome().name());
            assertNotEquals("SUCCESS",result.state().name());
            assertNotEquals("OPEN",vault.access().snapshot().phase().name());
            assertThrows(SecurityException.class,old::run);
        } finally {
            armed.set(false);Arrays.fill(current,(byte)0);Arrays.fill(next,(byte)0);
            java.lang.ref.Reference.reachabilityFence(failedCleanup);
        }
        // Reopen actual SQLite, proving the new wrapping committed despite failed resource cleanup.
        vault.close();clock.incrementAndGet();gate=new AccessGate(clock::get,TimeUnit.MINUTES.toNanos(4));
        gate.unlock();vault=new Vault(isolated,gate);fixture.vault=vault;vault.access().refresh();
        current=password();try {vault.access().unlock(current);} finally {Arrays.fill(current,(byte)0);}
        assertEquals("GENERIC_FAILURE",vault.access().snapshot().operation().outcome().name());
        next=replacement();try {vault.access().unlock(next);} finally {Arrays.fill(next,(byte)0);}
        assertEquals("OPEN",vault.access().snapshot().phase().name());
        assertArrayEquals(new byte[]{1,2,3,4},vault.get("meta","identity"));
    }
    @Test public void concurrentObservationsContainNoSecretsAndDoNotExtendAuthorization() throws Exception {
        create();open();var workers=java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            var results=new java.util.ArrayList<java.util.concurrent.Future<String>>();
            for(int n=0;n<16;n++)results.add(workers.submit(()->vault.access().snapshot().toString()));
            String first=results.get(0).get(5,TimeUnit.SECONDS);
            for(var result:results)assertEquals(first,result.get(5,TimeUnit.SECONDS));
            assertFalse(first.contains("synthetic readiness"));assertFalse(first.contains("[B@"));
            clock.addAndGet(TimeUnit.MINUTES.toNanos(4));
            assertNotEquals("OPEN",vault.access().snapshot().phase().name());
        } finally {workers.shutdownNow();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS));}
    }
}
