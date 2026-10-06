package app.umbra.devices;

import app.umbra.core.Bytes;
import java.util.Base64;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.ecc.ECPublicKey;

/** Bounded, domain-separated canonical transcripts; signatures use libsignal. */
final class DeviceTranscript {
    private DeviceTranscript() {}
    static String encode(byte[] raw) { return Base64.getUrlEncoder().withoutPadding().encodeToString(raw); }
    static byte[] decode(String value, int max) {
        if (value.length() > (max * 4L + 2) / 3) throw new SecurityException("Device field too large");
        byte[] raw;
        try { raw = Base64.getUrlDecoder().decode(value); }
        catch (IllegalArgumentException e) { throw new SecurityException("Invalid device encoding", e); }
        if (raw.length > max || !encode(raw).equals(value)) throw new SecurityException("Noncanonical device encoding");
        return raw;
    }
    static String key(String key) throws Exception {
        byte[] raw = decode(key, 33);
        if (raw.length != 33) throw new SecurityException("Invalid device key");
        new ECPublicKey(raw); return key;
    }
    static String id(String key) throws Exception { return Bytes.identity(decode(key(key), 33)); }
    static long number(String value) {
        if (!value.matches("[1-9][0-9]{0,11}")) throw new SecurityException("Invalid device integer");
        return Long.parseLong(value);
    }
    static String sign(IdentityKeyPair pair, String kind, String... fields) {
        byte[] raw = Bytes.utf8("UMBRA-DEVICE-" + kind + "-1\n" + String.join("\n", fields) + "\n");
        return "umbra:device:" + kind + ":1:" + encode(raw) + "." + encode(pair.getPrivateKey().calculateSignature(raw));
    }
    static String[] parse(String value, String kind, int count) throws Exception {
        String prefix = "umbra:device:" + kind + ":1:";
        if (value == null || value.length() > 32000 || !value.startsWith(prefix)) throw new SecurityException("Invalid device transcript");
        String[] parts = value.substring(prefix.length()).split("\\.", -1);
        if (parts.length != 2) throw new SecurityException("Invalid device signature envelope");
        byte[] raw = decode(parts[0], 23000), sig = decode(parts[1], 64);
        String body = Bytes.text(raw);
        if (!body.matches("[\\x20-\\x7E\\n]+")) throw new SecurityException("Invalid device characters");
        String[] lines = body.split("\n", -1);
        if (lines.length != count + 2 || !lines[0].equals("UMBRA-DEVICE-" + kind + "-1") || !lines[count + 1].isEmpty())
            throw new SecurityException("Invalid device fields");
        if (sig.length != 64 || !new ECPublicKey(decode(key(lines[1]), 33)).verifySignature(raw, sig))
            throw new SecurityException("Invalid device signature");
        return lines;
    }
    static void lifetime(String createdText, String expiryText, long max) {
        long created = number(createdText), expires = number(expiryText), now = Bytes.now();
        if (created > now + 300 || expires <= now || expires <= created || expires - created > max)
            throw new SecurityException("Expired device transcript");
    }
}
