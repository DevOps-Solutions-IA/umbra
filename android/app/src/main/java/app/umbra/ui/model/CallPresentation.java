package app.umbra.ui.model;

import java.util.Locale;
import java.util.Set;

/** Human wording for call signaling ({@code CallService}) and media ({@code NativeVoiceSession.State}) states. */
public record CallPresentation(String phase, String detail, Tone tone, boolean live, boolean terminal, boolean incoming) {
    private static final Set<String> TERMINAL = Set.of("CANCELLED", "REJECTED", "BUSY", "EXPIRED", "ENDED", "FAILED", "NOT_SELECTED");

    /**
     * @param signaling CallService row state (OUTGOING, INCOMING, ACCEPTING, SELECTED, NEGOTIATING or terminal), may be null
     * @param media     NativeVoiceSession.State name (NEGOTIATING, ACTIVE, ENDED, FAILED) or null when no media session
     */
    public static CallPresentation of(String signaling, String media) {
        String m = media == null ? "" : media.toUpperCase(Locale.ROOT);
        String s = signaling == null ? "" : signaling.toUpperCase(Locale.ROOT);
        if (m.equals("ACTIVE")) return new CallPresentation("En llamada", "Conexión privada mediante tu retransmisor autorizado", Tone.SUCCESS, true, false, false);
        if (m.equals("FAILED")) return new CallPresentation("No se pudo conectar", "La conexión privada no se estableció. El micrófono se liberó.", Tone.DANGER, false, true, false);
        if (m.equals("ENDED")) return new CallPresentation("Llamada finalizada", "El audio se detuvo.", Tone.NEUTRAL, false, true, false);
        if (m.equals("NEGOTIATING")) return new CallPresentation("Conectando…", "Estableciendo el canal de audio", Tone.NEUTRAL, false, false, false);
        if (TERMINAL.contains(s)) return new CallPresentation(terminalLabel(s), "No hay audio ni video activos.", Tone.NEUTRAL, false, true, false);
        return switch (s) {
            case "INCOMING" -> new CallPresentation("Llamada entrante", "Responder no activa tu micrófono ni tu cámara sin tu confirmación.", Tone.ACCENT, false, false, true);
            case "OUTGOING" -> new CallPresentation("Llamando…", "Esperando respuesta. Todavía no se transmite audio.", Tone.NEUTRAL, false, false, false);
            case "ACCEPTING" -> new CallPresentation("Respondiendo…", "Confirmando el dispositivo de la llamada", Tone.NEUTRAL, false, false, true);
            case "SELECTED", "NEGOTIATING" -> new CallPresentation("Lista para audio", "Autoriza el micrófono para empezar a hablar.", Tone.NEUTRAL, false, false, false);
            default -> new CallPresentation("Sin llamada", "", Tone.NEUTRAL, false, true, false);
        };
    }

    private static String terminalLabel(String s) {
        return switch (s) {
            case "REJECTED" -> "Llamada rechazada";
            case "BUSY" -> "Ocupado";
            case "EXPIRED" -> "Sin respuesta";
            case "CANCELLED" -> "Llamada cancelada";
            case "FAILED" -> "Llamada fallida";
            case "NOT_SELECTED" -> "Respondida en otro dispositivo";
            default -> "Llamada finalizada";
        };
    }

    /** mm:ss, or h:mm:ss after one hour. Negative values are clamped to zero. */
    public static String duration(long seconds) {
        long s = Math.max(0, seconds);
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
                         : String.format(Locale.ROOT, "%02d:%02d", s / 60, s % 60);
    }
}
