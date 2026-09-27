package app.umbra.transport;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

public class AdmissionClockWindowTest {
    @Test public void oneSecondVerifierLeadWaitsWithoutWeakeningValidation()throws Exception {
        AtomicLong wall=new AtomicLong(100),elapsed=new AtomicLong(),checks=new AtomicLong();
        RelayClient.awaitChallengeStart(101,wall::get,elapsed::get,checks::incrementAndGet,millis->{
            if(elapsed.addAndGet(millis*1_000_000)>=1_000_000_000)wall.set(101);
        });
        assertEquals(101,wall.get());assertEquals(1_000_000_000,elapsed.get());assertTrue(checks.get()>1);
    }
    @Test public void frozenOrLargeClockDifferenceFailsWithinBound()throws Exception {
        AtomicLong elapsed=new AtomicLong();
        assertThrows(SecurityException.class,()->RelayClient.awaitChallengeStart(101,()->100,elapsed::get,()->{},
            millis->elapsed.addAndGet(millis*1_000_000)));
        assertEquals(2_500_000_000L,elapsed.get());
        assertThrows(SecurityException.class,()->RelayClient.awaitChallengeStart(103,()->100,()->0,()->{},
            millis->{throw new AssertionError("Must not wait for a large clock mismatch");}));
    }
    @Test public void lockOrRevocationDuringClockWaitCannotReuseAuthorization()throws Exception {
        AtomicLong elapsed=new AtomicLong();
        assertThrows(SecurityException.class,()->RelayClient.awaitChallengeStart(101,()->100,elapsed::get,()->{
            if(elapsed.get()>0)throw new SecurityException("Synthetic old lease");
        },millis->elapsed.addAndGet(millis*1_000_000)));
        assertEquals(50_000_000L,elapsed.get());
    }
}
