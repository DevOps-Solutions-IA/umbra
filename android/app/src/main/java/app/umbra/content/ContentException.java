package app.umbra.content;

public final class ContentException extends SecurityException {
    public enum Code { INVALID, EXPIRED, CONSUMED, BUSY, CAPACITY, EXPORT_FORBIDDEN, CONSENT_REQUIRED }
    private final Code code;
    public ContentException(Code code) { super("Restricted content unavailable");this.code=code; }
    public Code code() { return code; }
}
