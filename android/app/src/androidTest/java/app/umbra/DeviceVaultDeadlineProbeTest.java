package app.umbra;

import android.content.ContextWrapper;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import app.umbra.core.AccessGate;
import app.umbra.data.Vault;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Uses only APIs present at the exact historical base, so its RED is attributable to Vault behavior. */
@RunWith(AndroidJUnit4.class)
public class DeviceVaultDeadlineProbeTest {
    @Test public void selectedDeadlineRejectsDataBeforeTimerDispatch() throws Exception {
        var fixture=new DeviceVaultPasswordTest();
        try {
            fixture.before();fixture.vault.close();
            var clock=new AtomicLong(System.nanoTime());
            var gate=new AccessGate(clock::get,TimeUnit.MINUTES.toNanos(4));gate.unlock();
            var context=new ContextWrapper(InstrumentationRegistry.getInstrumentation().getTargetContext()) {
                @Override public java.io.File getDatabasePath(String ignored) {return fixture.file;}
                @Override public SQLiteDatabase openOrCreateDatabase(String name,int mode,SQLiteDatabase.CursorFactory factory,DatabaseErrorHandler handler) {
                    return SQLiteDatabase.openDatabase(fixture.file.getPath(),factory,SQLiteDatabase.CREATE_IF_NECESSARY,handler);
                }
            };
            var vault=new Vault(context,gate);fixture.vault=vault;
            byte[] password="synthetic deadline probe".getBytes(StandardCharsets.UTF_8);
            try {vault.createPassword(password);gate.unlock();vault.setAutoLockPolicy(60_000);vault.unlock(password);}
            finally {java.util.Arrays.fill(password,(byte)0);}
            long began=System.nanoTime();
            assertArrayEquals(new byte[]{1,2,3,4},vault.get("meta","identity"));
            clock.addAndGet(TimeUnit.SECONDS.toNanos(60));
            assertTrue("Probe must run before real sixty-second timer",System.nanoTime()-began<TimeUnit.SECONDS.toNanos(5));
            // Do not call getVaultState/snapshot: the protected read itself must enforce this deadline.
            assertThrows(AccessGate.LockedException.class,()->vault.get("meta","identity"));
        } finally {fixture.after();}
    }
}
