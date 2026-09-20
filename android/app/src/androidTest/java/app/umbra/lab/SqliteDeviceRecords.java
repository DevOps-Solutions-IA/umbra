package app.umbra.lab;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.platform.app.InstrumentationRegistry;
import app.umbra.BuildConfig;
import app.umbra.core.AccessGate;
import app.umbra.data.Records;
import java.io.File;
import java.util.*;

/** TEST APK ONLY. Plaintext synthetic records in the test UID; never a production Vault fallback. */
public final class SqliteDeviceRecords implements Records, AutoCloseable {
    public final AccessGate gate = new AccessGate();
    private final File path;
    private SQLiteDatabase database;
    public String failBucket;
    public SqliteDeviceRecords() {
        var context = InstrumentationRegistry.getInstrumentation().getContext();
        if (!BuildConfig.DEBUG || !context.getPackageName().endsWith(".test")) throw new SecurityException("Test-only storage");
        path = new File(context.getCacheDir(), "synthetic-device-" + UUID.randomUUID() + ".db");
        gate.unlock(); reopen();
        database.execSQL("CREATE TABLE records(bucket TEXT NOT NULL,k TEXT NOT NULL,value BLOB NOT NULL,PRIMARY KEY(bucket,k))");
    }
    public synchronized void reopen() {
        if (database != null) database.close();
        database = SQLiteDatabase.openOrCreateDatabase(path,null);
        database.execSQL("PRAGMA synchronous=FULL");
    }
    public synchronized byte[] get(String bucket,String key) {
        gate.requireUnlocked();
        try(Cursor rows=database.rawQuery("SELECT value FROM records WHERE bucket=? AND k=?",new String[]{bucket,key})) {
            return rows.moveToFirst()?rows.getBlob(0):null;
        }
    }
    public synchronized void put(String bucket,String key,byte[] value) {
        gate.requireUnlocked(); if (!database.inTransaction()) throw new IllegalStateException("Transaction required");
        if (bucket.equals(failBucket)) throw new IllegalStateException("Synthetic storage failure");
        ContentValues values=new ContentValues(); values.put("bucket",bucket); values.put("k",key); values.put("value",value);
        if(database.insertWithOnConflict("records",null,values,SQLiteDatabase.CONFLICT_REPLACE)<0) throw new IllegalStateException("Synthetic database full");
    }
    public synchronized void remove(String bucket,String key) {
        gate.requireUnlocked(); if (!database.inTransaction()) throw new IllegalStateException("Transaction required");
        database.delete("records","bucket=? AND k=?",new String[]{bucket,key});
    }
    public synchronized List<String> keys(String bucket) {
        gate.requireUnlocked(); List<String> keys=new ArrayList<>();
        try(Cursor rows=database.rawQuery("SELECT k FROM records WHERE bucket=?",new String[]{bucket})) { while(rows.moveToNext()) keys.add(rows.getString(0)); }
        return keys;
    }
    public Runnable authorization() { var lease=gate.enter(); return () -> gate.check(lease); }
    public synchronized <T> T transaction(Work<T> work) throws Exception {
        var lease=gate.enter(); database.beginTransaction(); boolean ended=false;
        try {
            T value=work.run(); gate.commit(lease,() -> { database.setTransactionSuccessful(); database.endTransaction(); return null; });
            ended=true; return value;
        } finally { if(!ended && database.inTransaction()) database.endTransaction(); }
    }
    public synchronized void close() { database.close(); SQLiteDatabase.deleteDatabase(path); }
}
