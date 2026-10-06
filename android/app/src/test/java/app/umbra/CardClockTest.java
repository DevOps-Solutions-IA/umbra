package app.umbra;

import app.umbra.core.Bytes;
import app.umbra.crypto.Engine;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real libsignal card generation, deterministic wall-clock boundary; not radio evidence. */
public class CardClockTest {
    @Test public void cardLifetimeUsesOneInstantEvenIfTheClockCrossesASecond() throws Exception {
        MemoryRecords records = new MemoryRecords();
        Engine sender = new Engine(records), recipient = new Engine(new MemoryRecords());
        sender.initialize("Synthetic sender"); recipient.initialize("Synthetic recipient");
        long now = Bytes.now(); AtomicInteger reads = new AtomicInteger();
        LongSupplier boundary = () -> now + Math.max(0, reads.getAndIncrement() - 1);
        var create = Engine.class.getDeclaredMethod("createCard", LongSupplier.class); create.setAccessible(true);
        JSONObject card = (JSONObject) create.invoke(sender, boundary);
        // Before correction, separate key-expiry/created/expires reads sign a TTL of MAX_TTL+1.
        assertEquals(sender.id(), recipient.importCard(card));
        JSONObject body = new JSONObject(Bytes.text(Bytes.unb64(card.getString("body"))));
        assertEquals(Engine.MAX_TTL, body.getLong("expires") - body.getLong("created"));
        assertEquals(body.getLong("expires"), Long.parseLong(Bytes.text(records.get("key-expiry", "" + body.getInt("keyId")))));
        assertEquals(1, reads.get());
        assertEquals(Engine.TrustState.UNVERIFIED, recipient.trustState(sender.id()));
        // Keep the rejection boundary: a genuinely overlong signed card is still invalid.
        body.put("expires", body.getLong("expires") + 1);
        byte[] raw = Bytes.utf8(body.toString());
        byte[] signature = new app.umbra.crypto.SignalStore(records).getIdentityKeyPair().getPrivateKey().calculateSignature(raw);
        JSONObject overlong = new JSONObject().put("format", "umbra-contact-v1")
            .put("body", Bytes.b64(raw)).put("signature", Bytes.b64(signature));
        assertThrows(SecurityException.class, () -> recipient.importCard(overlong));
        assertEquals(Engine.TrustState.UNVERIFIED, recipient.trustState(sender.id()));
    }
}
