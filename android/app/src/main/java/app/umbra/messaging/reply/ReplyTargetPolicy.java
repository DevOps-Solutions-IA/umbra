package app.umbra.messaging.reply;

import app.umbra.protocol.Wire;
import java.util.Set;
import org.json.JSONObject;

/**
 * Side-effect-free metadata rules for ordinary, per-device message reply targets.
 * The caller must fetch the original under current vault/peer authorization and
 * revalidate it in the eventual send transaction. This helper grants no access,
 * performs no network/storage operation and never reads or copies quoted content.
 */
public final class ReplyTargetPolicy {
    public enum Availability { AVAILABLE, UNAVAILABLE }
    private static final Set<String> ORDINARY_KINDS = Set.of("text", "file", "reply");

    private ReplyTargetPolicy() {}

    /** Select only a live original in the current authorized conversation. */
    public static ReplyReference select(String self, String peer, JSONObject original, long nowSeconds) {
        requireContext(self, peer, nowSeconds);
        if (original == null) throw unavailable();
        Target target = inspect(self, peer, original);
        if (target.expires() <= nowSeconds) throw unavailable();
        return target.reference();
    }

    /**
     * Missing/deleted/expired originals cannot supply a preview. A different
     * original is invalid, not an invitation to search another chat or endpoint.
     * No remote text supplied as a quotation is accepted by this API.
     */
    public static Availability resolve(ReplyReference reference, String self, String peer,
                                       JSONObject original, long nowSeconds) {
        requireContext(self, peer, nowSeconds);
        if (reference == null) throw invalid();
        reference.requireConversation(self, peer);
        if (original == null) return Availability.UNAVAILABLE;
        Target target = inspect(self, peer, original);
        if (!reference.equals(target.reference())) throw invalid();
        return target.expires() > nowSeconds ? Availability.AVAILABLE : Availability.UNAVAILABLE;
    }

    private static Target inspect(String self, String peer, JSONObject original) {
        try {
            // Logical fanout needs its own root/recipient contract after Y02.
            // Reject v2 here instead of guessing which device's UUID was meant.
            if (Wire.integer(original, "v") != 1) throw invalid();
            ReplyReference reference = new ReplyReference(Wire.string(original, "id", 36),
                Wire.string(original, "from", 64), Wire.string(original, "to", 64));
            reference.requireConversation(self, peer);
            if (!peer.equals(Wire.string(original, "peer", 64))) throw invalid();
            if (!(original.get("outgoing") instanceof Boolean outgoing)) throw invalid();
            if (outgoing != reference.from().equals(self)) throw invalid();
            if (!ORDINARY_KINDS.contains(Wire.string(original, "kind", 16))) throw invalid();
            long created = Wire.integer(original, "created"), expires = Wire.integer(original, "expires");
            if (created < 0 || expires <= created) throw invalid();
            return new Target(reference, expires);
        } catch (Exception rejected) {
            // Do not expose record contents through JSON/UUID exception messages.
            throw invalid();
        }
    }

    private static void requireContext(String self, String peer, long nowSeconds) {
        try {
            Wire.identity(self);
            Wire.identity(peer);
            if (self.equals(peer) || nowSeconds < 0) throw invalid();
        } catch (Exception rejected) {
            throw invalid();
        }
    }

    private record Target(ReplyReference reference, long expires) {}
    private static SecurityException invalid() { return new SecurityException("Invalid reply target metadata"); }
    private static SecurityException unavailable() { return new SecurityException("Reply target unavailable"); }
}
