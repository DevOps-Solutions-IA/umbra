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
            case VERIFIED -> new TrustPresentation(level, "Verificado", "Contacto verificado",
                "Comparaste el código de seguridad con esta persona. Si su identidad cambia, UMBRA te avisará y detendrá las operaciones sensibles.",
                Glyph.VERIFIED, Tone.VERIFIED, true, true, true, "Volver a verificar");
            case UNVERIFIED -> new TrustPresentation(level, "No verificado", "Contacto no verificado",
                "Todavía no comparaste el código de seguridad. Hasta verificarlo, UMBRA no envía mensajes, ubicación ni llamadas a este contacto.",
                Glyph.SHIELD, Tone.WARNING, false, false, false, "Verificar contacto");
            case IDENTITY_CHANGED -> new TrustPresentation(level, "Identidad cambió", "La identidad cambió",
                "La identidad criptográfica de este contacto cambió. Verifica nuevamente antes de continuar con operaciones sensibles.",
                Glyph.IDENTITY_CHANGED, Tone.IDENTITY, false, false, false, "Verificar nueva identidad");
            case BLOCKED -> new TrustPresentation(level, "Bloqueado", "Contacto bloqueado",
                "No recibirás ni enviarás mensajes, ubicación ni llamadas con este contacto mientras esté bloqueado.",
                Glyph.PERSON_BLOCK, Tone.BLOCKED, false, false, false, "Desbloquear");
        };
    }

    /** Short wording for the reason a sensitive action is unavailable, or null when allowed. */
    public String blockedReason() {
        return switch (level) {
            case VERIFIED -> null;
            case UNVERIFIED -> "Verifica a este contacto para enviar mensajes.";
            case IDENTITY_CHANGED -> "La identidad cambió. Verifícala de nuevo para continuar.";
            case BLOCKED -> "Contacto bloqueado. Desbloquéalo para conversar.";
        };
    }
}
