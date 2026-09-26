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
}
