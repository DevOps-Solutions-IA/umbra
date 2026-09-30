package app.umbra;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import app.umbra.admission.*;
import app.umbra.core.Bytes;
import app.umbra.crypto.Engine;
import app.umbra.lab.SqliteDeviceRecords;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Actual Android SQLite and actual Vault/AndroidKeyStore test fixtures; no hardware claim. */
@RunWith(AndroidJUnit4.class)
public final class DeviceAdmissionTest {
    @Test public void sqliteAdmissionSnapshotsPreserveCorruptionAndDistinguishAbsentPending() throws Exception {
        for(String damagedKey:new String[]{"credential","pending","realm"}) {
            try(var records=new SqliteDeviceRecords()) {
                Engine engine=new Engine(records);engine.initialize("Synthetic snapshot member");
                var admission=engine.admission();assertNull(admission.pendingRequest());
                admission.createAdmissionRealm(true);var first=admission.createAdmissionRequest();
                var stale=admission.requestAuthorization();stale.run();
                assertThrows(AdmissionException.class,()->admission.cancelPendingRequest("wrong-id"));
                admission.cancelPendingRequest(first.requestId());assertNull(admission.pendingRequest());
                assertThrows(AdmissionException.class,stale::run);
                assertThrows(AdmissionException.class,admission::requestAuthorization);
                assertThrows(AdmissionException.class,()->admission.cancelPendingRequest(first.requestId()));
                var request=admission.createAdmissionRequest();
                var credential=admission.approveAndInstallOwnAdmission(admission.reviewAdmissionRequest(request.wire()),true,3600);
                var renewal=admission.createAdmissionRequest();var valid=admission.status();
                assertEquals(AdmissionService.State.ADMITTED,valid.state());
                assertEquals(Long.valueOf(credential.expiresAt()),valid.credentialExpiresAt());
                assertEquals(Long.valueOf(renewal.expiresAt()),valid.requestExpiresAt());
                byte[] corrupt=Bytes.utf8("synthetic corrupt admission record");
                records.transaction(()->{records.put("admission",damagedKey,corrupt);return null;});
                records.reopen();var reopened=new Engine(records).admission();var invalid=reopened.status();
                assertEquals(AdmissionService.State.INVALID,invalid.state());
                assertNull(invalid.requestExpiresAt());assertNull(invalid.credentialExpiresAt());
                assertArrayEquals(corrupt,records.get("admission",damagedKey));
                if(damagedKey.equals("pending"))assertThrows(AdmissionException.class,reopened::pendingRequest);
                records.gate.lock();
                assertThrows(SecurityException.class,reopened::status);assertThrows(SecurityException.class,reopened::pendingRequest);
                records.gate.unlock();assertArrayEquals(corrupt,records.get("admission",damagedKey));
                assertEquals(AdmissionService.State.INVALID,reopened.status().state());
            }
        }
    }
    @Test public void encryptedAuthorityCannotApproveAfterVaultLock() throws Exception {
        DeviceVaultPasswordTest fixture=new DeviceVaultPasswordTest();
        try {
            fixture.before();
            fixture.vault.transaction(() -> { fixture.vault.remove("meta","identity");fixture.vault.remove("session","ratchet");return null; });
            Engine admin=new Engine(fixture.vault),device=new Engine(new DeviceMemoryRecords());
            admin.initialize("Synthetic admission authority");device.initialize("Synthetic new member");
            RealmConfig realm=admin.admission().createAdmissionRealm(true);
            device.admission().installRealmConfig(realm.encode(),true);
            var request=device.admission().createAdmissionRequest();
            byte[] seed=fixture.vault.get("admission-secret","authority");
            try(var rows=fixture.vault.getReadableDatabase().rawQuery("SELECT value FROM records",null)) {
                while(rows.moveToNext()) assertFalse(java.util.Arrays.equals(seed,rows.getBlob(0)));
            }
            java.util.Arrays.fill(seed,(byte)0);
            fixture.vault.createPassword(Bytes.utf8("synthetic admission vault password"));
            fixture.gate.unlock();fixture.vault.unlock(Bytes.utf8("synthetic admission vault password"));
            var review=admin.admission().reviewAdmissionRequest(request.wire());
            fixture.vault.lock();
            assertThrows(SecurityException.class,()->admin.admission().approveAdmission(review,true,3600));
            fixture.gate.unlock();
            assertThrows(SecurityException.class,()->admin.admission().reviewAdmissionRequest(request.wire()));
            fixture.vault.unlock(Bytes.utf8("synthetic admission vault password"));
            assertThrows(SecurityException.class,()->admin.admission().approveAdmission(review,true,3600));
            var credential=admin.admission().approveAdmission(admin.admission().reviewAdmissionRequest(request.wire()),true,3600);
            device.admission().installAdmissionCredential(credential.wire());
            assertEquals(AdmissionService.State.ADMITTED,device.admission().getAdmissionState());
        } finally { fixture.after(); }
    }
    @Test public void sqliteRevocationPersistsAndDoesNotDeleteMessages() throws Exception {
        // This adapter's plaintext synthetic SQLite is not evidence of encrypted production storage.
        try(var a=new SqliteDeviceRecords();var b=new SqliteDeviceRecords();var adminRecords=new SqliteDeviceRecords()) {
            Engine first=new Engine(a),second=new Engine(b),admin=new Engine(adminRecords);
            first.initialize("Synthetic A");second.initialize("Synthetic B");admin.initialize("Synthetic admin");
            RealmConfig realm=admin.admission().createAdmissionRealm(true);
            for(Engine device:new Engine[]{first,second}) {
                device.admission().installRealmConfig(realm.encode(),true);
                var request=device.admission().createAdmissionRequest();
                device.admission().installAdmissionCredential(admin.admission().approveAdmission(
                    admin.admission().reviewAdmissionRequest(request.wire()),true,3600).wire());
            }
            first.importCard(second.createCard());second.importCard(first.createCard());
            String code=Bytes.safetyCode(first.id(),second.id());first.verify(second.id(),code);second.verify(first.id(),code);
            first.sendText(second.id(),"synthetic admitted Android message",600);
            second.receive(first.outbox().get(0).getJSONObject("envelope"));
            var revocation=admin.admission().revokeAdmission(first.admission().requireAdmission().wire(),true,"policy");
            first.admission().applyRevocation(revocation.wire());second.admission().applyRevocation(revocation.wire());
            a.reopen();b.reopen();
            assertEquals(AdmissionService.State.REVOKED,new Engine(a).admission().getAdmissionState());
            assertThrows(SecurityException.class,()->new Engine(a).sendText(second.id(),"denied",600));
            assertEquals(1,new Engine(b).messages(first.id()).size());
            assertEquals(AdmissionService.State.ADMITTED,new Engine(b).admission().getAdmissionState());
        }
    }
    @Test public void sqliteApprovalFailureRollsBackConsumption() throws Exception {
        try(var adminRecords=new SqliteDeviceRecords();var memberRecords=new SqliteDeviceRecords()) {
            Engine admin=new Engine(adminRecords),member=new Engine(memberRecords);
            admin.initialize("Synthetic admin");member.initialize("Synthetic member");
            member.admission().installRealmConfig(admin.admission().createAdmissionRealm(true).encode(),true);
            var request=member.admission().createAdmissionRequest();
            var review=admin.admission().reviewAdmissionRequest(request.wire());
            adminRecords.failBucket="admission-nonces";
            assertThrows(IllegalStateException.class,()->admin.admission().approveAdmission(review,true,3600));
            adminRecords.failBucket=null;adminRecords.reopen();
            assertTrue(adminRecords.keys("admission-decisions").isEmpty());
            member.admission().installAdmissionCredential(admin.admission().approveAdmission(
                admin.admission().reviewAdmissionRequest(request.wire()),true,3600).wire());
            member.admission().requireAdmission();
        }
    }
}
