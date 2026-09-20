package app.umbra;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import app.umbra.core.Bytes;
import app.umbra.crypto.Engine;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Actual packaged Android libsignal JNI, synthetic records, direct envelope transport (not Bluetooth). */
@RunWith(AndroidJUnit4.class)
public class DeviceSignalTest {
    private Engine alice, bob;
    private DeviceMemoryRecords aliceRecords;
    @Before public void setUp() throws Exception {
        aliceRecords = new DeviceMemoryRecords();
        alice = new Engine(aliceRecords); bob = new Engine(new DeviceMemoryRecords());
        alice.initialize("Synthetic Alice"); bob.initialize("Synthetic Bob");
        alice.importCard(bob.createCard()); bob.importCard(alice.createCard());
        String code = Bytes.safetyCode(alice.id(), bob.id());
        alice.verify(bob.id(), code); bob.verify(alice.id(), code);
    }
    private static JSONObject first(Engine engine) throws Exception {
        return engine.outbox().get(0).getJSONObject("envelope");
    }
    @Test public void realJniRoundtripReceiptAndDuplicate() throws Exception {
        alice.sendText(bob.id(), "Synthetic Android JNI message", 3600);
        JSONObject envelope = first(alice);
        assertFalse(envelope.toString().contains("Synthetic Android JNI message"));
        bob.receive(envelope); bob.receive(new JSONObject(envelope.toString()));
        assertEquals(1, bob.messages(alice.id()).size());
        assertEquals("Synthetic Android JNI message", bob.messages(alice.id()).get(0).getString("text"));
        alice.receive(first(bob));
        assertEquals("Entregado", alice.messages(bob.id()).get(0).getString("status"));
        assertTrue(alice.outbox().isEmpty());
    }
    @Test public void alteredCiphertextRollsBackBeforeAuthenticRetry() throws Exception {
        alice.sendText(bob.id(), "Synthetic authentic retry", 3600);
        JSONObject original = first(alice), altered = new JSONObject(original.toString());
        byte[] ciphertext = Bytes.unb64(altered.getString("ct")); ciphertext[ciphertext.length - 1] ^= 1;
        altered.put("ct", Bytes.b64(ciphertext));
        assertThrows(Exception.class, () -> bob.receive(altered));
        assertTrue(bob.messages(alice.id()).isEmpty());
        bob.receive(original);
        assertEquals("Synthetic authentic retry", bob.messages(alice.id()).get(0).getString("text"));
    }
    @Test public void ratchetPersistsAcrossEngineRecreation() throws Exception {
        alice.sendText(bob.id(), "Synthetic first", 3600); bob.receive(first(alice)); alice.receive(first(bob));
        Engine recreated = new Engine(aliceRecords);
        recreated.sendText(bob.id(), "Synthetic second", 3600); bob.receive(first(recreated));
        assertEquals(2, bob.messages(alice.id()).size());
    }
}
