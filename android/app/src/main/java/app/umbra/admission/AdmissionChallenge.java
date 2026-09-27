package app.umbra.admission;

import app.umbra.core.Bytes;

/** A verifier must store and atomically consume this exact challenge, including its context. */
public record AdmissionChallenge(String realmId,String credentialId,String credentialHash,String nonce,
                                 String verifierHash,String operationHash,long issuedAt,long expiresAt) {
    public static final long MAX_TTL=30;
    public AdmissionChallenge {
        AdmissionCodec.token(realmId); AdmissionCodec.token(credentialId); AdmissionCodec.digest(credentialHash);
        AdmissionCodec.token(nonce); AdmissionCodec.digest(verifierHash); AdmissionCodec.digest(operationHash);
        AdmissionCodec.interval(issuedAt,expiresAt,MAX_TTL);
    }
    public String encode() {
        return "umbra:challenge:1:"+AdmissionCodec.encode(AdmissionCodec.body("proof",fields()));
    }
    String[] fields() { return new String[]{realmId,credentialId,credentialHash,nonce,verifierHash,operationHash,""+issuedAt,""+expiresAt}; }
    public static AdmissionChallenge decode(String wire) {
        String prefix="umbra:challenge:1:";
        if(wire==null || wire.length()>2048 || !wire.startsWith(prefix)) throw AdmissionCodec.invalid();
        String[] p=Bytes.text(AdmissionCodec.decode(wire.substring(prefix.length()),-1)).split("\n",-1);
        if(p.length!=10 || !p[0].equals("UMBRA-ADMISSION-proof-1") || !p[9].isEmpty()) throw AdmissionCodec.invalid();
        AdmissionChallenge c=new AdmissionChallenge(p[1],p[2],p[3],p[4],p[5],p[6],AdmissionCodec.number(p[7]),AdmissionCodec.number(p[8]));
        if(!c.encode().equals(wire)) throw AdmissionCodec.invalid(); return c;
    }
    public static AdmissionChallenge create(AdmissionCredential credential,String verifierHash,String operationHash,long now) {
        return new AdmissionChallenge(credential.realmId(),credential.credentialId(),Bytes.sha256(Bytes.utf8(credential.wire())),
                AdmissionCodec.random(),verifierHash,operationHash,now,now+MAX_TTL);
    }
    /** Fill a server-issued pool challenge's operation before signing; no wildcard is signed. */
    public AdmissionChallenge forOperation(String operation) {
        if(!operationHash.equals("0".repeat(64))) throw AdmissionCodec.invalid();
        return new AdmissionChallenge(realmId,credentialId,credentialHash,nonce,verifierHash,
            AdmissionCodec.digest(operation),issuedAt,expiresAt);
    }
    public void validate(AdmissionCredential c,String verifier,String operation,long now) {
        if(!realmId.equals(c.realmId()) || !credentialId.equals(c.credentialId()) ||
                !credentialHash.equals(Bytes.sha256(Bytes.utf8(c.wire()))) || !verifierHash.equals(verifier) ||
                !operationHash.equals(operation)) throw AdmissionCodec.invalid();
        if(now<issuedAt) throw new SecurityException("Admission challenge not yet valid");
        if(now>=expiresAt) throw new SecurityException("Admission challenge expired");
    }
    static String prove(byte[] seed,AdmissionChallenge challenge) { return AdmissionCodec.sign("proof",seed,challenge.fields()); }
    public void verifyProof(AdmissionCredential credential,String wire) {
        String[] p=AdmissionCodec.fields("proof",wire,8);
        if(!java.util.Arrays.equals(p,fields())) throw AdmissionCodec.invalid();
        AdmissionCodec.verify("proof",wire,credential.devicePublicKey(),p);
    }
    @Override public String toString() { return "AdmissionChallenge[redacted]"; }
}
