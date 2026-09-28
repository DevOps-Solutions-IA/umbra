package app.umbra;

import app.umbra.ui.model.ConnectivityPresentation;
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
        assertTrue(unlocked.body().contains("Desbloquear no conecta"));
        assertFalse("admission alone cannot enable connect", online("UNLOCKED_OFFLINE", false, Service.NOT_OBSERVED).connectEnabled());
        assertFalse("no server configured", ConnectivityPresentation.of("UNLOCKED_OFFLINE", false, true, false, false, Service.NOT_OBSERVED).connectEnabled());
    }
    @Test public void connectedMeansConsentNotServerReachability() {
        ConnectivityPresentation c = online("CONNECTED", false, Service.NOT_OBSERVED);
        assertTrue(c.networkEnabled()); assertTrue(c.disconnectEnabled()); assertFalse(c.connectEnabled());
        assertEquals("Red habilitada", c.chip());
        String text = (c.chip() + c.title() + c.body() + c.service()).toLowerCase(Locale.ROOT);
        for (String claim : new String[]{"servidor disponible", "conectado al servidor", "llamada activa", "en línea"}) assertFalse(claim, text.contains(claim));
        assertTrue(c.body().contains("no se reconecta sola"));
        assertEquals("El servidor privado respondió en la última sincronización.", online("CONNECTED", false, Service.RESPONDED).service());
        assertNull("no service line while not connected", online("UNLOCKED_OFFLINE", true, Service.RESPONDED).service());
    }
    @Test public void errorRequiresANewExplicitAction() {
        ConnectivityPresentation e = online("OFFLINE_ERROR", true, Service.UNREACHABLE);
        assertFalse(e.networkEnabled());
        assertTrue(e.connectEnabled());
        assertTrue(e.body().contains("no reconecta sola"));
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
        assertEquals("Nearby detenido", off.nearby());
        ConnectivityPresentation near = ConnectivityPresentation.of("UNLOCKED_OFFLINE", true, true, true, true, Service.NOT_OBSERVED);
        assertTrue(near.nearby().startsWith("Nearby activo"));
        assertFalse("never claims no communication while Nearby is active", near.chip().toLowerCase(Locale.ROOT).contains("sin red"));
    }
    @Test public void nearbyIsIndependentOfNetworkConsent() {
        ConnectivityPresentation connectedNoNearby = ConnectivityPresentation.of("CONNECTED", false, false, false, true, Service.NOT_OBSERVED);
        assertEquals("Nearby detenido", connectedNoNearby.nearby());
        ConnectivityPresentation nearbyNoNetwork = ConnectivityPresentation.of("UNLOCKED_OFFLINE", false, true, true, true, Service.NOT_OBSERVED);
        assertFalse(nearbyNoNetwork.networkEnabled());
        assertTrue(nearbyNoNetwork.nearby().startsWith("Nearby activo"));
    }
}
