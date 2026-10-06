package app.umbra;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import app.umbra.admission.*;
import app.umbra.core.Bytes;
import app.umbra.crypto.Engine;
import app.umbra.lab.SqliteDeviceRecords;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Synthetic isolated Android SQLite/Vault tests; no physical hardware or network claim. */
@RunWith(AndroidJUnit4.class)
public final class DeviceProvisioningTest {
    private static final String ORIGIN="https://synthetic-provisioning.example.test";
    private static RealmConfig realm() throws Exception {
        Engine admin=new Engine(new DeviceMemoryRecords());
        admin.initialize("Synthetic provisioning authority");
        return admin.admission().createAdmissionRealm(true);
    }
    @Test public void sqliteFailedProvisioningRollsBackAndRecreationDoesNotConnect() throws Exception {
        try(var records=new SqliteDeviceRecords()) {
            Engine engine=new Engine(records); engine.initialize("Synthetic provisioned member");
            var service=engine.admission().provisioning();
            var review=service.review(ORIGIN,realm().encode());
            records.failBucket="meta";
            assertThrows(IllegalStateException.class,()->service.install(review,true));
            records.failBucket=null; records.reopen();
            Engine recreated=new Engine(records);
            assertEquals(AdmissionService.State.UNCONFIGURED,recreated.admission().getAdmissionState());
            assertEquals("",recreated.profile().getString("relay"));
            assertTrue(records.keys("admission-secret").isEmpty());
            var fresh=recreated.admission().provisioning();
            fresh.install(fresh.review(ORIGIN,realm().encode()),true);
            records.reopen();
            var persisted=new Engine(records);
            assertTrue(persisted.admission().provisioning().status().configured());
            assertFalse(persisted.admission().provisioning().status().registered());
            assertEquals(AdmissionService.State.NOT_ADMITTED,persisted.admission().getAdmissionState());
            assertFalse(persisted.connectivity().isNetworkSessionAllowed());
            assertFalse(persisted.connectivity().isNearbySessionAllowed());
        }
    }
    @Test public void encryptedPasswordVaultPreservesConfigurationAndRejectsStaleReview() throws Exception {
        DeviceVaultPasswordTest fixture=new DeviceVaultPasswordTest();
        byte[] password=Bytes.utf8("synthetic provisioning vault password");
        try {
            fixture.before();
            fixture.vault.transaction(()->{fixture.vault.remove("meta","identity");fixture.vault.remove("session","ratchet");return null;});
            Engine engine=new Engine(fixture.vault);engine.initialize("Synthetic encrypted member");
            var service=engine.admission().provisioning();
            var review=service.review(ORIGIN,realm().encode());
            service.install(review,true);
            fixture.vault.createPassword(password);
            assertThrows(SecurityException.class,service::status);
            fixture.gate.unlock();fixture.vault.unlock(password);
            assertThrows(SecurityException.class,()->service.install(review,true));
            var persisted=new Engine(fixture.vault);
            assertEquals(ORIGIN,persisted.admission().provisioning().status().exactOrigin());
            assertEquals(AdmissionService.State.NOT_ADMITTED,persisted.admission().provisioning().status().admissionState());
            assertFalse(persisted.admission().provisioning().status().registered());
            assertFalse(persisted.connectivity().isNetworkSessionAllowed());
            assertFalse(persisted.connectivity().isNearbySessionAllowed());
            fixture.vault.lock();
            assertThrows(SecurityException.class,()->persisted.admission().provisioning().status());
        } finally {java.util.Arrays.fill(password,(byte)0);fixture.after();}
    }
}
