package app.umbra.ui.model;

/**
 * Wording for {@code AdmissionService.peerStatus(deviceId)}: local evidence about another device's admission.
 * It is historical observation, never a live possession proof, never contact verification ("Verificado")
 * and never a global revocation freshness claim.
 */
public record PeerAdmissionPresentation(String value, Tone tone, String description) {
    /** @param state  PeerState name; unknown names fail closed to "No válida"
     *  @param source PeerSource name */
    public static PeerAdmissionPresentation of(String state, String source) {
        String how = switch (source == null ? "" : source) {
            case "NEARBY_PROOF" -> " · por cercanía";
            case "CHALLENGE_PROOF" -> " · con prueba";
            case "PUBLIC_CREDENTIAL" -> " · por credencial";
            default -> "";
        };
        return switch (state == null ? "" : state) {
            case "VALID_LOCALLY" -> new PeerAdmissionPresentation("Vigente" + how, Tone.NEUTRAL,
                "Vigente según registro local" + how + ". No verifica al contacto.");
            case "EXPIRED" -> new PeerAdmissionPresentation("Vencida", Tone.WARNING, "Admisión vencida según el registro local.");
            case "REVOKED" -> new PeerAdmissionPresentation("Revocada", Tone.BLOCKED, "Revocación conocida en este teléfono.");
            case "UNKNOWN" -> new PeerAdmissionPresentation("Sin datos", Tone.NEUTRAL, "Sin registro local de su admisión.");
            default -> new PeerAdmissionPresentation("No válida", Tone.DANGER, "Registro local de admisión no válido.");
        };
    }
}
