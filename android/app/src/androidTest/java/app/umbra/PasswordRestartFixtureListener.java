package app.umbra;

import android.content.Context;
import android.content.ContextWrapper;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import androidx.test.platform.app.InstrumentationRegistry;
import app.umbra.core.AccessGate;
import app.umbra.data.Vault;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.runner.Description;
import org.junit.runner.notification.RunListener;
import static org.junit.Assert.*;

/** Synthetic, test APK only. Host verifies and force-stops the live unlocked process. */
public final class PasswordRestartFixtureListener extends RunListener {
    @Override public void testRunStarted(Description description) throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue(context.getPackageName().endsWith(".dev") || context.getPackageName().endsWith(".vaultlab"));
        File locator = new File(context.getCacheDir(), "synthetic-password-restart-path");
        byte[] password = "synthetic password alpha".getBytes(StandardCharsets.UTF_8);
        String phase = InstrumentationRegistry.getArguments().getString("passwordPhase", "");
        if (phase.equals("prepare") || phase.equals("prepare-migration")) {
            assertFalse(locator.exists());
            DeviceVaultPasswordTest fixture = new DeviceVaultPasswordTest(); fixture.before();
            Files.write(locator.toPath(), fixture.file.getName().getBytes(StandardCharsets.UTF_8));
            if (phase.equals("prepare-migration")) {
                SQLiteDatabase db = fixture.vault.getWritableDatabase();
                db.execSQL("ALTER TABLE records RENAME TO synthetic_original_records");
                db.setCustomScalarFunction("umbra_lab_migration", value -> {
                    status("READY migration transaction open, staging table created, before commit");
                    while (true) {
                        try { Thread.sleep(1000); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new IllegalStateException("Fixture interrupted"); }
                    }
                });
                db.execSQL("CREATE VIEW records AS SELECT bucket,k,nonce,value FROM synthetic_original_records WHERE umbra_lab_migration(bucket)=bucket");
            }
            fixture.vault.createPassword(password); fixture.gate.unlock(); fixture.vault.unlock(password);
            assertEquals(Vault.State.UNLOCKED, fixture.vault.getVaultState());
            status("READY unlocked synthetic vault; awaiting actual force-stop");
            while (true) Thread.sleep(1000);
        } else if (phase.equals("verify") || phase.equals("verify-migration")) {
            String name = new String(Files.readAllBytes(locator.toPath()), StandardCharsets.UTF_8);
            if (!name.matches("synthetic-password-[0-9a-f-]+\\.db")) throw new SecurityException("Invalid fixture path");
            File file = new File(context.getCacheDir(), name); assertTrue(file.exists());
            Context isolated = new ContextWrapper(context) {
                @Override public File getDatabasePath(String ignored) { return file; }
                @Override public SQLiteDatabase openOrCreateDatabase(String ignored, int mode, SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler handler) {
                    return SQLiteDatabase.openDatabase(file.getPath(), factory, SQLiteDatabase.CREATE_IF_NECESSARY, handler);
                }
            };
            AccessGate gate = new AccessGate();
            try (Vault vault = new Vault(isolated, gate)) {
                assertEquals(Vault.State.LOCKED, vault.getVaultState()); gate.unlock();
                if (phase.equals("verify-migration")) {
                    assertFalse(vault.isPasswordConfigured());
                    try (var row = vault.getReadableDatabase().rawQuery("SELECT name FROM sqlite_master WHERE name='records_password'", null)) { assertFalse(row.moveToFirst()); }
                    // Restore only the test view; rollback must already have removed production staging.
                    vault.getWritableDatabase().execSQL("DROP VIEW records");
                    vault.getWritableDatabase().execSQL("ALTER TABLE synthetic_original_records RENAME TO records");
                    assertArrayEquals(new byte[]{1,2,3,4}, vault.get("meta", "identity"));
                    vault.createPassword(password); gate.unlock();
                }
                assertThrows(SecurityException.class, () -> vault.get("meta", "identity"));
                // Same production Argon2 profile, measured, no test-only reduced parameters.
                long[] times = new long[3];
                java.util.concurrent.atomic.AtomicBoolean measuring = new java.util.concurrent.atomic.AtomicBoolean(true);
                java.util.concurrent.atomic.AtomicLong peak = new java.util.concurrent.atomic.AtomicLong();
                Thread sampler = new Thread(() -> {
                    while (measuring.get()) {
                        Runtime rt = Runtime.getRuntime(); peak.accumulateAndGet(rt.totalMemory() - rt.freeMemory(), Math::max);
                        try { Thread.sleep(5); } catch (InterruptedException done) { return; }
                    }
                }); sampler.start();
                try {
                    for (int i = 0; i < 3; i++) {
                        vault.lock(); gate.unlock(); long start = System.nanoTime(); vault.unlock(password);
                        times[i] = (System.nanoTime() - start) / 1_000_000;
                        assertArrayEquals(new byte[]{1,2,3,4}, vault.get("meta", "identity"));
                    }
                } finally { measuring.set(false); sampler.interrupt(); sampler.join(1000); }
                Bundle metrics = new Bundle(); metrics.putString("passwordUnlockMillis", java.util.Arrays.toString(times));
                metrics.putString("sampledJavaHeapPeakBytes", Long.toString(peak.get()));
                metrics.putString("argon2Profile", "65536KiB/3passes/4lanes/32bytes");
                InstrumentationRegistry.getInstrumentation().sendStatus(0, metrics);
                vault.lock(); Vault.destroyKey(); gate.unlock();
                assertThrows(SecurityException.class, () -> vault.unlock(password));
                assertEquals(Vault.State.KEY_UNAVAILABLE, vault.getVaultState());
                assertTrue(file.exists());
                status("PASS force-stop preserved ciphertext, required password, rejected lost device key");
            } finally { Vault.destroyKey(); SQLiteDatabase.deleteDatabase(file); assertTrue(locator.delete()); }
        } else throw new SecurityException("Specify password restart phase");
        java.util.Arrays.fill(password, (byte)0);
    }
    private static void status(String value) {
        Bundle report = new Bundle(); report.putString("passwordRestart", value);
        InstrumentationRegistry.getInstrumentation().sendStatus(0, report);
    }
}
