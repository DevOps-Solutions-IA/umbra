package app.umbra.admission;

/** Offline provisioning rejection; messages contain no imported or stored values. */
public final class ProvisioningException extends SecurityException {
    public enum Code { INVALID_CONFIGURATION, SOURCE_CONFIRMATION_REQUIRED, REVIEW_UNAVAILABLE, BINDING_MISMATCH, CONNECTIVITY_ACTIVE }
    private final Code code;
    public ProvisioningException(Code code) { super("Offline provisioning rejected: " + code); this.code=code; }
    public Code code() { return code; }
}
