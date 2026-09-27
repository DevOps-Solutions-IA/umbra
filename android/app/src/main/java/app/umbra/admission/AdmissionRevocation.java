package app.umbra.admission;

/** Terminal per-credential tombstone. No user-wide or data-deletion semantics. */
public record AdmissionRevocation(String wire,String realmId,String credentialId,String devicePublicKey,
                                  String issuerKeyId,long sequence,long revokedAt,String reason) {
    public AdmissionRevocation {
        AdmissionCodec.token(realmId); AdmissionCodec.token(credentialId); AdmissionCodec.publicKey(devicePublicKey);
        AdmissionCodec.digest(issuerKeyId);
        AdmissionCodec.number(""+sequence); AdmissionCodec.number(""+revokedAt);
        if(!java.util.Set.of("owner_request","device_lost","policy").contains(reason)) throw AdmissionCodec.invalid();
        if(!java.util.Arrays.equals(AdmissionCodec.fields("revocation",wire,7),new String[]{realmId,credentialId,
                devicePublicKey,issuerKeyId,""+sequence,""+revokedAt,reason})) throw AdmissionCodec.invalid();
    }
    public static AdmissionRevocation decode(String wire,RealmConfig realm) {
        String[] p=AdmissionCodec.fields("revocation",wire,7);
        AdmissionRevocation r=new AdmissionRevocation(wire,p[0],p[1],p[2],p[3],AdmissionCodec.number(p[4]),AdmissionCodec.number(p[5]),p[6]);
        if(!r.realmId.equals(realm.realmId()) || !r.issuerKeyId.equals(realm.authorityKeyId())) throw AdmissionCodec.invalid();
        AdmissionCodec.verify("revocation",wire,realm.authorityPublicKey(),p); return r;
    }
    static AdmissionRevocation issue(RealmConfig realm,byte[] seed,AdmissionCredential c,long sequence,long now,String reason) {
        c.verify(realm);
        return decode(AdmissionCodec.sign("revocation",seed,realm.realmId(),c.credentialId(),c.devicePublicKey(),
                realm.authorityKeyId(),""+sequence,""+now,reason),realm);
    }
    @Override public String toString() { return "AdmissionRevocation[redacted]"; }
}
