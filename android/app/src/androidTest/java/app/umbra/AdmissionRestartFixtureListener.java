package app.umbra;

import androidx.test.platform.app.InstrumentationRegistry;
import app.umbra.admission.AdmissionService;
import app.umbra.crypto.Engine;
import app.umbra.lab.SqliteDeviceRecords;
import android.os.Bundle;
import org.junit.runner.Description;
import org.junit.runner.notification.RunListener;

/** Test APK only: actual process termination after commit, not during commit or a Vault fallback. */
public final class AdmissionRestartFixtureListener extends RunListener {
    private void status(String value) { Bundle b=new Bundle();b.putString("admissionRestart",value);InstrumentationRegistry.getInstrumentation().sendStatus(0,b); }
    @Override public void testRunStarted(Description description)throws Exception {
        String phase=InstrumentationRegistry.getArguments().getString("admissionPhase","");
        if(phase.equals("prepare")) {
            SqliteDeviceRecords records=new SqliteDeviceRecords("admission-restart",false);
            Engine member=new Engine(records),admin=new Engine(new DeviceMemoryRecords());
            member.initialize("Synthetic restart member");admin.initialize("Synthetic restart admin");
            member.admission().installRealmConfig(admin.admission().createAdmissionRealm(true).encode(),true);
            var request=member.admission().createAdmissionRequest();
            var credential=admin.admission().approveAdmission(admin.admission().reviewAdmissionRequest(request.wire()),true,3600);
            member.admission().installAdmissionCredential(credential.wire());
            member.admission().applyRevocation(admin.admission().revokeAdmission(credential.wire(),true,"policy").wire());
            status("READY");
            Thread.sleep(90_000);
            throw new AssertionError("Host did not force-stop the prepared process");
        } else if(phase.equals("verify")) {
            try(SqliteDeviceRecords records=new SqliteDeviceRecords("admission-restart",true)) {
                Engine member=new Engine(records);
                if(member.admission().getAdmissionState()!=AdmissionService.State.REVOKED)throw new AssertionError("Revocation lost after process death");
                try { member.admission().requireAdmission();throw new AssertionError("Revoked member authenticated after restart"); }
                catch(SecurityException expected) { /* required denial */ }
                if(!records.keys("outbox").isEmpty())throw new AssertionError("Pending private delivery after revoked restart");
                status("PASS");
            }
        } else throw new AssertionError("Unknown admission restart phase");
    }
}
