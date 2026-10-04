package app.umbra.ui.model;

import app.umbra.access.AccessSnapshot;
import app.umbra.access.AccessSnapshot.Outcome;
import app.umbra.access.AccessSnapshot.Phase;
import app.umbra.core.AccessGate;
import java.util.Locale;

/**
 * Presentation of ACCESS_READINESS_V1 snapshots and operation results. Observation only: nothing here
 * grants, extends or restores access. Every operation still re-validates in the domain, and a snapshot
 * may be stale immediately (docs/contracts/ACCESS_READINESS_V1.md).
 */
public final class AccessPresentation {
    private AccessPresentation() {}

    /** Short user copy for a phase: title + optional one-line detail. Never technical names. */
    public record Copy(String title, String detail, Tone tone, Glyph glyph, boolean working) {}

    public static Copy of(Phase phase) {
        return switch (phase) {
            case LOCKED, ANDROID_AUTH_REQUIRED -> new Copy("UMBRA bloqueado", null, Tone.NEUTRAL, Glyph.LOCK, false);
            case ANDROID_AUTHENTICATING -> new Copy("Desbloquear UMBRA", "Esperando a Android…", Tone.NEUTRAL, Glyph.LOCK, true);
            case METADATA_REQUIRED -> new Copy("Abriendo…", null, Tone.NEUTRAL, Glyph.VAULT_LOCKED, true);
            case PASSWORD_CREATE_REQUIRED -> new Copy("Protege UMBRA", "Crea tu contraseña. No se puede recuperar.", Tone.ACCENT, Glyph.PASSWORD, false);
            case LEGACY_ENROLLMENT_REQUIRED -> new Copy("Añade una contraseña", "Hoy solo usa el bloqueo de Android.", Tone.WARNING, Glyph.VAULT_LOCKED, false);
            case PASSWORD_REQUIRED -> new Copy("Desbloquear UMBRA", null, Tone.NEUTRAL, Glyph.VAULT_LOCKED, false);
            case PASSWORD_CREATE_WORKING -> new Copy("Creando protección…", null, Tone.NEUTRAL, Glyph.PASSWORD, true);
            case PASSWORD_UNLOCK_WORKING -> new Copy("Abriendo…", null, Tone.NEUTRAL, Glyph.UNLOCK, true);
            case PASSWORD_CHANGE_WORKING -> new Copy("Cambiando contraseña…", null, Tone.NEUTRAL, Glyph.CHANGE_PASSWORD, true);
            case OPEN -> new Copy("UMBRA abierto", null, Tone.SUCCESS, Glyph.VAULT_UNLOCKED, false);
            case LOCKING -> new Copy("Bloqueando…", null, Tone.NEUTRAL, Glyph.LOCK, true);
            case CORRUPT -> new Copy("No se pudo abrir la bóveda", "No se borró ni se reinició nada.", Tone.DANGER, Glyph.WARNING, false);
            case KEY_UNAVAILABLE -> new Copy("Clave del dispositivo no disponible", "No se borró nada ni se generó otra clave.", Tone.DANGER, Glyph.WARNING, false);
            case EMERGENCY_CLOSING -> new Copy("Cerrando UMBRA…", null, Tone.WARNING, Glyph.EMERGENCY_LOCK, true);
            case EMERGENCY_CLOSED -> new Copy("UMBRA cerrado", "Desbloquea de nuevo para continuar.", Tone.NEUTRAL, Glyph.EMERGENCY_LOCK, false);
            case EMERGENCY_INCOMPLETE -> new Copy("Cierre incompleto", "No se pudo confirmar todo. Reinicia el teléfono.", Tone.DANGER, Glyph.WARNING, false);
        };
    }

    /** Why the app is locked, shown once on the lock screen; null when there is nothing useful to say. */
    public static String lockReason(AccessGate.LockCause cause) {
        if (cause == null) return null;
        return switch (cause) {
            case AUTOLOCK -> "Se bloqueó por tiempo.";
            case ANDROID_AUTH_EXPIRED -> "La autenticación venció.";
            case BACKGROUND -> "Se bloqueó al salir de UMBRA.";
            case KEY_INVALIDATED -> "La clave del dispositivo cambió.";
            case VAULT_FAILURE -> "No se pudo leer la bóveda.";
            case EMERGENCY -> "Cierre de emergencia.";
            case PROCESS_RESTART, USER_REQUEST, UNKNOWN -> null;
        };
    }

    /** What the user sees after a password operation result; {@link ResultKind} drives navigation. */
    public enum ResultKind { OPENED, DONE_LOCKED, RETRY, TERMINAL, REAUTHENTICATE, IGNORE, BUSY, COMMITTED_WITH_ERROR }

    public record Result(ResultKind kind, String message, AccessStep terminal) {
        Result(ResultKind kind, String message) { this(kind, message, null); }
    }

    public static Result result(AccessSnapshot.OperationResult r, boolean change) {
        if (r == null) return new Result(ResultKind.IGNORE, null);
        Outcome o = r.outcome();
        return switch (o) {
            case OPENED -> new Result(ResultKind.OPENED, null);
            case COMPLETED_LOCKED -> new Result(ResultKind.DONE_LOCKED, change ? AccessStep.LOCKED_AFTER_CHANGE : AccessStep.LOCKED_AFTER_CREATE);
            // Wrong password and tampered data are deliberately indistinguishable.
            case GENERIC_FAILURE -> new Result(ResultKind.RETRY, change ? "No se cambió. Revisa la actual." : r.operation() == AccessSnapshot.Operation.UNLOCK
                ? AccessStep.UNLOCK_FAILED : "No se creó. Nada cambió.");
            case CORRUPT -> new Result(ResultKind.TERMINAL, of(Phase.CORRUPT).title(), AccessStep.CORRUPT);
            case KEY_UNAVAILABLE -> new Result(ResultKind.TERMINAL, of(Phase.KEY_UNAVAILABLE).title(), AccessStep.KEY_UNAVAILABLE);
            case BUSY -> new Result(ResultKind.BUSY, "Otra operación sigue en curso.");
            // The SQLite change is already committed; never retry automatically or assume rollback.
            case COMMITTED_CLEANUP_FAILED -> new Result(ResultKind.COMMITTED_WITH_ERROR,
                "Se guardó, pero el cierre falló. No repitas; desbloquea de nuevo.");
            case AUTHENTICATION_REQUIRED -> new Result(ResultKind.REAUTHENTICATE, null);
            case STALE, EXTERNAL_CANCELLED, EXTERNAL_ACTION_REQUIRED, NONE -> new Result(ResultKind.IGNORE, null);
        };
    }

    /**
     * Discreet countdown from an observed snapshot ("Bloqueo en 3:42"), or null when closed. The value is
     * the remaining time the domain reported at observation; it is not renewed by touches and not persisted.
     */
    public static String remaining(AccessSnapshot s) {
        if (s == null || s.phase() != Phase.OPEN || s.effectiveRemainingMillis() <= 0) return null;
        long seconds = s.effectiveRemainingMillis() / 1000;
        return String.format(Locale.ROOT, "Bloqueo en %d:%02d", seconds / 60, seconds % 60);
    }
}
