package app.umbra.ui.model;

import app.umbra.privacy.OperationFailure;

/**
 * Short Spanish text for a typed domain failure (contract UI_SECURITY_CONTENT_API_V1). The category comes
 * only from {@link OperationFailure#classify(Throwable)}: exception messages and causes are never parsed,
 * and an unknown failure stays the generic UNAVAILABLE text. Presentation only; it never decides access.
 */
public final class FailurePresentation {
    private FailurePresentation() {}

    /** Refusal created by the presentation layer itself (precondition text already written in Spanish). */
    public static final class UiRefusal extends SecurityException {
        public UiRefusal(String text) { super(text); }
    }

    public static String text(Throwable failure) {
        if (failure instanceof UiRefusal refusal) return refusal.getMessage();
        return text(OperationFailure.classify(failure));
    }

    public static String text(OperationFailure kind) {
        return switch (kind) {
            case LOCKED -> "Bóveda bloqueada.";
            case ADMISSION_INVALID -> "Admisión no válida.";
            case AUTHORITY_MISMATCH -> "Otra autoridad. Se conserva la actual.";
            case REQUEST_PENDING -> "Ya hay una solicitud pendiente.";
            case REQUEST_CONSUMED -> "Solicitud ya decidida.";
            case CAPACITY_REACHED -> "Límite de admisiones alcanzado.";
            case CONNECTIVITY_UNAVAILABLE -> "Red no habilitada.";
            case NEARBY_ALREADY_REQUESTED -> "Cercanía ya activa.";
            case ADMISSION_EXPIRED -> "Admisión vencida.";
            case ADMISSION_NOT_YET_VALID -> "Admisión aún no vigente.";
            case ADMISSION_REVOKED -> "Admisión revocada.";
            case WRONG_DEVICE -> "Es de otro dispositivo.";
            case NOT_AUTHORITY -> "Solo la autoridad puede hacerlo.";
            case NOT_ADMITTED -> "Requiere admisión vigente.";
            case EDITION_UNAVAILABLE -> "No incluido en esta edición.";
            case CONNECTIVITY_STATE -> "Estado de red no permite esto.";
            case CLEANUP_FAILED -> "Cierre no confirmado.";
            case CONSENT_REQUIRED -> "Confirmación vencida. Repite la acción.";
            case EXPORT_FORBIDDEN -> "Contenido protegido: no se exporta.";
            case INVALID_CONTENT -> "Formato no admitido.";
            case RESOURCE_LIMIT -> "Límite alcanzado. Cierra o espera.";
            case CONTENT_EXPIRED -> "Caducado.";
            case CONTENT_CONSUMED -> "Ya se abrió.";
            case CONTENT_BUSY -> "Ya está abierto.";
            case UNAVAILABLE -> "No completado.";
        };
    }
}
