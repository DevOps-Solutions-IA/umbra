package app.umbra.verification;

import app.umbra.core.Bytes;
import java.math.BigInteger;

/** Representations of the existing symmetric identity fingerprint, not human identity proof. */
public final class Verification {
    private Verification() {}
    public static String numeric(String first, String second) {
        String decimal = new BigInteger(Bytes.safetyCode(first, second), 16).toString(10);
        return "0".repeat(78 - decimal.length()) + decimal;
    }
    public static String qr(String first, String second) {
        String code = Bytes.safetyCode(first, second);
        String a = first.compareTo(second) < 0 ? first : second, b = first.compareTo(second) < 0 ? second : first;
        return "umbra:verify:1:" + a + ":" + b + ":" + code;
    }
    public static boolean matches(String first, String second, String comparison) {
        if (comparison == null || comparison.length() > 512) return false;
        if (comparison.startsWith("umbra:")) return Bytes.equal(Bytes.utf8(qr(first, second)), Bytes.utf8(comparison));
        String normalized = comparison.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s", "");
        return Bytes.equal(Bytes.utf8(Bytes.safetyCode(first, second)), Bytes.utf8(normalized)) ||
            Bytes.equal(Bytes.utf8(numeric(first, second)), Bytes.utf8(normalized));
    }
}
