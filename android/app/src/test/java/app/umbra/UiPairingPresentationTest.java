package app.umbra;

import app.umbra.pairing.PairingException;
import app.umbra.pairing.PairingSnapshot;
import app.umbra.pairing.PairingSnapshot.NextAction;
import app.umbra.pairing.PairingSnapshot.Phase;
import app.umbra.pairing.PairingSnapshot.Role;
import app.umbra.ui.model.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** PAIRING_PRODUCT_V1 presentation: real phases only, typed failures, never "verified" from pairing. */
public class UiPairingPresentationTest {
    private static PairingSnapshot snap(Role role, Phase phase, NextAction next, long expires, PairingException.Code failure, String peer, boolean verify) {
        return new PairingSnapshot("op", role, phase, next, expires, failure, peer, verify);
    }

    @Test public void stagesFollowOnlyDomainPhases() {
        assertEquals(PairingPresentation.Stage.PREPARING, PairingPresentation.stage(null));
        assertEquals(PairingPresentation.Stage.WAITING, PairingPresentation.stage(snap(Role.INVITER, Phase.INVITE_CREATED, NextAction.WAITING_FOR_PEER, 500, null, null, false)));
        assertEquals(PairingPresentation.Stage.WAITING, PairingPresentation.stage(snap(Role.JOINER, Phase.REQUEST_CREATED, NextAction.WAITING_FOR_PEER, 500, null, null, false)));
        // The inviter created the contact in accept(); the joiner only after complete().
        assertEquals(PairingPresentation.Stage.ADDED, PairingPresentation.stage(snap(Role.INVITER, Phase.ACK_CREATED, NextAction.DELIVER_ACK, 500, null, "p", true)));
        assertEquals(PairingPresentation.Stage.WAITING, PairingPresentation.stage(snap(Role.JOINER, Phase.ACK_CREATED, NextAction.DELIVER_ACK, 500, null, "p", true)));
        assertEquals(PairingPresentation.Stage.ADDED, PairingPresentation.stage(snap(Role.JOINER, Phase.COMPLETE, NextAction.VERIFY_IDENTITY, 500, null, "p", true)));
        for (Phase end : new Phase[]{Phase.EXPIRED, Phase.REVOKED, Phase.CANCELLED})
            assertEquals(end.name(), PairingPresentation.Stage.ENDED, PairingPresentation.stage(snap(Role.INVITER, end, NextAction.NONE, 0, null, null, false)));
        assertEquals("a failure always ends the flow", PairingPresentation.Stage.ENDED,
            PairingPresentation.stage(snap(Role.INVITER, Phase.INVITE_CREATED, NextAction.SHARE_INVITATION, 500, PairingException.Code.UNAVAILABLE, null, false)));
    }

    @Test public void stepsAreTheThreeRealPhasesWithoutInventedProgress() {
        assertArrayEquals(new String[]{"Preparando…", "Esperando al otro dispositivo…", "Contacto agregado"}, PairingPresentation.STEPS);
        for (String s : PairingPresentation.STEPS) { assertFalse(s, s.contains("%")); assertNull(SpanishText.englishWord(s)); }
    }

    @Test public void progressionAdvancesOnlyWhileWaitingAndNotExpired() {
        assertTrue(PairingPresentation.shouldAdvance(snap(Role.JOINER, Phase.REQUEST_CREATED, NextAction.WAITING_FOR_PEER, 10, null, null, false)));
        assertFalse(PairingPresentation.shouldAdvance(snap(Role.JOINER, Phase.REQUEST_CREATED, NextAction.WAITING_FOR_PEER, 0, null, null, false)));
        assertFalse(PairingPresentation.shouldAdvance(snap(Role.JOINER, Phase.COMPLETE, NextAction.VERIFY_IDENTITY, 10, null, "p", true)));
        assertFalse(PairingPresentation.shouldAdvance(snap(Role.INVITER, Phase.ACK_CREATED, NextAction.DELIVER_ACK, 10, null, "p", true)));
        assertFalse(PairingPresentation.shouldAdvance(snap(Role.INVITER, Phase.CANCELLED, NextAction.NONE, 10, PairingException.Code.CANCELLED, null, false)));
        assertFalse(PairingPresentation.shouldAdvance(null));
    }

    @Test public void everyFailureCodeHasShortSpanishCopyWithoutTechnicalTerms() {
        for (PairingException.Code c : PairingException.Code.values()) {
            String text = PairingPresentation.failure(c);
            assertNotNull(c.name(), text);
            assertTrue(c + ": " + text, text.length() <= 64);
            assertNull(c + ": " + text, SpanishText.englishWord(text));
            for (String banned : new String[]{"realm", "invite", "request", "ack", "capability", "token", "HTTP", "relay", "exception"})
                assertFalse(c + " mentions " + banned, text.toLowerCase(java.util.Locale.ROOT).contains(banned.toLowerCase(java.util.Locale.ROOT)));
        }
        assertEquals("Este código pertenece a este dispositivo.", PairingPresentation.failure(PairingException.Code.SELF_PAIRING));
        assertEquals("Este código venció.", PairingPresentation.failure(PairingException.Code.EXPIRED));
        assertEquals("Este contacto pertenece a otro entorno privado.", PairingPresentation.failure(PairingException.Code.AUTHORITY_MISMATCH));
        assertEquals("Desbloquea UMBRA para continuar.", PairingPresentation.failure(PairingException.Code.VAULT_LOCKED));
    }

    @Test public void typedCodeIsReadFromTheCauseChainNeverFromTheMessage() {
        assertEquals(PairingException.Code.EXPIRED, PairingPresentation.code(new RuntimeException("wrapped", new PairingException(PairingException.Code.EXPIRED))));
        assertEquals(PairingException.Code.UNAVAILABLE, PairingPresentation.code(new IllegalStateException("EXPIRED")));
    }

    @Test public void addedNeverClaimsVerificationAndRetryOnlyForUnavailable() {
        String pending = PairingPresentation.addedDetail(snap(Role.JOINER, Phase.COMPLETE, NextAction.VERIFY_IDENTITY, 1, null, "p", true));
        assertEquals("Verificación pendiente", pending);
        for (String word : new String[]{"seguro", "Seguro", "verificado", "Verificado"}) assertFalse(pending.contains(word));
        assertTrue(PairingPresentation.retryable(PairingException.Code.UNAVAILABLE));
        assertFalse(PairingPresentation.retryable(PairingException.Code.AUTHORITY_MISMATCH));
        assertFalse(PairingPresentation.retryable(PairingException.Code.EXPIRED));
    }

    @Test public void validityIsFormattedAndNeverNegative() {
        assertEquals("Válido durante 9:42", PairingPresentation.validFor(582));
        assertEquals("Válido durante 0:05", PairingPresentation.validFor(5));
        assertEquals("Venció", PairingPresentation.validFor(0));
        assertEquals("Venció", PairingPresentation.validFor(-3));
    }

    @Test public void onlinePairingNeedsRelayAdmissionAndConsent() {
        assertNull(PairingPresentation.onlineProblem(true, true, true));
        assertEquals("Conexión privada no disponible", PairingPresentation.onlineProblem(false, true, true));
        assertEquals("Conexión privada no disponible", PairingPresentation.onlineProblem(true, false, true));
        assertEquals("Conexión privada no disponible", PairingPresentation.onlineProblem(true, true, false));
    }
}
