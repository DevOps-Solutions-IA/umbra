package app.umbra.admission;

import app.umbra.core.Bytes;

/** Public signed authorization, never sufficient without possession proof. */
public record AdmissionCredential(String wire,String realmId,String credentialId,String devicePublicKey,
                                  String signalPublicKey,String issuerKeyId,long issuedAt,long notBefore,
                                  long expiresAt,String capabilities,String requestId) {
    public static final long MAX_TTL=7*86400L;
    public static final String CAPABILITIES="relay.nearby.turn";
    public AdmissionCredential {
        AdmissionCodec.token(realmId); AdmissionCodec.token(credentialId); AdmissionCodec.publicKey(devicePublicKey);
        AdmissionCodec.signalKey(signalPublicKey); AdmissionCodec.digest(issuerKeyId); AdmissionCodec.token(requestId);
        AdmissionCodec.interval(issuedAt,expiresAt,MAX_TTL);
        if(notBefore<issuedAt || notBefore>=expiresAt || !CAPABILITIES.equals(capabilities)) throw AdmissionCodec.invalid();
        String[] p=AdmissionCodec.fields("credential",wire,10);
        if(!java.util.Arrays.equals(p,new String[]{realmId,credentialId,devicePublicKey,signalPublicKey,issuerKeyId,
                ""+issuedAt,""+notBefore,""+expiresAt,capabilities,requestId})) throw AdmissionCodec.invalid();
    }
    public static AdmissionCredential decode(String wire,RealmConfig realm) {
        String[] p=AdmissionCodec.fields("credential",wire,10);
        AdmissionCredential c=new AdmissionCredential(wire,p[0],p[1],p[2],p[3],p[4],AdmissionCodec.number(p[5]),
                AdmissionCodec.number(p[6]),AdmissionCodec.number(p[7]),p[8],p[9]);
        c.verify(realm); return c;
    }
    public void verify(RealmConfig realm) {
        if(!realmId.equals(realm.realmId()) || !issuerKeyId.equals(realm.authorityKeyId())) throw new AdmissionException(AdmissionException.Code.AUTHORITY_MISMATCH);
        AdmissionCodec.verify("credential",wire,realm.authorityPublicKey(),AdmissionCodec.fields("credential",wire,10));
    }
    public void validate(RealmConfig realm,long now) { verify(realm); if(now<notBefore) throw new AdmissionException(AdmissionException.Code.NOT_YET_VALID); if(now>=expiresAt) throw new AdmissionException(AdmissionException.Code.EXPIRED); }
    static AdmissionCredential issue(RealmConfig realm,byte[] seed,AdmissionRequest request,long now,long ttl) {
        request.validate(realm,now);
        if(ttl<60 || ttl>MAX_TTL) throw AdmissionCodec.invalid();
        return decode(AdmissionCodec.sign("credential",seed,realm.realmId(),AdmissionCodec.random(),request.devicePublicKey(),
                request.signalPublicKey(),realm.authorityKeyId(),""+now,""+now,""+(now+ttl),CAPABILITIES,request.requestId()),realm);
    }
    public String deviceId() { return Bytes.identity(AdmissionCodec.decode(signalPublicKey,33)); }
    @Override public String toString() { return "AdmissionCredential[redacted]"; }
}
