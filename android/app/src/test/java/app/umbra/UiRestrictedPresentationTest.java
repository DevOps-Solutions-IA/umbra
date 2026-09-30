package app.umbra;

import app.umbra.ui.model.*;
import app.umbra.ui.model.RestrictedPresentation.Kind;
import java.util.Locale;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * F01–F06 wording: domain metadata only, ONCE distinct from UMBRA_ONLY, object expiry distinct from the session
 * limit, and no state that claims delivery, viewing or listening.
 */
public class UiRestrictedPresentationTest {
    @Test public void everyDomainFormatHasAPresentationAndUnknownFormatsAreNotOpenable() {
        for (String format : new String[]{"PNG", "AAC_ADTS", "PDF_PAGES", "AVC_MP4"}) assertNotNull(format, Kind.ofFormat(format));
        assertEquals(Kind.PHOTO, Kind.ofFormat("PNG"));
        assertEquals(Kind.NOTE, Kind.ofFormat("AAC_ADTS"));
        assertEquals(Kind.PDF, Kind.ofFormat("PDF_PAGES"));
        assertEquals(Kind.VIDEO, Kind.ofFormat("AVC_MP4"));
        assertNull(Kind.ofFormat("GIF"));
        RestrictedPresentation.Received unknown = RestrictedPresentation.received("id", "GIF", "ONCE", false, false, "10:00");
        assertFalse("an unknown format is never offered to open", unknown.canOpen());
    }
    @Test public void onceAndUmbraOnlyAreDistinctAndNeitherIsExportable() {
        assertNotEquals(RestrictedPresentation.modeLabel("ONCE"), RestrictedPresentation.modeLabel("UMBRA_ONLY"));
        assertTrue(RestrictedPresentation.modeDetail("ONCE").contains("consume"));
        assertTrue(RestrictedPresentation.modeDetail("UMBRA_ONLY").contains("caduque"));
        assertTrue(RestrictedPresentation.openWarning("ONCE").contains("No se podrá abrir otra vez"));
        assertTrue(RestrictedPresentation.openWarning("UMBRA_ONLY").contains("No se exporta"));
        assertTrue(Help.PROTECTED.lines.contains("No se exporta, comparte, reenvía ni imprime."));
    }
    @Test public void receivedStatesComeFromDomainFlagsOnly() {
        RestrictedPresentation.Received available = RestrictedPresentation.received("a", "PNG", "ONCE", false, false, "27/09 10:40");
        assertTrue(available.canOpen()); assertEquals("Disponible", available.state());
        assertEquals("27/09 10:40", available.expires());
        RestrictedPresentation.Received consumed = RestrictedPresentation.received("b", "PNG", "ONCE", true, false, "27/09 10:40");
        assertFalse(consumed.canOpen()); assertEquals("Ya abierto", consumed.state());
        RestrictedPresentation.Received expired = RestrictedPresentation.received("c", "AVC_MP4", "UMBRA_ONLY", false, true, "27/09 10:40");
        assertFalse(expired.canOpen()); assertEquals("Caducado", expired.state());
        assertFalse("expired rows do not advertise a future deadline", expired.detail().contains("caduca"));
        RestrictedPresentation.Received repeatable = RestrictedPresentation.received("d", "PDF_PAGES", "UMBRA_ONLY", false, false, "10:00");
        assertTrue("UMBRA_ONLY stays openable until the domain says expired", repeatable.canOpen());
    }
    @Test public void noWordingClaimsDeliveryViewingOrListening() {
        StringBuilder all = new StringBuilder(RestrictedPresentation.SENT).append(RestrictedPresentation.NOT_SENT)
            .append(RestrictedPresentation.SESSION_ENDED).append(RestrictedPresentation.CLOSURE_UNCONFIRMED);
        for (String st : new String[]{"READY", "ROUTING", "PLAYING", "COMPLETED", "INTERRUPTED", "FAILED", "CLOSED", "X"}) all.append(RestrictedPresentation.playbackLabel(st));
        for (boolean consumed : new boolean[]{false, true}) for (boolean expired : new boolean[]{false, true})
            all.append(RestrictedPresentation.received("i", "PNG", "ONCE", consumed, expired, "t").state());
        String text = all.toString().toLowerCase(Locale.ROOT);
        for (String claim : new String[]{"visto", "leído", "escuchado", "entregado", "recibido por", "borrado", "100%"}) assertFalse(claim, text.contains(claim));
        assertTrue(RestrictedPresentation.SENT.contains("No indica entrega"));
        assertEquals("Terminó", RestrictedPresentation.playbackLabel("COMPLETED"));
        assertTrue(RestrictedPresentation.playbackTerminal("COMPLETED"));
        assertFalse(RestrictedPresentation.playbackTerminal("ROUTING"));
    }
    @Test public void expiryChoicesStayInsideDomainBoundsAndAreSeparate() {
        for (long ttl : RestrictedPresentation.TTL_SECONDS) assertTrue(ttl >= 60 && ttl <= 86_400);
        for (long session : RestrictedPresentation.SESSION_SECONDS) assertTrue(session >= 1 && session <= 60);
        assertEquals(RestrictedPresentation.TTL_SECONDS.length, RestrictedPresentation.TTL_LABELS.length);
        assertEquals(RestrictedPresentation.SESSION_SECONDS.length, RestrictedPresentation.SESSION_LABELS.length);
        RestrictedPresentation.Choice c = RestrictedPresentation.defaultChoice();
        assertEquals("ONCE", c.mode());
        String summary = String.join("|", c.summary("Bruno", Kind.PHOTO).stream().map(l -> l[0] + ":" + l[1]).toList());
        assertTrue(summary.contains("Caduca en:")); assertTrue(summary.contains("Sesión:"));
    }
    @Test public void inputBoundsMatchTheDomainContract() {
        assertEquals(4 * 1024 * 1024, Kind.PHOTO.maxInputBytes());
        for (Kind k : new Kind[]{Kind.NOTE, Kind.VIDEO, Kind.PDF}) assertEquals(k.name(), 262_144, k.maxInputBytes());
        for (Kind k : Kind.values()) assertTrue(k.name(), k.mimeTypes().length > 0);
    }
}
