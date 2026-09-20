package app.umbra.pairing;

import app.umbra.core.Bytes;
import app.umbra.crypto.Engine;
import app.umbra.crypto.SignalStore;
import app.umbra.data.Records;
import app.umbra.protocol.Wire;
import java.util.Base64;
import org.json.JSONObject;
import org.signal.libsignal.protocol.ecc.ECPublicKey;

/** Signed enrollment transcripts; message encryption remains exclusively libsignal.
 * All entry points require the same unlocked Records implementation used by Engine.
 */
public final class PairingService {
    public static final long MAX_TTL = 86400;
    private final Records records;
    private final Engine engine;
    private final SignalStore signal;
    public PairingService(Records records) {
        this.records = records; engine = new Engine(records); signal = new SignalStore(records);
    }
    private JSONObject read(String bucket, String id) throws Exception { return engine.get(bucket, id); }
    private void save(String bucket, String id, JSONObject value) { records.put(bucket, id, Bytes.utf8(value.toString())); }
    public static String token(String value) {
        if (!value.matches("[A-Za-z0-9_-]{43}") || decode(value, 32).length != 32)
            throw new SecurityException("Invalid pairing capability");
        return value;
    }
    private static String encode(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static byte[] decode(String value, int max) {
        if (value.length() > (max * 4L + 2) / 3) throw new SecurityException("Pairing field too large");
        byte[] result = Base64.getUrlDecoder().decode(value);
        if (result.length > max || !encode(result).equals(value)) throw new SecurityException("Noncanonical pairing encoding");
        return result;
    }
    private String publicKey() { return Bytes.b64(signal.getIdentityKeyPair().getPublicKey().serialize()); }
    private String signed(String kind, String... fields) {
        String body = "UMBRA-PAIR-" + kind.toUpperCase(java.util.Locale.ROOT) + "-1\n" + String.join("\n", fields) + "\n";
        byte[] raw = Bytes.utf8(body);
        return "umbra:" + kind + ":1:" + encode(raw) + "." + encode(signal.getIdentityKeyPair().getPrivateKey().calculateSignature(raw));
    }
    private static String[] parse(String value, String kind, int count, int keyIndex) throws Exception {
        if (value == null || value.length() > (kind.equals("invite") ? 1024 : 24000)) throw new SecurityException("Pairing payload too large");
        String prefix = "umbra:" + kind + ":1:";
        if (!value.startsWith(prefix)) throw new SecurityException("Unsupported pairing version");
        String[] encoded = value.substring(prefix.length()).split("\\.", -1);
        if (encoded.length != 2) throw new SecurityException("Invalid pairing envelope");
        byte[] raw = decode(encoded[0], 18000), signature = decode(encoded[1], 64);
        String body = Bytes.text(raw);
        if (!body.matches("[\\x20-\\x7E\\n]+")) throw new SecurityException("Invalid pairing characters");
        String[] lines = body.split("\n", -1);
        if (lines.length != count + 2 || !lines[count + 1].isEmpty() ||
            !lines[0].equals("UMBRA-PAIR-" + kind.toUpperCase(java.util.Locale.ROOT) + "-1"))
            throw new SecurityException("Noncanonical pairing body");
        byte[] key = Bytes.unb64(lines[keyIndex], 44);
        if (key.length != 33 || signature.length != 64 || !new ECPublicKey(key).verifySignature(raw, signature))
            throw new SecurityException("Invalid pairing signature");
        return lines;
    }
    private static long number(String value) {
        if (!value.matches("[1-9][0-9]{0,11}")) throw new SecurityException("Noncanonical pairing timestamp");
        return Long.parseLong(value);
    }
    private static String[] invitation(String value) throws Exception {
        String[] f = parse(value, "invite", 7, 2);
        token(f[1]); token(f[3]); token(f[4]);
        long created = number(f[5]), expires = number(f[6]), now = Bytes.now();
        if (!f[7].equals("1") || created > now + 300 || expires <= now || expires <= created || expires - created > MAX_TTL)
            throw new SecurityException("Expired or invalid invitation");
        return f;
    }
    public static String invitationId(String value) throws Exception { return invitation(value)[1]; }
    public static String consumeToken(String value) throws Exception { return invitation(value)[3]; }
    public static long expires(String value) throws Exception { return number(invitation(value)[6]); }
    private void prune(String bucket) throws Exception {
        for (String id : records.keys(bucket)) if (read(bucket, id).getLong("expires") <= Bytes.now()) records.remove(bucket, id);
        if (records.keys(bucket).size() >= 32) throw new IllegalStateException("Pairing capacity reached");
    }
    public String createInvitation(long ttl) throws Exception {
        if (ttl < 60 || ttl > MAX_TTL) throw new IllegalArgumentException("Invalid invitation lifetime");
        return records.transaction(() -> {
            if (!engine.initialized()) throw new SecurityException("Identity required");
            prune("pairing-issued");
            String id = Bytes.token(); long now = Bytes.now();
            String invite = signed("invite", id, publicKey(), Bytes.token(), Bytes.token(), "" + now, "" + (now + ttl), "1");
            save("pairing-issued", id, new JSONObject().put("invite", invite).put("revoke", Bytes.token())
                .put("expires", now + ttl).put("state", "ISSUED"));
            return invite;
        });
    }
    /** Private owner registration material; never include this object in a QR or invitation. */
    public JSONObject relayRegistration(String invite) throws Exception {
        return records.transaction(() -> {
            String[] f = invitation(invite); JSONObject row = read("pairing-issued", f[1]);
            if (row == null || !row.getString("invite").equals(invite) || !row.getString("state").equals("ISSUED"))
                throw new SecurityException("Invitation unavailable");
            return new JSONObject().put("id", f[1]).put("consume_token", f[3]).put("revoke_token", row.getString("revoke"))
                .put("expires", number(f[6]));
        });
    }
    /** Revoke locally first; return the owner-only capability for optional relay revocation. */
    public String revoke(String id) throws Exception {
        token(id);
        return records.transaction(() -> {
            JSONObject row = read("pairing-issued", id);
            if (row == null) throw new SecurityException("Unknown invitation");
            row.put("state", "REVOKED"); row.remove("ack"); save("pairing-issued", id, row); return row.getString("revoke");
        });
    }
    public int revokeUnused() throws Exception {
        return records.transaction(() -> {
            int count = 0;
            for (String id : records.keys("pairing-issued")) {
                JSONObject row = read("pairing-issued", id);
                if (row.getString("state").equals("ISSUED")) {
                    row.put("state", "REVOKED"); save("pairing-issued", id, row); count++;
                }
            }
            return count;
        });
    }
    public String request(String invite) throws Exception {
        String[] f = invitation(invite); String peer = Bytes.identity(Bytes.unb64(f[2]));
        return records.transaction(() -> {
            invitation(invite); // Recheck after waiting for the vault transaction lock.
            if (!engine.initialized() || peer.equals(engine.id())) throw new SecurityException("Invalid invitation peer");
            JSONObject old = read("pairing-pending", f[1]);
            if (old != null) {
                if (!old.getString("invite").equals(invite)) throw new SecurityException("Invitation substitution");
                return old.getString("request");
            }
            prune("pairing-pending");
            String request = signed("request", invite, publicKey(), Bytes.token(), Bytes.b64(Bytes.utf8(engine.createCard().toString())));
            save("pairing-pending", f[1], new JSONObject().put("invite", invite).put("request", request)
                .put("peer", peer).put("expires", number(f[6])).put("state", "PENDING"));
            return request;
        });
    }
    private static JSONObject card(String encoded, String key) throws Exception {
        JSONObject card = Wire.parse(Bytes.unb64(encoded, 18000), 12000);
        JSONObject body = Wire.parse(Bytes.unb64(Wire.string(card, "body", 16000)), 12000);
        if (!Wire.string(body, "identity", 44).equals(key)) throw new SecurityException("Pairing card key substitution");
        return card;
    }
    public String accept(String request) throws Exception {
        String[] f = parse(request, "request", 4, 2), invite = invitation(f[1]); token(f[3]);
        String digest = Bytes.sha256(Bytes.utf8(request));
        JSONObject card = card(f[4], f[2]);
        return records.transaction(() -> {
            JSONObject row = read("pairing-issued", invite[1]);
            if (row == null || row.getLong("expires") <= Bytes.now() || !row.getString("invite").equals(f[1]) || !publicKey().equals(invite[2]) || row.getString("state").equals("REVOKED"))
                throw new SecurityException("Invitation unavailable");
            if (row.getString("state").equals("CONSUMED")) {
                if (!row.getString("requestHash").equals(digest)) throw new SecurityException("Invitation already consumed");
                return row.getString("ack");
            }
            String peer = engine.importCard(card);
            String ack = signed("ack", invite[1], digest, peer, publicKey(), Bytes.b64(Bytes.utf8(engine.createCard().toString())));
            row.put("state", "CONSUMED").put("requestHash", digest).put("ack", ack);
            save("pairing-issued", invite[1], row); return ack;
        });
    }
    public String complete(String ack) throws Exception {
        String[] f = parse(ack, "ack", 5, 4); token(f[1]); Wire.identity(f[2]); Wire.identity(f[3]);
        JSONObject card = card(f[5], f[4]);
        return records.transaction(() -> {
            JSONObject row = read("pairing-pending", f[1]);
            if (row == null || row.getLong("expires") <= Bytes.now() || !f[3].equals(engine.id()) ||
                !row.getString("peer").equals(Bytes.identity(Bytes.unb64(f[4]))) ||
                !f[2].equals(Bytes.sha256(Bytes.utf8(row.getString("request"))))) throw new SecurityException("Unexpected pairing acknowledgement");
            String peer = engine.importCard(card);
            row.put("state", "COMPLETE"); save("pairing-pending", f[1], row); return peer;
        });
    }
}
