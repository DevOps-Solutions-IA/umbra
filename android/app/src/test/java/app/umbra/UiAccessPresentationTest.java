package app.umbra;

import app.umbra.ui.model.AccessStep;
import app.umbra.ui.model.PasswordPolicy;
import app.umbra.ui.model.PasswordPolicy.Problem;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

/** Personal password presentation: byte-based bounds, exact bytes, and steps derived only from domain values. */
public class UiAccessPresentationTest {
    @Test public void vaultNotConfiguredAsksForAPasswordBeforeAnyIdentity() {
        assertEquals(AccessStep.CREATE_PASSWORD, AccessStep.from("UNINITIALIZED", false));
        assertEquals(AccessStep.LEGACY_ENROLLMENT, AccessStep.from("LOCKED", false));
    }
    @Test public void lockedVaultRequiresThePasswordAndUnknownStatesFailClosed() {
        assertEquals(AccessStep.PASSWORD_UNLOCK, AccessStep.from("LOCKED", true));
        assertEquals(AccessStep.PASSWORD_UNLOCK, AccessStep.from("UNLOCKING", true));
        assertEquals(AccessStep.PASSWORD_UNLOCK, AccessStep.from("LOCKING", true));
        assertEquals(AccessStep.PASSWORD_UNLOCK, AccessStep.from("SOMETHING_NEW", true));
        assertEquals(AccessStep.PASSWORD_UNLOCK, AccessStep.from(null, true));
        assertEquals(AccessStep.OPEN, AccessStep.from("UNLOCKED", true));
    }
    @Test public void damagedOrKeylessVaultsAreTerminalAndNeverOfferReset() {
        for (AccessStep step : new AccessStep[]{AccessStep.from("CORRUPT", true), AccessStep.from("KEY_UNAVAILABLE", false)}) {
            assertTrue(step.terminalFailure());
            String text = (step.title + " " + step.body).toLowerCase(java.util.Locale.ROOT);
            assertTrue(text.contains("no se borr") || text.contains("no se gener"));
            assertFalse(text.contains("restablecer"));
        }
    }
    @Test public void incorrectPasswordMessageIsGenericAndCreateChangeEndLocked() {
        assertFalse(AccessStep.UNLOCK_FAILED.toLowerCase(java.util.Locale.ROOT).contains("incorrecta"));
        assertTrue(AccessStep.LOCKED_AFTER_CREATE.contains("bloqueada"));
        assertTrue(AccessStep.LOCKED_AFTER_CHANGE.contains("bloqueada"));
    }
    @Test public void boundsAreCountedInUtf8BytesNotCharacters() {
        assertEquals(Problem.EMPTY, PasswordPolicy.check(""));
        assertEquals(Problem.TOO_SHORT, PasswordPolicy.check("elevenchars"));
        assertEquals(Problem.OK, PasswordPolicy.check("twelve chars"));
        assertEquals(Problem.OK, PasswordPolicy.check("ñandúñandú"));      // 10 characters, 14 bytes
        assertEquals(Problem.TOO_SHORT, PasswordPolicy.check("ñandú"));     // 5 characters, 7 bytes
        assertEquals(Problem.OK, PasswordPolicy.check("a".repeat(1024)));
        assertEquals(Problem.TOO_LONG, PasswordPolicy.check("a".repeat(1025)));
        assertEquals(Problem.TOO_LONG, PasswordPolicy.check("ñ".repeat(513))); // 513 characters, 1026 bytes
        assertEquals(Problem.INVALID_CHARACTER, PasswordPolicy.check("valid prefix \uD800"));
        assertEquals(4, PasswordPolicy.utf8Length("🔒"));
    }
    @Test public void encodingKeepsTheTypedBytesWithoutTrimOrNormalization() throws Exception {
        String typed = "  Café́ pass  ";
        byte[] bytes = PasswordPolicy.encode(new StringBuilder(typed));
        assertArrayEquals(typed.getBytes(StandardCharsets.UTF_8), bytes);
        assertEquals(PasswordPolicy.utf8Length(typed), bytes.length);
        PasswordPolicy.erase(bytes);
        assertTrue(Arrays.equals(new byte[bytes.length], bytes));
    }
    @Test public void confirmationMustMatchExactly() {
        assertEquals(Problem.OK, PasswordPolicy.checkNew("synthetic secret", "synthetic secret"));
        assertEquals(Problem.MISMATCH, PasswordPolicy.checkNew("synthetic secret", "synthetic secret "));
        assertEquals(Problem.MISMATCH, PasswordPolicy.checkNew("synthetic secret", null));
        assertEquals(Problem.TOO_SHORT, PasswordPolicy.checkNew("short", "short"));
    }
    @Test public void autoLockOffersNoOptionAboveTheDomainMaximum() {
        assertEquals(PasswordPolicy.AUTO_LOCK_MILLIS.length, PasswordPolicy.AUTO_LOCK_LABELS.length);
        for (long millis : PasswordPolicy.AUTO_LOCK_MILLIS) assertTrue(millis >= 1 && millis <= 240_000);
        assertEquals(240_000, PasswordPolicy.AUTO_LOCK_MILLIS[PasswordPolicy.DEFAULT_AUTO_LOCK_INDEX]);
        for (String label : PasswordPolicy.AUTO_LOCK_LABELS) assertNotEquals("5 min", label);
        assertEquals(PasswordPolicy.DEFAULT_AUTO_LOCK_INDEX, PasswordPolicy.autoLockIndex(300_000));
    }
}
