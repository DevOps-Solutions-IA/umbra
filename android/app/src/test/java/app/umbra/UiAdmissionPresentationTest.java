package app.umbra;

import app.umbra.ui.model.*;
import app.umbra.ui.model.AdmissionPresentation.Status;
import java.util.Locale;
import org.junit.Test;
import static org.junit.Assert.*;

/** Every admission state keeps its own wording; none is collapsed into a boolean or shown as contact trust. */
public class UiAdmissionPresentationTest {
    private static AdmissionPresentation of(Status s) { return AdmissionPresentation.of(s.name(), false, false); }

    @Test public void everyDomainStateHasItsOwnTitleAndOnlyAdmittedIsAdmitted() {
        java.util.Set<String> titles = new java.util.HashSet<>();
        for (Status s : Status.values()) {
            AdmissionPresentation p = of(s);
            assertEquals(s, p.status());
            assertEquals(s.name(), s == Status.ADMITTED, p.admitted());
            titles.add(p.title());
        }
        assertEquals(Status.values().length, titles.size());
        assertEquals(Status.INVALID, Status.fromEngine("AUTHORITY_MISMATCH")); // not a persisted state: fail closed
        assertEquals(Status.INVALID, Status.fromEngine(null));
    }
    @Test public void admissionNeverLooksLikeContactVerification() {
        for (Status s : Status.values()) {
            AdmissionPresentation p = of(s);
            assertNotEquals(s.name(), Tone.VERIFIED, p.tone());
            assertNotEquals(s.name(), Glyph.VERIFIED, p.glyph());
            assertFalse(s.name(), p.title().toLowerCase(Locale.ROOT).contains("verificad"));
        }
        assertEquals("No verifica contactos ni conecta.", of(Status.ADMITTED).body());
        assertTrue(Help.ADMISSION.lines.contains("Estar admitido no verifica contactos."));
    }
    @Test public void realmConfigurationDoesNotImplyAdmission() {
        AdmissionPresentation configured = of(Status.NOT_ADMITTED);
        assertFalse(configured.admitted());
        assertEquals("Acceso pendiente", configured.title());
        assertEquals("Configurada. Solicita acceso para conectarte.", configured.body());
        assertTrue("configuring never admits", of(Status.UNCONFIGURED).body().contains("Configurar no da acceso"));
        assertEquals("Configuración pendiente", of(Status.UNCONFIGURED).title());
    }
    @Test public void pendingRequestIsGeneratedNotReceived() {
        AdmissionPresentation pending = of(Status.REQUEST_PENDING);
        assertEquals("Solicitando acceso", pending.title());
        assertEquals("Generada · no recibida.", pending.body());
        assertTrue(Help.ADMISSION.lines.contains("Nada se envía solo: se comparten archivos."));
        assertTrue(pending.canImportDecision());
        assertTrue(pending.canExportRequest());
        assertFalse("no local approval", pending.canCreateRequest());
    }
    @Test public void rejectedExpiredRevokedAndInvalidAreDistinct() {
        assertEquals(Tone.DANGER, of(Status.REJECTED).tone());
        assertTrue(of(Status.REJECTED).canCreateRequest());
        AdmissionPresentation requestExpired = AdmissionPresentation.of("EXPIRED", true, true);
        AdmissionPresentation credentialExpired = AdmissionPresentation.of("EXPIRED", false, false);
        assertNotEquals(requestExpired.title(), credentialExpired.title());
        assertTrue(credentialExpired.body().contains("renovación autorizada"));
        assertTrue(credentialExpired.body().contains("no se renueva sola"));
        AdmissionPresentation revoked = of(Status.REVOKED);
        assertEquals(Tone.BLOCKED, revoked.tone());
        assertFalse("no bypass", revoked.canCreateRequest() || revoked.canImportDecision() || revoked.canImportRealm());
        assertTrue(revoked.body().contains("no se borraron tus datos"));
        AdmissionPresentation invalid = of(Status.INVALID);
        assertFalse(invalid.canCreateRequest() || invalid.canImportRealm() || invalid.canImportDecision());
        assertTrue(invalid.body().contains("no los repara"));
        assertEquals("Sin lectura", AdmissionPresentation.unreadable().title());
        assertFalse(AdmissionPresentation.notRead().admitted());
    }
    @Test public void securityCopyAvoidsAbsoluteClaims() {
        StringBuilder all = new StringBuilder(AdmissionPresentation.OFFLINE_REVOCATION_NOTE);
        for (Status s : Status.values()) all.append(of(s).title()).append(of(s).body());
        String text = all.toString().toLowerCase(Locale.ROOT);
        for (String banned : new String[]{"100%", "imposible", "anonimato total", "borrado remoto", "datos eliminados"})
            assertFalse(banned, text.contains(banned));
        assertTrue(AdmissionPresentation.OFFLINE_REVOCATION_NOTE.contains("no borra"));
        assertTrue(AdmissionPresentation.REALM_IMPORT_REJECTED.contains("se conserva la actual"));
        assertTrue(Help.ADMISSION.lines.contains("Otra autoridad se rechaza y se conserva la anterior."));
    }
    @Test public void importDispatchIsByDocumentedPrefixOnly() {
        assertEquals(AdmissionImport.Kind.REALM, AdmissionImport.classify("umbra:realm:1:abc:def:0123\n").kind());
        assertEquals(AdmissionImport.Kind.REQUEST, AdmissionImport.classify("umbra:admission:request:1:AAAA.BBBB").kind());
        assertEquals(AdmissionImport.Kind.CREDENTIAL, AdmissionImport.classify("umbra:admission:credential:1:AAAA.BBBB\n").kind());
        assertEquals(AdmissionImport.Kind.REJECTION, AdmissionImport.classify("umbra:admission:rejection:1:AAAA.BBBB").kind());
        assertEquals(AdmissionImport.Kind.REVOCATION, AdmissionImport.classify("umbra:admission:revocation:1:AAAA.BBBB").kind());
        assertEquals(AdmissionImport.Kind.RENEWAL_REQUEST,
            AdmissionImport.classify(AdmissionImport.file("umbra:admission:request:1:A.B", "umbra:admission:credential:1:C.D")).kind());
        assertEquals(AdmissionImport.Kind.RENEWAL_RESULT,
            AdmissionImport.classify(AdmissionImport.file("umbra:admission:credential:1:A.B", "umbra:admission:revocation:1:C.D")).kind());
    }
    @Test public void contactInvitationsAndMalformedFilesAreNotAdmissionObjects() {
        for (String text : new String[]{"umbra:invite:AAAA", "umbra:request:AAAA", "umbra:ack:AAAA", "", "\n", null,
                "umbra:admission:credential:1:A.B\r\n", " umbra:admission:credential:1:A.B",
                "umbra:admission:credential:1:A.B\numbra:admission:credential:1:C.D",
                "umbra:admission:request:1:A\numbra:admission:credential:1:B\numbra:admission:revocation:1:C",
                "umbra:admission:credential:1:" + "A".repeat(AdmissionImport.MAX_WIRE)})
            assertEquals(String.valueOf(text), AdmissionImport.Kind.UNKNOWN, AdmissionImport.classify(text).kind());
        assertThrows(IllegalArgumentException.class, () -> AdmissionImport.file("a", "b", "c"));
    }
    @Test public void fingerprintGroupingIsDisplayOnly() {
        assertEquals("0123 4567 89ab", AdmissionImport.group("0123456789abcdef", 3));
        assertEquals("", AdmissionImport.group(null, 3));
    }
}
