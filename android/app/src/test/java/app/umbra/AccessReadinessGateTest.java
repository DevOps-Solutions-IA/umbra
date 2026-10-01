package app.umbra;

import app.umbra.core.AccessGate;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

/** Pure monotonic authorization tests; not evidence of Android hardware or UI lifecycle. */
public class AccessReadinessGateTest {
    private static long ns(long seconds) { return TimeUnit.SECONDS.toNanos(seconds); }
    @Test public void selectedPoliciesHaveNoEarlyExpiryAndRejectAtExactBoundaryWithoutTimer() {
        for (long seconds : new long[]{60, 120, 240}) {
            AtomicLong clock = new AtomicLong(ns(7));
            AccessGate gate = new AccessGate(clock::get, ns(240));
            gate.unlock(); var lease = gate.enter(); gate.restrict(lease, ns(seconds));
            assertEquals(ns(7 + seconds), gate.timing().effectiveDeadlineNanos());
            clock.set(ns(7 + seconds) - 1); gate.check(lease);
            assertEquals(1, gate.timing().effectiveRemainingNanos());
            clock.incrementAndGet(); assertThrows(AccessGate.LockedException.class, () -> gate.check(lease));
            assertFalse(gate.timing().open()); assertEquals(0, gate.timing().effectiveRemainingNanos());
            gate.lock(); // Redundant cleanup must not rewrite the causal expiration as UNKNOWN.
            assertEquals(seconds == 240 ? AccessGate.LockCause.ANDROID_AUTH_EXPIRED : AccessGate.LockCause.AUTOLOCK,
                gate.timing().cause());
        }
    }
    @Test public void passwordTimeCannotExtendOriginalAuthenticationOrExistingShorterPolicy() {
        AtomicLong clock = new AtomicLong(); AccessGate gate = new AccessGate(clock::get, ns(240));
        gate.unlock(); clock.set(ns(90)); var lease = gate.invalidateAuthorizations();
        gate.restrict(lease, ns(240)); assertEquals(ns(150), gate.timing().effectiveRemainingNanos());
        gate.restrict(lease, ns(60)); assertEquals(ns(150), gate.timing().effectiveDeadlineNanos());
        clock.set(ns(100)); gate.restrict(lease, ns(240));
        assertEquals(ns(150), gate.timing().effectiveDeadlineNanos());
        assertEquals(ns(140), gate.remainingNanos(lease)); // Existing API remains original auth lifetime.
        var next = gate.invalidateAuthorizations();
        assertEquals(ns(50), gate.timing().effectiveRemainingNanos());
        assertThrows(AccessGate.LockedException.class, () -> gate.restrict(lease, ns(240)));
        gate.check(next);
    }
    @Test public void backgroundAndFreshAuthenticationNeverReviveAnOldEpoch() {
        AtomicLong clock = new AtomicLong(); AccessGate gate = new AccessGate(clock::get, ns(240));
        assertEquals(AccessGate.LockCause.PROCESS_RESTART, gate.timing().cause());
        assertFalse(gate.timing().open());
        gate.unlock(); var old = gate.enter(); gate.restrict(old, ns(60));
        gate.lockWithCause(AccessGate.LockCause.BACKGROUND);
        assertEquals(AccessGate.LockCause.BACKGROUND, gate.timing().cause());
        clock.set(ns(10)); gate.unlock(); var fresh = gate.enter();
        assertTrue(fresh.epoch() > old.epoch()); assertEquals(ns(250), gate.timing().effectiveDeadlineNanos());
        assertThrows(AccessGate.LockedException.class, () -> gate.check(old));
        assertThrows(AccessGate.LockedException.class, () -> gate.restrict(old, ns(60)));
        gate.check(fresh); assertEquals(ns(240), gate.timing().effectiveRemainingNanos());
        assertFalse(new AccessGate(clock::get, ns(240)).timing().open());
    }
    @Test public void snapshotsAtOneInstantAreDeterministicAcrossReaders() throws Exception {
        AtomicLong clock = new AtomicLong(); AccessGate gate = new AccessGate(clock::get, ns(240));
        gate.unlock(); gate.restrict(gate.enter(), ns(120)); clock.set(ns(30));
        var expected = gate.timing(); var executor = Executors.newFixedThreadPool(4);
        try {
            var results = new ArrayList<Future<AccessGate.Timing>>();
            for (int i = 0; i < 100; i++) results.add(executor.submit(gate::timing));
            for (var result : results) assertEquals(expected, result.get(5, TimeUnit.SECONDS));
        } finally { executor.shutdownNow(); }
        assertTrue(expected.toString().contains("effectiveRemainingNanos"));
    }
    @Test public void emergencyObservationDeniesImmediatelyWithoutWaitingForCleanup() {
        AccessGate gate = new AccessGate(); gate.unlock(); var old = gate.enter();
        gate.emergency().request();
        assertFalse(gate.timing().open());
        assertEquals(AccessGate.LockCause.EMERGENCY, gate.timing().cause());
        assertThrows(AccessGate.LockedException.class, () -> gate.check(old));
    }
    @Test public void invalidIntervalsAndBackwardClockFailClosed() {
        AtomicLong clock = new AtomicLong(ns(50)); AccessGate gate = new AccessGate(clock::get, ns(240));
        gate.unlock(); var lease = gate.enter();
        assertThrows(IllegalArgumentException.class, () -> gate.restrict(lease, 0));
        assertThrows(IllegalArgumentException.class, () -> gate.restrict(lease, -1));
        gate.restrict(lease, Long.MAX_VALUE); assertEquals(ns(240), gate.timing().effectiveRemainingNanos());
        clock.decrementAndGet(); assertFalse(gate.timing().open());
        assertEquals(AccessGate.LockCause.ANDROID_AUTH_EXPIRED, gate.timing().cause());
    }
    @Test public void nanoTimeWrapKeepsDurationArithmeticAndDoesNotExtendAccess() {
        AtomicLong clock = new AtomicLong(Long.MAX_VALUE - 10);
        AccessGate gate = new AccessGate(clock::get, 100); gate.unlock(); var lease = gate.enter();
        gate.restrict(lease, 20);
        clock.addAndGet(19); gate.check(lease);
        assertEquals(1, gate.timing().effectiveRemainingNanos());
        clock.incrementAndGet(); assertThrows(AccessGate.LockedException.class, () -> gate.check(lease));
        assertEquals(AccessGate.LockCause.AUTOLOCK, gate.timing().cause());
    }

