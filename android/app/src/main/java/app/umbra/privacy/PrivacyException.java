package app.umbra.privacy;

public final class PrivacyException extends SecurityException {
    public enum Code { CONSENT_REQUIRED, RESTRICTED_EXPORT, INVALID_CONTENT, LIMIT_EXCEEDED }
    private final Code code;
    public PrivacyException(Code code) { super("Privacy operation unavailable"); this.code=code; }
    public Code code() { return code; }
}
