package app.umbra;

import app.umbra.ui.model.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Clean-style copy contract: every string the presentation layer can put on screen is Spanish (no English
 * word from {@link SpanishText#ENGLISH}), titles and chips are short, and one-line notices stay one line.
 * Longer explanations belong in {@link Help} sheets, which are held to their own per-line limit.
 */
public class UiCopyTest {
    static final int MAX_TITLE = 32, MAX_LINE = 64, MAX_HELP_LINE = 72;

    private static void check(List<String> out, String text, int max) {
        if (text == null || text.isEmpty()) return;
        String english = SpanishText.englishWord(text);
        if (english != null) out.add("English '" + english + "': " + text);
        if (text.length() > max) out.add("Too long (" + text.length() + " > " + max + "): " + text);
    }
    private static void assertClean(List<String> problems) { assertTrue(String.join("\n", problems), problems.isEmpty()); }

    @Test public void spanishTextDetectsEnglishWordsOnly() {
        assertEquals("vault", SpanishText.englishWord("Vault records unreadable"));
        assertEquals("nearby", SpanishText.englishWord("Nearby activo"));
        assertNull(SpanishText.englishWord("Bóveda bloqueada · Sin conexión"));
        assertNull("brand and transport names are allowed", SpanishText.englishWord("UMBRA por Bluetooth en Android"));
        assertNull("Spanish 'error' is not flagged", SpanishText.englishWord("Error de lectura"));
        assertFalse(SpanishText.isSpanish(null));
        assertFalse(SpanishText.isSpanish("  "));
        assertFalse(SpanishText.isSpanish("Relay unavailable"));
        assertTrue(SpanishText.isSpanish("Servidor sin respuesta"));
    }

    @Test public void presentationCopyIsSpanishAndShort() {
        List<String> p = new ArrayList<>();
        for (TrustLevel l : TrustLevel.values()) {
            TrustPresentation t = TrustPresentation.of(l);
            check(p, t.label(), MAX_TITLE); check(p, t.headline(), MAX_TITLE); check(p, t.primaryAction(), MAX_TITLE);
            check(p, t.explanation(), MAX_LINE); check(p, t.blockedReason(), MAX_LINE);
        }
        for (AdmissionPresentation.Status s : AdmissionPresentation.Status.values())
            for (boolean request : new boolean[]{false, true}) {
                AdmissionPresentation a = AdmissionPresentation.of(s.name(), request, request);
                check(p, a.title(), MAX_TITLE); check(p, a.body(), MAX_LINE);
            }
        check(p, AdmissionPresentation.unreadable().title(), MAX_TITLE);
        check(p, AdmissionPresentation.IMPORT_REJECTED, MAX_LINE);
        check(p, AdmissionPresentation.REALM_IMPORT_REJECTED, MAX_LINE);
        check(p, AdmissionPresentation.OFFLINE_REVOCATION_NOTE, MAX_LINE);
        for (AdmissionImport.Kind k : AdmissionImport.Kind.values()) check(p, AdmissionImport.describe(k), MAX_TITLE);
        for (ConnectivityPresentation.State s : ConnectivityPresentation.State.values())
            for (ConnectivityPresentation.Service svc : ConnectivityPresentation.Service.values())
                for (boolean offline : new boolean[]{false, true})
                    for (boolean flag : new boolean[]{false, true}) {
                        ConnectivityPresentation c = ConnectivityPresentation.of(s.name(), offline, flag, flag, !flag, svc);
                        check(p, c.chip(), MAX_TITLE); check(p, c.title(), MAX_TITLE); check(p, c.body(), MAX_LINE);
                        check(p, c.nearby(), MAX_TITLE); check(p, c.service(), MAX_TITLE);
                    }
        for (ErrorKind k : ErrorKind.values()) {
            ErrorPresentation e = ErrorPresentation.of(k);
            check(p, e.title(), MAX_TITLE); check(p, e.body(), MAX_LINE);
        }
        String[] signaling = {null, "INCOMING", "OUTGOING", "ACCEPTING", "SELECTED", "NEGOTIATING", "REJECTED", "BUSY", "EXPIRED", "CANCELLED", "ENDED", "FAILED", "NOT_SELECTED"};
        String[] media = {null, "NEGOTIATING", "ACTIVE", "ENDED", "FAILED"};
        for (String s : signaling) for (String m : media) {
            CallPresentation c = CallPresentation.of(s, m);
            check(p, c.phase(), MAX_TITLE); check(p, c.detail(), MAX_LINE);
        }
        for (ModulatorPresentation.EngineState s : ModulatorPresentation.EngineState.values())
            for (boolean muted : new boolean[]{false, true}) {
                ModulatorPresentation m = ModulatorPresentation.of(s.name(), muted);
                check(p, m.headline(), MAX_TITLE); check(p, m.detail(), MAX_LINE); check(p, m.transmission(), MAX_TITLE);
            }
        check(p, ModulatorPresentation.NATURAL_CONFIRM_TITLE, MAX_TITLE);
        check(p, ModulatorPresentation.NATURAL_CONFIRM_BODY, MAX_LINE);
        check(p, ModulatorPresentation.NATURAL_CONFIRM_ACTION, MAX_TITLE);
        check(p, ModulatorPresentation.DISCLAIMER, MAX_LINE);
        long now = 10_000_000_000L;
        for (String v : new String[]{"OFF", "ACTIVE", "WAITING_FOR_FRAME", "STALE", "NEGOTIATING", "CAMERA_UNAVAILABLE"}) {
            VideoPresentation vp = VideoPresentation.of(v, now, now - 1_000L, 0);
            check(p, vp.remote(), MAX_TITLE); check(p, vp.camera(), MAX_TITLE);
        }
        check(p, VideoPresentation.requestHeadline("Bruno"), MAX_TITLE);
        for (String c : new String[]{VideoPresentation.CONSENT_REJECT, VideoPresentation.CONSENT_RECEIVE, VideoPresentation.CONSENT_SHARE}) check(p, c, MAX_TITLE);
        for (AccessStep s : AccessStep.values()) { check(p, s.title, MAX_TITLE); check(p, s.body, MAX_LINE); }
        for (String s : new String[]{AccessStep.LOCKED_AFTER_CREATE, AccessStep.LOCKED_AFTER_CHANGE, AccessStep.UNLOCK_FAILED, AccessStep.RECORDS_UNREADABLE}) check(p, s, MAX_LINE);
        for (PasswordPolicy.Problem pr : PasswordPolicy.Problem.values()) check(p, pr.message, MAX_LINE);
        for (FeatureAvailability.Status s : FeatureAvailability.Status.values()) check(p, FeatureAvailability.label(s), MAX_TITLE);
        for (Feature f : Feature.values()) check(p, f.title, MAX_TITLE);
        for (SettingsSection s : SettingsSection.values()) { check(p, s.title, MAX_TITLE); check(p, s.subtitle, MAX_LINE); }
        for (HomeTab t : HomeTab.values()) check(p, t.label, 12);
        for (LocationShareDraft.Precision pr : LocationShareDraft.Precision.values()) { check(p, pr.label, MAX_TITLE); check(p, pr.detail, MAX_LINE); }
        for (String[] line : LocationShareDraft.single(LocationShareDraft.Precision.ZONE).summary("Bruno", 2)) { check(p, line[0], MAX_TITLE); check(p, line[1], MAX_LINE); }
        for (TrustLevel l : TrustLevel.values()) check(p, ConversationItem.direct("x", "Ana", l, "").subtitle(), MAX_TITLE);
        assertClean(p);
    }

    @Test public void helpSheetsAreShortPlainLines() {
        List<String> p = new ArrayList<>();
        for (Help h : Help.values()) {
            check(p, h.title, MAX_TITLE);
            assertFalse(h.name() + " has lines", h.lines.isEmpty());
            assertTrue(h.name() + " stays a short sheet", h.lines.size() <= 6);
            for (String line : h.lines) check(p, line, MAX_HELP_LINE);
        }
        assertClean(p);
    }

    @Test public void transportAndLocationStatusNeverReachTheScreenVerbatimInEnglish() {
        for (String s : new String[]{"Bluetooth: enlace cerrado por tiempo límite", "Bluetooth: esperando vinculación explícita",
                "Bluetooth: esperando contacto verificado", "Bluetooth: escucha finalizada", "Bluetooth: conexión fallida",
                "Clave del dispositivo comprobada · verifica el código del contacto", "Bluetooth: contacto verificado conectado",
                "Bluetooth: enlace cerrado o paquete no aceptado", "Nearby session unavailable", null}) {
            String shown = ShortStatus.transport(s);
            assertTrue(shown, SpanishText.isSpanish(shown));
            assertTrue(shown, shown.length() <= ShortStatus.MAX);
        }
        for (String s : new String[]{"Ubicación activa; entrega sujeta a conexión", "Punto cifrado en cola",
                "Captura visible activa; esperando medición", "Ubicación interrumpida; requiere nuevo consentimiento", "Location denied", null}) {
            String shown = ShortStatus.location(s);
            assertTrue(shown, SpanishText.isSpanish(shown));
            assertTrue(shown, shown.length() <= ShortStatus.MAX);
        }
        assertEquals("Esperando contacto", ShortStatus.transport("Bluetooth: esperando contacto verificado"));
        assertEquals("Cercanía", ShortStatus.transport("Nearby session unavailable"));
    }
}
