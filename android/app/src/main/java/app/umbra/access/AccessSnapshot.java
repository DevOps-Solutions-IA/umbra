package app.umbra.access;

import app.umbra.core.AccessGate;

/** Non-secret observation, never an authorization. Deadlines use the AccessGate monotonic clock. */
public record AccessSnapshot(Phase phase, long selectedAutoLockMillis, long effectiveRemainingMillis,
        long effectiveDeadlineMonotonicNanos, long absoluteGateRemainingMillis, long epoch,
        AccessGate.LockCause lockCause, OperationResult operation, ExternalAction externalAction) {
    public enum Phase { LOCKED, ANDROID_AUTH_REQUIRED, ANDROID_AUTHENTICATING,
        METADATA_REQUIRED, PASSWORD_CREATE_REQUIRED, LEGACY_ENROLLMENT_REQUIRED, PASSWORD_CREATE_WORKING,
        PASSWORD_REQUIRED, PASSWORD_UNLOCK_WORKING, OPEN, PASSWORD_CHANGE_WORKING, LOCKING,
        CORRUPT, KEY_UNAVAILABLE, EMERGENCY_CLOSING, EMERGENCY_CLOSED, EMERGENCY_INCOMPLETE }
    public enum Operation { NONE, CREATE_PASSWORD, UNLOCK, CHANGE_PASSWORD, EXTERNAL }
    public enum OperationState { IDLE, WORKING, SUCCESS, FAILED, REQUIRES_USER_ACTION, CANCELLED, EXPIRED, UNAVAILABLE }
    public enum Outcome { NONE, COMPLETED_LOCKED, OPENED, GENERIC_FAILURE, CORRUPT, KEY_UNAVAILABLE,
        STALE, COMMITTED_CLEANUP_FAILED, AUTHENTICATION_REQUIRED, EXTERNAL_ACTION_REQUIRED, EXTERNAL_CANCELLED, BUSY }
    public enum ExternalAction { NONE, DOCUMENT_PICKER, ANDROID_SETTINGS, PERMISSION_PROMPT, ANDROID_AUTHENTICATION }
    public record OperationResult(long id, Operation operation, OperationState state, Outcome outcome) {}
}
