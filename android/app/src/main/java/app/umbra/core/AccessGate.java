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
    public AccessGate() { this(System::nanoTime, 240_000_000_000L); }
    public AccessGate(LongSupplier clock, long durationNanos) {
        if (clock == null || durationNanos <= 0) throw new IllegalArgumentException("Invalid access policy");
        this.clock = clock; this.duration = durationNanos;
    }
    /** Call only after the system authentication callback succeeds. */
    public synchronized void unlock() { epoch++; openedAt = clock.getAsLong(); open = true; }
    public synchronized void lock() { open = false; epoch++; }
    public synchronized Lease enter() { requireUnlocked(); return new Lease(epoch); }
    public synchronized void check(Lease lease) {
        requireUnlocked();
        if (lease == null || lease.epoch() != epoch) throw new LockedException();
    }
    public synchronized void requireUnlocked() {
        long elapsed = clock.getAsLong() - openedAt;
        if (!open || elapsed < 0 || elapsed >= duration) {
            if (open) { open = false; epoch++; }
            throw new LockedException();
        }
    }
    /** Linearizes authorization with commit. A concurrent lock takes effect after this small action. */
    public synchronized <T> T commit(Lease lease, Commit<T> commit) throws Exception {
        check(lease); return commit.run();
    }
    public interface Commit<T> { T run() throws Exception; }
}
