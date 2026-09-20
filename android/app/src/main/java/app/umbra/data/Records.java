package app.umbra.data;

import java.util.List;

/** All protocol state changes and inbox/outbox changes must share one transaction. */
public interface Records {
    byte[] get(String bucket, String key);
    void put(String bucket, String key, byte[] data);
    void remove(String bucket, String key);
    List<String> keys(String bucket);
    <T> T transaction(Work<T> work) throws Exception;
    /** Captures the current unlock epoch. Implementations without an access gate fail closed. */
    default Runnable authorization() { throw new SecurityException("Session authorization unavailable"); }
    interface Work<T> { T run() throws Exception; }
}
