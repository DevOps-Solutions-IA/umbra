package app.umbra.data;

import app.umbra.core.*;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class VaultSessionRegistryTest {
    private static void closed(AccessGate gate) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(gate.emergency().status().state()==EmergencyLock.State.CLOSING && System.nanoTime()<until)Thread.sleep(5);
        assertEquals(EmergencyLock.State.CLOSED,gate.emergency().status().state());
    }
    @Test public void replacingActivityCannotOverlapOrBypassIncompleteEmergency() throws Exception {
        String path=UUID.randomUUID().toString();var first=new AccessGate();first.unlock();var second=new AccessGate();second.unlock();
        var claim=VaultSessionRegistry.claim(path,first);
        assertThrows(SecurityException.class,()->VaultSessionRegistry.claim(path,second));
        var pending=new CompletableFuture<Void>();first.emergency().register(EmergencyLock.Subsystem.MEDIA,()->pending);
        first.emergency().request();claim.close();
        assertThrows(SecurityException.class,()->VaultSessionRegistry.claim(path,second));
        pending.complete(null);closed(first);
        assertThrows(SecurityException.class,()->VaultSessionRegistry.claim(path,second));
        second.unlock();try(var next=VaultSessionRegistry.claim(path,second)){second.enter();}
    }
    @Test public void failedClosureRetainsTombstoneAfterVaultClosed() throws Exception {
        String path=UUID.randomUUID().toString();var gate=new AccessGate();gate.unlock();var claim=VaultSessionRegistry.claim(path,gate);
        gate.emergency().register(EmergencyLock.Subsystem.MEDIA,()->{throw new IllegalStateException();});gate.emergency().request();claim.close();
        var replacement=new AccessGate();replacement.unlock();
        assertThrows(SecurityException.class,()->VaultSessionRegistry.claim(path,replacement));
    }
}
