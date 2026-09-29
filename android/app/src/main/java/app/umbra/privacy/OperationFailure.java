package app.umbra.privacy;

/** Presentation mapping only. Never catches an operation or converts failure into success. */
public enum OperationFailure {
    LOCKED, ADMISSION_INVALID, AUTHORITY_MISMATCH, REQUEST_PENDING, REQUEST_CONSUMED,
    CAPACITY_REACHED, CONNECTIVITY_UNAVAILABLE, NEARBY_ALREADY_REQUESTED,
    ADMISSION_EXPIRED, ADMISSION_NOT_YET_VALID, ADMISSION_REVOKED, WRONG_DEVICE, NOT_AUTHORITY, NOT_ADMITTED,
    EDITION_UNAVAILABLE, CONNECTIVITY_STATE, CLEANUP_FAILED,
    CONSENT_REQUIRED, EXPORT_FORBIDDEN, INVALID_CONTENT, RESOURCE_LIMIT,
    CONTENT_EXPIRED, CONTENT_CONSUMED, CONTENT_BUSY, UNAVAILABLE;
    public static OperationFailure classify(Throwable failure) {
        if(failure instanceof app.umbra.core.AccessGate.LockedException)return LOCKED;
        if(failure instanceof app.umbra.admission.AdmissionException admission)return switch(admission.code()) {
            case INVALID -> ADMISSION_INVALID; case AUTHORITY_MISMATCH -> AUTHORITY_MISMATCH;
            case REQUEST_PENDING -> REQUEST_PENDING; case REQUEST_CONSUMED -> REQUEST_CONSUMED;
            case CAPACITY_REACHED -> CAPACITY_REACHED; case EXPIRED -> ADMISSION_EXPIRED;
            case NOT_YET_VALID -> ADMISSION_NOT_YET_VALID; case REVOKED -> ADMISSION_REVOKED;
            case WRONG_DEVICE -> WRONG_DEVICE; case NOT_AUTHORITY -> NOT_AUTHORITY; case NOT_ADMITTED -> NOT_ADMITTED;
        };
        if(failure instanceof app.umbra.connectivity.ConnectivityException connection)return switch(connection.code()) {
            case CONSENT_OR_SESSION_UNAVAILABLE -> CONNECTIVITY_UNAVAILABLE;
            case NEARBY_ALREADY_REQUESTED -> NEARBY_ALREADY_REQUESTED;
            case CONSENT_REQUIRED -> CONSENT_REQUIRED; case EDITION -> EDITION_UNAVAILABLE;
            case STATE -> CONNECTIVITY_STATE; case CLEANUP_FAILED -> CLEANUP_FAILED;
        };
        if(failure instanceof PrivacyException privacy)return switch(privacy.code()) {
            case CONSENT_REQUIRED -> CONSENT_REQUIRED; case RESTRICTED_EXPORT -> EXPORT_FORBIDDEN;
            case INVALID_CONTENT -> INVALID_CONTENT; case LIMIT_EXCEEDED -> RESOURCE_LIMIT;
        };
        if(failure instanceof app.umbra.content.ContentException content)return switch(content.code()) {
            case INVALID -> INVALID_CONTENT; case EXPIRED -> CONTENT_EXPIRED;
            case CONSUMED -> CONTENT_CONSUMED; case BUSY -> CONTENT_BUSY;
            case CAPACITY -> RESOURCE_LIMIT; case EXPORT_FORBIDDEN -> EXPORT_FORBIDDEN;
            case CONSENT_REQUIRED -> CONSENT_REQUIRED;
        };
        return UNAVAILABLE;
    }
}
