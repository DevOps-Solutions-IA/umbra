package app.umbra.messaging.reply;

import app.umbra.core.Bytes;
import app.umbra.crypto.Engine;
import app.umbra.protocol.Wire;
import org.json.JSONObject;

/**
 * Isolated candidate decoder for a per-device v1 reply inside Signal ciphertext.
 * NOT registered in Wire/Engine. This validates structure and envelope bindings,
 * not decryption, authorization, peer support, original existence or authorship.
 * Callers must already have authenticated/decrypted the envelope and must still
 * authorize access and resolve the original from live local records.
 */
public final class ReplyPayload {
    public static final int MAX_TEXT_BYTES = 16_000;
    // Worst-case JSON escaping of a 16 KiB text plus fixed metadata, below Padding.MAX_CLEAR.
    public static final int MAX_WIRE_BYTES = 100_000;
    private final String id, from, to, text;
    private final long created, createdMs, expires;
    private final ReplyReference replyTo;

    private ReplyPayload(String id, String from, String to, long created, long createdMs,
                         long expires, String text, ReplyReference replyTo) {
        this.id=id; this.from=from; this.to=to; this.created=created;
        this.createdMs=createdMs; this.expires=expires; this.text=text; this.replyTo=replyTo;
    }

    /**
     * Input is plaintext already decrypted by the caller; this method does NOT decrypt.
     * expectedRecipient must be the local identity, not a value taken from untrusted content.
     * The caller owns/wipes its plaintext buffer. No input JSON object is retained.
     */
    public static ReplyPayload decode(byte[] decrypted, JSONObject envelope,
                                      String expectedRecipient, long nowSeconds) {
        try {
            // Keep every subsequent arithmetic operation within its signed long range.
            if (nowSeconds<0 || nowSeconds>Long.MAX_VALUE/1000-Engine.MAX_TTL-300) throw invalid();
            Wire.identity(expectedRecipient);
            Wire.envelope(envelope,expectedRecipient,nowSeconds,Engine.MAX_TTL);
            JSONObject content=Wire.parse(decrypted,MAX_WIRE_BYTES);
            Wire.fields(content,"v","id","from","to","created","createdMs","expires","kind","text","replyTo");
            if (Wire.integer(content,"v")!=1 || !"reply".equals(Wire.string(content,"kind",16))) throw invalid();
            String id=Wire.uuid(Wire.string(content,"id",36));
            String from=Wire.identity(Wire.string(content,"from",64));
            String to=Wire.identity(Wire.string(content,"to",64));
            if (!id.equals(Wire.string(envelope,"id",36)) || !from.equals(Wire.string(envelope,"from",64))
                    || !to.equals(expectedRecipient) || !to.equals(Wire.string(envelope,"to",64))) throw invalid();
            long created=Wire.integer(content,"created"), ms=Wire.integer(content,"createdMs");
            long expires=Wire.integer(content,"expires");
            if (created<0 || created<nowSeconds-Engine.MAX_TTL || created>nowSeconds+300
                    || expires!=Wire.integer(envelope,"expires") || expires<=nowSeconds
                    || expires<=created || expires-created>Engine.MAX_TTL || ms<0
                    || Math.abs(ms/1000-created)>1) throw invalid();
            String text=Wire.string(content,"text",MAX_TEXT_BYTES);
            if (text.trim().isEmpty()) throw invalid();
            byte[] utf8=Bytes.utf8(text);
            try { if (utf8.length>MAX_TEXT_BYTES) throw invalid(); }
            finally { java.util.Arrays.fill(utf8,(byte)0); }
            if (!(content.get("replyTo") instanceof JSONObject reference)) throw invalid();
            ReplyReference reply=ReplyReference.fromJson(reference);
            reply.requireConversation(from,to);
            // Identical UUIDs in the opposite direction are distinct messages.
            if (reply.id().equals(id) && reply.from().equals(from)) throw invalid();
            return new ReplyPayload(id,from,to,created,ms,expires,text,reply);
        } catch (Exception rejected) {
            // JSON/UUID exceptions can include input. Do not expose text, references or causes.
            throw invalid();
        }
    }

    public String id() { return id; }
    public String from() { return from; }
    public String to() { return to; }
    public long created() { return created; }
    public long createdMs() { return createdMs; }
    public long expires() { return expires; }
    public String text() { return text; }
    public ReplyReference replyTo() { return replyTo; }
    private static SecurityException invalid() { return new SecurityException("Invalid reply payload"); }
    @Override public String toString() { return "ReplyPayload[redacted]"; }
}
