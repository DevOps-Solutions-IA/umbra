package app.umbra;

import android.content.Context;
import android.content.ContextWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.umbra.data.Vault;
import java.io.File;
import java.security.KeyStore;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real AndroidKeyStore alias checks; synthetic missing-key DB path, no generated software Vault keys. */
@RunWith(AndroidJUnit4.class)
public class DeviceVaultKeyLifecycleTest {
    @Test public void concurrentPreparationsCannotBothEnterCheckThenGenerate() throws Exception {
        verifySerialized(false);
    }
    @Test public void deletionCannotInterleaveWithPreparation() throws Exception {
        verifySerialized(true);
    }
    private void verifySerialized(boolean deletion) throws Exception {
        assertTrue(BuildConfig.DEBUG);
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        assertFalse("Refuse existing user key", store.containsAlias("umbra.vault.v1"));
        assertFalse("Refuse existing user index key", store.containsAlias("umbra.index.v2"));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File existing = new File(context.getCacheDir(), "synthetic-missing-key-" + UUID.randomUUID());
        assertTrue(existing.createNewFile());
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), secondStarted = new CountDownLatch(1);
        AtomicInteger checks = new AtomicInteger();
        Context blocking = new ContextWrapper(context) {
            @Override public File getDatabasePath(String name) {
                checks.incrementAndGet(); entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Test release timeout"); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                return existing;
            }
        };
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = threads.submit(() -> assertThrows(SecurityException.class, () -> Vault.prepareKey(blocking)));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Future<?> second = threads.submit(() -> {
                secondStarted.countDown();
                if (deletion) { Vault.destroyKey(); return null; }
                assertThrows(SecurityException.class, () -> Vault.prepareKey(blocking)); return null;
            });
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
            assertThrows("Second lifecycle operation must wait for the active preparation", TimeoutException.class,
                () -> second.get(300, TimeUnit.MILLISECONDS));
            assertEquals("Only the first alias check may reach the database probe", 1, checks.get());
            release.countDown(); first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS);
            assertEquals(deletion ? 1 : 2, checks.get());
            assertFalse(store.containsAlias("umbra.vault.v1"));
            assertFalse(store.containsAlias("umbra.index.v2"));
            assertTrue("Missing-key rejection preserves existing data", existing.exists());
        } finally {
            release.countDown(); threads.shutdownNow();
            assertTrue(threads.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(existing.delete());
        }
    }
}
