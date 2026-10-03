package app.umbra;

import org.json.JSONObject;
import org.junit.Test;
import java.util.ArrayList;
import java.util.HashSet;
import static org.junit.Assert.*;

/** Real Engine/libsignal signaling; no claim about native callback scheduling. */
public class ConnectedVideoStopMultiplicityTest {
    @Test public void independentlyAuthorizedStopsForOneChangeRemainDistinctAndAccepted() throws Exception {
        var p=new ConnectedVideoSignalingTest.VideoPair();p.request();p.accept(true,true);p.negotiate(2);
        p.ae.calls().stopVideo(p.id);
        p.ae.calls().stopVideo(p.id);
        var stops=new ArrayList<JSONObject>();
        for(var row:p.ae.outbox())if(row.optString("callType").equals("VIDEO_STOP"))stops.add(row);
        assertTrue("Both authorized stop controls must remain queued",stops.size()==2);
        var ids=new HashSet<String>();
        for(var row:stops) {
            assertTrue("Same call binding",p.id.equals(row.getString("callSession")));
            assertTrue("Same generation binding",row.getInt("callGeneration")==2);
            assertTrue("Same video change binding",p.aVideo().getString("change").equals(row.getString("videoChange")));
            var envelope=row.getJSONObject("envelope");
            assertTrue("Distinct immutable envelopes",ids.add(envelope.getString("id")));
            String before=envelope.toString();
            p.ae.calls().authorizeDelivery(row);
            p.be.receive(envelope);
            p.be.receive(envelope); // An immutable transport retry stays idempotent.
            assertTrue("Envelope unchanged by delivery",before.equals(envelope.toString()));
        }
        assertTrue("Both endpoints remain stopped",p.aVideo().getString("state").equals("STOPPED") &&
            p.bVideo().getString("state").equals("STOPPED"));
        p.request();p.accept(true,true);p.negotiate(3);
        assertTrue("Next consent generation remains usable",p.aVideo().getInt("generation")==3);
    }
}
