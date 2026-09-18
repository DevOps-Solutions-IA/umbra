package app.umbra.crypto;

import app.umbra.core.Bytes;
import app.umbra.core.Padding;
import app.umbra.core.LocalCapacityException;
import app.umbra.core.RetryPolicy;
import app.umbra.protocol.Wire;
import app.umbra.data.Records;
import org.json.JSONObject;
import org.signal.libsignal.protocol.*;
import org.signal.libsignal.protocol.ecc.*;
import org.signal.libsignal.protocol.kem.*;
import org.signal.libsignal.protocol.message.*;
import org.signal.libsignal.protocol.state.*;
import java.security.SecureRandom;
import java.util.*;

/** Application orchestration, not a custom cryptographic protocol. All E2EE is libsignal. */
public final class Engine {
    public static final long MAX_TTL = 7 * 86400L;
    public static final int MAX_ATTACHMENT = 262_144;
    public static final int MAX_OUTBOX = 256, MAX_MESSAGES = 4096, MAX_SEEN = 8192;
    private final Records db;
    private final SignalStore signal;
    public Engine(Records records) { db = records; signal = new SignalStore(records); }
    public JSONObject get(String bucket, String key) throws Exception {
        byte[] value = db.get(bucket, key); return value == null ? null : new JSONObject(Bytes.text(value));
    }
    private void put(String bucket, String key, JSONObject value) { db.put(bucket, key, Bytes.utf8(value.toString())); }
    public boolean initialized() { return db.get("meta", "identity") != null; }
    public void initialize(String alias) throws Exception {
        if (alias.trim().isEmpty() || alias.length() > 40 || alias.codePoints().anyMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT)) throw new IllegalArgumentException("Alias de 1 a 40 caracteres");
        db.transaction(() -> {
            if (initialized()) throw new IllegalStateException("Identity already exists");
            IdentityKeyPair identity = IdentityKeyPair.generate();
            db.put("meta", "identity", identity.serialize());
            db.put("meta", "registration", Bytes.utf8("" + (new SecureRandom().nextInt(16380) + 1)));
            put("meta", "profile", new JSONObject().put("alias", alias).put("id", Bytes.identity(identity.getPublicKey().serialize()))
                .put("box", UUID.randomUUID().toString()).put("read", Bytes.token()).put("write", Bytes.token())
                .put("relay", "").put("registered", false).put("online", false));
            return null;
        });
    }
    public JSONObject profile() throws Exception { return get("meta", "profile"); }
    public String id() throws Exception { return profile().getString("id"); }
    public void updateRelay(String url, boolean registered) throws Exception {
        db.transaction(() -> { JSONObject p = profile(); p.put("relay", url).put("registered", registered).put("online", registered); put("meta", "profile", p); return null; });
    }
    public void setOnline(boolean enabled) throws Exception {
        db.transaction(() -> { JSONObject p = profile(); p.put("online", enabled); put("meta", "profile", p); return null; });
    }
    public JSONObject createCard() throws Exception {
        return db.transaction(() -> {
            // Bound unused key material: exporting indefinitely cannot fill storage without limit.
            if (db.keys("key-expiry").size() >= 200) throw new IllegalStateException("Demasiadas invitaciones pendientes; espere su vencimiento");
            IdentityKeyPair identity = signal.getIdentityKeyPair();
            int keyId;
            do { keyId = Bytes.positiveId(); } while (signal.containsSignedPreKey(keyId));
            ECKeyPair oneTime = ECKeyPair.generate(), signed = ECKeyPair.generate();
            KEMKeyPair kem = KEMKeyPair.generate(KEMKeyType.KYBER_1024);
            byte[] ecSignature = identity.getPrivateKey().calculateSignature(signed.getPublicKey().serialize());
            byte[] kemSignature = identity.getPrivateKey().calculateSignature(kem.getPublicKey().serialize());
            signal.storePreKey(keyId, new PreKeyRecord(keyId, oneTime));
            signal.storeSignedPreKey(keyId, new SignedPreKeyRecord(keyId, System.currentTimeMillis(), signed, ecSignature));
            signal.storeKyberPreKey(keyId, new KyberPreKeyRecord(keyId, System.currentTimeMillis(), kem, kemSignature));
            db.put("key-expiry", "" + keyId, Bytes.utf8("" + (Bytes.now() + MAX_TTL)));
            JSONObject me = profile();
            JSONObject body = new JSONObject().put("v", 1).put("id", me.getString("id")).put("alias", me.getString("alias"))
                .put("identity", Bytes.b64(identity.getPublicKey().serialize())).put("registration", signal.getLocalRegistrationId())
                .put("keyId", keyId).put("oneTime", Bytes.b64(oneTime.getPublicKey().serialize()))
                .put("signed", Bytes.b64(signed.getPublicKey().serialize())).put("signedSig", Bytes.b64(ecSignature))
                .put("kem", Bytes.b64(kem.getPublicKey().serialize())).put("kemSig", Bytes.b64(kemSignature))
                .put("box", me.getString("box")).put("write", me.getString("write"))
                .put("created", Bytes.now()).put("expires", Bytes.now() + MAX_TTL);
            byte[] raw = Bytes.utf8(body.toString());
            return new JSONObject().put("format", "umbra-contact-v1").put("body", Bytes.b64(raw))
                .put("signature", Bytes.b64(identity.getPrivateKey().calculateSignature(raw)));
        });
    }
    public String importCard(JSONObject card) throws Exception {
        return db.transaction(() -> {
            if (!"umbra-contact-v1".equals(card.getString("format")) || card.toString().length() > 16_000)
                throw new SecurityException("Tarjeta no compatible");
            byte[] raw = Bytes.unb64(card.getString("body"));
            JSONObject body = Wire.parse(raw, 12_000);
            Wire.fields(card, "format", "body", "signature");
            Wire.fields(body, "v", "id", "alias", "identity", "registration", "keyId", "oneTime", "signed", "signedSig", "kem", "kemSig", "box", "write", "created", "expires");
            for (String f : new String[]{"v", "registration", "keyId", "created", "expires"}) Wire.integer(body, f);
            for (String f : new String[]{"id", "alias", "identity", "oneTime", "signed", "signedSig", "kem", "kemSig", "box", "write"}) Wire.string(body, f, 4096);
            if (body.getInt("v") != 1) throw new SecurityException("Versión de contacto no compatible");
            String peer = body.getString("id");
            ECPublicKey key = new ECPublicKey(Bytes.unb64(body.getString("identity")));
            if (!peer.equals(Bytes.identity(key.serialize())) || peer.equals(id())) throw new SecurityException("Identidad inválida");
            if (!key.verifySignature(raw, Bytes.unb64(card.getString("signature")))) throw new SecurityException("Firma de contacto inválida");
            if (!key.verifySignature(Bytes.unb64(body.getString("signed")), Bytes.unb64(body.getString("signedSig"))) ||
                !key.verifySignature(Bytes.unb64(body.getString("kem")), Bytes.unb64(body.getString("kemSig"))))
                throw new SecurityException("Firma de preclave inválida");
            long now = Bytes.now(), expiry = body.getLong("expires"), created = body.getLong("created");
            if (expiry <= now || created > now + 300 || expiry > created + MAX_TTL || created < now - MAX_TTL)
                throw new SecurityException("Invitación vencida o reloj incorrecto");
            if (body.getInt("registration") < 1 || body.getInt("registration") > 16380 || body.getInt("keyId") < 1)
                throw new SecurityException("Preclaves inválidas");
            Wire.uuid(body.getString("box"));
            if (!body.getString("write").matches("[A-Za-z0-9_-]{43}")) throw new SecurityException("Buzón inválido");
            if (body.getString("alias").trim().isEmpty() || body.getString("alias").length() > 40 || body.getString("alias").codePoints().anyMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT)) throw new SecurityException("Alias inválido");
            // Parse key encodings now rather than deferring failures until first send.
            new ECPublicKey(Bytes.unb64(body.getString("oneTime")));
            new KEMPublicKey(Bytes.unb64(body.getString("kem")));
            JSONObject old = get("contact", peer);
            if (old == null && db.keys("contact").size() >= 100) throw new IllegalStateException("Límite de contactos alcanzado");
            if (old != null && (old.getJSONObject("card").getLong("created") > created ||
                (old.getJSONObject("card").getLong("created") == created &&
                !old.getJSONObject("card").getString("identity").equals(body.getString("identity")))))
                throw new SecurityException("No se permite retroceder una tarjeta de contacto");
            put("contact", peer, new JSONObject().put("id", peer).put("card", body)
                .put("verified", old != null && old.optBoolean("verified"))
                .put("blocked", old != null && old.optBoolean("blocked")));
            return peer;
        });
    }
    public void verify(String peer, String typedCode) throws Exception {
        db.transaction(() -> {
            String expected = Bytes.safetyCode(id(), peer);
            String entered = typedCode.toLowerCase(Locale.ROOT).replaceAll("\\s", "");
            if (!Bytes.equal(Bytes.utf8(expected), Bytes.utf8(entered))) throw new SecurityException("El código no coincide");
            JSONObject contact = requiredContact(peer, false);
            signal.pin(peer, new IdentityKey(new ECPublicKey(Bytes.unb64(contact.getJSONObject("card").getString("identity")))));
            contact.put("verified", true); put("contact", peer, contact); return null;
        });
    }
    public void block(String peer, boolean blocked) throws Exception {
        db.transaction(() -> {
            JSONObject contact = get("contact", peer);
            if (contact == null) throw new IllegalArgumentException("Unknown contact");
            contact.put("blocked", blocked); put("contact", peer, contact);
            if (blocked) for (String k : db.keys("outbox")) if (get("outbox", k).getString("peer").equals(peer)) db.remove("outbox", k);
            return null;
        });
    }
    private JSONObject requiredContact(String peer, boolean verified) throws Exception {
        JSONObject contact = get("contact", peer);
        if (contact == null || contact.optBoolean("blocked")) throw new SecurityException("Contacto desconocido o bloqueado");
        if (verified && !contact.optBoolean("verified")) throw new SecurityException("Verifique el código de seguridad antes de conversar");
        return contact;
    }
    public List<JSONObject> contacts() throws Exception {
        List<JSONObject> result = new ArrayList<>();
        for (String k : db.keys("contact")) result.add(get("contact", k));
        result.sort(Comparator.comparing(c -> c.optJSONObject("card").optString("alias").toLowerCase(Locale.ROOT)));
        return result;
    }
    public List<JSONObject> messages(String peer) throws Exception {
        List<JSONObject> result = new ArrayList<>();
        for (String k : db.keys("message")) {
            JSONObject message = get("message", k);
            if (peer.equals(message.getString("peer")) && message.getLong("expires") > Bytes.now()) result.add(message);
        }
        result.sort(Comparator.comparingLong(m -> m.optLong("createdMs", m.optLong("created") * 1000)));
        return result;
    }
    public String sendText(String peer, String text, long ttl) throws Exception {
        if (text.trim().isEmpty() || Bytes.utf8(text).length > 16_000) throw new IllegalArgumentException("Mensaje vacío o demasiado largo");
        return send(peer, new JSONObject().put("kind", "text").put("text", text), ttl);
    }
    public String sendFile(String peer, String name, byte[] content, long ttl) throws Exception {
        if (content.length < 1 || content.length > MAX_ATTACHMENT) throw new IllegalArgumentException("Adjunto máximo: 256 KiB");
        name = app.umbra.core.FileNames.sanitize(name);
        return send(peer, new JSONObject().put("kind", "file").put("name", name).put("data", Bytes.b64(content)), ttl);
    }
    private String send(String peer, JSONObject content, long ttl) throws Exception {
        if (ttl < 60 || ttl > MAX_TTL) throw new IllegalArgumentException("Caducidad inválida");
        return db.transaction(() -> {
            requiredContact(peer, true);
            if (db.keys("outbox").size() >= 128 || db.keys("message").size() >= MAX_MESSAGES) throw new LocalCapacityException();
            JSONObject envelope = encrypt(peer, content, Bytes.now() + ttl);
            JSONObject message = new JSONObject(content.toString()).put("peer", peer).put("outgoing", true).put("status", "Pendiente");
            put("message", envelope.getString("id"), message);
            putOutbox(peer, envelope, false);
            return envelope.getString("id");
        });
    }
    private JSONObject encrypt(String peer, JSONObject content, long expiry) throws Exception {
        JSONObject contact = requiredContact(peer, true), card = contact.getJSONObject("card");
        SignalProtocolAddress local = new SignalProtocolAddress(id(), 1), remote = new SignalProtocolAddress(peer, 1);
        if (!signal.containsSession(remote)) {
            if (card.getLong("expires") <= Bytes.now()) throw new SecurityException("Actualice la invitación de este contacto");
            int k = card.getInt("keyId");
            PreKeyBundle bundle = new PreKeyBundle(card.getInt("registration"), 1, k,
                new ECPublicKey(Bytes.unb64(card.getString("oneTime"))), k,
                new ECPublicKey(Bytes.unb64(card.getString("signed"))), Bytes.unb64(card.getString("signedSig")),
                new IdentityKey(new ECPublicKey(Bytes.unb64(card.getString("identity")))), k,
                new KEMPublicKey(Bytes.unb64(card.getString("kem"))), Bytes.unb64(card.getString("kemSig")));
            new SessionBuilder(signal, remote, local).process(bundle);
        }
        String messageId = UUID.randomUUID().toString();
        content.put("createdMs", System.currentTimeMillis()).put("v", 1).put("id", messageId).put("from", id()).put("to", peer).put("created", Bytes.now()).put("expires", expiry);
        byte[] clear = Bytes.utf8(content.toString()), padded = Padding.pad(clear);
        CiphertextMessage encrypted;
        try { encrypted = new SessionCipher(signal, local, remote).encrypt(padded); }
        finally { Arrays.fill(clear, (byte) 0); Arrays.fill(padded, (byte) 0); }
        return new JSONObject().put("v", 1).put("id", messageId).put("from", id()).put("to", peer)
            .put("type", encrypted.getType()).put("ct", Bytes.b64(encrypted.serialize())).put("expires", expiry);
    }
    private static String digest(JSONObject envelope) throws Exception {
        return Bytes.sha256(Bytes.utf8(envelope.getInt("v") + "|" + envelope.getString("id") + "|" + envelope.getString("from") + "|" +
            envelope.getString("to") + "|" + envelope.getInt("type") + "|" + envelope.getLong("expires") + "|" + envelope.getString("ct")));
    }
    private void putOutbox(String peer, JSONObject envelope, boolean receipt) throws Exception {
        String messageId = envelope.getString("id");
        JSONObject existing = get("outbox", messageId);
        if (existing != null) {
            if (!digest(existing.getJSONObject("envelope")).equals(digest(envelope))) throw new SecurityException("Outbox substitution");
            return; // A duplicate must not reset backoff or change its immutable ciphertext.
        }
        if (db.keys("outbox").size() >= MAX_OUTBOX) throw new LocalCapacityException();
        byte[] raw = db.get("meta", "enqueue-sequence");
        long sequence = Math.addExact(raw == null ? 0 : Long.parseLong(Bytes.text(raw)), 1);
        db.put("meta", "enqueue-sequence", Bytes.utf8(Long.toString(sequence)));
        put("outbox", messageId, new JSONObject().put("peer", peer).put("envelope", envelope).put("receipt", receipt)
            .put("relayUploaded", false).put("lastBluetooth", 0).put("sequence", sequence)
            .put("relayAttempts", 0).put("nextRelay", 0));
    }
    /** Success means committed locally, not merely that a socket accepted bytes. */
    public void receive(JSONObject envelope) throws Exception {
        db.transaction(() -> {
            long now = Bytes.now();
            Wire.envelope(envelope, id(), now, MAX_TTL);
            String peer = envelope.getString("from"), messageId = envelope.getString("id");
            requiredContact(peer, true);
            long expiry = envelope.getLong("expires");
            String seenKey = peer + ":" + messageId, fingerprint = digest(envelope);
            JSONObject seen = get("seen", seenKey);
            if (seen != null) {
                if (!seen.getString("digest").equals(fingerprint)) throw new SecurityException("Message id collision");
                if (seen.has("receipt") && seen.getJSONObject("receipt").getLong("expires") > now)
                    putOutbox(peer, seen.getJSONObject("receipt"), true);
                return null;
            }
            if (db.keys("seen").size() >= MAX_SEEN) throw new LocalCapacityException();
            byte[] ciphertext = Bytes.unb64(envelope.getString("ct"));
            if (ciphertext.length > 720_000) throw new SecurityException("Ciphertext too large");
            SessionCipher cipher = new SessionCipher(signal, new SignalProtocolAddress(id(), 1), new SignalProtocolAddress(peer, 1));
            byte[] padded = switch (envelope.getInt("type")) {
                case CiphertextMessage.PREKEY_TYPE -> cipher.decrypt(new PreKeySignalMessage(ciphertext));
                case CiphertextMessage.WHISPER_TYPE -> cipher.decrypt(new SignalMessage(ciphertext));
                default -> throw new SecurityException("Unsupported ciphertext type");
            };
            byte[] raw = null; JSONObject content;
            try { raw = Padding.unpad(padded); content = Wire.parse(raw, Padding.MAX_CLEAR); }
            finally { Arrays.fill(padded, (byte) 0); if (raw != null) Arrays.fill(raw, (byte) 0); }
            Wire.content(content, envelope, now, MAX_TTL, MAX_ATTACHMENT);
            String kind = content.getString("kind");
            JSONObject seenValue = new JSONObject().put("digest", fingerprint).put("expires", now + MAX_TTL);
            if (kind.equals("receipt")) {
                String acknowledged = content.getString("ackFor");
                JSONObject sent = get("message", acknowledged);
                if (sent != null && sent.optBoolean("outgoing") && peer.equals(sent.getString("peer"))) {
                    sent.put("status", "Entregado"); put("message", acknowledged, sent); db.remove("outbox", acknowledged);
                }
            } else {
                if (kind.equals("text")) {
                    if (Bytes.utf8(content.getString("text")).length > 16_000) throw new SecurityException("Text too large");
                } else if (kind.equals("file")) {
                    if (Bytes.unb64(content.getString("data")).length > MAX_ATTACHMENT || content.getString("name").length() > 120)
                        throw new SecurityException("Attachment too large");
                } else throw new SecurityException("Unsupported content");
                if (db.keys("message").size() >= MAX_MESSAGES) throw new LocalCapacityException();
                put("message", peer + ":" + messageId, new JSONObject(content.toString()).put("peer", peer).put("outgoing", false).put("status", "Recibido"));
                JSONObject receipt = encrypt(peer, new JSONObject().put("kind", "receipt").put("ackFor", messageId), Math.min(expiry, now + 86400));
                putOutbox(peer, receipt, true); seenValue.put("receipt", receipt);
            }
            put("seen", seenKey, seenValue); return null;
        });
    }
    public List<JSONObject> outbox() throws Exception {
        List<JSONObject> result = new ArrayList<>();
        for (String k : db.keys("outbox")) result.add(get("outbox", k));
        // Order of encryption, not expiry: a short TTL must not overtake the initial pre-key message.
        result.sort(Comparator.comparingLong(o -> o.optLong("sequence", 0)));
        return result;
    }
    public JSONObject contact(String peer) throws Exception { return get("contact", peer); }
    public void transported(String messageId, boolean relay) throws Exception {
        db.transaction(() -> {
            JSONObject queued = get("outbox", messageId); if (queued == null) return null;
            if (queued.optBoolean("receipt")) db.remove("outbox", messageId);
            else {
                if (relay) queued.put("relayUploaded", true).put("nextRelay", Bytes.now() + 300).put("relayAttempts", 0);
                else queued.put("lastBluetooth", Bytes.now());
                put("outbox", messageId, queued);
                JSONObject message = get("message", messageId);
                if (message != null && !"Entregado".equals(message.optString("status"))) {
                    message.put("status", relay ? "En cola del servidor" : "Enlace Bluetooth"); put("message", messageId, message);
                }
            }
            return null;
        });
    }
    public void relayFailed(String messageId) throws Exception {
        db.transaction(() -> {
            JSONObject queued = get("outbox", messageId); if (queued == null) return null;
            int attempts = Math.min(queued.optInt("relayAttempts", 0) + 1, 30);
            queued.put("relayAttempts", attempts).put("nextRelay", RetryPolicy.next(Bytes.now(), attempts, new SecureRandom().nextDouble()));
            put("outbox", messageId, queued); return null;
        });
    }
    /** Sign only a bounded, domain-separated application challenge; not an arbitrary signing oracle. */
    public byte[] proveNearby(boolean dialer, String peer, byte[] ownNonce, byte[] peerNonce) throws Exception {
        return db.transaction(() -> {
            requiredContact(peer, false); // Explicit enrollment must have imported the peer card first.
            return signal.getIdentityKeyPair().getPrivateKey().calculateSignature(
                app.umbra.core.NearbyTranscript.encode(dialer, id(), ownNonce, peer, peerNonce));
        });
    }
    public void verifyNearby(boolean peerIsDialer, String peer, byte[] peerNonce, byte[] ownNonce, byte[] signature, boolean enrolling) throws Exception {
        db.transaction(() -> {
            JSONObject c = requiredContact(peer, !enrolling);
            byte[] publicKey = Bytes.unb64(c.getJSONObject("card").getString("identity"));
            if (signature.length != 64 || !peer.equals(Bytes.identity(publicKey)) || !new ECPublicKey(publicKey).verifySignature(
                app.umbra.core.NearbyTranscript.encode(peerIsDialer, peer, peerNonce, id(), ownNonce), signature))
                throw new SecurityException("No se pudo autenticar el dispositivo cercano");
            return null;
        });
    }
    public String stageExport(byte[] content) throws Exception {
        try {
            if (content.length == 0 || content.length > MAX_ATTACHMENT) throw new IllegalArgumentException("Export too large");
            return db.transaction(() -> {
                if (db.keys("export").size() >= 4) throw new LocalCapacityException();
                String key = UUID.randomUUID().toString();
                put("export", key, new JSONObject().put("data", Bytes.b64(content)).put("expires", Bytes.now() + 600));
                return key;
            });
        } finally { Arrays.fill(content, (byte) 0); }
    }
    public byte[] exportData(String key) throws Exception {
        JSONObject value = get("export", key);
        if (value == null || value.getLong("expires") <= Bytes.now()) throw new SecurityException("La exportación venció");
        return Bytes.unb64(value.getString("data"));
    }
    public void clearExport(String key) throws Exception { db.transaction(() -> { db.remove("export", key); return null; }); }
    public void expire() throws Exception {
        db.transaction(() -> {
            long now = Bytes.now();
            for (String bucket : new String[]{"message", "seen", "outbox", "export"}) for (String k : db.keys(bucket)) {
                JSONObject item = get(bucket, k);
                long expiry = bucket.equals("outbox") ? item.getJSONObject("envelope").getLong("expires") : item.getLong("expires");
                if (expiry <= now) db.remove(bucket, k);
            }
            for (String keyId : db.keys("key-expiry")) {
                long expiry = Long.parseLong(Bytes.text(db.get("key-expiry", keyId)));
                if (expiry <= now) {
                    db.remove("prekey", keyId); db.remove("signed", keyId); db.remove("kyber", keyId); db.remove("key-expiry", keyId);
                }
            }
            for (String k : db.keys("kem-used")) if (Long.parseLong(Bytes.text(db.get("kem-used", k))) < now - MAX_TTL) db.remove("kem-used", k);
            return null;
        });
    }
    public void clearConversation(String peer) throws Exception {
        db.transaction(() -> {
            for (String bucket : new String[]{"message", "outbox"}) {
                for (String k : db.keys(bucket)) if (get(bucket, k).getString("peer").equals(peer)) db.remove(bucket, k);
            }
            return null;
        });
    }
}
