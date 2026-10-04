package app.umbra.ui.model;

/**
 * Presentation of {@code ConnectivityService.State} (PRIVATE_STARTUP_STRICT v1) plus the separate,
 * independently consented Nearby session and the last real relay observation.
 *
 * <p>CONNECTED is local permission for on-demand I/O. It is never shown as "server reachable", ICE or
 * an active call: service reachability is a separate line that only appears after a real sync result.
 */
public record ConnectivityPresentation(State state, String chip, Tone tone, Glyph glyph, String title, String body,
                                       boolean connectEnabled, boolean disconnectEnabled, String nearby, String service) {

    public enum State { LOCKED_PRIVATE, UNLOCKED_OFFLINE, CONNECTING, CONNECTED, DISCONNECTING, OFFLINE_ERROR;
        /** Unknown names fail closed to the most restrictive presentation. */
        public static State fromEngine(String name) {
            if (name != null) for (State s : values()) if (s.name().equals(name)) return s;
            return LOCKED_PRIVATE;
        }
    }
    /** Last observation of the private server made by an actual sync after consent. */
    public enum Service { NOT_OBSERVED, RESPONDED, UNREACHABLE }

    /**
     * @param canConnect   {@code ConnectivityService.canConnect()} (includes admission and edition)
     * @param nearbyActive {@code isNearbySessionAllowed()}
     * @param relayKnown   a private server address is stored in the profile
     */
    public static ConnectivityPresentation of(String engineState, boolean offlineEdition, boolean canConnect,
                                              boolean nearbyActive, boolean relayKnown, Service service) {
        State s = State.fromEngine(engineState);
        String nearby = nearbyActive ? "Cercanía activa" : "Cercanía detenida";
        String svc = s != State.CONNECTED ? null : switch (service) {
            case RESPONDED -> "Servidor respondió";
            case UNREACHABLE -> "Servidor sin respuesta";
            case NOT_OBSERVED -> "Sin sincronizar aún";
        };
        if (offlineEdition)
            return new ConnectivityPresentation(s, nearbyActive ? "Cercanía activa" : "Sin conexión",
                nearbyActive ? Tone.OFFLINE : Tone.NEUTRAL, Glyph.OFFLINE_BLUETOOTH, "Edición sin internet", "Solo cercanía por Bluetooth.",
                false, false, nearby, null);
        return switch (s) {
            case LOCKED_PRIVATE -> new ConnectivityPresentation(s, "Bloqueada · sin conexión", Tone.NEUTRAL, Glyph.NETWORK_OFF,
                "Bloqueada · sin conexión", "", false, false, nearby, null);
            case UNLOCKED_OFFLINE -> new ConnectivityPresentation(s, nearbyActive ? "Sin red · cercanía activa" : "Sin conexión", Tone.NEUTRAL, Glyph.NETWORK_OFF,
                "Sin conexión", canConnect ? "" : relayKnown ? "Requiere acceso privado activo." : "Configura la conexión privada.",
                canConnect && relayKnown, false, nearby, null);
            case CONNECTING -> new ConnectivityPresentation(s, "Conectando…", Tone.WARNING, Glyph.CLOUD,
                "Conectando…", "", false, true, nearby, null);
            case CONNECTED -> new ConnectivityPresentation(s, "Red habilitada", Tone.ACCENT, Glyph.CLOUD,
                "Red habilitada", "No reconecta sola.", false, true, nearby, svc);
            case DISCONNECTING -> new ConnectivityPresentation(s, "Desconectando…", Tone.NEUTRAL, Glyph.CLOUD_OFF,
                "Desconectando…", "", false, false, nearby, null);
            case OFFLINE_ERROR -> new ConnectivityPresentation(s, "Red interrumpida", Tone.WARNING, Glyph.CLOUD_OFF,
                "Red interrumpida", "No reconecta sola.", canConnect && relayKnown, false, nearby, null);
        };
    }

    /** Short label for toolbars; replaces the old "Conectado · servidor privado" claim. */
    public boolean networkEnabled() { return state == State.CONNECTED; }
}
