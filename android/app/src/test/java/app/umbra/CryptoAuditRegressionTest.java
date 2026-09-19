package app.umbra;

import app.umbra.core.Bytes;
import app.umbra.crypto.Engine;
import app.umbra.data.Records;
import org.json.JSONObject;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

/** Real libsignal/JNI with synthetic records; does not exercise Android SQLite or Keystore. */
public class CryptoAuditRegressionTest {
    @Test public void freshVaultCanInitializeAndRestart() throws Exception {
        MemoryRecords records = new MemoryRecords(); Engine engine = new Engine(records);
        assertFalse(engine.initialized()); engine.initialize("Synthetic identity");
        assertTrue(new Engine(records).initialized());
        assertThrows(IllegalStateException.class, () -> engine.initialize("Replacement"));
    }

    @Test public void missingIdentityCannotReinitializeExistingVault() throws Exception {
        MemoryRecords records = new MemoryRecords(); Engine engine = new Engine(records);
        engine.initialize("Synthetic original"); byte[] profile = records.get("meta", "profile");
        records.remove("meta", "identity");
        assertThrows(IllegalStateException.class, engine::initialized);
        assertThrows(IllegalStateException.class, () -> engine.initialize("Replacement"));
        assertNull(records.get("meta", "identity")); assertArrayEquals(profile, records.get("meta", "profile"));
    }

    @Test public void missingAllMetadataCannotHideRemainingProtocolRecords() throws Exception {
        for (String bucket : new String[]{"contact", "trusted", "session", "prekey", "signed", "kyber",
                "key-expiry", "kem-used", "sender-key", "message", "seen", "outbox", "export"}) {
            MemoryRecords records = new MemoryRecords(); byte[] sentinel = Bytes.utf8("synthetic retained record");
            records.put(bucket, "synthetic", sentinel); Engine engine = new Engine(records);
            assertThrows(IllegalStateException.class, engine::initialized);
            assertThrows(IllegalStateException.class, () -> engine.initialize("Replacement"));
            assertArrayEquals(sentinel, records.get(bucket, "synthetic")); assertNull(records.get("meta", "identity"));
        }
    }

    @Test public void missingProfileFailsClosedWithoutReplacingKeys() throws Exception {
        MemoryRecords records = new MemoryRecords(); Engine engine = new Engine(records);
        engine.initialize("Synthetic original"); byte[] identity = records.get("meta", "identity");
        records.remove("meta", "profile");
        assertThrows(IllegalStateException.class, engine::initialized);
        assertThrows(IllegalStateException.class, () -> engine.initialize("Replacement"));
        assertArrayEquals(identity, records.get("meta", "identity")); assertNull(records.get("meta", "profile"));
    }

    @Test public void inconsistentProfileIdentityFailsClosed() throws Exception {
        MemoryRecords records = new MemoryRecords(); Engine engine = new Engine(records);
        engine.initialize("Synthetic original");
        JSONObject profile = engine.profile().put("id", "0".repeat(64));
        records.put("meta", "profile", Bytes.utf8(profile.toString()));
        assertThrows(IllegalStateException.class, engine::initialized);
        assertThrows(IllegalStateException.class, () -> engine.initialize("Replacement"));
        assertEquals(profile.toString(), engine.profile().toString());
    }

    @Test public void invalidRegistrationAndCorruptIdentityFailClosed() throws Exception {
        for (String registration : new String[]{"0", "16381", "-1", "4294967297", "bad"}) {
            MemoryRecords records = new MemoryRecords(); Engine engine = new Engine(records);
            engine.initialize("Synthetic original"); records.put("meta", "registration", Bytes.utf8(registration));
            assertThrows(Exception.class, engine::initialized);
            assertThrows(Exception.class, () -> engine.initialize("Replacement"));
            assertEquals(registration, Bytes.text(records.get("meta", "registration")));
        }
        MemoryRecords records = new MemoryRecords(); Engine engine = new Engine(records);
        engine.initialize("Synthetic original"); records.put("meta", "identity", new byte[]{1, 2, 3});
        assertThrows(Exception.class, engine::initialized);
        assertThrows(Exception.class, () -> engine.initialize("Replacement"));
        assertArrayEquals(new byte[]{1, 2, 3}, records.get("meta", "identity"));
    }

    private static final class FailingRecords implements Records {
        final MemoryRecords memory = new MemoryRecords();
        String failingBucket;
        public byte[] get(String b, String k) { return memory.get(b, k); }
        public void put(String b, String k, byte[] v) {
            if (b.equals(failingBucket)) throw new IllegalStateException("Synthetic disk write failure");
            memory.put(b, k, v);
        }
        public void remove(String b, String k) { memory.remove(b, k); }
        public List<String> keys(String b) { return memory.keys(b); }
        public <T> T transaction(Work<T> work) throws Exception { return memory.transaction(work); }
    }
    private static void connect(Engine alice, Engine bob) throws Exception {
        alice.initialize("Synthetic Alice"); bob.initialize("Synthetic Bob");
        alice.importCard(bob.createCard()); bob.importCard(alice.createCard());
        String code = Bytes.safetyCode(alice.id(), bob.id());
        alice.verify(bob.id(), code); bob.verify(alice.id(), code);
    }

    @Test public void failedSendPersistenceRollsBackRatchetAndOneTimeSession() throws Exception {
        FailingRecords records = new FailingRecords(); Engine alice = new Engine(records), bob = new Engine(new MemoryRecords());
        connect(alice, bob); records.failingBucket = "outbox";
        assertThrows(IllegalStateException.class, () -> alice.sendText(bob.id(), "failed", 3600));
        assertTrue(records.keys("session").isEmpty()); assertTrue(alice.messages(bob.id()).isEmpty());
        assertNull(records.get("meta", "enqueue-sequence")); records.failingBucket = null;
        alice.sendText(bob.id(), "committed", 3600); bob.receive(alice.outbox().get(0).getJSONObject("envelope"));
        assertEquals("committed", bob.messages(alice.id()).get(0).getString("text"));
    }

    @Test public void failedReceivePersistenceRollsBackReceiptRatchetAndConsumedPrekeys() throws Exception {
        for (String bucket : new String[]{"message", "outbox", "seen"}) {
            FailingRecords records = new FailingRecords(); Engine alice = new Engine(new MemoryRecords()), bob = new Engine(records);
            connect(alice, bob); List<String> prekeys = records.keys("prekey"), kyber = records.keys("kyber");
            alice.sendText(bob.id(), "retry immutable ciphertext", 3600);
            JSONObject envelope = alice.outbox().get(0).getJSONObject("envelope"); records.failingBucket = bucket;
            assertThrows(IllegalStateException.class, () -> bob.receive(envelope));
            assertEquals(prekeys, records.keys("prekey")); assertEquals(kyber, records.keys("kyber"));
            for (String empty : new String[]{"session", "message", "outbox", "seen", "kem-used"}) assertTrue(records.keys(empty).isEmpty());
            records.failingBucket = null; bob.receive(new JSONObject(envelope.toString()));
            assertEquals(1, bob.messages(alice.id()).size());
            alice.receive(bob.outbox().get(0).getJSONObject("envelope"));
            assertEquals("Entregado", alice.messages(bob.id()).get(0).getString("status"));
        }
    }
}
