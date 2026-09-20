package app.umbra;

import android.content.Context;
import android.content.ContextWrapper;
import android.database.Cursor;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.umbra.core.AccessGate;
import app.umbra.data.Vault;
import java.io.File;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real SQLite transaction boundaries only; no substitute Vault keys or encrypted identity creation. */
@RunWith(AndroidJUnit4.class)
public class DeviceVaultTransactionTest {
    private File database;
    private AccessGate gate;
    private Vault vault;
    @Before public void setUp() {
        assertTrue(BuildConfig.DEBUG);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        database = new File(context.getCacheDir(), "synthetic-vault-" + UUID.randomUUID() + ".db");
        Context isolated = new ContextWrapper(context) {
            @Override public File getDatabasePath(String name) { return database; }
            @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode,
                    SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler handler) {
                return SQLiteDatabase.openDatabase(database.getPath(), factory,
                    SQLiteDatabase.CREATE_IF_NECESSARY, handler);
            }
        };
        gate = new AccessGate(); gate.unlock(); vault = new Vault(isolated, gate);
    }
    @After public void tearDown() {
        if (vault != null) vault.close();
        if (database != null) SQLiteDatabase.deleteDatabase(database);
    }
    private void marker(String key) {
        // Synthetic opaque bytes exercise the database transaction; never decrypt as a Vault record.
        vault.getWritableDatabase().execSQL("INSERT INTO records(bucket,k,nonce,value) VALUES('probe',?,X'00',X'01')", new Object[]{key});
    }
    private int count() {
        try (Cursor rows = vault.getReadableDatabase().rawQuery("SELECT count(*) FROM records", null)) {
            assertTrue(rows.moveToFirst()); return rows.getInt(0);
        }
    }
    @Test public void successfulTransactionDurablyCommitsAcrossReopen() throws Exception {
        vault.transaction(() -> { marker("synthetic"); return null; });
        vault.close(); assertEquals(1, count());
        try (Cursor setting = vault.getReadableDatabase().rawQuery("PRAGMA secure_delete", null)) {
            assertTrue(setting.moveToFirst()); assertEquals(1, setting.getInt(0));
        }
    }
    @Test public void failedWorkRollsBackPreviouslyWrittenRows() throws Exception {
        vault.transaction(() -> { marker("before"); return null; });
        assertThrows(IllegalStateException.class, () -> vault.transaction(() -> {
            marker("must-rollback"); throw new IllegalStateException("synthetic failure");
        }));
        assertEquals(1, count());
    }
    @Test public void lockBeforeCommitRollsBackWithoutReinitialization() throws Exception {
        vault.transaction(() -> { marker("before"); return null; });
        assertThrows(AccessGate.LockedException.class, () -> vault.transaction(() -> {
            marker("must-rollback"); gate.lock(); return null;
        }));
        gate.unlock(); assertEquals(1, count());
    }
    @Test public void reauthenticationDoesNotAuthorizeAnOldTransaction() throws Exception {
        vault.transaction(() -> { marker("before"); return null; });
        assertThrows(AccessGate.LockedException.class, () -> vault.transaction(() -> {
            marker("must-rollback"); gate.lock(); gate.unlock(); return null;
        }));
        assertEquals(1, count());
    }
    @Test public void nestedFailureCannotCommitOuterWritesEvenIfCaught() throws Exception {
        vault.transaction(() -> { marker("before"); return null; });
        vault.transaction(() -> {
            marker("outer-must-rollback");
            try { vault.transaction(() -> { marker("inner-must-rollback"); throw new IllegalArgumentException("synthetic nested failure"); }); }
            catch (IllegalArgumentException expected) { /* Outer code cannot reverse SQLite's failed child marker. */ }
            return null;
        });
        assertEquals(1, count());
    }
    @Test public void rejectedLegacyMigrationPreservesOriginalSchemaAndRows() throws Exception {
        java.security.KeyStore keys = java.security.KeyStore.getInstance("AndroidKeyStore"); keys.load(null);
        assertFalse("Refuse existing data keys", keys.containsAlias("umbra.vault.v1"));
        try (SQLiteDatabase legacy = SQLiteDatabase.openOrCreateDatabase(database, null)) {
            legacy.execSQL("CREATE TABLE records(bucket TEXT NOT NULL,k TEXT NOT NULL,nonce BLOB NOT NULL,value BLOB NOT NULL,PRIMARY KEY(bucket,k))");
            legacy.execSQL("INSERT INTO records VALUES('probe','synthetic',X'00',X'01')");
            legacy.setVersion(1);
        }
        assertThrows(IllegalStateException.class, () -> vault.getWritableDatabase());
        try (SQLiteDatabase preserved = SQLiteDatabase.openDatabase(database.getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
            assertEquals(1, preserved.getVersion());
            try (Cursor row = preserved.rawQuery("SELECT k FROM records", null)) {
                assertTrue(row.moveToFirst()); assertEquals("synthetic", row.getString(0)); assertFalse(row.moveToNext());
            }
            try (Cursor row = preserved.rawQuery("SELECT name FROM sqlite_master WHERE name='records_v2'", null)) {
                assertFalse("Failed migration must not leave its staging table", row.moveToFirst());
            }
        }
    }

}
