package app.umbra.admission;
import org.junit.Test;
import static org.junit.Assert.*;

public class AdmissionReviewRollbackTest {
    @Test public void failedCommitDoesNotPermanentlyConsumeReview() throws Exception {
        var s=new AdmissionServiceTest.Setup(); var request=s.a.admission.createAdmissionRequest();
        var review=s.admin.admission.reviewAdmissionRequest(request.wire());
        s.admin.db.fail=true;
        assertThrows(IllegalStateException.class,()->s.admin.admission.approveAdmission(review,true,3600));
        s.admin.db.fail=false;
        assertTrue(s.admin.db.keys("admission-decisions").isEmpty());
        var credential=s.admin.admission.approveAdmission(review,true,3600);
        s.a.admission.installAdmissionCredential(credential.wire());
        assertEquals(credential,s.a.admission.requireAdmission());
        assertThrows(SecurityException.class,()->s.admin.admission.approveAdmission(review,true,3600));
    }
}
