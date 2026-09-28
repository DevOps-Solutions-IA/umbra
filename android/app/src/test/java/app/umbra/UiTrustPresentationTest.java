package app.umbra;

import app.umbra.ui.model.*;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

/** Trust wording is derived only from the engine state name and never relaxes sensitive actions. */
public class UiTrustPresentationTest {
    @Test public void mapsEveryEngineStateAndFailsClosedOnUnknownValues() {
        assertEquals(TrustLevel.VERIFIED, TrustLevel.fromEngine("VERIFIED"));
        assertEquals(TrustLevel.IDENTITY_CHANGED, TrustLevel.fromEngine("IDENTITY_CHANGED"));
        assertEquals(TrustLevel.BLOCKED, TrustLevel.fromEngine("BLOCKED"));
        assertEquals(TrustLevel.UNVERIFIED, TrustLevel.fromEngine("UNVERIFIED"));
        assertEquals(TrustLevel.UNVERIFIED, TrustLevel.fromEngine(null));
        assertEquals(TrustLevel.UNVERIFIED, TrustLevel.fromEngine("verified-ish"));
        assertEquals(TrustLevel.UNVERIFIED, TrustLevel.fromEngine(""));
    }
    @Test public void onlyVerifiedAllowsSensitiveOperations() {
        for (TrustLevel level : TrustLevel.values()) {
            TrustPresentation p = TrustPresentation.of(level);
            boolean verified = level == TrustLevel.VERIFIED;
            assertEquals(level.name(), verified, p.allowsMessaging());
            assertEquals(level.name(), verified, p.allowsCalls());
            assertEquals(level.name(), verified, p.allowsLocation());
            assertEquals(level.name(), verified, p.blockedReason() == null);
        }
    }
    @Test public void identityChangeIsExplainedWithoutCryptographicJargon() {
        TrustPresentation changed = TrustPresentation.of(TrustLevel.IDENTITY_CHANGED);
        assertEquals("La identidad criptográfica de este contacto cambió. Verifica nuevamente antes de continuar con operaciones sensibles.", changed.explanation());
        for (TrustLevel level : TrustLevel.values()) {
            String all = (TrustPresentation.of(level).label() + TrustPresentation.of(level).explanation()).toLowerCase(Locale.ROOT);
            for (String jargon : new String[]{"x3dh", "signature", "mismatch", "ratchet", "prekey", "libsignal"})
                assertFalse(level + " uses jargon " + jargon, all.contains(jargon));
        }
    }
    @Test public void statesAreDistinguishableWithoutColor() {
        Set<Glyph> glyphs = new HashSet<>(); Set<String> labels = new HashSet<>();
        for (TrustLevel level : TrustLevel.values()) {
            TrustPresentation p = TrustPresentation.of(level);
            glyphs.add(p.glyph()); labels.add(p.label());
            assertFalse(p.label().isBlank());
        }
        assertEquals(TrustLevel.values().length, glyphs.size());
        assertEquals(TrustLevel.values().length, labels.size());
    }
    @Test public void conversationRowNeverClaimsVerificationForOtherStates() {
        for (TrustLevel level : TrustLevel.values()) {
            ConversationItem item = ConversationItem.direct("peer", "Ana", level, "");
            assertEquals(level == TrustLevel.VERIFIED, item.subtitle().startsWith("Verificado"));
            assertTrue(item.accessibilityLabel().contains("Ana"));
        }
    }
}
