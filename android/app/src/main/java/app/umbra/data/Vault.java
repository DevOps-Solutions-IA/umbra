package app.umbra.data;

import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.security.keystore.*;
import app.umbra.core.*;
import org.json.JSONObject;
import java.security.KeyStore;
import java.util.*;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;

/** Encrypted values, HMAC-blinded logical indices and authorization checked at every operation. */
public final class Vault extends SQLiteOpenHelper implements Records {
    private static final String AES_ALIAS = "umbra.vault.v1", INDEX_ALIAS = "umbra.index.v2";
    private static final long MAX_ENCRYPTED_BYTES = 64L * 1024 * 1024;
    private final AccessGate gate;
    private int transactionDepth;
    private boolean rollbackOnly;
    public Vault(Context context, AccessGate gate) { super(context, "umbra.db", null, 2); this.gate = gate; }
    private static void createTable(SQLiteDatabase db, String table) {
        // Table identifiers are internal constants, never user input.
        db.execSQL("CREATE TABLE " + table + "(bucket TEXT NOT NULL,k TEXT NOT NULL,nonce BLOB NOT NULL,value BLOB NOT NULL,PRIMARY KEY(bucket,k))");
    }
    @Override public void onCreate(SQLiteDatabase db) { gate.requireUnlocked(); createTable(db, "records"); }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion != 1 || newVersion != 2) throw new IllegalStateException("Explicit migration required");
        AccessGate.Lease lease = gate.enter();
        // SQLiteOpenHelper wraps this method AND user_version in its upgrade transaction.
        // Checks roll back locks observed during migration; SQLiteOpenHelper owns the final commit.
        // Do not claim the last check is atomic with that later framework commit.
        try {
            createTable(db, "records_v2");
            try (Cursor c = db.rawQuery("SELECT bucket,k,nonce,value FROM records", null)) {
                while (c.moveToNext()) {
                    gate.check(lease);
                    String bucket = c.getString(0), oldIndex = c.getString(1);
                    byte[] clear = VaultCodec.openLegacy(key(AES_ALIAS), bucket, oldIndex, c.getBlob(2), c.getBlob(3));
                    try {
                        JSONObject record = new JSONObject(Bytes.text(clear));
                        String logicalKey = record.getString("key");
                        if (!oldIndex.equals(Bytes.sha256(Bytes.utf8(bucket + ":" + logicalKey))))
                            throw new SecurityException("Legacy record address mismatch");
                        String index = index(bucket, logicalKey);
                        insert(db, "records_v2", bucket, index, VaultCodec.seal(key(AES_ALIAS), bucket, index, clear));
                    } finally { Arrays.fill(clear, (byte) 0); }
                }
            }
            gate.check(lease);
            db.execSQL("DROP TABLE records"); db.execSQL("ALTER TABLE records_v2 RENAME TO records");
            gate.check(lease);
        } catch (Exception e) { throw new IllegalStateException("Migration failed; original transaction must be preserved", e); }
    }
    @Override public void onConfigure(SQLiteDatabase db) {
        // secure_delete returns a row even when setting the value; execSQL rejects it on Android.
        try (Cursor result = db.rawQuery("PRAGMA secure_delete=ON", null)) {
            if (!result.moveToFirst() || result.getInt(0) != 1)
                throw new IllegalStateException("SQLite secure_delete could not be enabled");
        }
        db.execSQL("PRAGMA synchronous=FULL");
        db.execSQL("PRAGMA temp_store=MEMORY");
    }
    /** Execute before authentication; never replace an existing data key that has disappeared.
     * Serialize creation/deletion across Activity instances: AndroidKeyStore generation replaces
     * an existing alias, so check-then-generate must not race another preparation.
     */
    public static synchronized void prepareKey(Context context) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias(AES_ALIAS) && context.getDatabasePath("umbra.db").exists())
            throw new SecurityException("La clave de la bóveda no está disponible; no se reemplazará automáticamente");
        boolean strongBox = context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE);
        if (!store.containsAlias(AES_ALIAS)) generate(AES_ALIAS, false, strongBox);
        if (!store.containsAlias(INDEX_ALIAS)) {
            // A v2 database with a missing HMAC key must not get a replacement index silently.
            java.io.File file = context.getDatabasePath("umbra.db");
            if (file.exists()) {
                try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
                    if (db.getVersion() >= 2) throw new SecurityException("La clave de índices no está disponible");
                }
            }
            generate(INDEX_ALIAS, true, strongBox);
        }
        requireHardware(key(AES_ALIAS)); requireHardware(key(INDEX_ALIAS));
    }
    private static void generate(String alias, boolean hmac, boolean strongBox) throws Exception {
        String algorithm = hmac ? KeyProperties.KEY_ALGORITHM_HMAC_SHA256 : KeyProperties.KEY_ALGORITHM_AES;
        KeyGenParameterSpec.Builder p = new KeyGenParameterSpec.Builder(alias,
            hmac ? KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY : KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(300, KeyProperties.AUTH_BIOMETRIC_STRONG | KeyProperties.AUTH_DEVICE_CREDENTIAL)
            .setUnlockedDeviceRequired(true).setIsStrongBoxBacked(strongBox);
        if (hmac) p.setDigests(KeyProperties.DIGEST_SHA256);
        else p.setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true);
        KeyGenerator generator = KeyGenerator.getInstance(algorithm, "AndroidKeyStore");
        try { generator.init(p.build()); generator.generateKey(); }
        catch (StrongBoxUnavailableException unavailable) {
            if (!strongBox) throw unavailable;
            p.setIsStrongBoxBacked(false); generator.init(p.build()); generator.generateKey();
        }
        requireHardware(key(alias)); // TEE is permitted; software-only is not a silent fallback.
    }
    private static void requireHardware(SecretKey key) throws Exception {
        KeyInfo info = (KeyInfo) SecretKeyFactory.getInstance(key.getAlgorithm(), "AndroidKeyStore").getKeySpec(key, KeyInfo.class);
        int level = info.getSecurityLevel();
        if (level != KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT && level != KeyProperties.SECURITY_LEVEL_STRONGBOX)
            throw new SecurityException("UMBRA requiere claves protegidas por hardware compatible");
    }
    private static SecretKey key(String alias) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        SecretKey key = (SecretKey) store.getKey(alias, null);
        if (key == null) throw new IllegalStateException("Vault key missing; recovery is not possible");
        return key;
    }
    private String index(String bucket, String key) throws Exception { return VaultCodec.index(key(INDEX_ALIAS), bucket, key); }
    private JSONObject decode(String bucket, String index, byte[] nonce, byte[] ciphertext) throws Exception {
        byte[] clear = VaultCodec.open(key(AES_ALIAS), bucket, index, nonce, ciphertext);
        try { return new JSONObject(Bytes.text(clear)); }
        finally { Arrays.fill(clear, (byte) 0); }
    }
    @Override public synchronized byte[] get(String bucket, String logicalKey) {
        AccessGate.Lease lease = gate.enter();
        try {
            String index = index(bucket, logicalKey);
            try (Cursor c = getReadableDatabase().rawQuery("SELECT nonce,value FROM records WHERE bucket=? AND k=?", new String[]{bucket, index})) {
                if (!c.moveToFirst()) { gate.check(lease); return null; }
                JSONObject record = decode(bucket, index, c.getBlob(0), c.getBlob(1));
                if (!logicalKey.equals(record.getString("key"))) throw new SecurityException("Record identity mismatch");
                byte[] value = Bytes.unb64(record.getString("data"), 2_000_000);
                try { gate.check(lease); return value; }
                catch (Exception e) { Arrays.fill(value, (byte) 0); throw e; }
            }
        } catch (AccessGate.LockedException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("Vault locked, invalidated or damaged", e); }
    }
    @Override public synchronized void put(String bucket, String logicalKey, byte[] data) {
        gate.requireUnlocked();
        if (data == null || data.length > 1_500_000) throw new IllegalArgumentException("Record too large");
        try {
            String index = index(bucket, logicalKey);
            byte[] clear = Bytes.utf8(new JSONObject().put("key", logicalKey).put("data", Bytes.b64(data)).toString());
            try {
                VaultCodec.Sealed sealed = VaultCodec.seal(key(AES_ALIAS), bucket, index, clear);
                SQLiteDatabase db = getWritableDatabase();
                if (!db.inTransaction()) throw new IllegalStateException("Vault writes require an explicit transaction");
                try (Cursor c = db.rawQuery("SELECT COALESCE(SUM(length(value)),0),COALESCE(SUM(CASE WHEN bucket=? AND k=? THEN length(value) ELSE 0 END),0) FROM records", new String[]{bucket, index})) {
                    if (!c.moveToFirst() || c.getLong(0) - c.getLong(1) + sealed.ciphertext().length > MAX_ENCRYPTED_BYTES)
                        throw new LocalCapacityException();
                }
                gate.requireUnlocked(); insert(db, "records", bucket, index, sealed);
            } finally { Arrays.fill(clear, (byte) 0); }
        } catch (AccessGate.LockedException | LocalCapacityException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("Cannot update vault", e); }
    }
    private static void insert(SQLiteDatabase db, String table, String bucket, String index, VaultCodec.Sealed sealed) {
        ContentValues values = new ContentValues(); values.put("bucket", bucket); values.put("k", index);
        values.put("nonce", sealed.nonce()); values.put("value", sealed.ciphertext());
        if (db.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_REPLACE) == -1)
            throw new android.database.SQLException("Vault write failed");
    }
    @Override public synchronized void remove(String bucket, String logicalKey) {
        gate.requireUnlocked();
        try {
            SQLiteDatabase db = getWritableDatabase();
            if (!db.inTransaction()) throw new IllegalStateException("Vault writes require an explicit transaction");
            db.delete("records", "bucket=? AND k=?", new String[]{bucket, index(bucket, logicalKey)});
        }
        catch (AccessGate.LockedException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("Cannot remove vault record", e); }
    }
    @Override public synchronized List<String> keys(String bucket) {
        AccessGate.Lease lease = gate.enter(); List<String> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery("SELECT k,nonce,value FROM records WHERE bucket=?", new String[]{bucket})) {
            while (c.moveToNext()) { gate.check(lease); out.add(decode(bucket, c.getString(0), c.getBlob(1), c.getBlob(2)).getString("key")); }
            gate.check(lease); return out;
        } catch (AccessGate.LockedException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("Cannot enumerate locked vault", e); }
    }
    @Override public Runnable authorization() {
        AccessGate.Lease lease = gate.enter();
        return () -> gate.check(lease);
    }
    @Override public synchronized <T> T transaction(Work<T> work) throws Exception {
        AccessGate.Lease lease = gate.enter(); SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        boolean ended = false;
        if (transactionDepth++ == 0) rollbackOnly = false;
        try {
            T value = work.run();
            if (rollbackOnly) throw new IllegalStateException("Nested vault transaction failed");
            // Prevent the UI lock from interleaving between the authorization check and SQLite commit.
            gate.commit(lease, () -> { db.setTransactionSuccessful(); db.endTransaction(); return null; });
            ended = true; return value;
        } catch (Exception | Error failure) {
            rollbackOnly = true; throw failure;
        } finally {
            try { if (!ended && db.inTransaction()) db.endTransaction(); }
            finally { if (--transactionDepth == 0) rollbackOnly = false; }
        }
    }
    public static synchronized void destroyKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        store.deleteEntry(AES_ALIAS); store.deleteEntry(INDEX_ALIAS);
    }
}
