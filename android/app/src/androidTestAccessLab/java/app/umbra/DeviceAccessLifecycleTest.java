package app.umbra;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import app.umbra.accesslab.AccessLifecycleHarness;
import app.umbra.data.Vault;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Positive real Vault access in an opt-in Activity harness, not production hardware authentication. */
@RunWith(AndroidJUnit4.class)
public class DeviceAccessLifecycleTest {
    @Test public void foregroundBackgroundReturnAndRecreationInvalidateRealVaultAccess() throws Exception {
        DeviceVaultPasswordTest fixture=new DeviceVaultPasswordTest();
        try {
            fixture.before(); byte[] password="synthetic readiness lifecycle".getBytes(StandardCharsets.UTF_8);
            try {
                fixture.vault.access().createPassword(password); fixture.gate.unlock();
                fixture.vault.setAutoLockPolicy(240_000); fixture.vault.access().refresh();
                password="synthetic readiness lifecycle".getBytes(StandardCharsets.UTF_8);
                fixture.vault.access().unlock(password);
            } finally {java.util.Arrays.fill(password,(byte)0);}
            assertEquals("OPEN",fixture.vault.access().snapshot().phase().name());
            Runnable old=fixture.vault.authorization();
            try(ActivityScenario<AccessLifecycleHarness> scenario=ActivityScenario.launch(AccessLifecycleHarness.class)) {
                scenario.onActivity(activity->activity.attach(fixture.vault.access()));
                assertEquals(Vault.State.UNLOCKED,fixture.vault.getVaultState());
                assertTrue(fixture.vault.access().snapshot().effectiveRemainingMillis()>0);
                assertArrayEquals(new byte[]{1,2,3,4},fixture.vault.get("meta","identity"));
                scenario.moveToState(Lifecycle.State.CREATED);
                assertEquals(Vault.State.LOCKED,fixture.vault.getVaultState());
                assertThrows(SecurityException.class,old::run);
                scenario.moveToState(Lifecycle.State.RESUMED);
                assertNotEquals("OPEN",fixture.vault.access().snapshot().phase().name());
                scenario.recreate();scenario.onActivity(activity->assertFalse(activity.attached()));
                assertThrows(SecurityException.class,old::run);
                assertThrows(SecurityException.class,()->fixture.vault.get("meta","identity"));
            }
        } finally {fixture.after();}
    }
}
