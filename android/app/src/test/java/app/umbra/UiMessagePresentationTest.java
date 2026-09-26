package app.umbra;

import app.umbra.ui.model.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Message, conversation and fingerprint presentation helpers. */
public class UiMessagePresentationTest {
    @Test public void deliveryMirrorsEngineStatus() {
        assertEquals("Pendiente", MessageItem.Delivery.ofEngine("Pendiente").label());
        assertEquals("Entregado", MessageItem.Delivery.ofEngine("Entregado").label());
        assertEquals(Tone.SUCCESS, MessageItem.Delivery.ofEngine("Entregado").tone());
        assertEquals("Enviado por Bluetooth", MessageItem.Delivery.ofEngine("Enlace Bluetooth").label());
        assertEquals(Glyph.RETRY, MessageItem.Delivery.failed().glyph());
    }
    @Test public void filesAndImagesAreDistinguished() {
        assertEquals(MessageItem.Kind.IMAGE, MessageItem.file("1", true, "Foto.JPG", 2048, "10:00", "Pendiente", null).kind());
        assertEquals(MessageItem.Kind.FILE, MessageItem.file("2", true, "Documento.pdf", 2048, "10:00", "Pendiente", null).kind());
        assertEquals("2 KB", MessageItem.size(2048));
        assertEquals("512 B", MessageItem.size(512));
        assertEquals("1.5 MB", MessageItem.size(1024 * 1536));
    }
    @Test public void fingerprintsAreGroupedAndSpokenCharacterByCharacter() {
        assertEquals("ABCD EFGH IJ", Fingerprints.group("ABCDEFGHIJ"));
        assertEquals("ABCD EFGH\nIJKL", Fingerprints.lines("ABCDEFGHIJKL", 2));
        assertEquals("A B C D, E F", Fingerprints.spoken("ABCD EF"));
        assertEquals("ABCDEF12", Fingerprints.shortId("abcdef1234567890"));
        assertEquals("—", Fingerprints.shortId(null));
        assertEquals("A", Fingerprints.initial(" ana"));
        assertEquals("?", Fingerprints.initial(""));
    }
    @Test public void groupRowsDescribeMembers() {
        ConversationItem g = ConversationItem.group("g", "Equipo Operaciones", 5, "");
        assertTrue(g.group());
        assertEquals("5 miembros", g.subtitle());
        assertTrue(g.accessibilityLabel().startsWith("Grupo Equipo Operaciones"));
    }
}
