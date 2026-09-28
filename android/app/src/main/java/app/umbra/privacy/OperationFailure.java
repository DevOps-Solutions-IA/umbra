package app.umbra.privacy;

/** Presentation mapping only. Never catches an operation or converts failure into success. */
public enum OperationFailure {
    LOCKED, ADMISSION_INVALID, AUTHORITY_MISMATCH, REQUEST_PENDING, REQUEST_CONSUMED,
    CAPACITY_REACHED, CONNECTIVITY_UNAVAILABLE, NEARBY_ALREADY_REQUESTED,
    CONSENT_REQUIRED, EXPORT_FORBIDDEN, INVALID_CONTENT, RESOURCE_LIMIT, UNAVAILABLE;
    public static OperationFailure classify(Throwable failure) {
        if(failure instanceof app.umbra.core.AccessGate.LockedException)return LOCKED;
        if(failure instanceof app.umbra.admission.AdmissionException admission)return switch(admission.code()) {
            case INVALID -> ADMISSION_INVALID; case AUTHORITY_MISMATCH -> AUTHORITY_MISMATCH;
            case REQUEST_PENDING -> REQUEST_PENDING; case REQUEST_CONSUMED -> REQUEST_CONSUMED;
            case CAPACITY_REACHED -> CAPACITY_REACHED;
        };
        if(failure instanceof app.umbra.connectivity.ConnectivityException connection)return switch(connection.code()) {
            case CONSENT_OR_SESSION_UNAVAILABLE -> CONNECTIVITY_UNAVAILABLE;
            case NEARBY_ALREADY_REQUESTED -> NEARBY_ALREADY_REQUESTED;
        };
        if(failure instanceof PrivacyException privacy)return switch(privacy.code()) {
            case CONSENT_REQUIRED -> CONSENT_REQUIRED; case RESTRICTED_EXPORT -> EXPORT_FORBIDDEN;
            case INVALID_CONTENT -> INVALID_CONTENT; case LIMIT_EXCEEDED -> RESOURCE_LIMIT;
        };
        return UNAVAILABLE;
    }
}
