package app.umbra;

import app.umbra.ui.model.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Errors are human, visible and never carry multi-line traces. */
public class UiErrorPresentationTest {
    @Test public void everyKindHasHumanTitleBodyAndIcon() {
        for (ErrorKind kind : ErrorKind.values()) {
            ErrorPresentation e = ErrorPresentation.of(kind);
            assertFalse(kind.name(), e.title().isBlank());
            assertFalse(kind.name(), e.body().isBlank());
            assertNotNull(kind.name(), e.glyph());
            assertFalse(kind.name(), e.body().contains("Exception"));
            assertNull(e.technical());
        }
    }
    @Test public void technicalDetailIsSingleLineAndBounded() {
        String trace = "java.lang.SecurityException: x\n\tat app.umbra.crypto.Engine.verify(Engine.java:1)\n" + "y".repeat(400);
        String detail = ErrorPresentation.of(ErrorKind.GENERIC, trace).technical();
        assertFalse(detail.contains("\n"));
        assertFalse(detail.contains("\t"));
        assertTrue(detail.length() <= 161);
    }
    @Test public void typedFailuresMapWithoutReadingMessages() {
        // Same message text, different typed codes: only the type decides the wording.
        assertEquals("Ya se abrió.", FailurePresentation.text(new app.umbra.content.ContentException(app.umbra.content.ContentException.Code.CONSUMED)));
        assertEquals("Caducado.", FailurePresentation.text(new app.umbra.content.ContentException(app.umbra.content.ContentException.Code.EXPIRED)));
        assertEquals("Otra autoridad. Se conserva la actual.",
            FailurePresentation.text(new app.umbra.admission.AdmissionException(app.umbra.admission.AdmissionException.Code.AUTHORITY_MISMATCH)));
        assertEquals("Bóveda bloqueada.", FailurePresentation.text(new app.umbra.core.AccessGate.LockedException()));
        // An untyped exception whose message looks meaningful is still the generic text.
        assertEquals("No completado.", FailurePresentation.text(new SecurityException("Contacto bloqueado: admission revoked")));
        assertEquals("No completado.", FailurePresentation.text(new IllegalStateException("Verifique el código")));
        assertEquals("Activa la cercanía", FailurePresentation.text(new FailurePresentation.UiRefusal("Activa la cercanía")));
        for (app.umbra.privacy.OperationFailure kind : app.umbra.privacy.OperationFailure.values()) {
            String text = FailurePresentation.text(kind);
            assertTrue(kind.name(), SpanishText.isSpanish(text));
            assertTrue(kind.name(), text.length() <= 64);
        }
    }
    @Test public void identityChangeErrorUsesPlainLanguage() {
        ErrorPresentation e = ErrorPresentation.of(ErrorKind.IDENTITY_CHANGED);
        assertEquals("Identidad cambió", e.title());
        assertEquals("Verifica de nuevo antes de continuar.", e.body());
        assertFalse(e.retryable());
    }
}
