package app.umbra.messaging.reply;

import app.umbra.core.Bytes;
import app.umbra.protocol.Wire;
import org.json.JSONObject;

/**
 * Bounded reference to one original message between two device identities.
 * This is metadata, not authorization or proof that the referenced message exists.
 * A future transport integration must include it inside authenticated ciphertext.
 */
public record ReplyReference(String id, String from, String to) {
    public static final int MAX_WIRE_BYTES = 512;

    public ReplyReference {
        try {
            Wire.uuid(id);
            Wire.identity(from);
            Wire.identity(to);
            if (from.equals(to)) throw invalid();
        } catch (Exception rejected) {
            // UUID parser exceptions can contain their input. Do not retain that cause.
            throw invalid();
        }
    }

    /** Raw input boundary: existing strict JSON parser rejects duplicates and coercions. */
    public static ReplyReference parse(byte[] bytes) {
        try {
            return fromJson(Wire.parse(bytes, MAX_WIRE_BYTES));
        } catch (Exception rejected) {
            throw invalid();
        }
    }

    /** For a nested object already decoded by Wire.parse, not by a permissive JSON parser. */
    public static ReplyReference fromJson(JSONObject value) {
        try {
            Wire.fields(value, "v", "id", "from", "to");
            if (Wire.integer(value, "v") != 1) throw invalid();
            return new ReplyReference(Wire.string(value, "id", 36),
                Wire.string(value, "from", 64), Wire.string(value, "to", 64));
        } catch (Exception rejected) {
            throw invalid();
        }
    }

    /** Explicit protocol serialization, never a log representation. No preview is included. */
    public JSONObject toJson() {
        try {
            return new JSONObject().put("v", 1).put("id", id).put("from", from).put("to", to);
        } catch (Exception rejected) {
            throw invalid();
        }
    }

    public byte[] encode() { return Bytes.utf8(toJson().toString()); }

    /** The original may have either direction, but neither endpoint may be substituted. */
    public void requireConversation(String self, String peer) {
        try {
            Wire.identity(self);
            Wire.identity(peer);
            if (self.equals(peer) || !((from.equals(self) && to.equals(peer))
                    || (from.equals(peer) && to.equals(self)))) throw invalid();
        } catch (Exception rejected) {
            throw invalid();
        }
    }

    private static SecurityException invalid() { return new SecurityException("Invalid reply reference"); }

    @Override public String toString() { return "ReplyReference[redacted]"; }
}
