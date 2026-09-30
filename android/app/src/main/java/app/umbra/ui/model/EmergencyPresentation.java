package app.umbra.ui.model;

import app.umbra.core.EmergencyLock;
import java.util.ArrayList;
import java.util.List;

/**
 * Wording for {@code engine.emergency().status()} (docs/EMERGENCY_LOCK.md). Only CLOSED says that closure was
 * confirmed; INVALIDATED/CLOSING are "in progress" and INCOMPLETE keeps access denied. Nothing here claims a
 * remote acknowledgement, forensic erasure or that already delivered data was withdrawn.
 */
public record EmergencyPresentation(EmergencyLock.State state, String title, String body, Tone tone, Glyph glyph,
                                    boolean finished, boolean allowsNewAuthentication, List<Line> lines) {
    public record Line(String subsystem, String outcome, Tone tone) {}

    public static EmergencyPresentation of(EmergencyLock.Status status) {
        EmergencyLock.State s = status == null ? EmergencyLock.State.INCOMPLETE : status.state();
        List<Line> lines = new ArrayList<>();
        if (status != null) for (EmergencyLock.Result r : status.results()) lines.add(new Line(subsystem(r.subsystem()), outcome(r.outcome()), tone(r.outcome())));
        return switch (s) {
            case READY -> new EmergencyPresentation(s, "Sin bloqueo de emergencia", "", Tone.NEUTRAL, Glyph.LOCK, true, true, List.copyOf(lines));
            case INVALIDATED, CLOSING -> new EmergencyPresentation(s, "Cerrando…", "Acceso ya denegado.", Tone.WARNING, Glyph.EMERGENCY_LOCK, false, false, List.copyOf(lines));
            case CLOSED -> new EmergencyPresentation(s, "Cierre confirmado", "Para volver: desbloqueo completo.", Tone.SUCCESS, Glyph.LOCK, true, true, List.copyOf(lines));
            case INCOMPLETE -> new EmergencyPresentation(s, "Cierre incompleto", "Acceso denegado en esta sesión.", Tone.DANGER, Glyph.WARNING, true, false, List.copyOf(lines));
        };
    }

    public static String subsystem(EmergencyLock.Subsystem subsystem) {
        return switch (subsystem) {
            case VAULT -> "Bóveda";
            case CONNECTIVITY -> "Red";
            case NEARBY -> "Cercanía";
            case CALLS -> "Llamadas";
            case MEDIA -> "Micrófono y cámara";
            case LOCATION -> "Ubicación";
            case DOCUMENTS -> "Contenido protegido";
        };
    }
    public static String outcome(EmergencyLock.Outcome outcome) {
        return switch (outcome) {
            case CLOSING -> "Cerrando";
            case CLOSED -> "Cerrado";
            case FAILED -> "Falló";
            case TIMED_OUT -> "Sin confirmar";
        };
    }
    private static Tone tone(EmergencyLock.Outcome outcome) {
        return switch (outcome) { case CLOSING -> Tone.NEUTRAL; case CLOSED -> Tone.SUCCESS; case FAILED, TIMED_OUT -> Tone.DANGER; };
    }
}
