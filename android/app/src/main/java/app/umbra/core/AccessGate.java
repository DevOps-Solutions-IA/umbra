package app.umbra.core;

import java.util.function.LongSupplier;

/** Process-local authorization epoch. Android Keystore is still the cryptographic boundary. */
public final class AccessGate {
    public static final class LockedException extends SecurityException {
        public LockedException() { super("Bóveda bloqueada; operación cancelada"); }
    }
    public record Lease(long epoch) {}
    private final LongSupplier clock;
    private final long duration;
    private long epoch, openedAt;
    private boolean open;
    private final java.util.List<java.lang.ref.WeakReference<Runnable>> invalidators = new java.util.ArrayList<>();
    /** Callbacks must not acquire a Vault/SQLite lock; invoked under the gate monitor. */
    public synchronized void onInvalidation(Runnable callback) { invalidators.add(new java.lang.ref.WeakReference<>(callback)); }
    private void invalidate() {
        var iterator = invalidators.iterator();
        while (iterator.hasNext()) {
            Runnable callback = iterator.next().get();
            if (callback == null) iterator.remove(); else callback.run();
        }
    }
    public AccessGate() { this(System::nanoTime, 240_000_000_000L); }
    public AccessGate(LongSupplier clock, long durationNanos) {
        if (clock == null || durationNanos <= 0) throw new IllegalArgumentException("Invalid access policy");
        this.clock = clock; this.duration = durationNanos;
    }
    /** Call only after the system authentication callback succeeds. */
    public synchronized void unlock() { invalidate(); epoch++; openedAt = clock.getAsLong(); open = true; }
    public synchronized void lock() { open = false; epoch++; invalidate(); }
    /** Revoke old domain grants without extending Android authentication's lifetime. */
    public synchronized Lease invalidateAuthorizations() {
        requireUnlocked(); invalidate(); epoch++; return new Lease(epoch);
    }
    public synchronized Lease enter() { requireUnlocked(); return new Lease(epoch); }
    public synchronized void check(Lease lease) {
        requireUnlocked();
        if (lease == null || lease.epoch() != epoch) throw new LockedException();
    }
    public synchronized void requireUnlocked() {
        long elapsed = clock.getAsLong() - openedAt;
        if (!open || elapsed < 0 || elapsed >= duration) {
            if (open) { open = false; epoch++; invalidate(); }
            throw new LockedException();
        }
    }
    /** Linearizes authorization with commit. A concurrent lock takes effect after this small action. */
    public synchronized <T> T commit(Lease lease, Commit<T> commit) throws Exception {
        check(lease); return commit.run();
    }
    public interface Commit<T> { T run() throws Exception; }
}
