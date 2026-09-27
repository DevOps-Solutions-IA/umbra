package app.umbra.admission;

import app.umbra.core.Bytes;

/** Device self-signature proves possession of its separate admission key, not human identity. */
public record AdmissionRequest(String wire,String realmId,String requestId,String devicePublicKey,
                               String signalPublicKey,long createdAt,long expiresAt,String nonce) {
    public static final long MAX_TTL=600;
    public AdmissionRequest {
        AdmissionCodec.token(realmId); AdmissionCodec.token(requestId); AdmissionCodec.publicKey(devicePublicKey);
        AdmissionCodec.signalKey(signalPublicKey); AdmissionCodec.token(nonce);
        if (requestId.equals(nonce)) throw AdmissionCodec.invalid();
        AdmissionCodec.interval(createdAt,expiresAt,MAX_TTL);
        String[] fields=AdmissionCodec.fields("request",wire,7);
        String[] expected={realmId,requestId,devicePublicKey,signalPublicKey,""+createdAt,""+expiresAt,nonce};
        if (!java.util.Arrays.equals(fields,expected)) throw AdmissionCodec.invalid();
        AdmissionCodec.verify("request",wire,devicePublicKey,fields);
    }
    public static AdmissionRequest decode(String wire) {
        String[] p=AdmissionCodec.fields("request",wire,7);
        return new AdmissionRequest(wire,p[0],p[1],p[2],p[3],AdmissionCodec.number(p[4]),AdmissionCodec.number(p[5]),p[6]);
    }
    static AdmissionRequest create(RealmConfig realm,byte[] deviceSeed,byte[] signalKey,long now) {
        String wire=AdmissionCodec.sign("request",deviceSeed,realm.realmId(),AdmissionCodec.random(),
                AdmissionCodec.encode(AdmissionCodec.publicFromSeed(deviceSeed)),AdmissionCodec.encode(signalKey),
                ""+now,""+(now+MAX_TTL),AdmissionCodec.random());
        return decode(wire);
    }
    public void validate(RealmConfig realm,long now) {
        if (!realmId.equals(realm.realmId()) || now<createdAt || now>=expiresAt) throw AdmissionCodec.invalid();
    }
    public String deviceId() { return Bytes.identity(AdmissionCodec.decode(signalPublicKey,33)); }
    public String deviceFingerprint() { return Bytes.sha256(AdmissionCodec.decode(devicePublicKey,32)); }
    @Override public String toString() { return "AdmissionRequest[redacted]"; }
}
