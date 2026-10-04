package app.umbra;

import app.umbra.ui.model.ShortStatus;
import org.junit.Test;
import static org.junit.Assert.*;

/** Nearby phases come from the real BluetoothLink handshake; "Conectado" only after AUTHENTICATED. */
public class UiNearbyStageTest {
    @Test public void everyStageHasAShortSpanishPhaseAndOnlyAuthenticatedIsConnected() {
        // Names of app.umbra.transport.BluetoothLink.Stage (kept literal: the JVM suite has no Bluetooth classes).
        for (String s : new String[]{"CONNECTING", "SOCKET_CONNECTED", "HELLO_SENT", "HELLO_RECEIVED", "PROOF_SENT", "AUTHENTICATED"}) {
            String text = ShortStatus.nearbyStage(s);
            assertNotNull(s, text);
            assertTrue(text.length() <= 32);
            assertEquals(s, "AUTHENTICATED".equals(s), "Conectado".equals(text));
        }
        assertEquals("Cercanía", ShortStatus.nearbyStage(null));
        assertEquals("Cercanía", ShortStatus.nearbyStage("UNKNOWN"));
    }
}
