package app.umbra.ui.model;

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
            case NO_CONNECTION -> new ErrorPresentation(kind, "Sin conexión", "Los mensajes quedan cifrados en cola.", Glyph.NETWORK_OFF, Tone.WARNING, true, t);
            case RELAY_UNAVAILABLE -> new ErrorPresentation(kind, "Conexión privada no disponible", "Los mensajes siguen en cola.", Glyph.CLOUD_OFF, Tone.WARNING, true, t);
            case TURN_UNAVAILABLE -> new ErrorPresentation(kind, "Llamada no establecida", "No se transmitió audio.", Glyph.CALL_END, Tone.DANGER, true, t);
            case CONTACT_UNVERIFIED -> new ErrorPresentation(kind, "Sin verificar", "Compara el código antes de enviar.", Glyph.SHIELD, Tone.WARNING, false, t);
            case IDENTITY_CHANGED -> new ErrorPresentation(kind, "Identidad cambió", "Verifica de nuevo antes de continuar.", Glyph.IDENTITY_CHANGED, Tone.IDENTITY, false, t);
            case CONTACT_BLOCKED -> new ErrorPresentation(kind, "Contacto bloqueado", "Operación detenida.", Glyph.PERSON_BLOCK, Tone.BLOCKED, false, t);
            case DEVICE_REVOKED -> new ErrorPresentation(kind, "Dispositivo revocado", "Operaciones bloqueadas.", Glyph.DEVICE_REVOKED, Tone.BLOCKED, false, t);
            case CAMERA_DENIED -> new ErrorPresentation(kind, "Cámara denegada", "Puedes seguir solo con audio.", Glyph.CAMERA, Tone.WARNING, false, t);
            case MICROPHONE_DENIED -> new ErrorPresentation(kind, "Micrófono denegado", "Concédelo en los ajustes de Android.", Glyph.MIC_OFF, Tone.WARNING, false, t);
            case LOCATION_DENIED -> new ErrorPresentation(kind, "Ubicación denegada", "No se compartió nada. Usa un punto manual.", Glyph.LOCATION, Tone.WARNING, false, t);
            case BLUETOOTH_DENIED -> new ErrorPresentation(kind, "Bluetooth denegado", "Concede el permiso de dispositivos cercanos.", Glyph.BLUETOOTH, Tone.WARNING, false, t);
            case VAULT_LOCKED -> new ErrorPresentation(kind, "Bóveda bloqueada", "Desbloquea para continuar.", Glyph.LOCK, Tone.NEUTRAL, false, t);
            case STORAGE_FAILED -> new ErrorPresentation(kind, "No se pudo guardar", "La operación no se completó.", Glyph.WARNING, Tone.DANGER, true, t);
            case CALL_ENDED -> new ErrorPresentation(kind, "Llamada terminada", "Audio y video detenidos.", Glyph.CALL_END, Tone.NEUTRAL, false, t);
            case BLUETOOTH_UNAVAILABLE -> new ErrorPresentation(kind, "Bluetooth no disponible", "Actívalo o acércate al otro teléfono.", Glyph.BLUETOOTH, Tone.WARNING, true, t);
            case FEATURE_PENDING -> new ErrorPresentation(kind, "Próximamente", "Aún no disponible.", Glyph.INFO, Tone.NEUTRAL, false, t);
            case OFFLINE_EDITION -> new ErrorPresentation(kind, "No disponible sin conexión", "No incluida en esta edición.", Glyph.OFFLINE_BLUETOOTH, Tone.OFFLINE, false, t);
            case GENERIC -> new ErrorPresentation(kind, "No completado", "Inténtalo de nuevo.", Glyph.WARNING, Tone.WARNING, true, t);
        };
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
