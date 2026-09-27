package app.umbra.admission;

import app.umbra.core.Bytes;
import app.umbra.crypto.Engine;
import app.umbra.data.Records;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

public class AdmissionServiceTest {
    private static final long NOW=Bytes.now();
    private static final String VERIFIER=Bytes.sha256(Bytes.utf8("synthetic verifier"));
    private static final String OPERATION=Bytes.sha256(Bytes.utf8("synthetic operation"));
    /** Serial transactions and unlock epochs; not a claim of Android SQLite/Keystore coverage. */
    static final class Memory implements Records {
        Map<String,byte[]> rows=new LinkedHashMap<>(); boolean open=true,fail=false; long epoch;
        void lock() { open=false; epoch++; }
        void unlock() { open=true; epoch++; }
        void gate() { if(!open) throw new SecurityException("Locked"); }
        public byte[] get(String b,String k) { gate(); byte[] v=rows.get(b+":"+k); return v==null?null:v.clone(); }
        public void put(String b,String k,byte[] v) { gate(); rows.put(b+":"+k,v.clone()); }
        public void remove(String b,String k) { gate(); rows.remove(b+":"+k); }
        public List<String> keys(String b) { gate(); return rows.keySet().stream().filter(k->k.startsWith(b+":")).map(k->k.substring(b.length()+1)).toList(); }
        public synchronized <T>T transaction(Work<T> work)throws Exception {
            gate(); Map<String,byte[]> before=new LinkedHashMap<>(rows);
            try { T v=work.run(); if(fail) throw new IllegalStateException("Synthetic disk failure"); return v; }
            catch(Exception|Error e) { rows=before; throw e; }
        }
        public Runnable authorization() { gate(); long captured=epoch; return ()->{ gate(); if(epoch!=captured) throw new SecurityException("Old lease"); }; }
    }
    static final class Device {
        final Memory db=new Memory(); final AtomicLong clock=new AtomicLong(NOW);
        final Engine engine=new Engine(db); final AdmissionService admission=new AdmissionService(db,clock::get);
        Device()throws Exception { engine.initialize("synthetic"); }
    }
    static final class Setup {
        final Device admin=new Device(),a=new Device(),b=new Device(); final RealmConfig realm;
        Setup()throws Exception {
            realm=admin.admission.createAdmissionRealm(true);
            a.admission.installRealmConfig(realm.encode(),true); b.admission.installRealmConfig(realm.encode(),true);
        }
        AdmissionCredential enroll(Device device)throws Exception {
            AdmissionRequest r=device.admission.createAdmissionRequest();
            AdmissionCredential c=admin.admission.approveAdmission(admin.admission.reviewAdmissionRequest(r.wire()),true,3600);
            device.admission.installAdmissionCredential(c.wire()); return c;
        }
    }
    private static void denied(Records.Work<?> action) throws Exception {
        try { action.run(); fail("Expected admission rejection"); } catch(SecurityException expected) { /* required rejection */ }
    }
    @Test public void missingRealmWithRevocationCannotReinitialize()throws Exception {
        Setup s=new Setup(); AdmissionCredential c=s.enroll(s.a);
        AdmissionRevocation r=s.admin.admission.revokeAdmission(c.wire(),true,"policy");
        s.a.admission.applyRevocation(r.wire());
        s.a.db.rows.keySet().removeIf(k->k.startsWith("admission") && !k.startsWith("admission-revoked:"));
        assertEquals(AdmissionService.State.INVALID,s.a.admission.getAdmissionState());
        denied(()->{s.a.admission.installRealmConfig(s.realm.encode(),true);return null;});
        denied(()->s.a.admission.createAdmissionRealm(true));
    }
    @Test public void realmImportAndContactIdentityDoNotAdmit()throws Exception {
        Setup s=new Setup(); assertEquals(AdmissionService.State.NOT_ADMITTED,s.a.admission.getAdmissionState());
        denied(s.a.admission::requireAdmission); assertTrue(s.a.engine.initialized());
        assertTrue(s.a.db.keys("trusted").isEmpty());
        s.enroll(s.a); assertEquals(AdmissionService.State.ADMITTED,s.a.admission.getAdmissionState());
        assertTrue(s.a.db.keys("trusted").isEmpty()); assertEquals(AdmissionService.State.NOT_ADMITTED,s.b.admission.getAdmissionState());
    }
    @Test public void copiedCredentialCannotOpenDifferentDevice()throws Exception {
        Setup s=new Setup(); AdmissionCredential c=s.enroll(s.a); s.b.admission.createAdmissionRequest();
        denied(()->{s.b.admission.installAdmissionCredential(c.wire()); return null;});
        s.b.db.put("admission","credential",Bytes.utf8(c.wire()));
        denied(s.b.admission::requireAdmission); assertEquals(AdmissionService.State.INVALID,s.b.admission.getAdmissionState());
    }
    @Test public void realmSubstitutionFailsClosed()throws Exception {
        Setup s=new Setup(); Device attacker=new Device(); RealmConfig other=attacker.admission.createAdmissionRealm(true);
        denied(()->{s.a.admission.installRealmConfig(other.encode(),true); return null;});
        assertEquals(s.realm,s.a.admission.getRealmInfo());
    }
    @Test public void expiryAndRevocationAffectOnlyOneCredential()throws Exception {
        Setup s=new Setup(); AdmissionCredential a=s.enroll(s.a); s.enroll(s.b);
        AdmissionRevocation r=s.admin.admission.revokeAdmission(a.wire(),true,"device_lost");
        s.a.admission.applyRevocation(r.wire());
        assertEquals(AdmissionService.State.REVOKED,s.a.admission.getAdmissionState());
        denied(s.a.admission::requireAdmission); s.b.admission.requireAdmission();
        s.b.clock.set(NOW+3600); denied(s.b.admission::requireAdmission);
        assertEquals(AdmissionService.State.EXPIRED,s.b.admission.getAdmissionState());
        s.a.admission.applyRevocation(r.wire()); denied(()->{s.a.admission.installAdmissionCredential(a.wire()); return null;});
    }
    @Test public void possessionBindsPeerVerifierOperationAndIsOneUse()throws Exception {
        Setup s=new Setup(); AdmissionCredential a=s.enroll(s.a); s.enroll(s.b);
        AdmissionChallenge challenge=s.b.admission.challenge(a.wire(),VERIFIER,OPERATION);
        assertEquals(challenge,AdmissionChallenge.decode(challenge.encode()));
        String proof=s.a.admission.prove(challenge,VERIFIER,OPERATION);
        denied(()->s.a.admission.prove(challenge,VERIFIER,Bytes.sha256(new byte[]{1})));
        denied(()->s.b.admission.prove(challenge,VERIFIER,OPERATION));
        denied(()->{s.b.admission.acceptProof(a.wire(),challenge,proof,s.b.engine.id(),VERIFIER,OPERATION); return null;});
        s.b.admission.acceptProof(a.wire(),challenge,proof,s.a.engine.id(),VERIFIER,OPERATION);
        s.b.admission.requirePeer(s.a.engine.id());
        denied(()->{s.b.admission.acceptProof(a.wire(),challenge,proof,s.a.engine.id(),VERIFIER,OPERATION); return null;});
    }
    @Test public void proofExpiryAndLockInvalidatePendingWork()throws Exception {
        Setup s=new Setup(); AdmissionCredential a=s.enroll(s.a); s.enroll(s.b);
        AdmissionChallenge challenge=s.b.admission.challenge(a.wire(),VERIFIER,OPERATION);
        String proof=s.a.admission.prove(challenge,VERIFIER,OPERATION);
        s.b.clock.addAndGet(30);
        denied(()->{s.b.admission.acceptProof(a.wire(),challenge,proof,s.a.engine.id(),VERIFIER,OPERATION); return null;});
        s.a.db.lock(); denied(()->s.a.admission.prove(challenge,VERIFIER,OPERATION));
    }
    @Test public void adminApprovalNeedsConfirmationAndFreshVaultLease()throws Exception {
        Setup s=new Setup(); AdmissionRequest r=s.a.admission.createAdmissionRequest();
        AdmissionService.Review review=s.admin.admission.reviewAdmissionRequest(r.wire());
        denied(()->s.admin.admission.approveAdmission(review,false,3600));
        s.admin.db.lock(); s.admin.db.unlock();
        denied(()->s.admin.admission.approveAdmission(review,true,3600));
        AdmissionService.Review fresh=s.admin.admission.reviewAdmissionRequest(r.wire());
        s.admin.clock.set(NOW+600); denied(()->s.admin.admission.approveAdmission(fresh,true,3600));
    }
    @Test public void requestReplayAndConcurrentApprovalsHaveOneWinner()throws Exception {
        Setup s=new Setup(); AdmissionRequest r=s.a.admission.createAdmissionRequest();
        AdmissionService.Review a=s.admin.admission.reviewAdmissionRequest(r.wire()),b=s.admin.admission.reviewAdmissionRequest(r.wire());
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results=pool.invokeAll(List.of(
                ()->{try{s.admin.admission.approveAdmission(a,true,3600);return true;}catch(SecurityException e){return false;}},
                ()->{try{s.admin.admission.approveAdmission(b,true,3600);return true;}catch(SecurityException e){return false;}}));
            assertNotEquals(results.get(0).get(),results.get(1).get());
        } finally { pool.shutdownNow(); }
        denied(()->s.admin.admission.reviewAdmissionRequest(r.wire()));
    }
    @Test public void failedCommitDoesNotPersistCredentialOrConsumeRequest()throws Exception {
        Setup s=new Setup(); AdmissionRequest r=s.a.admission.createAdmissionRequest();
        s.admin.db.fail=true;
        try { s.admin.admission.approveAdmission(newReviewBeforeFailure(s,r),true,3600); fail(); }
        catch(IllegalStateException expected) { assertTrue(s.admin.db.keys("admission-decisions").isEmpty()); }
        s.admin.db.fail=false;
        AdmissionCredential c=s.admin.admission.approveAdmission(s.admin.admission.reviewAdmissionRequest(r.wire()),true,3600);
        s.a.db.fail=true;
        try{s.a.admission.installAdmissionCredential(c.wire());fail();}catch(IllegalStateException expected){assertNull(s.a.db.get("admission","credential"));}
        s.a.db.fail=false; s.a.admission.installAdmissionCredential(c.wire());
        assertEquals(AdmissionService.State.ADMITTED,new AdmissionService(s.a.db,s.a.clock::get).getAdmissionState());
    }
    private static AdmissionService.Review newReviewBeforeFailure(Setup s,AdmissionRequest r)throws Exception {
        s.admin.db.fail=false; AdmissionService.Review review=s.admin.admission.reviewAdmissionRequest(r.wire());s.admin.db.fail=true;return review;
    }
    @Test public void signedFieldsTamperingAndUnknownFormatsFailClosed()throws Exception {
        Setup s=new Setup(); AdmissionCredential c=s.enroll(s.a);
        String[] fields=AdmissionCodec.fields("credential",c.wire(),10);
        String signature=c.wire().substring(c.wire().lastIndexOf('.')+1);
        for(int i=0;i<fields.length;i++) {
            String[] altered=fields.clone(); altered[i]=altered[i].equals("relay.nearby.turn")?"relay.nearby.turn.admin":altered[i]+"1";
            String wire="umbra:admission:credential:1:"+AdmissionCodec.encode(AdmissionCodec.body("credential",altered))+"."+signature;
            denied(()->AdmissionCredential.decode(wire,s.realm));
        }
        denied(()->AdmissionCredential.decode(c.wire().replace(":1:",":2:"),s.realm));
        denied(()->AdmissionCredential.decode(c.wire()+"=",s.realm));
        String request=s.b.admission.createAdmissionRequest().wire();
        denied(()->AdmissionCredential.decode(request,s.realm));
    }
    @Test public void forgedAuthorityAndSignatureCannotGrantAdmission()throws Exception {
        Setup s=new Setup(); AdmissionCredential c=s.enroll(s.a);
        byte[] attacker=Bytes.random(32); String[] fields=AdmissionCodec.fields("credential",c.wire(),10);
        denied(()->AdmissionCredential.decode(AdmissionCodec.sign("credential",attacker,fields),s.realm));
        byte[] signature=AdmissionCodec.decode(c.wire().substring(c.wire().lastIndexOf('.')+1),64);signature[0]^=1;
        String altered=c.wire().substring(0,c.wire().lastIndexOf('.')+1)+AdmissionCodec.encode(signature);
        denied(()->AdmissionCredential.decode(altered,s.realm));
        assertFalse(c.toString().contains(c.wire())); assertFalse(s.realm.toString().contains(s.realm.authorityPublicKey()));
    }
    @Test public void realEngineAndSignalRequireIndependentMembershipAndHumanVerification()throws Exception {
        Setup s=new Setup();
        s.a.engine.importCard(s.b.engine.createCard()); s.b.engine.importCard(s.a.engine.createCard());
        String safety=Bytes.safetyCode(s.a.engine.id(),s.b.engine.id());
        s.a.engine.verify(s.b.engine.id(),safety); s.b.engine.verify(s.a.engine.id(),safety);
        denied(()->s.a.engine.sendText(s.b.engine.id(),"synthetic denied",600));
        AdmissionCredential a=s.enroll(s.a); s.enroll(s.b);
        // Updated public cards carry distinct authority-signed membership evidence.
        s.a.engine.importCard(s.b.engine.createCard()); s.b.engine.importCard(s.a.engine.createCard());
        s.a.engine.sendText(s.b.engine.id(),"synthetic admitted",600);
        s.b.engine.receive(s.a.engine.outbox().get(0).getJSONObject("envelope"));
        assertEquals("synthetic admitted",s.b.engine.messages(s.a.engine.id()).get(0).getString("text"));
        AdmissionRevocation revoked=s.admin.admission.revokeAdmission(a.wire(),true,"policy");
        s.a.admission.applyRevocation(revoked.wire()); s.b.admission.applyRevocation(revoked.wire());
        denied(()->s.a.engine.sendText(s.b.engine.id(),"denied after revocation",600));
        denied(()->s.b.engine.sendText(s.a.engine.id(),"denied revoked recipient",600));
        assertEquals(1,s.b.engine.messages(s.a.engine.id()).size());
        assertTrue(s.a.engine.outbox().isEmpty()); assertTrue(s.b.engine.outbox().isEmpty());
    }
    @Test public void nearbyRetainsSignalProofAndAddsAdmissionPossession()throws Exception {
        Setup s=new Setup(); s.enroll(s.a); s.enroll(s.b);
        s.a.engine.importCard(s.b.engine.createCard()); s.b.engine.importCard(s.a.engine.createCard());
        byte[] aNonce=Bytes.random(32),bNonce=Bytes.random(32);
        byte[] proof=s.a.engine.proveNearby(true,s.b.engine.id(),aNonce,bNonce);
        s.b.engine.verifyNearby(true,s.a.engine.id(),aNonce,bNonce,proof,true);
        assertEquals(Engine.TrustState.UNVERIFIED,s.b.engine.trustState(s.a.engine.id()));
        denied(()->{s.b.engine.verifyNearby(true,s.a.engine.id(),aNonce,Bytes.random(32),proof,true);return null;});
        org.json.JSONObject copied=new org.json.JSONObject(Bytes.text(proof));
        copied.put("credential",s.b.admission.requireAdmission().wire());
        denied(()->{s.b.engine.verifyNearby(true,s.a.engine.id(),aNonce,bNonce,Bytes.utf8(copied.toString()),true);return null;});
    }
    @Test public void deviceLinkingDoesNotGrantAdmission()throws Exception {
        Setup s=new Setup(); s.enroll(s.a);
        app.umbra.devices.DeviceService a=new app.umbra.devices.DeviceService(s.a.db),b=new app.umbra.devices.DeviceService(s.b.db);
        a.migrate(); String challenge=a.challenge(b.publicKey(),600);
        String response=b.respond(b.reviewChallenge(challenge),true);
        String approved=a.approve(a.reviewResponse(response),true); b.complete(approved);
        assertEquals(AdmissionService.State.NOT_ADMITTED,s.b.admission.getAdmissionState());
        denied(s.b.admission::requireAdmission);
        s.enroll(s.b); s.b.admission.requireAdmission();
        assertNotEquals(s.a.admission.requireAdmission().credentialId(),s.b.admission.requireAdmission().credentialId());
    }
    @Test public void challengeCannotSurviveLockOrServiceRestart()throws Exception {
        Setup s=new Setup(); AdmissionCredential a=s.enroll(s.a); s.enroll(s.b);
        AdmissionChallenge challenge=s.b.admission.challenge(a.wire(),VERIFIER,OPERATION);
        String proof=s.a.admission.prove(challenge,VERIFIER,OPERATION);
        s.b.db.lock(); s.b.db.unlock();
        denied(()->{s.b.admission.acceptProof(a.wire(),challenge,proof,s.a.engine.id(),VERIFIER,OPERATION);return null;});
        AdmissionService restarted=new AdmissionService(s.b.db,s.b.clock::get);
        denied(()->{restarted.acceptProof(a.wire(),challenge,proof,s.a.engine.id(),VERIFIER,OPERATION);return null;});
    }
    @Test public void renewalRequiresNewRequestAndRevokesOldCredential()throws Exception {
        Setup s=new Setup(); AdmissionCredential old=s.enroll(s.a);
        AdmissionRequest request=s.a.admission.createAdmissionRequest();
        AdmissionService.Renewal renewal=s.admin.admission.renewAdmission(s.admin.admission.reviewAdmissionRequest(request.wire()),old.wire(),true,3600);
        s.a.admission.applyRevocation(renewal.revocation().wire());
        denied(s.a.admission::requireAdmission);
        s.a.admission.installAdmissionCredential(renewal.credential().wire());
        assertNotEquals(old.credentialId(),s.a.admission.requireAdmission().credentialId());
        denied(()->s.admin.admission.reviewAdmissionRequest(request.wire()));
    }
    @Test public void degenerateEd25519PublicKeysAreRejected()throws Exception {
        byte[] zero=new byte[32],identity=new byte[32]; identity[0]=1;
        denied(()->AdmissionCodec.publicKey(AdmissionCodec.encode(zero)));
        denied(()->AdmissionCodec.publicKey(AdmissionCodec.encode(identity)));
    }

    @Test public void rejectionIsAuthenticatedAndRequestCannotBeReapproved()throws Exception {
        Setup s=new Setup(); AdmissionRequest request=s.a.admission.createAdmissionRequest();
        var rejected=s.admin.admission.rejectAdmission(s.admin.admission.reviewAdmissionRequest(request.wire()),true);
        s.a.admission.installRejection(rejected.wire());
        assertEquals(AdmissionService.State.REJECTED,s.a.admission.getAdmissionState());
        denied(()->s.admin.admission.reviewAdmissionRequest(request.wire()));
        AdmissionRequest fresh=s.a.admission.createAdmissionRequest();
        assertNotEquals(request.requestId(),fresh.requestId());
        denied(()->{s.a.admission.installRejection(rejected.wire());return null;});
    }
    @Test public void expiryIsRecheckedAfterWaitingForTransaction()throws Exception {
        Setup s=new Setup(); var request=s.a.admission.createAdmissionRequest();
        var review=s.admin.admission.reviewAdmissionRequest(request.wire());
        ExecutorService pool=Executors.newSingleThreadExecutor(); java.util.concurrent.CountDownLatch attempting=new java.util.concurrent.CountDownLatch(1);
        Future<AdmissionCredential> approval;
        try {
            synchronized(s.admin.db) {
                approval=pool.submit(()->{attempting.countDown();return s.admin.admission.approveAdmission(review,true,3600);});
                assertTrue(attempting.await(2,TimeUnit.SECONDS));
                s.admin.clock.set(request.expiresAt());
            }
            try { approval.get(2,TimeUnit.SECONDS); fail("Expired queued approval accepted"); }
            catch(ExecutionException expected) { assertTrue(expected.getCause() instanceof SecurityException); }
            assertTrue(s.admin.db.keys("admission-decisions").isEmpty());
        } finally { pool.shutdownNow(); }
    }
    @Test public void missingPrivateMaterialIsNeverRegenerated()throws Exception {
        Setup s=new Setup(); s.enroll(s.a);
        s.a.db.remove("admission-secret","device");
        assertEquals(AdmissionService.State.INVALID,s.a.admission.getAdmissionState());
        denied(s.a.admission::requireAdmission);
        denied(()->{s.a.admission.installRealmConfig(s.realm.encode(),true);return null;});
        assertNull(s.a.db.get("admission-secret","device"));
        s.admin.db.remove("admission-secret","authority");
        denied(()->s.admin.admission.createAdmissionRealm(true));
        assertNull(s.admin.db.get("admission-secret","authority"));
    }

}
