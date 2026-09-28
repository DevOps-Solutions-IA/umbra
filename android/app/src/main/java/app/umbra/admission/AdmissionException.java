package app.umbra.admission;

/** Stable presentation-safe reason. No payload, provider message or nested secret-bearing cause. */
public final class AdmissionException extends SecurityException {
    public enum Code { INVALID, AUTHORITY_MISMATCH, REQUEST_PENDING, REQUEST_CONSUMED, CAPACITY_REACHED, EXPIRED, NOT_YET_VALID, REVOKED, WRONG_DEVICE, NOT_AUTHORITY, NOT_ADMITTED }
    private final Code code;
    public AdmissionException(Code code) { super("Admission unavailable"); this.code=java.util.Objects.requireNonNull(code); }
    public Code code() { return code; }
}
