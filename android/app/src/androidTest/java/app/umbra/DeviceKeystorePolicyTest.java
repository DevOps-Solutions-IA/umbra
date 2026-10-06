package app.umbra;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.Bundle;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyInfo;
import android.security.keystore.KeyProperties;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.umbra.data.Vault;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.security.KeyStore;
import java.util.UUID;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Real AndroidKeystore capability checks. No injected keys or policy changes in production. */
@RunWith(AndroidJUnit4.class)
public class DeviceKeystorePolicyTest {
    @Test public void productionHardwarePolicyMatchesActualKeystoreSecurityLevel() throws Exception {
        assertTrue("Synthetic debug fixture only", BuildConfig.DEBUG);
        String alias = "umbra.instrumentation.probe." + UUID.randomUUID();
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        try {
            // Test-only capability probe, never used by Vault or Engine to protect any content.
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            SecretKey probe = generator.generateKey();
            KeyInfo info = (KeyInfo) SecretKeyFactory.getInstance(probe.getAlgorithm(), "AndroidKeyStore")
                .getKeySpec(probe, KeyInfo.class);
            int level = info.getSecurityLevel();
            boolean hardware = level == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ||
                level == KeyProperties.SECURITY_LEVEL_STRONGBOX;
            Bundle evidence = new Bundle(); evidence.putString("keystoreProbeSecurityLevel", Integer.toString(level));
            evidence.putString("keystoreProbeHardwareReported", Boolean.toString(hardware));
            InstrumentationRegistry.getInstrumentation().sendStatus(0, evidence);
            Method policy = Vault.class.getDeclaredMethod("requireHardware", SecretKey.class);
            policy.setAccessible(true);
            if (hardware) policy.invoke(null, probe);
            else {
                InvocationTargetException rejection = assertThrows(InvocationTargetException.class,
                    () -> policy.invoke(null, probe));
                assertTrue("Production must reject a software or unknown-level key", rejection.getCause() instanceof SecurityException);
            }
        } finally { store.deleteEntry(alias); }
    }
    @Test public void productionPreparationRejectsMissingDeviceCredentialWithoutCreatingDatabase() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue("Synthetic debug fixture only", BuildConfig.DEBUG);
        assertFalse("Use a disposable AVD without a PIN", context.getSystemService(KeyguardManager.class).isDeviceSecure());
        assertFalse("Refuse existing app data", context.getDatabasePath("umbra.db").exists());
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        assertFalse("Refuse existing vault key", store.containsAlias("umbra.vault.v1"));
        assertFalse("Refuse existing index key", store.containsAlias("umbra.index.v2"));
        try {
            assertThrows(Exception.class, () -> Vault.prepareKey(context));
            assertFalse("Authentication failure must not initialize a database", context.getDatabasePath("umbra.db").exists());
        } finally {
            // Preconditions prove these aliases did not predate this synthetic test.
            Vault.destroyKey();
        }
    }
}
