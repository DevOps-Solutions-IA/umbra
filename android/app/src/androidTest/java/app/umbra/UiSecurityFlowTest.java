package app.umbra;

import android.content.Context;
import android.content.ContextWrapper;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import app.umbra.connectivity.ConnectivityService;
import app.umbra.core.AccessGate;
import app.umbra.core.Bytes;
import app.umbra.crypto.Engine;
import app.umbra.data.Vault;
import app.umbra.ui.flow.AdmissionFlow;
import app.umbra.ui.flow.VaultFlow;
import app.umbra.ui.model.AccessStep;
import app.umbra.ui.model.AdmissionPresentation;
import app.umbra.ui.model.PasswordPolicy;
import java.io.File;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/**
 * The UI's vault/admission/connectivity flow ({@link VaultFlow}, {@link AdmissionFlow}) over actual SQLite, Vault,
 * JCA and AndroidKeyStore keys created only in the test UID (via {@link DeviceVaultPasswordTest}'s fixture).
 * Does not call or weaken production key preparation; the Activity's Android authentication prompt is not driven here.
 */
@RunWith(AndroidJUnit4.class)
public class UiSecurityFlowTest {
    private static byte[] typed(String text) throws Exception { return PasswordPolicy.encode(text); }
    private static boolean erased(byte[] b) { return Arrays.equals(new byte[b.length], b); }
    /** Fresh legacy vault with a real Engine identity, as an installation before v1 would have. */
    private static DeviceVaultPasswordTest legacy() throws Exception {
        DeviceVaultPasswordTest fixture = new DeviceVaultPasswordTest(); fixture.before();
        fixture.vault.transaction(() -> { fixture.vault.remove("meta", "identity"); fixture.vault.remove("session", "ratchet"); return null; });
        new Engine(fixture.vault).initialize("Synthetic UI vault");
        return fixture;
    }
    private static Vault reopen(DeviceVaultPasswordTest fixture, AccessGate gate) {
        Context base = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getTargetContext();
        File file = fixture.file;
        return new Vault(new ContextWrapper(base) {
            @Override public File getDatabasePath(String name) { return file; }
            @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory, DatabaseErrorHandler handler) {
                return SQLiteDatabase.openDatabase(file.getPath(), factory, SQLiteDatabase.CREATE_IF_NECESSARY, handler);
            }
        }, gate);
    }

    @Test public void enrollUnlockWrongPasswordChangeLockAndRestartThroughTheUiFlow() throws Exception {
        DeviceVaultPasswordTest fixture = legacy();
        try {
            assertEquals(AccessStep.LEGACY_ENROLLMENT, VaultFlow.step(fixture.vault));
            byte[] first = typed("synthetic ui password one");
            VaultFlow.createPassword(fixture.vault, first);
            assertTrue("caller buffer erased", erased(first));
            assertThrows("enrollment finishes locked", AccessGate.LockedException.class, fixture.gate::requireUnlocked);

            fixture.gate.unlock(); // Android authentication alone
            assertEquals(AccessStep.PASSWORD_UNLOCK, VaultFlow.step(fixture.vault));
            byte[] wrong = typed("synthetic wrong password");
            assertThrows(SecurityException.class, () -> VaultFlow.unlock(fixture.vault, wrong, 240_000, () -> 0L));
            assertTrue(erased(wrong));
            assertEquals(AccessStep.PASSWORD_UNLOCK, VaultFlow.step(fixture.vault));
            assertThrows(SecurityException.class, () -> fixture.vault.get("meta", "identity"));

            fixture.gate.unlock();
            byte[] right = typed("synthetic ui password one");
            Engine engine = VaultFlow.unlock(fixture.vault, right, 120_000, () -> 0L);
            assertTrue(erased(right));
            assertEquals(AccessStep.OPEN, VaultFlow.step(fixture.vault));
            assertEquals(120_000, fixture.vault.getAutoLockPolicy());
            assertThrows("auto-lock is configured only while locked", IllegalStateException.class, () -> fixture.vault.setAutoLockPolicy(60_000));
            assertTrue(engine.initialized());
            assertEquals("unlock never connects", ConnectivityService.State.UNLOCKED_OFFLINE, engine.connectivity().getConnectivityState());
            assertFalse(engine.connectivity().isNetworkSessionAllowed());
            assertFalse(engine.connectivity().isNearbySessionAllowed());

            byte[] current = typed("synthetic ui password one"), next = typed("synthetic ui password two");
            VaultFlow.changePassword(fixture.vault, current, next);
            assertTrue(erased(current)); assertTrue(erased(next));
            assertThrows("change finishes locked", AccessGate.LockedException.class, fixture.gate::requireUnlocked);
            assertEquals(ConnectivityService.State.LOCKED_PRIVATE, engine.connectivity().getConnectivityState());

            // Process restart: a new Vault/gate starts locked and needs Android plus the new password.
            fixture.vault.close();
            AccessGate restarted = new AccessGate(); restarted.unlock();
            fixture.vault = reopen(fixture, restarted); fixture.gate = restarted;
            assertEquals(AccessStep.PASSWORD_UNLOCK, VaultFlow.step(fixture.vault));
            assertEquals("process-local policy returns to the default", 240_000, fixture.vault.getAutoLockPolicy());
            assertThrows(SecurityException.class, () -> VaultFlow.unlock(fixture.vault, typed("synthetic ui password one"), 240_000, () -> 0L));
            restarted.unlock();
            Engine reopened = VaultFlow.unlock(fixture.vault, typed("synthetic ui password two"), 240_000, () -> 0L);
            assertTrue(reopened.initialized());
            assertEquals(ConnectivityService.State.UNLOCKED_OFFLINE, reopened.connectivity().getConnectivityState());
        } finally { fixture.after(); }
    }

    @Test public void lockDuringOrAfterUnlockInvalidatesOldEnginesAndSnapshots() throws Exception {
        DeviceVaultPasswordTest fixture = legacy();
        try {
            VaultFlow.createPassword(fixture.vault, typed("synthetic ui password one"));
            fixture.gate.unlock();
            Engine engine = VaultFlow.unlock(fixture.vault, typed("synthetic ui password one"), 240_000, () -> 0L);
            AdmissionFlow.Snapshot before = AdmissionFlow.read(engine.admission(), Bytes.now());
            assertEquals("UNCONFIGURED", before.state());
            fixture.vault.lock(); // Activity lock / onPause / vault auto-lock
            assertThrows("a stale callback cannot read admission", SecurityException.class, () -> AdmissionFlow.read(engine.admission(), Bytes.now()));
            assertEquals(ConnectivityService.State.LOCKED_PRIVATE, engine.connectivity().getConnectivityState());
            assertThrows(SecurityException.class, () -> engine.connectivity().startNearby(true));
            // Unlocking again with Android alone does not revive the previous engine's lease.
            fixture.gate.unlock();
            assertThrows(SecurityException.class, () -> fixture.vault.get("meta", "identity"));
            assertThrows(SecurityException.class, engine.connectivity()::vaultUnlocked);
        } finally { fixture.after(); }
    }

    @Test public void admissionOnTheRealVaultNeverConnectsOrVerifies() throws Exception {
        DeviceVaultPasswordTest fixture = legacy();
        try {
            VaultFlow.createPassword(fixture.vault, typed("synthetic ui password one"));
            fixture.gate.unlock();
            Engine engine = VaultFlow.unlock(fixture.vault, typed("synthetic ui password one"), 240_000, () -> 0L);
            AdmissionFixture.enroll(engine);
            AdmissionFlow.Snapshot admitted = AdmissionFlow.read(engine.admission(), Bytes.now());
            assertEquals("ADMITTED", admitted.state());
            assertTrue(AdmissionPresentation.of(admitted.state(), false, false).admitted());
            assertNotNull(admitted.credentialExpiresAt());
            assertEquals("admission does not connect", ConnectivityService.State.UNLOCKED_OFFLINE, engine.connectivity().getConnectivityState());
            assertFalse(engine.connectivity().isNetworkSessionAllowed());
            assertFalse(engine.connectivity().isNearbySessionAllowed());
            assertTrue("admission creates no contact or verification", engine.contacts().isEmpty());
            // A new process has no grants even though the credential persists in the encrypted vault.
            fixture.vault.close();
            AccessGate restarted = new AccessGate(); restarted.unlock();
            fixture.vault = reopen(fixture, restarted); fixture.gate = restarted;
            Engine reopened = VaultFlow.unlock(fixture.vault, typed("synthetic ui password one"), 240_000, () -> 0L);
            assertEquals("ADMITTED", AdmissionFlow.read(reopened.admission(), Bytes.now()).state());
            assertEquals(ConnectivityService.State.UNLOCKED_OFFLINE, reopened.connectivity().getConnectivityState());
            assertFalse(reopened.connectivity().isNearbySessionAllowed());
        } finally { fixture.after(); }
    }
}