    @Test public void seededPolicyBoundariesNeverExtendOrPermitAnExpiredLease() {
        java.util.Random random = new java.util.Random(0x554d425241L);
        for (int sample = 0; sample < 2000; sample++) {
            long elapsedMillis = random.nextInt(240_000);
            long selectedMillis = 1L + random.nextInt(240_000);
            long remainingMillis = Math.min(selectedMillis, 240_000 - elapsedMillis);
            AtomicLong clock = new AtomicLong(ns(11));
            AccessGate gate = new AccessGate(clock::get, ns(240)); gate.unlock();
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(elapsedMillis));
            var lease = gate.invalidateAuthorizations();
            gate.restrict(lease, TimeUnit.MILLISECONDS.toNanos(selectedMillis));
            long deadline = clock.get() + TimeUnit.MILLISECONDS.toNanos(remainingMillis);
            assertEquals(deadline, gate.timing().effectiveDeadlineNanos());
            assertEquals(TimeUnit.MILLISECONDS.toNanos(remainingMillis), gate.timing().effectiveRemainingNanos());
            // A second, longer request and a domain epoch renewal cannot extend the chosen deadline.
            gate.restrict(lease, Long.MAX_VALUE);
            var replacement = gate.invalidateAuthorizations();
            assertThrows(AccessGate.LockedException.class, () -> gate.check(lease));
            assertEquals(deadline, gate.timing().effectiveDeadlineNanos());
            clock.set(deadline - 1); gate.check(replacement);
            assertEquals(1, gate.timing().effectiveRemainingNanos());
            clock.incrementAndGet();
            assertThrows(AccessGate.LockedException.class, () -> gate.check(replacement));
            assertFalse(gate.timing().open());
            assertEquals(0, gate.timing().effectiveRemainingNanos());
        }
    }

}
