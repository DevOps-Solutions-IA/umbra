package app.umbra.ui.model;

/**
 * Presentation of this device's private admission as reported by {@code AdmissionService.State}.
 *
 * <p>Admission is permission for <em>this</em> device to use a private realm. It is independent of
 * contact verification (never VERIFIED), device linking and chats. Every state keeps its own wording;
 * none is collapsed into a boolean. Actions listed here are offered only because the real domain API
 * exists; the domain still validates each one.
 */
public record AdmissionPresentation(Status status, String title, String body, Tone tone, Glyph glyph,
                                    boolean canImportRealm, boolean canCreateRequest, boolean canImportDecision,
                                    boolean canExportRequest, boolean canImportRevocation) {

    /** Mirrors {@code AdmissionService.State}; unknown values map to {@link #INVALID} (fail closed). */
    public enum Status { UNCONFIGURED, NOT_ADMITTED, REQUEST_PENDING, REJECTED, ADMITTED, EXPIRED, REVOKED, INVALID;
        public static Status fromEngine(String name) {
            if (name != null) for (Status s : values()) if (s.name().equals(name)) return s;
            return INVALID;
        }
    }

    /** Only ADMITTED permits the domain to open connectivity sessions; the UI never infers more. */
    public boolean admitted() { return status == Status.ADMITTED; }

    /**
     * @param state            {@code AdmissionService.State} name
     * @param requestPresent   a pending request exists ({@code pendingRequest()} returned one)
     * @param requestExpired   that pending request's expiry has passed
     */
    public static AdmissionPresentation of(String state, boolean requestPresent, boolean requestExpired) {
        Status s = Status.fromEngine(state);
        return switch (s) {
            // Normal-user wording: "conexión privada". Technical terms (entorno, autoridad, huellas) live in Administración.
            case UNCONFIGURED -> new AdmissionPresentation(s, "Configuración pendiente", "Importa la configuración. Configurar no da acceso.",
                Tone.NEUTRAL, Glyph.SHIELD, true, false, false, false, false);
            case NOT_ADMITTED -> new AdmissionPresentation(s, "Acceso pendiente", "Configurada. Solicita acceso para conectarte.",
                Tone.NEUTRAL, Glyph.DEVICE_PENDING, false, true, false, false, true);
            case REQUEST_PENDING -> new AdmissionPresentation(s, "Solicitando acceso", "Generada · no recibida.",
                Tone.WARNING, Glyph.DEVICE_PENDING, false, false, true, true, true);
            case REJECTED -> new AdmissionPresentation(s, "Acceso no aprobado", "Puedes solicitarlo de nuevo.",
                Tone.DANGER, Glyph.BLOCK, false, true, false, false, true);
            case ADMITTED -> new AdmissionPresentation(s, "Acceso activo", "No verifica contactos ni conecta.",
                Tone.SUCCESS, Glyph.DEVICE_AUTHORIZED, false, !requestPresent, requestPresent, requestPresent, true);
            case EXPIRED -> requestPresent && requestExpired
                ? new AdmissionPresentation(s, "Solicitud vencida", "Genera otra.",
                    Tone.WARNING, Glyph.TIMER, false, true, false, false, true)
                : new AdmissionPresentation(s, "Acceso vencido", "Requiere renovación autorizada; no se renueva sola.",
                    Tone.WARNING, Glyph.TIMER, false, !requestPresent, requestPresent, requestPresent, true);
            case REVOKED -> new AdmissionPresentation(s, "Acceso revocado", "Sin nuevas sesiones; no se borraron tus datos.",
                Tone.BLOCKED, Glyph.DEVICE_REVOKED, false, false, false, false, false);
            case INVALID -> new AdmissionPresentation(s, "Configuración incompatible", "UMBRA no los repara ni los reemplaza.",
                Tone.DANGER, Glyph.WARNING, false, false, false, false, false);
        };
    }

    /** Before the first domain read in this session: no state is claimed and no action is offered. */
    public static AdmissionPresentation notRead() {
        return new AdmissionPresentation(Status.INVALID, "Consultando…", "",
            Tone.NEUTRAL, Glyph.TIMER, false, false, false, false, false);
    }

    /** The domain read failed (not a lock). Nothing is claimed or offered; the next refresh reads again. */
    public static AdmissionPresentation unreadable() {
        return new AdmissionPresentation(Status.INVALID, "Sin lectura", "No se modificó nada.",
            Tone.WARNING, Glyph.WARNING, false, false, false, false, false);
    }

    /** Offline revocation is only known once the signed revocation is imported; say so plainly. */
    public static final String OFFLINE_REVOCATION_NOTE = "Sin conexión, llega solo con el archivo firmado; no borra datos.";
    public static final String IMPORT_REJECTED = "Archivo rechazado. Sin cambios.";
    public static final String REALM_IMPORT_REJECTED = "Configuración rechazada; se conserva la actual.";
}
