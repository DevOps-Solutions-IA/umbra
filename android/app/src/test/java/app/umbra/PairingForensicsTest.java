package app.umbra;

import app.umbra.admission.*;
import app.umbra.core.Bytes;
import app.umbra.crypto.Engine;
import app.umbra.pairing.PairingService;
import org.junit.Test;
import static org.junit.Assert.*;

/** Baseline forensic evidence. Real Signal; MemoryRecords is explicitly not SQLite durability. */
public final class PairingForensicsTest {
    private static final class Person {
        final MemoryRecords records = new MemoryRecords();
        final Engine engine = new Engine(records);
        final PairingService pairing = new PairingService(records);
        Person() throws Exception { engine.initialize("Synthetic peer"); }
    }
    private static void ownRealm(Person p) throws Exception {
        var service=p.engine.admission(); service.createAdmissionRealm(true);
        var request=service.createAdmissionRequest();
        var credential=service.approveAdmission(service.reviewAdmissionRequest(request.wire()),true,600);
        service.installAdmissionCredential(credential.wire());
    }
    private static void complete(Person a, Person b) throws Exception {
        String invite=a.pairing.createInvitation(600);
        assertTrue(a.engine.contacts().isEmpty()); assertTrue(b.engine.contacts().isEmpty());
        String request=b.pairing.request(invite);
        assertTrue(a.engine.contacts().isEmpty()); assertTrue(b.engine.contacts().isEmpty());
        String ack=a.pairing.accept(request);
        assertEquals(1,a.engine.contacts().size()); assertTrue(b.engine.contacts().isEmpty());
        assertEquals(a.engine.id(),b.pairing.complete(ack));
        assertEquals(1,b.engine.contacts().size());
        assertEquals(Engine.TrustState.UNVERIFIED,a.engine.trustState(b.engine.id()));
        assertEquals(Engine.TrustState.UNVERIFIED,b.engine.trustState(a.engine.id()));
        assertThrows(SecurityException.class,()->a.engine.sendText(b.engine.id(),"synthetic",60));
        assertThrows(SecurityException.class,()->b.engine.sendText(a.engine.id(),"synthetic",60));
        System.out.println("PAIRING_FORENSICS lengths invite="+invite.length()+" request="+request.length()+" ack="+ack.length());
    }
    @Test public void sharedRealmCreatesContactsOnlyAtAcceptAndComplete() throws Exception {
        Person a=new Person(),b=new Person();AdmissionFixture.enroll(a.engine);AdmissionFixture.enroll(b.engine);complete(a,b);
    }
    @Test public void noAdmissionPairsButCannotConverseEvenAfterVerification() throws Exception {
        Person a=new Person(),b=new Person();complete(a,b);
        a.engine.verify(b.engine.id(),Bytes.safetyCode(a.engine.id(),b.engine.id()));
        assertThrows(SecurityException.class,()->a.engine.sendText(b.engine.id(),"synthetic",60));
        assertEquals(AdmissionService.State.UNCONFIGURED,a.engine.admission().getAdmissionState());
    }
    @Test public void differentRealmsRejectBeforeContactAndDoNotConsume() throws Exception {
        Person a=new Person(),b=new Person();ownRealm(a);ownRealm(b);
        String invite=a.pairing.createInvitation(600),request=b.pairing.request(invite);
        assertThrows(AdmissionException.class,()->a.pairing.accept(request));
        assertTrue(a.engine.contacts().isEmpty());assertTrue(b.engine.contacts().isEmpty());
        assertEquals("ISSUED",a.engine.get("pairing-issued",PairingService.invitationId(invite)).getString("state"));
        assertTrue(a.records.keys("admission-peers").isEmpty());
    }
    @Test public void admittedInviterUnconfiguredJoinerStopsAtComplete() throws Exception {
        Person a=new Person(),b=new Person();ownRealm(a);
        String invite=a.pairing.createInvitation(600),request=b.pairing.request(invite),ack=a.pairing.accept(request);
        assertEquals(1,a.engine.contacts().size());
        assertThrows(AdmissionException.class,()->b.pairing.complete(ack));
        assertTrue(b.engine.contacts().isEmpty());
        assertEquals("PENDING",b.engine.get("pairing-pending",PairingService.invitationId(invite)).getString("state"));
        assertEquals(Engine.TrustState.UNVERIFIED,a.engine.trustState(b.engine.id()));
    }
    @Test public void unconfiguredInviterAdmittedJoinerStopsAtAccept() throws Exception {
        Person a=new Person(),b=new Person();ownRealm(b);
        String invite=a.pairing.createInvitation(600),request=b.pairing.request(invite);
        assertThrows(AdmissionException.class,()->a.pairing.accept(request));
        assertTrue(a.engine.contacts().isEmpty());assertTrue(b.engine.contacts().isEmpty());
    }
    @Test public void admittedAndUnadmittedWithSamePinnedRealmCanPair() throws Exception {
        Person a=new Person(),b=new Person();ownRealm(a);
        b.engine.admission().installRealmConfig(a.engine.admission().getRealmInfo().encode(),true);
        complete(a,b);
        assertEquals(AdmissionService.State.NOT_ADMITTED,b.engine.admission().getAdmissionState());
    }
    @Test public void selfPairingLeavesInvitationAndPrekeysUnchanged() throws Exception {
        Person a=new Person();String invite=a.pairing.createInvitation(600);
        int prekeys=a.records.keys("key-expiry").size();
        assertThrows(SecurityException.class,()->a.pairing.request(invite));
        assertTrue(a.engine.contacts().isEmpty());assertTrue(a.records.keys("pairing-pending").isEmpty());
        assertEquals(prekeys,a.records.keys("key-expiry").size());
        assertEquals("ISSUED",a.engine.get("pairing-issued",PairingService.invitationId(invite)).getString("state"));
    }
}
