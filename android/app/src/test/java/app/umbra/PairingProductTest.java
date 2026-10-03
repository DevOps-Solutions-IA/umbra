package app.umbra;

import app.umbra.core.Bytes;
import app.umbra.crypto.Engine;
import app.umbra.pairing.*;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real protocol signatures and QR/AEAD codecs with test-only in-memory Records.
 * These are not Android SQLite, process-death, network or Bluetooth tests. */
public final class PairingProductTest {
    private static void safeEqual(Object expected,Object actual) {
        assertTrue("Synthetic pairing invariant mismatch (values redacted)",java.util.Objects.deepEquals(expected,actual));
    }

    private static final class Person {
        final MemoryRecords db = new MemoryRecords();
        final Engine engine = new Engine(db);
        final PairingProduct product = new PairingProduct(db);
        Person(String alias) throws Exception { engine.initialize(alias); AdmissionFixture.enroll(engine); }
    }

    @Test public void fileFlowSnapshotsKeepSecretsOutAndRequireHumanVerification() throws Exception {
        Person a = new Person("A"), b = new Person("B");
        var invitation = a.product.createPairing();
        String invite = invitation.delivery().payload(), id = invitation.snapshot().id();
        safeEqual(PairingSnapshot.Phase.INVITE_CREATED, invitation.snapshot().phase());
        safeEqual(invite,new PairingProduct(a.db).resumeFile(id).delivery().payload());
        var request = b.product.importFile(invite);
        safeEqual(PairingSnapshot.Phase.REQUEST_CREATED, request.snapshot().phase());
        safeEqual(request.delivery().payload(),new PairingProduct(b.db).resumeFile(id).delivery().payload());
        safeEqual(request.delivery().payload(), new PairingProduct(b.db).importFile(invite).delivery().payload());
        var ack = a.product.importFile(request.delivery().payload());
        safeEqual(PairingSnapshot.Phase.ACK_CREATED, ack.snapshot().phase());
        safeEqual(b.engine.id(),ack.snapshot().peerId());
        safeEqual(ack.delivery().payload(),new PairingProduct(a.db).resumeFile(id).delivery().payload());
        safeEqual(ack.delivery().payload(), new PairingProduct(a.db).importFile(request.delivery().payload()).delivery().payload());
        var complete = b.product.importFile(ack.delivery().payload());
        safeEqual(PairingSnapshot.Phase.COMPLETE, complete.snapshot().phase());
        assertNull(complete.delivery()); assertNull(new PairingProduct(b.db).resumeFile(id).delivery()); assertTrue(complete.snapshot().verificationRequired());
        safeEqual(a.engine.id(), complete.snapshot().peerId());
        safeEqual(PairingSnapshot.Phase.COMPLETE, new PairingProduct(b.db).pairingStatus(id).phase());
        safeEqual(Engine.TrustState.UNVERIFIED, a.engine.trustState(b.engine.id()));
        safeEqual(Engine.TrustState.UNVERIFIED, b.engine.trustState(a.engine.id()));
        assertThrows(SecurityException.class, () -> a.engine.sendText(b.engine.id(), "synthetic denied", 600));
        String diagnostic = invitation + " " + request + " " + ack + " " + complete;
        for (String secret : new String[]{invite, request.delivery().payload(), ack.delivery().payload(),
                PairingService.consumeToken(invite), a.engine.get("pairing-issued", id).getString("revoke")})
            assertFalse(diagnostic.contains(secret));
        safeEqual(1, a.engine.contacts().size()); safeEqual(1, b.engine.contacts().size());
    }

    @Test public void qrDecodesRenderedPixelsButRejectsSafetyQrAndOversizedFrames() throws Exception {
        Person a = new Person("A"), b = new Person("B");
        String invite = a.product.createPairing().delivery().payload();
        var matrix = PairingQrCodec.render(invite, 512);
        byte[] plane = new byte[matrix.getWidth() * matrix.getHeight()];
        for (int y = 0; y < matrix.getHeight(); y++) for (int x = 0; x < matrix.getWidth(); x++)
            plane[y * matrix.getWidth() + x] = matrix.get(x, y) ? 0 : (byte) 255;
        safeEqual(invite, PairingQrCodec.decodeLuminance(plane, matrix.getWidth(), matrix.getHeight()));
        assertThrows(PairingException.class, () -> PairingQrCodec.decode(
            app.umbra.verification.Verification.qr(a.engine.id(), b.engine.id())));
        assertThrows(PairingException.class, () -> PairingQrCodec.decode("x".repeat(1025)));
        assertThrows(PairingException.class, () -> PairingQrCodec.decodeLuminance(new byte[1], Integer.MAX_VALUE, Integer.MAX_VALUE));
        assertThrows(PairingException.class, () -> PairingQrCodec.decodeLuminance(new byte[10], 2, 2));
        assertThrows(PairingException.class, () -> PairingQrCodec.render(invite, 2049));
    }

