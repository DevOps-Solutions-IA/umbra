package app.umbra.messaging.reply;

import app.umbra.core.Bytes;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Host-only contract tests. They do not prove transport, Android or authorization. */
public final class ReplyReferenceTest {
    private static final String ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String A = "a1".repeat(32), B = "b2".repeat(32), C = "c3".repeat(32);

    private static ReplyReference reference() { return new ReplyReference(ID, A, B); }

    @Test public void canonicalReferenceRoundTripsWithoutQuotedContent() throws Exception {
        ReplyReference ref = reference();
        byte[] bytes = ref.encode();
        assertTrue(bytes.length <= ReplyReference.MAX_WIRE_BYTES);
        assertEquals(ref, ReplyReference.parse(bytes));
        JSONObject json = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        Set<String> keys = new java.util.HashSet<>(); json.keys().forEachRemaining(keys::add);
        assertEquals(Set.of("v", "id", "from", "to"), keys);
        assertEquals(1, json.getInt("v"));
        assertFalse(json.has("text"));
        assertFalse(json.has("preview"));
    }

    @Test public void acceptsEitherDirectionOnlyWithinTheExactConversation() {
        reference().requireConversation(A, B);
        reference().requireConversation(B, A);
        assertThrows(SecurityException.class, () -> reference().requireConversation(A, C));
        assertThrows(SecurityException.class, () -> reference().requireConversation(C, B));
        assertThrows(SecurityException.class, () -> reference().requireConversation(A, A));
        assertThrows(SecurityException.class, () -> reference().requireConversation(null, B));
    }

    @Test public void rejectsNoncanonicalOrMissingMessageIdentifiers() {
        for (String id : new String[]{null, "", "private-synthetic-id", "1-1-1-1-1", ID.toUpperCase(), ID + " "})
            assertThrows(SecurityException.class, () -> new ReplyReference(id, A, B));
    }

    @Test public void rejectsBadIdentitiesAndSelfConversation() {
        for (String from : new String[]{null, "", "abc", A.toUpperCase(), A + "0"})
            assertThrows(SecurityException.class, () -> new ReplyReference(ID, from, B));
        assertThrows(SecurityException.class, () -> new ReplyReference(ID, A, A));
        assertThrows(SecurityException.class, () -> new ReplyReference(ID, A, null));
    }

    @Test public void rejectsMissingOrAdditionalFieldsRatherThanIgnoringThem() throws Exception {
        for (String field : new String[]{"v", "id", "from", "to"}) {
            JSONObject missing = reference().toJson(); missing.remove(field);
            assertThrows(SecurityException.class, () -> ReplyReference.fromJson(missing));
        }
        JSONObject extra = reference().toJson().put("preview", "synthetic quote must never be accepted");
        assertThrows(SecurityException.class, () -> ReplyReference.fromJson(extra));
    }

    @Test public void versionMustBeAnExactSupportedInteger() throws Exception {
        for (Object value : new Object[]{"1", 1.0, true, 0, 2, JSONObject.NULL, Long.MAX_VALUE}) {
            JSONObject json = reference().toJson().put("v", value);
            assertThrows(SecurityException.class, () -> ReplyReference.fromJson(json));
        }
        assertEquals(reference(), ReplyReference.fromJson(reference().toJson().put("v", 1L)));
    }

    @Test public void rejectsJsonTypeCoercion() throws Exception {
        for (String field : new String[]{"id", "from", "to"}) {
            JSONObject json = reference().toJson().put(field, 42);
            assertThrows(SecurityException.class, () -> ReplyReference.fromJson(json));
        }
        assertThrows(SecurityException.class, () -> ReplyReference.fromJson(null));
    }

    @Test public void rawBoundaryRejectsDuplicateFieldsTrailingDataAndInvalidUtf8() {
        String valid = "{\"v\":1,\"id\":\"" + ID + "\",\"from\":\"" + A + "\",\"to\":\"" + B + "\"}";
        for (byte[] raw : new byte[][]{
            Bytes.utf8(valid.replace("\"v\":1", "\"v\":1,\"v\":1")),
            Bytes.utf8(valid + " false"), Bytes.utf8("[" + valid + "]"),
            new byte[]{(byte) 0xc3, (byte) 0x28}, null})
            assertThrows(SecurityException.class, () -> ReplyReference.parse(raw));
        assertEquals(reference(), ReplyReference.parse(Bytes.utf8(valid)));
    }

    @Test public void rawBoundaryIsBounded() {
        assertThrows(SecurityException.class, () -> ReplyReference.parse(new byte[ReplyReference.MAX_WIRE_BYTES + 1]));
    }

    @Test public void jsonInputAndOutputCannotMutateTheReference() throws Exception {
        JSONObject input = reference().toJson();
        ReplyReference parsed = ReplyReference.fromJson(input);
        input.put("from", C);
        JSONObject output = parsed.toJson(); output.put("to", C);
        assertEquals(reference(), parsed);
        assertEquals(B, parsed.toJson().getString("to"));
    }

    @Test public void directionIsPartOfIdentityEvenWhenUuidCollides() {
        assertNotEquals(reference(), new ReplyReference(ID, B, A));
        assertNotEquals(reference(), new ReplyReference(ID, A, C));
    }

    @Test public void defaultDiagnosticsDoNotPrintReferenceOrRejectedInput() {
        String rendered = reference().toString();
        assertFalse(rendered.contains(ID)); assertFalse(rendered.contains(A)); assertFalse(rendered.contains(B));
        String bad = "synthetic-sensitive-input";
        SecurityException failure = assertThrows(SecurityException.class, () -> new ReplyReference(bad, A, B));
        assertFalse(failure.toString().contains(bad));
        assertNull(failure.getCause());
    }
}
