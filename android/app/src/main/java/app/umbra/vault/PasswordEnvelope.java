package app.umbra.vault;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/** Fixed-length authenticated format. No password verifier or recovery copy is persisted. */
public final class PasswordEnvelope {
    public record Parameters(int memoryKiB, int iterations, int parallelism) {
        public Parameters {
            if (memoryKiB < 65536 || memoryKiB > 131072 || memoryKiB % 1024 != 0
                    || iterations < 3 || iterations > 6 || parallelism != 4)
                throw new IllegalArgumentException("Unsupported vault KDF profile");
        }
    }
    public static final Parameters DEFAULT = new Parameters(65536, 3, 4);
    public static final int SIZE = 148;
    private static final int HEADER = 60, MAGIC = 0x554d5057;
    private static final SecureRandom RANDOM = new SecureRandom();
    private PasswordEnvelope() {}

    /** Password is exact UTF-8 bytes (no normalization). Caller must erase its input. */
    public static byte[] seal(byte[] password, byte[] dataKey, SecretKey deviceKey, Parameters profile)
            throws GeneralSecurityException {
        if (dataKey == null || dataKey.length != 32) throw failure();
        byte[] salt = random(16), id = random(16);
        byte[] header = ByteBuffer.allocate(HEADER).putInt(MAGIC).putInt(1).putInt(0x13)
            .putInt(profile.memoryKiB()).putInt(profile.iterations()).putInt(profile.parallelism())
            .putInt(32).put(salt).put(id).array();
        byte[] derived = null, inner = null;
        try {
            derived = derive(password, salt, profile);
            inner = encrypt(new SecretKeySpec(derived, "AES"), dataKey, header, "password");
            byte[] outer = encrypt(deviceKey, inner, header, "device");
            return ByteBuffer.allocate(SIZE).put(header).put(outer).array();
        } finally { erase(derived); erase(inner); }
    }
    public static byte[] open(byte[] password, byte[] envelope, SecretKey deviceKey)
            throws GeneralSecurityException {
        byte[] derived = null, inner = null;
        try {
            if (envelope == null || envelope.length != SIZE) throw failure();
            ByteBuffer in = ByteBuffer.wrap(envelope);
            if (in.getInt() != MAGIC || in.getInt() != 1 || in.getInt() != 0x13) throw failure();
            Parameters profile = new Parameters(in.getInt(), in.getInt(), in.getInt());
            if (in.getInt() != 32) throw failure();
            byte[] salt = new byte[16]; in.get(salt);
            byte[] header = Arrays.copyOf(envelope, HEADER);
            // Authenticate metadata with device key before allocating Argon2 memory.
            inner = decrypt(deviceKey, Arrays.copyOfRange(envelope, HEADER, SIZE), header, "device");
            if (inner.length != 60) throw failure();
            derived = derive(password, salt, profile);
            byte[] key = decrypt(new SecretKeySpec(derived, "AES"), inner, header, "password");
            if (key.length != 32) { erase(key); throw failure(); }
            return key;
        } catch (GeneralSecurityException | IllegalArgumentException e) { throw failure(); }
        finally { erase(derived); erase(inner); }
    }
    /** Serial KDF bounds concurrent memory. No lowered production/test profile exists. */
    static synchronized byte[] derive(byte[] password, byte[] salt, Parameters profile) throws GeneralSecurityException {
        if (password == null || password.length < 12 || password.length > 1024 || salt.length != 16) throw failure();
        long needed = profile.memoryKiB() * 1024L + 32L * 1024 * 1024;
        Runtime runtime = Runtime.getRuntime();
        if (runtime.maxMemory() < needed) throw failure();
        // Track only this operation's bounded blocks so exceptional paths can clear them too.
        java.util.ArrayList<Argon2BytesGenerator.Block> blocks = new java.util.ArrayList<>(profile.memoryKiB());
        Argon2BytesGenerator.BlockPool pool = new Argon2BytesGenerator.BlockPool() {
            public Argon2BytesGenerator.Block allocate() {
                var block = new Argon2BytesGenerator.Block(); blocks.add(block); return block;
            }
            public void deallocate(Argon2BytesGenerator.Block block) { block.clear(); }
        };
        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13).withMemoryAsKB(profile.memoryKiB())
            .withIterations(profile.iterations()).withParallelism(profile.parallelism()).withSalt(salt).withBlockPool(pool).build();
        byte[] output = new byte[32];
        try {
            Argon2BytesGenerator generator = new Argon2BytesGenerator(); generator.init(params);
            generator.generateBytes(password, output); return output;
        } catch (RuntimeException | OutOfMemoryError e) { erase(output); throw failure(); }
        finally { for (var block : blocks) block.clear(); blocks.clear(); params.clear(); }
    }
    private static byte[] encrypt(SecretKey key, byte[] input, byte[] header, String layer) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key); // provider-generated fresh nonce, including AndroidKeyStore
        cipher.updateAAD(aad(header, layer));
        byte[] encrypted = cipher.doFinal(input);
        if (cipher.getIV().length != 12) throw failure();
        return ByteBuffer.allocate(12 + encrypted.length).put(cipher.getIV()).put(encrypted).array();
    }
    private static byte[] decrypt(SecretKey key, byte[] input, byte[] header, String layer) throws GeneralSecurityException {
        if (input.length < 28) throw failure();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, input, 0, 12));
        cipher.updateAAD(aad(header, layer)); return cipher.doFinal(input, 12, input.length - 12);
    }
    private static byte[] aad(byte[] header, String layer) {
        byte[] domain = ("UMBRA-password-v1/" + layer + "\u0000").getBytes(StandardCharsets.US_ASCII);
        return ByteBuffer.allocate(domain.length + header.length).put(domain).put(header).array();
    }
    public static Parameters parameters(byte[] envelope) {
        if (envelope == null || envelope.length != SIZE) throw new IllegalArgumentException("Invalid protection format");
        ByteBuffer header = ByteBuffer.wrap(envelope);
        if (header.getInt() != MAGIC || header.getInt() != 1 || header.getInt() != 0x13)
            throw new IllegalArgumentException("Invalid protection format");
        return new Parameters(header.getInt(), header.getInt(), header.getInt());
    }
    public static byte[] randomDataKey() { return random(32); }
    private static byte[] random(int length) { byte[] bytes = new byte[length]; RANDOM.nextBytes(bytes); return bytes; }
    public static void erase(byte[] bytes) { if (bytes != null) Arrays.fill(bytes, (byte) 0); }
    private static GeneralSecurityException failure() { return new GeneralSecurityException("Vault unlock failed"); }
}
