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
        String nearby = nearbyActive ? "Nearby activo · Bluetooth autorizado por ti" : "Nearby detenido";
        String svc = s != State.CONNECTED ? null : switch (service) {
            case RESPONDED -> "El servidor privado respondió en la última sincronización.";
            case UNREACHABLE -> "Sin respuesta del servidor privado. Los mensajes quedan pendientes.";
            case NOT_OBSERVED -> "Aún no hay sincronización con el servidor en esta sesión.";
        };
        if (offlineEdition)
            return new ConnectivityPresentation(s, nearbyActive ? "Offline · Nearby activo" : "Modo offline · sin red",
                nearbyActive ? Tone.OFFLINE : Tone.NEUTRAL, Glyph.OFFLINE_BLUETOOTH, "Edición offline",
                "Esta edición no tiene permiso de internet. Solo puede comunicarse por Bluetooth cuando tú activas Nearby.",
                false, false, nearby, null);
        return switch (s) {
            case LOCKED_PRIVATE -> new ConnectivityPresentation(s, "Bloqueada · sin conexión", Tone.NEUTRAL, Glyph.NETWORK_OFF,
                "Bloqueada · sin conexión", "Con la bóveda bloqueada UMBRA no abre conexiones.", false, false, nearby, null);
            case UNLOCKED_OFFLINE -> new ConnectivityPresentation(s, nearbyActive ? "Sin red · Nearby activo" : "Sin conexión", Tone.NEUTRAL, Glyph.NETWORK_OFF,
                "Red deshabilitada",
                canConnect ? "Desbloquear no conecta. Pulsa «Conectar» cuando quieras usar el servidor privado."
                    : relayKnown ? "Conectar requiere que este dispositivo esté admitido y que la bóveda siga abierta."
                    : "Configura primero el servidor privado y la admisión de este dispositivo.",
                canConnect && relayKnown, false, nearby, null);
            case CONNECTING -> new ConnectivityPresentation(s, "Habilitando red…", Tone.WARNING, Glyph.CLOUD,
                "Habilitando red", "Solicitando el permiso de red al motor.", false, true, nearby, null);
            case CONNECTED -> new ConnectivityPresentation(s, "Red habilitada", Tone.ACCENT, Glyph.CLOUD,
                "Red habilitada por ti",
                "UMBRA puede usar el servidor privado mientras la bóveda siga abierta. Si la red cambia o se pierde, se deshabilita y no se reconecta sola.",
                false, true, nearby, svc);
            case DISCONNECTING -> new ConnectivityPresentation(s, "Deshabilitando red…", Tone.NEUTRAL, Glyph.CLOUD_OFF,
                "Deshabilitando red", "Cerrando el trabajo de red registrado.", false, false, nearby, null);
            case OFFLINE_ERROR -> new ConnectivityPresentation(s, "Red interrumpida", Tone.WARNING, Glyph.CLOUD_OFF,
                "La red se interrumpió",
                "Se perdió la red, cambió o la admisión dejó de ser válida. UMBRA no reconecta sola: vuelve a pulsar «Conectar» si quieres.",
                canConnect && relayKnown, false, nearby, null);
        };
    }

    /** Short label for toolbars; replaces the old "Conectado · servidor privado" claim. */
    public boolean networkEnabled() { return state == State.CONNECTED; }
}
