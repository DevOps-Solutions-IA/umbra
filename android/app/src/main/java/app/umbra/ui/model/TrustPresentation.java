package app.umbra.ui.model;

/**
 * Human wording for a contact's trust state, without cryptographic jargon. Every state carries a
 * label, an icon and an explanation so color is never the only signal.
 */
public record TrustPresentation(TrustLevel level, String label, String headline, String explanation,
                                Glyph glyph, Tone tone, boolean allowsMessaging, boolean allowsCalls,
                                boolean allowsLocation, String primaryAction) {

    public static TrustPresentation of(TrustLevel level) {
        return switch (level) {
            case VERIFIED -> new TrustPresentation(level, "Verificado", "Verificado",
                "Código comparado. Si cambia, se detiene el envío.",
                Glyph.VERIFIED, Tone.VERIFIED, true, true, true, "Verificar de nuevo");
            case UNVERIFIED -> new TrustPresentation(level, "Sin verificar", "Sin verificar",
                "Compara el código antes de enviar.",
                Glyph.SHIELD, Tone.WARNING, false, false, false, "Verificar");
            case IDENTITY_CHANGED -> new TrustPresentation(level, "Identidad cambió", "Identidad cambió",
                "Verifica de nuevo antes de continuar.",
                Glyph.IDENTITY_CHANGED, Tone.IDENTITY, false, false, false, "Verificar");
            case BLOCKED -> new TrustPresentation(level, "Bloqueado", "Bloqueado",
                "Sin mensajes, ubicación ni llamadas.",
                Glyph.PERSON_BLOCK, Tone.BLOCKED, false, false, false, "Desbloquear");
        };
    }

    /** Short wording for the reason a sensitive action is unavailable, or null when allowed. */
    public String blockedReason() {
        return switch (level) {
            case VERIFIED -> null;
            case UNVERIFIED -> "Verifica este contacto.";
            case IDENTITY_CHANGED -> "Identidad cambió. Verifica de nuevo.";
            case BLOCKED -> "Contacto bloqueado.";
        };
    }
}
