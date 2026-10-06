package app.umbra.messaging.reply;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic metadata tests, not a substitute for authenticated Engine/Records integration. */
public final class ReplyTargetPolicyTest {
    private static final String ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String OTHER = "550e8400-e29b-41d4-a716-446655440001";
    private static final String A = "a1".repeat(32), B = "b2".repeat(32), C = "c3".repeat(32);
    private static final long NOW = 1000;

    private static JSONObject target(boolean outgoing, String kind) throws Exception {
        return new JSONObject().put("v", 1).put("id", ID)
            .put("from", outgoing ? A : B).put("to", outgoing ? B : A)
            .put("peer", B).put("outgoing", outgoing).put("kind", kind)
            .put("created", 900L).put("createdMs", 900000L).put("expires", 1500L)
            .put("text", "synthetic original text, never copied into the reply reference");
    }

    @Test public void selectsOutgoingTextWithoutChangingTheOriginal() throws Exception {
        JSONObject message = target(true, "text"); String before = message.toString();
        ReplyReference ref = ReplyTargetPolicy.select(A, B, message, NOW);
        assertEquals(new ReplyReference(ID, A, B), ref);
        assertEquals(before, message.toString());
        assertFalse(new String(ref.encode(), java.nio.charset.StandardCharsets.UTF_8).contains("synthetic original"));
    }

    @Test public void selectsIncomingTextWithItsOriginalAuthor() throws Exception {
        assertEquals(new ReplyReference(ID, B, A), ReplyTargetPolicy.select(A, B, target(false, "text"), NOW));
    }

    @Test public void ordinaryFileAndReplyMayBeTargetsWithoutReadingTheirContent() throws Exception {
        for (String kind : new String[]{"file", "reply"}) {
            JSONObject message = target(true, kind);
            message.remove("text");
            assertEquals(new ReplyReference(ID, A, B), ReplyTargetPolicy.select(A, B, message, NOW));
            // The policy handles metadata only; Wire/Engine must validate actual payload separately.
        }
    }

    @Test public void resolvesOnlyAnExactLiveOriginal() throws Exception {
        ReplyReference ref = new ReplyReference(ID, A, B);
        assertEquals(ReplyTargetPolicy.Availability.AVAILABLE,
            ReplyTargetPolicy.resolve(ref, A, B, target(true, "text"), NOW));
    }

    @Test public void missingOriginalIsUnavailableNotAFabricatedQuote() {
        ReplyReference ref = new ReplyReference(ID, A, B);
        assertEquals(ReplyTargetPolicy.Availability.UNAVAILABLE, ReplyTargetPolicy.resolve(ref, A, B, null, NOW));
        assertThrows(SecurityException.class, () -> ReplyTargetPolicy.select(A, B, null, NOW));
    }

    @Test public void expiryBoundaryPreventsSelectionAndPreviewWithoutExtendingTtl() throws Exception {
        JSONObject message = target(true, "text").put("expires", NOW);
        ReplyReference ref = new ReplyReference(ID, A, B);
        assertEquals(ReplyTargetPolicy.Availability.UNAVAILABLE, ReplyTargetPolicy.resolve(ref, A, B, message, NOW));
        assertThrows(SecurityException.class, () -> ReplyTargetPolicy.select(A, B, message, NOW));
        message.put("expires", NOW + 1);
        assertEquals(ReplyTargetPolicy.Availability.AVAILABLE, ReplyTargetPolicy.resolve(ref, A, B, message, NOW));
        assertEquals(NOW + 1, message.getLong("expires"));
    }

    @Test public void mismatchedUuidIsNotTreatedAsAMissingOriginal() throws Exception {
        JSONObject message = target(true, "text").put("id", OTHER);
        assertThrows(SecurityException.class, () -> ReplyTargetPolicy.resolve(new ReplyReference(ID, A, B), A, B, message, NOW));
    }

