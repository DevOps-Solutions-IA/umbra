package app.umbra.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public final class Bytes {
    private static final SecureRandom RANDOM = new SecureRandom();
    private Bytes() {}
    public static byte[] random(int count) {
        if (count < 1 || count > 1_000_000) throw new IllegalArgumentException("Invalid random length");
        byte[] out = new byte[count]; RANDOM.nextBytes(out); return out;
    }
    public static int positiveId() { return RANDOM.nextInt(Integer.MAX_VALUE - 1) + 1; }
    public static byte[] utf8(String value) {
        try {
            java.nio.ByteBuffer encoded = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .encode(java.nio.CharBuffer.wrap(value));
            byte[] result = new byte[encoded.remaining()]; encoded.get(result); return result;
        } catch (java.nio.charset.CharacterCodingException e) { throw new IllegalArgumentException("Invalid Unicode", e); }
    }
    public static String text(byte[] value) {
        try { return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(value)).toString(); }
        catch (java.nio.charset.CharacterCodingException e) { throw new IllegalArgumentException("Invalid UTF-8", e); }
    }
    public static String b64(byte[] value) { return Base64.getEncoder().encodeToString(value); }
    public static byte[] unb64(String value) { return unb64(value, 1_000_000); }
    public static byte[] unb64(String value, int maximumCharacters) {
        if (maximumCharacters < 0 || maximumCharacters > 2_100_000 || value.length() > maximumCharacters)
            throw new IllegalArgumentException("Encoding too large");
        byte[] decoded = Base64.getDecoder().decode(value);
        if (!Base64.getEncoder().encodeToString(decoded).equals(value)) throw new IllegalArgumentException("Noncanonical base64");
        return decoded;
    }
    public static String token() { return Base64.getUrlEncoder().withoutPadding().encodeToString(random(32)); }
    public static String hex(byte[] bytes) {
        char[] alphabet = "0123456789abcdef".toCharArray(); char[] result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) { result[i * 2] = alphabet[(bytes[i] >>> 4) & 15]; result[i * 2 + 1] = alphabet[bytes[i] & 15]; }
        return new String(result);
    }
    public static String sha256(byte[] value) {
        try { return hex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception e) { throw new IllegalStateException("SHA-256 unavailable", e); }
    }
    public static boolean equal(byte[] a, byte[] b) { return MessageDigest.isEqual(a, b); }
    public static String identity(byte[] publicKey) { return sha256(publicKey); }
    public static long now() { return System.currentTimeMillis() / 1000; }
    public static String safetyCode(String first, String second) {
        if (!first.matches("[a-f0-9]{64}") || !second.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Identity format");
        String a = first.compareTo(second) < 0 ? first : second;
        String b = first.compareTo(second) < 0 ? second : first;
        return sha256(utf8("UMBRA-VERIFY-v1:" + a + ":" + b));
    }
}
