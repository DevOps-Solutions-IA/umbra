package app.umbra.ui.model;

import java.util.Locale;

/**
 * Human error messages. Critical errors are never hidden; stack traces are never user text.
 * {@code technical} carries a bounded, secret-free diagnostic only for the optional
 * "Detalles técnicos" view (debug builds).
 */
public record ErrorPresentation(ErrorKind kind, String title, String body, Glyph glyph, Tone tone,
                                boolean retryable, String technical) {

    public static ErrorPresentation of(ErrorKind kind) { return of(kind, null); }

    public static ErrorPresentation of(ErrorKind kind, String technical) {
        String t = sanitize(technical);
        return switch (kind) {
            case NO_CONNECTION -> new ErrorPresentation(kind, "Sin conexión", "No hay acceso a internet. Los mensajes quedan cifrados en cola y se enviarán cuando vuelva la conexión.", Glyph.CLOUD_OFF, Tone.WARNING, true, t);
            case RELAY_UNAVAILABLE -> new ErrorPresentation(kind, "Servidor privado no disponible", "No se pudo contactar con tu servidor. Tus mensajes siguen en cola y no se perdieron.", Glyph.CLOUD_OFF, Tone.WARNING, true, t);
            case TURN_UNAVAILABLE -> new ErrorPresentation(kind, "No se pudo establecer la llamada", "El retransmisor autorizado para llamadas no respondió. No se transmitió audio.", Glyph.CALL_END, Tone.DANGER, true, t);
            case CONTACT_UNVERIFIED -> new ErrorPresentation(kind, "Contacto no verificado", "Compara el código de seguridad con esta persona antes de enviar mensajes, ubicación o llamar.", Glyph.SHIELD, Tone.WARNING, false, t);
            case IDENTITY_CHANGED -> new ErrorPresentation(kind, "La identidad cambió", "La identidad criptográfica de este contacto cambió. Verifica nuevamente antes de continuar con operaciones sensibles.", Glyph.WARNING, Tone.WARNING, false, t);
            case CONTACT_BLOCKED -> new ErrorPresentation(kind, "Contacto bloqueado", "La operación se detuvo porque el contacto está bloqueado.", Glyph.BLOCK, Tone.DANGER, false, t);
            case DEVICE_REVOKED -> new ErrorPresentation(kind, "Dispositivo revocado", "Este dispositivo ya no está autorizado. Las operaciones con él se bloquearon.", Glyph.BLOCK, Tone.DANGER, false, t);
            case CAMERA_DENIED -> new ErrorPresentation(kind, "Permiso de cámara denegado", "UMBRA no puede compartir tu cámara. Puedes seguir en la llamada solo con audio.", Glyph.VIDEO_OFF, Tone.WARNING, false, t);
            case MICROPHONE_DENIED -> new ErrorPresentation(kind, "Permiso de micrófono denegado", "Sin micrófono no se puede hablar en la llamada. Concede el permiso en Ajustes de Android.", Glyph.MIC_OFF, Tone.WARNING, false, t);
            case LOCATION_DENIED -> new ErrorPresentation(kind, "Permiso de ubicación denegado", "No se compartió ninguna ubicación. Puedes enviar un punto manual o conceder el permiso.", Glyph.LOCATION, Tone.WARNING, false, t);
            case BLUETOOTH_DENIED -> new ErrorPresentation(kind, "Permiso de dispositivos cercanos denegado", "Concede el permiso para conectar por Bluetooth.", Glyph.BLUETOOTH, Tone.WARNING, false, t);
            case VAULT_LOCKED -> new ErrorPresentation(kind, "Bóveda bloqueada", "Desbloquea UMBRA para continuar. Tus datos no se borraron.", Glyph.LOCK, Tone.NEUTRAL, false, t);
            case STORAGE_FAILED -> new ErrorPresentation(kind, "No se pudo guardar", "El almacenamiento local falló. La operación no se completó y no se reintentó en silencio.", Glyph.WARNING, Tone.DANGER, true, t);
            case CALL_ENDED -> new ErrorPresentation(kind, "Llamada terminada", "El audio y el video se detuvieron.", Glyph.CALL_END, Tone.NEUTRAL, false, t);
            case BLUETOOTH_UNAVAILABLE -> new ErrorPresentation(kind, "Bluetooth no disponible", "Activa Bluetooth en Android o acércate al otro teléfono.", Glyph.BLUETOOTH, Tone.WARNING, true, t);
            case FEATURE_PENDING -> new ErrorPresentation(kind, "Función no disponible todavía", "La interfaz está preparada, pero el motor aún no la implementa.", Glyph.INFO, Tone.NEUTRAL, false, t);
            case OFFLINE_EDITION -> new ErrorPresentation(kind, "No disponible offline", "La edición offline no usa internet. Esta función no está incluida.", Glyph.BLUETOOTH, Tone.OFFLINE, false, t);
            case GENERIC -> new ErrorPresentation(kind, "Operación no completada", "Revisa la conexión, la invitación y el estado del contacto.", Glyph.WARNING, Tone.WARNING, true, t);
        };
    }

    /**
     * Classifies an engine/service message into a user category. Messages come from Engine
     * exceptions (already user-safe, bounded); anything unrecognized becomes GENERIC.
     */
    public static ErrorKind classify(String message) {
        if (message == null) return ErrorKind.GENERIC;
        String m = message.toLowerCase(Locale.ROOT);
        if (m.contains("verifique") || m.contains("verify the") || m.contains("verificar")) return ErrorKind.CONTACT_UNVERIFIED;
        if (m.contains("identity change") || m.contains("identidad cambi")) return ErrorKind.IDENTITY_CHANGED;
        if (m.contains("bloquead") || m.contains("blocked")) return ErrorKind.CONTACT_BLOCKED;
        if (m.contains("revoked") || m.contains("revocad") || m.contains("retired")) return ErrorKind.DEVICE_REVOKED;
        if (m.contains("internet") && m.contains("edici")) return ErrorKind.OFFLINE_EDITION;
        if (m.contains("relay") || m.contains("servidor")) return ErrorKind.RELAY_UNAVAILABLE;
        if (m.contains("turn")) return ErrorKind.TURN_UNAVAILABLE;
        if (m.contains("bluetooth")) return ErrorKind.BLUETOOTH_UNAVAILABLE;
        return ErrorKind.GENERIC;
    }

    /** Same category with a context-specific explanation (still user-facing text, never a trace). */
    public ErrorPresentation withBody(String newBody) { return new ErrorPresentation(kind, title, newBody, glyph, tone, retryable, technical); }

    /** Bounded single-line diagnostic; never multi-line traces. */
    static String sanitize(String technical) {
        if (technical == null) return null;
        String line = technical.replaceAll("[\\r\\n\\t]+", " ").trim();
        return line.length() > 160 ? line.substring(0, 160) + "…" : line;
    }
}