    @Test public void boundedQrParserFuzzRejectsMalformedInputWithoutCreatingState() throws Exception {
        Person a = new Person("A"); Random random = new Random(0x50414952L);
        for (int sample = 0; sample < 100; sample++) {
            byte[] raw = new byte[random.nextInt(1025)]; random.nextBytes(raw);
            String input = "invalid:" + Base64.getEncoder().encodeToString(raw);
            assertThrows(PairingException.class, () -> PairingQrCodec.decode(input));
        }
        for (String input : new String[]{"", "umbra:invite:1:", "umbra:invite:1:...", "umbra:invite:2:AA.AA", "\u0000", "umbra:invite:1:é.é"})
            assertThrows(PairingException.class, () -> PairingQrCodec.decode(input));
        assertTrue(a.db.keys("pairing-issued").isEmpty()); assertTrue(a.db.keys("pairing-pending").isEmpty());
        String invite = a.product.createPairing().delivery().payload();
        int separator = invite.lastIndexOf('.');
        byte[] signature = Base64.getUrlDecoder().decode(invite.substring(separator + 1));
        for (int index = 0; index < signature.length; index++) {
            byte[] altered = signature.clone(); altered[index] ^= 1;
            String input = invite.substring(0, separator + 1)
                + Base64.getUrlEncoder().withoutPadding().encodeToString(altered);
            assertThrows(PairingException.class, () -> PairingQrCodec.decode(input));
        }
        safeEqual(1, a.db.keys("pairing-issued").size()); assertTrue(a.db.keys("pairing-pending").isEmpty());
    }

    @Test public void humanCodesHaveSixteenBase32SymbolsAndCanonicalEquivalentInputs() {
        safeEqual(80, PairingSecrets.CODE_BITS);
        HashSet<String> observed = new HashSet<>();
        for (int i = 0; i < 128; i++) {
            char[] code = PairingSecrets.newCode();
            try {
                String display = new String(code);
                assertTrue(display.matches("[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{4}(-[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{4}){3}"));
                assertTrue(observed.add(display));
                byte[] normalized = PairingSecrets.normalize(code);
                try { safeEqual(16, normalized.length); }
                finally { Arrays.fill(normalized, (byte) 0); }
                char[] equivalent = display.toLowerCase(java.util.Locale.ROOT).replace('-', ' ').toCharArray();
                try {
                    safeEqual(PairingSecrets.codeLocator(code), PairingSecrets.codeLocator(equivalent));
                    safeEqual(PairingSecrets.codeCapability(code), PairingSecrets.codeCapability(equivalent));
                    assertNotEquals(PairingSecrets.codeLocator(code), PairingSecrets.codeCapability(code));
                } finally { Arrays.fill(equivalent, '\0'); }
            } finally { Arrays.fill(code, '\0'); }
        }
        // Shape and uniqueness smoke checks do not statistically certify the entropy source.
        for (String invalid : new String[]{"", "2".repeat(15), "2".repeat(17), "O".repeat(16), "I".repeat(16), "0".repeat(16), "2".repeat(16) + "\n"})
            assertThrows(PairingException.class, () -> PairingSecrets.normalize(invalid.toCharArray()));
    }

    private static String changeHeader(String blob, int field, String value) {
        String[] parts = Bytes.text(Base64.getDecoder().decode(blob)).split("\n", -1);
        parts[field] = value;
        return Bytes.b64(Bytes.utf8(String.join("\n", parts)));
    }

