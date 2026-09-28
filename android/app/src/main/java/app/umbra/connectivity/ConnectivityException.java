package app.umbra.connectivity;

/** Rejection is local and contains no origin, credential or transport diagnostics. */
public final class ConnectivityException extends SecurityException {
    public enum Code { CONSENT_OR_SESSION_UNAVAILABLE, NEARBY_ALREADY_REQUESTED }
    private final Code code;
    public ConnectivityException(Code code) { super("Connectivity unavailable"); this.code=java.util.Objects.requireNonNull(code); }
    public Code code() { return code; }
}
