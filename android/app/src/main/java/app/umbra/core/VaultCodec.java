package app.umbra.core;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Standard JCA primitives only. Production keys are non-exportable Android Keystore handles. */
public final class VaultCodec {
    public static final int MAX_VALUE = 2_100_000;
    public record Sealed(byte[] nonce, byte[] ciphertext) {
        public Sealed { nonce = nonce.clone(); ciphertext = ciphertext.clone(); }
        @Override public byte[] nonce() { return nonce.clone(); }
        @Override public byte[] ciphertext() { return ciphertext.clone(); }
    }
    private VaultCodec() {}
    public static String index(SecretKey indexKey, String bucket, String logicalKey) throws GeneralSecurityException {
        if (bucket == null || logicalKey == null || bucket.isEmpty() || bucket.length() > 64 || logicalKey.length() > 256)
            throw new IllegalArgumentException("Invalid record address");
        byte[] b = Bytes.utf8(bucket), k = Bytes.utf8(logicalKey);
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(indexKey);
        mac.update(Bytes.utf8("UMBRA-index-v2\u0000"));
        mac.update(ByteBuffer.allocate(4).putInt(b.length).array()); mac.update(b);
        mac.update(ByteBuffer.allocate(4).putInt(k.length).array()); mac.update(k);
        return Bytes.hex(mac.doFinal());
    }
    private static byte[] aad(int version, String bucket, String index) {
        if (bucket == null || !bucket.matches("[a-z][a-z0-9-]{0,63}") || index == null || !index.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid authenticated address");
        return Bytes.utf8("UMBRA-vault-v" + version + ":" + bucket + ":" + index);
    }
    public static Sealed seal(SecretKey key, String bucket, String index, byte[] clear) throws GeneralSecurityException {
        if (clear == null || clear.length == 0 || clear.length > MAX_VALUE) throw new IllegalArgumentException("Record too large");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key);
        c.updateAAD(aad(2, bucket, index));
        return new Sealed(c.getIV(), c.doFinal(clear));
    }
    public static byte[] open(SecretKey key, String bucket, String index, byte[] nonce, byte[] ciphertext) throws GeneralSecurityException {
        return openVersion(key, 2, bucket, index, nonce, ciphertext);
    }
    /** Only for the atomic v1 -> v2 on-device migration. Never a network fallback. */
    public static byte[] openLegacy(SecretKey key, String bucket, String index, byte[] nonce, byte[] ciphertext) throws GeneralSecurityException {
        return openVersion(key, 1, bucket, index, nonce, ciphertext);
    }
    private static byte[] openVersion(SecretKey key, int version, String bucket, String index, byte[] nonce, byte[] ciphertext) throws GeneralSecurityException {
        if (nonce == null || nonce.length != 12 || ciphertext == null || ciphertext.length < 17 || ciphertext.length > MAX_VALUE + 16)
            throw new GeneralSecurityException("Invalid encrypted record");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce));
        c.updateAAD(aad(version, bucket, index)); return c.doFinal(ciphertext);
    }
}
