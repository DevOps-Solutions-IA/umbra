package app.umbra.admission;

import app.umbra.core.Bytes;
import app.umbra.crypto.SignalStore;
import app.umbra.data.Records;
import java.util.Arrays;
import java.util.function.LongSupplier;

/** Local admission authority and membership. Every private operation uses the current vault lease. */
public final class AdmissionService {
    public enum State { UNCONFIGURED, NOT_ADMITTED, REQUEST_PENDING, REJECTED, ADMITTED, EXPIRED, REVOKED, INVALID }
    public static final int MAX_DECISIONS=4096, MAX_PEERS=4096, MAX_CHALLENGES=64;
    private final Records db;
    private final app.umbra.connectivity.ConnectivityService connectivity;
    private final LongSupplier clock;
    private final java.util.Map<String,Runnable> challengeLeases=new java.util.concurrent.ConcurrentHashMap<>();
    public AdmissionService(Records db) { this(db,Bytes::now); }
    public AdmissionService(Records db,LongSupplier clock) {
        this.db=db; this.clock=clock;
        connectivity=new app.umbra.connectivity.ConnectivityService(db,this,app.umbra.calls.CallPlatform.ENABLED);
    }
    public app.umbra.connectivity.ConnectivityService connectivity() { return connectivity; }
    private String read(String key) { byte[] v=db.get("admission",key); return v==null?null:Bytes.text(v); }
    private void write(String key,String value) { db.put("admission",key,Bytes.utf8(value)); }
    private RealmConfig realm() { String r=read("realm"); if(r==null) throw AdmissionCodec.invalid(); return RealmConfig.decode(r); }
    private byte[] signalPublic() { return new SignalStore(db).getIdentityKeyPair().getPublicKey().serialize(); }
    private byte[] seed(String key) {
        byte[] seed=db.get("admission-secret",key);
        if(seed==null || seed.length!=32) throw AdmissionCodec.invalid(); return seed;
    }
    private boolean hasAdmissionRecords() {
        for(String bucket:new String[]{"admission","admission-secret","admission-peers","admission-revoked",
                "admission-decisions","admission-nonces","admission-challenges","admission-peer-evidence"})
            if(!db.keys(bucket).isEmpty()) return true;
        return false;
    }
    private Runnable lease() { Runnable check=db.authorization(); check.run(); return check; }
    public RealmConfig getRealmInfo() throws Exception { return db.transaction(this::realm); }
    /** Snapshot only; never substitutes for authorization at an operation boundary. */
    public boolean isAdmissionAuthority() throws Exception {
        Runnable check=lease();
        return db.transaction(() -> {
            check.run();
            if(db.get("admission-secret","authority")==null) return false;
            authority(); check.run(); return true;
        });
    }
    public record Status(State state,Long requestExpiresAt,Long credentialExpiresAt) {}
    public Status status() throws Exception {
        Runnable check=lease();
        return db.transaction(() -> {
            check.run(); State state=getAdmissionState();
            String pending=read("pending"),credential=read("credential");
            Long requestExpiry=pending==null?null:AdmissionRequest.decode(pending).expiresAt();
            Long credentialExpiry=credential==null?null:AdmissionCredential.decode(credential,realm()).expiresAt();
            check.run(); return new Status(state,requestExpiry,credentialExpiry);
        });
    }
    /** Local cancellation only: no network operation and no authority-side revocation. */
    public void cancelPendingRequest(String expectedRequestId) throws Exception {
        Runnable check=lease();
        db.transaction(() -> {
            check.run(); AdmissionRequest pending=pendingRequest();
            if(!pending.requestId().equals(expectedRequestId)) throw AdmissionCodec.invalid();
            db.remove("admission","pending"); db.remove("admission","rejection");
            check.run(); return null;
        });
    }
    public record IssuedCredential(String credentialId,String deviceId,long issuedAt,long expiresAt,boolean revoked) {}
    /** Bounded public metadata; only the locally pinned authority may inspect it. */
    public java.util.List<IssuedCredential> issuedCredentials() throws Exception {
        Runnable check=lease();
        return db.transaction(() -> {
            check.run(); authority();
            java.util.List<String> keys=db.keys("admission-decisions");
            if(keys.size()>MAX_DECISIONS) throw AdmissionCodec.invalid();
            java.util.List<IssuedCredential> result=new java.util.ArrayList<>();
            for(String id:keys) {
                String wire=Bytes.text(db.get("admission-decisions",id));
                // Decisions can be credentials or signed rejections; unknown/corrupt data fails closed.
                try {
                    AdmissionCredential c=AdmissionCredential.decode(wire,realm());
                    if(!c.requestId().equals(id)) throw AdmissionCodec.invalid();
                    result.add(new IssuedCredential(c.credentialId(),c.deviceId(),c.issuedAt(),c.expiresAt(),
                            db.get("admission-revoked",c.credentialId())!=null));
                } catch(SecurityException invalidCredential) {
                    if(!AdmissionRejection.decode(wire,realm()).requestId().equals(id)) throw AdmissionCodec.invalid();
                }
            }
            check.run(); return java.util.List.copyOf(result);
        });
    }
    public State getAdmissionState() throws Exception {
        return db.transaction(() -> {
            if(read("realm")==null) {
                if(hasAdmissionRecords()) return State.INVALID;
                return State.UNCONFIGURED;
            }
            try {
                RealmConfig r=realm(); String own=read("credential");
                byte[] present=seed("device"); Arrays.fill(present,(byte)0);
                if(own==null) {
                    if(read("pending")==null) return State.NOT_ADMITTED;
                    AdmissionRequest pending=AdmissionRequest.decode(read("pending"));
                    if(read("rejection")!=null) { AdmissionRejection.decode(read("rejection"),r).validate(pending); return State.REJECTED; }
                    return clock.getAsLong()>=pending.expiresAt()?State.EXPIRED:State.REQUEST_PENDING;
                }
                AdmissionCredential c=AdmissionCredential.decode(own,r); verifyOwn(c);
                if(db.get("admission-revoked",c.credentialId())!=null) return State.REVOKED;
                if(clock.getAsLong()>=c.expiresAt()) return State.EXPIRED;
                c.validate(r,clock.getAsLong()); return State.ADMITTED;
            } catch(SecurityException | IllegalArgumentException invalid) { return State.INVALID; }
        });
    }
    /** Provisioning pins public authority only; local confirmation is required by the caller. */
    public void installRealmConfig(String wire,boolean confirmed) throws Exception {
        Runnable check=lease(); RealmConfig proposed=RealmConfig.decode(wire);
        db.transaction(() -> {
            check.run(); if(!confirmed) throw AdmissionCodec.invalid(); signalPublic();
            String old=read("realm");
            if(old!=null && !old.equals(proposed.encode())) throw new AdmissionException(AdmissionException.Code.AUTHORITY_MISMATCH);
            if(old==null) {
                if(hasAdmissionRecords()) throw AdmissionCodec.invalid();
                byte[] key=Bytes.random(32);
                try { db.put("admission-secret","device",key); write("realm",proposed.encode()); }
                finally { Arrays.fill(key,(byte)0); }
            } else { byte[] key=seed("device"); Arrays.fill(key,(byte)0); }
            check.run(); return null;
        });
    }
    public RealmConfig createAdmissionRealm(boolean confirmed) throws Exception {
        Runnable check=lease();
        return db.transaction(() -> {
            check.run(); if(!confirmed || hasAdmissionRecords()) throw AdmissionCodec.invalid();
            signalPublic(); byte[] authority=Bytes.random(32),device=Bytes.random(32);
            try {
                RealmConfig r=RealmConfig.create(authority);
                db.put("admission-secret","authority",authority); db.put("admission-secret","device",device); write("realm",r.encode());
                check.run(); return r;
            } finally { Arrays.fill(authority,(byte)0); Arrays.fill(device,(byte)0); }
        });
    }
    public AdmissionRequest createAdmissionRequest() throws Exception {
        Runnable check=lease();
        return db.transaction(() -> {
            check.run(); RealmConfig r=realm();
            String pending=read("pending");
            if(pending!=null && read("rejection")==null && AdmissionRequest.decode(pending).expiresAt()>clock.getAsLong()) throw new AdmissionException(AdmissionException.Code.REQUEST_PENDING);
            byte[] key=seed("device");
            try {
                AdmissionRequest request=AdmissionRequest.create(r,key,signalPublic(),clock.getAsLong());
                check.run(); request.validate(r,clock.getAsLong()); write("pending",request.wire()); db.remove("admission","rejection"); return request;
            } finally { Arrays.fill(key,(byte)0); }
        });
    }
    public final class Review {
        private final AdmissionService owner=AdmissionService.this;
        private final AdmissionRequest request;
        private final Runnable authorization;
        private Review(AdmissionRequest request,Runnable authorization) { this.request=request; this.authorization=authorization; }
        public String deviceFingerprint() { return request.deviceFingerprint(); }
        public String identityFingerprint() { return request.deviceId(); }
        public String realmId() { return request.realmId(); }
        public long expiresAt() { return request.expiresAt(); }
        @Override public String toString() { return "AdmissionReview[redacted]"; }
    }
    private void authority() {
        if(db.get("admission-secret","authority")==null)throw new AdmissionException(AdmissionException.Code.NOT_AUTHORITY);
        byte[] key=seed("authority");
        try { if(!AdmissionCodec.encode(AdmissionCodec.publicFromSeed(key)).equals(realm().authorityPublicKey())) throw AdmissionCodec.invalid(); }
        finally { Arrays.fill(key,(byte)0); }
    }
    public Review reviewAdmissionRequest(String wire) throws Exception {
        Runnable check=lease(); AdmissionRequest request=AdmissionRequest.decode(wire);
        return db.transaction(() -> {
            check.run(); authority(); request.validate(realm(),clock.getAsLong()); unconsumed(request);
            return new Review(request,check);
        });
    }
    private void unconsumed(AdmissionRequest request) {
        if(db.get("admission-decisions",request.requestId())!=null || db.get("admission-nonces",request.nonce())!=null) throw new AdmissionException(AdmissionException.Code.REQUEST_CONSUMED);
        if(db.keys("admission-decisions").size()>=MAX_DECISIONS) throw new AdmissionException(AdmissionException.Code.CAPACITY_REACHED);
    }
    private void consent(Review review,boolean confirmed) {
        if(review==null || review.owner!=this || !confirmed) throw AdmissionCodec.invalid();
        review.authorization.run(); authority(); review.request.validate(realm(),clock.getAsLong()); unconsumed(review.request);
    }
    private void consume(Review review,String result) {
        review.authorization.run(); review.request.validate(realm(),clock.getAsLong());
        db.put("admission-decisions",review.request.requestId(),Bytes.utf8(result));
        db.put("admission-nonces",review.request.nonce(),Bytes.utf8(review.request.requestId()));
    }
    public AdmissionCredential approveAdmission(Review review,boolean confirmed,long ttl) throws Exception {
        return db.transaction(() -> {
            consent(review,confirmed); byte[] key=seed("authority");
            try {
                AdmissionCredential c=AdmissionCredential.issue(realm(),key,review.request,clock.getAsLong(),ttl);
                consume(review,c.wire()); return c;
            } finally { Arrays.fill(key,(byte)0); }
        });
    }
    public AdmissionRejection rejectAdmission(Review review,boolean confirmed) throws Exception {
        return db.transaction(() -> {
            consent(review,confirmed); byte[] key=seed("authority");
            try {
                AdmissionRejection rejection=AdmissionRejection.issue(realm(),key,review.request,clock.getAsLong());
                consume(review,rejection.wire()); return rejection;
            } finally { Arrays.fill(key,(byte)0); }
        });
    }
    public void installRejection(String wire) throws Exception {
        Runnable check=lease();
        db.transaction(() -> {
            check.run(); AdmissionRejection rejection=AdmissionRejection.decode(wire,realm());
            String pending=read("pending"); if(pending==null) throw AdmissionCodec.invalid();
            rejection.validate(AdmissionRequest.decode(pending)); write("rejection",wire); return null;
        });
    }
    public AdmissionRequest pendingRequest() throws Exception {
        return db.transaction(() -> { lease(); String pending=read("pending"); if(pending==null) throw AdmissionCodec.invalid();
            return AdmissionRequest.decode(pending); });
    }
    public Records.Work<Void> requestAuthorization() throws Exception {
        Runnable captured=lease(); String pending=pendingRequest().wire();
        return () -> { captured.run(); if(!pending.equals(pendingRequest().wire())) throw AdmissionCodec.invalid(); return null; };
    }
    public String proveRequestResult(AdmissionChallenge challenge,String expectedVerifier) throws Exception {
        Runnable check=lease();
        return db.transaction(() -> {
            check.run(); AdmissionRequest request=pendingRequest();
            String operation=Bytes.sha256(Bytes.utf8("UMBRA-ADMISSION-RESULT-1"));
            if(!challenge.realmId().equals(realm().realmId()) || !challenge.credentialId().equals(request.requestId()) ||
                    !challenge.credentialHash().equals(Bytes.sha256(Bytes.utf8(request.wire()))) ||
                    !challenge.verifierHash().equals(expectedVerifier) || !challenge.operationHash().equals(operation) ||
                    clock.getAsLong()<challenge.issuedAt() || clock.getAsLong()>=challenge.expiresAt()) throw AdmissionCodec.invalid();
            byte[] key=seed("device");
            try {
                if(!AdmissionCodec.encode(AdmissionCodec.publicFromSeed(key)).equals(request.devicePublicKey())) throw AdmissionCodec.invalid();
                String proof=AdmissionCodec.sign("request-proof",key,challenge.fields()); check.run();
                if(clock.getAsLong()<challenge.issuedAt() || clock.getAsLong()>=challenge.expiresAt()) throw AdmissionCodec.invalid();
                return proof;
            } finally { Arrays.fill(key,(byte)0); }
        });
    }
    public void installAdmissionCredential(String wire) throws Exception {
        Runnable check=lease();
        db.transaction(() -> {
            check.run(); AdmissionCredential c=AdmissionCredential.decode(wire,realm()); c.validate(realm(),clock.getAsLong()); verifyOwn(c);
            if(db.get("admission-revoked",c.credentialId())!=null) throw new AdmissionException(AdmissionException.Code.REVOKED);
            String pending=read("pending"); if(pending==null || read("rejection")!=null) throw AdmissionCodec.invalid();
            AdmissionRequest request=AdmissionRequest.decode(pending);
            if(!request.requestId().equals(c.requestId()) || !request.devicePublicKey().equals(c.devicePublicKey()) ||
                    !request.signalPublicKey().equals(c.signalPublicKey()) || c.issuedAt()<request.createdAt() || c.issuedAt()>=request.expiresAt()) throw AdmissionCodec.invalid();
            check.run(); write("credential",c.wire()); db.remove("admission","pending"); return null;
        });
    }
    /** Approve this authority device's pending request and install it atomically. */
    public AdmissionCredential approveAndInstallOwnAdmission(Review review,boolean confirmed,long ttl) throws Exception {
        Runnable check=lease();
        return db.transaction(() -> {
            check.run();
            AdmissionCredential credential=approveAdmission(review,confirmed,ttl);
            installAdmissionCredential(credential.wire());
            check.run(); return credential;
        });
    }
    /** Import the authenticated renewal pair atomically; neither object alone is a renewal. */
    public void installRenewal(String credentialWire,String revocationWire) throws Exception {
        Runnable check=lease();
        db.transaction(() -> {
            check.run(); RealmConfig pinned=realm();
            String current=read("credential"); if(current==null) throw AdmissionCodec.invalid();
            AdmissionCredential old=AdmissionCredential.decode(current,pinned); verifyOwn(old);
            AdmissionCredential next=AdmissionCredential.decode(credentialWire,pinned);
            AdmissionRevocation revoked=AdmissionRevocation.decode(revocationWire,pinned);
            if(!revoked.credentialId().equals(old.credentialId()) ||
                    !revoked.devicePublicKey().equals(old.devicePublicKey()) ||
                    next.credentialId().equals(old.credentialId()) ||
                    !next.devicePublicKey().equals(old.devicePublicKey()) ||
                    !next.signalPublicKey().equals(old.signalPublicKey())) throw AdmissionCodec.invalid();
            installAdmissionCredential(credentialWire);
            applyRevocation(revocationWire);
            check.run(); return null;
        });
    }
    private void verifyOwn(AdmissionCredential c) {
        byte[] key=seed("device");
        try {
            if(!AdmissionCodec.encode(AdmissionCodec.publicFromSeed(key)).equals(c.devicePublicKey()) ||
                    !AdmissionCodec.encode(signalPublic()).equals(c.signalPublicKey())) throw new AdmissionException(AdmissionException.Code.WRONG_DEVICE);
        } finally { Arrays.fill(key,(byte)0); }
    }
    public Records.Work<Void> authorization() throws Exception {
        Runnable captured=lease(); requireAdmission();
        return () -> { captured.run(); requireAdmission(); return null; };
    }
    public AdmissionCredential requireAdmission() throws Exception {
        return db.transaction(() -> {
            lease(); String wire=read("credential"); if(wire==null) throw new AdmissionException(AdmissionException.Code.NOT_ADMITTED);
            AdmissionCredential c=AdmissionCredential.decode(wire,realm()); verifyOwn(c); valid(c); return c;
        });
    }
    private void valid(AdmissionCredential c) {
        c.validate(realm(),clock.getAsLong());
        if(db.get("admission-revoked",c.credentialId())!=null) throw new AdmissionException(AdmissionException.Code.REVOKED);
    }
    public String prove(AdmissionChallenge challenge,String expectedVerifier,String expectedOperation) throws Exception {
        Runnable check=lease();
        return db.transaction(() -> {
            check.run(); AdmissionCredential c=requireAdmission();
            challenge.validate(c,expectedVerifier,expectedOperation,clock.getAsLong()); byte[] key=seed("device");
            try { String proof=AdmissionChallenge.prove(key,challenge); check.run(); valid(c);
                challenge.validate(c,expectedVerifier,expectedOperation,clock.getAsLong()); return proof;
            } finally { Arrays.fill(key,(byte)0); }
        });
    }
    public AdmissionChallenge challenge(String credential,String verifier,String operation) throws Exception {
        Runnable check=lease();
        return db.transaction(() -> {
            check.run(); requireAdmission(); AdmissionCredential c=AdmissionCredential.decode(credential,realm()); valid(c);
            long now=clock.getAsLong();
            for(String key:db.keys("admission-challenges")) {
                AdmissionChallenge old=AdmissionChallenge.decode(Bytes.text(db.get("admission-challenges",key)));
                if(now>=old.expiresAt() || !challengeLeases.containsKey(key)) { db.remove("admission-challenges",key); challengeLeases.remove(key); }
            }
            if(db.keys("admission-challenges").size()>=MAX_CHALLENGES) throw AdmissionCodec.invalid();
            AdmissionChallenge challenge=AdmissionChallenge.create(c,verifier,operation,now);
            db.put("admission-challenges",challenge.nonce(),Bytes.utf8(challenge.encode()));
            challengeLeases.put(challenge.nonce(),check); return challenge;
        });
    }
    /** Must be called for an expected authenticated Signal peer, not an identity claimed by the remote. */
    public void acceptProof(String credential,AdmissionChallenge challenge,String proof,String expectedDevice,
                            String verifier,String operation) throws Exception {
        Runnable check=lease();
        db.transaction(() -> {
            check.run(); requireAdmission(); AdmissionCredential c=AdmissionCredential.decode(credential,realm()); valid(c);
            if(!c.deviceId().equals(expectedDevice)) throw AdmissionCodec.invalid();
            Runnable original=challengeLeases.get(challenge.nonce()); if(original==null) throw AdmissionCodec.invalid(); original.run();
            byte[] stored=db.get("admission-challenges",challenge.nonce());
            if(stored==null || !Bytes.text(stored).equals(challenge.encode())) throw AdmissionCodec.invalid();
            challenge.validate(c,verifier,operation,clock.getAsLong()); challenge.verifyProof(c,proof);
            if(db.get("admission-peers",expectedDevice)==null && db.keys("admission-peers").size()>=MAX_PEERS) throw AdmissionCodec.invalid();
            check.run(); valid(c); challenge.validate(c,verifier,operation,clock.getAsLong());
            db.remove("admission-challenges",challenge.nonce()); challengeLeases.remove(challenge.nonce()); db.put("admission-peers",expectedDevice,Bytes.utf8(c.wire())); recordPeerEvidence(c,PeerSource.CHALLENGE_PROOF); return null;
        });
    }
    public enum PeerState { UNKNOWN, VALID_LOCALLY, EXPIRED, REVOKED, INVALID }
    public enum PeerSource { UNKNOWN_LEGACY, PUBLIC_CREDENTIAL, CHALLENGE_PROOF, NEARBY_PROOF }
    /** Observation is historical, never a live possession proof or global revocation freshness. */
    public record PeerStatus(PeerState state,PeerSource source,Long observedAt,Long expiresAt) {}
    private void recordPeerEvidence(AdmissionCredential credential,PeerSource source) {
        String value="1\n"+source.name()+"\n"+clock.getAsLong()+"\n"+Bytes.sha256(Bytes.utf8(credential.wire()));
        db.put("admission-peer-evidence",credential.deviceId(),Bytes.utf8(value));
    }
    public PeerStatus peerStatus(String deviceId) throws Exception {
        Runnable check=lease();
        return db.transaction(() -> {
            check.run(); byte[] wire=db.get("admission-peers",deviceId);
            if(wire==null) return new PeerStatus(PeerState.UNKNOWN,PeerSource.UNKNOWN_LEGACY,null,null);
            try {
                AdmissionCredential c=AdmissionCredential.decode(Bytes.text(wire),realm());
                if(!c.deviceId().equals(deviceId)) throw AdmissionCodec.invalid();
                PeerSource source=PeerSource.UNKNOWN_LEGACY; Long observed=null;
                byte[] metadata=db.get("admission-peer-evidence",deviceId);
                if(metadata!=null) {
                    if(metadata.length>180) throw AdmissionCodec.invalid();
                    String[] parts=Bytes.text(metadata).split("\n",-1);
                    if(parts.length!=4 || !parts[0].equals("1") || !parts[3].equals(Bytes.sha256(wire))) throw AdmissionCodec.invalid();
                    source=PeerSource.valueOf(parts[1]); observed=AdmissionCodec.number(parts[2]);
                }
                PeerState state=db.get("admission-revoked",c.credentialId())!=null?PeerState.REVOKED:
                        clock.getAsLong()>=c.expiresAt()?PeerState.EXPIRED:
                        clock.getAsLong()<c.notBefore()?PeerState.INVALID:PeerState.VALID_LOCALLY;
                check.run(); return new PeerStatus(state,source,observed,c.expiresAt());
            } catch(AdmissionException | IllegalArgumentException invalid) {
                check.run(); return new PeerStatus(PeerState.INVALID,PeerSource.UNKNOWN_LEGACY,null,null);
            }
        });
    }
    public void requirePeer(String deviceId) throws Exception {
        db.transaction(() -> {
            requireAdmission(); byte[] wire=db.get("admission-peers",deviceId); if(wire==null) throw AdmissionCodec.invalid();
            AdmissionCredential c=AdmissionCredential.decode(Bytes.text(wire),realm());
            if(!c.deviceId().equals(deviceId)) throw AdmissionCodec.invalid(); valid(c); return null;
        });
    }
    /** Public membership evidence only: does not verify a contact or prove a live connection. */
    public void installPeerCredential(String wire,String expectedDevice) throws Exception {
        Runnable check=lease();
        db.transaction(() -> {
            check.run(); AdmissionCredential c=AdmissionCredential.decode(wire,realm()); valid(c);
            if(!c.deviceId().equals(expectedDevice)) throw AdmissionCodec.invalid();
            if(db.get("admission-peers",expectedDevice)==null && db.keys("admission-peers").size()>=MAX_PEERS) throw AdmissionCodec.invalid();
            db.put("admission-peers",expectedDevice,Bytes.utf8(wire)); recordPeerEvidence(c,PeerSource.PUBLIC_CREDENTIAL); return null;
        });
    }
    /** Signs only a purpose-separated hash of the existing role/identity/nonces Signal transcript. */
    public String proveNearby(byte[] signalTranscript) throws Exception {
        Runnable check=lease();
        return db.transaction(() -> {
            check.run(); AdmissionCredential c=requireAdmission(); byte[] key=seed("device");
            try {
                String proof=AdmissionCodec.sign("nearby",key,c.realmId(),c.credentialId(),
                    Bytes.sha256(Bytes.utf8(c.wire())),Bytes.sha256(signalTranscript));
                check.run(); valid(c); return proof;
            } finally { Arrays.fill(key,(byte)0); }
        });
    }
    /** Engine must first verify the original Signal proof against the authenticated peer card. */
    public void verifyNearby(String credential,String proof,String expectedDevice,byte[] signalTranscript) throws Exception {
        Runnable check=lease();
        db.transaction(() -> {
            check.run(); requireAdmission(); AdmissionCredential c=AdmissionCredential.decode(credential,realm()); valid(c);
            if(!c.deviceId().equals(expectedDevice)) throw AdmissionCodec.invalid();
            String[] fields=AdmissionCodec.fields("nearby",proof,4);
            if(!Arrays.equals(fields,new String[]{c.realmId(),c.credentialId(),Bytes.sha256(Bytes.utf8(c.wire())),
                    Bytes.sha256(signalTranscript)})) throw AdmissionCodec.invalid();
            AdmissionCodec.verify("nearby",proof,c.devicePublicKey(),fields);
            check.run(); installPeerCredential(credential,expectedDevice); recordPeerEvidence(c,PeerSource.NEARBY_PROOF); return null;
        });
    }
    public AdmissionRevocation revokeAdmission(String credential,boolean confirmed,String reason) throws Exception {
        Runnable check=lease();
        return db.transaction(() -> {
            check.run(); if(!confirmed) throw AdmissionCodec.invalid(); authority();
            AdmissionCredential c=AdmissionCredential.decode(credential,realm());
            if(db.get("admission-revoked",c.credentialId())!=null) throw new AdmissionException(AdmissionException.Code.REVOKED);
            long sequence=read("sequence")==null?1:AdmissionCodec.number(read("sequence"))+1;
            byte[] key=seed("authority");
            try {
                AdmissionRevocation r=AdmissionRevocation.issue(realm(),key,c,sequence,clock.getAsLong(),reason);
                check.run(); applyRevocation(r.wire()); write("sequence",""+sequence); return r;
            } finally { Arrays.fill(key,(byte)0); }
        });
    }
    /** Explicit renewal consumes a new request and revokes the old credential in one transaction.
     * Export both signed objects to the relay/peers; offline observers only apply known revocations. */
    public Renewal renewAdmission(Review review,String oldCredential,boolean confirmed,long ttl) throws Exception {
        return db.transaction(() -> {
            consent(review,confirmed); AdmissionCredential old=AdmissionCredential.decode(oldCredential,realm());
            if(!old.devicePublicKey().equals(review.request.devicePublicKey()) ||
                    !old.signalPublicKey().equals(review.request.signalPublicKey()) ||
                    db.get("admission-revoked",old.credentialId())!=null) throw AdmissionCodec.invalid();
            AdmissionCredential next=approveAdmission(review,confirmed,ttl);
            AdmissionRevocation revoked=revokeAdmission(oldCredential,confirmed,"policy");
            return new Renewal(next,revoked);
        });
    }
    public record Renewal(AdmissionCredential credential,AdmissionRevocation revocation) {
        @Override public String toString() { return "AdmissionRenewal[redacted]"; }
    }
    public void applyRevocation(String wire) throws Exception {
        Runnable check=lease();
        db.transaction(() -> {
            check.run(); AdmissionRevocation r=AdmissionRevocation.decode(wire,realm());
            byte[] old=db.get("admission-revoked",r.credentialId());
            if(old!=null) {
                if(!Bytes.text(old).equals(wire)) throw AdmissionCodec.invalid(); return null;
            }
            if(db.keys("admission-revoked").size()>=MAX_PEERS) throw AdmissionCodec.invalid();
            db.put("admission-revoked",r.credentialId(),Bytes.utf8(wire));
            // Cancel pending deliveries only for the exact known credential, preserving history
            // and ratchet state. Rewrapping/renewal never rewinds an already advanced ratchet.
            boolean own=false; String mine=read("credential");
            if(mine!=null) own=AdmissionCredential.decode(mine,realm()).credentialId().equals(r.credentialId());
            if(own) connectivity.admissionInvalidated();
            java.util.Set<String> affected=new java.util.HashSet<>();
            for(String peer:db.keys("admission-peers")) {
                AdmissionCredential c=AdmissionCredential.decode(Bytes.text(db.get("admission-peers",peer)),realm());
                if(c.credentialId().equals(r.credentialId())) affected.add(peer);
            }
            for(String key:db.keys("outbox")) {
                org.json.JSONObject queued=new org.json.JSONObject(Bytes.text(db.get("outbox",key)));
                if(own || affected.contains(queued.getString("peer"))) db.remove("outbox",key);
            }
            return null;
        });
    }
}
