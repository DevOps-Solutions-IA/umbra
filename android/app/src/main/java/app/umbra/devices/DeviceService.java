package app.umbra.devices;

import app.umbra.core.Bytes;
import app.umbra.crypto.Engine;
import app.umbra.crypto.SignalStore;
import app.umbra.data.Records;
import app.umbra.protocol.Wire;
import java.util.*;
import org.json.JSONObject;

/** Device administration over the same transactional, authenticated Records as Engine. */
public final class DeviceService {
    private final Records db;
    private final Engine engine;
    private final SignalStore signal;
    public DeviceService(Records records) { db = records; engine = new Engine(records); signal = new SignalStore(records); }
    public static final class Consent {
        private final DeviceService owner;
        private final String purpose, transcript;
        private final Runnable session;
        private Consent(DeviceService owner, String purpose, String transcript, Runnable session) {
            this.owner = owner; this.purpose = purpose; this.transcript = transcript; this.session = session;
        }
        public String fingerprint() { return Bytes.sha256(Bytes.utf8(transcript)); }
    }
    private JSONObject get(String bucket, String key) throws Exception { return engine.get(bucket, key); }
    private void put(String bucket, String key, JSONObject value) { db.put(bucket, key, Bytes.utf8(value.toString())); }
    public String publicKey() { return DeviceTranscript.encode(signal.getIdentityKeyPair().getPublicKey().serialize()); }
    private String sign(String kind, String... fields) { return DeviceTranscript.sign(signal.getIdentityKeyPair(), kind, fields); }
    private void consent(Consent consent, String purpose, boolean confirmed) {
        if (!confirmed || consent == null || consent.owner != this || !purpose.equals(consent.purpose))
            throw new SecurityException("Explicit device confirmation required");
        consent.session.run();
    }
    /** Explicit additive migration; never modifies existing keys, sessions, contacts or history. */
    public String migrate() throws Exception {
        return db.transaction(() -> {
            if (!engine.initialized()) throw new SecurityException("Identity required");
            JSONObject own = get("meta", "device-affiliation");
            if (own != null) {
                JSONObject row = get("device-roster", own.getString("root"));
                if (row == null) throw new SecurityException("Missing device roster");
                return own.getString("root");
            }
            if (!db.keys("device-roster").isEmpty() || !db.keys("device-index").isEmpty() || !db.keys("device-issued").isEmpty())
                throw new SecurityException("Incomplete device migration");
            DeviceRoster roster = DeviceRoster.sign(signal.getIdentityKeyPair(), 1,
                Map.of(engine.id(), new DeviceRoster.Member(publicKey(), true)));
            put("meta", "device-affiliation", new JSONObject().put("v", 1).put("root", engine.id()));
            install(roster, true); return engine.id();
        });
    }
    private DeviceRoster administrator() throws Exception {
        JSONObject own = get("meta", "device-affiliation");
        if (own == null || !own.getString("root").equals(engine.id())) throw new SecurityException("Not device administrator");
        JSONObject row = get("device-roster", engine.id());
        if (row == null || row.optBoolean("retired")) throw new SecurityException("Device authority retired");
        return DeviceRoster.stored(row.getString("transcript"));
    }
    public String roster(String root) throws Exception {
        JSONObject row = get("device-roster", Wire.identity(root));
        if (row == null) throw new SecurityException("Unknown device roster"); return row.getString("transcript");
    }
    private void prune(String bucket) throws Exception {
        for (String key : db.keys(bucket)) if (get(bucket, key).getLong("expires") <= Bytes.now()) db.remove(bucket, key);
        if (db.keys(bucket).size() >= 16) throw new SecurityException("Device ceremony capacity reached");
    }
    public String challenge(String newPublicKey, long ttl) throws Exception {
        DeviceTranscript.key(newPublicKey);
        if (ttl < 60 || ttl > 600) throw new IllegalArgumentException("Device challenge lifetime must be 60..600 seconds");
        return db.transaction(() -> {
            DeviceRoster roster = administrator();
            if (roster.members.containsKey(DeviceTranscript.id(newPublicKey))) throw new SecurityException("Device already known; use a new key after revocation");
            prune("device-issued"); String nonce = Bytes.token(); long now = Bytes.now();
            String value = sign("challenge", publicKey(), newPublicKey, nonce, "" + now, "" + (now + ttl));
            put("device-issued", nonce, new JSONObject().put("challenge", value).put("expires", now + ttl)); return value;
        });
    }
    private static String[] challengeFields(String challenge) throws Exception {
        String[] f = DeviceTranscript.parse(challenge, "challenge", 5);
        DeviceTranscript.key(f[2]); app.umbra.pairing.PairingService.token(f[3]);
        DeviceTranscript.lifetime(f[4], f[5], 600); return f;
    }
    private void freshJoiningDevice() throws Exception {
        if (!engine.initialized() || get("meta", "device-affiliation") != null || !db.keys("contact").isEmpty() ||
            !db.keys("message").isEmpty() || !db.keys("session").isEmpty() || !db.keys("outbox").isEmpty())
            throw new SecurityException("Device linking requires a fresh unaffiliated identity");
    }
    public Consent reviewChallenge(String challenge) throws Exception {
        String[] f = challengeFields(challenge);
        if (!f[2].equals(publicKey()) || f[1].equals(f[2])) throw new SecurityException("Wrong joining device");
        freshJoiningDevice(); return new Consent(this, "respond", challenge, db.authorization());
    }
    public String respond(Consent reviewed, boolean confirmed) throws Exception {
        return db.transaction(() -> {
            consent(reviewed, "respond", confirmed); String[] f = challengeFields(reviewed.transcript); freshJoiningDevice();
            JSONObject old = get("device-pending", f[3]);
            if (old != null) {
                if (!old.getString("challenge").equals(reviewed.transcript)) throw new SecurityException("Challenge substitution");
                return old.getString("response");
            }
            prune("device-pending");
            String response = sign("response", publicKey(), reviewed.transcript,
                DeviceTranscript.encode(Bytes.utf8(engine.createCard().toString())));
            put("device-pending", f[3], new JSONObject().put("challenge", reviewed.transcript).put("response", response)
                .put("expires", DeviceTranscript.number(f[5]))); return response;
        });
    }
    private static String[] responseFields(String response) throws Exception {
        String[] f = DeviceTranscript.parse(response, "response", 3), c = challengeFields(f[2]);
        if (!f[1].equals(c[2])) throw new SecurityException("Joining key substitution"); return f;
    }
    public Consent reviewResponse(String response) throws Exception {
        String[] f = responseFields(response), c = challengeFields(f[2]); administrator();
        if (!c[1].equals(publicKey())) throw new SecurityException("Wrong administrator");
        return new Consent(this, "approve", response, db.authorization());
    }
    public String approve(Consent reviewed, boolean confirmed) throws Exception {
        return db.transaction(() -> {
            consent(reviewed, "approve", confirmed);
            String[] f = responseFields(reviewed.transcript), c = challengeFields(f[2]); DeviceRoster old = administrator();
            JSONObject issued = get("device-issued", c[3]); String hash = reviewed.fingerprint();
            if (!c[1].equals(publicKey()) || issued == null || !issued.getString("challenge").equals(f[2]) || issued.getLong("expires") <= Bytes.now())
                throw new SecurityException("Unknown device ceremony");
            if (issued.has("approval")) {
                if (!issued.getString("responseHash").equals(hash) || !old.active(DeviceTranscript.id(f[1])))
                    throw new SecurityException("Device ceremony consumed or revoked");
                return issued.getString("approval");
            }
            String device = DeviceTranscript.id(f[1]);
            if (old.members.containsKey(device)) throw new SecurityException("Device already enrolled or revoked");
            JSONObject card = Wire.parse(DeviceTranscript.decode(f[3], 16000), 16000);
            if (!engine.importCard(card).equals(device)) throw new SecurityException("Device card key substitution");
            Map<String, DeviceRoster.Member> members = new LinkedHashMap<>(old.members);
            members.put(device, new DeviceRoster.Member(f[1], true));
            DeviceRoster next = DeviceRoster.sign(signal.getIdentityKeyPair(), Math.addExact(old.version, 1), members);
            install(next, true);
            String approval = sign("approval", publicKey(), c[3], hash, next.transcript,
                DeviceTranscript.encode(Bytes.utf8(engine.createCard().toString())));
            issued.put("responseHash", hash).put("approval", approval); put("device-issued", c[3], issued); return approval;
        });
    }
    public String complete(String approval) throws Exception {
        String[] f = DeviceTranscript.parse(approval, "approval", 5); DeviceRoster next = DeviceRoster.parse(f[4]);
        return db.transaction(() -> {
            DeviceRoster.parse(f[4]);
            JSONObject pending = get("device-pending", f[2]);
            if (pending == null || pending.getLong("expires") <= Bytes.now() ||
                !Bytes.sha256(Bytes.utf8(pending.getString("response"))).equals(f[3])) throw new SecurityException("Unexpected device approval");
            String[] c = challengeFields(pending.getString("challenge"));
            if (!f[1].equals(c[1]) || !next.rootKey.equals(c[1]) || !next.active(engine.id())) throw new SecurityException("Wrong device approval");
            if (pending.has("approval")) {
                if (!pending.getString("approval").equals(approval)) throw new SecurityException("Approval substitution");
                DevicePolicy.authorize(db, engine.id(), next.root); return next.root;
            }
            freshJoiningDevice();
            if (!engine.importCard(Wire.parse(DeviceTranscript.decode(f[5], 16000), 16000)).equals(next.root)) throw new SecurityException("Administrator card substitution");
            put("meta", "device-affiliation", new JSONObject().put("v", 1).put("root", next.root));
            install(next, true); pending.put("approval", approval); put("device-pending", f[2], pending); return next.root;
        });
    }
    /** Apply authentic reductions immediately; additions remain unapproved until full-set comparison. */
    public void apply(String value) throws Exception {
        DeviceRoster roster = DeviceRoster.parse(value);
        db.transaction(() -> {
            DeviceRoster.parse(value); // Expiry must also hold after acquiring the transaction.
            JSONObject own = get("meta", "device-affiliation"), existing = get("device-roster", roster.root);
            if (own == null) throw new SecurityException("Explicit device migration required");
            if (existing == null && (own == null || !own.getString("root").equals(roster.root)) && engine.trustState(roster.root) != Engine.TrustState.VERIFIED)
                throw new SecurityException("Verify the administrator before accepting its device list");
            install(roster, false); return null;
        });
    }
    public Consent reviewRoster(String value) throws Exception {
        DeviceRoster.parse(value); apply(value); return new Consent(this, "roster", value, db.authorization());
    }
    public void approveRoster(Consent reviewed, String comparedFingerprint, boolean confirmed) throws Exception {
        db.transaction(() -> {
            consent(reviewed, "roster", confirmed); DeviceRoster roster = DeviceRoster.parse(reviewed.transcript);
            if (!roster.fingerprint.equals(comparedFingerprint)) throw new SecurityException("Device set comparison mismatch");
            JSONObject row = get("device-roster", roster.root);
            if (row == null || !row.getString("transcript").equals(roster.transcript) || roster.retired()) throw new SecurityException("Device set changed during review");
            if (!roster.root.equals(engine.id()) && engine.trustState(roster.root) != Engine.TrustState.VERIFIED)
                throw new SecurityException("Administrator identity not verified");
            install(roster, true); return null;
        });
    }
    public String revoke(String device) throws Exception {
        return db.transaction(() -> {
            DeviceRoster old = administrator();
            if (!old.members.containsKey(device)) throw new SecurityException("Unknown device");
            if (!old.active(device)) return old.transcript;
            Map<String, DeviceRoster.Member> members = new LinkedHashMap<>(old.members);
            for (String id : members.keySet()) if (id.equals(device) || device.equals(old.root))
                members.put(id, new DeviceRoster.Member(members.get(id).key(), false));
            DeviceRoster next = DeviceRoster.sign(signal.getIdentityKeyPair(), Math.addExact(old.version, 1), members);
            install(next, true); return next.transcript;
        });
    }
    public String renew() throws Exception {
        return db.transaction(() -> {
            DeviceRoster old = administrator();
            DeviceRoster next = DeviceRoster.sign(signal.getIdentityKeyPair(), Math.addExact(old.version, 1), old.members);
            install(next, true); return next.transcript;
        });
    }
    private void install(DeviceRoster next, boolean approve) throws Exception {
        JSONObject old = get("device-roster", next.root);
        if (old == null && db.keys("device-roster").size() >= 100) throw new SecurityException("Device roster capacity reached");
        JSONObject approved = new JSONObject();
        if (old != null) {
            if ((old.optBoolean("retired") && !next.transcript.equals(old.getString("transcript"))) || next.version < old.getLong("version") ||
                (next.version == old.getLong("version") && !next.transcript.equals(old.getString("transcript"))))
                throw new SecurityException("Stale, conflicting or retired device authority");
            DeviceRoster previous = DeviceRoster.stored(old.getString("transcript"));
            for (var entry : previous.members.entrySet()) {
                DeviceRoster.Member member = next.members.get(entry.getKey());
                if (member == null || !member.key().equals(entry.getValue().key()) || (!entry.getValue().active() && member.active()))
                    throw new SecurityException("Removed tombstone or device reactivation");
            }
            approved = old.getJSONObject("approved");
        }
        JSONObject active = new JSONObject();
        for (var entry : next.members.entrySet()) {
            String id = entry.getKey(); boolean enabled = entry.getValue().active();
            JSONObject index = get("device-index", id);
            if (index != null && !index.getString("root").equals(next.root)) throw new SecurityException("Device belongs to another identity");
            put("device-index", id, new JSONObject().put("root", next.root)); active.put(id, enabled);
            approved.put(id, enabled && (approve || approved.optBoolean(id) || (id.equals(next.root) && engine.contact(id) != null && engine.trustState(id) == Engine.TrustState.VERIFIED)));
            if (!enabled) {
                if (engine.contact(id) != null) engine.block(id, true);
                signal.deleteAllSessions(id);
                JSONObject grant = get("device-relay", id);
                if (grant != null) { grant.put("pending", true); put("device-relay", id, grant); }
                for (String key : db.keys("outbox")) if (get("outbox", key).getString("peer").equals(id) || id.equals(engine.id())) db.remove("outbox", key);
            }
        }
        put("device-roster", next.root, new JSONObject().put("transcript", next.transcript).put("version", next.version)
            .put("expires", next.expires).put("retired", next.retired()).put("active", active).put("approved", approved));
        if (approve) for (String id : next.members.keySet()) if (next.active(id) && !id.equals(engine.id()) && engine.contact(id) != null)
            engine.verify(id, Bytes.safetyCode(engine.id(), id));
    }
    /** Secret deletion-only capability, delivered to A1 inside a Signal message, never a QR. */
    public JSONObject relayDelegation() throws Exception {
        return db.transaction(() -> {
            JSONObject own = get("meta", "device-affiliation");
            if (own == null || own.getString("root").equals(engine.id())) throw new SecurityException("Linked secondary device required");
            DevicePolicy.authorize(db, engine.id(), own.getString("root"));
            JSONObject grant = get("meta", "device-relay-own");
            if (grant == null) {
                grant = new JSONObject().put("box", engine.profile().getString("box")).put("token", Bytes.token()).put("root", own.getString("root"));
                put("meta", "device-relay-own", grant);
            }
            byte[] transcript = grantTranscript(engine.id(), own.getString("root"), grant.getString("box"), grant.getString("token"));
            grant.put("proof", Bytes.b64(signal.getIdentityKeyPair().getPrivateKey().calculateSignature(transcript)));
            return grant;
        });
    }
    private static byte[] grantTranscript(String device, String root, String box, String token) {
        return Bytes.utf8("UMBRA-DEVICE-RELAY-GRANT-1\n" + device + "\n" + root + "\n" + box + "\n" + token + "\n");
    }
    /** Called only after the Engine authenticates and decrypts the grant sender. */
    public void receiveRelayDelegation(String peer, JSONObject content) throws Exception {
        DeviceRoster own = administrator();
        if (!own.active(peer) || peer.equals(engine.id()) || !content.getString("box").equals(engine.contact(peer).getJSONObject("card").getString("box")))
            throw new SecurityException("Wrong device delegation");
        String token = app.umbra.pairing.PairingService.token(content.getString("token"));
        byte[] signature = Bytes.unb64(content.getString("proof"), 88);
        if (signature.length != 64 || !new org.signal.libsignal.protocol.ecc.ECPublicKey(DeviceTranscript.decode(own.members.get(peer).key(), 33))
                .verifySignature(grantTranscript(peer, own.root, content.getString("box"), token), signature))
            throw new SecurityException("Invalid deletion delegation proof");
        JSONObject old = get("device-relay", peer);
        if (old != null && (!old.getString("token").equals(token) || !old.getString("box").equals(content.getString("box"))))
            throw new SecurityException("Delegation substitution");
        if (old == null) put("device-relay", peer, new JSONObject().put("box", content.getString("box")).put("token", token).put("pending", false));
    }
    public List<JSONObject> pendingRelayRevocations() throws Exception {
        return db.transaction(() -> {
            List<JSONObject> result = new ArrayList<>();
            for (String id : db.keys("device-relay")) {
                JSONObject grant = get("device-relay", id);
                if (grant.getBoolean("pending")) result.add(grant.put("device", id));
            }
            return result;
        });
    }
    public void relayRevoked(String device, String box) throws Exception {
        db.transaction(() -> {
            JSONObject grant = get("device-relay", device);
            if (grant == null || !grant.getString("box").equals(box)) throw new SecurityException("Unknown relay revocation");
            grant.put("pending", false); put("device-relay", device, grant); return null;
        });
    }
    public List<String> recipients(String root) throws Exception {
        JSONObject row = get("device-roster", Wire.identity(root));
        if (row == null) throw new SecurityException("Unknown device roster");
        DeviceRoster roster = DeviceRoster.parse(row.getString("transcript")); List<String> result = new ArrayList<>();
        for (String id : roster.members.keySet()) if (roster.active(id) && !id.equals(engine.id())) {
            DevicePolicy.authorize(db, engine.id(), id); result.add(id);
        }
        if (result.isEmpty()) throw new SecurityException("No authorized recipients"); return result;
    }
}
