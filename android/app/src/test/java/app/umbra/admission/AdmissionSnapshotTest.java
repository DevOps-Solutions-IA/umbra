package app.umbra.admission;

import app.umbra.core.Bytes;
import org.junit.Test;
import static org.junit.Assert.*;

public class AdmissionSnapshotTest {
    @Test public void publicEvidenceDoesNotClaimPossessionOrContactTrust() throws Exception {
        var s=new AdmissionServiceTest.Setup(); var c=s.enroll(s.a); s.enroll(s.b);
        assertEquals(AdmissionService.PeerState.UNKNOWN,s.b.admission.peerStatus(c.deviceId()).state());
        s.b.admission.installPeerCredential(c.wire(),c.deviceId());
        var status=s.b.admission.peerStatus(c.deviceId());
        assertEquals(AdmissionService.PeerState.VALID_LOCALLY,status.state());
        assertEquals(AdmissionService.PeerSource.PUBLIC_CREDENTIAL,status.source());
        assertEquals(Long.valueOf(s.b.clock.get()),status.observedAt());
        assertTrue(s.b.db.keys("trusted").isEmpty());
        s.b.clock.set(c.expiresAt());
        assertEquals(AdmissionService.PeerState.EXPIRED,s.b.admission.peerStatus(c.deviceId()).state());
    }
    @Test public void legacyRecordsRemainUnknownProvenanceAndCorruptMetadataFailsClosed() throws Exception {
        var s=new AdmissionServiceTest.Setup(); var c=s.enroll(s.a);
        s.b.admission.installPeerCredential(c.wire(),c.deviceId());
        s.b.db.remove("admission-peer-evidence",c.deviceId());
        var status=s.b.admission.peerStatus(c.deviceId());
        assertEquals(AdmissionService.PeerSource.UNKNOWN_LEGACY,status.source()); assertNull(status.observedAt());
        s.b.db.put("admission-peer-evidence",c.deviceId(),Bytes.utf8("broken"));
        assertEquals(AdmissionService.PeerState.INVALID,s.b.admission.peerStatus(c.deviceId()).state());
        s.b.db.lock(); assertThrows(SecurityException.class,()->s.b.admission.peerStatus(c.deviceId()));
    }
    @Test public void knownRevocationIsLocalAndPersistsAcrossServiceRecreation() throws Exception {
        var s=new AdmissionServiceTest.Setup(); var c=s.enroll(s.a);
        s.b.admission.installPeerCredential(c.wire(),c.deviceId());
        var revocation=s.admin.admission.revokeAdmission(c.wire(),true,"policy");
        assertEquals(AdmissionService.PeerState.VALID_LOCALLY,s.b.admission.peerStatus(c.deviceId()).state());
        s.b.admission.applyRevocation(revocation.wire());
        var reopened=new AdmissionService(s.b.db,s.b.clock::get);
        assertEquals(AdmissionService.PeerState.REVOKED,reopened.peerStatus(c.deviceId()).state());
    }
    @Test public void typedMismatchPreservesPinnedRealmAndDoesNotExposeInput() throws Exception {
        var s=new AdmissionServiceTest.Setup(); var other=new AdmissionServiceTest.Device();
        var different=other.admission.createAdmissionRealm(true);
        var failure=assertThrows(AdmissionException.class,()->s.a.admission.installRealmConfig(different.encode(),true));
        assertEquals(AdmissionException.Code.AUTHORITY_MISMATCH,failure.code());
        assertFalse(failure.toString().contains(different.realmId()));
        assertEquals(s.realm,s.a.admission.getRealmInfo());
        s.a.db.lock(); assertThrows(SecurityException.class,()->s.a.admission.installRealmConfig("malformed",true));
    }
    @Test public void authorityListingValidatesSignedRejectionsToo() throws Exception {
        var s=new AdmissionServiceTest.Setup(); var request=s.a.admission.createAdmissionRequest();
        s.admin.admission.rejectAdmission(s.admin.admission.reviewAdmissionRequest(request.wire()),true);
        assertTrue(s.admin.admission.issuedCredentials().isEmpty());
        s.admin.db.put("admission-decisions",request.requestId(),Bytes.utf8("broken"));
        assertThrows(AdmissionException.class,()->s.admin.admission.issuedCredentials());
    }
}
