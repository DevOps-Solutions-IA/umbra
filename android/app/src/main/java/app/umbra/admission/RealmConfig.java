package app.umbra.admission;

import app.umbra.core.Bytes;

/** Public provisioning only. Importing this object never grants membership. */
public record RealmConfig(String realmId, String authorityPublicKey, String authorityKeyId) {
    public RealmConfig {
        AdmissionCodec.token(realmId); AdmissionCodec.publicKey(authorityPublicKey);
        if (!Bytes.sha256(AdmissionCodec.decode(authorityPublicKey,32)).equals(authorityKeyId)) throw AdmissionCodec.invalid();
    }
    public String encode() { return "umbra:realm:1:"+realmId+":"+authorityPublicKey+":"+authorityKeyId; }
    public static RealmConfig decode(String wire) {
        if (wire == null || wire.length()>256) throw AdmissionCodec.invalid();
        String[] p=wire.split(":",-1);
        if(p.length!=6 || !p[0].equals("umbra") || !p[1].equals("realm") || !p[2].equals("1")) throw AdmissionCodec.invalid();
        return new RealmConfig(p[3],p[4],p[5]);
    }
    static RealmConfig create(byte[] authoritySeed) {
        String pub=AdmissionCodec.encode(AdmissionCodec.publicFromSeed(authoritySeed));
        return new RealmConfig(AdmissionCodec.random(),pub,Bytes.sha256(AdmissionCodec.decode(pub,32)));
    }
    @Override public String toString() { return "RealmConfig[public provisioning]"; }
}
