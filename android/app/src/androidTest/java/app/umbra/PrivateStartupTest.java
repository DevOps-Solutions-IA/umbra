package app.umbra;

import app.umbra.connectivity.ConnectivityService.State;
import app.umbra.core.Bytes;
import app.umbra.crypto.Engine;
import app.umbra.lab.SqliteDeviceRecords;
import app.umbra.transport.RelayClient;
import org.junit.Test;
import static org.junit.Assert.*;

/** Actual SQLite and isolated Android Keystore. Network observation belongs to the host laboratory. */
public final class PrivateStartupTest {
    private static final String ORIGIN="https://startup.umbra.test";
    @Test public void passwordUnlockRemainsDisconnectedAndLockInvalidatesConnectivity() throws Exception {
        DeviceVaultPasswordTest fixture=new DeviceVaultPasswordTest();
        try {
            fixture.before();
            fixture.vault.transaction(()->{fixture.vault.remove("meta","identity");fixture.vault.remove("session","ratchet");return null;});
            Engine engine=new Engine(fixture.vault);engine.initialize("Synthetic startup vault");AdmissionFixture.enroll(engine);
            fixture.vault.createPassword(Bytes.utf8("synthetic private startup password"));
            assertEquals(State.LOCKED_PRIVATE,engine.connectivity().getConnectivityState());
            fixture.gate.unlock();
            assertThrows(SecurityException.class,engine.connectivity()::vaultUnlocked);
            fixture.vault.unlock(Bytes.utf8("synthetic private startup password"));engine.connectivity().vaultUnlocked();
            assertEquals(State.UNLOCKED_OFFLINE,engine.connectivity().getConnectivityState());
            assertThrows(SecurityException.class,()->new RelayClient(ORIGIN,()->true,engine.admission()));
            if(app.umbra.calls.CallPlatform.ENABLED) engine.connectivity().connect(ORIGIN,true);
            fixture.vault.lock();assertEquals(State.LOCKED_PRIVATE,engine.connectivity().getConnectivityState());
            assertFalse(engine.connectivity().isNearbySessionAllowed());
        } finally {fixture.after();}
    }
    @Test public void sqliteRecreationNeverRestoresOnlinePreferenceOrNearbyConsent() throws Exception {
        try(var records=new SqliteDeviceRecords()) {
            Engine engine=new Engine(records);engine.initialize("Synthetic restart");AdmissionFixture.enroll(engine);
            engine.setOnline(true);engine.connectivity().vaultUnlocked();
            var nearby=engine.connectivity().startNearby(true);nearby.checkNearby();
            if(app.umbra.calls.CallPlatform.ENABLED) engine.connectivity().connect(ORIGIN,true);
            records.gate.lock();records.gate.unlock();records.reopen();
            Engine replacement=new Engine(records);
            assertEquals(State.LOCKED_PRIVATE,replacement.connectivity().getConnectivityState());
            replacement.connectivity().vaultUnlocked();
            assertEquals(State.UNLOCKED_OFFLINE,replacement.connectivity().getConnectivityState());
            assertFalse(replacement.connectivity().isNearbySessionAllowed());
            assertThrows(SecurityException.class,nearby::checkNearby);
        }
    }
    @Test public void membershipLossDisconnectsWithoutDeletingHistory() throws Exception {
        try(var records=new SqliteDeviceRecords()) {
            Engine admin=new Engine(new DeviceMemoryRecords()),engine=new Engine(records);
            admin.initialize("Synthetic authority");engine.initialize("Synthetic member");
            engine.admission().installRealmConfig(admin.admission().createAdmissionRealm(true).encode(),true);
            var request=engine.admission().createAdmissionRequest();
            var credential=admin.admission().approveAdmission(admin.admission().reviewAdmissionRequest(request.wire()),true,3600);
            engine.admission().installAdmissionCredential(credential.wire());engine.connectivity().vaultUnlocked();
            if(app.umbra.calls.CallPlatform.ENABLED) engine.connectivity().connect(ORIGIN,true);
            var near=engine.connectivity().startNearby(true);
            byte[] identity=records.get("meta","identity");
            engine.admission().applyRevocation(admin.admission().revokeAdmission(credential.wire(),true,"policy").wire());
            assertFalse(engine.connectivity().isNetworkSessionAllowed());assertFalse(engine.connectivity().canConnect());
            assertThrows(SecurityException.class,near::checkNearby);
            assertArrayEquals(identity,records.get("meta","identity"));
        }
    }
}
