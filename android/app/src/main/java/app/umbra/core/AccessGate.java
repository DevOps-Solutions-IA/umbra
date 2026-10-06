package app.umbra.core;

import java.util.function.LongSupplier;

/** Process-local authorization epoch. Android Keystore is still the cryptographic boundary. */
public final class AccessGate {
    public static final class LockedException extends SecurityException {
        public LockedException() { super("Bóveda bloqueada; operación cancelada"); }
    }
    public record Lease(long epoch) {}
    public enum LockCause { PROCESS_RESTART, USER_REQUEST, BACKGROUND, AUTOLOCK,
        ANDROID_AUTH_EXPIRED, EMERGENCY, KEY_INVALIDATED, VAULT_FAILURE, UNKNOWN }
    /** Times belong to this gate's monotonic clock, not wall time or Android elapsedRealtime.
     * This observation is not authorization; callers must still validate their Lease. */
    public record Timing(long epoch, boolean open, long observedNanos, long absoluteRemainingNanos,
                         long effectiveRemainingNanos, long effectiveDeadlineNanos, LockCause cause) {}
    private LockCause cause = LockCause.PROCESS_RESTART;
    private long effectiveDuration;
    private final LongSupplier clock;
    private final long duration;
    private long epoch, openedAt;
    private boolean open;
    private final java.util.concurrent.atomic.AtomicBoolean emergencyDenied=new java.util.concurrent.atomic.AtomicBoolean();
    private final EmergencyLock emergency=new EmergencyLock(this);
    public EmergencyLock emergency() { return emergency; }
    void denyForEmergency() { emergencyDenied.set(true); }
    /** Only after fresh Android authentication, followed by the existing vault password unlock. */
    public synchronized void unlock(EmergencyLock.Authentication authentication) {
        emergency.authenticated(authentication,()->{emergencyDenied.set(false); unlock();});
    }
    private final java.util.List<java.lang.ref.WeakReference<Runnable>> invalidators = new java.util.ArrayList<>();
    /** Callbacks must not acquire a Vault/SQLite lock; invoked under the gate monitor. */
    public synchronized void onInvalidation(Runnable callback) { invalidators.add(new java.lang.ref.WeakReference<>(callback)); }
    private void invalidate() {
        boolean failed=false;
        var iterator = invalidators.iterator();
        while (iterator.hasNext()) {
            Runnable callback = iterator.next().get();
            if (callback == null) iterator.remove(); else {
                try { callback.run(); } catch(RuntimeException failure) { failed=true; }
            }
        }
        if(failed)throw new IllegalStateException("Authorization cleanup failed");
    }
    public AccessGate() { this(System::nanoTime, 240_000_000_000L); }
    public AccessGate(LongSupplier clock, long durationNanos) {
        if (clock == null || durationNanos <= 0) throw new IllegalArgumentException("Invalid access policy");
        this.clock = clock; this.duration = durationNanos; this.effectiveDuration = durationNanos;
    }
    /** Process monotonic authentication boundary, for a replacement owner of the same vault. */
    public synchronized boolean authenticatedAfter(long boundaryNanos) {
        try { requireUnlocked();return openedAt>boundaryNanos; }
        catch(LockedException denied) {return false;}
    }
    /** Call only after the system authentication callback succeeds. */
    public synchronized void unlock() { if(emergencyDenied.get())throw new LockedException(); open=false; invalidate(); epoch++; openedAt = clock.getAsLong(); effectiveDuration = duration; cause = LockCause.UNKNOWN; open = !emergencyDenied.get(); if(!open)throw new LockedException(); }
    public synchronized void lock() { lockWithCause(LockCause.UNKNOWN); }
    public synchronized void lockWithCause(LockCause reason) {
        java.util.Objects.requireNonNull(reason);
        // Cleanup often calls lock again. Preserve the first invalidation cause for this epoch.
        if (emergencyDenied.get()) cause = LockCause.EMERGENCY;
        else if (open) cause = reason;
        open = false; epoch++; invalidate();
    }
    /** Limit the current authenticated epoch from now; never extends an earlier limit.
     * Synchronous checks enforce this even if a cleanup timer is delayed. */
    public synchronized void restrict(Lease lease, long durationNanos) {
        if (durationNanos <= 0) throw new IllegalArgumentException("Invalid access policy");
        check(lease);
        long now = clock.getAsLong(); expireAt(now);
        long elapsed = now - openedAt;
        long remaining = effectiveDuration - elapsed;
        effectiveDuration = elapsed + Math.min(durationNanos, remaining);
    }
    public synchronized Timing timing() {
        long now = clock.getAsLong();
        try { expireAt(now); } catch (LockedException locked) { /* Observation remains available when locked. */ }
        long elapsed = now - openedAt;
        boolean allowed = open && !emergencyDenied.get();
        return new Timing(epoch, allowed, now, allowed ? duration - elapsed : 0,
            allowed ? effectiveDuration - elapsed : 0, allowed ? openedAt + effectiveDuration : 0,
            emergencyDenied.get() ? LockCause.EMERGENCY : cause);
    }
    /** Revoke old domain grants without extending Android authentication's lifetime. */
    public synchronized Lease invalidateAuthorizations() {
        requireUnlocked();
        // Revoke the former grants before cleanup callbacks can observe or use them.
        epoch++;
        try { invalidate(); }
        catch (RuntimeException failure) {
            // Partial cleanup cannot leave this authenticated epoch available for new work.
            open = false; cause = LockCause.VAULT_FAILURE;
            throw failure;
        }
        return new Lease(epoch);
    }
    /** Remaining original authentication lifetime; never renews it. */
    public synchronized long remainingNanos(Lease lease) {
        check(lease); return timing().absoluteRemainingNanos();
    }
    public synchronized Lease enter() { requireUnlocked(); return new Lease(epoch); }
    public synchronized void check(Lease lease) {
        requireUnlocked();
        if (lease == null || lease.epoch() != epoch) throw new LockedException();
    }
    public synchronized void requireUnlocked() { expireAt(clock.getAsLong()); }
    private void expireAt(long now) {
        if(emergencyDenied.get())throw new LockedException();
        long elapsed = now - openedAt;
        if (!open || elapsed < 0 || elapsed >= effectiveDuration) {
            if (open) {
                open = false;
                cause = elapsed < 0 || elapsed >= duration ? LockCause.ANDROID_AUTH_EXPIRED : LockCause.AUTOLOCK;
                epoch++; invalidate();
            }
            throw new LockedException();
        }
    }
    /** Linearizes authorization with commit. A concurrent lock takes effect after this small action. */
    public synchronized <T> T commit(Lease lease, Commit<T> commit) throws Exception {
        check(lease); return commit.run();
    }
    public interface Commit<T> { T run() throws Exception; }
}
