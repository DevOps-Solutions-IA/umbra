package app.umbra;

import app.umbra.core.Bytes;
import app.umbra.crypto.Engine;
import app.umbra.crypto.SignalStore;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

/** Regression tests use real libsignal signatures/sessions, with synthetic in-memory records. */
public class AuditRegressionTest {
    private MemoryRecords aliceRecords, bobRecords;
    private Engine alice, bob;

    @Before public void setup() throws Exception {
        aliceRecords = new MemoryRecords(); bobRecords = new MemoryRecords();
        alice = new Engine(aliceRecords); bob = new Engine(bobRecords);
        alice.initialize("Alice audit"); bob.initialize("Bob audit");
        alice.importCard(bob.createCard()); bob.importCard(alice.createCard());
        String code = Bytes.safetyCode(alice.id(), bob.id());
        alice.verify(bob.id(), code); bob.verify(alice.id(), code);
    }

    private JSONObject signedBody(JSONObject body) throws Exception {
        byte[] raw = Bytes.utf8(body.toString());
        byte[] signature = new SignalStore(bobRecords).getIdentityKeyPair().getPrivateKey().calculateSignature(raw);
        return new JSONObject().put("format", "umbra-contact-v1").put("body", Bytes.b64(raw))
            .put("signature", Bytes.b64(signature));
    }

    private JSONObject body() throws Exception {
        return new JSONObject(Bytes.text(Bytes.unb64(bob.createCard().getString("body"))));
    }

    private void rejectedWithoutReplacingContact(JSONObject body) throws Exception {
        String previous = alice.contact(bob.id()).toString();
        JSONObject malformed = signedBody(body);
        assertThrows(SecurityException.class, () -> alice.importCard(malformed));
        assertEquals(previous, alice.contact(bob.id()).toString());
        assertTrue(alice.contact(bob.id()).getBoolean("verified"));
    }

    @Test public void signedVersionCannotWrapToVersionOne() throws Exception {
        for (long value : new long[]{4294967297L, -4294967295L})
            rejectedWithoutReplacingContact(body().put("v", value));
    }

    @Test public void signedRegistrationCannotWrapToValidInt() throws Exception {
        JSONObject body = body();
        rejectedWithoutReplacingContact(body.put("registration", body.getLong("registration") + (1L << 32)));
    }

    @Test public void signedKeyIdCannotWrapToValidInt() throws Exception {
        JSONObject body = body();
        rejectedWithoutReplacingContact(body.put("keyId", body.getLong("keyId") + (1L << 32)));
    }

    @Test public void signedKeyIdBoundsAreCheckedBeforeConversion() throws Exception {
        for (long value : new long[]{0, -1, (long) Integer.MAX_VALUE + 1, Long.MAX_VALUE})
            rejectedWithoutReplacingContact(body().put("keyId", value));
    }

    @Test public void signedInvitationMustExpireAfterCreation() throws Exception {
        long now = Bytes.now();
        rejectedWithoutReplacingContact(body().put("created", now + 120).put("expires", now + 60));
    }

    @Test public void signedWriteCapabilityMustHaveCanonicalEncoding() throws Exception {
        JSONObject body = body();
        String token = body.getString("write"), alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
        String noncanonical = token.substring(0, 42) + alphabet.charAt(alphabet.indexOf(token.charAt(42)) + 1);
        rejectedWithoutReplacingContact(body.put("write", noncanonical));
    }

    @Test public void delayedInitialMessageSurvivesInvitationExpirySweep() throws Exception {
        alice.sendText(bob.id(), "synthetic delayed initial message", 3600);
        JSONObject envelope = alice.outbox().get(0).getJSONObject("envelope");
        // Move only retirement metadata to the expired-invitation boundary; no real keys or clocks.
        for (String key : bobRecords.keys("key-expiry"))
            bobRecords.put("key-expiry", key, Bytes.utf8(Long.toString(Bytes.now() - 1)));
        bob.expire();
        bob.receive(envelope);
        assertEquals("synthetic delayed initial message", bob.messages(alice.id()).get(0).getString("text"));
    }

    @Test public void expiredPrekeysAreErasedAfterBoundedTransitWindow() throws Exception {
        List<String> ids = bobRecords.keys("key-expiry");
        assertFalse(ids.isEmpty());
        for (String key : ids)
            bobRecords.put("key-expiry", key, Bytes.utf8(Long.toString(Bytes.now() - Engine.MAX_TTL)));
        bob.expire();
        for (String key : ids) for (String bucket : new String[]{"key-expiry", "prekey", "signed", "kyber"})
            assertNull(bobRecords.get(bucket, key));
    }

    @Test public void consumedOneTimeKeysStillDisappearImmediately() throws Exception {
        List<String> ids = bobRecords.keys("key-expiry");
        assertEquals(1, ids.size()); String key = ids.get(0);
        assertNotNull(bobRecords.get("prekey", key)); assertNotNull(bobRecords.get("kyber", key));
        alice.sendText(bob.id(), "synthetic consumption", 3600);
        bob.receive(alice.outbox().get(0).getJSONObject("envelope"));
        assertNull(bobRecords.get("prekey", key)); assertNull(bobRecords.get("kyber", key));
    }
}
