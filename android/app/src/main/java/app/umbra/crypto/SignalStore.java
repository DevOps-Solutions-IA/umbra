package app.umbra.crypto;

import app.umbra.core.Bytes;
import app.umbra.data.Records;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.signal.libsignal.protocol.*;
import org.signal.libsignal.protocol.ecc.ECPublicKey;
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord;
import org.signal.libsignal.protocol.state.*;

/** Durable libsignal adapter. Calls must be serialized inside the same Records transaction. */
public final class SignalStore implements SignalProtocolStore {
    private final Records records;
    public SignalStore(Records records) { this.records = records; }
    private static String address(SignalProtocolAddress a) { return a.getName() + ":" + a.getDeviceId(); }
    private byte[] need(String bucket, int id) throws InvalidKeyIdException {
        byte[] value = records.get(bucket, String.valueOf(id));
        if (value == null) throw new InvalidKeyIdException("Unknown or consumed key");
        return value;
    }
    @Override public IdentityKeyPair getIdentityKeyPair() {
        try { return new IdentityKeyPair(records.get("meta", "identity")); }
        catch (Exception e) { throw new IllegalStateException("Identity unavailable", e); }
    }
    @Override public int getLocalRegistrationId() { return Integer.parseInt(Bytes.text(records.get("meta", "registration"))); }
    public void pin(String id, IdentityKey identity) {
        if (!id.equals(Bytes.identity(identity.serialize()))) throw new SecurityException("Identity mismatch");
        saveIdentity(new SignalProtocolAddress(id, 1), identity);
    }
    @Override public IdentityChange saveIdentity(SignalProtocolAddress a, IdentityKey identity) {
        byte[] old = records.get("trusted", address(a));
        if (old != null && !Bytes.equal(old, identity.serialize()))
            throw new SecurityException("Pinned identity changed: re-verification required");
        records.put("trusted", address(a), identity.serialize());
        return IdentityChange.NEW_OR_UNCHANGED;
    }
    @Override public boolean isTrustedIdentity(SignalProtocolAddress a, IdentityKey identity, Direction direction) {
        byte[] expected = records.get("trusted", address(a));
        return expected != null && Bytes.equal(expected, identity.serialize());
    }
    @Override public IdentityKey getIdentity(SignalProtocolAddress a) {
        byte[] raw = records.get("trusted", address(a));
        if (raw == null) return null;
        try { return new IdentityKey(new ECPublicKey(raw)); }
        catch (Exception e) { throw new IllegalStateException("Invalid stored identity", e); }
    }
    @Override public PreKeyRecord loadPreKey(int id) throws InvalidKeyIdException {
        byte[] value = need("prekey", id);
        try { return new PreKeyRecord(value); }
        catch (Exception e) { throw new IllegalStateException("Invalid prekey record", e); }
    }
    @Override public void storePreKey(int id, PreKeyRecord value) { records.put("prekey", "" + id, value.serialize()); }
    @Override public boolean containsPreKey(int id) { return records.get("prekey", "" + id) != null; }
    @Override public void removePreKey(int id) { records.remove("prekey", "" + id); }
    @Override public SessionRecord loadSession(SignalProtocolAddress a) {
        byte[] value = records.get("session", address(a));
        try { return value == null ? new SessionRecord() : new SessionRecord(value); }
        catch (Exception e) { throw new IllegalStateException("Invalid session record", e); }
    }
    @Override public List<SessionRecord> loadExistingSessions(List<SignalProtocolAddress> addresses) throws NoSessionException {
        List<SessionRecord> out = new ArrayList<>();
        for (SignalProtocolAddress a : addresses) {
            if (!containsSession(a)) throw new NoSessionException(a, "No saved session");
            out.add(loadSession(a));
        }
        return out;
    }
    @Override public List<Integer> getSubDeviceSessions(String name) {
        List<Integer> ids = new ArrayList<>();
        for (String key : records.keys("session")) if (key.startsWith(name + ":")) ids.add(Integer.parseInt(key.substring(name.length() + 1)));
        return ids;
    }
    @Override public void storeSession(SignalProtocolAddress a, SessionRecord record) { records.put("session", address(a), record.serialize()); }
    @Override public boolean containsSession(SignalProtocolAddress a) { return records.get("session", address(a)) != null; }
    @Override public void deleteSession(SignalProtocolAddress a) { records.remove("session", address(a)); }
    @Override public void deleteAllSessions(String name) {
        for (String key : records.keys("session")) if (key.startsWith(name + ":")) records.remove("session", key);
    }
    @Override public SignedPreKeyRecord loadSignedPreKey(int id) throws InvalidKeyIdException {
        byte[] value = need("signed", id);
        try { return new SignedPreKeyRecord(value); }
        catch (Exception e) { throw new IllegalStateException("Invalid signed prekey", e); }
    }
    @Override public List<SignedPreKeyRecord> loadSignedPreKeys() {
        List<SignedPreKeyRecord> out = new ArrayList<>();
        try { for (String id : records.keys("signed")) out.add(loadSignedPreKey(Integer.parseInt(id))); }
        catch (Exception e) { throw new IllegalStateException(e); }
        return out;
    }
    @Override public void storeSignedPreKey(int id, SignedPreKeyRecord record) { records.put("signed", "" + id, record.serialize()); }
    @Override public boolean containsSignedPreKey(int id) { return records.get("signed", "" + id) != null; }
    @Override public void removeSignedPreKey(int id) { records.remove("signed", "" + id); }
    @Override public KyberPreKeyRecord loadKyberPreKey(int id) throws InvalidKeyIdException {
        byte[] value = need("kyber", id);
        try { return new KyberPreKeyRecord(value); }
        catch (Exception e) { throw new IllegalStateException("Invalid KEM record", e); }
    }
    @Override public List<KyberPreKeyRecord> loadKyberPreKeys() {
        List<KyberPreKeyRecord> out = new ArrayList<>();
        try { for (String id : records.keys("kyber")) out.add(loadKyberPreKey(Integer.parseInt(id))); }
        catch (Exception e) { throw new IllegalStateException(e); }
        return out;
    }
    @Override public void storeKyberPreKey(int id, KyberPreKeyRecord record) { records.put("kyber", "" + id, record.serialize()); }
    @Override public boolean containsKyberPreKey(int id) { return records.get("kyber", "" + id) != null; }
    @Override public void markKyberPreKeyUsed(int id, int signedId, ECPublicKey baseKey) throws ReusedBaseKeyException {
        String usage = id + ":" + signedId + ":" + Bytes.sha256(baseKey.serialize());
        if (records.get("kem-used", usage) != null) throw new ReusedBaseKeyException();
        records.put("kem-used", usage, Bytes.utf8("" + Bytes.now()));
        // Cards are single-recipient invitations. Erase this one-time KEM secret on first use.
        records.remove("kyber", "" + id);
    }
    @Override public void storeSenderKey(SignalProtocolAddress sender, UUID distribution, SenderKeyRecord value) {
        records.put("sender-key", address(sender) + ":" + distribution, value.serialize());
    }
    @Override public SenderKeyRecord loadSenderKey(SignalProtocolAddress sender, UUID distribution) {
        byte[] value = records.get("sender-key", address(sender) + ":" + distribution);
        if (value == null) return null;
        try { return new SenderKeyRecord(value); }
        catch (Exception e) { throw new IllegalStateException("Invalid sender key", e); }
    }
}