    @Test public void oppositeDirectionWithSameUuidCannotSupplyTheQuote() throws Exception {
        JSONObject message = target(false, "text");
        assertThrows(SecurityException.class, () -> ReplyTargetPolicy.resolve(new ReplyReference(ID, A, B), A, B, message, NOW));
    }

    @Test public void thirdPartyOriginalOrReferenceCannotCrossConversations() throws Exception {
        JSONObject unrelated = target(true, "text").put("to", C);
        assertThrows(SecurityException.class, () -> ReplyTargetPolicy.select(A, B, unrelated, NOW));
        assertThrows(SecurityException.class, () -> ReplyTargetPolicy.resolve(new ReplyReference(ID, A, C), A, B, null, NOW));
    }

    @Test public void persistedPeerAndDirectionMustMatchTheAuthenticatedTuple() throws Exception {
        JSONObject peerMismatch = target(true, "text").put("peer", C);
        JSONObject directionMismatch = target(true, "text").put("outgoing", false);
        assertThrows(SecurityException.class, () -> ReplyTargetPolicy.select(A, B, peerMismatch, NOW));
        assertThrows(SecurityException.class, () -> ReplyTargetPolicy.select(A, B, directionMismatch, NOW));
    }

    @Test public void booleanDirectionNeverUsesJsonCoercion() throws Exception {
        for (Object value : new Object[]{"true", "false", 1, JSONObject.NULL}) {
            JSONObject message = target(true, "text").put("outgoing", value);
            assertThrows(SecurityException.class, () -> ReplyTargetPolicy.select(A, B, message, NOW));
        }
    }

    @Test public void restrictedContentAndControlEventsNeverBecomeReplyTargets() throws Exception {
        for (String kind : new String[]{"restricted", "location", "call", "receipt", "device-roster", "device-grant", "future-kind"}) {
            JSONObject message = target(true, kind);
            assertThrows(SecurityException.class, () -> ReplyTargetPolicy.select(A, B, message, NOW));
            assertThrows(SecurityException.class, () -> ReplyTargetPolicy.resolve(new ReplyReference(ID, A, B), A, B, message, NOW));
        }
    }

    @Test public void logicalFanoutIsExplicitlyUnsupportedRatherThanGuessed() throws Exception {
        JSONObject message = target(true, "text").put("v", 2).put("logicalId", OTHER)
            .put("logicalFrom", A).put("logicalTo", B);
        assertThrows(SecurityException.class, () -> ReplyTargetPolicy.select(A, B, message, NOW));
    }

    @Test public void malformedTimestampsAreRejectedInsteadOfCoerced() throws Exception {
        for (Object value : new Object[]{"1500", 1500.0, true, JSONObject.NULL, -1L, 899L, 900L}) {
            JSONObject message = target(true, "text").put("expires", value);
            assertThrows(SecurityException.class, () -> ReplyTargetPolicy.select(A, B, message, NOW));
        }
        JSONObject negativeCreated = target(true, "text").put("created", -1);
        assertThrows(SecurityException.class, () -> ReplyTargetPolicy.select(A, B, negativeCreated, NOW));
    }

    @Test public void invalidContextAndClockAreRejectedEvenWhenOriginalIsAbsent() {
        ReplyReference ref = new ReplyReference(ID, A, B);
        assertThrows(SecurityException.class, () -> ReplyTargetPolicy.resolve(null, A, B, null, NOW));
        assertThrows(SecurityException.class, () -> ReplyTargetPolicy.resolve(ref, A, A, null, NOW));
        assertThrows(SecurityException.class, () -> ReplyTargetPolicy.resolve(ref, null, B, null, NOW));
        assertThrows(SecurityException.class, () -> ReplyTargetPolicy.resolve(ref, A, B, null, -1));
    }

    @Test public void missingMetadataDoesNotCreateALocalReference() throws Exception {
        for (String field : new String[]{"v", "id", "from", "to", "peer", "outgoing", "kind", "created", "expires"}) {
            JSONObject message = target(true, "text"); message.remove(field);
            assertThrows(SecurityException.class, () -> ReplyTargetPolicy.select(A, B, message, NOW));
        }
    }
}
