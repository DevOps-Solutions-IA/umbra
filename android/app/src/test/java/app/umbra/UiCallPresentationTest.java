package app.umbra;

import app.umbra.ui.model.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Call, video and location wording follow real engine state and explicit consent. */
public class UiCallPresentationTest {
    @Test public void mediaStateTakesPrecedenceOverSignaling() {
        assertEquals("En llamada", CallPresentation.of("NEGOTIATING", "ACTIVE").phase());
        assertTrue(CallPresentation.of("NEGOTIATING", "ACTIVE").live());
        assertEquals("No se pudo conectar", CallPresentation.of("NEGOTIATING", "FAILED").phase());
        assertEquals("Conectando…", CallPresentation.of("SELECTED", "NEGOTIATING").phase());
    }
    @Test public void incomingCallNeverImpliesMicrophoneOrCamera() {
        CallPresentation incoming = CallPresentation.of("INCOMING", null);
        assertTrue(incoming.incoming());
        assertFalse(incoming.live());
        assertTrue(incoming.detail().contains("no activa tu micrófono ni tu cámara"));
    }
    @Test public void terminalStatesAreNotLive() {
        for (String s : new String[]{"CANCELLED", "REJECTED", "BUSY", "EXPIRED", "ENDED", "FAILED", "NOT_SELECTED"}) {
            CallPresentation p = CallPresentation.of(s, null);
            assertTrue(s, p.terminal()); assertFalse(s, p.live());
        }
    }
    @Test public void formatsDuration() {
        assertEquals("00:42", CallPresentation.duration(42));
        assertEquals("03:00", CallPresentation.duration(180));
        assertEquals("1:00:05", CallPresentation.duration(3605));
        assertEquals("00:00", CallPresentation.duration(-5));
    }
    @Test public void cameraIsTransmittingOnlyWithFreshCaptureEvidence() {
        long now = 10_000_000_000L;
        assertTrue(VideoPresentation.of("ACTIVE", now, now - 500_000_000L, 0).cameraTransmitting());
        assertFalse(VideoPresentation.of("ACTIVE", now, now - 3_000_000_000L, 0).cameraTransmitting());
        assertFalse(VideoPresentation.of("ACTIVE", now, 0, 0).cameraTransmitting());
        assertFalse("closed after last frame", VideoPresentation.of("ACTIVE", now, now - 100, now - 50).cameraTransmitting());
        assertEquals("Esperando imagen…", VideoPresentation.of("WAITING_FOR_FRAME", now, 0, 0).remote());
        assertFalse(VideoPresentation.of(null, now, 0, 0).remoteVisible());
        assertEquals("Bruno solicita activar video.", VideoPresentation.requestHeadline("Bruno"));
    }
    @Test public void locationSummaryStatesWhoHowLongAndPrecision() {
        var draft = LocationShareDraft.live(LocationShareDraft.Precision.APPROXIMATE, 3600);
        var lines = draft.summary("Bruno", 2);
        assertEquals("Quién la recibirá", lines.get(0)[0]);
        assertEquals("Bruno · 2 dispositivos aprobados", lines.get(0)[1]);
        assertTrue(lines.get(1)[1].contains("1 hora"));
        assertTrue(lines.get(2)[1].startsWith("Aproximada"));
        assertThrows(IllegalArgumentException.class, () -> LocationShareDraft.live(LocationShareDraft.Precision.MANUAL, 900));
        assertEquals("15 minutos", LocationShareDraft.durationLabel(900));
        assertEquals("8 horas", LocationShareDraft.durationLabel(28800));
        for (LocationShareDraft.Precision p : LocationShareDraft.Precision.values()) assertEquals(p.name(), p.engineMode());
    }
}
