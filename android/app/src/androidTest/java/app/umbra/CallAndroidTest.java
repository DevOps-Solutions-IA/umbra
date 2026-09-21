package app.umbra;

import app.umbra.calls.*;
import app.umbra.crypto.Engine;
import app.umbra.core.Bytes;
import app.umbra.lab.SqliteDeviceRecords;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
import static app.umbra.calls.CallPayload.NetworkPolicy.RELAY_ONLY;

/** SQLite/JNI synthetic storage only. No positive hardware Vault or media claim. */
public class CallAndroidTest {
    private static void deliver(Engine a,Engine b) throws Exception {
        for(JSONObject q:a.outbox()) if(q.getString("peer").equals(b.id())) b.receive(q.getJSONObject("envelope"));
    }
    @Test public void sqliteSignalingRollbackAndTerminalPersistenceOrOfflineRejection() throws Exception {
        try(var ar=new SqliteDeviceRecords();var br=new SqliteDeviceRecords()) {
            Engine a=new Engine(ar),b=new Engine(br);LocationAndroidTest.pair(a,b,ar,br);
            if(!CallPlatform.ENABLED) {
                assertThrows(SecurityException.class,()->a.calls().reviewInvite(b.id(),RELAY_ONLY));
                a.sendText(b.id(),"synthetic offline chat retained",600);deliver(a,b);assertEquals(1,b.messages(a.id()).size());return;
            }
            String id=a.calls().invite(a.calls().reviewInvite(b.id(),RELAY_ONLY),true);deliver(a,b);
            b.calls().accept(b.calls().reviewAccept(id,RELAY_ONLY),true);
            ar.failBucket="calls";assertThrows(IllegalStateException.class,()->deliver(b,a));ar.failBucket=null;ar.reopen();
            assertEquals("OUTGOING",a.calls().session(id).getString("state"));deliver(b,a);deliver(a,b);
            assertEquals("SELECTED",b.calls().session(id).getString("state"));
            a.calls().description(id,1,"offer","v=0\r\ns=synthetic no media\r\nt=0 0\r\n",Bytes.sha256(Bytes.utf8("synthetic certificate")));deliver(a,b);
            assertEquals("NEGOTIATING",b.calls().session(id).getString("state"));
            a.calls().end(id);deliver(a,b);br.reopen();assertEquals("ENDED",new Engine(br).calls().session(id).getString("state"));
        }
    }
    @Test public void sqliteReopenNoAutoResumeAndOldLeaseRejectedOrOfflineIngressDenied() throws Exception {
        try(var ar=new SqliteDeviceRecords();var br=new SqliteDeviceRecords()) {
            Engine a=new Engine(ar),b=new Engine(br);LocationAndroidTest.pair(a,b,ar,br);
            if(!CallPlatform.ENABLED) {assertThrows(SecurityException.class,()->a.calls().receive(null,new JSONObject()));return;}
            String id=a.calls().invite(a.calls().reviewInvite(b.id(),RELAY_ONLY),true);
            var auth=a.deliveryAuthorization(a.outbox().get(0).getJSONObject("envelope"));
            ar.gate.lock();ar.gate.unlock();assertThrows(SecurityException.class,auth::run);ar.reopen();
            Engine reopened=new Engine(ar);assertEquals("FAILED",reopened.calls().session(id).getString("state"));assertTrue(reopened.outbox().isEmpty());
        }
    }
}
