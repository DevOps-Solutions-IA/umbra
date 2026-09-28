package app.umbra.admission;

import app.umbra.core.AccessGate;
import app.umbra.privacy.OperationFailure;
import app.umbra.connectivity.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class AdmissionFailureContractTest {
    @Test public void typedMembershipFailuresNeverRequireParsingExceptionText() throws Exception {
        var s=new AdmissionServiceTest.Setup();
        assertEquals(AdmissionException.Code.NOT_ADMITTED,assertThrows(AdmissionException.class,s.a.admission::requireAdmission).code());
        assertEquals(AdmissionException.Code.NOT_AUTHORITY,assertThrows(AdmissionException.class,s.a.admission::issuedCredentials).code());
        var credential=s.enroll(s.a);s.b.admission.createAdmissionRequest();
        assertEquals(AdmissionException.Code.WRONG_DEVICE,assertThrows(AdmissionException.class,()->s.b.admission.installAdmissionCredential(credential.wire())).code());
        s.a.clock.set(credential.expiresAt());
        var expired=assertThrows(AdmissionException.class,s.a.admission::requireAdmission);
        assertEquals(OperationFailure.ADMISSION_EXPIRED,OperationFailure.classify(expired));
        s.a.clock.set(credential.issuedAt());
        s.a.admission.applyRevocation(s.admin.admission.revokeAdmission(credential.wire(),true,"policy").wire());
        assertEquals(AdmissionException.Code.REVOKED,assertThrows(AdmissionException.class,s.a.admission::requireAdmission).code());
        s.a.db.lock();
        assertFalse(assertThrows(SecurityException.class,()->s.a.admission.installAdmissionCredential("not a credential")) instanceof AdmissionException);
    }
    @Test public void connectivityReasonsAreAvailableOnlyAfterVaultAuthorization() throws Exception {
        var s=new AdmissionServiceTest.Setup();
        var online=new ConnectivityService(s.a.db,s.a.admission,true);
        assertThrows(AccessGate.LockedException.class,()->online.connect("bad",false));
        online.vaultUnlocked();
        assertEquals(ConnectivityException.Code.CONSENT_REQUIRED,assertThrows(ConnectivityException.class,()->online.connect("bad",false)).code());
        assertEquals(AdmissionException.Code.NOT_ADMITTED,assertThrows(AdmissionException.class,()->online.connect("https://relay.example.test",true)).code());
        var offline=new ConnectivityService(s.a.db,s.a.admission,false);offline.vaultUnlocked();
        assertEquals(ConnectivityException.Code.EDITION,assertThrows(ConnectivityException.class,()->offline.connect("https://relay.example.test",true)).code());
        s.a.db.lock();assertFalse(assertThrows(SecurityException.class,()->offline.connect("bad",false)) instanceof ConnectivityException);
    }
}
