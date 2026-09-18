package app.umbra;

import app.umbra.crypto.Engine;
import app.umbra.core.Bytes;
import org.json.JSONObject;
import org.junit.*;
import static org.junit.Assert.*;
import java.util.*;

/** Must run against REAL libsignal JNI. These tests were included, but not run in the authoring environment. */
public class SignalIntegrationTest {
    private MemoryRecords aliceRecords, bobRecords;
    private Engine alice, bob;
    @Before public void setup() throws Exception {
        aliceRecords = new MemoryRecords(); bobRecords = new MemoryRecords();
        alice = new Engine(aliceRecords); bob = new Engine(bobRecords);
        alice.initialize("Alice"); bob.initialize("Bob");
        alice.importCard(bob.createCard()); bob.importCard(alice.createCard());
        verifyBoth();
    }
    private void verifyBoth() throws Exception {
        String code = Bytes.safetyCode(alice.id(), bob.id()); alice.verify(bob.id(), code); bob.verify(alice.id(), code);
    }
    private JSONObject first(Engine engine) throws Exception { return engine.outbox().get(0).getJSONObject("envelope"); }
    @Test public void realSignalRoundtripAndReceipt() throws Exception {
        alice.sendText(bob.id(), "Mensaje privado", 3600);
        JSONObject wire = first(alice); assertFalse(wire.toString().contains("Mensaje privado"));
        bob.receive(wire); assertEquals("Mensaje privado", bob.messages(alice.id()).get(0).getString("text"));
        alice.receive(first(bob)); assertEquals("Entregado", alice.messages(bob.id()).get(0).getString("status"));
        assertTrue(alice.outbox().isEmpty());
    }
    @Test public void duplicateAcrossTransportsIsDisplayedOnce() throws Exception {
        alice.sendText(bob.id(), "one", 3600); JSONObject envelope = first(alice);
        bob.receive(envelope); bob.receive(new JSONObject(envelope.toString()));
        assertEquals(1, bob.messages(alice.id()).size());
    }
    @Test public void ratchetSurvivesEngineRestart() throws Exception {
        alice.sendText(bob.id(), "first", 3600); bob.receive(first(alice)); alice.receive(first(bob));
        Engine restarted = new Engine(aliceRecords);
        restarted.sendText(bob.id(), "second", 3600); bob.receive(first(restarted));
        assertEquals(2, bob.messages(alice.id()).size());
    }
    @Test public void alteredCiphertextIsRejectedWithoutConsumingKeys() throws Exception {
        alice.sendText(bob.id(), "intact", 3600); JSONObject original = first(alice);
        JSONObject modified = new JSONObject(original.toString()); byte[] ct = Bytes.unb64(modified.getString("ct")); ct[ct.length - 1] ^= 1;
        modified.put("ct", Bytes.b64(ct)); assertThrows(Exception.class, () -> bob.receive(modified));
        bob.receive(original); assertEquals("intact", bob.messages(alice.id()).get(0).getString("text"));
    }
    @Test public void changedOuterIdIsRejectedAtomically() throws Exception {
        alice.sendText(bob.id(), "bound", 3600); JSONObject original = first(alice);
        JSONObject modified = new JSONObject(original.toString()).put("id", UUID.randomUUID().toString());
        assertThrows(Exception.class, () -> bob.receive(modified)); bob.receive(original);
        assertEquals(1, bob.messages(alice.id()).size());
    }
    @Test public void wrongRecipientIsRejected() throws Exception {
        alice.sendText(bob.id(), "bound", 3600); JSONObject original = first(alice);
        JSONObject modified = new JSONObject(original.toString()).put("to", "f".repeat(64));
        assertThrows(Exception.class, () -> bob.receive(modified)); bob.receive(original);
    }
    @Test public void outOfOrderInitialMessages() throws Exception {
        alice.sendText(bob.id(), "first", 3600); JSONObject first = new JSONObject(first(alice).toString());
        alice.sendText(bob.id(), "second", 3600);
        JSONObject second = alice.outbox().stream().map(o -> o.optJSONObject("envelope")).filter(o -> !o.optString("id").equals(first.optString("id"))).findFirst().orElseThrow();
        bob.receive(second); bob.receive(first); assertEquals(2, bob.messages(alice.id()).size());
    }
    @Test public void bidirectionalInitiation() throws Exception {
        alice.sendText(bob.id(), "from Alice", 3600); JSONObject a = first(alice);
        bob.sendText(alice.id(), "from Bob", 3600); JSONObject b = first(bob);
        bob.receive(a); alice.receive(b);
        assertEquals(2, alice.messages(bob.id()).size()); assertEquals(2, bob.messages(alice.id()).size());
    }
    @Test public void unverifiedContactsCannotSend() throws Exception {
        Engine carol = new Engine(new MemoryRecords()); carol.initialize("Carol"); carol.importCard(bob.createCard());
        assertThrows(SecurityException.class, () -> carol.sendText(bob.id(), "blocked", 3600)); assertTrue(carol.outbox().isEmpty());
    }
    @Test public void incorrectVerificationCodeRejected() throws Exception {
        assertThrows(SecurityException.class, () -> alice.verify(bob.id(), "0".repeat(64)));
    }
    @Test public void blockedContactCannotSendOrReceive() throws Exception {
        alice.sendText(bob.id(), "blocked", 3600); JSONObject envelope = first(alice);
        bob.block(alice.id(), true); assertThrows(SecurityException.class, () -> bob.receive(envelope));
        assertThrows(SecurityException.class, () -> bob.sendText(alice.id(), "blocked", 3600));
    }
    @Test public void cardTamperingRejected() throws Exception {
        JSONObject card = bob.createCard(); JSONObject body = new JSONObject(Bytes.text(Bytes.unb64(card.getString("body"))));
        body.put("alias", "Mallory"); card.put("body", Bytes.b64(Bytes.utf8(body.toString())));
        assertThrows(SecurityException.class, () -> alice.importCard(card));
    }
    @Test public void attachmentRoundtrip() throws Exception {
        byte[] input = Bytes.random(Engine.MAX_ATTACHMENT); alice.sendFile(bob.id(), "data.bin", input, 3600); bob.receive(first(alice));
        assertArrayEquals(input, Bytes.unb64(bob.messages(alice.id()).get(0).getString("data")));
    }
    @Test public void oversizedAttachmentRejectedBeforeEncryption() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> alice.sendFile(bob.id(), "large", new byte[Engine.MAX_ATTACHMENT + 1], 3600));
        assertTrue(alice.outbox().isEmpty());
    }
    @Test public void invalidLifetimeRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> alice.sendText(bob.id(), "x", 0));
        assertThrows(IllegalArgumentException.class, () -> alice.sendText(bob.id(), "x", 604801));
    }
    @Test public void localClearDoesNotForgetReplayProtection() throws Exception {
        alice.sendText(bob.id(), "once", 3600); JSONObject envelope = first(alice); bob.receive(envelope);
        bob.clearConversation(alice.id()); bob.receive(envelope); assertTrue(bob.messages(alice.id()).isEmpty());
    }
    @Test public void retriesReuseExactCiphertext() throws Exception {
        alice.sendText(bob.id(), "retry", 3600); JSONObject envelope = first(alice);
        alice.transported(envelope.getString("id"), true); assertEquals(envelope.toString(), first(alice).toString());
        assertNotEquals("Entregado", alice.messages(bob.id()).get(0).getString("status"));
    }
    @Test public void transactionRollbackRestoresRecords() throws Exception {
        MemoryRecords records = new MemoryRecords(); records.put("x", "key", Bytes.utf8("before"));
        assertThrows(Exception.class, () -> records.transaction(() -> { records.put("x", "key", Bytes.utf8("after")); throw new Exception("abort"); }));
        assertEquals("before", Bytes.text(records.get("x", "key")));
    }

    @Test public void queueKeepsEncryptionOrderWhenTtlDiffers() throws Exception {
        String firstId = alice.sendText(bob.id(), "long first", 604800);
        String secondId = alice.sendText(bob.id(), "short second", 60);
        List<JSONObject> queue = alice.outbox();
        assertEquals(firstId, queue.get(0).getJSONObject("envelope").getString("id"));
        assertEquals(secondId, queue.get(1).getJSONObject("envelope").getString("id"));
        assertTrue(queue.get(0).getLong("sequence") < queue.get(1).getLong("sequence"));
    }
    @Test public void nearbyProofUsesRealIdentitySignature() throws Exception {
        byte[] a = Bytes.random(32), b = Bytes.random(32);
        byte[] signature = alice.proveNearby(true, bob.id(), a, b);
        bob.verifyNearby(true, alice.id(), a, b, signature, false);
    }
    @Test public void nearbySignatureCannotBeReplayedToAnotherChallenge() throws Exception {
        byte[] a = Bytes.random(32), b = Bytes.random(32);
        byte[] signature = alice.proveNearby(true, bob.id(), a, b);
        assertThrows(SecurityException.class, () -> bob.verifyNearby(true, alice.id(), a, Bytes.random(32), signature, false));
    }
    @Test public void nearbySignatureBindsDialerRole() throws Exception {
        byte[] a = Bytes.random(32), b = Bytes.random(32);
        byte[] signature = alice.proveNearby(true, bob.id(), a, b);
        assertThrows(SecurityException.class, () -> bob.verifyNearby(false, alice.id(), a, b, signature, false));
    }
    @Test public void nearbyReconnectRejectsUnverifiedPeer() throws Exception {
        Engine carol = new Engine(new MemoryRecords()); carol.initialize("Carol");
        carol.importCard(bob.createCard()); bob.importCard(carol.createCard());
        byte[] a = Bytes.random(32), b = Bytes.random(32);
        byte[] signature = carol.proveNearby(true, bob.id(), a, b);
        assertThrows(SecurityException.class, () -> bob.verifyNearby(true, carol.id(), a, b, signature, false));
        bob.verifyNearby(true, carol.id(), a, b, signature, true);
        assertFalse(bob.contact(carol.id()).getBoolean("verified"));
    }
    @Test public void blockedPeerCannotRequestProof() throws Exception {
        alice.block(bob.id(), true);
        assertThrows(SecurityException.class, () -> alice.proveNearby(true, bob.id(), Bytes.random(32), Bytes.random(32)));
    }
    @Test public void envelopeTypeCoercionRejectedWithoutConsumingRatchet() throws Exception {
        alice.sendText(bob.id(), "keep", 3600); JSONObject original = first(alice);
        JSONObject changed = new JSONObject(original.toString()).put("expires", original.get("expires").toString());
        assertThrows(SecurityException.class, () -> bob.receive(changed));
        bob.receive(original); assertEquals("keep", bob.messages(alice.id()).get(0).getString("text"));
    }
    @Test public void unknownEnvelopeFieldRejectedWithoutConsumingRatchet() throws Exception {
        alice.sendText(bob.id(), "keep", 3600); JSONObject original = first(alice);
        assertThrows(SecurityException.class, () -> bob.receive(new JSONObject(original.toString()).put("extra", true)));
        bob.receive(original); assertEquals(1, bob.messages(alice.id()).size());
    }
    @Test public void localCapacityFailureDoesNotConsumeRatchet() throws Exception {
        alice.sendText(bob.id(), "after freeing space", 3600); JSONObject envelope = first(alice);
        for (int i = 0; i < 4096; i++) bobRecords.put("message", "filler-" + i, Bytes.utf8("{}"));
        assertThrows(app.umbra.core.LocalCapacityException.class, () -> bob.receive(envelope));
        for (String key : bobRecords.keys("message")) bobRecords.remove("message", key);
        bob.receive(envelope); assertEquals(1, bob.messages(alice.id()).size());
    }
    @Test public void retryMetadataDoesNotChangeCiphertext() throws Exception {
        String id = alice.sendText(bob.id(), "retry", 3600); String wire = first(alice).toString();
        alice.relayFailed(id); JSONObject queued = alice.outbox().get(0);
        assertEquals(1, queued.getInt("relayAttempts")); assertTrue(queued.getLong("nextRelay") > Bytes.now());
        assertEquals(wire, queued.getJSONObject("envelope").toString());
    }
    @Test public void stagedExportIsBoundedAndCleared() throws Exception {
        byte[] content = Bytes.utf8("private export"); String key = alice.stageExport(content);
        assertArrayEquals(new byte[content.length], content);
        assertEquals("private export", Bytes.text(alice.exportData(key)));
        alice.clearExport(key); assertThrows(SecurityException.class, () -> alice.exportData(key));
    }
    @Test public void expiredStagedExportCannotBeRead() throws Exception {
        String key = alice.stageExport(Bytes.utf8("expire"));
        JSONObject record = new JSONObject(Bytes.text(aliceRecords.get("export", key))).put("expires", Bytes.now() - 1);
        aliceRecords.put("export", key, Bytes.utf8(record.toString()));
        assertThrows(SecurityException.class, () -> alice.exportData(key));
        alice.expire(); assertNull(aliceRecords.get("export", key));
    }
}
