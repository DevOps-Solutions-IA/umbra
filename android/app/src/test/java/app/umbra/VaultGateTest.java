package app.umbra;

import app.umbra.core.AccessGate;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

public class VaultGateTest {
    @Test public void passwordAttemptRevokesOldGrantsWithoutRenewingDeadline() {
        AtomicLong clock = new AtomicLong(); AccessGate gate = new AccessGate(clock::get, 100);
        AtomicInteger erased = new AtomicInteger(); Runnable wipe = erased::incrementAndGet; gate.onInvalidation(wipe);
        gate.unlock(); var previous = gate.enter(); clock.set(90);
        var current = gate.invalidateAuthorizations();
        assertEquals(10, gate.remainingNanos(current));
        assertThrows(SecurityException.class, () -> gate.check(previous)); gate.check(current);
        clock.set(100); assertThrows(SecurityException.class, () -> gate.check(current));
        assertEquals(3, erased.get());
    }
    @Test public void lockAndReauthenticationBothEraseSessionMaterial() {
        AccessGate gate = new AccessGate(); byte[] material = {1, 2, 3};
        Runnable wipe = () -> java.util.Arrays.fill(material, (byte)0); gate.onInvalidation(wipe);
        gate.unlock(); assertArrayEquals(new byte[3], material);
        material[0] = 1; gate.lock(); assertArrayEquals(new byte[3], material);
        var failures = assertThrows(SecurityException.class, gate::invalidateAuthorizations);
        assertNotNull(failures);
    }
    @Test public void failedGrantInvalidationClosesAccessAndRejectsEveryOldLease() {
        AtomicLong clock = new AtomicLong(); AccessGate gate = new AccessGate(clock::get, 100);
        gate.unlock(); var previous = gate.enter(); clock.set(90);
        AtomicInteger cleaned = new AtomicInteger();
        Runnable failing = () -> { throw new IllegalStateException("synthetic cleanup failure"); };
        Runnable following = cleaned::incrementAndGet;
        gate.onInvalidation(failing); gate.onInvalidation(following);
        assertThrows(IllegalStateException.class, gate::invalidateAuthorizations);
        assertEquals(1, cleaned.get()); // One failure never prevents the other cleanup callbacks.
        assertFalse(gate.timing().open());
        assertTrue(gate.timing().epoch() > previous.epoch());
        assertEquals(AccessGate.LockCause.VAULT_FAILURE, gate.timing().cause());
        assertThrows(AccessGate.LockedException.class, () -> gate.check(previous));
        assertThrows(AccessGate.LockedException.class, gate::enter);
        assertThrows(AccessGate.LockedException.class, gate::invalidateAuthorizations);
    }
    @Test public void invalidatorsObserveThePreviousGrantAlreadyRevoked() {
        AtomicLong clock = new AtomicLong(); AccessGate gate = new AccessGate(clock::get, 100);
        gate.unlock(); var previous = gate.enter(); clock.set(90);
        AtomicInteger denied = new AtomicInteger();
        Runnable callback = () -> {
            assertThrows(AccessGate.LockedException.class, () -> gate.check(previous));
            denied.incrementAndGet();
        };
        gate.onInvalidation(callback);
        var current = gate.invalidateAuthorizations();
        assertEquals(1, denied.get()); gate.check(current);
        assertEquals(10, gate.remainingNanos(current));
        assertEquals(100, gate.timing().effectiveDeadlineNanos());
    }
    @Test public void cleanupFailureCannotBeRecoveredByReusingTheOldGrant() {
        AccessGate gate = new AccessGate(); gate.unlock(); var old = gate.enter();
        java.util.concurrent.atomic.AtomicBoolean fail = new java.util.concurrent.atomic.AtomicBoolean(true);
        Runnable callback = () -> { if (fail.get()) throw new IllegalStateException("synthetic"); };
        gate.onInvalidation(callback);
        assertThrows(IllegalStateException.class, gate::invalidateAuthorizations);
        fail.set(false);
        assertThrows(AccessGate.LockedException.class, gate::enter);
        gate.unlock(); // Explicit fresh authentication, never a retry that revives the former lease.
        var fresh = gate.enter(); assertTrue(fresh.epoch() > old.epoch());
        assertThrows(AccessGate.LockedException.class, () -> gate.check(old));
    }

}
