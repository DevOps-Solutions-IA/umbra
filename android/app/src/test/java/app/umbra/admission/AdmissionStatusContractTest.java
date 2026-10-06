package app.umbra.admission;

import app.umbra.core.Bytes;
import app.umbra.data.Records;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real admission/Signal domain; in-memory records test transactions, not Android durability. */
public class AdmissionStatusContractTest {
    private static Map<String,byte[]> snapshot(AdmissionServiceTest.Memory db) {
        Map<String,byte[]> copy=new LinkedHashMap<>();db.rows.forEach((key,value)->copy.put(key,value.clone()));return copy;
    }
    private static void unchanged(Map<String,byte[]> before,AdmissionServiceTest.Memory db) {
        assertEquals(before.keySet(),db.rows.keySet());before.forEach((key,value)->assertArrayEquals(key,value,db.rows.get(key)));
    }
    private static void invalidStatus(AdmissionService service) throws Exception {
        var status=service.status();assertEquals(AdmissionService.State.INVALID,status.state());
        assertNull(status.requestExpiresAt());assertNull(status.credentialExpiresAt());
    }
    private static void invalid(Records.Work<?> work) {
        assertEquals(AdmissionException.Code.INVALID,assertThrows(AdmissionException.class,work::run).code());
    }
    @Test public void corruptCredentialReportsInvalidWithoutRepairOrExpiry() throws Exception {
        var s=new AdmissionServiceTest.Setup();s.enroll(s.a);
        s.a.db.put("admission","credential",Bytes.utf8("corrupt-credential"));var before=snapshot(s.a.db);
        assertEquals(AdmissionService.State.INVALID,s.a.admission.getAdmissionState());invalidStatus(s.a.admission);
        invalid(s.a.admission::requireAdmission);unchanged(before,s.a.db);
        invalidStatus(new AdmissionService(s.a.db,s.a.clock::get));unchanged(before,s.a.db);
    }
    @Test public void corruptPendingReportsInvalidWithoutRepairOrExpiry() throws Exception {
        var s=new AdmissionServiceTest.Setup();s.a.admission.createAdmissionRequest();
        s.a.db.put("admission","pending",Bytes.utf8("corrupt-pending"));var before=snapshot(s.a.db);
        invalidStatus(s.a.admission);invalid(s.a.admission::pendingRequest);invalid(s.a.admission::requestAuthorization);
        invalid(()->{s.a.admission.cancelPendingRequest("incorrect");return null;});unchanged(before,s.a.db);
    }
    @Test public void corruptPendingAlongsideCredentialDoesNotProduceHealthySnapshot() throws Exception {
        var s=new AdmissionServiceTest.Setup();s.enroll(s.a);s.a.admission.createAdmissionRequest();
        s.a.db.put("admission","pending",Bytes.utf8("corrupt-renewal"));var before=snapshot(s.a.db);
        invalidStatus(s.a.admission);unchanged(before,s.a.db);
    }
    @Test public void corruptRealmDoesNotPublishCredentialExpiryOrRepairRealm() throws Exception {
        var s=new AdmissionServiceTest.Setup();s.enroll(s.a);
        s.a.db.put("admission","realm",Bytes.utf8("corrupt-realm"));var before=snapshot(s.a.db);
        invalidStatus(s.a.admission);invalid(s.a.admission::requireAdmission);unchanged(before,s.a.db);
    }
    @Test public void missingRealmWithOrphanedRecordsRemainsInvalid() throws Exception {
        var s=new AdmissionServiceTest.Setup();s.enroll(s.a);s.a.db.remove("admission","realm");var before=snapshot(s.a.db);
        invalidStatus(s.a.admission);unchanged(before,s.a.db);
    }
    @Test public void lockedRecordsNeverBecomeInvalidSnapshotOrAbsentRequest() throws Exception {
        var s=new AdmissionServiceTest.Setup();var request=s.a.admission.createAdmissionRequest();var before=snapshot(s.a.db);
        s.a.db.lock();
        for(Records.Work<?> work:List.<Records.Work<?>>of(s.a.admission::status,s.a.admission::pendingRequest,
                s.a.admission::requestAuthorization,()->{s.a.admission.cancelPendingRequest(request.requestId());return null;})) {
            assertFalse(assertThrows(SecurityException.class,work::run) instanceof AdmissionException);
        }
        s.a.db.unlock();unchanged(before,s.a.db);assertEquals(request,s.a.admission.pendingRequest());
    }
    @Test public void validRequestAndCredentialExpirationsAreReportedExactly() throws Exception {
        var s=new AdmissionServiceTest.Setup();var request=s.a.admission.createAdmissionRequest();
        assertEquals(AdmissionService.State.REQUEST_PENDING,s.a.admission.status().state());
        assertEquals(Long.valueOf(request.expiresAt()),s.a.admission.status().requestExpiresAt());
        s.a.clock.set(request.expiresAt());assertEquals(AdmissionService.State.EXPIRED,s.a.admission.status().state());
        assertEquals(Long.valueOf(request.expiresAt()),s.a.admission.status().requestExpiresAt());
        s.a.clock.set(s.admin.clock.get());s.a.admission.cancelPendingRequest(request.requestId());
        var c=s.enroll(s.a);var renewal=s.a.admission.createAdmissionRequest();var before=snapshot(s.a.db);
        var status=s.a.admission.status();assertEquals(AdmissionService.State.ADMITTED,status.state());
        assertEquals(Long.valueOf(c.expiresAt()),status.credentialExpiresAt());assertEquals(Long.valueOf(renewal.expiresAt()),status.requestExpiresAt());
        s.a.clock.set(c.expiresAt());status=s.a.admission.status();assertEquals(AdmissionService.State.EXPIRED,status.state());
        assertEquals(Long.valueOf(c.expiresAt()),status.credentialExpiresAt());assertEquals(Long.valueOf(renewal.expiresAt()),status.requestExpiresAt());unchanged(before,s.a.db);
    }
    @Test public void rejectedRequestRetainsRealExpiry() throws Exception {
        var s=new AdmissionServiceTest.Setup();var request=s.a.admission.createAdmissionRequest();
        s.a.admission.installRejection(s.admin.admission.rejectAdmission(s.admin.admission.reviewAdmissionRequest(request.wire()),true).wire());
        var before=snapshot(s.a.db);var status=s.a.admission.status();
        assertEquals(AdmissionService.State.REJECTED,status.state());assertEquals(Long.valueOf(request.expiresAt()),status.requestExpiresAt());
        assertNull(status.credentialExpiresAt());unchanged(before,s.a.db);
    }
    @Test public void genuinelyAbsentPendingIsNullBeforeAndAfterEnrollmentOrCancellation() throws Exception {
        var fresh=new AdmissionServiceTest.Device();assertNull(fresh.admission.pendingRequest());
        var s=new AdmissionServiceTest.Setup();assertNull(s.a.admission.pendingRequest());
        var request=s.a.admission.createAdmissionRequest();assertEquals(request,s.a.admission.pendingRequest());
        s.a.admission.cancelPendingRequest(request.requestId());assertNull(s.a.admission.pendingRequest());
        s.enroll(s.a);assertNull(s.a.admission.pendingRequest());s.a.admission.requireAdmission();
    }
    @Test public void absentOrWrongRequestCannotCancelOrAuthorizeAndDoesNotMutate() throws Exception {
        var s=new AdmissionServiceTest.Setup();var before=snapshot(s.a.db);
        invalid(s.a.admission::requestAuthorization);invalid(()->{s.a.admission.cancelPendingRequest("absent");return null;});unchanged(before,s.a.db);
        var request=s.a.admission.createAdmissionRequest();var original=s.a.admission.requestAuthorization();original.run();before=snapshot(s.a.db);
        invalid(()->{s.a.admission.cancelPendingRequest("wrong-id");return null;});unchanged(before,s.a.db);
        s.a.admission.cancelPendingRequest(request.requestId());invalid(original);
        s.a.admission.createAdmissionRequest();invalid(original);
    }
    @Test public void canceledRequestCannotProduceResultProof() throws Exception {
        var s=new AdmissionServiceTest.Setup();var request=s.a.admission.createAdmissionRequest();
        String verifier=Bytes.sha256(Bytes.utf8("synthetic verifier"));
        var challenge=new AdmissionChallenge(s.realm.realmId(),request.requestId(),Bytes.sha256(Bytes.utf8(request.wire())),
                AdmissionCodec.random(),verifier,Bytes.sha256(Bytes.utf8("UMBRA-ADMISSION-RESULT-1")),s.a.clock.get(),s.a.clock.get()+30);
        assertNotNull(s.a.admission.proveRequestResult(challenge,verifier));
        s.a.admission.cancelPendingRequest(request.requestId());invalid(()->s.a.admission.proveRequestResult(challenge,verifier));
    }
    private static final class ReadHook implements Records {
        final AdmissionServiceTest.Memory delegate;Runnable pendingRead;
        ReadHook(AdmissionServiceTest.Memory db,Runnable hook){delegate=db;pendingRead=hook;}
        public byte[] get(String b,String k){byte[] v=delegate.get(b,k);if(b.equals("admission")&&k.equals("pending")&&pendingRead!=null){Runnable hook=pendingRead;pendingRead=null;hook.run();}return v;}
        public void put(String b,String k,byte[] v){delegate.put(b,k,v);}
        public void remove(String b,String k){delegate.remove(b,k);}
        public List<String> keys(String b){return delegate.keys(b);}
        public <T>T transaction(Work<T> work)throws Exception{return delegate.transaction(work);}
        public Runnable authorization(){return delegate.authorization();}
        public Object restrictedResourceScope(){return delegate.restrictedResourceScope();}
    }
    @Test public void snapshotStorageFailureIsNotConvertedToInvalidOrAbsence() throws Exception {
        var s=new AdmissionServiceTest.Setup();s.a.admission.createAdmissionRequest();var before=snapshot(s.a.db);
        for(boolean status:List.of(false,true)) {
            var service=new AdmissionService(new ReadHook(s.a.db,()->{throw new IllegalStateException("Synthetic read failure");}),s.a.clock::get);
            assertThrows(IllegalStateException.class,()->{if(status)service.status();else service.pendingRequest();});
        }
        unchanged(before,s.a.db);
    }
    @Test public void lockUnlockDuringReadCannotReturnSnapshotOrPendingFromOldLease() throws Exception {
        var s=new AdmissionServiceTest.Setup();s.a.admission.createAdmissionRequest();
        for(boolean status:List.of(false,true)) {
            var service=new AdmissionService(new ReadHook(s.a.db,()->{s.a.db.lock();s.a.db.unlock();}),s.a.clock::get);
            assertThrows(SecurityException.class,()->{if(status)service.status();else service.pendingRequest();});
        }
    }
}
