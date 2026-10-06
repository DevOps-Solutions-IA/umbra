package app.umbra.admission;

import app.umbra.core.Bytes;

/** Authenticated rejection of one exact request; never grants or revokes another credential. */
public record AdmissionRejection(String wire,String realmId,String requestId,String requestHash,
                                 String devicePublicKey,String issuerKeyId,long rejectedAt) {
    public AdmissionRejection {
        AdmissionCodec.token(realmId);AdmissionCodec.token(requestId);AdmissionCodec.digest(requestHash);
        AdmissionCodec.publicKey(devicePublicKey);AdmissionCodec.digest(issuerKeyId);AdmissionCodec.number(""+rejectedAt);
        if(!java.util.Arrays.equals(AdmissionCodec.fields("rejection",wire,6),new String[]{realmId,requestId,requestHash,
                devicePublicKey,issuerKeyId,""+rejectedAt})) throw AdmissionCodec.invalid();
    }
    public static AdmissionRejection decode(String wire,RealmConfig realm) {
        String[] p=AdmissionCodec.fields("rejection",wire,6);
        AdmissionRejection result=new AdmissionRejection(wire,p[0],p[1],p[2],p[3],p[4],AdmissionCodec.number(p[5]));
        if(!result.realmId.equals(realm.realmId()) || !result.issuerKeyId.equals(realm.authorityKeyId())) throw AdmissionCodec.invalid();
        AdmissionCodec.verify("rejection",wire,realm.authorityPublicKey(),p); return result;
    }
    static AdmissionRejection issue(RealmConfig realm,byte[] seed,AdmissionRequest request,long now) {
        request.validate(realm,now);
        return decode(AdmissionCodec.sign("rejection",seed,realm.realmId(),request.requestId(),
            Bytes.sha256(Bytes.utf8(request.wire())),request.devicePublicKey(),realm.authorityKeyId(),""+now),realm);
    }
    public void validate(AdmissionRequest request) {
        if(!realmId.equals(request.realmId()) || !requestId.equals(request.requestId()) ||
                !requestHash.equals(Bytes.sha256(Bytes.utf8(request.wire()))) || !devicePublicKey.equals(request.devicePublicKey()) ||
                rejectedAt<request.createdAt() || rejectedAt>=request.expiresAt()) throw AdmissionCodec.invalid();
    }
    @Override public String toString() { return "AdmissionRejection[redacted]"; }
}