    @Test public void courierAeadBindsInvitationDirectionHeaderAndCiphertext() throws Exception {
        Person a = new Person("A"), b = new Person("B");
        String invite = a.product.createPairing().delivery().payload();
        String other = a.product.createPairing().delivery().payload();
        String request = b.product.importFile(invite).delivery().payload();
        String blob = PairingSecrets.sealTranscript(PairingSecrets.Direction.REQUEST, invite, request);
        safeEqual(request, PairingSecrets.openTranscript(PairingSecrets.Direction.REQUEST, invite, blob));
        assertThrows(PairingException.class, () -> PairingSecrets.openTranscript(PairingSecrets.Direction.ACK, invite, blob));
        assertThrows(PairingException.class, () -> PairingSecrets.openTranscript(PairingSecrets.Direction.REQUEST, other, blob));
        for (String tampered : new String[]{changeHeader(blob, 1, "ACK"), changeHeader(blob, 2, Bytes.token()),
                changeHeader(blob, 3, "0".repeat(64)), changeHeader(blob, 4, "1"), changeHeader(blob, 5, "A".repeat(16))})
            assertThrows(PairingException.class, () -> PairingSecrets.openTranscript(PairingSecrets.Direction.REQUEST, invite, tampered));
        String[] parts = Bytes.text(Base64.getDecoder().decode(blob)).split("\n", -1);
        byte[] ciphertext = Base64.getUrlDecoder().decode(parts[6]); ciphertext[ciphertext.length - 1] ^= 1;
        String altered = changeHeader(blob, 6, Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext));
        assertThrows(PairingException.class, () -> PairingSecrets.openTranscript(PairingSecrets.Direction.REQUEST, invite, altered));
        char[] code = PairingSecrets.newCode(), wrong = PairingSecrets.newCode();
        try {
            String sealed = PairingSecrets.sealInvite(code, invite);
            safeEqual(invite, PairingSecrets.openInvite(code, sealed));
            assertThrows(PairingException.class, () -> PairingSecrets.openInvite(wrong, sealed));
            assertThrows(PairingException.class, () -> PairingSecrets.openInvite(code, changeHeader(sealed, 3, "0".repeat(64))));
        } finally { Arrays.fill(code, '\0'); Arrays.fill(wrong, '\0'); }
    }

    @Test public void cancellationPersistsAndDirectCoreCannotResumeJoiner() throws Exception {
        Person a = new Person("A"), b = new Person("B");
        var invitation = a.product.createPairing(); String invite = invitation.delivery().payload();
        var request = b.product.importFile(invite);
        String ack = a.product.importFile(request.delivery().payload()).delivery().payload();
        b.product.cancelPairing(invitation.snapshot().id());
        safeEqual(PairingSnapshot.Phase.CANCELLED, new PairingProduct(b.db).pairingStatus(invitation.snapshot().id()).phase());
        assertThrows(PairingException.class, () -> b.product.importFile(invite));
        assertThrows(PairingException.class, () -> b.product.importFile(ack));
        assertThrows(SecurityException.class, () -> new PairingService(b.db).request(invite));
        assertThrows(SecurityException.class, () -> new PairingService(b.db).complete(ack));
        assertTrue(b.engine.contacts().isEmpty());
        a.product.cancelPairing(invitation.snapshot().id());
        assertThrows(SecurityException.class, () -> new PairingService(a.db).accept(request.delivery().payload()));
    }

    @Test public void tamperedAckCourierCiphertextCannotCompletePendingPairing() throws Exception {
        Person a = new Person("A"), b = new Person("B");
        var offered = a.product.createPairing();
        String invite = offered.delivery().payload(), id = offered.snapshot().id();
        String request = b.product.importFile(invite).delivery().payload();
        String ack = a.product.importFile(request).delivery().payload();
        String sealed = PairingSecrets.sealTranscript(PairingSecrets.Direction.ACK, invite, ack);
        safeEqual(ack, PairingSecrets.openTranscript(PairingSecrets.Direction.ACK, invite, sealed));
        byte[] before = b.db.get("pairing-pending", id);
        String[] fields = Bytes.text(Base64.getDecoder().decode(sealed)).split("\n", -1);
        byte[] ciphertext = Base64.getUrlDecoder().decode(fields[6]); ciphertext[0] ^= 1;
        String altered = changeHeader(sealed, 6, Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext));
        safeEqual(PairingException.Code.INVALID_FORMAT, assertThrows(PairingException.class, () ->
            b.product.importFile(PairingSecrets.openTranscript(PairingSecrets.Direction.ACK, invite, altered))).code());
        safeEqual(before, b.db.get("pairing-pending", id));
        assertTrue(b.engine.contacts().isEmpty());
        safeEqual(PairingSnapshot.Phase.COMPLETE, b.product.importFile(
            PairingSecrets.openTranscript(PairingSecrets.Direction.ACK, invite, sealed)).snapshot().phase());
    }

    @Test public void locallyFailedPublicationRetainsOneRecoverableCodeWithoutSnapshotLeak() throws Exception {
        Person a = new Person("A");
        // Deliberately absent courier injects a local failure before any network I/O.
        // This does not establish HTTP retry behavior or encrypted Android storage.
        safeEqual(PairingException.Code.UNAVAILABLE,
            assertThrows(PairingException.class, () -> a.product.createHumanCode(null)).code());
        var states = a.product.pendingPairings(); safeEqual(1, states.size());
        String id = states.get(0).id();
        byte[] stored = a.db.get("pairing-product", id);
        try (var recovered = new PairingProduct(a.db).humanCode(id)) {
            char[] display = recovered.display();
            try {
                safeEqual(19, display.length);
                safeEqual(PairingException.Code.SELF_PAIRING,
                    assertThrows(PairingException.class,()->a.product.acceptHumanCode(display,null)).code());
                assertTrue(a.db.keys("pairing-code-claims").isEmpty());
                assertFalse(states.toString().contains(new String(display)));
                assertFalse(recovered.toString().contains(new String(display)));
                safeEqual(PairingException.Code.UNAVAILABLE,
                    assertThrows(PairingException.class, () -> a.product.retryPairing(id, null)).code());
                safeEqual(stored, a.db.get("pairing-product", id));
                try (var again = new PairingProduct(a.db).humanCode(id)) {
                    char[] repeated = again.display();
                    try { safeEqual(display, repeated); }
                    finally { Arrays.fill(repeated, '\0'); }
                }
                safeEqual(1, a.product.pendingPairings().size());
            } finally { Arrays.fill(display, '\0'); }
            recovered.close(); safeEqual(new char[19], recovered.display());
        }
        a.product.cancelPairing(id);
        safeEqual(PairingException.Code.CANCELLED,
            assertThrows(PairingException.class, () -> a.product.humanCode(id)).code());
    }
}
