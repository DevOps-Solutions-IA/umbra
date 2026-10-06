package app.umbra;

import app.umbra.core.EmergencyLock;
import app.umbra.ui.model.*;
import java.util.List;
import java.util.Locale;
import org.junit.Test;
import static org.junit.Assert.*;

/** Emergency wording follows the coordinator exactly: only CLOSED is confirmed; INCOMPLETE keeps access denied. */
public class UiEmergencyPresentationTest {
    private static EmergencyLock.Status status(EmergencyLock.State state, EmergencyLock.Outcome... outcomes) {
        List<EmergencyLock.Result> results = new java.util.ArrayList<>();
        EmergencyLock.Subsystem[] subsystems = EmergencyLock.Subsystem.values();
        for (int i = 0; i < outcomes.length; i++) results.add(new EmergencyLock.Result(subsystems[i % subsystems.length], outcomes[i], 1));
        return new EmergencyLock.Status(state, 1, 2, 3, results);
    }
    @Test public void onlyClosedConfirmsAndAllowsNewAuthentication() {
        for (EmergencyLock.State s : EmergencyLock.State.values()) {
            EmergencyPresentation p = EmergencyPresentation.of(status(s));
            boolean closed = s == EmergencyLock.State.CLOSED;
            assertEquals(s.name(), closed, p.title().toLowerCase(Locale.ROOT).contains("confirmado"));
            if (s != EmergencyLock.State.READY) assertEquals(s.name(), closed, p.allowsNewAuthentication());
        }
        assertFalse(EmergencyPresentation.of(status(EmergencyLock.State.INCOMPLETE)).allowsNewAuthentication());
        assertFalse(EmergencyPresentation.of(status(EmergencyLock.State.CLOSING)).finished());
        assertFalse(EmergencyPresentation.of(status(EmergencyLock.State.INVALIDATED)).finished());
        assertTrue(EmergencyPresentation.of(status(EmergencyLock.State.INCOMPLETE)).body().contains("denegado"));
        assertTrue("a missing status fails closed", !EmergencyPresentation.of(null).allowsNewAuthentication());
    }
    @Test public void perSubsystemOutcomesAreShownWithoutInventingSuccess() {
        EmergencyPresentation p = EmergencyPresentation.of(status(EmergencyLock.State.INCOMPLETE,
            EmergencyLock.Outcome.CLOSED, EmergencyLock.Outcome.TIMED_OUT, EmergencyLock.Outcome.FAILED));
        assertEquals(3, p.lines().size());
        assertEquals("Cerrado", p.lines().get(0).outcome());
        assertEquals("Sin confirmar", p.lines().get(1).outcome());
        assertEquals("Falló", p.lines().get(2).outcome());
        for (EmergencyLock.Subsystem s : EmergencyLock.Subsystem.values()) assertTrue(SpanishText.isSpanish(EmergencyPresentation.subsystem(s)));
    }
    @Test public void noRemoteOrForensicClaims() {
        StringBuilder all = new StringBuilder();
        for (EmergencyLock.State s : EmergencyLock.State.values()) { EmergencyPresentation p = EmergencyPresentation.of(status(s)); all.append(p.title()).append(p.body()); }
        for (String line : Help.EMERGENCY.lines) all.append(line);
        String text = all.toString().toLowerCase(Locale.ROOT);
        for (String claim : new String[]{"borrado", "eliminado", "servidor confirmó", "100%", "irrecuperable"}) assertFalse(claim, text.contains(claim));
        assertTrue(Help.EMERGENCY.lines.contains("No pide contraseña."));
        assertTrue(Help.EMERGENCY.lines.contains("Lo ya entregado no se retira."));
    }
    @Test public void peerAdmissionIsLocalEvidenceNeverVerification() {
        for (String state : new String[]{"VALID_LOCALLY", "EXPIRED", "REVOKED", "UNKNOWN", "INVALID", null, "FUTURE"})
            for (String source : new String[]{"UNKNOWN_LEGACY", "PUBLIC_CREDENTIAL", "CHALLENGE_PROOF", "NEARBY_PROOF", null}) {
                PeerAdmissionPresentation p = PeerAdmissionPresentation.of(state, source);
                assertFalse(p.value().toLowerCase(Locale.ROOT).contains("verificad"));
                assertNotEquals(Tone.VERIFIED, p.tone());
                assertTrue(p.value(), SpanishText.isSpanish(p.value()));
            }
        assertTrue(PeerAdmissionPresentation.of("VALID_LOCALLY", "NEARBY_PROOF").description().contains("No verifica al contacto"));
        assertEquals("No válida", PeerAdmissionPresentation.of("FUTURE", null).value());
    }
}
