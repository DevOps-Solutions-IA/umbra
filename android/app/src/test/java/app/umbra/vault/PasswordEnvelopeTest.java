package app.umbra.vault;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.spec.SecretKeySpec;
import org.junit.Test;
import static org.junit.Assert.*;

public class PasswordEnvelopeTest {
    private static byte[] password() { return "synthetic passphrase only".getBytes(StandardCharsets.UTF_8); }
    @Test public void roundTripRequiresBothIndependentFactors() throws Exception {
        byte[] data = PasswordEnvelope.randomDataKey();
        var device = new SecretKeySpec(PasswordEnvelope.randomDataKey(), "AES");
        byte[] envelope = PasswordEnvelope.seal(password(), data, device, PasswordEnvelope.DEFAULT);
        assertArrayEquals(data, PasswordEnvelope.open(password(), envelope, device));
        assertThrows(GeneralSecurityException.class, () -> PasswordEnvelope.open(new byte[24], envelope, device));
        assertThrows(GeneralSecurityException.class, () -> PasswordEnvelope.open(password(), envelope,
            new SecretKeySpec(PasswordEnvelope.randomDataKey(), "AES")));
        byte[] second = PasswordEnvelope.seal(password(), data, device, PasswordEnvelope.DEFAULT);
        assertFalse(Arrays.equals(envelope, second));
        assertFalse(Arrays.equals(Arrays.copyOfRange(envelope, 28, 44), Arrays.copyOfRange(second, 28, 44)));
    }
    @Test public void everyStoredByteAndTruncationIsAuthenticated() throws Exception {
        var device = new SecretKeySpec(PasswordEnvelope.randomDataKey(), "AES");
        byte[] envelope = PasswordEnvelope.seal(password(), PasswordEnvelope.randomDataKey(), device, PasswordEnvelope.DEFAULT);
        for (int i = 0; i < envelope.length; i++) {
            byte[] changed = envelope.clone(); changed[i] ^= 1;
            assertThrows("Tamper byte " + i, GeneralSecurityException.class, () -> PasswordEnvelope.open(password(), changed, device));
        }
        assertThrows(GeneralSecurityException.class, () -> PasswordEnvelope.open(password(), Arrays.copyOf(envelope, 147), device));
        assertThrows(GeneralSecurityException.class, () -> PasswordEnvelope.open(password(), Arrays.copyOf(envelope, 149), device));
    }
    @Test public void rewrapChangesPasswordWithoutChangingDataKey() throws Exception {
        byte[] data = PasswordEnvelope.randomDataKey();
        var device = new SecretKeySpec(PasswordEnvelope.randomDataKey(), "AES");
        byte[] before = PasswordEnvelope.seal(password(), data, device, PasswordEnvelope.DEFAULT);
        byte[] next = "different synthetic passphrase".getBytes(StandardCharsets.UTF_8);
        byte[] after = PasswordEnvelope.seal(next, PasswordEnvelope.open(password(), before, device), device, PasswordEnvelope.DEFAULT);
        assertArrayEquals(data, PasswordEnvelope.open(next, after, device));
        assertThrows(GeneralSecurityException.class, () -> PasswordEnvelope.open(password(), after, device));
    }
    @Test public void resourceBoundsAndInvalidPasswordsReject() {
        assertThrows(IllegalArgumentException.class, () -> new PasswordEnvelope.Parameters(1024, 3, 4));
        assertThrows(IllegalArgumentException.class, () -> new PasswordEnvelope.Parameters(Integer.MAX_VALUE, 3, 4));
        assertThrows(IllegalArgumentException.class, () -> new PasswordEnvelope.Parameters(65536, 30, 4));
        var device = new SecretKeySpec(new byte[32], "AES");
        assertThrows(GeneralSecurityException.class, () -> PasswordEnvelope.seal(new byte[1025], new byte[32], device, PasswordEnvelope.DEFAULT));
    }
    @Test public void maintainedImplementationMatchesRfc9106Argon2idVector() {
        // RFC 9106 section 5.3's small vector is test-only; production rejects this memory cost.
        byte[] password = new byte[32], salt = new byte[16], secret = new byte[8], ad = new byte[12];
        Arrays.fill(password, (byte)1); Arrays.fill(salt, (byte)2); Arrays.fill(secret, (byte)3); Arrays.fill(ad, (byte)4);
        var params = new org.bouncycastle.crypto.params.Argon2Parameters.Builder(org.bouncycastle.crypto.params.Argon2Parameters.ARGON2_id)
            .withVersion(0x13).withMemoryAsKB(32).withIterations(3).withParallelism(4).withSalt(salt).withSecret(secret).withAdditional(ad).build();
        var generator = new org.bouncycastle.crypto.generators.Argon2BytesGenerator(); generator.init(params);
        byte[] actual = new byte[32]; generator.generateBytes(password, actual);
        assertEquals("0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659", app.umbra.core.Bytes.hex(actual));
        params.clear();
    }

}
