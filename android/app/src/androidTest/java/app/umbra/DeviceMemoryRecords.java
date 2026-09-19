package app.umbra;

import app.umbra.data.Records;
import java.util.*;

/** Instrumentation APK only: synthetic persistence, never installed into the production app. */
final class DeviceMemoryRecords implements Records {
    private Map<String,byte[]> entries = new LinkedHashMap<>();
    private static String address(String bucket, String key) { return bucket + "\u0000" + key; }
    public byte[] get(String bucket, String key) {
        byte[] value = entries.get(address(bucket, key)); return value == null ? null : value.clone();
    }
    public void put(String bucket, String key, byte[] value) { entries.put(address(bucket, key), value.clone()); }
    public void remove(String bucket, String key) { entries.remove(address(bucket, key)); }
    public List<String> keys(String bucket) {
        String prefix = bucket + "\u0000"; List<String> out = new ArrayList<>();
        for (String key : entries.keySet()) if (key.startsWith(prefix)) out.add(key.substring(prefix.length()));
        return out;
    }
    public <T> T transaction(Work<T> work) throws Exception {
        Map<String,byte[]> snapshot = new LinkedHashMap<>();
        entries.forEach((key, value) -> snapshot.put(key, value.clone()));
        try { return work.run(); }
        catch (Exception | Error failure) { entries = snapshot; throw failure; }
    }
}
