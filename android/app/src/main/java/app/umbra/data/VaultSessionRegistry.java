package app.umbra.data;

import app.umbra.core.AccessGate;
import app.umbra.core.EmergencyLock;
import java.util.HashMap;
import java.util.Map;

/** Process-local ownership of each private database path. Retains a denied-session tombstone:
 * recreating an Activity/AccessGate cannot escape an incomplete emergency shutdown. No keys. */
final class VaultSessionRegistry {
    private static final Map<String,Binding> OWNERS=new HashMap<>();
    private static final class Binding {
        final AccessGate gate;int holders;
        Binding(AccessGate gate){this.gate=gate;}
    }
    static synchronized Claim claim(String path,AccessGate gate) {
        Binding previous=OWNERS.get(path);
        if(previous!=null && previous.gate!=gate) {
            EmergencyLock.Status status=previous.gate.emergency().status();
            if(previous.holders!=0 || (status.state()!=EmergencyLock.State.READY &&
                    (status.state()!=EmergencyLock.State.CLOSED || !gate.authenticatedAfter(status.finishedNanos()))))
                throw new AccessGate.LockedException();
        }
        Binding binding=previous!=null && previous.gate==gate?previous:new Binding(gate);
        OWNERS.put(path,binding);binding.holders++;return new Claim(binding);
    }
    static final class Claim implements AutoCloseable {
        private Binding binding;
        Claim(Binding binding){this.binding=binding;}
        @Override public void close(){synchronized(VaultSessionRegistry.class){if(binding!=null){binding.holders--;binding=null;}}}
    }
}
