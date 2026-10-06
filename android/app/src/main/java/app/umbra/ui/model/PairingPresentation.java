package app.umbra.ui.model;

import app.umbra.pairing.PairingException;
import app.umbra.pairing.PairingSnapshot;
import java.util.Locale;

/**
 * Presentation of PAIRING_PRODUCT_V1 snapshots and typed failures. Observation only: a snapshot never authorizes
 * anything, never contains the code/invitation/request/ack, and COMPLETE (or the inviter's ACK_CREATED) means the
 * contact exists locally and is UNVERIFIED — never "secure" or "verified" (docs/contracts/PAIRING_PRODUCT_V1.md).
 */
public final class PairingPresentation {
    private PairingPresentation() {}

    /** Only the phases the domain can actually report; there is no invented intermediate step. */
    public static final String[] STEPS = {"Preparando…", "Esperando al otro dispositivo…", "Contacto agregado"};

    /** Where a pairing stands for the user. */
    public enum Stage { PREPARING, WAITING, ADDED, ENDED }

    public static Stage stage(PairingSnapshot s) {
        if (s == null) return Stage.PREPARING;
        if (s.failure() != null) return Stage.ENDED;
        return switch (s.phase()) {
            case INVITE_CREATED, REQUEST_CREATED -> Stage.WAITING;
            // ACK_CREATED: the inviter already created the contact in accept(); it does not prove the peer completed.
            case ACK_CREATED -> s.role() == PairingSnapshot.Role.INVITER ? Stage.ADDED : Stage.WAITING;
            case COMPLETE -> Stage.ADDED;
            case EXPIRED, REVOKED, CANCELLED -> Stage.ENDED;
        };
    }

    /** Index of the current step in {@link #STEPS}; ENDED keeps the last reached step (shown as failed). */
    public static int stepIndex(PairingSnapshot s) {
        return switch (stage(s)) {
            case PREPARING -> 0;
            case WAITING, ENDED -> 1;
            case ADDED -> 2;
        };
    }

    /**
     * Foreground-only progression: advance while the exchange is waiting on the peer and not expired. Stops on
     * every terminal state (added, expired, revoked, cancelled, failure).
     */
    public static boolean shouldAdvance(PairingSnapshot s) {
        return s != null && stage(s) == Stage.WAITING && s.expiresInSeconds() > 0;
    }

    /** Short user copy for the closed failure codes. Exception messages are never parsed or shown. */
    public static String failure(PairingException.Code code) {
        if (code == null) return "No se pudo completar.";
        return switch (code) {
            case SELF_PAIRING -> "Este código pertenece a este dispositivo.";
            case INVALID_FORMAT, INVALID_SIGNATURE -> "El código no es válido.";
            case EXPIRED -> "Este código venció.";
            case REVOKED -> "Esta invitación fue cancelada.";
            case WRONG_INVITATION, STATE_MISMATCH, WRONG_ROLE -> "Este proceso ya no coincide.";
            case ALREADY_CONSUMED, REPLAY -> "Este código ya se usó.";
            case AUTHORITY_MISMATCH -> "Este contacto pertenece a otro entorno privado.";
            case ADMISSION_INVALID -> "No se pudo validar su acceso privado.";
            case VAULT_LOCKED -> "Desbloquea UMBRA para continuar.";
            case CAPACITY_REACHED -> "No se puede iniciar otro vínculo ahora.";
            case CANCELLED -> "Vinculación cancelada.";
            case PAYLOAD_TOO_LARGE -> "El código no es compatible.";
            case UNAVAILABLE -> "No se pudo completar.";
        };
    }

    /** Typed failure carried by a PairingException anywhere in the cause chain; UNAVAILABLE otherwise. */
    public static PairingException.Code code(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) if (t instanceof PairingException p) return p.code();
        return PairingException.Code.UNAVAILABLE;
    }

    /** "Válido durante 9:42" from an observed wall-clock remainder; never an authorization or a lease. */
    public static String validFor(long seconds) {
        if (seconds <= 0) return "Venció";
        return String.format(Locale.ROOT, "Válido durante %d:%02d", seconds / 60, seconds % 60);
    }

    /** Result headline after the contact exists. Always pending verification unless the peer was already VERIFIED. */
    public static String addedDetail(PairingSnapshot s) {
        return s != null && !s.verificationRequired() ? "Ya estaba verificado." : "Verificación pendiente";
    }

    /** Online QR/code are offered as ready only with relay, admission and the user's network consent; never faked. */
    public static final String ONLINE_UNAVAILABLE = "Conexión privada no disponible";
    public static String onlineProblem(boolean relayConfigured, boolean admitted, boolean networkAllowed) {
        return relayConfigured && admitted && networkAllowed ? null : ONLINE_UNAVAILABLE;
    }

    /** True when a failure should offer "Intentar de nuevo" (same operation, fresh authorization). */
    public static boolean retryable(PairingException.Code code) {
        return code == PairingException.Code.UNAVAILABLE;
    }
}
