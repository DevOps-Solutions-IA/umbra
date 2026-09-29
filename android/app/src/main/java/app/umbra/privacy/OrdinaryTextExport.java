package app.umbra.privacy;

import app.umbra.core.Bytes;
import app.umbra.crypto.Engine;
import app.umbra.data.Records;
import app.umbra.protocol.Wire;
import org.json.JSONObject;

/** Explicit ordinary-text export consent, bound to the vault generation before a dialog opens. */
public final class OrdinaryTextExport {
    private OrdinaryTextExport() {}
    public static final class Review {
        private final Engine engine;
        private final String peer, id;
        private final Records.Work<Void> authorization;
        private final long created = System.nanoTime();
        private boolean used;
        private Review(Engine engine, String peer, String id) throws Exception {
            this.engine=engine; this.peer=peer; this.id=Wire.uuid(id);
            authorization=engine.deliveryAuthorization(peer);
        }
        private void check() throws Exception {
            authorization.run();
            long elapsed=System.nanoTime()-created;
            if(used || elapsed<0 || elapsed>=60_000_000_000L)
                throw new PrivacyException(PrivacyException.Code.CONSENT_REQUIRED);
        }
        @Override public String toString() { return "TextExportReview[redacted]"; }
    }
    /** Does not export plaintext; call before requesting owner confirmation. */
    public static Review review(Engine engine, String peer, String messageId) throws Exception {
        Review review=new Review(engine,peer,messageId);
        review.check(); select(review); review.check(); return review;
    }
    private static String select(Review review) throws Exception {
        JSONObject message=review.engine.get("message",review.id);
        if(message==null)message=review.engine.get("message",review.peer+":"+review.id);
        if(message==null || message.optLong("expires")<=Bytes.now() ||
                !review.peer.equals(message.optString("peer")) || !"text".equals(message.optString("kind")))
            throw new PrivacyException(PrivacyException.Code.RESTRICTED_EXPORT);
        String text=message.getString("text");
        if(Bytes.utf8(text).length>16000)throw new PrivacyException(PrivacyException.Code.LIMIT_EXCEEDED);
        return text;
    }
    /** Export is irreversible after the sink accepts it. No rollback or recall is claimed. */
    public static void export(Review review, boolean confirmed, Sink sink) throws Exception {
        if(review==null || !confirmed)throw new PrivacyException(PrivacyException.Code.CONSENT_REQUIRED);
        synchronized(review) {
            review.check(); String text=select(review); review.check();
            // A sink failure can occur after an external write: do not replay the consent.
            review.used=true; sink.write(text);
        }
    }
    public interface Sink { void write(String text) throws Exception; }
}
