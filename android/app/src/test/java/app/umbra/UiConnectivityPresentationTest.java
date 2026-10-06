package app.umbra;

import app.umbra.ui.model.ConnectivityPresentation;
import app.umbra.ui.model.Help;
import app.umbra.ui.model.ConnectivityPresentation.Service;
import app.umbra.ui.model.ConnectivityPresentation.State;
import java.util.Locale;
import org.junit.Test;
import static org.junit.Assert.*;

/** PRIVATE_STARTUP_STRICT wording: consent is not reachability, and nothing reconnects by itself. */
public class UiConnectivityPresentationTest {
    private static ConnectivityPresentation online(String state, boolean canConnect, Service service) {
        return ConnectivityPresentation.of(state, false, canConnect, false, true, service);
    }
    @Test public void lockedAndUnlockedOfflineNeverOfferAutomaticConnection() {
        ConnectivityPresentation locked = online("LOCKED_PRIVATE", true, Service.NOT_OBSERVED);
        assertFalse(locked.connectEnabled()); assertFalse(locked.disconnectEnabled());
        assertEquals("Bloqueada · sin conexión", locked.chip());
        ConnectivityPresentation unlocked = online("UNLOCKED_OFFLINE", true, Service.NOT_OBSERVED);
        assertTrue(unlocked.connectEnabled());
        assertFalse(unlocked.networkEnabled());
        assertEquals("Sin conexión", unlocked.chip());
        assertTrue(Help.NETWORK.lines.contains("Desbloquear no conecta."));
        assertFalse("admission alone cannot enable connect", online("UNLOCKED_OFFLINE", false, Service.NOT_OBSERVED).connectEnabled());
        assertFalse("no server configured", ConnectivityPresentation.of("UNLOCKED_OFFLINE", false, true, false, false, Service.NOT_OBSERVED).connectEnabled());
    }
    @Test public void connectedMeansConsentNotServerReachability() {
        ConnectivityPresentation c = online("CONNECTED", false, Service.NOT_OBSERVED);
        assertTrue(c.networkEnabled()); assertTrue(c.disconnectEnabled()); assertFalse(c.connectEnabled());
        assertEquals("Red habilitada", c.chip());
        String text = (c.chip() + c.title() + c.body() + c.service()).toLowerCase(Locale.ROOT);
        for (String claim : new String[]{"servidor disponible", "conectado al servidor", "llamada activa", "en línea"}) assertFalse(claim, text.contains(claim));
        assertEquals("No reconecta sola.", c.body());
        assertEquals("Servicio privado disponible", online("CONNECTED", false, Service.RESPONDED).service());
        assertNull("no service line while not connected", online("UNLOCKED_OFFLINE", true, Service.RESPONDED).service());
    }
    @Test public void errorRequiresANewExplicitAction() {
        ConnectivityPresentation e = online("OFFLINE_ERROR", true, Service.UNREACHABLE);
        assertFalse(e.networkEnabled());
        assertTrue(e.connectEnabled());
        assertEquals("No reconecta sola.", e.body());
        assertTrue(Help.NETWORK.lines.contains("Si la red se pierde o cambia, no reconecta sola."));
        assertFalse(online("CONNECTING", true, Service.NOT_OBSERVED).connectEnabled());
        assertFalse(online("DISCONNECTING", true, Service.NOT_OBSERVED).connectEnabled());
    }
    @Test public void unknownStatesFailClosed() {
        assertEquals(State.LOCKED_PRIVATE, State.fromEngine("RECONNECTING"));
        assertEquals(State.LOCKED_PRIVATE, State.fromEngine(null));
        assertFalse(online("RECONNECTING", true, Service.RESPONDED).connectEnabled());
    }
    @Test public void offlineEditionOffersNoNetworkControlAndHonestNearbyWording() {
        ConnectivityPresentation off = ConnectivityPresentation.of("UNLOCKED_OFFLINE", true, true, false, true, Service.RESPONDED);
        assertFalse(off.connectEnabled()); assertFalse(off.disconnectEnabled()); assertNull(off.service());
        assertEquals("Cercanía detenida", off.nearby());
        ConnectivityPresentation near = ConnectivityPresentation.of("UNLOCKED_OFFLINE", true, true, true, true, Service.NOT_OBSERVED);
        assertTrue(near.nearby().equals("Cercanía activa"));
        assertFalse("never claims no communication while Nearby is active", near.chip().toLowerCase(Locale.ROOT).contains("sin red"));
    }
    @Test public void nearbyIsIndependentOfNetworkConsent() {
        ConnectivityPresentation connectedNoNearby = ConnectivityPresentation.of("CONNECTED", false, false, false, true, Service.NOT_OBSERVED);
        assertEquals("Cercanía detenida", connectedNoNearby.nearby());
        ConnectivityPresentation nearbyNoNetwork = ConnectivityPresentation.of("UNLOCKED_OFFLINE", false, true, true, true, Service.NOT_OBSERVED);
        assertFalse(nearbyNoNetwork.networkEnabled());
        assertTrue(nearbyNoNetwork.nearby().equals("Cercanía activa"));
    }
}
