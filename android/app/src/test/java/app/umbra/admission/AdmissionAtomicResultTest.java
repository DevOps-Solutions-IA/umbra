package app.umbra.admission;

import org.junit.Test;
import static org.junit.Assert.*;

/** Real signed objects and Signal identities; transaction fault injection is JVM-only. */
public class AdmissionAtomicResultTest {
    private AdmissionService.Renewal renewal(AdmissionServiceTest.Setup s,AdmissionCredential old) throws Exception {
        AdmissionRequest request=s.a.admission.createAdmissionRequest();
        return s.admin.admission.renewAdmission(s.admin.admission.reviewAdmissionRequest(request.wire()),old.wire(),true,3600);
    }
    @Test public void malformedSecondObjectPreservesOldCredentialAndPendingRequest() throws Exception {
        var s=new AdmissionServiceTest.Setup(); var old=s.enroll(s.a); var pair=renewal(s,old);
        String pending=s.a.admission.pendingRequest().wire();
        assertThrows(SecurityException.class,()->s.a.admission.installRenewal(pair.credential().wire(),"invalid"));
        assertEquals(old,s.a.admission.requireAdmission());
        assertEquals(pending,s.a.admission.pendingRequest().wire());
        s.a.admission.installRenewal(pair.credential().wire(),pair.revocation().wire());
        assertEquals(pair.credential(),s.a.admission.requireAdmission());
        assertNotNull(s.a.db.get("admission-revoked",old.credentialId()));
        assertThrows(SecurityException.class,()->s.a.admission.installRenewal(pair.credential().wire(),pair.revocation().wire()));
    }
    @Test public void unrelatedSignedRevocationCannotBePairedWithRenewal() throws Exception {
        var s=new AdmissionServiceTest.Setup(); var old=s.enroll(s.a); var other=s.enroll(s.b);
        var pair=renewal(s,old); var unrelated=s.admin.admission.revokeAdmission(other.wire(),true,"policy");
        assertThrows(SecurityException.class,()->s.a.admission.installRenewal(pair.credential().wire(),unrelated.wire()));
        assertEquals(old,s.a.admission.requireAdmission());
        assertNull(s.a.db.get("admission-revoked",other.credentialId()));
    }
    @Test public void failedStorageLeavesRenewalRetryable() throws Exception {
        var s=new AdmissionServiceTest.Setup(); var old=s.enroll(s.a); var pair=renewal(s,old);
        String pending=s.a.admission.pendingRequest().wire(); s.a.db.fail=true;
        assertThrows(IllegalStateException.class,()->s.a.admission.installRenewal(pair.credential().wire(),pair.revocation().wire()));
        s.a.db.fail=false;
        assertEquals(old,s.a.admission.requireAdmission()); assertEquals(pending,s.a.admission.pendingRequest().wire());
        assertNull(s.a.db.get("admission-revoked",old.credentialId()));
        s.a.admission.installRenewal(pair.credential().wire(),pair.revocation().wire());
        assertEquals(pair.credential(),s.a.admission.requireAdmission());
    }
    @Test public void selfApprovalRollbackDoesNotConsumeReviewInMemory() throws Exception {
        var s=new AdmissionServiceTest.Setup(); var request=s.admin.admission.createAdmissionRequest();
        var review=s.admin.admission.reviewAdmissionRequest(request.wire()); s.admin.db.fail=true;
        assertThrows(IllegalStateException.class,()->s.admin.admission.approveAndInstallOwnAdmission(review,true,3600));
        s.admin.db.fail=false;
        assertNull(s.admin.db.get("admission","credential"));
        assertTrue(s.admin.db.keys("admission-decisions").isEmpty());
        var credential=s.admin.admission.approveAndInstallOwnAdmission(review,true,3600);
        assertEquals(credential,s.admin.admission.requireAdmission());
        assertThrows(SecurityException.class,()->s.admin.admission.approveAdmission(review,true,3600));
    }
    @Test public void reviewFromPreviousUnlockCannotApprove() throws Exception {
        var s=new AdmissionServiceTest.Setup(); var request=s.admin.admission.createAdmissionRequest();
        var review=s.admin.admission.reviewAdmissionRequest(request.wire()); s.admin.db.lock(); s.admin.db.unlock();
        assertThrows(SecurityException.class,()->s.admin.admission.approveAndInstallOwnAdmission(review,true,3600));
        assertNull(s.admin.db.get("admission","credential"));
    }
    @Test public void cancellationRejectsLateApprovalAndCannotCancelReplacementRequest() throws Exception {
        var s=new AdmissionServiceTest.Setup(); var request=s.a.admission.createAdmissionRequest();
        var credential=s.admin.admission.approveAdmission(s.admin.admission.reviewAdmissionRequest(request.wire()),true,3600);
        s.a.admission.cancelPendingRequest(request.requestId());
        assertEquals(AdmissionService.State.NOT_ADMITTED,s.a.admission.getAdmissionState());
        assertThrows(SecurityException.class,()->s.a.admission.installAdmissionCredential(credential.wire()));
        var next=s.a.admission.createAdmissionRequest();
        assertNotEquals(request.requestId(),next.requestId());
        assertThrows(SecurityException.class,()->s.a.admission.cancelPendingRequest(request.requestId()));
        assertEquals(next,s.a.admission.pendingRequest());
        assertThrows(SecurityException.class,()->s.a.admission.installAdmissionCredential(credential.wire()));
    }
    @Test public void snapshotsSeparateExpiryAndDoNotGrantAuthority() throws Exception {
        var s=new AdmissionServiceTest.Setup(); assertTrue(s.admin.admission.isAdmissionAuthority());
        assertFalse(s.a.admission.isAdmissionAuthority());
        assertThrows(SecurityException.class,()->s.a.admission.issuedCredentials());
        var c=s.enroll(s.a); var pending=s.a.admission.createAdmissionRequest();
        var snapshot=s.a.admission.status(); assertEquals(AdmissionService.State.ADMITTED,snapshot.state());
        assertEquals(Long.valueOf(c.expiresAt()),snapshot.credentialExpiresAt());
        assertEquals(Long.valueOf(pending.expiresAt()),snapshot.requestExpiresAt());
        assertEquals(1,s.admin.admission.issuedCredentials().size());
        s.admin.admission.revokeAdmission(c.wire(),true,"policy");
        assertTrue(s.admin.admission.issuedCredentials().get(0).revoked());
        s.a.db.lock(); assertThrows(SecurityException.class,()->s.a.admission.status());
        assertThrows(SecurityException.class,()->s.a.admission.isAdmissionAuthority());
    }
}
