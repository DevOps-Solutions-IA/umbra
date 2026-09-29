package app.umbra;

import android.os.Bundle;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyInfo;
import android.security.keystore.KeyProperties;
import androidx.test.platform.app.InstrumentationRegistry;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import org.junit.runner.Description;
import org.junit.runner.notification.RunListener;
import static org.junit.Assert.*;

/** Synthetic, non-authenticated key observation only; NEVER a production Vault substitute. */
public final class PhysicalKeystoreFixture extends RunListener {
    @Override public void testRunStarted(Description description) throws Exception {
        var instrumentation=InstrumentationRegistry.getInstrumentation();
        assertEquals("true",InstrumentationRegistry.getArguments().getString("physicalSafe"));
        assertTrue(BuildConfig.DEBUG);
        assertTrue(Set.of("app.umbra.privatechat.dev","app.umbra.privatechat.offline.dev")
            .contains(instrumentation.getTargetContext().getPackageName()));
        String alias="umbra-physical-synthetic-"+UUID.randomUUID();
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        assertFalse(store.containsAlias(alias));
        byte[] clear=new byte[]{1,3,5,7,9},ciphertext=null,decoded=null;
        String level;
        try {
            KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            var key=generator.generateKey();assertNull(key.getEncoded());
            KeyInfo info=(KeyInfo)SecretKeyFactory.getInstance(key.getAlgorithm(),"AndroidKeyStore").getKeySpec(key,KeyInfo.class);
            level=switch(info.getSecurityLevel()) {
                case KeyProperties.SECURITY_LEVEL_SOFTWARE -> "SOFTWARE";
                case KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TEE";
                case KeyProperties.SECURITY_LEVEL_STRONGBOX -> "STRONGBOX";
                default -> "UNKNOWN";
            };
            Cipher encrypt=Cipher.getInstance("AES/GCM/NoPadding");encrypt.init(Cipher.ENCRYPT_MODE,key);
            ciphertext=encrypt.doFinal(clear);
            Cipher decrypt=Cipher.getInstance("AES/GCM/NoPadding");
            decrypt.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,encrypt.getIV()));
            decoded=decrypt.doFinal(ciphertext);assertArrayEquals(clear,decoded);
            ciphertext[0]^=1;
            byte[] altered=ciphertext;
            assertThrows(javax.crypto.AEADBadTagException.class,()-> {
                Cipher reject=Cipher.getInstance("AES/GCM/NoPadding");
                reject.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,encrypt.getIV()));reject.doFinal(altered);
            });
        } finally {
            Arrays.fill(clear,(byte)0);if(ciphertext!=null)Arrays.fill(ciphertext,(byte)0);if(decoded!=null)Arrays.fill(decoded,(byte)0);
            // Only this call's random alias in this isolated lab UID, never enumerate/delete others.
            if(store.containsAlias(alias))store.deleteEntry(alias);
        }
        assertFalse(store.containsAlias(alias));
        Bundle result=new Bundle();result.putString("physicalKeystoreLevel",level);
        result.putString("physicalKeystoreAuthentication","NON_AUTHENTICATED_LAB_KEY_ONLY");
        instrumentation.sendStatus(0,result);
    }
}
