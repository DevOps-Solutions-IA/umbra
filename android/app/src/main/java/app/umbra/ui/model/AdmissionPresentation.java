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
            case UNCONFIGURED -> new AdmissionPresentation(s, "Entorno no configurado",
                "Importa la configuración pública del entorno UMBRA que te entregó el administrador. Configurar el entorno no admite este dispositivo.",
                Tone.NEUTRAL, Glyph.SHIELD, true, false, false, false, false);
            case NOT_ADMITTED -> new AdmissionPresentation(s, "Entorno UMBRA configurado · sin admisión",
                "Este dispositivo todavía no está admitido. Genera una solicitud y compártela con el administrador por un canal fuera de UMBRA.",
                Tone.NEUTRAL, Glyph.DEVICE_PENDING, false, true, false, false, true);
            case REQUEST_PENDING -> new AdmissionPresentation(s, "Solicitud generada para compartir",
                "La solicitud existe en este teléfono. UMBRA no sabe si el administrador la recibió: nada se envía automáticamente. Cuando recibas la respuesta firmada, impórtala aquí.",
                Tone.WARNING, Glyph.DEVICE_PENDING, false, false, true, true, true);
            case REJECTED -> new AdmissionPresentation(s, "Solicitud rechazada",
                "El administrador firmó un rechazo para esta solicitud. El dispositivo no está admitido. Puedes generar una solicitud nueva si el administrador lo acuerda contigo.",
                Tone.DANGER, Glyph.BLOCK, false, true, false, false, true);
            case ADMITTED -> new AdmissionPresentation(s, "Dispositivo admitido",
                "Este dispositivo tiene una credencial vigente del entorno. No verifica a tus contactos ni conecta por sí solo: conectar sigue siendo una decisión tuya.",
                Tone.SUCCESS, Glyph.DEVICE_AUTHORIZED, false, !requestPresent, requestPresent, requestPresent, true);
            case EXPIRED -> requestPresent && requestExpired
                ? new AdmissionPresentation(s, "La solicitud venció",
                    "La solicitud dura diez minutos y venció sin una respuesta importada. Genera una nueva y compártela otra vez.",
                    Tone.WARNING, Glyph.TIMER, false, true, false, false, true)
                : new AdmissionPresentation(s, "La admisión venció",
                    "La credencial de este dispositivo caducó. Requiere una renovación autorizada por el administrador; UMBRA no renueva por sí sola.",
                    Tone.WARNING, Glyph.TIMER, false, !requestPresent, requestPresent, requestPresent, true);
            case REVOKED -> new AdmissionPresentation(s, "Admisión revocada",
                "El administrador revocó la credencial de este dispositivo. No puede abrir nuevas sesiones del entorno. Tus datos locales no se borraron y no hay forma de omitir esta revocación desde aquí.",
                Tone.BLOCKED, Glyph.DEVICE_REVOKED, false, false, false, false, false);
            case INVALID -> new AdmissionPresentation(s, "Admisión no válida",
                "Los datos de admisión guardados no superan la validación del motor. UMBRA no los repara ni los reemplaza automáticamente.",
                Tone.DANGER, Glyph.WARNING, false, false, false, false, false);
        };
    }

    /** Before the first domain read in this session: no state is claimed and no action is offered. */
    public static AdmissionPresentation notRead() {
        return new AdmissionPresentation(Status.INVALID, "Consultando la admisión…", "UMBRA todavía no leyó el estado de admisión en esta sesión.",
            Tone.NEUTRAL, Glyph.TIMER, false, false, false, false, false);
    }

    /** Offline revocation is only known once the signed revocation is imported; say so plainly. */
    public static final String OFFLINE_REVOCATION_NOTE =
        "Sin conexión, otros teléfonos solo conocen una revocación cuando reciben el archivo firmado. Revocar no borra datos que ya se entregaron.";
    public static final String IMPORT_REJECTED = "El archivo no se aceptó. No se cambió la admisión de este dispositivo.";
    public static final String REALM_IMPORT_REJECTED =
        "No se aceptó esta configuración. Si ya había un entorno configurado, se conserva sin cambios; UMBRA nunca acepta otra autoridad automáticamente.";
}
