package app.umbra;

import app.umbra.core.Bytes;
import app.umbra.crypto.Engine;
import app.umbra.pairing.PairingService;
import org.junit.Test;
import static org.junit.Assert.*;
import org.json.JSONObject;

/** Real libsignal signatures and ciphertext; only Records is a test double. */
public class PairingTest {
    private static final class Person {
        final MemoryRecords db = new MemoryRecords(); final Engine engine = new Engine(db);
        final PairingService pairing = new PairingService(db);
        Person(String alias) throws Exception { engine.initialize(alias); }
    }
    private static void reject(Throwing operation) throws Exception {
        try { operation.run(); fail("Expected rejection"); } catch (SecurityException | IllegalArgumentException expected) { }
    }
    private interface Throwing { void run() throws Exception; }
    private static String link(Person a, Person b) throws Exception {
        String invite = a.pairing.createInvitation(3600), request = b.pairing.request(invite);
        String ack = a.pairing.accept(request); assertEquals(a.engine.id(), b.pairing.complete(ack)); return invite;
    }
    @Test public void realSignedPairingRequiresHumanVerificationAndSurvivesReopen() throws Exception {
        Person a = new Person("Synthetic A"), b = new Person("Synthetic B"); link(a,b);
        assertEquals(Engine.TrustState.UNVERIFIED, a.engine.trustState(b.engine.id()));
        reject(() -> a.engine.sendText(b.engine.id(), "synthetic", 600));
        reject(() -> a.engine.authorizeTransport(b.engine.id()));
        reject(() -> a.engine.sendFile(b.engine.id(), "fixture.txt", new byte[]{1}, 600));
        String code = Bytes.safetyCode(a.engine.id(), b.engine.id());
        assertEquals(code, Bytes.safetyCode(b.engine.id(), a.engine.id()));
        reject(() -> a.engine.verify(b.engine.id(), "0".repeat(64)));
        a.engine.verify(b.engine.id(), code); b.engine.verify(a.engine.id(), code);
        Engine reopened = new Engine(a.db);
        assertEquals(Engine.TrustState.VERIFIED, reopened.trustState(b.engine.id()));
        String id = reopened.sendText(b.engine.id(), "synthetic hello", 600);
        JSONObject envelope = reopened.get("outbox", id).getJSONObject("envelope");
        b.engine.receive(envelope);
        assertEquals("synthetic hello", b.engine.messages(a.engine.id()).get(0).getString("text"));
    }
    @Test public void oneUseAndExactTranscriptRetries() throws Exception {
        Person a = new Person("A"), b = new Person("B"), c = new Person("C");
        String invite = a.pairing.createInvitation(600), request = b.pairing.request(invite);
        assertEquals(request, new PairingService(b.db).request(invite));
        String ack = a.pairing.accept(request);
        assertEquals(ack, new PairingService(a.db).accept(request));
        reject(() -> a.pairing.accept(c.pairing.request(invite)));
        b.pairing.complete(ack); b.pairing.complete(ack);
        assertEquals(1, b.engine.contacts().size()); assertEquals(1, a.engine.contacts().size());
    }
    @Test public void revocationRejectsNewAndConsumedRequests() throws Exception {
        Person a = new Person("A"), b = new Person("B");
        String invite = a.pairing.createInvitation(600), request = b.pairing.request(invite);
        assertEquals(1, a.pairing.revokeUnused()); reject(() -> a.pairing.accept(request));
        assertEquals(0, a.pairing.revokeUnused());
        String second = a.pairing.createInvitation(600), next = b.pairing.request(second);
        a.pairing.accept(next); assertEquals(0, a.pairing.revokeUnused()); a.pairing.revoke(PairingService.invitationId(second)); reject(() -> a.pairing.accept(next));
    }
    @Test public void tamperingNoncanonicalVersionsAndWrongPeerFail() throws Exception {
        Person a = new Person("A"), b = new Person("B"), c = new Person("C");
        String invite = a.pairing.createInvitation(600);
        reject(() -> b.pairing.request(invite + "=")); reject(() -> b.pairing.request(invite + "\n"));
        reject(() -> b.pairing.request(invite.replace(":1:", ":2:")));
        reject(() -> a.pairing.request(invite));
        String request = b.pairing.request(invite), ack = a.pairing.accept(request);
        reject(() -> c.pairing.complete(ack)); reject(() -> b.pairing.complete(ack.substring(0, ack.length()-2) + "AA"));
        reject(() -> c.pairing.accept(request)); assertEquals(0, c.engine.contacts().size());
    }
    @Test public void compactQrAndBoundedInvitationCapacity() throws Exception {
        Person a = new Person("A"); String invite = a.pairing.createInvitation(600);
        assertTrue(invite.length() < 1024);
        new com.google.zxing.qrcode.QRCodeWriter().encode(invite, com.google.zxing.BarcodeFormat.QR_CODE, 512, 512);
        for(int i=1;i<32;i++) a.pairing.createInvitation(600);
        assertThrows(IllegalStateException.class, () -> a.pairing.createInvitation(600));
        reject(() -> a.pairing.createInvitation(86401));
    }
    @Test public void identityChangeBlocksDirectApisAndCannotInheritTrustByAlias() throws Exception {
        Person a = new Person("A"), b = new Person("Same alias"), replacement = new Person("Same alias"); link(a,b);
        a.engine.verify(b.engine.id(), Bytes.safetyCode(a.engine.id(), b.engine.id()));
        a.engine.sendText(b.engine.id(), "pending", 600); a.engine.identityChanged(b.engine.id());
        assertEquals(0, a.db.keys("outbox").size()); assertEquals(Engine.TrustState.IDENTITY_CHANGED, a.engine.trustState(b.engine.id()));
        a.engine.importCard(b.engine.createCard());
        reject(() -> a.engine.authorizeTransport(b.engine.id()));
        reject(() -> a.engine.sendText(b.engine.id(), "no", 600));
        reject(() -> a.engine.verify(b.engine.id(), Bytes.safetyCode(a.engine.id(), b.engine.id())));
        JSONObject newCard = replacement.engine.createCard();
        reject(() -> a.engine.confirmIdentityChange(b.engine.id(), newCard, Bytes.safetyCode(a.engine.id(), b.engine.id())));
        assertNull(a.engine.get("contact", replacement.engine.id()));
        a.engine.confirmIdentityChange(b.engine.id(), newCard, Bytes.safetyCode(a.engine.id(), replacement.engine.id()));
        assertEquals(Engine.TrustState.BLOCKED, a.engine.trustState(b.engine.id()));
        assertEquals(Engine.TrustState.VERIFIED, a.engine.trustState(replacement.engine.id()));
        a.engine.block(b.engine.id(), false); reject(() -> a.engine.sendText(b.engine.id(), "no", 600));
    }
    @Test public void numericAndQrVerificationBindBothIdentities() throws Exception {
        Person a = new Person("A"), b = new Person("B"), c = new Person("C"); link(a,b);
        String numeric = app.umbra.verification.Verification.numeric(a.engine.id(), b.engine.id());
        String qr = app.umbra.verification.Verification.qr(a.engine.id(), b.engine.id());
        assertEquals(78, numeric.length());
        assertEquals(qr, app.umbra.verification.Verification.qr(b.engine.id(), a.engine.id()));
        reject(() -> a.engine.verify(b.engine.id(), qr.replace(b.engine.id(), c.engine.id())));
        reject(() -> a.engine.verify(b.engine.id(), qr + "="));
        a.engine.verify(b.engine.id(), numeric); b.engine.verify(a.engine.id(), qr);
        assertEquals(Engine.TrustState.VERIFIED, a.engine.trustState(b.engine.id()));
    }
    @Test public void partialIdentityLossNeverReinitializesPairingVault() throws Exception {
        Person a = new Person("A"); a.pairing.createInvitation(600);
        for(String key:a.db.keys("meta")) a.db.remove("meta", key);
        assertThrows(IllegalStateException.class, a.engine::initialized);
        assertThrows(IllegalStateException.class, () -> a.engine.initialize("new"));
    }
}
