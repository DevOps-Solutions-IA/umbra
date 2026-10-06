package app.umbra.messaging.reply;

import app.umbra.core.Bytes;
import app.umbra.crypto.Engine;
import app.umbra.protocol.Wire;
import java.util.UUID;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Candidate codec only. Structural test envelopes are not encrypted transport evidence. */
public final class ReplyPayloadTest {
    private static final String A="a".repeat(64), B="b".repeat(64), C="c".repeat(64);
    private static final long NOW=2_000_000_000L;
    private static JSONObject payload() throws Exception {
        return new JSONObject().put("v",1).put("id",UUID.randomUUID().toString())
            .put("from",A).put("to",B).put("created",NOW).put("createdMs",NOW*1000)
            .put("expires",NOW+600).put("kind","reply").put("text","Synthetic new answer")
            .put("replyTo",new ReplyReference(UUID.randomUUID().toString(),B,A).toJson());
    }
    private static JSONObject envelope(JSONObject c) throws Exception {
        return new JSONObject().put("v",1).put("id",c.get("id")).put("from",c.get("from"))
            .put("to",c.get("to")).put("expires",c.get("expires"))
            .put("type",2).put("ct",Bytes.b64(new byte[24]));
    }
    private static ReplyPayload decode(JSONObject c) throws Exception {
        return ReplyPayload.decode(Bytes.utf8(c.toString()),envelope(c),B,NOW);
    }
    private static void reject(JSONObject c) {
        assertThrows(SecurityException.class,()->decode(c));
    }
    @Test public void exactCandidateBindsEveryEnvelopeFieldAndReturnsImmutableValues() throws Exception {
        JSONObject c=payload(); ReplyPayload result=decode(c);
        assertEquals(c.getString("id"),result.id()); assertEquals(A,result.from());
        assertEquals(B,result.to()); assertEquals(NOW,result.created());
        assertEquals(NOW*1000,result.createdMs()); assertEquals(NOW+600,result.expires());
        assertEquals("Synthetic new answer",result.text());
        assertEquals(B,result.replyTo().from()); assertEquals(A,result.replyTo().to());
        c.put("text","mutated"); c.getJSONObject("replyTo").put("from",C);
        assertEquals("Synthetic new answer",result.text()); assertEquals(B,result.replyTo().from());
    }
    @Test public void earlierOutgoingOriginalIsAllowed() throws Exception {
        JSONObject c=payload(); c.put("replyTo",new ReplyReference(UUID.randomUUID().toString(),A,B).toJson());
        assertEquals(A,decode(c).replyTo().from());
    }
    @Test public void sameUuidInOppositeDirectionIsNotWronglyConflated() throws Exception {
        JSONObject c=payload();c.put("replyTo",new ReplyReference(c.getString("id"),B,A).toJson());
        assertEquals(B,decode(c).replyTo().from());
    }
    @Test public void selfReferenceInSameDirectionIsRejected() throws Exception {
        JSONObject c=payload();c.put("replyTo",new ReplyReference(c.getString("id"),A,B).toJson());reject(c);
    }
    @Test public void unknownParentDoesNotTriggerLookupOrRequireItsPresence() throws Exception {
        // Parsing is not proof of the original's existence. The UI resolver remains separate.
        assertNotNull(decode(payload()).replyTo());
    }
    @Test public void thirdPartyReferenceIsRejected() throws Exception {
        JSONObject c=payload();c.put("replyTo",new ReplyReference(UUID.randomUUID().toString(),A,C).toJson());reject(c);
    }
    @Test public void unsupportedVersionAndLogicalFanoutAreRejected() throws Exception {
        for(Object v:new Object[]{0,2,3,"1",true,JSONObject.NULL}) {JSONObject c=payload();c.put("v",v);reject(c);}
        // JSONObject serializes Double(1.0) as integer 1; preserve lexical input at the raw boundary.
        for(String number:new String[]{"1.0","1e0"}) {
            JSONObject c=payload(),e=envelope(c);c.remove("v");
            String raw="{\"v\":"+number+","+c.toString().substring(1);
            assertThrows(SecurityException.class,()->ReplyPayload.decode(Bytes.utf8(raw),e,B,NOW));
        }
        JSONObject c=payload();c.put("logicalId",UUID.randomUUID().toString()).put("logicalFrom",A).put("logicalTo",B);reject(c);
    }
    @Test public void unknownOrMissingFieldsAreRejected() throws Exception {
        JSONObject c=payload();c.put("preview","Synthetic forged quotation");reject(c);
        c=payload();c.remove("replyTo");reject(c);
        c=payload();c.getJSONObject("replyTo").put("text","Synthetic forged quotation");reject(c);
    }
    @Test public void kindAndNestedObjectTypesCannotBeCoerced() throws Exception {
        JSONObject c=payload();c.put("kind","text");reject(c);
        for(Object value:new Object[]{"{}",true,1,JSONObject.NULL}) {c=payload();c.put("replyTo",value);reject(c);}
        c=payload();c.getJSONObject("replyTo").put("v","1");reject(c);
    }
    @Test public void messageIdAndIdentityMustBeCanonical() throws Exception {
        JSONObject c=payload();c.put("id","1-1-1-1-1");reject(c);
        c=payload();c.put("from",A.toUpperCase());reject(c);
        c=payload();c.put("to",A);reject(c);
    }
    @Test public void actualLocalRecipientIsRequiredRatherThanPayloadClaim() throws Exception {
        JSONObject c=payload();assertThrows(SecurityException.class,()->ReplyPayload.decode(Bytes.utf8(c.toString()),envelope(c),C,NOW));
    }
    @Test public void outerHeaderSubstitutionIsRejected() throws Exception {
        for(String field:new String[]{"id","from","to","expires"}) {
            JSONObject c=payload(), e=envelope(c);
            e.put(field,field.equals("id")?UUID.randomUUID().toString():field.equals("expires")?NOW+601:C);
            assertThrows(SecurityException.class,()->ReplyPayload.decode(Bytes.utf8(c.toString()),e,B,NOW));
        }
    }
    @Test public void unknownEnvelopeFieldIsRejected() throws Exception {
        JSONObject c=payload(),e=envelope(c).put("replyTo","visible-reference");
        assertThrows(SecurityException.class,()->ReplyPayload.decode(Bytes.utf8(c.toString()),e,B,NOW));
    }
    @Test public void textByteLimitRejectsEmptyOversizeAndCoercedValues() throws Exception {
        for(Object text:new Object[]{"", " \n\t", "x".repeat(16001), "é".repeat(8001),42,true,JSONObject.NULL}) {
            JSONObject c=payload();c.put("text",text);reject(c);
        }
        JSONObject c=payload();c.put("text","é".repeat(8000));assertEquals(16000,Bytes.utf8(decode(c).text()).length);
    }
    @Test public void supportedEscapedTextFitsRawBound() throws Exception {
        JSONObject c=payload();String text="\u0001X".repeat(8000);c.put("text",text);
        assertTrue(Bytes.utf8(c.toString()).length<=ReplyPayload.MAX_WIRE_BYTES);
        assertEquals(text,decode(c).text());
    }
    @Test public void expiredTooLongOrNonPositiveLifetimesAreRejected() throws Exception {
        for(long expiry:new long[]{NOW,NOW-1,NOW+Engine.MAX_TTL+1,Long.MAX_VALUE}) {
            JSONObject c=payload();c.put("expires",expiry);reject(c);
        }
        JSONObject c=payload();c.put("expires",NOW+Engine.MAX_TTL);assertEquals(NOW+Engine.MAX_TTL,decode(c).expires());
    }
    @Test public void timestampTypesSkewAndOverflowAreRejected() throws Exception {
        for(Object created:new Object[]{"2000000000",true,-1L,NOW+301,NOW-Engine.MAX_TTL-1,Long.MIN_VALUE,Long.MAX_VALUE}) {
            JSONObject c=payload();c.put("created",created);reject(c);
        }
        for(Object ms:new Object[]{"2000000000000",-1L,Long.MAX_VALUE,NOW*1000+2000}) {
            JSONObject c=payload();c.put("createdMs",ms);reject(c);
        }
    }
    @Test public void rawDuplicateFieldsInvalidJsonAndSizeAreRejected() throws Exception {
        JSONObject c=payload(),e=envelope(c);
        String raw=c.toString();String duplicated="{\"kind\":\"reply\","+raw.substring(1);
        for(byte[] value:new byte[][]{Bytes.utf8(duplicated),Bytes.utf8("[]"),Bytes.utf8("{"),new byte[ReplyPayload.MAX_WIRE_BYTES+1]})
            assertThrows(SecurityException.class,()->ReplyPayload.decode(value,e,B,NOW));
        assertThrows(SecurityException.class,()->ReplyPayload.decode(null,e,B,NOW));
    }
    @Test public void invalidClockIsRejectedWithoutOverflow() throws Exception {
        JSONObject c=payload(),e=envelope(c);
        for(long now:new long[]{-1,Long.MIN_VALUE,Long.MAX_VALUE})
            assertThrows(SecurityException.class,()->ReplyPayload.decode(Bytes.utf8(c.toString()),e,B,now));
    }
    @Test public void originalDeadlineIsNotCopiedToNewAnswer() throws Exception {
        JSONObject c=payload();assertEquals(NOW+600,decode(c).expires());
        assertFalse(c.getJSONObject("replyTo").has("expires"));
    }
    @Test public void logRepresentationAndExceptionsContainNoContent() throws Exception {
        ReplyPayload p=decode(payload());assertEquals("ReplyPayload[redacted]",p.toString());
        JSONObject c=payload();c.put("kind","SECRET_INVALID_PAYLOAD");
        SecurityException e=assertThrows(SecurityException.class,()->decode(c));
        assertEquals("Invalid reply payload",e.getMessage());assertNull(e.getCause());
        assertEquals(0,e.getSuppressed().length);
    }
    @Test public void currentProductionParserRejectsProposedKindAndStillAcceptsText() throws Exception {
        JSONObject c=payload();assertThrows(SecurityException.class,()->Wire.content(c,envelope(c),NOW,Engine.MAX_TTL,Engine.MAX_ATTACHMENT));
        c.remove("replyTo");c.put("kind","text");Wire.content(c,envelope(c),NOW,Engine.MAX_TTL,Engine.MAX_ATTACHMENT);
    }
}
