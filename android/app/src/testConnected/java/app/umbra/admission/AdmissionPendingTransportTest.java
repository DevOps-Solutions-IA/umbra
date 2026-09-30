package app.umbra.admission;

import app.umbra.data.Records;
import app.umbra.transport.RelayClient;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class AdmissionPendingTransportTest {
    /** Deterministic cancellation between authorization capture and transport's next snapshot. */
    @Test public void cancellationBeforeRelaySnapshotRejectsWithoutEnteringTransport() throws Exception {
        var s=new AdmissionServiceTest.Setup();s.enroll(s.a);s.a.admission.createAdmissionRequest();
        final boolean[] cancel={false};
        Records records=new Records() {
            public byte[] get(String b,String k) {
                byte[] v=s.a.db.get(b,k);
                if(cancel[0] && b.equals("admission") && k.equals("pending")) {
                    cancel[0]=false;s.a.db.remove("admission","pending");
                }
                return v;
            }
            public void put(String b,String k,byte[] v){s.a.db.put(b,k,v);}
            public void remove(String b,String k){s.a.db.remove(b,k);}
            public List<String> keys(String b){return s.a.db.keys(b);}
            public <T>T transaction(Work<T> work)throws Exception{return s.a.db.transaction(work);}
            public Runnable authorization(){return s.a.db.authorization();}
            public Object restrictedResourceScope(){return s.a.db.restrictedResourceScope();}
        };
        var admission=new AdmissionService(records,s.a.clock::get);var connection=admission.connectivity();
        connection.vaultUnlocked();connection.connect("https://relay.example.test",true);
        AtomicInteger transportEntries=new AtomicInteger();
        try(var relay=new RelayClient("https://relay.example.test",()->{transportEntries.incrementAndGet();return true;},admission)) {
            cancel[0]=true;
            assertEquals(AdmissionException.Code.INVALID,assertThrows(AdmissionException.class,relay::admissionResult).code());
            assertEquals(0,transportEntries.get());assertNull(s.a.db.get("admission","pending"));
        } finally {connection.disconnect();}
    }
}
