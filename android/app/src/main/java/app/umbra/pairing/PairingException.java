package app.umbra.pairing;

/** Presentation-safe failure: no transcript, provider exception or capability. */
public final class PairingException extends SecurityException {
    public enum Code {
        SELF_PAIRING, INVALID_FORMAT, INVALID_SIGNATURE, EXPIRED, REVOKED,
        WRONG_INVITATION, WRONG_ROLE, STATE_MISMATCH, ALREADY_CONSUMED,
        AUTHORITY_MISMATCH, ADMISSION_INVALID, VAULT_LOCKED, CAPACITY_REACHED,
        CANCELLED, REPLAY, PAYLOAD_TOO_LARGE, UNAVAILABLE
    }
    private final Code code;
    public PairingException(Code code) { super("Pairing unavailable"); this.code=java.util.Objects.requireNonNull(code); }
    public Code code() { return code; }
}
