package app.umbra;

import app.umbra.core.Bytes;
import app.umbra.core.Padding;
import app.umbra.crypto.Engine;
import app.umbra.crypto.SignalStore;
import app.umbra.messaging.reply.ReplyPayload;
import app.umbra.messaging.reply.ReplyReference;
import app.umbra.messaging.reply.ReplyTargetPolicy;
import app.umbra.protocol.Wire;
import java.util.*;
import org.json.JSONObject;
import org.junit.Test;
import org.signal.libsignal.protocol.*;
import org.signal.libsignal.protocol.message.*;
import static org.junit.Assert.*;

/**
 * Real libsignal/JNI proposal probes with transactional synthetic memory records.
 * Candidate messages use direct SessionCipher test helpers, NOT an implemented
 * Engine reply API. No new transport, Android storage, UI or device acceptance.
 */
public final class ReplyPayloadSignalTest {
    private static final class Pair {
        final MemoryRecords ar=new MemoryRecords(),br=new MemoryRecords();
        final Engine a=new Engine(ar),b=new Engine(br);
        final String originalId;
        Pair() throws Exception {
            a.initialize("Synthetic Reply A"); b.initialize("Synthetic Reply B");
            AdmissionFixture.enroll(a); AdmissionFixture.enroll(b);
            a.importCard(b.createCard()); b.importCard(a.createCard());
            String code=Bytes.safetyCode(a.id(),b.id());a.verify(b.id(),code);b.verify(a.id(),code);
            originalId=a.sendText(b.id(),"Synthetic original",600);
            b.receive(a.outbox().get(0).getJSONObject("envelope"));
            for(JSONObject ack:b.outbox())a.receive(ack.getJSONObject("envelope"));
        }
        JSONObject payload() throws Exception {
            long now=Bytes.now();
            return new JSONObject().put("v",1).put("id",UUID.randomUUID().toString())
                .put("from",a.id()).put("to",b.id()).put("created",now)
                .put("createdMs",now*1000).put("expires",now+600).put("kind","reply")
                .put("text","Synthetic reply body unique to this test")
                .put("replyTo",new ReplyReference(originalId,a.id(),b.id()).toJson());
        }
        JSONObject encryptProposal(JSONObject content) throws Exception {
            // TEST ONLY: authentication is real but there is no production sendReply yet.
            var authorization=a.deliveryAuthorization(b.id());
            return ar.transaction(()->{
                authorization.run();
                byte[] clear=Bytes.utf8(content.toString()),padded=Padding.pad(clear);
                CiphertextMessage ciphertext;
                try {ciphertext=new SessionCipher(new SignalStore(ar),new SignalProtocolAddress(a.id(),1),
                        new SignalProtocolAddress(b.id(),1)).encrypt(padded);}
                finally {Arrays.fill(clear,(byte)0);Arrays.fill(padded,(byte)0);}
                return new JSONObject().put("v",1).put("id",content.get("id"))
                    .put("from",a.id()).put("to",b.id()).put("expires",content.get("expires"))
                    .put("type",ciphertext.getType()).put("ct",Bytes.b64(ciphertext.serialize()));
            });
        }
        ReplyPayload decodeProposal(JSONObject envelope) throws Exception {
            var authorization=b.deliveryAuthorization(a.id());
            return br.transaction(()->{
                authorization.run();long now=Bytes.now();Wire.envelope(envelope,b.id(),now,Engine.MAX_TTL);
                SessionCipher cipher=new SessionCipher(new SignalStore(br),new SignalProtocolAddress(b.id(),1),
                    new SignalProtocolAddress(a.id(),1));
                byte[] encrypted=Bytes.unb64(envelope.getString("ct"));
                byte[] padded=envelope.getInt("type")==CiphertextMessage.PREKEY_TYPE?
                    cipher.decrypt(new PreKeySignalMessage(encrypted)):cipher.decrypt(new SignalMessage(encrypted));
                byte[] clear=null;
                try {clear=Padding.unpad(padded);return ReplyPayload.decode(clear,envelope,b.id(),now);}
                finally {Arrays.fill(padded,(byte)0);if(clear!=null)Arrays.fill(clear,(byte)0);}
            });
        }
    }
    private static Map<String,byte[]> snapshot(MemoryRecords records) {
        Map<String,byte[]> result=new TreeMap<>();
        for(String bucket:new String[]{"session","prekey","signed","kyber","key-expiry","kem-used","message","seen","outbox"})
            for(String key:records.keys(bucket))result.put(bucket+":"+key,records.get(bucket,key));
        return result;
    }
    private static void unchanged(Map<String,byte[]> before,MemoryRecords records) {
        Map<String,byte[]> after=snapshot(records);
        assertTrue("No protocol/history keys may be added or removed on rejection",before.keySet().equals(after.keySet()));
        for(String key:before.keySet())assertTrue("Rejected operation changed synthetic protocol bytes",Arrays.equals(before.get(key),after.get(key)));
    }
    @Test public void signalCiphertextCarriesReferenceWithoutAddingOuterMetadata() throws Exception {
        Pair p=new Pair();JSONObject content=p.payload(),e=p.encryptProposal(content);
        Set<String> keys=new HashSet<>();e.keys().forEachRemaining(keys::add);
        assertEquals(Set.of("v","id","from","to","type","ct","expires"),keys);
        assertFalse(e.toString().contains("replyTo"));assertFalse(e.toString().contains(content.getString("text")));
        assertFalse(e.toString().contains(p.originalId));
        ReplyPayload decoded=p.decodeProposal(e);assertEquals(p.originalId,decoded.replyTo().id());
        assertEquals(content.getString("text"),decoded.text());
        assertEquals(ReplyTargetPolicy.Availability.AVAILABLE,ReplyTargetPolicy.resolve(decoded.replyTo(),p.b.id(),p.a.id(),
            p.b.get("message",p.a.id()+":"+p.originalId),Bytes.now()));
    }
    @Test public void productionEngineRefusesCandidateAndLeavesRatchetHistoryUntouched() throws Exception {
        Pair p=new Pair();JSONObject e=p.encryptProposal(p.payload());Map<String,byte[]> before=snapshot(p.br);
        assertThrows(SecurityException.class,()->p.b.receive(e));unchanged(before,p.br);
        String next=p.a.sendText(p.b.id(),"Synthetic ordinary text after unsupported reply",600);
        JSONObject regular=p.a.outbox().stream().map(x->x.optJSONObject("envelope"))
            .filter(x->next.equals(x.optString("id"))).findFirst().orElseThrow();
        p.b.receive(regular);
        assertEquals("Synthetic ordinary text after unsupported reply",p.b.get("message",p.a.id()+":"+next).getString("text"));
        assertEquals(p.originalId,p.decodeProposal(e).replyTo().id());
    }
    @Test public void alteredCiphertextRejectsWithoutConsumingTheValidMessage() throws Exception {
        Pair p=new Pair();JSONObject e=p.encryptProposal(p.payload()),bad=new JSONObject(e.toString());
        byte[] bytes=Bytes.unb64(e.getString("ct"));bytes[bytes.length-1]^=1;bad.put("ct",Bytes.b64(bytes));
        Map<String,byte[]> before=snapshot(p.br);assertThrows(Exception.class,()->p.decodeProposal(bad));
        unchanged(before,p.br);assertEquals(p.originalId,p.decodeProposal(e).replyTo().id());
    }
    @Test public void authenticatedOuterIdMismatchRollsBackDecryption() throws Exception {
        Pair p=new Pair();JSONObject e=p.encryptProposal(p.payload()),bad=new JSONObject(e.toString()).put("id",UUID.randomUUID().toString());
        Map<String,byte[]> before=snapshot(p.br);
        assertThrows(SecurityException.class,()->p.decodeProposal(bad));unchanged(before,p.br);
        assertEquals(e.getString("id"),p.decodeProposal(e).id());
    }
    @Test public void outerRecipientSubstitutionRejectedAndOriginalRemainsDecryptable() throws Exception {
        Pair p=new Pair();JSONObject e=p.encryptProposal(p.payload()),bad=new JSONObject(e.toString()).put("to","c".repeat(64));
        Map<String,byte[]> before=snapshot(p.br);assertThrows(SecurityException.class,()->p.decodeProposal(bad));
        unchanged(before,p.br);assertEquals(p.b.id(),p.decodeProposal(e).to());
    }
    @Test public void authenticatedSenderCannotQuoteAnotherConversation() throws Exception {
        Pair p=new Pair();JSONObject content=p.payload();
        content.put("replyTo",new ReplyReference(UUID.randomUUID().toString(),p.a.id(),"c".repeat(64)).toJson());
        JSONObject e=p.encryptProposal(content);Map<String,byte[]> before=snapshot(p.br);
        assertThrows(SecurityException.class,()->p.decodeProposal(e));unchanged(before,p.br);
    }
    @Test public void replyCanArriveBeforeOrdinaryOriginalWithoutInventingIt() throws Exception {
        Pair p=new Pair();String original=p.a.sendText(p.b.id(),"Synthetic delayed original",600);
        JSONObject pending=p.a.outbox().stream().map(x->x.optJSONObject("envelope"))
            .filter(x->original.equals(x.optString("id"))).findFirst().orElseThrow();
        JSONObject content=p.payload().put("replyTo",new ReplyReference(original,p.a.id(),p.b.id()).toJson());
        ReplyPayload reply=p.decodeProposal(p.encryptProposal(content));
        assertNull(p.b.get("message",p.a.id()+":"+original));
        assertEquals(ReplyTargetPolicy.Availability.UNAVAILABLE,ReplyTargetPolicy.resolve(reply.replyTo(),p.b.id(),p.a.id(),null,Bytes.now()));
        p.b.receive(pending);
        assertEquals(ReplyTargetPolicy.Availability.AVAILABLE,ReplyTargetPolicy.resolve(reply.replyTo(),p.b.id(),p.a.id(),
            p.b.get("message",p.a.id()+":"+original),Bytes.now()));
    }
    @Test public void outerTransactionFailureRollsBackSuccessfulCandidateDecode() throws Exception {
        Pair p=new Pair();JSONObject e=p.encryptProposal(p.payload());Map<String,byte[]> before=snapshot(p.br);
        assertThrows(java.io.IOException.class,()->p.br.transaction(()->{
            p.decodeProposal(e);throw new java.io.IOException("Synthetic persistence failure");
        }));
        unchanged(before,p.br);assertEquals(p.originalId,p.decodeProposal(e).replyTo().id());
    }
    @Test public void clearConversationDoesNotReconstructDeletedParentFromReference() throws Exception {
        Pair p=new Pair();JSONObject e=p.encryptProposal(p.payload());p.b.clearConversation(p.a.id());
        ReplyPayload reply=p.decodeProposal(e);
        assertEquals(ReplyTargetPolicy.Availability.UNAVAILABLE,ReplyTargetPolicy.resolve(reply.replyTo(),p.b.id(),p.a.id(),null,Bytes.now()));
        assertTrue(p.b.messages(p.a.id()).isEmpty());
    }
}
