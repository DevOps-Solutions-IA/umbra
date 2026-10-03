package app.umbra;

import org.junit.Test;
import static org.junit.Assert.*;

/** Real Engine/libsignal authorization race; synthetic adapter state, no native/network claim. */
public final class ConnectedNativeRejectionDeliveryTest {
    @Test public void queuedIceAuthorizationIsRevokedBeforeNextHttpCheck() throws Exception {
        var p=new ConnectedVideoSignalingTest.VideoPair();
        String digest=app.umbra.core.Bytes.sha256(app.umbra.core.Bytes.utf8(ConnectedCallTest.sdp("audio offer")));
        p.ae.calls().ice(p.id,1,digest,"0","candidate:synthetic 1 udp 1 192.0.2.10 49160 typ relay");
        var queued=p.ae.outbox().stream().filter(q->q.optString("callType").equals("ICE")).findFirst().orElseThrow();
        String frozen=queued.getJSONObject("envelope").toString();
        var authorization=p.ae.deliveryAuthorization(queued.getJSONObject("envelope"));
        authorization.run();
        // Controlled ordering matching native fail(): FAILED is published before lease cancellation.
        String syntheticNativeState="FAILED";
        p.ae.calls().cancelPending(p.id);
        SecurityException failure=assertThrows(SecurityException.class,authorization::run);
        assertEquals("Call interrupted",failure.getMessage());
        assertEquals("FAILED",syntheticNativeState);
        assertEquals(frozen,queued.getJSONObject("envelope").toString());
        assertFalse(queued.optBoolean("relayUploaded"));
        // The exact same immutable control remains unauthorized. No delivery retry is performed.
        assertThrows(SecurityException.class,authorization::run);
    }
}
