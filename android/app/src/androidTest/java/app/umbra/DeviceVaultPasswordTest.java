package app.umbra;

import android.content.Context;
import android.content.ContextWrapper;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.umbra.core.AccessGate;
import app.umbra.data.Vault;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.UUID;
import javax.crypto.KeyGenerator;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Actual Vault/SQLite/JCA with AndroidKeyStore software-capable keys created ONLY in test UID.
 * Does not call or weaken prepareKey; production hardware/authentication acceptance is tested separately.
 */
@RunWith(AndroidJUnit4.class)
public class DeviceVaultPasswordTest {
    private Context isolated;
    File file;
    AccessGate gate;
    Vault vault;
    volatile Runnable migrationBarrier;
    private boolean ownsKeys;
    private static byte[] password() { return "synthetic password alpha".getBytes(StandardCharsets.UTF_8); }
    private static byte[] next() { return "synthetic password beta".getBytes(StandardCharsets.UTF_8); }
    @Before public void before() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue(context.getPackageName().endsWith(".dev") || context.getPackageName().endsWith(".vaultlab"));
        assertFalse(context.getDatabasePath("umbra.db").exists());
        KeyStore keys = KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
        assertFalse(keys.containsAlias("umbra.vault.v1")); assertFalse(keys.containsAlias("umbra.index.v2"));
        ownsKeys = true;
        KeyGenerator aes = KeyGenerator.getInstance("AES", "AndroidKeyStore");
        aes.init(new KeyGenParameterSpec.Builder("umbra.vault.v1", KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes("GCM").setEncryptionPaddings("NoPadding").build()); aes.generateKey();
        KeyGenerator hmac = KeyGenerator.getInstance("HmacSHA256", "AndroidKeyStore");
        hmac.init(new KeyGenParameterSpec.Builder("umbra.index.v2", KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
            .setKeySize(256).setDigests("SHA-256").build()); hmac.generateKey();
        file = new File(context.getCacheDir(), "synthetic-password-" + UUID.randomUUID() + ".db");
        isolated = new ContextWrapper(context) {
            @Override public File getDatabasePath(String name) { return file; }
            @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler handler) {
                SQLiteDatabase.CursorFactory observed = (db, driver, table, query) -> {
                    if (migrationBarrier != null && query.toString().contains("SELECT bucket,k,nonce,value FROM records")) migrationBarrier.run();
                    return new android.database.sqlite.SQLiteCursor(driver, table, query);
                };
                return SQLiteDatabase.openDatabase(file.getPath(), observed, SQLiteDatabase.CREATE_IF_NECESSARY, handler);
            }
        };
        gate = new AccessGate(); gate.unlock(); vault = new Vault(isolated, gate);
        vault.transaction(() -> { vault.put("meta", "identity", new byte[]{1,2,3,4}); vault.put("session", "ratchet", new byte[]{9,8,7}); return null; });
    }
    @After public void after() throws Exception {
        if (vault != null) vault.close();
        if (file != null) SQLiteDatabase.deleteDatabase(file);
        if (ownsKeys) Vault.destroyKey();
    }
    @Test public void enrollmentPreservesRecordsAndUiGateCannotBypassPassword() throws Exception {
        vault.createPassword(password()); assertTrue(vault.isPasswordConfigured());
        gate.unlock(); // exactly the old biometric/UI path: insufficient for enrolled data
        assertThrows(SecurityException.class, () -> vault.get("meta", "identity"));
        assertThrows(SecurityException.class, () -> vault.get("meta", "nonexistent"));
        assertThrows(SecurityException.class, () -> vault.authorization());
        assertThrows(SecurityException.class, () -> vault.unlock(next()));
        assertEquals(Vault.State.LOCKED, vault.getVaultState());
        vault.unlock(password()); assertArrayEquals(new byte[]{1,2,3,4}, vault.get("meta", "identity"));
        assertArrayEquals(new byte[]{9,8,7}, vault.get("session", "ratchet"));
        Runnable old = vault.authorization(); gate.lock(); gate.unlock();
        assertThrows(SecurityException.class, old::run);
        assertThrows(SecurityException.class, () -> vault.get("meta", "identity"));
        vault.unlock(password()); assertEquals(Vault.State.UNLOCKED, vault.getVaultState());
    }
    @Test public void passwordChangeRewrapsAndFreshInstanceStartsLocked() throws Exception {
        vault.createPassword(password()); gate.unlock(); vault.unlock(password());
        byte[] original;
        try (var row = vault.getReadableDatabase().rawQuery("SELECT value FROM records WHERE bucket='session'", null)) {
            assertTrue(row.moveToFirst()); original = row.getBlob(0);
        }
        vault.changePassword(password(), next()); gate.unlock();
        assertThrows(SecurityException.class, () -> vault.unlock(password()));
        vault.unlock(next());
        try (var row = vault.getReadableDatabase().rawQuery("SELECT value FROM records WHERE bucket='session'", null)) {
            assertTrue(row.moveToFirst()); assertArrayEquals(original, row.getBlob(0));
        }
        vault.close(); gate = new AccessGate(); gate.unlock(); vault = new Vault(isolated, gate);
        assertEquals(Vault.State.LOCKED, vault.getVaultState());
        assertThrows(SecurityException.class, () -> vault.get("meta", "identity"));
        vault.unlock(next()); assertArrayEquals(new byte[]{9,8,7}, vault.get("session", "ratchet"));
    }
    @Test public void failedMigrationRollsBackAndLostKeyNeverReinitializes() throws Exception {
        migrationBarrier = () -> { throw new android.database.sqlite.SQLiteDiskIOException("Synthetic storage failure inside migration"); };
        assertThrows(Exception.class, () -> vault.createPassword(password()));
        migrationBarrier = null;
        assertFalse(vault.isPasswordConfigured());
        assertArrayEquals(new byte[]{1,2,3,4}, vault.get("meta", "identity"));
        vault.getWritableDatabase().execSQL("UPDATE records SET value=X'01' WHERE bucket='session'");
        assertThrows(Exception.class, () -> vault.createPassword(password()));
        assertFalse(vault.isPasswordConfigured());
        assertArrayEquals(new byte[]{1,2,3,4}, vault.get("meta", "identity"));
        try (var row = vault.getReadableDatabase().rawQuery("SELECT name FROM sqlite_master WHERE name='records_password'", null)) {
            assertFalse(row.moveToFirst());
        }
        Vault.destroyKey();
        assertThrows(Exception.class, () -> vault.get("meta", "identity"));
        assertThrows(SecurityException.class, () -> Vault.prepareKey(isolated));
        assertTrue(file.exists());
    }
    @Test public void sqliteFailureDuringRewrapKeepsOldPasswordAndData() throws Exception {
        vault.createPassword(password()); gate.unlock(); vault.unlock(password());
        vault.getWritableDatabase().execSQL("CREATE TRIGGER reject_rewrap BEFORE UPDATE ON vault_protection BEGIN SELECT RAISE(ABORT,'synthetic storage failure'); END");
        assertThrows(Exception.class, () -> vault.changePassword(password(), next()));
        vault.lock(); gate.unlock(); assertThrows(SecurityException.class, () -> vault.unlock(next()));
        vault.unlock(password()); assertArrayEquals(new byte[]{9,8,7}, vault.get("session", "ratchet"));
    }
    @Test public void metadataRemovalDoesNotRestoreDeviceOnlyDecryption() throws Exception {
        vault.createPassword(password()); gate.unlock();
        vault.getWritableDatabase().execSQL("DROP TABLE vault_protection");
        assertThrows(Exception.class, () -> vault.get("meta", "identity"));
        assertThrows(Exception.class, () -> vault.createPassword(password()));
    }
    @Test public void autolockInvalidatesDataKeyAndNeverReopens() throws Exception {
        vault.createPassword(password()); gate.unlock(); vault.setAutoLockPolicy(100);
        vault.unlock(password()); Runnable old = vault.authorization();
        long until = System.nanoTime() + 2_000_000_000L;
        while (vault.getVaultState() == Vault.State.UNLOCKED && System.nanoTime() < until) Thread.sleep(10);
        assertEquals(Vault.State.LOCKED, vault.getVaultState());
        gate.unlock(); assertThrows(SecurityException.class, old::run);
        assertThrows(SecurityException.class, () -> vault.get("meta", "identity"));
    }
    @Test public void lockDuringArgon2CannotPublishAKeyIntoANewSession() throws Exception {
        vault.createPassword(password()); gate.unlock();
        var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var result = worker.submit(() -> { vault.unlock(password()); return null; });
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (vault.getVaultState() != Vault.State.UNLOCKING && !result.isDone() && System.nanoTime() < deadline) Thread.yield();
            assertEquals("Must observe the actual in-progress unlock", Vault.State.UNLOCKING, vault.getVaultState());
            vault.lock(); gate.unlock();
            var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> result.get(10, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof SecurityException);
            assertEquals(Vault.State.LOCKED, vault.getVaultState());
            assertThrows(SecurityException.class, () -> vault.get("meta", "identity"));
        } finally { worker.shutdownNow(); assertTrue(worker.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)); }
    }
    @Test public void realSignalIdentityAndRatchetSurviveMigrationAndPasswordChange() throws Exception {
        vault.transaction(() -> { vault.remove("meta", "identity"); vault.remove("session", "ratchet"); return null; });
        var alice = new app.umbra.crypto.Engine(vault);
        var bob = new app.umbra.crypto.Engine(new DeviceMemoryRecords());
        alice.initialize("Synthetic vault Alice"); bob.initialize("Synthetic vault Bob");
        alice.importCard(bob.createCard()); bob.importCard(alice.createCard());
        String code = app.umbra.core.Bytes.safetyCode(alice.id(), bob.id());
        alice.verify(bob.id(), code); bob.verify(alice.id(), code);
        alice.sendText(bob.id(), "synthetic before migration", 3600);
        bob.receive(alice.outbox().get(0).getJSONObject("envelope"));
        alice.receive(bob.outbox().get(0).getJSONObject("envelope"));
        byte[] identity = vault.get("meta", "identity"); String id = alice.id();
        String session = vault.keys("session").get(0); byte[] ratchet = vault.get("session", session);
        vault.createPassword(password()); gate.unlock(); vault.unlock(password());
        assertArrayEquals(identity, vault.get("meta", "identity"));
        assertArrayEquals(ratchet, vault.get("session", session));
        vault.changePassword(password(), next()); gate.unlock(); vault.unlock(next());
        assertArrayEquals(identity, vault.get("meta", "identity"));
        assertArrayEquals(ratchet, vault.get("session", session));
        var recreated = new app.umbra.crypto.Engine(vault); assertEquals(id, recreated.id());
        recreated.sendText(bob.id(), "synthetic after rewrap", 3600);
        bob.receive(recreated.outbox().get(0).getJSONObject("envelope"));
        assertEquals(2, bob.messages(id).size());
    }

}
