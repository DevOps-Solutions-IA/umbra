package app.umbra;

import app.umbra.access.AccessSnapshot;
import app.umbra.access.AccessSnapshot.*;
import app.umbra.core.AccessGate;
import app.umbra.ui.model.*;
import java.util.Locale;
import org.junit.Test;
import static org.junit.Assert.*;

/** ACCESS_READINESS_V1 presentation: observation only, short Spanish copy, no false promises. */
public class UiAccessReadinessPresentationTest {
    private static AccessSnapshot snap(Phase phase, long remainingMillis) {
        return new AccessSnapshot(phase, 240_000, remainingMillis, 0, 0, 1, AccessGate.LockCause.UNKNOWN,
            new OperationResult(0, Operation.NONE, OperationState.IDLE, Outcome.NONE), ExternalAction.NONE);
    }

    @Test public void everyPhaseHasShortSpanishCopyAndNoTechnicalNames() {
        for (Phase p : Phase.values()) {
            AccessPresentation.Copy c = AccessPresentation.of(p);
            assertNotNull(p.name(), c);
            assertTrue(p + ": " + c.title(), c.title().length() <= 40);
            assertNull(c.title(), SpanishText.englishWord(c.title()));
            String all = (c.title() + " " + (c.detail() == null ? "" : c.detail())).toLowerCase(Locale.ROOT);
            for (String banned : new String[]{"epoch", "lease", "aead", "tag", "metadata", "schema", "keystore", "argon"})
                assertFalse(p + " mentions " + banned, all.contains(banned));
        }
        assertTrue(AccessPresentation.of(Phase.PASSWORD_UNLOCK_WORKING).working());
        assertTrue(AccessPresentation.of(Phase.PASSWORD_CREATE_WORKING).working());
        assertFalse(AccessPresentation.of(Phase.EMERGENCY_CLOSED).working());
        assertNotEquals("closing is never presented as closed", AccessPresentation.of(Phase.EMERGENCY_CLOSING).title(), AccessPresentation.of(Phase.EMERGENCY_CLOSED).title());
    }

    @Test public void countdownOnlyWhileOpenAndFromTheObservedValue() {
        assertEquals("Bloqueo en 3:42", AccessPresentation.remaining(snap(Phase.OPEN, 222_999)));
        assertNull(AccessPresentation.remaining(snap(Phase.OPEN, 0)));
        assertNull("a closed phase shows no countdown", AccessPresentation.remaining(snap(Phase.PASSWORD_REQUIRED, 100_000)));
        assertNull(AccessPresentation.remaining(null));
    }

    @Test public void autoLockLabelsSayTheCeilingIsAMaximum() {
        assertEquals("4 min máx.", PasswordPolicy.AUTO_LOCK_LABELS[PasswordPolicy.AUTO_LOCK_LABELS.length - 1]);
        assertEquals(240_000, PasswordPolicy.AUTO_LOCK_MILLIS[PasswordPolicy.AUTO_LOCK_MILLIS.length - 1]);
    }

    @Test public void lockReasonsAreShortAndUnknownSaysNothing() {
        assertNull(AccessPresentation.lockReason(AccessGate.LockCause.UNKNOWN));
        assertNull(AccessPresentation.lockReason(AccessGate.LockCause.USER_REQUEST));
        assertNull(AccessPresentation.lockReason(null));
        assertEquals("Se bloqueó por tiempo.", AccessPresentation.lockReason(AccessGate.LockCause.AUTOLOCK));
        for (AccessGate.LockCause c : AccessGate.LockCause.values()) {
            String r = AccessPresentation.lockReason(c);
            if (r != null) { assertTrue(r.length() <= 48); assertNull(SpanishText.englishWord(r)); }
        }
    }

    @Test public void operationResultsMapWithoutDistinguishingPasswordFromTampering() {
        AccessPresentation.Result wrong = AccessPresentation.result(new OperationResult(1, Operation.UNLOCK, OperationState.FAILED, Outcome.GENERIC_FAILURE), false);
        assertEquals(AccessPresentation.ResultKind.RETRY, wrong.kind());
        assertEquals("generic by design", AccessStep.UNLOCK_FAILED, wrong.message());
        assertFalse(wrong.message().toLowerCase(Locale.ROOT).contains("incorrecta"));
        assertEquals(AccessPresentation.ResultKind.OPENED, AccessPresentation.result(new OperationResult(1, Operation.UNLOCK, OperationState.SUCCESS, Outcome.OPENED), false).kind());
        AccessPresentation.Result created = AccessPresentation.result(new OperationResult(1, Operation.CREATE_PASSWORD, OperationState.SUCCESS, Outcome.COMPLETED_LOCKED), false);
        assertEquals("create ends LOCKED, never open", AccessPresentation.ResultKind.DONE_LOCKED, created.kind());
        assertEquals(AccessStep.LOCKED_AFTER_CHANGE, AccessPresentation.result(new OperationResult(1, Operation.CHANGE_PASSWORD, OperationState.SUCCESS, Outcome.COMPLETED_LOCKED), true).message());
        assertEquals(AccessStep.CORRUPT, AccessPresentation.result(new OperationResult(1, Operation.UNLOCK, OperationState.FAILED, Outcome.CORRUPT), false).terminal());
        assertEquals(AccessStep.KEY_UNAVAILABLE, AccessPresentation.result(new OperationResult(1, Operation.UNLOCK, OperationState.FAILED, Outcome.KEY_UNAVAILABLE), false).terminal());
        assertEquals(AccessPresentation.ResultKind.IGNORE, AccessPresentation.result(new OperationResult(1, Operation.UNLOCK, OperationState.CANCELLED, Outcome.STALE), false).kind());
        assertEquals(AccessPresentation.ResultKind.BUSY, AccessPresentation.result(new OperationResult(2, Operation.UNLOCK, OperationState.UNAVAILABLE, Outcome.BUSY), false).kind());
        AccessPresentation.Result committed = AccessPresentation.result(new OperationResult(1, Operation.CHANGE_PASSWORD, OperationState.FAILED, Outcome.COMMITTED_CLEANUP_FAILED), true);
        assertEquals("never retried automatically", AccessPresentation.ResultKind.COMMITTED_WITH_ERROR, committed.kind());
        assertEquals(AccessPresentation.ResultKind.REAUTHENTICATE, AccessPresentation.result(new OperationResult(1, Operation.UNLOCK, OperationState.FAILED, Outcome.AUTHENTICATION_REQUIRED), false).kind());
        assertEquals(AccessPresentation.ResultKind.IGNORE, AccessPresentation.result(null, false).kind());
        for (Outcome o : Outcome.values()) assertNotNull(o.name(), AccessPresentation.result(new OperationResult(1, Operation.UNLOCK, OperationState.FAILED, o), false));
    }
}
