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
    @Test public void classifiesEngineMessages() {
        assertEquals(ErrorKind.CONTACT_UNVERIFIED, ErrorPresentation.classify("Verifique el código de seguridad antes de conversar"));
        assertEquals(ErrorKind.CONTACT_BLOCKED, ErrorPresentation.classify("Contacto desconocido o bloqueado"));
        assertEquals(ErrorKind.OFFLINE_EDITION, ErrorPresentation.classify("Esta edición no tiene acceso a internet"));
        assertEquals(ErrorKind.RELAY_UNAVAILABLE, ErrorPresentation.classify("Sin respuesta del relay"));
        assertEquals(ErrorKind.GENERIC, ErrorPresentation.classify(null));
        assertEquals(ErrorKind.GENERIC, ErrorPresentation.classify("algo inesperado"));
    }
    @Test public void identityChangeErrorUsesPlainLanguage() {
        ErrorPresentation e = ErrorPresentation.of(ErrorKind.IDENTITY_CHANGED);
        assertTrue(e.body().startsWith("La identidad criptográfica de este contacto cambió."));
        assertFalse(e.retryable());
    }
}
